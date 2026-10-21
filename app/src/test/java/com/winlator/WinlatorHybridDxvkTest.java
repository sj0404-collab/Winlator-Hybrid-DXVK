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

    @Test
    public void testGameProgramSystemCategoryFilteringAndShortcuts() throws Exception {
        File tmpRoot = new File(System.getProperty("java.io.tmpdir"), "winlator_test_v122");
        tmpRoot.mkdirs();

        // 1. Игра с папкой данных (Souldiers + UnityPlayer.dll + Souldiers_Data)
        File gameDir = new File(tmpRoot, "Games/Souldiers");
        new File(gameDir, "Souldiers_Data").mkdirs();
        new File(gameDir, "UnityPlayer.dll").createNewFile();
        File souldiersExe = new File(gameDir, "Souldiers.exe");
        souldiersExe.createNewFile();
        assertEquals(WinlatorProSuite.CAT_GAME, WinlatorProSuite.classifyExecutable(souldiersExe, gameDir));
        assertEquals("🎮 Игра", WinlatorProSuite.getCategoryBadgeLabel(WinlatorProSuite.CAT_GAME, "souldiers.exe"));

        // 2. Программа (ZipGenius)
        File progDir = new File(tmpRoot, "Program Files (x86)/ZipGenius 6");
        progDir.mkdirs();
        File zgExe = new File(progDir, "zg.exe");
        zgExe.createNewFile();
        assertEquals(WinlatorProSuite.CAT_PROGRAM, WinlatorProSuite.classifyExecutable(zgExe, progDir));
        assertEquals("💼 Программа", WinlatorProSuite.getCategoryBadgeLabel(WinlatorProSuite.CAT_PROGRAM, "zg.exe"));

        // 3. Система: деинсталлятор unins000.exe и установщик s_setup.exe / vc_redist.x64.exe
        File uninsExe = new File(progDir, "unins000.exe");
        uninsExe.createNewFile();
        assertEquals(WinlatorProSuite.CAT_SYSTEM, WinlatorProSuite.classifyExecutable(uninsExe, progDir));
        assertTrue(WinlatorProSuite.getCategoryBadgeLabel(WinlatorProSuite.CAT_SYSTEM, "unins000.exe").contains("Система"));

        File setupExe = new File(progDir, "s_setup.exe");
        setupExe.createNewFile();
        assertEquals(WinlatorProSuite.CAT_SYSTEM, WinlatorProSuite.classifyExecutable(setupExe, progDir));

        File vcRedist = new File(gameDir, "vc_redist.x64.exe");
        vcRedist.createNewFile();
        assertEquals(WinlatorProSuite.CAT_SYSTEM, WinlatorProSuite.classifyExecutable(vcRedist, gameDir));

        // 4. Проверка создания .lnk ярлыка с многоуровневым путём и рабочей папкой игры (workingDir)
        File lnkOut = new File(tmpRoot, "Souldiers.lnk");
        com.winlator.win32.MSLink.LinkInfo info = new com.winlator.win32.MSLink.LinkInfo();
        info.targetPath = "G:\\Games\\Souldiers\\Souldiers.exe";
        info.workingDir = "G:\\Games\\Souldiers";
        info.iconLocation = "G:\\Games\\Souldiers\\Souldiers.exe";
        assertTrue(com.winlator.win32.MSLink.createFile(info, lnkOut));

        com.winlator.win32.MSLink.LinkInfo parsed = com.winlator.win32.MSLink.extractLinkInfo(lnkOut);
        assertNotNull(parsed);
        assertEquals("G:\\Games\\Souldiers\\Souldiers.exe", parsed.targetPath);
        assertEquals("G:\\Games\\Souldiers", parsed.workingDir);
    }

    @Test
    public void testShortcutDosPathEscapingAndDirnameSafety() {
        // 1. unescapeDOSPath must preserve single backslashes and strip outer quotes
        String quotedSingleSlash = "\"G:\\Games\\Souldiers\\Souldiers.exe\"";
        assertEquals("G:\\Games\\Souldiers\\Souldiers.exe", com.winlator.core.StringUtils.unescapeDOSPath(quotedSingleSlash));

        // 2. escapeDOSPath + unescapeDOSPath roundtrip with spaces
        String withSpaces = "D:\\GOG Games\\Souldiers\\Souldiers.exe";
        String escaped = com.winlator.core.StringUtils.escapeDOSPath(withSpaces);
        assertEquals(withSpaces, com.winlator.core.StringUtils.unescapeDOSPath(escaped));

        // 3. FileUtils.getDirname must never throw StringIndexOutOfBoundsException when no slash is present
        assertEquals("G:\\Games\\Souldiers", com.winlator.core.FileUtils.getDirname("G:\\Games\\Souldiers\\Souldiers.exe"));
        assertEquals("C:\\", com.winlator.core.FileUtils.getDirname("C:Souldiers.exe"));
        assertEquals("", com.winlator.core.FileUtils.getDirname("Souldiers.exe"));
        assertEquals("", com.winlator.core.FileUtils.getDirname(""));
        assertEquals("", com.winlator.core.FileUtils.getDirname(null));

        // 4. GraphicsDrivers default OpenGL driver must be VIRGL (Mesa OpenGL 4.3), not GLADIO (OpenGL 2.1)
        assertEquals(com.winlator.container.GraphicsDrivers.VIRGL, com.winlator.container.GraphicsDrivers.DEFAULT_OPENGL_DRIVER);
        String[] parsedDrivers = com.winlator.container.GraphicsDrivers.parseIdentifiers("vortek");
        assertEquals(com.winlator.container.GraphicsDrivers.VORTEK, parsedDrivers[0]);
        assertEquals(com.winlator.container.GraphicsDrivers.VIRGL, parsedDrivers[1]);

        // 5. Verify native direct3d.tzst (d3dcompiler_47.dll) and hybrid DXVK archive sizes
        File assetsDir = new File("src/main/assets");
        if (!assetsDir.isDirectory()) {
            assetsDir = new File("app/src/main/assets");
        }
        File direct3dTzst = new File(assetsDir, "wincomponents/direct3d.tzst");
        assertTrue("direct3d.tzst must exist and contain native DirectX compilers", direct3dTzst.isFile() && direct3dTzst.length() > 15_000_000L);
        File hybridDxvk = new File(assetsDir, "dxwrapper/dxvk-1.7.2-2.4.1-hybrid.tzst");
        assertTrue("rebuilt dxvk-1.7.2-2.4.1-hybrid.tzst must be > 5 MB", hybridDxvk.isFile() && hybridDxvk.length() > 5_000_000L);
    }
}
