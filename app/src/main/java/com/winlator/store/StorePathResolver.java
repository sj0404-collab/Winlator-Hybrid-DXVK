package com.winlator.store;

import com.winlator.container.Container;
import com.winlator.core.WineUtils;
import com.winlator.win32.MSLink;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** Resolves vendor launchers from the Windows Start Menu for non-default install locations. */
final class StorePathResolver {
    private static final int MAX_DEPTH = 6;
    private static final int MAX_ENTRIES = 512;

    private StorePathResolver() {}

    static File findStartMenuExecutable(Container container, String shortcutNameToken, String expectedExeName) {
        if (container == null) return null;
        File startMenu = container.getStartMenuDir();
        if (!startMenu.isDirectory()) return null;
        String token = shortcutNameToken.toLowerCase(Locale.ROOT);
        String expected = expectedExeName.toLowerCase(Locale.ROOT);

        Deque<Node> pending = new ArrayDeque<>();
        pending.add(new Node(startMenu, 0));
        int visited = 0;
        while (!pending.isEmpty() && visited < MAX_ENTRIES) {
            Node node = pending.removeFirst();
            File[] children = node.directory.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (++visited > MAX_ENTRIES) break;
                if (child.isDirectory()) {
                    if (node.depth < MAX_DEPTH && !isSymlink(child)) pending.addLast(new Node(child, node.depth + 1));
                    continue;
                }
                String lowerName = child.getName().toLowerCase(Locale.ROOT);
                if (!lowerName.endsWith(".lnk") || !lowerName.contains(token)) continue;
                try {
                    MSLink.LinkInfo info = MSLink.extractLinkInfo(child);
                    if (info == null || info.targetPath == null || info.targetPath.trim().isEmpty()) continue;
                    String target = info.targetPath.trim();
                    String basename = target.substring(Math.max(target.lastIndexOf('\\'), target.lastIndexOf('/')) + 1);
                    if (!basename.equalsIgnoreCase(expected)) continue;
                    String unixPath = WineUtils.dosToUnixPath(target, container);
                    if (unixPath == null || unixPath.isEmpty()) continue;
                    File executable = new File(unixPath);
                    if (executable.isFile()) return executable;
                }
                catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean isSymlink(File file) {
        try {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        }
        catch (Exception e) {
            return true;
        }
    }

    private static final class Node {
        final File directory;
        final int depth;

        Node(File directory, int depth) {
            this.directory = directory;
            this.depth = depth;
        }
    }
}
