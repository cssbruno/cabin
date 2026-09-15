# Joying replacement runtime — Hardware Lab 0.5

**Superseded details:** [current module implementation](../../diagnostics/src/main/java/com/cabin/hardware/replacement/ModuleToolkitBridge.java) replaces the replacement service Binder bridge, adds radio command previews and C7604 EQ calculation. The descriptions below preserve the 0.5 baseline; consult 0.6 for current callback/readback behavior.

The independent implementation now builds as an Android APK, with its own serial/I²C JNI library. It is a **development bench, not a complete drop-in replacement for SYU**. The app starts with no device nodes open. Cabin's production integrations still bind the installed vendor service.

## Try it

1. Open Cabin → Car Settings → My car → Diagnostics → **Open replacement bench**.
2. Select **Run built-in example** to replay synthetic door/climate/sleep packets.
3. Inspect packet counts, historical decoded fields, and the observed power sequence.
4. **Save report** exports JSON through Android's file picker.
5. For an actual capture, select **Open MCU receive capture**. Enable the Honda option only for profile `0x10012a` with normal door and temperature order. Imports are raw binary, at most 8 MiB, with the outer MCU framing still present. Mixed transmit/receive captures are not suitable for interpreting vehicle state.

The example is explicitly synthetic, not a captured vehicle trace. Playback currently runs as fast as the decoder can consume it. At EOF, current vehicle fields clear; `lastDecodedCanFieldsHistorical` remains available for inspection. A report never presents historical values as live data.

## Implemented scope

| Component | Implemented behavior | Remaining boundary |
| --- | --- | --- |
| Native serial | Own C++/JNI; raw 8N1; 38400/115200; bounded buffers; poll deadlines; cancellation pipe; partial writes; descriptor lifetime via shared ownership | No target access/ownership verification, startup handshake, automatic reconnection, or device deployment |
| Platform selection | Explicit FYT family/subtype to serial node and baud rules from pinned firmware | Family/subtype must be verified; `ums512` alone is insufficient; mailbox result cannot be guessed |
| MCU | Existing bounded streaming codec; resynchronization; checksum/length metrics; per-session decoder; receive runtime | No command scheduler, hardware startup, or semantic transmit support |
| Replay | Owned input stream, 8 MiB limit, EOF/error/close handling, rejects writes | Raw receive captures only; no capture acquisition or automatic direction identification |
| CAN | Strip outer `E3`; preserve last raw packet; selected Honda `0x10012a` door and climate subset | Other profiles, remaining Honda fields, decoder negotiation and transmit commands unimplemented |
| State | Five-second per-field freshness; empty state on EOF/failure/close; explicit historical packet values | Timeout is a bench policy, not a measured vehicle cadence |
| Power | Observe ordered `01 00 89 53`, `54`, `55`; detect out-of-order requests | No ACKs, OS sleep, wake, readiness handling, boot receiver, or power control |
| C7604 | I²C address `0x1c`; `84`/`89` coefficient packet packing; signed 24-bit MSB first; `A4 00 00` commit; failure poisons writer | No verified initialization, coefficient generation/register map, sound Binder service, feedback, or amplifier driver |
| Services | Signature-protected diagnostics service; explicit replay controls within app; optional existing CAN Binder subset; unsupported modules return null | Not package/action/UID compatible with SYU; remaining toolkit modules and transaction 2 readback unimplemented |
| Inventory | FYT platform/MCU properties, ttyMbx and I²C node metadata added | Directory visibility does not establish permissions or compatibility |

### CAN evidence and fields

Evidence is the local APK pinned in Joying replacement audit (historical external audit), SHA-256 `4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577`. `f0/wp.V` selects `module/canbus/v` for low profile word `0x12a`; `v.M2` implements the receive branches. The implementation deliberately accepts only full profile `0x10012a` to avoid applying variant-specific logic to other vehicles.

Input to `HondaCanDecoder` starts immediately after the outer `E3`. Its byte 0 selects the CAN branch; byte 1 is not interpreted as a verified inner length/checksum. Outer MCU checksum validation precedes dispatch.

- `M2` packed-switch `0x24` / `pswitch_aab`: byte 2 bits 6,7,4,5,3,2 → fields 37,38,39,40,41,36. Nonzero `f0/tp.U` swaps the paired door bits; constructor option represents this setting. The variant-specific extra byte is not decoded.
- `M2` packed-switch `0x21` / `pswitch_bfb`, common branch `cond_c57`: byte 2 bits 7..1 → fields 32,24,21,51,20,30,23. Byte 3 bits 7,6,5 → fields 28,26,27; low nibble clamped to seven → field 29.
- Bytes 4/5 → fields 25/31, reversed when `persist.fyt.reversetemp` is selected. Raw zero becomes vendor sentinel -2; raw `FF` becomes -3. Other bytes stay unsigned, **not converted into a guessed Celsius value**.
- Byte 6 bits 7,0,2 → fields 22,33,34. Remaining climate fields are absent. The climate branch requires at least ten bytes, consistent with the referenced packet accesses.

