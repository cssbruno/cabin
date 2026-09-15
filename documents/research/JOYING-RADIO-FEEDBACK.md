# Radio receive feedback — Hardware Lab 0.7

The replacement bench can decode the reference MCU radio driver's band and current frequency from imported receive captures. Select “Decode radio feedback as reference MCU driver 1” before opening a capture. The built-in example includes FM1 / 101.00 MHz. Profile selection is explicit; driver 1 is not inferred from toolkit module ID 1.

This extends local development after the [combined alpha.7 release](https://github.com/cssbruno/cabin/releases/tag/v0.1.0-alpha.7). That release contains Hardware Lab 0.6, not these later changes.

## Evidence and contract

Reference: the previously pinned Joying `190000000_com.syu.ms.apk` described in `JOYING-REPLACEMENT-AUDIT.md`. Its `y/i.f` routes payload prefix `01 03` to `y/i.l`; `r0/f.e` publishes module-1 field 0 (band), and `r0/f.h` publishes field 1 (frequency). No proprietary implementation is bundled.

- `01 03 06 value`: values 0–2 mean FM1–FM3 (65536–65538); 10–11 mean AM1–AM2 (0–1).
- `01 03 01 high`, `01 03 02 middle`, `01 03 03 low`: current frequency is `high*10000 + middle*100 + low`.
- Decoder conservatively requires ordered chunks within 1000 ms, decimal middle/low values below 100, a known band, and plausible AM/FM frequency bounds. Unverified variants are omitted.
- Preset sequences are decoded separately as indexed field-4 tuples: selector `01 03 10 value` maps 1–18 to FM channels 65536–65553 and 0x65–0x70 to AM channels 0–11. Ordered frequency chunks then encode `100000 + frequency`. The selector expires after 1000 ms and is consumed once. Preset values never become current frequency. RDS, station names and other feedback remain incomplete.

`RadioFeedbackDecoder` has no I/O. Runtime discards its state on framing loss, session replacement, and disconnect; band changes invalidate old frequency. Current fields expire after five seconds and EOF clears them. Explicitly labeled historical fields remain available in exported reports. The toolkit exposes scalar callbacks on module 1 only after profile selection or command-preview activation. Receive selection alone does not enable command previews. No automatic queries or MCU writes are performed.

## Verification

74 Hardware Lab tests passed; `assembleDebug` and `lintDebug` passed (lint warnings remain). New tests cover AM/FM vectors, unknown bands, bounds, missing/repeated fragments, timeout, clock rollback, malformed input, band changes, reset, preset rejection, corrupted framing, explicit opt-in, expiry, EOF history and service integration. Existing transport test rejects any write from the receive runtime.

No physical head unit was connected. This remains an offline development bench, not a complete SYU replacement.

## Indexed preset callback increment

Live and replay module 1 now publish all fresh field-4 `[channel, frequency]` tuples through the existing toolkit subscription protocol. Each station expires independently after five seconds; disconnect or framing loss clears current presets, while reports label prior observations as historical. Tests cover the first/last AM/FM indices, invalid selectors, one-shot consumption, timeout, multiple cached stations and independent expiry. The full current Hardware Lab suite passes 94 tests; APK build and lint also pass. No head-unit validation is claimed.
