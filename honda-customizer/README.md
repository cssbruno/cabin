# Honda Customizer 2.0 — original Honda head unit

Standalone Android app for the original Mitsubishi Electric Honda head unit. Built from the recovered OEM service contracts, with a Civic-focused offline catalog and live vehicle capability discovery.

## Install

Copy **`dist/Honda-Customizer-2.0-original-HU.apk`** to the original Honda head unit and use its APK installer. Open the app and connect to load the controls the vehicle actually reports. Android 4.2/API 17 is the minimum; this is a debug-signed development APK, not a Honda-signed system app.

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
| Head-unit settings | Opens Honda's exported language and system editors for language, display, clock, wallpaper, sound and climate-popup settings, preserving the OEM side effects. These are integrations with the installed Honda UI, not reimplemented preference screens. |
| Diagnostics | Opens the installed, exported Honda diagnostic application. This app does not implement raw DTC/OBD commands or invent ECU addresses. |
| Reports | Copyable connection, current-value and request-outcome report. No network permission or automatic uploads. |

## Use

1. **Vehicle settings:** Connect, wait for discovery, acknowledge that the car is parked, choose a setting and confirm Apply.
2. **Panel:** Connect to load contents; choose a preset, add/remove/move items, then Save. A draft alone does not change the car. Save before leaving the screen or switching apps. Select the active preset separately in Vehicle settings.
3. **Cameras:** Connect, inspect reported cameras, acknowledge parked, then change an available control. Dynamic guidelines require the reported steering-angle sensor.
4. **Service:** Start calibration, restore customization defaults, or load supported maintenance items. Each action has a specific confirmation.
5. **Head unit:** Open Honda's own language/system screens. The vehicle client disconnects when another editor is opened.

## Verification and limits

**67 Robolectric tests passed**, including independent Parcel decoders, service descriptor checks, capability gating, protected content, presets/defaults, value validation, action callbacks, matching readback, rejection, timeout, disconnect and stale-session behavior. Android lint passed with no errors and 10 non-blocking localization/resource warnings. APK v1 and v2 signatures verified.

No actual head unit or vehicle was connected. API 17 compatibility is checked by Android lint; runtime tests use API 28. Vehicle trim/year coverage and installation/service permissions still depend on the original hardware. No permission bypass, proprietary executable library or raw CAN injection is included.

The parked checkbox is a user acknowledgement, not a measured-speed interlock. The original services remain responsible for their hardware and driving restrictions. Synchronous Binder calls already in progress cannot be cancelled: timeout/disconnect invalidates the local session and reports uncertain outcomes; cleanup runs when the call returns. Honda's broadcast callbacks lack per-request tokens, so avoid concurrent customization editors.

Unknown values, inconsistent encodings, unsupported maintenance variants and unmapped features remain read-only or unavailable. Shared-firmware catalog entries are not all Civic features. This release implements the traced paths above; it is not a claim to reproduce every setting on every Civic generation. Camera readback confirms service-stored values, not independent ECU acknowledgement or visible camera behavior.

## Build

Use Android SDK platform 36 and a compatible JDK with the included Gradle wrapper:

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 tools/package_release.py
```

The legacy target is API 28; the Play publishing target-policy lint rule is disabled because this APK is sideloaded on OEM firmware. Other lint checks remain enabled.

`tools/extract_catalog.py` reproduces catalog labels from the local OEM research evidence, including the separately traced panel preset selector. Build inputs are already bundled. `tools/package_release.py` exports the tested APK, source ZIP, test/lint reports and checksums; it excludes caches, signing keys, local SDK configuration and proprietary decompiled source.

Protocol evidence: `../honda-cluster-analysis/oem-civic/meter-binder-protocol.md`, `camera-native-implementation.md`, `action-implementation-evidence.md`, and `../honda-cluster-analysis/full-command-trace.md`.
