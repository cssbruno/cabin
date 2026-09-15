# Standalone C7604 sound endpoint

The live replacement session now optionally owns a `C7604SoundModule` at toolkit ID 4. Existing sessions without a configured sound device remain unchanged. `ReplacementService.startOwnedConnection` has an internal overload to transfer ownership of the programmed sound endpoint alongside the MCU transport.

Implemented wire contracts, pinned in `SYU-SOUND-CLIENT-CONTRACT.md`:

- Command 1 `[band,gain]` applies the C7604 EQ plan using that band's explicitly configured frequency and Q. Unconfigured bands and invalid gains are rejected before I/O.
- Command 3 `[balance,fade]` applies the DSP channel field while preserving the configured mute state.
- Callback 1 `[11]` identifies the explicitly configured driver, not a chip auto-detection result.
- Callback 9 `[band,gain]` retains distinct last-applied EQ band values; callback 8 `[balance,fade]` reports the last successfully applied field.

No initial gains or field values are invented. Callbacks describe driver-owned software state after successful writes/commits, not physical hardware readback. Read failures/failed writes and closed sessions expose no cached values. Live toolkit access is disabled after the MCU receive session ends. Toolkit/session closure releases the sound writer.

The mute state and EQ frequency/Q map must come from the real startup/configuration coordinator. Unsupported commands, including presets, loudness, volume, amplifier control, filters and source changes, are rejected until their complete service semantics are implemented. The low-level helpers for several of these controls exist separately; they are not automatically enabled on Binder.

123 current Hardware Lab tests pass, APK build and lint pass. Tests exercise module-to-I2C commands, indexed state, preservation of mute during field changes, unknown-command rejection, failure invalidation, an actual Binder transaction through live toolkit module 4, and closure of the owned sound device. No head unit was accessed. Full SYU replacement remains incomplete.
