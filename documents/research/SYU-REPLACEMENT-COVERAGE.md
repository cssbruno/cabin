# SYU replacement coverage

Reference: `SYU-MS-9853I-INSPECTION.md`, APK SHA-256 `2ef3c02846513acf2147e6f1f38f50bc359dcc7e4bc2624f1305e537102cea2d`.

The reference's ModuleService advertises 16 actions: main, radio, bt, dvd, sound, ipod, tv, canbus, tpms, dvr, steer, customer, obd, test, can.up, amp. These are advertised subsystem contracts, not proof that every hardware variant implements every function.

| Area | Current implementation | Evidence still needed |
| --- | --- | --- |
| Toolkit/CAN client interface | Cabin uses the existing SYU CAN module; Hardware Lab separately simulates a subset | Validate target transaction contract and callback behavior |
| Real MCU/CAN transport | Not implemented | Target native libraries, node permissions, framing and startup sequences |
| DSP/sound/amplifier | Not implemented | Target chip, command routing, coefficients, gain bounds and readback |
| Radio/Bluetooth/audio routing | Cabin reads MAIN APP_ID source callbacks; routing commands are not implemented | Target module contracts and hardware validation |
| ACC/sleep/wake/power | Not implemented | Target lifecycle protocol and hardware validation |
| Steering/TPMS/OBD/DVR | Not implemented | Target decoders, transport modules and hardware capabilities |
| DVD/iPod/TV/customer/test/update | Not implemented | Confirm target availability and required behavior |

Reference JNI load names confirmed by static inspection: sqlcontrol, sqltouch, jni_i2c, sqlserial, jni_serial, jni_spectrum, jni_toolkit, syu_jni. These are not included as native binaries in the inspected APK. Native declarations and load names alone are insufficient to reconstruct their behavior.

Hardware Lab 0.3 can gather readable target firmware evidence. No real hardware replacement has been implemented or validated. Do not label simulated callbacks, inventory collection, or firmware export as finished SYU/DSP support.

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
