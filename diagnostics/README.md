# Cabin diagnostics

Internal Cabin diagnostics library with **live vendor CAN reception**, simulation and replay. It is not a drop-in replacement for `com.syu.ms`. No vehicle has been connected for validation.

Open **Cabin → Car Settings → My car → Diagnostics** to subscribe to the installed vehicle service's CAN module 7. The receiver tries the toolkit and direct CAN service routes, requests cached and changed integer fields, reports the vendor profile and callback count, reconnects after failures, and releases subscriptions when the screen closes. **Save live CAN report** exports the current diagnosis. This receive path sends no vehicle commands and does not open the MCU serial device. Initial/cached vendor callbacks are not proof of fresh vehicle traffic; use a physical door or climate change to check that the count and fields update. The landing screen starts this live receiver automatically and never substitutes simulated values. **Offline test bench (simulated data)** and saved-capture examples are explicit development tools.

Version **0.6** adds typed module Binder calls with one-way callbacks, driver-1 radio command previews, and all 36 C7604 EQ coefficient/register paths. See [0.6 module implementation](../documents/research/JOYING-MODULE-RUNTIME.md).

Version **0.5** adds an independent native serial/I²C library, receive runtime, reference Honda CAN decoding, power-sequence observation, C7604 packet writing, and a signature-protected diagnostics service. Open **Open replacement bench → Run built-in example** to inspect synthetic packet replay and save a JSON report. Raw serial device access remains a library integration point; the new live CAN screen receives through the installed vendor service.

See [implemented scope, evidence, service contract and remaining work](../documents/research/JOYING-REPLACEMENT-RUNTIME.md).

Version 0.4 adds an independent MCU frame codec and **offline capture analysis**.
Open **Analyze saved MCU capture → Analyze example data** to try it, or select a
saved raw binary capture (8 MiB maximum) and export its JSON analysis. Example data
is synthetic. This checks framing; it does not decode vehicle values or replace the
live service. See [MCU framing evidence and next milestones](../documents/research/JOYING-MCU-FRAMING.md).

## What works

- Open Cabin → Car Settings → My car → Diagnostics for inspection, live CAN, capture analysis and simulation.
- Toolkit transaction 1 returns CAN module 7; unsupported modules return null.
- CAN transactions 3/4 subscribe/unsubscribe using the SYU callback wire shape. Cached and changed integer fields are delivered.
- Transaction 1 accepts bounded integer command envelopes, but the simulated backend rejects every command without changing data.
- Backend abstraction for later firmware-specific hardware adapters.
- Signature-protected binding, bounded subscriptions, callback death cleanup, and service cleanup on unbind.

The app uses an explicit simulation fixture with profile 1048874 and legacy field numbers. This paragraph describes the original simulator: its state persists only for the bound service lifetime. The separate 0.5 replacement runtime adds five-second telemetry expiry and manual session restart, a limited CAN decoder and DSP packet library. Full DSP/main modules, privileged UID, boot receiver and production handover remain unimplemented. Existing Cabin still connects to the vendor service.

## Source layout

- `src/main/java/com/cabin/hardware`: internal diagnostic screens and vehicle-service clients.
- `src/main/java/com/cabin/hardware/replacement`: protocol models and offline test tools.
- `src/main/java/com/cabin/reports`: report export, event journal and live debug UI.
- `src/main/cpp`: native hardware interfaces.
- `src/test`: JVM and native tests.

This is an Android library consumed by `:app`; it has no separate APK. Existing Java package names are retained to preserve component identities.

## Build and test

```
./gradlew :diagnostics:testDebugUnitTest :app:assembleDebug
```

Cabin APK: `app/build/outputs/apk/debug/app-debug.apk`. This library produces no standalone diagnostic APK or launcher entry.

## Binding from a test client

Use explicit component `zeno.carlink/com.cabin.hardware.ToolkitService`, request `com.cabin.hardware.permission.CONNECT`, and sign the client with the same certificate. This uses the observed SYU descriptors but does not claim the vendor's package, service action, or system UID. Only the transaction subset above is implemented.

## Next hardware milestone

Obtain the target unit's processor, firmware/MCU identity, installed `com.syu.ms` APK, and corresponding native libraries. Validate the implemented serial framing and reconstruct target startup behavior before enabling a live backend. Audio/DSP needs its own verified command and feedback mapping. A real replacement must additionally satisfy existing vendor clients, power/audio lifecycle requirements, and platform access requirements; the reference 9853i APK is not evidence of compatibility with another unit.

