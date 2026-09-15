# SYU replacement coverage

Latest implementation: [live service command path and completion requirements](JOYING-LIVE-SERVICE.md). An owned MCU connection can now serve typed radio commands and receive callbacks through the replacement toolkit. Physical SYU replacement remains incomplete. Replay feedback is documented in [radio feedback](JOYING-RADIO-FEEDBACK.md).

Latest Joying-specific inspection: [replacement audit](JOYING-REPLACEMENT-AUDIT.md)
and [hashed service/driver map](JOYING-SERVICE-CONTRACT.json). It establishes 19
ModuleService actions and 20 toolkit factories for the 2023 Joying APK; the
16-action count below applies only to the older 9853i reference. Serial/I²C native
paths, a C7604 driver path, CAN dispatch and part of the sleep protocol are now
located. Hardware Lab 0.5 now implements independent serial/I²C libraries and a replay runtime; live service integration is still incomplete. See [0.5 implementation status](JOYING-REPLACEMENT-RUNTIME.md).

Reference: `SYU-MS-9853I-INSPECTION.md`, APK SHA-256 `2ef3c02846513acf2147e6f1f38f50bc359dcc7e4bc2624f1305e537102cea2d`.

The reference's ModuleService advertises 16 actions: main, radio, bt, dvd, sound, ipod, tv, canbus, tpms, dvr, steer, customer, obd, test, can.up, amp. These are advertised subsystem contracts, not proof that every hardware variant implements every function.

| Area | Current implementation | Evidence still needed |
| --- | --- | --- |
| Toolkit/CAN client interface | Cabin uses the existing SYU CAN module; Hardware Lab separately simulates a subset | Validate target transaction contract and callback behavior |
| Real MCU/CAN transport | Independent framing, native serial library, receive/replay runtime and Honda receive subset; no deployed live service | Match target firmware, validate captures and ownership, implement startup sequences |
| DSP/sound/amplifier | C7604 packet writer only; full sound service unimplemented | Target chip, command routing, coefficients, gain bounds and readback |
| Radio/Bluetooth/audio routing | Cabin reads MAIN APP_ID source callbacks; routing commands are not implemented | Target module contracts and hardware validation |
| ACC/sleep/wake/power | Ordered sleep-message observer; no power actions | Target lifecycle protocol and hardware validation |
| Steering/TPMS/OBD/DVR | Not implemented | Target decoders, transport modules and hardware capabilities |
| DVD/iPod/TV/customer/test/update | Not implemented | Confirm target availability and required behavior |

Reference JNI load names confirmed by static inspection: sqlcontrol, sqltouch, jni_i2c, sqlserial, jni_serial, jni_spectrum, jni_toolkit, syu_jni. These are not included as native binaries in the inspected APK. Native declarations and load names alone are insufficient to reconstruct their behavior.

Hardware Lab 0.3 can gather readable target firmware evidence. No real hardware replacement has been implemented or validated. Do not label simulated callbacks, inventory collection, or firmware export as finished SYU/DSP support.

Hardware Lab 0.4 adds a tested MCU frame codec without SYU dependencies, based on
static inspection of the local Joying UIS7862 reference. It analyzes saved binary
captures and reports framing errors; it does not yet produce live vehicle telemetry.
See [Joying MCU framing](JOYING-MCU-FRAMING.md) for pinned evidence, usage and remaining work.

## Integration in the existing Cabin launcher

The audio widget subscribes to MAIN module 0, field 0 (APP_ID), through the installed
SYU toolkit. It displays the selected source, clears it on disconnect, retries failed
bindings, and unregisters when the widget leaves the resumed lifecycle. Source selection
does not imply playback. The volume buttons still control Android STREAM_MUSIC.

A configured, available Equalizer / DSP launcher activity can be opened from a sufficiently
large audio widget. Select it in the existing accessory shortcut settings. This opens the
factory app; it does not implement DSP coefficients, amplifier commands, or native drivers.

Protocol reference: [FytBt findings](https://github.com/PimpinPumpkin/FytBt/blob/main/FINDINGS.md),
MAIN APP_ID and Toolkit Binder sections. Those findings describe another FYT platform;
the Binder tests validate Cabin's implementation, not compatibility with every head unit.

CAN, climate and vehicle widgets continue using Cabin's existing profile-aware SYU
controller. No raw CAN access, proprietary library replacement, or firmware change is
introduced by the audio integration. Target hardware testing remains required.

## Current launcher client additions

Native Sound controls and diagnostics are described in `SYU-SOUND-CLIENT-CONTRACT.md`.
Radio tuning/presets, telemetry refresh, lifecycle recovery and compatibility reporting
are described in `SYU-VEHICLE-RADIO-INTEGRATION.md`. These supersede the earlier client
coverage descriptions above; the native service/driver replacement gaps still apply.
