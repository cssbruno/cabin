# Cabin Hardware Lab

First executable prototype for an independent FYT service implementation. **Simulation only**, not a drop-in replacement for `com.syu.ms` and not connected to a vehicle.

## What works

- Separate Android app `com.cabin.hardware.lab`; launch it and toggle a simulated door.
- Toolkit transaction 1 returns CAN module 7; unsupported modules return null.
- CAN transactions 3/4 subscribe/unsubscribe using the SYU callback wire shape. Cached and changed integer fields are delivered.
- Transaction 1 accepts bounded integer command envelopes, but the simulated backend rejects every command without changing data.
- Backend abstraction for later firmware-specific hardware adapters.
- Signature-protected binding, bounded subscriptions, callback death cleanup, and service cleanup on unbind.

The app uses an explicit simulation fixture with profile 1048874 and legacy field numbers. It does not identify hardware. There is no telemetry expiry model or hardware reconnect policy yet; simulated state persists only for the bound service lifetime. No DSP/main modules, raw CAN parsing, firmware native libraries, privileged UID, boot receiver, or production hardware writes are included. Existing Cabin still connects to the vendor service.

## Build and test

```
./gradlew :hardware-lab:testDebugUnitTest :hardware-lab:assembleDebug
```

APK: `hardware-lab/build/outputs/apk/debug/hardware-lab-debug.apk`.

## Binding from a test client

Use explicit component `com.cabin.hardware.lab/com.cabin.hardware.ToolkitService`, request `com.cabin.hardware.permission.CONNECT`, and sign the client with the same certificate. This uses the observed SYU descriptors but does not claim the vendor's package, service action, or system UID. Only the transaction subset above is implemented.

## Next hardware milestone

Obtain the target unit's processor, firmware/MCU identity, installed `com.syu.ms` APK, and corresponding native libraries. Trace serial framing and startup behavior before implementing a backend. Audio/DSP needs its own verified command and feedback mapping. A real replacement must additionally satisfy existing vendor clients, power/audio lifecycle requirements, and platform access requirements; the reference 9853i APK is not evidence of compatibility with another unit.

Reference: ../documents/research/SYU-MS-9853I-INSPECTION.md. Interface implementation here is independently written from the wire contract already used by Cabin; no vendor binary or decompiled implementation is bundled.

## 0.2: inspect the target head unit

Install this APK on the actual unit, tap **Inspect this unit**, then **Export hardware report**. The system file picker saves a JSON file you can share for the next development step.

The report contains Android build/ABI information, selected non-identifying build properties, visible SYU package versions and APK/library paths, declared toolkit services, metadata for the three native library names found in the 9853i reference, and visible serial/CAN interface names. Package queries are limited to `com.syu.ms` and `com.syu.canbus`.

Inspection runs off the UI thread. It does not load native libraries, open device nodes, bind the vendor service, request root, change system files, or send vehicle commands. It excludes serial numbers, Android IDs, account data, and network identifiers. A library or device node's existence is not proof that it is usable by the app or compatible with this prototype. Missing visibility is not proof of absent hardware.

This report is an identification step, not the finished hardware backend. Native binaries and matching MCU/DSP protocol evidence may still be required afterward. There was no head unit attached to ADB during development; physical hardware compatibility remains untested.

## 0.3: firmware evidence bundle

**Export SYU firmware bundle** saves a ZIP through the system file picker. It includes readable base/split APKs for `com.syu.ms` and `com.syu.canbus`, visible readable libraries matching eight names observed in the reference JNI wrappers, and the hardware report. Each copied file has a SHA-256 hash in the report. Files are limited to 64 MiB each and 192 MiB total; unavailable/oversized candidates are reported. Failed exports must be discarded.

This copies firmware binaries only, not application data or device-node contents. It does not request root or modify the installed service. It is not a complete recovery backup. Some required libraries may have different names or be inaccessible; such missing evidence still needs a matching vendor firmware package. MCU/DSP identification and compatibility remain unverified until the target files and hardware are examined.
