# Store integration (Hybrid runtime)

## Current integration model

Steam and Epic are integrated as official **Windows store clients inside a selected Winlator Hybrid container**. This does not launch a separate Android app and does not replace the Hybrid Wine/runtime stack. Authentication, account entitlements, store browsing, download, install, and updates remain owned by each vendor's Windows client.

The Winlator game library is the unified launch surface for container shortcuts and imported installed games. Importing a game does not copy game files: it writes a small `.desktop` shortcut inside the container.

## Steam

- The installer is downloaded from `https://cdn.akamai.steamstatic.com/client/installer/SteamSetup.exe` and run in the selected container. Launcher discovery checks standard install paths and Windows Start Menu shortcuts, so ordinary custom install locations can be found too.
- After the user signs in and installs a title in Steam, **Sync games** scans Steam's standard Windows install location and `steamapps/libraryfolders.vdf` / `appmanifest_*.acf` files.
- Imported titles launch the same container's `steam.exe` with `-applaunch <appid>`, allowing Steam to manage its own session and game launch.
- Library-folder paths and install-directory names are checked before shortcut creation.

## Epic Games Store

- The MSI is requested from Epic's launcher installer service over HTTPS and run through the container's `msiexec` path. Launcher discovery checks standard install paths and Start Menu shortcuts.
- After the user signs in and installs a title in Epic, **Sync games** scans the standard Epic manifest directory (`ProgramData/Epic/EpicGamesLauncher/Data/Manifests/*.item`).
- Imported titles launch the executable recorded by the manifest, after checking that its path remains within the install directory. Bounded launch arguments are kept only when they do not resemble auth/password/token material.
- Some titles require Epic Online Services, launcher arguments, anti-cheat, or DRM behavior that may not work under Wine. Compatibility is not promised.

## Installer transport

`StoreInstallerDownloader` requires HTTPS, validates every redirect host against the vendor allowlist, caps installer size, uses connect/read timeouts, downloads into a `.part` file, checks the received length when supplied, and replaces the destination only after a complete transfer. It never installs an APK or adds a separate Android storefront app.

On 2026-10-28, HTTP HEAD checks returned Steam's official CDN response and Epic's official installer service redirected to `epicgames-download1.akamaized.net`, which is on the redirect allowlist. The installer binaries were **not** downloaded or executed during static verification.

## Scope and verification

This is a client-backed integration, not a native port of GameNative's JavaSteam/Epic OAuth/depot-download services. Account login and the catalog of uninstalled owned games are not yet shown in Winlator's own UI; the vendor clients provide those online functions. Imported games become visible in Hybrid's library after installation; the library scans manifests asynchronously on resume (throttled to once per minute), and each store also has a manual sync action.

No FEX-dependent components or Mali/PanVK-specific changes are included. Local Gradle builds are intentionally not run; final build/test verification is reserved for GitHub Actions.
