# SYU MS reference APK inspection

Source: https://github.com/hvdwolf/JoyingBinRepo/blob/master/9853i-com_syu_ms/NK/2020-04-07/190000000_com.syu.ms.apk

Static inspection only; not installed or executed. Download and extracted inspection files are in `/tmp/fyt-ms-inspect`.

- SHA256: `2ef3c02846513acf2147e6f1f38f50bc359dcc7e4bc2624f1305e537102cea2d`
- Package `com.syu.ms`, versionCode `2004080958`, versionName `1.0`.
- Manifest compilation metadata: `2020-04-08 09:58:31`; min/target SDK 16; platform metadata `C2`.
- Repository classifies it under 9853i. Compatibility with the user's actual head unit has not been established.
- Manifest requests shared UID `android.uid.system` and privileged permissions including DEVICE_POWER, INJECT_EVENTS and REBOOT.
- `app.ToolkitService` exposes `com.syu.ms.toolkit`; `app.ModuleService` exposes subsystem service actions.
- `com.syu.jni.SerialNative` loads `sqlserial`; declares open/setup/read/write/close and mailbox-open native operations.
- `com.syu.jni.JniSerial` loads `jni_serial`; declares serial open/setup/read/write/close operations.
- `com.syu.jni.ControlNative` loads `sqlcontrol`; native methods include amplifier muting, LCD control, and Bluetooth/GPS reset.
- ZIP contains no `.so` libraries. The APK depends on libraries outside the APK; their implementations were not inspected.

## Implications

This is a useful reference for the service side, unlike an IPC client-only launcher. It does not by itself establish access to raw vehicle CAN frames: the observed low-level interfaces are native serial/mailbox APIs, and the MCU/decoder protocol remains to be traced.

A replacement needs the target firmware's native dependencies, device permissions, MCU protocol and service contracts. The reference APK alone is insufficient to establish a working replacement on another FYT generation. Next evidence needed: the installed target `com.syu.ms` APK, matching native libraries, and hardware/firmware identity. Preserve the existing firmware until a replacement has been validated on that target.
