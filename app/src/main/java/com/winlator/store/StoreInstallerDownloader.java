package com.winlator.store;

import android.app.Activity;

import com.winlator.R;
import com.winlator.core.Callback;
import com.winlator.core.DownloadProgressDialog;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Downloads official store installers over HTTPS, restricts redirects, and replaces the target atomically. */
public final class StoreInstallerDownloader {
    private static final int MAX_REDIRECTS = 6;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 60000;

    private StoreInstallerDownloader() {}

    public static void download(Activity activity, String sourceUrl, File destination, String[] allowedHostSuffixes,
                                long minimumBytes, long maximumBytes, Callback<Boolean> callback) {
        DownloadProgressDialog progressDialog = new DownloadProgressDialog(activity);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        progressDialog.show(R.string.downloading_file, () -> cancelled.set(true));
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = false;
            File partial = null;
            try {
                File parent = destination.getParentFile();
                if (parent == null) throw new IllegalArgumentException("Installer destination must have a parent directory");
                partial = new File(parent, destination.getName() + ".part");
                if (!parent.isDirectory() && !parent.mkdirs()) {
                    throw new IllegalStateException("Unable to create installer directory");
                }
                URL finalUrl = resolveAllowedUrl(sourceUrl, allowedHostSuffixes);
                HttpURLConnection connection = openFollowingSafeRedirects(finalUrl, allowedHostSuffixes, cancelled);
                try {
                    int status = connection.getResponseCode();
                    if (status != HttpURLConnection.HTTP_OK) throw new IllegalStateException("Unexpected installer HTTP status");
                    long expectedLength = connection.getContentLengthLong();
                    if (expectedLength > maximumBytes) throw new IllegalStateException("Installer exceeds size limit");

                    long total = 0;
                    try (InputStream input = new BufferedInputStream(connection.getInputStream());
                         FileOutputStream output = new FileOutputStream(partial, false)) {
                        byte[] buffer = new byte[32768];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (cancelled.get()) throw new InterruptedException("Download cancelled");
                            total += read;
                            if (total > maximumBytes) throw new IllegalStateException("Installer exceeds size limit");
                            output.write(buffer, 0, read);
                            if (expectedLength > 0) {
                                int percent = (int) Math.min(100, (total * 100L) / expectedLength);
                                activity.runOnUiThread(() -> progressDialog.setProgress(percent));
                            }
                        }
                        output.getFD().sync();
                    }
                    if (cancelled.get() || total < minimumBytes || (expectedLength >= 0 && total != expectedLength)) {
                        throw new IllegalStateException("Incomplete installer download");
                    }
                    try {
                        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    }
                    catch (Exception unsupportedAtomicMove) {
                        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    success = true;
                }
                finally {
                    connection.disconnect();
                }
            }
            catch (Exception ignored) {
                if (partial != null) partial.delete();
            }
            final boolean result = success;
            activity.runOnUiThread(() -> {
                progressDialog.close();
                callback.call(result);
            });
        });
    }

    private static HttpURLConnection openFollowingSafeRedirects(URL initial, String[] allowedSuffixes, AtomicBoolean cancelled) throws Exception {
        URL current = initial;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (cancelled.get()) throw new InterruptedException("Download cancelled");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Winlator-Hybrid-Store-Installer/1.0");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_SEE_OTHER || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == MAX_REDIRECTS) throw new IllegalStateException("Invalid installer redirect");
                current = new URI(current.toExternalForm()).resolve(location).toURL();
                requireAllowedHttps(current, allowedSuffixes);
                continue;
            }
            return connection;
        }
        throw new IllegalStateException("Too many redirects");
    }

    private static URL resolveAllowedUrl(String sourceUrl, String[] allowedSuffixes) throws Exception {
        URL url = new URL(sourceUrl);
        requireAllowedHttps(url, allowedSuffixes);
        return url;
    }

    static void requireAllowedHttps(URL url, String[] allowedSuffixes) {
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new SecurityException("HTTPS is required");
        String host = url.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = false;
        for (String suffix : allowedSuffixes) {
            String normalized = suffix.toLowerCase(Locale.ROOT);
            if (host.equals(normalized) || host.endsWith("." + normalized)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) throw new SecurityException("Installer redirect host is not allowed");
    }
}
