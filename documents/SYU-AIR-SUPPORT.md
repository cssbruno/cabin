# SYU Air support

Cabin now has a shared profile registry, a bounded command dispatcher, a native
climate panel, and a dashboard widget that uses pages instead of vertical scrolling.
Vehicle identities are exact numeric CAN profiles, not a brand-wide wildcard.

## Coverage and limits

The pinned public reference contains **2,033 explicit profiles** across its shared
Air screen initializers and router. The initial extraction finds bounded command
sequences for **1,975**. That is source coverage, not proof that all vehicles or
firmware versions work. Profiles without a resolved command stay unavailable.
State-dependent handlers that require separate protocol adapters are not silently
converted into generic key presses. Unmapped functions remain unavailable.
Existing Civic dialects and their verified controls retain their current behavior.

The native interface includes the registered front climate functions, airflow,
seat heating/ventilation, rear controls, and auxiliary climate functions. Each
control also needs fresh feedback for its associated field. Unsupported functions
are never sent to another profile as a fallback.

## Source provenance

Protocol facts come from revision `755c9ae89ef255a975bcc0ee6a149e68cce2128c` of
[the public FYT reference](https://github.com/vasyl91/FYT-Launcher-Mod/tree/755c9ae89ef255a975bcc0ee6a149e68cce2128c/app/src/main/java/com/syu/carinfo/air).
The application does not include vendor Activities, runtime Java evaluation,
root access, downloaded executable modules, or an arbitrary CAN command UI.
Source hashes and screen names are recorded in `assets/syu/air-profiles.json`.

`tools/extract_syu_air.py` uses `javalang==0.13.0` to interpret a restricted subset
of static assignments and control flow offline. Unsupported expressions fail
closed. Every exported command must match a source button forwarding that exact
control constant on both press and release. Where multiple screen generations
share an identity without an explicit route, only agreeing facts are retained.
The temperature-reversal firmware branch is evaluated with both boolean values.
Only identical complete command sequences are retained, so unaffected Toyota
controls work without assuming the firmware setting. Front temperature commands
whose target zone changes with that property remain unavailable.
Temperature conversion candidates require a separate exhaustive verification step
before they may be used for displayed temperatures.

## Runtime boundaries

- Catalog is bundled in the signed APK; there is no external profile import.
- Catalog size, identities, field indices, command indices, frame counts, and
  payload lengths/values are bounded during loading.
- Only registered callback fields are accepted. Vendor sample envelopes and
  integer counts are bounded; unused arrays are not allocated.
- All writes run on one worker and use the detected profile's fixed command plan.
- Profile, connection epoch, selected layout, and field freshness are rechecked
  before dispatch. Failed connections stop the remaining frames.
- Readings change on feedback, never optimistically after a button press.

This is targeted defensive engineering, not a complete security audit or a claim
of universal hardware compatibility. Actual FYT firmware still controls access to
its toolkit and CAN decoder capabilities.

## Current verification results

Temperature models for **177 profiles** were checked for every integer input in
0..1023, in both unit modes where defined. Other numeric temperature displays
remain unavailable; relative commands can still be available with fresh zone
feedback. Known LOW/HIGH/unavailable sentinels are handled separately.

This is not yet a complete port of every SYU function: **58 catalog profiles**
still have no extracted command plan, and some controls in otherwise covered
profiles need state-dependent or firmware-specific adapters. They remain disabled.
The current implementation must not be advertised as universal vehicle support.

Rebuild protocol facts with `tools/extract_syu_air.py` using the pinned source
files, then run `tools/verify_syu_temperature_facts.py` before accepting temperature
models. Only the verification output carries the flag enabling numeric models.
