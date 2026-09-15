# Native amplifier mute and independent reasons

`NativeIo.setAmplifierMute` writes one ASCII byte to `/sys/fytver/muteAMP`: `1` for mute, `0` for unmute. This follows `jni_exe_cmd_mute_amp` at 0xADA0 in the pinned ARM64 library. Its path string is at 0x5F6E; the zero/nonzero argument branch selects strings at 0x5F20 (`0`) and 0x69EA (`1`). The replacement requires exact write completion, closes descriptors on success/failure and never retries uncertain writes. No vendor native library is loaded.

`AmplifierMute` maintains independent replacement lifecycle reasons (STARTUP, SOURCE_CHANGE, USER, SLEEP, FAULT). These are internal reasons, not vendor bitmask IDs. Explicit initialization applies the startup mute. Clearing a reason only unmutes when no other reasons remain. Duplicate desired states do not repeat writes. Failed writes clear the last-applied state, latch FAULT and reject further changes; no hardware success or readback is fabricated.

The source `t0/g.X`, `t0/g.t0` and `t0/g.Y` combines mute flags before applying policy. The complete vendor `L` policy also handles call state and other conditions; this controller does not yet recreate that full policy. Lifecycle integration must initialize it before startup/reset, retain startup mute until sound configuration is complete, and coordinate source switching, calls and sleep. Board ownership and permissions remain prerequisites.

130 Hardware Lab tests pass, APK build and lint pass, and all three native host tests pass. The native syscall test checks the fixed amplifier path, ASCII encoding, descriptor cleanup and short-write failure without accessing sysfs. Java tests cover independent reasons, initialization requirements and uncertain-write failure. No head unit or physical amplifier was accessed.
