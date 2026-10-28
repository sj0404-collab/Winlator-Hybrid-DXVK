package com.winlator.core;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;

import com.winlator.container.Container;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

/**
 * Portable, streaming backup format for a complete Winlator container.
 * The archive includes installed Windows games, the Wine prefix (and therefore saves),
 * shortcuts and container settings. Global Wine/graphics runtimes remain app-managed.
 */
public final class ContainerBackupArchive {
    private static final String MANIFEST_NAME = "backup.json";
    private static final String CONTAINER_PREFIX = "container/";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 500_000;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int BUFFER_SIZE = 128 * 1024;

    private ContainerBackupArchive() {}

    public static boolean exportTo(Context context, Container container, Uri destination) {
        if (context == null || container == null || destination == null || container.getRootDir() == null) return false;
        if (!container.getRootDir().isDirectory()) return false;

        try (OutputStream raw = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (raw == null) return false;
            try (ZstdCompressorOutputStream zstd = new ZstdCompressorOutputStream(new BufferedOutputStream(raw, BUFFER_SIZE), 3);
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                writeManifest(tar, container);
                TarArchiveEntry root = new TarArchiveEntry(CONTAINER_PREFIX, TarConstants.LF_DIR);
                root.setMode(0755);
                tar.putArchiveEntry(root);
                tar.closeArchiveEntry();
                String containerRoot = container.getRootDir().getCanonicalPath();
                if (!addDirectory(tar, container.getRootDir(), CONTAINER_PREFIX, containerRoot)) return false;
                tar.finish();
            }
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    public static boolean importFrom(Context context, Uri source, File destination) {
        if (context == null || source == null || destination == null) return false;
        if (!destination.isDirectory() && !destination.mkdirs()) return false;
        if (destination.list() != null && destination.list().length != 0) return false;

        Set<String> seenPaths = new HashSet<>();
        int entryCount = 0;
        long totalBytes = 0;
        long availableAtStart;
        try {
            availableAtStart = new StatFs(destination.getAbsolutePath()).getAvailableBytes();
        }
        catch (Exception e) {
            return false;
        }
        long reserveBytes = Math.min(256L * 1024L * 1024L, Math.max(32L * 1024L * 1024L, availableAtStart / 20L));
        long importLimit = Math.max(0, availableAtStart - reserveBytes);
        byte[] manifestBytes = null;
        String rootPath;
        try {
            rootPath = destination.getCanonicalPath();
        }
        catch (IOException e) {
            return false;
        }

        try (InputStream raw = context.getContentResolver().openInputStream(source)) {
            if (raw == null) return false;
            try (ZstdCompressorInputStream zstd = new ZstdCompressorInputStream(new BufferedInputStream(raw, BUFFER_SIZE));
                 TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                TarArchiveEntry entry;
                byte[] buffer = new byte[BUFFER_SIZE];
                while ((entry = tar.getNextTarEntry()) != null) {
                    if (++entryCount > MAX_ENTRIES || !tar.canReadEntryData(entry)) return false;
                    String entryName = entry.getName();
                    if (MANIFEST_NAME.equals(entryName)) {
                        if (manifestBytes != null || entry.isDirectory() || entry.getSize() > MAX_MANIFEST_BYTES) return false;
                        manifestBytes = readLimited(tar, (int)Math.max(0, entry.getSize()), MAX_MANIFEST_BYTES);
                        if (manifestBytes == null) return false;
                        continue;
                    }

                    String relative = getSafeContainerRelativePath(entryName);
                    if (relative == null) return false;
                    if (relative.isEmpty()) continue; // container root marker
                    if (!seenPaths.add(relative)) return false;

                    File target = new File(destination, relative);
                    if (!isWithinRoot(rootPath, target) || hasSymlinkParent(destination, target)) return false;

                    if (entry.isSymbolicLink()) {
                        if (!isSafeLink(rootPath, target, entry.getLinkName())) return false;
                        File parent = target.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                        if (target.exists() || Files.isSymbolicLink(target.toPath())) return false;
                        FileUtils.symlink(entry.getLinkName(), target.getAbsolutePath());
                        if (!Files.isSymbolicLink(target.toPath())) return false;
                        continue;
                    }

                    if (entry.isDirectory()) {
                        if (Files.isSymbolicLink(target.toPath())) return false;
                        if (!target.isDirectory() && !target.mkdirs()) return false;
                        continue;
                    }

                    if (!entry.isFile() || Files.isSymbolicLink(target.toPath())) return false;
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
                        int count;
                        while ((count = tar.read(buffer)) != -1) {
                            if (count > importLimit - totalBytes) return false;
                            totalBytes += count;
                            if (!hasEnoughSpace(destination, count)) return false;
                            out.write(buffer, 0, count);
                        }
                    }
                    target.setLastModified(entry.getLastModifiedDate().getTime());
                    FileUtils.chmod(target, 0771);
                }
            }

            if (manifestBytes == null || !isValidManifest(manifestBytes)) return false;
            File config = new File(destination, ".container");
            File winePrefix = new File(destination, ".wine/drive_c");
            return config.isFile() && winePrefix.isDirectory();
        }
        catch (Exception e) {
            return false;
        }
    }

    private static void writeManifest(TarArchiveOutputStream tar, Container container) throws IOException, JSONException {
        JSONObject manifest = new JSONObject();
        manifest.put("format", "winlator-container-backup");
        manifest.put("version", FORMAT_VERSION);
        manifest.put("containerName", container.getName());
        manifest.put("containerId", container.id);
        manifest.put("exportedAt", System.currentTimeMillis());
        byte[] data = manifest.toString().getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(MANIFEST_NAME);
        entry.setSize(data.length);
        entry.setMode(0600);
        tar.putArchiveEntry(entry);
        tar.write(data);
        tar.closeArchiveEntry();
    }

    private static boolean addDirectory(TarArchiveOutputStream tar, File directory, String basePath, String containerRoot) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return directory.isDirectory();
        for (File child : children) {
            String name = basePath + child.getName();
            if (Files.isSymbolicLink(child.toPath())) {
                String linkName = FileUtils.readSymlink(child);
                if (!isSafeLink(containerRoot, child, linkName)) return false;
                TarArchiveEntry link = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
                link.setLinkName(linkName);
                link.setMode(0777);
                tar.putArchiveEntry(link);
                tar.closeArchiveEntry();
            }
            else if (child.isDirectory()) {
                TarArchiveEntry entry = (TarArchiveEntry)tar.createArchiveEntry(child, name + "/");
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
                if (!addDirectory(tar, child, name + "/", containerRoot)) return false;
            }
            else if (child.isFile()) {
                TarArchiveEntry entry = (TarArchiveEntry)tar.createArchiveEntry(child, name);
                tar.putArchiveEntry(entry);
                try (InputStream in = new BufferedInputStream(new FileInputStream(child), BUFFER_SIZE)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = in.read(buffer)) != -1) tar.write(buffer, 0, count);
                }
                tar.closeArchiveEntry();
            }
        }
        return true;
    }

    static String getSafeContainerRelativePath(String entryName) {
        if (entryName == null || entryName.indexOf('\\') >= 0 || entryName.startsWith("/")) return null;
        String name = entryName.endsWith("/") ? entryName.substring(0, entryName.length() - 1) : entryName;
        if (CONTAINER_PREFIX.substring(0, CONTAINER_PREFIX.length() - 1).equals(name)) return "";
        if (!name.startsWith(CONTAINER_PREFIX)) return null;
        String relative = name.substring(CONTAINER_PREFIX.length());
        if (relative.isEmpty()) return "";
        for (String part : relative.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return null;
        }
        return relative;
    }

    private static boolean isWithinRoot(String rootPath, File target) throws IOException {
        String path = target.getCanonicalPath();
        return path.equals(rootPath) || path.startsWith(rootPath + File.separator);
    }

    private static boolean hasSymlinkParent(File root, File target) {
        File parent = target.getParentFile();
        while (parent != null && !parent.equals(root)) {
            if (Files.isSymbolicLink(parent.toPath())) return true;
            parent = parent.getParentFile();
        }
        return parent == null;
    }

    static boolean isSafeLink(String rootPath, File link, String linkName) {
        if (linkName == null || linkName.isEmpty() || linkName.indexOf('\\') >= 0) return false;
        // Wine's conventional Z: mapping points at the Android process root. Permit only that
        // well-known mapping; all other links must resolve inside the imported container.
        String normalizedLink = link.getPath().replace(File.separatorChar, '/');
        if ("/".equals(linkName) && normalizedLink.endsWith("/.wine/dosdevices/z:")) return true;
        if (linkName.startsWith("/") || linkName.matches("^[A-Za-z]:.*")) return false;
        try {
            File resolved = new File(link.getParentFile(), linkName).getCanonicalFile();
            return resolved.getPath().equals(rootPath) || resolved.getPath().startsWith(rootPath + File.separator);
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean hasEnoughSpace(File destination, long bytesWritten) {
        try {
            long available = new StatFs(destination.getAbsolutePath()).getAvailableBytes();
            return bytesWritten <= available;
        }
        catch (Exception e) {
            return true;
        }
    }

    private static byte[] readLimited(InputStream in, int expectedSize, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(Math.max(expectedSize, 32), limit));
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > limit) return null;
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static boolean isValidManifest(byte[] bytes) {
        try {
            JSONObject manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return "winlator-container-backup".equals(manifest.optString("format"))
                && FORMAT_VERSION == manifest.optInt("version", -1)
                && !manifest.optString("containerName", "").trim().isEmpty();
        }
        catch (JSONException e) {
            return false;
        }
    }
}
