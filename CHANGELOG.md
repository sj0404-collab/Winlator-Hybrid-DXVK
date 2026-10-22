# Changelog

## 12.6.5-Vortek-Mali

- Added per-executable DXVK profiles: synchronous DXVK 1.10.3, GPL async 2.4.1, and the existing hybrid package. Profiles are keyed by full executable path and migrate prior async settings.
- Kept the hybrid profile as the default. Async is opt-in and the UI warns that the GPL async package may require newer Vulkan support than some Mali/Vortek devices expose.
- Removed unconditional async environment/config flags, forced 4 GB DXVK memory limits, GTA V-only memory overrides, and globally forced barrier relaxations from generated DXVK configuration.
- Reduced the new-container Mesa shader-cache budget to 256 MB and made the default CPU policy less aggressive for sustained thermal behavior.
- Raised default ALSA/Pulse latency target to 32 ms and made ALSA buffer sizing robust against invalid channel counts, sample rates, and latency values.
- Changed the home screen to a game/app library with direct actions for adding software, opening graphics/FPS settings, and managing containers.
- GitHub Actions now runs on feature branches, uploads a versioned APK plus SHA-256 checksum, and can publish a GitHub Release when a `v*` tag is pushed.

## Validation limits

The Android build and unit tests must complete on GitHub Actions; the local sandbox could not resolve Maven artifacts because its TLS handshake to Maven Central fails. DXVK frame pacing and audio behavior still require device testing on actual Mali/Vortek hardware; the repository tests cannot establish FPS or audio quality on a physical device.
