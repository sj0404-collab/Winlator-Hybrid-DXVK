package com.winlator.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.Executors;

public abstract class FileUtils {
    public static byte[] read(Context context, String assetFile) {
        try (InputStream inStream = context.getAssets().open(assetFile)) {
            return StreamUtils.copyToByteArray(inStream);
        }
        catch (IOException e) {
            return null;
        }
    }

    public static byte[] read(File file) {
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(file))) {
            return StreamUtils.copyToByteArray(inStream);
        }
        catch (IOException e) {
            return null;
        }
    }

    public static String readString(Context context, String assetFile) {
        return new String(read(context, assetFile), StandardCharsets.UTF_8);
    }

    public static String readString(File file) {
        return new String(read(file), StandardCharsets.UTF_8);
    }

    public static String readString(Context context, Uri uri) {
        StringBuilder sb = new StringBuilder();
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
        catch (IOException e) {
            return null;
        }
    }

    public static boolean write(File file, byte[] data) {
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(data, 0, data.length);
            return true;
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static boolean writeString(File file, String data) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            bw.write(data);
            bw.flush();
            return true;
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void symlink(File linkTarget, File linkFile) {
        symlink(linkTarget.getAbsolutePath(), linkFile.getAbsolutePath());
    }

    public static void symlink(String linkTarget, String linkFile) {
        try {
            (new File(linkFile)).delete();
            Os.symlink(linkTarget, linkFile);
        }
        catch (ErrnoException e) {}
    }

    public static boolean isSymlink(File file) {
        return Files.isSymbolicLink(file.toPath());
    }

    public static boolean delete(File targetFile) {
        if (targetFile == null) return false;
        if (targetFile.isDirectory()) {
            if (!isSymlink(targetFile)) if (!clear(targetFile)) return false;
        }
        return targetFile.delete();
    }

    public static boolean clear(File targetFile) {
        if (targetFile == null) return false;
        if (targetFile.isDirectory()) {
            File[] files = targetFile.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!delete(file)) return false;
                }
            }
        }
        return true;
    }

    public static boolean isEmpty(File targetFile) {
        if (targetFile == null) return true;
        if (targetFile.isDirectory()) {
            String[] files = targetFile.list();
            return files == null || files.length == 0;
        }
        else return targetFile.length() == 0;
    }

    public static boolean isAscendantOf(File srcFile, File dstFile) {
        File parent = dstFile.getParentFile();
        while (parent != null) {
            if (parent.equals(srcFile)) return true;
            parent = parent.getParentFile();
        }
        return false;
    }

    public static boolean copy(File srcFile, File dstFile) {
        return copy(srcFile, dstFile, null);
    }

    public static boolean copy(File srcFile, File dstFile, Callback<File> callback) {
        if (isSymlink(srcFile)) return true;
        if (srcFile.isDirectory()) {
            if (isAscendantOf(srcFile, dstFile) || (!dstFile.exists() && !dstFile.mkdirs())) return false;
            if (callback != null) callback.call(dstFile);

            String[] filenames = srcFile.list();
            if (filenames != null) {
                for (String filename : filenames) {
                    if (!copy(new File(srcFile, filename), new File(dstFile, filename), callback)) {
                        return false;
                    }
                }
            }
        }
        else {
            File parent = dstFile.getParentFile();
            if (!srcFile.exists() || (parent != null && !parent.exists() && !parent.mkdirs())) return false;

            try {
                FileChannel inChannel = (new FileInputStream(srcFile)).getChannel();
                FileChannel outChannel = (new FileOutputStream(dstFile)).getChannel();
                inChannel.transferTo(0, inChannel.size(), outChannel);
                inChannel.close();
                outChannel.close();

                if (callback != null) callback.call(dstFile);
                return dstFile.exists();
            }
            catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    public static void copy(Context context, String assetFile, File dstFile) {
        if (isDirectory(context, assetFile)) {
            if (!dstFile.isDirectory()) dstFile.mkdirs();
            try {
                String[] filenames = context.getAssets().list(assetFile);
                for (String filename : filenames) {
                    String relativePath = StringUtils.addEndSlash(assetFile)+filename;
                    if (isDirectory(context, relativePath)) {
                        copy(context, relativePath, new File(dstFile, filename));
                    }
                    else copy(context, relativePath, dstFile);
                }
            }
            catch (IOException e) {}
        }
        else {
            if (dstFile.isDirectory()) dstFile = new File(dstFile, FileUtils.getName(assetFile));
            File parent = dstFile.getParentFile();
            if (!parent.isDirectory()) parent.mkdirs();
            try (InputStream inStream = context.getAssets().open(assetFile);
                 BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(dstFile), StreamUtils.BUFFER_SIZE)) {
                StreamUtils.copy(inStream, outStream);
            }
            catch (IOException e) {}
        }
    }

    public static ArrayList<String> readLines(File file) {
        return readLines(file, false);
    }

    public static ArrayList<String> readLines(File file, boolean skipEmptyLines) {
        ArrayList<String> lines = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file)) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
            String line;
            while ((line = reader.readLine()) != null) {
                if (skipEmptyLines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                }
                lines.add(line);
            }
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return lines;
    }

    public static String getName(String path) {
        if (path == null) return "";
        path = StringUtils.removeEndSlash(path);
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(index + 1);
    }

    public static String getBasename(String path) {
        return getName(path).replaceFirst("\\.[^\\.]+$", "");
    }

    public static String getDirname(String path) {
        if (path == null) return "";
        path = StringUtils.removeEndSlash(path);
        int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(0, index);
    }

    public static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        }
        catch (ErrnoException e) {}
    }

    public static File createTempFile(File parent, String prefix) {
        File tempFile = null;
        boolean exists = true;
        while (exists) {
            tempFile = new File(parent, prefix+"-"+ UUID.randomUUID().toString().replace("-", "")+".tmp");
            exists = tempFile.exists();
        }
        return tempFile;
    }

    public static String getFilePathFromUri(Uri uri) {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        String authority = uri.getAuthority();
        if (authority == null) return null;
        String path = null;
        String lastSegment = uri.getLastPathSegment();
        if (lastSegment != null && lastSegment.startsWith("raw:")) {
            return lastSegment.substring(4);
        }
        if (authority.equals("com.android.externalstorage.documents")) {
            if (lastSegment != null) {
                if (lastSegment.startsWith("tree:")) lastSegment = lastSegment.substring(5);
                if (lastSegment.contains(":")) {
                    String[] parts = lastSegment.split(":", 2);
                    String vol = parts[0];
                    if (vol.contains("/")) vol = vol.substring(vol.lastIndexOf('/') + 1);
                    String tail = parts.length > 1 && !parts[1].isEmpty() ? "/" + parts[1] : "";
                    if (vol.equalsIgnoreCase("primary") || vol.equalsIgnoreCase("home")) {
                        path = Environment.getExternalStorageDirectory() + (vol.equalsIgnoreCase("home") ? "/Documents" + tail : tail);
                    }
                    else {
                        path = "/storage/" + vol + tail;
                    }
                }
            }
        }
        return path;
    }

    public static File findExistingFileOnStorage(String displayName, long expectedSize) {
        if (displayName == null || displayName.isEmpty()) return null;
        File extRoot = Environment.getExternalStorageDirectory();
        File[] priorityDirs = new File[]{
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            new File(extRoot, "Games"),
            new File(extRoot, "GOG Games"),
            new File(extRoot, "Winlator"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            extRoot
        };
        for (File dir : priorityDirs) {
            File found = searchFileInDir(dir, displayName, expectedSize, 0, 4);
            if (found != null) return found;
        }
        return null;
    }

    private static File searchFileInDir(File dir, String targetName, long expectedSize, int depth, int maxDepth) {
        if (dir == null || !dir.isDirectory() || depth > maxDepth) return null;
        File direct = new File(dir, targetName);
        if (direct.isFile() && (expectedSize <= 0 || direct.length() == expectedSize)) {
            return direct;
        }
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File f : children) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.startsWith(".") || n.equalsIgnoreCase("Android") || n.equalsIgnoreCase("DCIM") || n.equalsIgnoreCase("Pictures")) continue;
                File sub = searchFileInDir(f, targetName, expectedSize, depth + 1, maxDepth);
                if (sub != null) return sub;
            } else if (f.getName().equalsIgnoreCase(targetName) && (expectedSize <= 0 || f.length() == expectedSize)) {
                return f;
            }
        }
        return null;
    }

    public static String getUriDisplayName(Context context, Uri uri) {
        if (uri == null) return "imported_file.bin";
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        }
        catch (Exception e) {}
        String last = uri.getLastPathSegment();
        if (last != null) {
            int colon = last.lastIndexOf(':');
            int slash = last.lastIndexOf('/');
            int cut = Math.max(colon, slash);
            if (cut >= 0 && cut + 1 < last.length()) return last.substring(cut + 1);
            return last;
        }
        return "imported_file.bin";
    }

    public static File getFileFromUri(Context context, Uri uri) {
        if (uri == null) return null;
        String path = getFilePathFromUri(uri);
        if (path != null) {
            File direct = new File(path);
            if (direct.exists()) return direct;
        }
        long expectedSize = -1;
        String displayName = null;
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int dataIdx = cursor.getColumnIndex("_data");
                if (dataIdx >= 0) {
                    String dataPath = cursor.getString(dataIdx);
                    if (dataPath != null && !dataPath.isEmpty()) {
                        File f = new File(dataPath);
                        if (f.exists()) return f;
                    }
                }
                int nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx);
                int sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) expectedSize = cursor.getLong(sizeIdx);
            }
        } catch (Exception ignored) {}

        if (displayName == null || displayName.isEmpty()) {
            displayName = StringUtils.clearReservedChars(getUriDisplayName(context, uri));
        }
        File existingInFolder = findExistingFileOnStorage(displayName, expectedSize);
        if (existingInFolder != null && existingInFolder.isFile()) {
            return existingInFolder;
        }

        if (displayName.isEmpty()) displayName = "imported_file.bin";
        File cacheDir = new File(context.getCacheDir(), "uri_imports");
        if (!cacheDir.isDirectory()) cacheDir.mkdirs();
        File dstFile = new File(cacheDir, displayName);
        try (InputStream inStream = context.getContentResolver().openInputStream(uri);
             BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(dstFile), StreamUtils.BUFFER_SIZE)) {
            if (inStream != null && StreamUtils.copy(inStream, outStream)) {
                return dstFile;
            }
        }
        catch (IOException e) {}
        return null;
    }

    public static boolean contentEquals(File origin, File target) {
        if (origin.isDirectory() && target.isDirectory()) {
            File[] originFiles = origin.listFiles();
            File[] targetFiles = origin.listFiles();

            if (originFiles != null && targetFiles != null) {
                if (originFiles.length != targetFiles.length) return false;

                for (int i = 0; i < originFiles.length; i++) {
                    if (!contentEquals(originFiles[i], targetFiles[i])) return false;
                }

                return true;
            }
            else return originFiles == null && targetFiles == null;
        }
        else if (origin.isFile() && target.isFile()) {
            if (origin.length() != target.length()) return false;

            try (InputStream inStream1 = new BufferedInputStream(new FileInputStream(origin));
                 InputStream inStream2 = new BufferedInputStream(new FileInputStream(target))) {
                int data;
                while ((data = inStream1.read()) != -1) {
                    if (data != inStream2.read()) return false;
                }
                return true;
            }
            catch (IOException e) {
                return false;
            }
        }
        else return false;
    }

    public static void getSizeAsync(File file, Callback<Long> callback) {
        Executors.newSingleThreadExecutor().execute(() -> getSize(file, callback));
    }

    private static void getSize(File file, Callback<Long> callback) {
        if (file == null) return;
        if (file.isFile()) {
            callback.call(file.length());
            return;
        }

        Stack<File> stack = new Stack<>();
        stack.push(file);

        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] files = current.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isDirectory()) {
                    stack.push(f);
                }
                else {
                    long length = f.length();
                    if (length > 0) callback.call(length);
                }
            }
        }
    }

    public static long getSize(Context context, String assetFile) {
        try (InputStream inStream = context.getAssets().open(assetFile)) {
            return inStream.available();
        }
        catch (IOException e) {
            return 0;
        }
    }

    public static long getInternalStorageSize() {
        File dataDir = Environment.getDataDirectory();
        StatFs stat = new StatFs(dataDir.getPath());
        long blockSize = stat.getBlockSizeLong();
        long totalBlocks = stat.getBlockCountLong();
        return totalBlocks * blockSize;
    }

    public static boolean isDirectory(Context context, String assetFile) {
        try {
            String[] files = context.getAssets().list(assetFile);
            return files != null && files.length > 0;
        }
        catch (IOException e) {
            return false;
        }
    }

    public static String toRelativePath(String basePath, String fullPath) {
        return StringUtils.removeEndSlash((fullPath.startsWith("/") ? "/" : "")+(new File(basePath).toURI().relativize(new File(fullPath).toURI()).getPath()));
    }

    public static int readInt(String path) {
        int result = 0;
        try {
            try (RandomAccessFile reader = new RandomAccessFile(path, "r")) {
                String line = reader.readLine();
                result = !line.isEmpty() ? Integer.parseInt(line) : 0;
            }
        }
        catch (Exception e) {}
        return result;
    }

    public static String readSymlink(File file) {
        try {
            return Files.readSymbolicLink(file.toPath()).toString();
        }
        catch (IOException e) {
            return "";
        }
    }

    public static String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) return "";
        int dotIndex = filename.lastIndexOf(".");
        return dotIndex != -1 ? filename.substring(dotIndex + 1) : "";
    }

    public static void openIntent(Activity activity, String path) {
        Intent intent;
        if (path.startsWith("file://")) {
            File file = new File(Uri.decode(path.replace("file://", "")));
            intent = new Intent(Intent.ACTION_VIEW, FileProvider.getUriForFile(activity, "com.winlator.FileProvider", file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        else intent = new Intent(Intent.ACTION_VIEW, Uri.parse(path));
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
