# Cabin · Alpha

## Apps

This repository builds and distributes **Cabin**.
Diagnostics ship inside Cabin. The `diagnostics` directory is an internal library,
and is packaged in the Cabin APK.

A fullscreen Android car launcher with resizable widgets, CarPlay/Android Auto projection, and compatible FYT vehicle integration.

- Android 8.1+ · English, Portuguese, Spanish, French, German and Italian.
- Vehicle readings and A/C controls depend on the head unit, firmware and CAN profile.
- Honda customization is built into **Car Settings → Honda instrument panel**, using the shared FYT connection. WC panel controls and mapped RZC/BNR units/tachometer settings are available here. See [supported Honda controls](documents/research/HONDA-FYT-PANEL.md).

- Diagnostics are built into **Car Settings → My car → Diagnostics**. The internal `diagnostics` library provides these screens.

**[Download the latest APK](https://github.com/cssbruno/cabin/releases)**

Release builds may require uninstalling an earlier debug build. Updates: Settings → Launcher → Updates. Automatic daily checks; download and Android install approval are manual. Alpha builds also receive alpha releases.

Push a `v*` tag to build and publish a signed APK and `cabin-oem-integration.zip`, with SHA-256 checksums. CI does not run tests. The ZIP is an additive firmware-integration bundle, **not a self-installing FYT USB update**.

Based on [Carlink](https://github.com/lvalen91/carlink). See [LICENSE.txt](LICENSE.txt).

## Repository layout

- `app/`: Cabin application and UI.
- `diagnostics/`: internal diagnostic screens, protocols and report tools.
- `tools/`: build, firmware-analysis and packaging scripts.
- `documents/`: [documentation index](documents/README.md), research and archived notes.
- `assets/`: store artwork; runtime resources belong under each module’s `src/main/res`.
