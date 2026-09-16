# Cabin

## Apps

This repository builds and distributes **Cabin**.
Diagnostics ship inside Cabin. The `diagnostics` directory is an internal library,
and is packaged in the Cabin APK.

A fullscreen Android car launcher with resizable widgets, CarPlay/Android Auto projection, and compatible FYT vehicle integration.

- Android 8.1+ · English, Portuguese, Spanish, French, German and Italian.
- Carlink imports its engine libraries into Cabin and runs them in a private app process. This engine targets ARM64 Android 10 and still needs compatible platform libraries and hardware access. See [Carlink integration](documents/research/CARLINK-INDEPENDENT-ENGINE.md).
- Vehicle readings and A/C controls depend on the head unit, firmware and CAN profile.
- Honda customization is built into **Car Settings → Honda instrument panel**, using the shared FYT connection. WC panel controls and mapped RZC/BNR units/tachometer settings are available here. See [supported Honda controls](documents/research/HONDA-FYT-PANEL.md).

- Diagnostics are built into **Car Settings → My car → Diagnostics**. The internal `diagnostics` library provides these screens.

**[Download the latest APK](https://github.com/cssbruno/cabin/releases)**

Release builds may require uninstalling an earlier debug build. Updates: Settings → Launcher → Updates. Automatic daily checks; download and Android install approval are manual. Versions use plain numbers such as 0.1.

Push a `v*` tag to build and publish a signed APK and `cabin-oem-integration.zip`, with SHA-256 checksums. CI requires app, diagnostics and native transport tests to pass before signing and publishing. The ZIP is an additive firmware-integration bundle, **not a self-installing FYT USB update**.

Based on [Carlink](https://github.com/lvalen91/carlink). See [LICENSE.txt](LICENSE.txt).

New driver profiles start in dark mode. Choose Day, Night or Follow head unit in the appearance settings. Existing saved choices are preserved.

Optional crash reporting: **Settings → Logs → Share crash reports**. Requires a Sentry-configured build and starts off. See [setup and data collected](documents/CRASH-REPORTING.md).

## Repository layout

- `app/`: Cabin application and UI.
- `diagnostics/`: internal diagnostic screens, protocols and report tools.
- `tools/`: build, firmware-analysis and packaging scripts.
- `documents/`: [documentation index](documents/README.md), research and archived notes.
- `assets/`: store artwork; runtime resources belong under each module’s `src/main/res`.

CarPlay configuration stays inside **Settings → CarPlay**: wireless setup, phone selection, retry and disconnect. Carlink runs Cabin’s bundled engine; it does not launch or bind to the stock app or start the firmware CarplayServer daemon. Android may still present required permission, pairing and default-Home consent. Native loading and physical playback on the head unit remain unverified.
