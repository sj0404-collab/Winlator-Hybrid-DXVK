package com.winlator.store;

import com.winlator.container.Container;
import com.winlator.core.WineUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridges games installed by Valve's official Windows Steam client into Winlator's
 * existing shortcut/library model. Steam still owns account authentication, licenses,
 * updates and content delivery; launches remain in the selected Hybrid Wine container.
 */
public final class SteamLibraryBackend {
    public static final String STEAM_SETUP_URL = "https://cdn.akamai.steamstatic.com/client/installer/SteamSetup.exe";

    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern APP_ID = Pattern.compile("\"appid\"\\s+\"([0-9]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("\"name\"\\s+\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTALL_DIR = Pattern.compile("\"installdir\"\\s+\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private SteamLibraryBackend() {}

    public static File getSteamExecutable(Container container) {
        if (container == null) return null;
        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        File[] candidates = {
            new File(driveC, "Program Files (x86)/Steam/steam.exe"),
            new File(driveC, "Program Files/Steam/steam.exe"),
            new File(driveC, "Steam/steam.exe")
        };
        for (File candidate : candidates) if (candidate.isFile()) return candidate;
        return StorePathResolver.findStartMenuExecutable(container, "steam", "steam.exe");
    }

    public static File getInstallerDestination(Container container) {
        File downloads = new File(container.getUserDir(), "Downloads");
        if (!downloads.isDirectory()) downloads.mkdirs();
        return new File(downloads, "SteamSetup.exe");
    }

    /**
     * Import installed Steam app manifests as normal Hybrid desktop shortcuts.
     * Returns the number of new or changed entries. This method performs file I/O and
     * should be called off the UI thread.
     */
    public static int syncInstalledGames(Container container) {
        File steamExe = getSteamExecutable(container);
        if (steamExe == null) return 0;

        File steamRoot = steamExe.getParentFile();
        if (steamRoot == null) return 0;
        String steamDosPath = WineUtils.unixToDOSPath(steamExe.getAbsolutePath(), container);
        if (steamDosPath == null || steamDosPath.isEmpty() || steamDosPath.equals("\\")) return 0;

        Set<File> steamAppsDirs = findSteamAppsDirectories(container, steamRoot);
        File desktopDir = new File(container.getUserDir(), "Desktop");
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return 0;

        int changed = 0;
        for (File steamApps : steamAppsDirs) {
            File[] manifests = steamApps.listFiles((dir, name) -> name.matches("appmanifest_[0-9]+\\.acf"));
            if (manifests == null) continue;
            for (File manifest : manifests) {
                try {
                    String data = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
                    String appId = group(APP_ID, data);
                    String gameName = unescapeVdf(group(NAME, data));
                    String installDirName = unescapeVdf(group(INSTALL_DIR, data));
                    if (appId.isEmpty() || gameName.isEmpty() || installDirName.isEmpty()) continue;
                    if (!isSafeInstallDirName(installDirName)) continue;

                    File commonDir = new File(steamApps, "common");
                    File gameDir = new File(commonDir, installDirName);
                    if (!gameDir.isDirectory() || !isInside(commonDir, gameDir)) continue;

                    String gameDosDir = WineUtils.unixToDOSPath(gameDir.getAbsolutePath(), container);
                    if (gameDosDir == null || gameDosDir.isEmpty() || gameDosDir.equals("\\")) continue;
                    String fileBase = safeFilePart(gameName) + " [Steam " + appId + "]";
                    File entry = new File(desktopDir, fileBase + ".desktop");
                    String prefix = new File(container.getRootDir(), ".wine").getAbsolutePath();
                    String content = "[Desktop Entry]\n" +
                        "Name=" + cleanField(gameName) + "\n" +
                        "Exec=env WINEPREFIX=\"" + prefix + "\" wine \"" + steamDosPath + "\" -applaunch " + appId + "\n" +
                        "Path=" + gameDosDir + "\n" +
                        "Type=Application\n" +
                        "StartupNotify=true\n" +
                        "Icon=" + appId + "\n" +
                        "\n[Extra Data]\n" +
                        "container_id=" + container.id + "\n" +
                        "execDosPath=" + steamDosPath + "\n" +
                        "execUnixPath=" + steamExe.getAbsolutePath() + "\n" +
                        "execArgs=-applaunch " + appId + "\n" +
                        "storeSource=Steam\n" +
                        "storeAppId=" + appId + "\n" +
                        "storeInstallDir=" + gameDir.getAbsolutePath() + "\n";
                    if (!entry.isFile() || !content.equals(readText(entry))) {
                        try (FileOutputStream out = new FileOutputStream(entry)) {
                            out.write(content.getBytes(StandardCharsets.UTF_8));
                        }
                        changed++;
                    }
                }
                catch (Exception ignored) {}
            }
        }
        return changed;
    }

    private static Set<File> findSteamAppsDirectories(Container container, File steamRoot) {
        Set<File> result = new LinkedHashSet<>();
        addSteamApps(result, new File(steamRoot, "steamapps"));

        File libraryFolders = new File(steamRoot, "steamapps/libraryfolders.vdf");
        if (!libraryFolders.isFile()) return result;
        try {
            String content = new String(Files.readAllBytes(libraryFolders.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = VDF_PATH.matcher(content);
            while (matcher.find()) {
                String windowsPath = unescapeVdf(matcher.group(1));
                if (windowsPath.isEmpty()) continue;
                String unixPath = WineUtils.dosToUnixPath(windowsPath, container);
                if (unixPath != null && !unixPath.isEmpty()) addSteamApps(result, new File(unixPath, "steamapps"));
            }
        }
        catch (Exception ignored) {}
        return result;
    }

    private static void addSteamApps(Set<File> dirs, File candidate) {
        try {
            if (candidate.isDirectory()) dirs.add(candidate.getCanonicalFile());
        }
        catch (Exception ignored) {}
    }

    private static String group(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    static String unescapeVdf(String value) {
        if (value == null) return "";
        return value.replace("\\\\", "\\").replace("\\\"", "\"").trim();
    }

    static boolean isSafeInstallDirName(String value) {
        return value != null && !value.isEmpty() && !value.contains("..") &&
            !value.contains("/") && !value.contains("\\") && !new File(value).isAbsolute();
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

    private static String safeFilePart(String value) {
        String cleaned = cleanField(value).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.isEmpty()) cleaned = "Steam Game";
        return cleaned.length() > 90 ? cleaned.substring(0, 90).trim() : cleaned;
    }

    private static String cleanField(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
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
