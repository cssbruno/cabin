# C7604 input selection

`C7604Writer.selectInput` implements the DSP writes at the end of the reference `appId` path: `e0(boolean)` followed by `X(input)`.

`e0` uses `C7604$i.a`, returning 0x10 for false and 0x20 for true. `B0(1,0x80,6,word)` writes `80 00 06 00 00 value`, then commits with `A4 00 00`. `X` writes register 8D through `C0`: channel IN1 (0) selects 08, IN2 (1) selects 0C, IN3 (2) selects 02, and IN4 (3) follows the reference default 08. Unknown input indices are rejected instead of silently defaulting.

The operation requires a successfully programmed DSP session. If the path write, commit or selector write fails, the session is invalidated and no automatic retry occurs. A successful serial/I2C write is not a claim of playback or verified source wiring.

115 Hardware Lab tests pass; APK build and lint pass. New tests cover all four inputs with both path values, exact operation ordering, invalid input/program state and failure before final input selection. No physical hardware was accessed.

## Remaining routing coordinator

The complete `appId` method also prioritizes a call override, then the global forced app ID, consults the configured app-to-input map, handles forced ARM analog routing and chip-specific radio behavior, invokes Android AudioManager voice/audio I2S switching (or the reference wired-device fallback), then applies source gain. The direct DSP input API does not implement or infer these states. A standalone coordinator, verified app/input map, framework integration, amplifier-mute coordination and physical validation remain required for full SYU replacement.

## Source policy and Android switch adapter

`C7604SourceRouting` now resolves an explicitly supplied app/input map with the reference override priority: forced source, then active call source 2, then requested source. It handles forced ARM analog and radio-specific analog flags, plus the platform-switching branch. Its plan applies the Android switch before the DSP writes and closes the DSP writer after a platform/DSP failure because the route may have changed partially. It does not invent missing mappings or source-gain calibration.

`AndroidAudioSwitch` discovers the firmware's public `setVoiceSwitch2iis` and `setAudioSwitch2iis` methods, or the observed four-argument `setWiredDeviceConnectionState` fallback (device 8, state inverse of IIS selection). It does not bypass hidden-API restrictions. Missing/inaccessible methods and invocation failures report errors. These method calls were tested with controlled stand-ins, not the target AudioManager or audio hardware.

Unit coverage includes override priority, branch behavior, missing mappings, platform-failure invalidation, both adapter paths and platform-before-DSP ordering. Full sound service ownership/startup, source state providers, source gain, amplifier arbitration and hardware validation remain incomplete.
