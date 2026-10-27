package com.winlator.devtools;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.winlator.R;
import com.winlator.container.Container;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Optional, per-container Windows developer tools. Nothing is downloaded until the user asks. */
public final class DeveloperToolsInstaller {
    public enum Tool { NODE, OPENCODE }

    public interface Listener {
        void onProgress(String message, int percent);
        void onComplete(File installDir);
        void onError(String message);
    }

    private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/anomalyco/opencode/releases/latest";
    private static final String NODE_INDEX = "https://nodejs.org/dist/index.json";
    private static final long MAX_ARCHIVE_SIZE = 512L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_SIZE = 1024L * 1024L * 1024L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private DeveloperToolsInstaller() {}

    public static File getToolDir(Container container, Tool tool) {
        String folder = tool == Tool.NODE ? "Node" : "OpenCode";
        return new File(container.getRootDir(), ".wine/drive_c/WinlatorTools/" + folder);
    }

    public static boolean isInstalled(Container container, Tool tool) {
        File dir = getToolDir(container, tool);
        if (tool == Tool.NODE) return new File(dir, "node.exe").isFile();
        return getOpenCodeExecutable(container) != null;
    }

    public static File getOpenCodeExecutable(Container container) {
        return findFile(getToolDir(container, Tool.OPENCODE), "opencode.exe");
    }

