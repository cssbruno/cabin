# Cabin · Alpha

A fullscreen Android car launcher with resizable widgets, CarPlay/Android Auto projection, and compatible FYT vehicle integration.

- Android 8.1+ · English, Portuguese, Spanish, French, German and Italian.
- Vehicle readings and A/C controls depend on the head unit, firmware and CAN profile.

**[Download the latest APK](https://github.com/cssbruno/cabin/releases)**

Release builds may require uninstalling an earlier debug build. Updates: Settings → Launcher → Updates. Automatic daily checks; download and Android install approval are manual. Alpha builds also receive alpha releases.

Push a `v*` tag to build and publish a signed APK and `cabin-oem-integration.zip`, with SHA-256 checksums. CI does not run tests. The ZIP is an additive firmware-integration bundle, **not a self-installing FYT USB update**.

Based on [Carlink](https://github.com/lvalen91/carlink). See [LICENSE.txt](LICENSE.txt).
