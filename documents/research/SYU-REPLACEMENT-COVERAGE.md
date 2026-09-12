# SYU replacement coverage

Reference: `SYU-MS-9853I-INSPECTION.md`, APK SHA-256 `2ef3c02846513acf2147e6f1f38f50bc359dcc7e4bc2624f1305e537102cea2d`.

The reference's ModuleService advertises 16 actions: main, radio, bt, dvd, sound, ipod, tv, canbus, tpms, dvr, steer, customer, obd, test, can.up, amp. These are advertised subsystem contracts, not proof that every hardware variant implements every function.

| Area | Current implementation | Evidence still needed |
| --- | --- | --- |
| Toolkit/CAN client interface | Tested simulation subset only | Full target transaction contract and callback behavior |
| Real MCU/CAN transport | Not implemented | Target native libraries, node permissions, framing and startup sequences |
| DSP/sound/amplifier | Not implemented | Target chip, command routing, coefficients, gain bounds and readback |
| Radio/Bluetooth/audio routing | Not implemented | Target module contracts and MCU source state |
| ACC/sleep/wake/power | Not implemented | Target lifecycle protocol and hardware validation |
| Steering/TPMS/OBD/DVR | Not implemented | Target decoders, transport modules and hardware capabilities |
| DVD/iPod/TV/customer/test/update | Not implemented | Confirm target availability and required behavior |

Reference JNI load names confirmed by static inspection: sqlcontrol, sqltouch, jni_i2c, sqlserial, jni_serial, jni_spectrum, jni_toolkit, syu_jni. These are not included as native binaries in the inspected APK. Native declarations and load names alone are insufficient to reconstruct their behavior.

Hardware Lab 0.3 can gather readable target firmware evidence. No real hardware replacement has been implemented or validated. Do not label simulated callbacks, inventory collection, or firmware export as finished SYU/DSP support.
