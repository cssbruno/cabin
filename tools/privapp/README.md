# Cabin privileged installation

Requires **an already-rooted Magisk head unit**, authorized ADB, Python 3 and Android SDK build-tools. This script does not root the unit. Keep the factory launcher installed and park before changing settings.

Install using the same signing key as the Cabin version already installed:

```sh
python3 tools/privapp/cabin_privapp.py install --serial YOUR_ADB_SERIAL --apk /path/to/cabin-release.apk
```

Approve the existing root manager prompt if shown. Reboot manually, then verify:

```sh
python3 tools/privapp/cabin_privapp.py status --serial YOUR_ADB_SERIAL
```

Expect `SYSTEM`/`PRIVILEGED` flags and `BIND_APPWIDGET: granted=true`. Firmware may impose additional restrictions. The script has not been tested on a physical FYT unit.

To create the module ZIP without connecting a unit:

```sh
python3 tools/privapp/cabin_privapp.py build --apk /path/to/cabin-release.apk --output cabin-privapp.zip
```

For manual Magisk installation, first install the exact same APK normally through Android. Module setup verifies its hash against the installed APK; a different build is rejected. Existing output files are rejected. Development APKs require `--allow-debug`; production installations should use a release APK. `--sdk` overrides the SDK path. Installing a different signer fails without deleting app data.

## What it enables

Cabin is overlaid at `/system/priv-app/Cabin/Cabin.apk`, with an allowlist in the same system partition for its existing `BIND_APPWIDGET` request. This can remove Android widget-binding prompts. Cabin settings show actual system-app status and widget permission, plus buttons for display, sound and default launcher.

Privileged placement does **not** grant root to Cabin, manufacturer signing keys, arbitrary CAN commands, camera surfaces or unrestricted settings access. No SELinux changes, signature-check changes, platform shared UID, factory-app replacement or automatic reboot is included. Display/sound/default-launcher buttons use Android settings screens and also work without root where supported.

## Rollback and updates

Disable/remove **Cabin privileged app** in Magisk and reboot. Or disable an active module via:

```sh
python3 tools/privapp/cabin_privapp.py disable --serial YOUR_ADB_SERIAL
```

For a pending installation, remove it through Magisk before rebooting. Cabin remains normally installed with its data. The installer first uses Android PackageManager to install/update the APK; if module installation fails, that normal app update remains installed.

Normal signed APK updates can remain in `/data/app` above the system copy. Rebuild/reinstall the module when you want to refresh its base APK. Do not uninstall a current version to force a downgrade over newer app data.

References: [Magisk module guide](https://topjohnwu.github.io/Magisk/guides.html), [Android privileged permission allowlist](https://source.android.com/docs/core/permissions/perms-allowlist).

## FYT firmware-route assessment

The separate [hvdwolf FYT repository](https://github.com/hvdwolf/FYTuis7862BinRepo/tree/b5e22b6e94b27fcd7a4dadedf7bc060bfe5d2872) describes firmware-update packages that operate without preinstalled root. This is distinct from the Magisk installer above.

Read-only inspection of its Joying music package found updater binaries for UIS7862/UMS512, SC9863A/UIS8581 and SC9853I, plus vendor app replacement scripts. These are not evidence that Cabin receives Android privileged permissions, or that every Teyes/Joying firmware accepts the same provisioning process. No upstream binary or script has been executed or bundled with Cabin.

Collect a small, read-only compatibility report with authorized ADB:

```sh
python3 tools/privapp/fyt_preflight.py --serial YOUR_ADB_SERIAL
```

If ADB is not in PATH, supply `--adb /path/to/adb`. The report includes the model, chip hints, Android version, build ID and FYT manufacturer property; it omits the serial and does not request root. Chip matches are hints only. Installation and privileged-permission compatibility remain unverified until the vendor-supported provisioning process is established. This is **not a flashable installer**.

## FYT debug USB ZIP (manual CI run)

The **Build FYT Debug USB ZIP** GitHub Actions workflow builds `app-debug.apk` and a FYT-style USB ZIP. It is deliberately manual and uploads an artifact; it does not attach anything to a release.

Before running it, add the repository Actions secret `FYT_UPDATER_BINARIES_BASE64`. Its value must be the base64 of the vendor update ZIP that supplies the three matching router binaries: `lsec6315update`, `lsec6316update`, and `lsec6521update`. The binaries are vendor/firmware-specific, so they are not committed to Cabin or substituted with a generic executable.

The generated ZIP contains `Cabin.apk`, the router binaries and three `lsec_updatesh` scripts. The selected script verifies the debug APK checksum, backs up an existing Cabin OEM APK to the USB storage and writes only `/oem/app/zeno.carlink/Cabin.apk`. It refuses an existing `.bak` file, never replaces factory apps, remounts partitions or reboots. It can still be incompatible with a particular FYT vendor package format and can brick a head unit; use a confirmed backup and stable power while parked.

## CI OEM integration ZIP

Pushing a `v*` tag runs the existing release workflow. It builds the signed release APK, verifies it, then creates `cabin-oem-integration.zip` from that exact APK. Both files, their SHA-256 checksums, and `update.json` are attached to the GitHub release and saved as a workflow artifact. Tests are not run in CI. Signing uses the existing `CABIN_KEYSTORE_BASE64` and `CABIN_KEYSTORE_PASSWORD` secrets.

The ZIP contains additive system APK/permission files, a manifest and an explicit integration README. It includes `install.sh` and `rollback.sh` for an existing authorized root environment with running Android and writable `/system`. It includes no FYT updater binaries or automatic updater hooks and is not a self-installing USB-flash package. The exact APK must first be installed normally; existing system destinations are rejected. Rollback removes only unchanged files owned by the same bundle. Scripts never remount partitions, change SELinux policy, or reboot.

Local equivalent:

```sh
python3 tools/privapp/build_oem_bundle.py --apk /path/to/cabin-release.apk --output cabin-oem-integration.zip
```
