# Cabin native sound controls: verified client contract

Reference: Joying UIS7862 firmware 2023-08-31, factory Equalizer
`190000000_7604_com.syu.eq.apk`, SHA-256
`cb02910a5a0ba60c3c3154db42d45aa759376d8a1c3028a8e99b302b810c9ff4`.

This implements an Android client of the installed SYU sound service, not a replacement
for its native DSP drivers. Evidence is factory-app bytecode inspected with Android SDK
`apkanalyzer dex code --class CLASS APK`. No controls were tested on the user's unit.

## Enabled contract

Sound module is 4. Callback field 1 identifies the DSP module. Current allowlist:
6 (C32107), 7 (C7602), 11 (AKM7604). Unsupported identities expose no writes.
Only valid, current callbacks create editable controls. Missing readings never mean zero.

| Control | Callback (integer array) | Command (integer array) | Allowed vendor steps |
| --- | --- | --- | --- |
| Factory EQ preset | 10 `[mode]` | 2 `[mode]` | 0..8 |
| EQ band gain, modules 6/7 | 9 `[band,gain]` | 1 `[band,gain]` | gain 0..24; reported band 0..31 |
| EQ band gain, module 11 | 9 `[band,gain]` | 1 `[band,gain]` | gain 0..20; reported band 0..35 |
| Balance/fader | 8 `[x,y]` | 3 `[x,y]` | each axis 0..16 |
| Loudness | 11 `[enabled]` | 5 `[enabled]` | 0/1 |
| Subwoofer level, module 11 only | 26 `[level]` | 23 `[level]` | 0..10 |

The second axis is preserved from confirmed feedback when changing one axis. EQ callback
9 reports one band at a time: normalized map key `1000 + band` stores `[gain]`, so callbacks
for different bands do not replace each other and each band can expire independently.
Synthetic keys are in-memory cache identifiers, never SYU subscription fields.

All gain labels are vendor levels. There is no unverified dB conversion, fixed frequency
label, or native chip identity inference from APK filename. Band frequency can be changed
separately by the factory app; therefore the UI numbers bands rather than inventing Hz.

## Evidence locations

- `com.syu.eq.jar.finl.FinalSound`: module/command/update constants, especially
  `C_EQ_GAIN=1`, `U_EQ_GAIN=9`, `C_BAL_FADE=3`, `U_BAL_FADE=8`.
- `com.syu.eq.jar.MyActivity.commad`: forwards command frames to module 4.
- `com.syu.eq.sound.SoundSliderLayout`: constructor has 16 sliders, min 0/max 24;
  `initLayout` selects 18 sliders/max 20 for IDs 11/12/13, and 13 sliders/max 24
  for ID 7 with `lsec_new`. `SoundSliderLayout$1` clamps then forwards band/gain.
- `SoundFragment.refreshWave`: two pages of 16 bands and clamp 0..24;
  `SoundFragment.callback` consumes `[band,gain]` from field 9. Reported bands only
  are exposed, so a 13-band layout does not create nonexistent extra controls.
- `SoundFragmentH$5.onProgressChange`: command 1, `[band, min(24,max(0,gain))]`.
  `SoundFragmentH.callback` consumes the same update shape.
- `SoundFragmentAKM$2.onProgressChange`: command 1, clamp 0..20;
  `SoundWaveAKM` allocates 36 entries; `SoundFragmentAKM.callback` consumes
  field 9 `[band,gain]` and fields 10/11 `[value]`.
- `SoundFragmentAKM$10`, `SoundFragment$5`, `SoundFragmentH$4`: command 2 forwards
  ordinary preset indices. New-style user presets 9+ use factory app persistence and
  confirmation flows, so Cabin deliberately does not reproduce those custom slots.
- `SoundFieldFragment` initializes `FIELD_MAX=16`; callback 8 consumes both axes.
  `SoundFieldFragment$1` sends command 3 with both axes. The special
  `yl7738` / module 13 case swaps axes and reverses one (`16-y`), which is why
  ID 13 is not treated as a universal match.
- `SoundFragmentAKM$8`: command 5 with explicit boolean value, derived from field11.
- `DynamicBassFragmentAKM$10`: command 23 `[min(progress,10)]`;
  `DynamicBassFragmentAKM.callback`, packed switch starting field26, consumes `[level]`
  and clamps max10. `highpass.Slider` defaults to minimum0.

## Deliberately not enabled

DSP IDs 12/13 and unknown variants need complete per-feature mapping before enabling
writes. Native EQ frequency/Q, bass enhancement, filters, subwoofer crossover/enable,
surround, speaker delays and reset are not guessed. For example the AKM subwoofer
frequency listeners send command25 with two integers (selector plus value), while its
amp button sends command7: neither can safely be modeled as the similarly named
single-integer command36 solely from constant names.

No DSP/MCU initialization, raw serial/I2C access, firmware flashing or service replacement
is included. A UIS7862 processor identifies the CPU, not the installed DSP chip/variant.

## Launcher integration

Open **Sound** on a tall Audio Control widget. The native, full-screen dialog provides
presets, loudness, balance/fader and supported subwoofer level; individual EQ bands are
under Advanced. It scrolls in portrait and includes an optional configured factory-app
fallback. English, Portuguese, Spanish, French, German and Italian labels are included.

`SyuSoundController` owns its Binder connection on a worker thread only while the screen
is resumed. Identity is subscribed first; changed identity, service loss or screen closure
invalidates the connection epoch and clears samples/queued changes. Samples expire after
45 seconds, with cached subscriptions refreshed every 20 seconds. Edits are coalesced for
80 milliseconds, wait for callback confirmation, and time out after 3 seconds. Combined
balance/fader edits share one frame so one axis cannot undo the other queued adjustment.

The view shows confirmed values, never treats a successful Binder reply as hardware
confirmation, and disables adjustment while moving. Unsupported DSPs expose no writes.
Physical head-unit validation is still required; this is not a firmware or SYU replacement.

## Validation

Full `:app:testDebugUnitTest` and `:diagnostics:testDebugUnitTest` passed,
followed by `:app:assembleDebug`. Coverage includes protocol bounds and frames,
synchronous cached callbacks, edit coalescing/confirmation/timeouts, profile changes,
reconnect ownership, stale feedback, portrait dialog, launcher entry and all six locales.
This validates client behavior with test doubles, not the physical DSP.

## Hardware diagnostics

Sound → Hardware diagnostics expands a read-only report with Android manufacturer/model,
OS/API version, sound-service connection, and the DSP profile ID reported by SYU.
Known labels (C32107, C7602, AKM7604) describe the factory service profiles, not a
physical chip probe. Unknown IDs remain visible without enabling unverified writes.
The report distinguishes unmapped features from mapped controls without fresh valid
readings and counts individually reported EQ bands. Disconnected snapshots ignore old
samples and identity. Copy report copies the current displayed snapshot locally; it
does not send data or issue hardware commands. Diagnostics are collapsed by default.
