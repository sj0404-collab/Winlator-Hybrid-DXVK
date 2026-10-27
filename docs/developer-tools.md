# Optional developer tools

Developer tools are opt-in and are installed per container, not bundled in the APK. The file-manager context menu exposes **Open in OpenCode**, **Open terminal window here**, and the developer-tools catalog for folders. The XServer drawer also exposes the catalog and a terminal-session switcher.

## Catalog

- **OpenCode** downloads the current official Windows x64 baseline release from the OpenCode GitHub release API. The archive is checked against GitHub's published SHA-256 digest before extraction.
- **Node.js** downloads the current Windows x64 LTS ZIP from nodejs.org. The archive is checked against the SHA-256 entry in the official `SHASUMS256.txt` before extraction.
- Both tools are stored under the selected container's `C:\WinlatorTools` directory and can be removed independently. Neither is installed unless the user selects it.
- OpenCode is a standalone executable; Node.js is a separate optional runtime for other CLI tools. Node's install directory is added to the PATH of developer terminal sessions.

## Sessions and desktop

The normal Winlator desktop remains unchanged. A folder action starts a dedicated Windows command session in the same container and working folder; OpenCode runs in its own session window. Additional sessions can be created from the XServer developer-tools menu. Sessions remain separate processes while another window is focused. **Terminal sessions** filters the active-window switcher to the developer session titles so the user can return to a running session without closing it.

The ported, architecture-neutral v12.7 pieces in this branch are the X-server display-thread priority and quieter DXVK warning/error logging; the DXVK 1.10.3 async component is also offered as an optional installable component. Its archive contains normal x64 and x86 Windows DLL sets and is not auto-selected. PanVK, FEX-dependent components, Mali-specific driver paths, and the v12.7 frame-pacing/upscaling changes are intentionally not carried over here; they need their own compatibility review and device validation.

This is a Winlator/Wine terminal-window workflow, not an Android-host shell or a bundled Linux terminal. OpenCode/Node Windows x64 execution depends on the device's existing Winlator Wine/Box64 environment; CI compilation alone cannot prove runtime behavior on a particular device. No Mali compatibility is implied. Browser hand-off remains the existing optional Android-browser integration; this change does not bundle a browser or add another WebView.

## Validation

The APK must be built and tested through the repository's GitHub Actions workflow. The workflow now also runs for pull requests to `main`/`master`; no local Gradle build is part of this change.
