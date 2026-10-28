package com.winlator.store;

import org.junit.Test;

import java.net.URL;

import static org.junit.Assert.assertThrows;

public class StoreInstallerDownloaderTest {
    @Test
    public void acceptsOfficialHttpsSubdomain() throws Exception {
        StoreInstallerDownloader.requireAllowedHttps(
            new URL("https://cdn.akamai.steamstatic.com/client/installer/SteamSetup.exe"),
            new String[]{"steamstatic.com"}
        );
    }

    @Test
    public void acceptsEpicOfficialInstallerRedirectHost() throws Exception {
        StoreInstallerDownloader.requireAllowedHttps(
            new URL("https://epicgames-download1.akamaized.net/Builds/UnrealEngineLauncher/Installers/Windows/EpicInstaller.msi"),
            new String[]{"epicgames.com", "epicgames-download1.akamaized.net"}
        );
    }

    @Test
    public void rejectsPlainHttpEvenForAllowedHost() throws Exception {
        assertThrows(SecurityException.class, () -> StoreInstallerDownloader.requireAllowedHttps(
            new URL("http://cdn.akamai.steamstatic.com/client/installer/SteamSetup.exe"),
            new String[]{"steamstatic.com"}
        ));
    }

    @Test
    public void rejectsLookalikeRedirectHost() throws Exception {
        assertThrows(SecurityException.class, () -> StoreInstallerDownloader.requireAllowedHttps(
            new URL("https://steamstatic.com.attacker.invalid/setup.exe"),
            new String[]{"steamstatic.com"}
        ));
    }
}
