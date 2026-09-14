# Cabin · Alpha

## Apps

This repository builds and distributes **Cabin**.
Diagnostics ship inside Cabin. The `hardware-lab` directory is an internal library,
not a third app; do not build or publish a Hardware Lab APK.

A fullscreen Android car launcher with resizable widgets, CarPlay/Android Auto projection, and compatible FYT vehicle integration.

- Android 8.1+ · English, Portuguese, Spanish, French, German and Italian.
- Joying: native CarPlay integration inside Cabin, with video/touch, Bluetooth pairing, Wi-Fi setup, audio focus, and service handoff. Requires compatible firmware privileges; physical head-unit validation is pending. See [Joying integration](documents/research/JOYING-CARPLAY.md).
- Vehicle readings and A/C controls depend on the head unit, firmware and CAN profile.
- Honda customization is built into **Car Settings → Honda instrument panel**, using the shared FYT connection. WC panel controls and mapped RZC/BNR units/tachometer settings are available here. See [supported Honda controls](documents/research/HONDA-FYT-PANEL.md).

- Diagnostics are built into **Car Settings → My car → Diagnostics**. Hardware Lab is an internal library and produces no separate diagnostic APK.

**[Download the latest APK](https://github.com/cssbruno/cabin/releases)**

Release builds may require uninstalling an earlier debug build. Updates: Settings → Launcher → Updates. Automatic daily checks; download and Android install approval are manual. Alpha builds also receive alpha releases.

Push a `v*` tag to build and publish a signed APK and `cabin-oem-integration.zip`, with SHA-256 checksums. CI does not run tests. The ZIP is an additive firmware-integration bundle, **not a self-installing FYT USB update**.

Based on [Carlink](https://github.com/lvalen91/carlink). See [LICENSE.txt](LICENSE.txt).
