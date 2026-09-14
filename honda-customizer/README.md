# Honda Customizer 2.1.0 — original Honda head unit

Standalone Android app for the original Mitsubishi Electric Honda head unit. Built from the recovered OEM service contracts, with a Civic-focused offline catalog and live vehicle capability discovery.

## Install

Copy **`dist/Honda-Customizer-2.1.0-original-HU.apk`** to the original Honda head unit and use its APK installer. Open the app and connect to load the controls the vehicle actually reports. Android 4.2/API 17 is the minimum; this is a non-debuggable release build signed with the existing development certificate, not a Honda-signed system app. The published APK updates the earlier 2.0 development build in place; locally built/CI APKs use their own development certificate.

## Implemented controls

| Area | Implementation |
|---|---|
| Vehicle settings | Native enum/range editors for known values reported by VehicleInfoManager: language, units, trip reset behavior, alarms, lighting, locks, mirrors and equipped Civic-variant controls. |
| Civic catalog | 68 OEM setting IDs tied to the investigated Civic feature candidates. Offline entries never authorize writes. The complete 313-entry source catalog remains available internally to interpret live replies. |
| Cluster language | Original regional vehicle settings, including the source-backed Portuguese/English mapping. Head-unit language is separate. |
| Tachometer | Stores and reads back the original UnitInfo preference, then requests and verifies the vehicle change. Partial failures are reported explicitly. |
| Panel contents | Native add/remove/reorder editor, protected-item handling, live content availability and runtime capacity. |
| Panel presets | Edit presets 1–3, load defaults into a draft, save with acknowledgement and matching ordered-content readback. Select the active preset through Vehicle settings → Panel configuration. |
| Cameras | Native rear fixed/dynamic guidelines; LaneWatch activation, 0/2-second display duration and reference guides. Live hardware checks, fresh full-Bundle writes and service readback. |
| Camera defaults | Original per-camera defaults with readback. Rear defaults also reset the parking-sensor view setting when present; confirmation discloses this. |
| TPMS calibration | Dedicated OEM action, gated by live discovery. Reports request acknowledgement and refreshed state, not completion of the later driving calibration. |
| Vehicle defaults | Dedicated OEM reset followed by complete capability/value rediscovery. It resets vehicle customization, not Android apps or files. |
| Maintenance | Reads live maintenance variant/support flags, presents named supported items and all-due reset where applicable; uses original reset mode and callback flow. Reloaded status does not prove physical service was performed. |
| Native head-unit settings | Clock format/display/style and built-in backgrounds; climate-popup duration; touch sensitivity; voice tips/prompts; swipe direction and four-way/volume gestures. Destination-aware options, fresh baseline checks and service readback. |
| Native display adjustment | Brightness, contrast and black level through the original AV service. Preserves other display fields; editing requires a reported active day/night mode and type 0. |
| Original head-unit editors | Menu color, head-unit language, imported wallpaper and sound retain Honda's editors for their extra system/file/locale effects. |
| Native diagnostic readings | Reads head-unit hardware error history and telematics DTC rows. Can delete only the head-unit hardware history after a specific confirmation, then verifies empty readback. Honda's diagnostic service requires its OEM signature permission; a normal development-signed APK cannot obtain that permission by declaring it. The app explains denied access. Engine/transmission OBD reads, generic DTC clear and Dealer Clear All are not implemented. |
| Compatibility / physical checks | Inspects installed services, versions, permissions and Android build. Reads raw Honda model/destination identifiers only when UnitInfo is accessible. Copies/exports a report; physical observations begin Not tested and stay explicitly user-reported. |
| Reports | Copyable connection, current-value and request-outcome report. No network permission or automatic uploads. |

## Use

1. **Vehicle settings:** Connect, wait for discovery, acknowledge that the car is parked, choose a setting and confirm Apply.
2. **Panel:** Connect to load contents; choose a preset, add/remove/move items, then Save. A draft alone does not change the car. Save before leaving the screen or switching apps. Select the active preset separately in Vehicle settings.
3. **Cameras:** Connect, inspect reported cameras, acknowledge parked, then change an available control. Dynamic guidelines require the reported steering-angle sensor.
4. **Service:** Start calibration, restore customization defaults, or load supported maintenance items. Each action has a specific confirmation.
5. **Head unit:** Connect for native preferences or open Display adjustment. Confirm parked before applying a supported value. Original Honda editors remain available for language, menu color, imported wallpaper and sound.
6. **Service → Diagnostic readings:** Connect, read hardware history or telematics records, and copy the report. Deleting hardware history has its own parked acknowledgement and confirmation. An unavailable OEM signature permission is a blocker; no permission bypass is included.
7. **Check unit:** Run read-only checks and copy/export the compatibility report. Record physical behavior only after observing it on the actual parked car. A successful package check is not a successful vehicle test.

