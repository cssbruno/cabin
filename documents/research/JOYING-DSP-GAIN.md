# C7604 master gain

`C7604Gain` and `C7604Writer.setMasterGainDb` implement the direct master gain path traced from `C7604.z0 -> C7604$d.a -> C7604$j.a(mode 2)` in the pinned Joying firmware.

The coefficient is `round(10^(dB/20) * 2^21)`, with input clamped above 12 dB. At or below -145 dB, the coefficient is zero. Non-finite inputs are rejected. The write is `80 00 07 coefficient[MSB..LSB]`, followed by `A4 00 00`, matching `B0(1,0x80,7,words)`. This mode uses Q21, unlike the Q22 EQ coefficients.

The writer requires successful program loading before changing gain. A failed coefficient write or commit invalidates the session and blocks subsequent writes without retries. Gain zero provides digital silence; it is not a claim that the physical amplifier is muted. A completed write is not hardware readback.

105 Hardware Lab tests pass, APK build and lint pass. Tests include independent unity/half/double-amplitude vectors, zero output, clamping, monotonicity, non-finite rejection, exact write/commit bytes, programming prerequisites and failed-commit invalidation. Native code is unchanged in this increment. No physical unit was accessed.

Still incomplete: UI volume-step calibration (`AudioDevice.J` includes driver/source state), source routing, amplifier mute, reset/ownership, sound-module Binder command/state integration and head-unit validation. This increment does not complete the SYU replacement.

## Explicit volume-table mapping

`C7604VolumeCurve` now implements `AudioDevice.J/C/k/n` with the C7604 `y()` baseline of -700 tenths dB. It accepts the target's table and current calibration/compensation state explicitly. Level zero bypasses offsets. Other levels subtract the table-direction-adjusted difference between the default offset and configured offset. Compensation interpolates the higher table entry above state 60, reaching the full difference at 120, with the reference float arithmetic and rounding. Conversion to dB preserves the firmware's float division by ten.

`C7604Writer.setVolumeLevel` connects this mapping to the existing programmed-session gain write. No default table, speed source, persistent calibration or factory configuration is inferred. Those state providers, source routing, amplifier mute and sound Binder integration remain incomplete.

109 current Hardware Lab tests pass, along with APK build and lint. New tests cover level-zero behavior, calibration signs, compensation thresholds and upper-index limits, defensive copying, invalid inputs and conversion through to actual bus-write bytes. Target hardware calibration remains unverified.
