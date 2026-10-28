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
import java.util.UUID;

/** Export/restore common Windows user-data locations without copying installed game files. */
public final class SaveDataArchive {
    private static final String MANIFEST = "saves.json";
    private static final String PROFILE_PREFIX = "profile/";
    private static final String[] SAVE_FOLDERS = {
        "Saved Games", "Documents", "AppData/Roaming", "AppData/Local", "AppData/LocalLow"
    };
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 250_000;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;

    private SaveDataArchive() {}

    public static boolean exportTo(Context context, Container container, Uri destination) {
        if (context == null || container == null || destination == null || container.getRootDir() == null) return false;
        File userDir = container.getUserDir();
        if (!userDir.isDirectory()) return false;
        int exportedFolders = 0;
        try (OutputStream raw = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (raw == null) return false;
            try (ZstdCompressorOutputStream zstd = new ZstdCompressorOutputStream(new BufferedOutputStream(raw, BUFFER_SIZE), 3);
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                writeManifest(tar, container);
                TarArchiveEntry profile = new TarArchiveEntry(PROFILE_PREFIX, TarConstants.LF_DIR);
                profile.setMode(0755);
                tar.putArchiveEntry(profile);
                tar.closeArchiveEntry();
                String rootPath = userDir.getCanonicalPath();
                for (String relative : SAVE_FOLDERS) {
                    File source = new File(userDir, relative);
                    if (!source.exists() && !Files.isSymbolicLink(source.toPath())) continue;
                    if (!addNode(tar, source, PROFILE_PREFIX + relative, rootPath)) return false;
                    exportedFolders++;
                }
                if (exportedFolders == 0) return false;
                tar.finish();
            }
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    public static boolean importTo(Context context, Uri source, Container container) {
        if (context == null || source == null || container == null || container.getRootDir() == null) return false;
        File userDir = container.getUserDir();
        if (!userDir.isDirectory() && !userDir.mkdirs()) return false;
        File staging = new File(container.getRootDir(), ".wh-save-restore-" + UUID.randomUUID());
        if (!staging.mkdirs()) return false;
        boolean success = false;
        try {
            if (!extractTo(context, source, staging)) return false;
            File stagedProfile = new File(staging, "profile");
            if (!stagedProfile.isDirectory()) return false;
            if (!mergeDirectory(stagedProfile, userDir, userDir.getCanonicalPath())) return false;
            success = true;
            return true;
        }
        catch (Exception e) {
            return false;
        }
        finally {
            FileUtils.delete(staging);
            if (!success) {
                // The restore is intentionally overlay-only; existing data remains unless a file
                // was already replaced before an I/O failure.
            }
        }
    }

    private static boolean extractTo(Context context, Uri source, File staging) throws IOException {
        Set<String> seen = new HashSet<>();
        int entries = 0;
        byte[] manifestBytes = null;
        long available;
        try {
            available = new StatFs(staging.getAbsolutePath()).getAvailableBytes();
        }
        catch (Exception e) {
            return false;
        }
        long reserve = Math.min(128L * 1024L * 1024L, Math.max(16L * 1024L * 1024L, available / 20L));
        long maxBytes = Math.max(0, available - reserve);
        long written = 0;
        String root = staging.getCanonicalPath();

        try (InputStream raw = context.getContentResolver().openInputStream(source)) {
            if (raw == null) return false;
            try (ZstdCompressorInputStream zstd = new ZstdCompressorInputStream(new BufferedInputStream(raw, BUFFER_SIZE));
                 TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                TarArchiveEntry entry;
                while ((entry = tar.getNextTarEntry()) != null) {
                    if (++entries > MAX_ENTRIES || !tar.canReadEntryData(entry)) return false;
                    if (MANIFEST.equals(entry.getName())) {
                        if (manifestBytes != null || entry.isDirectory() || entry.getSize() > MAX_MANIFEST_BYTES) return false;
                        manifestBytes = readLimited(tar, (int)Math.max(0, entry.getSize()), MAX_MANIFEST_BYTES);
                        if (manifestBytes == null) return false;
                        continue;
                    }
                    String relative = safeProfilePath(entry.getName());
                    if (relative == null) return false;
                    if (relative.isEmpty()) continue;
                    if (!seen.add(relative)) return false;
                    File target = new File(staging, relative);
                    if (!within(root, target) || hasSymlinkParent(staging, target)) return false;
                    if (entry.isSymbolicLink()) {
                        if (!safeSymlink(root, target, entry.getLinkName())) return false;
                        File parent = target.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                        if (target.exists() || Files.isSymbolicLink(target.toPath())) return false;
                        FileUtils.symlink(entry.getLinkName(), target.getAbsolutePath());
                        if (!Files.isSymbolicLink(target.toPath())) return false;
                    }
                    else if (entry.isDirectory()) {
                        if (Files.isSymbolicLink(target.toPath())) return false;
                        if (!target.isDirectory() && !target.mkdirs()) return false;
                    }
                    else if (entry.isFile() && !Files.isSymbolicLink(target.toPath())) {
                        File parent = target.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
                            int count;
                            while ((count = tar.read(buffer)) != -1) {
                                if (count > maxBytes - written) return false;
                                written += count;
                                if (!hasEnoughSpace(staging, count)) return false;
                                out.write(buffer, 0, count);
                            }
                        }
                        target.setLastModified(entry.getLastModifiedDate().getTime());
                        FileUtils.chmod(target, 0771);
                    }
                    else return false;
                }
            }
        }
        if (manifestBytes == null || !validManifest(manifestBytes)) return false;
        return new File(staging, "profile").isDirectory();
    }

    private static boolean addNode(TarArchiveOutputStream tar, File source, String archiveName, String root) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) {
            String linkName = FileUtils.readSymlink(source);
            if (!safeSymlink(root, source, linkName)) return false;
            TarArchiveEntry link = new TarArchiveEntry(archiveName, TarConstants.LF_SYMLINK);
            link.setLinkName(linkName);
            link.setMode(0777);
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
            return true;
        }
        if (source.isDirectory()) {
            TarArchiveEntry dir = new TarArchiveEntry(archiveName + "/", TarConstants.LF_DIR);
            dir.setMode(0755);
            tar.putArchiveEntry(dir);
            tar.closeArchiveEntry();
            return addDirectoryContents(tar, source, archiveName + "/", root);
        }
        if (!source.isFile()) return false;
        TarArchiveEntry file = (TarArchiveEntry)tar.createArchiveEntry(source, archiveName);
        tar.putArchiveEntry(file);
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = in.read(buffer)) != -1) tar.write(buffer, 0, count);
        }
        tar.closeArchiveEntry();
        return true;
    }

    private static boolean addDirectoryContents(TarArchiveOutputStream tar, File directory, String base, String root) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return directory.isDirectory();
        for (File child : children) {
            if (!addNode(tar, child, base + child.getName(), root)) return false;
        }
        return true;
    }

    private static void writeManifest(TarArchiveOutputStream tar, Container container) throws IOException, JSONException {
        JSONObject json = new JSONObject();
        json.put("format", "winlator-save-backup");
        json.put("version", VERSION);
        json.put("containerName", container.getName());
        json.put("exportedAt", System.currentTimeMillis());
        org.json.JSONArray folders = new org.json.JSONArray();
        for (String folder : SAVE_FOLDERS) folders.put(folder);
        json.put("folders", folders);
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(MANIFEST);
        entry.setSize(bytes.length);
        entry.setMode(0600);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    static String safeProfilePath(String entryName) {
        if (entryName == null || entryName.indexOf('\\') >= 0 || entryName.startsWith("/")) return null;
        String name = entryName.endsWith("/") ? entryName.substring(0, entryName.length() - 1) : entryName;
        if ("profile".equals(name)) return "";
        if (!name.startsWith(PROFILE_PREFIX)) return null;
        String relative = name.substring(PROFILE_PREFIX.length());
        if (relative.isEmpty()) return "";
        for (String part : relative.split("/")) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return null;
        }
        return "profile/" + relative;
    }

    private static boolean validManifest(byte[] bytes) {
        try {
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return "winlator-save-backup".equals(json.optString("format")) && json.optInt("version", -1) == VERSION;
        }
        catch (JSONException e) {
            return false;
        }
    }

    private static boolean mergeDirectory(File source, File destination, String userRoot) throws IOException {
        File[] children = source.listFiles();
        if (children == null) return false;
        if (!destination.exists() && !destination.mkdirs()) return false;
        for (File child : children) {
            File target = new File(destination, child.getName());
            if (!within(userRoot, target)) return false;
            if (Files.isSymbolicLink(child.toPath())) {
                String linkName = FileUtils.readSymlink(child);
                if (!safeSymlink(userRoot, target, linkName)) return false;
                if (target.exists() || Files.isSymbolicLink(target.toPath())) FileUtils.delete(target);
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                FileUtils.symlink(linkName, target.getAbsolutePath());
                if (!Files.isSymbolicLink(target.toPath())) return false;
            }
            else if (child.isDirectory()) {
                if (Files.isSymbolicLink(target.toPath())) {
                    if (!within(userRoot, target)) return false;
                }
                else if (target.exists() && !target.isDirectory()) FileUtils.delete(target);
                if (!mergeDirectory(child, target, userRoot)) return false;
            }
            else if (child.isFile()) {
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                File temp = new File(parent, target.getName() + ".restore-" + UUID.randomUUID());
                try (InputStream in = new BufferedInputStream(new FileInputStream(child), BUFFER_SIZE);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(temp), BUFFER_SIZE)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                if (target.exists() || Files.isSymbolicLink(target.toPath())) FileUtils.delete(target);
                if (!temp.renameTo(target)) {
                    FileUtils.delete(temp);
                    return false;
                }
                target.setLastModified(child.lastModified());
                FileUtils.chmod(target, 0771);
            }
            else return false;
        }
        return true;
    }

    static boolean safeSymlink(String root, File link, String target) {
        if (target == null || target.isEmpty() || target.indexOf('\\') >= 0 || target.startsWith("/")) return false;
        try {
            File resolved = new File(link.getParentFile(), target).getCanonicalFile();
            return resolved.getPath().equals(root) || resolved.getPath().startsWith(root + File.separator);
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean within(String root, File target) throws IOException {
        String path = target.getCanonicalPath();
        return path.equals(root) || path.startsWith(root + File.separator);
    }

    private static boolean hasSymlinkParent(File root, File target) {
        File parent = target.getParentFile();
        while (parent != null && !parent.equals(root)) {
            if (Files.isSymbolicLink(parent.toPath())) return true;
            parent = parent.getParentFile();
        }
        return parent == null;
    }

    private static boolean hasEnoughSpace(File destination, long bytes) {
        try {
            return bytes <= new StatFs(destination.getAbsolutePath()).getAvailableBytes();
        }
        catch (Exception e) {
            return true;
        }
    }

    private static byte[] readLimited(InputStream in, int expected, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(Math.max(expected, 32), limit));
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > limit) return null;
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
