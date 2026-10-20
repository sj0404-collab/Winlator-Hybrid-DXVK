package com.winlator;

import com.winlator.core.DefaultVersion;
import com.winlator.core.GeneralComponents;
import com.winlator.pro.WinlatorProSuite;
import com.winlator.renderer.effects.CASSharpenEffect;
import com.winlator.renderer.effects.HDRBloomEffect;
import com.winlator.renderer.effects.NightVisionEffect;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
    public void testCombinableShadersAndMemoryStatus() {
        CASSharpenEffect cas = new CASSharpenEffect();
        cas.setSharpness(0.85f);
        assertEquals(0.85f, cas.getSharpness(), 0.001f);

        HDRBloomEffect hdr = new HDRBloomEffect();
        hdr.setIntensity(0.75f);
        assertEquals(0.75f, hdr.getIntensity(), 0.001f);

        NightVisionEffect nv = new NightVisionEffect();
        assertNotNull(nv);

        WinlatorProSuite.MemoryStatus lowMem = new WinlatorProSuite.MemoryStatus(
            2L * 1024L * 1024L * 1024L,
            64L * 1024L * 1024L * 1024L,
            900L * 1024L * 1024L,
            8192L * 1024L * 1024L
        );
        assertTrue(lowMem.isLowStorage);
        assertTrue(lowMem.isLowRam);
        assertTrue(lowMem.formatStorageGb().contains("ГБ"));

        WinlatorProSuite.MemoryStatus highMem = new WinlatorProSuite.MemoryStatus(
            18L * 1024L * 1024L * 1024L,
            128L * 1024L * 1024L * 1024L,
            4200L * 1024L * 1024L,
            8192L * 1024L * 1024L
        );
        assertFalse(highMem.isLowStorage);
        assertFalse(highMem.isLowRam);
    }

    @Test
    public void testLightweightSystemAssetsAndHybridDxvkExist() {
        File assetsDir = new File("src/main/assets");
        if (!assetsDir.isDirectory()) {
            assetsDir = new File("app/src/main/assets");
        }
        File rootfs = new File(assetsDir, "rootfs.tzst");
        assertTrue("rootfs.tzst must exist", rootfs.isFile());
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