## Verification and limits

**165 Robolectric unit/integration tests passed**, with zero failures, errors or skips. Coverage includes independent Parcel decoders, service descriptor checks, capability gating, destination-specific options, full readback, callback request IDs, rejection, timeout, foreground loss, disconnect and observer-triggered reconnects. Android lint passed with no errors and 11 non-blocking localization/resource warnings. APK v1 and v2 signatures verified.

**11 installed-APK emulator checks passed on each of Android 4.2/API17 and Android 16/API36.1**, at 800 × 480. These cover release installation, Civic language search, all native screens, compatibility-report output and unavailable-service gating. Seven Python checks verify that failed, repeated, missing or incorrectly named emulator results are rejected. Emulator checks cannot validate OEM services or vehicle behavior.

No actual head unit or vehicle was connected. JVM service-contract tests use Android API 28; the release also runs the installed APK on Android 4.2/API 17 and modern Android emulators. Vehicle trim/year coverage and installation/service permissions still depend on the original hardware. No permission bypass, proprietary executable library or raw CAN injection is included.

The parked checkbox is a user acknowledgement, not a measured-speed interlock. The original services remain responsible for their hardware and driving restrictions. Synchronous Binder calls already in progress cannot be cancelled: timeout/disconnect invalidates the local session and reports uncertain outcomes; cleanup runs when the call returns. Honda's broadcast callbacks lack per-request tokens, so avoid concurrent customization editors.

Unknown values, inconsistent encodings, unsupported maintenance variants and unmapped features remain read-only or unavailable. Shared-firmware catalog entries are not all Civic features. This release implements the traced paths above; it is not a claim to reproduce every setting on every Civic generation. Camera readback confirms service-stored values, not independent ECU acknowledgement or visible camera behavior.

## Build

Use Android SDK platform 36 and a compatible JDK with the included Gradle wrapper:

```sh
export ANDROID_HOME=/path/to/Android/Sdk
# Recreate deterministic lint reports so packaging can verify their freshness.
rm -f app/build/reports/lint-results-release.xml app/build/reports/lint-results-release.html
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease :app:assembleReleaseAndroidTest --rerun-tasks
# Start an Android API17 emulator, wait for boot, and specify its exact serial:
python3 tools/run_emulator_smoke.py --serial emulator-5554
# Repeat on an API35+ emulator; both runs must test the same APK/test APK.
python3 tools/run_emulator_smoke.py --serial emulator-5556
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/package_release.py
```

Release packaging requires matching successful API17 and API35+ smoke results. The CI matrix independently tests API17 and API35, and the smoke parser checks every expected test name so duplicate or omitted checks cannot pass by count alone.

The legacy target is API 28; the Play publishing target-policy lint rule is disabled because this APK is sideloaded on OEM firmware. Other lint checks remain enabled.

`tools/extract_catalog.py` reproduces catalog labels from the local OEM research evidence, including the separately traced panel preset selector. Build inputs are already bundled. `tools/package_release.py` rejects stale/failed results, checks the tested APK hashes, version and signature, then exports the APK, source ZIP, unit/lint/emulator reports and checksums; it excludes caches, signing keys, local SDK configuration and proprietary decompiled source.

Protocol evidence: `../honda-cluster-analysis/oem-civic/meter-binder-protocol.md`, `camera-native-implementation.md`, `action-implementation-evidence.md`, and `../honda-cluster-analysis/full-command-trace.md`.

## 2.0.1 changes

Cancelled service sessions cannot start delayed tachometer preference writes or reuse a new connection for an old panel save. Tachometer requests verify both the head-unit preference and vehicle response, including when cached values already match. Panel defaults refresh capacity, saves publish fresh capabilities, and malformed outgoing IDs are rejected. Camera failures survive screen cleanup; interrupted writes report uncertain outcomes. Head-unit editor authorization clears when leaving the screen. The added GitHub workflow repeats unit, lint, build and emulator checks for Honda changes.

## 2.1.0 changes

Adds native head-unit preference and AV display editors, narrow OEM diagnostic history/telematics workflows, and a read-only compatibility/physical-observation report. Unsupported values and signature-protected diagnostics remain unavailable with an explanation. Tests cover actual Parcel contracts, readback, wrong/missing permissions, cancellation, foreground loss and observer-triggered reconnect races. Minimum Android 4.2 runtime checks and a two-version CI emulator matrix replace lint-only minimum-version coverage.
