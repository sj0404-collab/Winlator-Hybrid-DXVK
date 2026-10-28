package com.winlator.store;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SteamLibraryBackendTest {
    @Test
    public void decodesEscapedWindowsLibraryPath() {
        assertEquals("D:\\Steam Library\\Games", SteamLibraryBackend.unescapeVdf("D:\\\\Steam Library\\\\Games"));
    }

    @Test
    public void acceptsSimpleSteamInstallDirectory() {
        assertTrue(SteamLibraryBackend.isSafeInstallDirName("Hollow Knight"));
    }

    @Test
    public void rejectsTraversalAndNestedInstallDirectories() {
        assertFalse(SteamLibraryBackend.isSafeInstallDirName("../outside"));
        assertFalse(SteamLibraryBackend.isSafeInstallDirName(".."));
        assertFalse(SteamLibraryBackend.isSafeInstallDirName("nested/game"));
        assertFalse(SteamLibraryBackend.isSafeInstallDirName("nested\\game"));
    }
}