Reference: ../documents/research/SYU-MS-9853I-INSPECTION.md. Interface implementation here is independently written from the wire contract already used by Cabin; no vendor binary or decompiled implementation is bundled.

## 0.2: inspect the target head unit

Install Cabin on the actual unit and open Diagnostics, tap **Inspect this unit**, then **Export hardware report**. The system file picker saves a JSON file you can share for the next development step.

The report contains Android build/ABI information, selected non-identifying build properties, visible SYU package versions and APK/library paths, declared toolkit services, metadata for the three native library names found in the 9853i reference, and visible serial/CAN interface names. Package queries are limited to `com.syu.ms` and `com.syu.canbus`.

Inspection runs off the UI thread. It does not load native libraries, open device nodes, bind the vendor service, request root, change system files, or send vehicle commands. It excludes serial numbers, Android IDs, account data, and network identifiers. A library or device node's existence is not proof that it is usable by the app or compatible with this prototype. Missing visibility is not proof of absent hardware.

This report is an identification step, not the finished hardware backend. Native binaries and matching MCU/DSP protocol evidence may still be required afterward. There was no head unit attached to ADB during development; physical hardware compatibility remains untested.

## 0.3: firmware evidence bundle

**Export SYU firmware bundle** saves a ZIP through the system file picker. It includes readable base/split APKs for `com.syu.ms` and `com.syu.canbus`, visible readable libraries matching eight names observed in the reference JNI wrappers, and the hardware report. Each copied file has a SHA-256 hash in the report. Files are limited to 64 MiB each and 192 MiB total; unavailable/oversized candidates are reported. Failed exports must be discarded.

This copies firmware binaries only, not application data or device-node contents. It does not request root or modify the installed service. It is not a complete recovery backup. Some required libraries may have different names or be inaccessible; such missing evidence still needs a matching vendor firmware package. MCU/DSP identification and compatibility remain unverified until the target files and hardware are examined.

## Radio feedback development (0.7)

Capture replay now optionally decodes reference driver-1 radio band/current-frequency feedback, with expiring callbacks and historical diagnostics. See [radio feedback contract and validation](../documents/research/JOYING-RADIO-FEEDBACK.md). Historical standalone diagnostic APKs have been removed from local artifacts; the current implementation ships inside Cabin.

## Debug reports and head-unit export compatibility

Live CAN reports include a bounded history of the latest 100 connection/subscription/callback/error events. The same diagnostic journal records Cabin CAN and Joying CarPlay connection failures. It contains app-owned events, not a system logcat dump.

JSON report export captures its contents before opening another app. Use **Save to Downloads**, **Share file**, or **Copy report**; no document picker is required. Android 10+ saves to `Downloads/Cabin` with pending-file cleanup on failure. Earlier Android versions save to the app's external Documents directory. File sharing grants temporary read access to a dedicated reports directory. Up to 20 export snapshots are retained in app-private storage. Firmware ZIP export and capture import still use the document picker.

Keep this fallback: some head units lack a working document picker, and pausing the live screen stops its receiver. Never wait until an export activity returns to collect the live report.

## Live debug menu

Open **Diagnostics → Live debug** to watch event details without leaving or stopping the receiver. Cabin also offers **Live debug** on the Joying CarPlay screen and beside the vehicle diagnostics export button. Filter All/CAN/CarPlay/export, pause and resume the display, clear recent events, or export the captured view using Save/Share/Copy. Pausing freezes the display, not data reception. The newest events appear first; the last 100 events are retained. Closing the dialog removes its refresh timer. Diagnostics shares Cabin's process and journal, including Cabin CAN and CarPlay events.

## Live receiver reliability and report sources

FYT discovery has a 10-second deadline covering toolkit lookup and subscriptions. Vendor calls and unregister cleanup run on separate, bounded toolkit/direct workers; a stuck route cannot prevent fallback or closing the screen. Android cannot cancel a Binder call already in progress; its route remains unavailable until that call and cleanup return.

Each reading expires from `lastReceivedFields` after 30 seconds without its own callback. Reports retain explicitly historical fields and ages; no callbacks for 30 seconds changes status to `STALE_VENDOR_DATA`. This measures callback recency, not independent vehicle liveness: cached callbacks can be recent, and unchanged values may not be rebroadcast.

Offline test-bench reports label the built-in sample `synthetic-example` and imported captures `user-selected-file`. Neither represents a live vehicle connection.
