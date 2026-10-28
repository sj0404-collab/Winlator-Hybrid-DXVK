package com.winlator.store;

import com.winlator.container.Container;
import com.winlator.core.WineUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Windows Epic Games Launcher bridge for Winlator Hybrid. Epic handles account login,
 * entitlements, downloads and updates; this adapter imports locally installed game
 * manifests into Hybrid shortcuts and never starts another Android application.
 */
public final class EpicLibraryBackend {
    public static final String EPIC_INSTALLER_URL = "https://launcher-public-service-prod06.ol.epicgames.com/launcher/api/installer/download/EpicGamesLauncherInstaller.msi";

    private static final String[] LAUNCHER_RELATIVE_PATHS = {
        "Program Files (x86)/Epic Games/Launcher/Portal/Binaries/Win64/EpicGamesLauncher.exe",
        "Program Files/Epic Games/Launcher/Portal/Binaries/Win64/EpicGamesLauncher.exe"
    };

    private EpicLibraryBackend() {}

    public static File getLauncherExecutable(Container container) {
        if (container == null) return null;
        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        for (String relative : LAUNCHER_RELATIVE_PATHS) {
            File candidate = new File(driveC, relative);
            if (candidate.isFile()) return candidate;
        }
        return StorePathResolver.findStartMenuExecutable(container, "epic", "EpicGamesLauncher.exe");
    }

    public static File getInstallerDestination(Container container) {
        File downloads = new File(container.getUserDir(), "Downloads");
        if (!downloads.isDirectory()) downloads.mkdirs();
        return new File(downloads, "EpicGamesLauncherInstaller.msi");
    }

    /** Imports installed *.item manifests. Run on a worker thread. */
    public static int syncInstalledGames(Container container) {
        File launcher = getLauncherExecutable(container);
        if (launcher == null) return 0;
        File manifestsDir = new File(container.getRootDir(), ".wine/drive_c/ProgramData/Epic/EpicGamesLauncher/Data/Manifests");
        File[] manifests = manifestsDir.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".item"));
        if (manifests == null) return 0;

        File desktopDir = new File(container.getUserDir(), "Desktop");
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return 0;
        int changed = 0;

        for (File manifest : manifests) {
            try {
                JSONObject item = new JSONObject(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
                String appName = clean(item.optString("AppName", ""));
                String displayName = clean(item.optString("DisplayName", appName));
                String installLocation = clean(item.optString("InstallLocation", ""));
                String executableRelative = clean(item.optString("LaunchExecutable", ""));
                String launchCommand = safeLaunchCommand(item.optString("LaunchCommand", ""));
                if (appName.isEmpty() || displayName.isEmpty() || installLocation.isEmpty() || executableRelative.isEmpty()) continue;
                if (item.optBoolean("bIsIncompleteInstall", false)) continue;
                if (installLocation.contains("..") || !isSafeRelativeExecutable(executableRelative)) continue;

                String unixInstall = WineUtils.dosToUnixPath(installLocation, container);
                if (unixInstall == null || unixInstall.isEmpty()) continue;
                File installDir = new File(unixInstall);
                File exe = new File(installDir, executableRelative.replace('\\', File.separatorChar).replace('/', File.separatorChar));
                if (!exe.isFile() || !isInside(installDir, exe)) continue;
                String dosExe = WineUtils.unixToDOSPath(exe.getAbsolutePath(), container);
                String dosDir = WineUtils.unixToDOSPath(installDir.getAbsolutePath(), container);
                if (dosExe == null || dosExe.isEmpty() || dosDir == null || dosDir.isEmpty()) continue;

                String uniqueId = appName.replaceAll("[^A-Za-z0-9._-]", "_");
                if (uniqueId.isEmpty()) continue;
                String base = safeFilePart(displayName) + " [Epic " + uniqueId + "]";
                File entry = new File(desktopDir, base + ".desktop");
                String prefix = new File(container.getRootDir(), ".wine").getAbsolutePath();
                String content = "[Desktop Entry]\n" +
                    "Name=" + displayName + "\n" +
                    "Exec=env WINEPREFIX=\"" + prefix + "\" wine \"" + dosExe + "\"\n" +
                    "Path=" + dosDir + "\n" +
                    "Type=Application\n" +
                    "StartupNotify=true\n" +
                    "Icon=" + uniqueId + "\n" +
                    "\n[Extra Data]\n" +
                    "container_id=" + container.id + "\n" +
                    "execDosPath=" + dosExe + "\n" +
                    "execUnixPath=" + exe.getAbsolutePath() + "\n" +
                    "execArgs=" + launchCommand + "\n" +
                    "storeSource=Epic\n" +
                    "storeAppId=" + uniqueId + "\n" +
                    "storeInstallDir=" + installDir.getAbsolutePath() + "\n";
                if (!entry.isFile() || !content.equals(readText(entry))) {
                    try (FileOutputStream out = new FileOutputStream(entry)) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    changed++;
                }
            }
            catch (Exception ignored) {}
        }
        return changed;
    }

    static boolean isSafeRelativeExecutable(String value) {
        if (value == null || value.isEmpty() || value.contains("..") || value.startsWith("/") || value.startsWith("\\")) return false;
        if (value.matches("(?i)^[a-z]:.*") || new File(value).isAbsolute()) return false;
        return value.toLowerCase(java.util.Locale.ROOT).endsWith(".exe");
    }

    static String safeLaunchCommand(String value) {
        if (value == null) return "";
        String cleaned = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\0', ' ').trim();
        String lower = cleaned.toLowerCase(java.util.Locale.ROOT);
        if (cleaned.length() > 4096 || lower.contains("auth_") || lower.contains("password") ||
            lower.contains("token") || lower.contains("exchange")) return "";
        return cleaned;
    }

    private static String safeFilePart(String value) {
        String cleaned = clean(value).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) cleaned = "Epic Game";
        return cleaned.length() > 90 ? cleaned.substring(0, 90).trim() : cleaned;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean isInside(File parent, File child) {
        try {
            String parentPath = parent.getCanonicalPath() + File.separator;
            return child.getCanonicalPath().startsWith(parentPath);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static String readText(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            return "";
        }
    }
}
