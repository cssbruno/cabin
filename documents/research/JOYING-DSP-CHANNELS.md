# C7604 channel mute and balance/fade

The independent writer now implements the four-channel attenuation portion of `C7604.muteAmp` and `field2Ic`. This is not the complete physical amplifier-mute path.

Evidence from the pinned Joying service:

- `field2Ic` takes positions 0–16 (center 8). The four indices are max(balance,fade), max(16-balance,fade), max(balance,16-fade), max(16-balance,16-fade).
- `speakerGain` maps IDs 0x11000, 0x12000, 0x21000, 0x22000 to channel indices 0–3. `F0` selects the V2 table value; `p0` writes it to register 0x83 plus the channel through `C0`.
- V2 values for indices 0–16 are 18,18,18,18,18,18,18,18,18,1E,24,2A,30,36,3C,42,FF (hexadecimal).
- `muteAmp(true)` writes V2's last value FF to all four channels. Its superclass also calls `t0/g.Y(0x100, state)` to update shared amplifier mute flags; that separate path remains incomplete.

`C7604Channels` produces the four values. `C7604Writer.setSpeakerField` requires successful program loading and writes four `C0 00 register value` packets. Muting overrides all values to FF. Restoration takes explicit desired positions, rather than restoring unknown cached hardware state. Any failed write invalidates the session and prevents subsequent writes. This does not claim GPIO/MCU amplifier mute, physical silence or verified speaker wiring.

112 current Hardware Lab tests pass, APK build and lint pass. Tests cover center, all four corners, partial balance, invalid positions, exact register writes, explicit restoration, programming prerequisites and mid-sequence failure. A duplicate local variable in LabActivity's firmware export handler was also renamed to restore compilation. No physical unit was accessed.
