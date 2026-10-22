package com.winlator.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Per-executable DXVK mode identifiers and stable preference keys. */
public final class DxvkProfile {
    public static final int SYNC = 0;
    public static final int ASYNC = 1;
    public static final int HYBRID = 2;
    public static final int DEFAULT = HYBRID;

    private DxvkProfile() {}

    public static int normalize(int mode) {
        return mode == SYNC || mode == ASYNC || mode == HYBRID ? mode : DEFAULT;
    }

    public static String getVersion(int mode) {
        switch (normalize(mode)) {
            case SYNC: return DefaultVersion.MINOR_DXVK; // DXVK 1.10.3 supports Vulkan 1.1 class devices.
            case ASYNC: return DefaultVersion.ASYNC_DXVK; // GPL async build based on DXVK 2.4.1.
            default: return DefaultVersion.HYBRID_DXVK;
        }
    }

    public static String getLabel(int mode) {
        switch (normalize(mode)) {
            case SYNC: return "Синхронный · DXVK 1.10.3";
            case ASYNC: return "Асинхронный · GPL async 2.4.1";
            default: return "Гибридный · совместимый";
        }
    }

    /** Distinguishes same-named EXEs installed in different game folders. */
    public static String preferenceKey(File exe) {
        if (exe == null) return "desktop";
        String identity;
        try {
            identity = exe.getCanonicalPath();
        } catch (Exception ignored) {
            identity = exe.getAbsolutePath();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(24);
            for (int i = 0; i < 12; i++) key.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(identity.toLowerCase(Locale.ROOT).hashCode());
        }
    }
}
