# Reference volume command and remaining integration

Static inspection of the APK pinned in `JOYING-SERVICE-CONTRACT.json` identifies **SOUND module 4, command 0**, not a MAIN volume command. `t0/i.cmd`'s packed switch at 0 selects `pswitch_252`, validates an integer argument and invokes `t0/g.w0`. SOUND subscription field 2 reports `t0/e.e` (current volume); field 3 reports `t0/e.k` (mute).

`SoundVolumeCommand.resolve` decodes these state-dependent actions without I/O:

| Argument | Behavior |
| --- | --- |
| Nonnegative | Set volume, clamped to configured maximum; clear active user mute |
| -1 | Increase by configured step, clamped; at maximum do nothing, including preserving mute |
| -2 | Decrease by configured step, clamped; at zero do nothing, including preserving mute |
| -3 | Request mute if currently unmuted, subject to call/MAIN policy |
| -4 | Clear mute if currently muted |
| -5 | Toggle mute, subject to call/MAIN policy |

Evidence: `t0/g.w0` packed switch starts at -7. Targets -1/-2 recurse with a new absolute value only when below/above a limit; -3/-4 conditionally recurse with -5. The -5 branch enables mute only when current mute is zero, `e0/c.H` is zero and `p0/i.X2()` is false. The decoder therefore requires an explicitly resolved `muteAllowed`, rather than guessing from the selected source. `t0/g.x0` clamps absolute values, updates field 2 and invokes volume application. Values -6/-7 invoke additional lifecycle functions and remain unsupported by this decoder.

The reference absolute path also sends MCU payload `E7 level 00`, persists source-dependent media/call/extra-call volume through `v0`, and clears mute through -4. Mute changes also send `01 00 A1` or `01 00 A0`.

`RadioMcuTransport.sendVolume` and `sendUserMute` now encode those payloads through the existing MCU framing codec and share the radio writer lock. They reject unresolved/out-of-byte-range volume values before I/O. Concurrent writes are rejected without queuing; failed writes retire the entire connection without retry. Raw writes remain unavailable.

`C7604AudioSession.applyVolumeCommand` composes a resolved action: validate calibration, apply DSP volume, notify the MCU, apply user mute if requested, then notify the MCU of mute. It preserves boundary no-ops from the decoder. A failure after starting the operation attempts amplifier fault mute, invalidates audio state and closes both connections. This prevents continued use of a partially applied operation; it does not roll back physical writes or infer an MCU acknowledgement.

Integration remains incomplete: current call policy, sleep guards, source-dependent state persistence and MCU ownership must be supplied before enabling command 0 on the replacement Binder endpoint. The operation takes explicit resolved policy and calibration; it does not invent these state providers.

## Source volume memory

`SoundVolumeMemory` now implements the bank selection in `t0/g.v0`: MAIN app 2 selects bank 1 (call), app 15 selects bank 2 (extra call), and other app IDs select bank 0 (media). The corresponding `t0/i.J2`, `K2` and `M2` methods publish SOUND field 0x43 as `[bank,level]` and use vendor store keys 0x17d, 0x184 and 0x17c respectively. The independent implementation publishes the same indexed tuple shapes without using vendor storage.

All three initial values and the maximum must be supplied explicitly. Updates save a defensive copy of all banks before publishing the new in-memory state. Failed saves surface an exception and preserve the previous in-memory bank values. This memory is configured only when the reference's configuration-ready condition has been satisfied by the replacement startup provider; it does not infer that condition itself.

`SoundVolumePreferences` provides a dedicated Android preferences backend with synchronous commit. Its schema includes the profile identifier and configured maximum. Restore rejects missing data, a different profile, a changed maximum, wrong types and invalid levels. Provisioning must explicitly save the initial values; no default volume is invented. A failed commit does not prove durable storage, and startup must treat save failure as a fault rather than assuming a successful restore. Tests recreate the store and verify separate bank values and corrupt/mismatched state rejection.

The bank memory and storage backend still need to be attached to the live startup/source coordinator and command endpoint. They do not yet make SOUND command 0 available to factory clients.

Tests cover saturation, configured increments, preservation of mute at volume boundaries, call-policy suppression and rejection of missing state/unsupported lifecycle actions. No physical unit was accessed.
