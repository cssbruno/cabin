# Cabin: 100-improvement delivery

> Subsequent design/UX refinements and the latest APK verification are recorded in [UX-DESIGN-REVIEW.md](UX-DESIGN-REVIEW.md). The evidence below is the original 100-improvement delivery snapshot.

**All 100 goal areas have implementation changes: 65 goals are verified complete; 35 await physical acceptance.** The [tracker](100-IMPROVEMENT-GOALS.json) records acceptance separately: source implementation alone does not satisfy a required physical-device check.

Four agents worked on the combined implementation, integration, and acceptance audit. Existing work from the [50-feature delivery](50-FEATURE-DELIVERY.md) is preserved.

| Area | Implemented behavior |
| --- | --- |
| Connection and projection, G001–G020 | Typed failure recovery, retry controls, adapter and phone preferences, bounded session history, driver presentation, timed video trials, control visibility, picture status and touch diagnostics. |
| Audio, G021–G030 | Focus and gain behavior, microphone recovery, driver presets, backend capability explanations, frame-aligned buffering and stream stability. |
| Launcher and accessibility, G031–G050 | Folders, bulk edits and undo, app sorting, dashboard tools, settings search, accessible navigation, large-font layouts and reduced motion. |
| Vehicle and journeys, G051–G070 | Reading freshness, climate controls, tire preferences, trip filtering/export, maintenance records and parking tools. |
| Profiles, privacy and backups, G071–G080 | Driver-scoped settings, guest isolation, expanded portable backups, encrypted imports, selective restore, retention and deletion controls. |
| Diagnostics, G081–G090 | Multi-file/time-filtered log search, bounded continuation, full filtered exports, context, rotation following, bookmarks, severity summaries and report comparisons. |
| Updates and quality, G091–G100 | Release notes, network/download controls, safe resume and storage budgets, pre-update backup, skip/channel settings, startup benchmarks and minified release checks. |

## Verification

The final combined run passed **1,183 unit and Compose UI tests with zero failures**: 1,028 app tests and 155 diagnostics tests. One existing opt-in protocol-export test was skipped. Three native CTest checks and 27 Python checks also passed. Debug assembly succeeded.

The software counts and suites are recorded in [verification-software.json](verification-software.json). Per-goal implementation paths and acceptance evidence are in [connection/audio](evidence-connection.json), [launcher/accessibility](evidence-launcher.json), [vehicle/profiles](evidence-vehicle.json), and [support/release](evidence-support-release.json).

The API 27 and API 35 emulator runs exercise the minified, non-debuggable `releaseCheck` variant. This variant preserves a small list of runtime entry points needed by the separate instrumentation APK, uses a local debug signing key, and is not a production update. App and Compose implementation code remains optimized. [Quality-check instructions](../../tools/quality/README.md) document the commands, device scope and startup budgets.

The smoke flow covers denied permissions, first launch, dashboard/settings navigation, searching a synthetic log and saving a frozen export through Android's document picker. The resource check repeats no-adapter connection attempts/stops, driver changes, settings navigation and activity recreation for 30 measured cycles after five warmup cycles. Its limits are +8 threads, +12 file descriptors and +24 MiB of retained Java heap between the first and last five samples.

**Both API 27 and API 35 passed both minified device tests (four passing test executions).** Each [API 27 record](verification/api27/device.json) and [API 35 record](verification/api35/device.json) includes the tested app/instrumentation hashes and device fingerprint.

| Emulator | Thread change | File-descriptor change | Retained Java heap change | Evidence |
| --- | --- | --- | --- | --- |
| API 27 | +0.0 | +0.0 | +67.0 KiB | [30 samples](verification/api27/soak.json) |
| API 35 | +1.6 | +0.0 | +21.0 KiB | [30 samples](verification/api35/soak.json) |

Startup checks sample seven cold launches and seven warm foreground returns. The ceilings are 2500 ms cold and 500 ms warm median, with fixed application-stage measurements to identify the largest delay. These measure application launch, not phone projection readiness.

Startup results for the final production sources:

| Reference emulator | Cold median | Warm median | Evidence |
| --- | --- | --- | --- |
| Android 8.1 / API 27, Pixel 2 AOSP x86_64 | 496 ms | 29 ms | [Samples and APK hash](verification/api27/startup.json) |
| Android 15 / API 35, Pixel 2 Google APIs x86_64 | 625 ms | 30 ms | [Samples and APK hash](verification/api35/startup.json) |

Both use KVM and host graphics with two vCPUs; API 27 has 1536 MiB RAM and API 35 has 2048 MiB RAM. The [initial API 36 reference](verification/startup-initial-reference.json) documents calibration before final integration.

The compact dashboard header now reserves full-size primary actions before assigning a scrollable viewport to page navigation. Regression checks cover 352 dp width at normal and 200% text scaling, real Settings activation, page navigation and parked/moving gates.

All 309 new goal string keys are present in English, Portuguese, Spanish, French, German and Italian. Portuguese (Brazil) overrides are also provided for connection and vehicle text.

## Acceptance still requiring hardware

The 34 originally hardware-tagged goals remain open until their specified head-unit or physical Android checks are recorded. In addition, G099 needs sustained **live projection** reconnection and resource measurements: passing the no-adapter emulator soak does not validate video decoding, audio hardware or native allocator retention.

Record head-unit/device model, Android version, adapter firmware, vehicle profile and observations in the per-goal evidence before checking off these items. The UI, persistence, model and emulated lifecycle tests provide software evidence without replacing that acceptance.

CI configuration now gates release publication on the Android quality matrix. This local task does not publish a release or claim a remote GitHub Actions run.

## Review artifacts

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Minified local test APK: `app/build/outputs/apk/releaseCheck/app-releaseCheck.apk`.
- Stable goal IDs, criteria and remaining checks: [100-goal checklist](100-IMPROVEMENT-GOALS.md).

Changes remain in the working tree for review; no commit, pull request or deployment was created.
