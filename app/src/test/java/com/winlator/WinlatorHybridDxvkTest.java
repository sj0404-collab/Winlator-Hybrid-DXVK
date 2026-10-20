package com.winlator;

import com.winlator.core.DefaultVersion;
import com.winlator.core.GeneralComponents;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WinlatorHybridDxvkTest {
    @Test
    public void testHybridDxvkIsDefault() {
        assertEquals("1.7.2-2.4.1-hybrid", DefaultVersion.HYBRID_DXVK);
        assertEquals("1.7.2", DefaultVersion.FAST_DXVK);
        assertEquals("1.7.2-2.4.1-hybrid", DefaultVersion.DXVK());
        assertEquals("1.7.2-2.4.1-hybrid", DefaultVersion.DXVK("turnip"));
        assertEquals("1.7.2-2.4.1-hybrid", DefaultVersion.DXVK("vortek"));
    }

    @Test
    public void testBuiltinDxvkComponentsList() {
        ArrayList<String> names = GeneralComponents.getBuiltinComponentNames(GeneralComponents.Type.DXVK);
        assertTrue(names.contains("1.7.2-2.4.1-hybrid"));
        assertTrue(names.contains("1.7.2"));
        assertTrue(names.contains("1.10.3"));
        assertTrue(names.contains("2.4.1"));
        assertEquals("1.7.2-2.4.1-hybrid", names.get(0));
    }

    @Test
    public void testLightweightSystemAssetsAndHybridDxvkExist() {
        File assetsDir = new File("src/main/assets");
        if (!assetsDir.isDirectory()) {
            assetsDir = new File("app/src/main/assets");
        }
        File rootfs = new File(assetsDir, "rootfs.tzst");
        assertTrue("rootfs.tzst must exist", rootfs.isFile());
        // Must be lightweight (< 100 MB), not 1 GB+
        assertTrue("rootfs.tzst must be under 100 MB", rootfs.length() > 50_000_000L && rootfs.length() < 100_000_000L);

        File hybridDxvk = new File(assetsDir, "dxwrapper/dxvk-1.7.2-2.4.1-hybrid.tzst");
        File fastDxvk = new File(assetsDir, "dxwrapper/dxvk-1.7.2.tzst");
        File dxvk1103 = new File(assetsDir, "dxwrapper/dxvk-1.10.3.tzst");
        File dxvk241 = new File(assetsDir, "dxwrapper/dxvk-2.4.1.tzst");
        File steamSetup = new File(assetsDir, "steam/SteamSetup.exe");

        assertTrue("dxvk-1.7.2-2.4.1-hybrid.tzst must exist", hybridDxvk.isFile() && hybridDxvk.length() > 4_000_000L);
        assertTrue("dxvk-1.7.2.tzst must exist", fastDxvk.isFile() && fastDxvk.length() > 2_500_000L);
        assertTrue("dxvk-1.10.3.tzst must exist", dxvk1103.isFile() && dxvk1103.length() > 2_500_000L);
        assertTrue("dxvk-2.4.1.tzst must exist", dxvk241.isFile() && dxvk241.length() > 3_000_000L);
        assertTrue("SteamSetup.exe must exist", steamSetup.isFile() && steamSetup.length() > 1_500_000L);
    }
}
