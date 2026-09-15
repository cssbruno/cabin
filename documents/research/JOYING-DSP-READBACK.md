# C7604 status readback

This increment adds real I2C read support needed by DSP initialization. It does not implement a complete sound service or prove the target board uses this chip.

## Pinned reference evidence

The extracted Joying `libjni_i2c.so` SHA-256 is `2274a310d18753c64cf50d3b11364f3269119a914241c53e6647d00c647bd7ea`, matching `JOYING-SERVICE-CONTRACT.json`.

- `C7604.setup()` toggles normal IO 0 low/high with 20 ms delays, then polls `s0(0xc0)` until its value is 4. The vendor retry branch waits 520 ms and has no finite retry limit.
- `s0` calls `q0(0x40,0xc0,1)`. `q0` builds command plus register high/low bytes, producing prefix `40 00 C0`, and requests one response byte at address `0x1c` through `i1/m.f(..., false, ...)`.
- The false branch selects `JniI2c.readNormalStopNo`. ARM64 function address `0x1524` constructs two `i2c_msg` structures at `0x15f8` onward: first a write, then a read with flag 1. At `0x1684`–`0x1688` it issues ioctl `0x707` (`I2C_RDWR`) with two messages. This is a repeated-start transaction, not independent write/read calls.
- The reference selects the slave with `I2C_SLAVE_FORCE`; our existing open uses `I2C_SLAVE`. The reference's combined-transfer return is not used to validate completion before copying the response; our implementation requires exactly two completed messages.

## Implementation

`I2cDevice.read` checks prefix/response bounds of 1–4096 bytes, serializes the two-message transfer against writes on the same device object, and rejects failed or incomplete transfers without retry. JNI returns bytes only after successful completion and retains the native object's lifetime during the call. Closing a handle prevents future lookups; an in-flight kernel ioctl remains subject to kernel/adapter completion, not a userspace cancellation deadline.

`C7604Writer.readStatus` performs the exact one-byte status request. It returns an unsigned status value, throws on malformed/missing/error responses, and invalidates the session after an I/O failure so later writes cannot proceed on an uncertain state. A returned value of 4 establishes only the chip status expected by the reference, not completion of DSP programming or audio routing.

## Validation and remaining initialization

The current Hardware Lab suite passes 97 tests, APK build and Android lint pass, and both native host tests pass. The new native test inspects the production ioctl message structures using a linked syscall test double, validates addressing/flags/prefix, and checks that incomplete/error responses do not cause retries. Java tests cover unsigned status, invalidation after failed/short/null responses and closure. No I2C bus or head unit was accessed.

The subsequent [program-loading implementation](JOYING-DSP-PROGRAM.md) implements `f0` with the reference tables. Remaining work includes reset-pin ownership/mapping, bounded readiness scheduling, source selection, mute/volume ordering, sound toolkit integration and physical validation. Do not treat status-read support as initialized audio hardware.