    public static void install(Context context, Container container, Tool tool, Listener listener) {
        final Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File archive = null;
            try {
                File cacheDir = new File(appContext.getCacheDir(), "developer-tools");
                if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new Exception("Cannot create temporary download folder");
                if (tool == Tool.NODE) {
                    NodeRelease release = getLatestNodeRelease();
                    archive = new File(cacheDir, "node-" + release.version + "-win-x64.zip");
                    downloadVerified(release.url, archive, release.sha256, listener, "Node.js " + release.version);
                    File destination = getToolDir(container, tool);
                    File stage = new File(destination.getParentFile(), ".Node-installing");
                    deleteRecursively(stage);
                    extractZip(archive, stage, "node-" + release.version + "-win-x64/");
                    requireFile(stage, "node.exe");
                    replaceDirectory(stage, destination);
                    finish(listener, destination);
                } else {
                    OpenCodeRelease release = getLatestOpenCodeRelease();
                    archive = new File(cacheDir, "opencode-" + release.version + "-windows-x64.zip");
                    downloadVerified(release.url, archive, release.sha256, listener, "OpenCode " + release.version);
                    File destination = getToolDir(container, tool);
                    File stage = new File(destination.getParentFile(), ".OpenCode-installing");
                    deleteRecursively(stage);
                    extractZip(archive, stage, null);
                    if (findFile(stage, "opencode.exe") == null) throw new Exception("The verified OpenCode archive did not contain opencode.exe");
                    replaceDirectory(stage, destination);
                    finish(listener, destination);
                }
            } catch (Exception e) {
                fail(listener, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } finally {
                if (archive != null) archive.delete();
            }
        });
    }

    private static NodeRelease getLatestNodeRelease() throws Exception {
        String json = readText(NODE_INDEX);
        JSONArray releases = new JSONArray(json);
        JSONObject selected = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject item = releases.getJSONObject(i);
            String ltsName = item.optString("lts", "false");
            if (!"false".equalsIgnoreCase(ltsName) && !ltsName.isEmpty()) {
                selected = item;
                break;
            }
        }
        if (selected == null) throw new Exception("No current Node.js LTS release was found");
        String version = selected.getString("version");
        if (!version.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) throw new Exception("Invalid Node.js LTS version metadata");
        String archiveName = "node-" + version + "-win-x64.zip";
        String base = "https://nodejs.org/dist/" + version + "/";
        String sums = readText(base + "SHASUMS256.txt");
        String expected = findSha256(sums, archiveName);
        return new NodeRelease(version, base + archiveName, expected);
    }

    private static OpenCodeRelease getLatestOpenCodeRelease() throws Exception {
        JSONObject release = new JSONObject(readText(GITHUB_RELEASE_API));
        JSONArray assets = release.getJSONArray("assets");
        // The baseline x64 build avoids requiring newer x86 instructions from the emulated CPU.
        String wanted = "opencode-windows-x64-baseline.zip";
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!wanted.equals(asset.optString("name"))) continue;
            String digest = asset.optString("digest");
            String version = release.optString("tag_name", "latest");
            if (!version.matches("[A-Za-z0-9._-]{1,100}")) throw new Exception("Invalid OpenCode release tag");
            if (!digest.matches("sha256:[0-9a-fA-F]{64}")) throw new Exception("GitHub did not provide a valid SHA-256 digest for OpenCode");
            String url = asset.getString("browser_download_url");
            if (!url.startsWith("https://github.com/anomalyco/opencode/releases/download/")) throw new Exception("OpenCode download URL is not from the official repository");
            return new OpenCodeRelease(version, url, digest.substring(7).toLowerCase(Locale.ROOT));
        }
        throw new Exception("The latest OpenCode release has no Windows x64 baseline archive");
    }

    private static String readText(String urlString) throws Exception {
        HttpURLConnection connection = open(urlString);
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(urlString).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Winlator-Hybrid-Developer-Tools");
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/json, */*");
        return connection;
    }

    private static void downloadVerified(String url, File destination, String expectedSha256, Listener listener, String label) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) throw new Exception("Download failed (HTTP " + response + ")");
            long length = connection.getContentLengthLong();
            if (length > MAX_ARCHIVE_SIZE) throw new Exception("Archive exceeds the 512 MiB safety limit");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(destination)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_ARCHIVE_SIZE) throw new Exception("Archive exceeds the 512 MiB safety limit");
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    if (listener != null && length > 0) {
                        int percent = (int)Math.min(90, total * 90L / length);
                        progress(listener, "Downloading " + label + "…", percent);
                    }
                }
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                destination.delete();
                throw new Exception("SHA-256 verification failed; the archive was discarded");
            }
            progress(listener, "Verified download; extracting…", 92);
        } finally {
            connection.disconnect();
        }
    }

    private static void extractZip(File archive, File destination, String stripPrefix) throws Exception {
        if (!destination.isDirectory() && !destination.mkdirs()) throw new Exception("Cannot create tools installation folder");
        String destinationPath = destination.getCanonicalPath() + File.separator;
        long extracted = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.startsWith("/") || entryName.contains("../") || entryName.equals("..")) throw new Exception("Unsafe path in tool archive");
                if (stripPrefix != null) {
                    if (!entryName.startsWith(stripPrefix)) continue;
                    entryName = entryName.substring(stripPrefix.length());
                    if (entryName.isEmpty()) continue;
                }
                File out = new File(destination, entryName);
                String outPath = out.getCanonicalPath();
                if (!outPath.startsWith(destinationPath)) throw new Exception("Unsafe path in tool archive");
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new Exception("Cannot create extracted directory");
                } else {
                    File parent = out.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new Exception("Cannot create extracted directory");
                    try (FileOutputStream stream = new FileOutputStream(out)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            extracted += read;
                            if (extracted > MAX_EXTRACTED_SIZE) throw new Exception("Extracted archive exceeds the 1 GiB safety limit");
                            stream.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static File findFile(File dir, String name) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (file.isFile() && name.equalsIgnoreCase(file.getName())) return file;
            if (file.isDirectory()) {
                File match = findFile(file, name);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String findSha256(String sums, String wantedName) throws Exception {
        for (String line : sums.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.endsWith(" " + wantedName) || trimmed.endsWith("*" + wantedName)) {
                String[] fields = trimmed.split("\\s+");
                if (fields.length >= 2 && fields[0].matches("[0-9a-fA-F]{64}")) return fields[0].toLowerCase(Locale.ROOT);
            }
        }
        throw new Exception("No SHA-256 checksum was published for " + wantedName);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static void replaceDirectory(File stage, File destination) throws Exception {
        File backup = new File(destination.getParentFile(), "." + destination.getName() + "-previous");
        deleteRecursively(backup);
        if (destination.exists() && !destination.renameTo(backup)) throw new Exception("Cannot replace the previous tool installation");
        if (!stage.renameTo(destination)) {
            if (backup.exists()) backup.renameTo(destination);
            throw new Exception("Cannot activate the new tool installation");
        }
        deleteRecursively(backup);
    }

    public static void remove(Container container, Tool tool) {
        deleteRecursively(getToolDir(container, tool));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static void requireFile(File directory, String name) throws Exception {
        if (!new File(directory, name).isFile()) throw new Exception("The Node.js archive did not contain " + name);
    }

    private static void progress(Listener listener, String message, int percent) {
        if (listener != null) MAIN.post(() -> listener.onProgress(message, percent));
    }

    private static void finish(Listener listener, File dir) {
        if (listener != null) MAIN.post(() -> listener.onComplete(dir));
    }

    private static void fail(Listener listener, String message) {
        if (listener != null) MAIN.post(() -> listener.onError(message));
    }

    private static final class NodeRelease {
        final String version, url, sha256;
        NodeRelease(String version, String url, String sha256) { this.version = version; this.url = url; this.sha256 = sha256; }
    }

    private static final class OpenCodeRelease {
        final String version, url, sha256;
        OpenCodeRelease(String version, String url, String sha256) { this.version = version; this.url = url; this.sha256 = sha256; }
    }
}
