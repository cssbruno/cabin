# C7604 program loading

The independent writer now implements the `C7604.f0` table-loading sequence. This is one part of `setup`, not a complete sound service.

## Image evidence

The pinned Joying APK maps raw resources as follows (verified using Android resource IDs, not guessed ZIP names):

| Resource | ZIP entry | Parsed content | Raw SHA-256 |
| --- | --- | --- | --- |
| `0x7f03000e`, c7604_reg_table | `res/n0.txt` | 164 register/value pairs | `a5daf727b84353d14570126f16e7766e6b07bfe6193995aaf02fb69617ca69e7` |
| `0x7f03000d`, c7604_param_table | `res/3z.txt` | 4,600 bytes | `db80af258dc6f0ec03a292e3b0f0e8089792e877f5b036c4eac769955ec96505` |
| `0x7f03000c`, c7604_cram_table | `res/HH.txt` | 1,533 bytes | `2c5bffab3af00de9a5376bd1f980699d8974b27d2701e767f8cfccda78ae2946` |

`i1/p0.y` reads comma-separated hexadecimal values. `C7604ProgramImage.fromReferenceApk` bounds decompression, checks all raw hashes and parsed counts, and rejects mismatches. Firmware tables remain in the caller-supplied APK; none are bundled in the replacement source/APK. The direct image constructor supports caller-supplied images with structural checks only; it does not establish hardware compatibility.

## Sequence

After the caller has established ownership and reset the chip, `C7604Writer.loadProgram`:

1. Requires status register C0 to return 4.
2. Reads registers 02 and A3, clears bit 7 of 02 and bits 0–2 of A3, preserving unrelated bits. Writes use `C0 reg-high reg-low value`.
3. Waits 10 ms, writes the register table with those same control bits cleared, then waits 10 ms.
4. Sets register 02 bit 7 while keeping A3 bits 0–2 clear, then waits 10 ms.
5. Sends the parameter table with prefix `B8 00 00`, followed by CRAM with prefix `B4 00 00`.
6. Waits 10 ms, sets register 02 bit 7 and A3 bits 0–2, then waits 100 ms.

These operations derive from `f0`, `t0`, `i0`, `h0`, `g0`, `C0` and `A0` in the pinned class. There is no guessed chunking or extra A4 coefficient commit. The parameter packet is 4,603 bytes, so native I2C writes now permit up to 8,192 bytes while retaining exact-write checks and no retries. Native combined reads retain their separate 4,096-byte bounds.

Interrupted or failed programming invalidates the writer and blocks later writes. `programLoaded()` becomes true only after the final delay. It does not claim source routing, amplifier configuration, EQ state or audio playback are ready. Kernel I2C operations still have kernel/adapter completion semantics, not cancellable userspace deadlines.

## Verification

102 Hardware Lab tests pass; APK build and lint pass. Both native host tests pass, including acceptance of the required 4,603-byte packet and rejection above the write limit. The actual pinned APK import was also exercised separately: all three hashes and counts passed. Fixture tests check operation order, preservation of unrelated bits, delays, interruption, failure before final control-bit writes, immutable images and rejection of unrecognized APK tables.

No hardware was accessed. Remaining startup work includes native reset-control mapping/ownership, bounded readiness retries, then the remainder of `setup`: EQ/field configuration, source routing, amplifier mute and volume. Full sound Binder integration and physical validation are still incomplete.