Door/climate tests exercise these mappings and truncation handling. Physical validation is still required.

### Service interface

Explicit component: `com.cabin.hardware.lab/com.cabin.hardware.ReplacementService`.
The caller needs `com.cabin.hardware.permission.CONNECT` and the same signing certificate.

Descriptor: `com.cabin.hardware.IReplacementDiagnostics`. Synchronous requests contain only the interface token:

- Transaction 1 → exception header, then JSON string report.
- Transaction 2 → exception header, then current reference CAN toolkit Binder or null.

The toolkit uses the existing observed SYU descriptors and supports module 7 only. The replacement mode rejects unsupported integer commands with an exception. Transactions 3/4 subscribe/unsubscribe. Missing or expired data emits a callback with null integer/float/string arrays, rather than a fabricated zero. Clients must handle null/invalidation. The original simulator retains its previous command-rejection counting behavior.

Replay starts create a new runtime/toolkit. Previously acquired toolkit Binders close; clients must reacquire transaction 2 after a replay starts. The activity starts replays locally; exported diagnostics calls cannot open files/devices, change profiles, or start hardware writes.

Notifications poll current snapshots on a separate, single executor at 250 ms intervals, so synchronous legacy callbacks cannot block the serial receive loop. A hung remote callback can still stall other subscribers on that executor; production callback isolation remains work. Short replay sessions may finish between notification ticks; use the historical report for offline playback results.

### Native integration boundary

`NativeSerialTransport` can be supplied directly to `ReplacementRuntime.start` by a future platform adapter after establishing exclusive ownership and permissions. The runtime itself never calls `write`. Opening a serial port changes its termios settings. No UI or exported service currently enables this path.

`TIOCEXCL` prevents later unprivileged opens; it **does not prove** the vendor process has released an already-open port. Two readers would consume each other's bytes. This APK neither stops the vendor service nor performs a live handover.

`C7604Writer.open` requires an explicit `/dev/i2c-N` path. It uses `I2C_SLAVE`, deliberately refusing a kernel-owned address instead of the reference library's `I2C_SLAVE_FORCE`. It cannot establish exclusive ownership against another userspace process. I²C kernel writes have no application-enforced deadline/cancellation; use only on an appropriately owned worker. After a failed write or commit, the writer rejects subsequent writes until the caller closes it and performs a verified reinitialization with a new writer. Validation errors send nothing.

No proprietary library is loaded or distributed. Included native code is independently written. ABI packaging: `arm64-v8a`, `armeabi-v7a`, `x86_64`; minimum Android API 27.

## Verification

Android checks:

```sh
./gradlew :diagnostics:testDebugUnitTest :diagnostics:assembleDebug :diagnostics:lintDebug --offline
```

Host serial checks use pseudo-terminals and `/dev/null`, never a vehicle device:

```sh
cmake -S diagnostics/src/main/cpp -B /tmp/cabin-native-build
cmake --build /tmp/cabin-native-build
ctest --test-dir /tmp/cabin-native-build --output-on-failure
```

Current validation: 47 JVM/Robolectric tests passed; the native pseudo-terminal test passed; Android lint completed with zero errors and 31 warnings. The APK contains the independent native library for all three ABIs and the synthetic replay asset.

Coverage includes valid/invalid framing, CAN orientation and temperature sentinels, expiry, EOF/failure/restart/close, runtime no-transmit behavior, Binder rejection/invalidation, JSON report serialization, DSP packet ordering/failure, native serial timeout/cancellation/disconnect and bounded writes. Android JNI is compiled for all three ABIs; it has not been executed on the Joying. No physical I²C transfers or vehicle sleep/wake tests have occurred.

## Work required to finish the replacement

The known module factory list is a catalog, not an implementation. Radio, Bluetooth, DVD, iPod, TV, TPMS, DVR, steering commands, customer functions, OBD, tests, CAN update, amplifier, emitter, G-sensor, gesture and local sensors still require individual implementations. SOUND also needs initialization/feedback and MAIN needs lifecycle/audio routing.

Next required input is the actual head unit's inventory/firmware, MCU version, selected CAN decoder/profile, DSP sound ID and direction-labeled captures. Then implement and validate startup/ownership handover, CAN control, DSP initialization and radio/audio routing, followed by the complete ACC sleep/wake contract. A production system service also needs a matching privilege/SELinux arrangement and recovery strategy. This bench is not sufficient to disable or uninstall `com.syu.ms`.
