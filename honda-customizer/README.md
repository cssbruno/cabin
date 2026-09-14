# Honda Customizer 3.1.0 — Joying / FYT

An Android app for FYT head units using the installed `com.syu.ms` service. It has no original Honda/Mitsubishi service dependency, permission, editor or executable client code in the APK.

## FYT connection

Connect tries `com.syu.ms.toolkit` (`app.ToolkitService`) and obtains CANBUS module 7. If that route is unavailable, rejected or does not finish discovery, it tries `com.syu.ms.canbus` (`app.ModuleService`) with the direct module Binder. Firmware-specific exported service names are resolved inside `com.syu.ms`. Each route has its own worker so a stuck toolkit call cannot block the direct route. Both contracts are traced in `../../syu-ms-analysis/evidence/app.ToolkitService.smali`, `app.ModuleService.smali` and the Joying manifest.

The detected profile comes from FYT field 1000. An unmapped profile is reported as a connected FYT service with no mapped controls, rather than pretending the FYT service is absent. **Report → Copy** includes the service version, unit model, attempted connection routes, exact profile and received settings. It does not upload anything or issue vehicle commands.

## Decoder-specific controls

| Decoder profiles | Controls |
|---|---|
| WC: `0x40141`, `0x50141`, `0x60141`, `0xB0141`, `0xC0141`, `0xD0141` | Up to 13 panel controls, with the stock per-row restrictions: navigation directions, warning messages, Type 1–3, reverse tone, speed/message/idle-stop reminders, eco lighting, signs, alarm volume, trip A/B reset and outside-temperature adjustment. Command 106. |
| RZC Civic: `0x10012a`, `0x11012a`, `0x29012a` | Speed/distance units, tachometer display, and the separate vendor tachometer option. Command 105, keys 21/22/35; feedback 77/78/87. |
| BNR direct settings route: `0x6012a`, `0x7012a`, `0x8012a`, `0x9012a`, `0xA012a`, `0xB012a`, `0xF012a`, `0x28012a` | The two tachometer options. Units remain excluded because the stock entry route hides that row. |

These are explicit firmware profile IDs, not model-year guesses. WC commands cannot be sent through RZC/BNR settings. The second tachometer option retains the vendor wording because its distinction from display visibility is unresolved.

Connect, wait for live values, acknowledge parked, choose a setting and confirm. Only the active profile's fields are subscribed. Missing, invalid or older-than-30-second values disable editing; Refresh requests cached FYT values again. Successful Binder completion alone is not confirmation: the app also requires matching feedback received after dispatch. A timeout, changed profile or disconnect invalidates pending actions. An in-progress Binder transaction cannot be cancelled, and broadcast feedback has no request ID; do not operate concurrent vehicle editors.

Language, full panel-content editing, OEM camera controls, maintenance, diagnostics and head-unit settings are not implemented here. The OEM catalog IDs are never substituted into FYT commands.

## Build and checks

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:assembleReleaseAndroidTest --no-daemon
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/run_emulator_smoke.py --serial emulator-5554
```

APK: `app/build/outputs/apk/release/app-release.apk`, version code 6. Signed with the local development certificate; installing over an earlier copy requires a matching signing certificate. Minimum Android API 17 is a compatibility floor; the target hardware is the FYT head unit.

31 service/protocol regression tests and 7 smoke-result parser tests pass. Lint has no errors. These tests cover direct-service fallback (including a blocked toolkit), exact decoder commands, field subscriptions, fresh feedback, write failure, timeout and disconnect. They do not establish operation on the user's physical FYT hardware. The exact installed firmware/decoder has not been supplied, and no vehicle is connected to this development environment.

Active sources: `app/src/fyt`, `fytTest`, `fytAndroidTest`. Original Honda sources/tests and `README-OEM-reference.md` are historical reference only; Gradle excludes their executable code. Old `original-HU` APKs in `dist` are not FYT builds. The distribution packaging script requires fresh API17 and modern-emulator results and emits `Honda-Customizer-3.1.0-FYT.apk` only after those checks.

Command evidence: `../documents/research/HONDA-FYT-PANEL.md`, `../../honda-cluster-analysis/fyt-additional/other-honda/findings.md`, and the saved `Acrivity_RZC_17CRVSettings`, `AcrivitySiYuSettings`, `HondaIndexActi`, and `FinalCanbus` bytecode. No firmware was flashed or vehicle commands sent during development.
