package com.winlator.store;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EpicLibraryBackendTest {
    @Test
    public void allowsRelativeGameExecutable() {
        assertTrue(EpicLibraryBackend.isSafeRelativeExecutable("Game/Binaries/Win64/Game.exe"));
        assertTrue(EpicLibraryBackend.isSafeRelativeExecutable("Game\\Binaries\\Win64\\Game.EXE"));
    }

    @Test
    public void boundsAndNormalizesManifestLaunchArguments() {
        org.junit.Assert.assertEquals("--windowed -novid", EpicLibraryBackend.safeLaunchCommand("--windowed\r\n-novid"));
        org.junit.Assert.assertEquals("", EpicLibraryBackend.safeLaunchCommand(repeat('x', 4097)));
        org.junit.Assert.assertEquals("", EpicLibraryBackend.safeLaunchCommand("-AUTH_PASSWORD=private-code"));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    @Test
    public void rejectsAbsoluteOrTraversingManifestPath() {
        assertFalse(EpicLibraryBackend.isSafeRelativeExecutable("../outside.exe"));
        assertFalse(EpicLibraryBackend.isSafeRelativeExecutable("..\\outside.exe"));
        assertFalse(EpicLibraryBackend.isSafeRelativeExecutable("C:\\Windows\\system32\\cmd.exe"));
        assertFalse(EpicLibraryBackend.isSafeRelativeExecutable("\\rooted.exe"));
        assertFalse(EpicLibraryBackend.isSafeRelativeExecutable("not-an-executable.dll"));
    }
}
