# C7604 reset and startup

`C7604Startup` now composes native reset, readiness polling and table loading. The caller must already own the target GPIO and I2C interfaces; this code does not stop SYU, acquire system privileges or establish exclusive board ownership.

## Reset evidence

`C7604.setup` calls `ToolsJni.cmd_251_Normal_Io_Set(0,0)`, waits 20 ms, calls the same method with `(0,1)`, and waits another 20 ms. The Java helper builds `0x10000 | (gpioIndex << 8) | value` and sends command 251.

In the pinned ARM64 `libsyu_jni.so`, the command-table entry at virtual address `0x19420` holds ID 0xFB; its function-pointer relocation at `0x19428` identifies `jni_exe_cmd_set_gpios` at `0xF094`. That function opens `/sys/fytver/Gpios` (string at `0x53E9`), masks the supplied value to 24 bits and writes a four-byte native little-endian integer. GPIO 0 therefore receives `00 00 01 00` for low and `01 00 01 00` for high.

The independent native operation uses that fixed path, write-only/CLOEXEC/NOFOLLOW flags, no file creation or truncation, and requires a complete four-byte write. Failed or short writes are not retried. It opens no other GPIO path and includes no vendor library.

## Startup behavior

The caller supplies a fresh writer and validated program image. Startup serializes against writer operations, performs the two reset writes/delays, and polls status for 1–20 caller-selected attempts with 520 ms between not-ready results. When status is 4, it invokes the existing program loader. Invalid requests and closed/failed/programmed writers are rejected before reset. Poll exhaustion, write/read failure and interruption close the writer; interruption preserves the thread interrupt flag.

The attempt limit bounds the number of polls, not a stuck kernel ioctl. Native I2C and sysfs operations still depend on kernel completion. A successful program load does not mean that source routing, EQ, volume, amplifier control or the entire SYU replacement is initialized.

## Verification

The native reset test intercepts production open/write calls, uses /dev/null instead of sysfs, checks the exact two reset words, and verifies descriptor cleanup and no retries for short/error writes. All three native tests pass. Java tests cover reset/delay order, readiness exhaustion, reset failure, interruption and rejection before hardware actions.

No physical reset occurred and no head unit was accessed. Target board matching, ownership handover, permissions/provisioning, the remainder of sound startup, boot/ACC lifecycle and physical validation remain incomplete.
