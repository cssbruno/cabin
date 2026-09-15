# Vehicle and radio integration

## Implemented client behavior

- Audio widget → Radio opens a full-screen, scrolling native radio panel. It shows
  current frequency, seek/step controls and reported presets. Overwriting a reported
  preset requires a confirmation and the same current AM/FM family.
- Commands run through installed SYU module 1. They require fresh valid station feedback,
  use a connection epoch, and are limited to one command per 300 ms. Successful Binder
  replies do not replace station readings. No guessed station values are shown.
- Radio callbacks 0/1/4 are refreshed using cached subscriptions every 15 seconds and
  expire after 30 seconds. A band change clears the previous frequency and invalidates
  old queued commands. Leaving the resumed screen closes its connection.
- The vehicle controller refreshes quiet cached fields every 15 seconds. Motion fields
  89/90/149/151 are excluded: cached speed must not become new driving evidence.
- Activity stop suspends vehicle telemetry and clears its connection/readings. Activity
  start reconnects and registers fields afresh. This complements existing video resume
  and display recovery. It is Android lifecycle handling, not an ignition/MCU protocol.
- Existing steering-button learning now offers Launcher, including short/long mappings,
  repeat suppression and cancellation on focus loss. It calls the existing full-screen
  Home transition, so compact projection mode is exited correctly.
- Hub → System health → details now includes a copyable compatibility snapshot of the
  vehicle profile, available field IDs, doors, actionable climate controls, factory
  settings, tyres and battery availability. Counts also appear in exported diagnostics.
- Existing profile-aware climate registry, temperature/fan/airflow/switch controls and
  vehicle widgets remain in use. No unverified fuel or temperature decoder is added.

## Radio protocol evidence

Inspected locally using Android SDK apkanalyzer: Joying UIS7862 2023-08-31 factory
`190043001_com.syu.radio.apk`, SHA256
`64a97cea25538864274aff317bb5d577e723ca51ebc87c97e3a9b5abfe4a2376`.
Bytecode excerpts are retained in `artifacts/joying-uis7862/inspection/radio/`.

- `com.syu.e.a.a.d.a(com.syu.d.g)` requests module 1 and registers cached fields.
- `FinalRadio`: U_BAND=0, U_FREQ=1, U_CHANNEL_FREQ=4. Scalar updates consume ints[0];
  preset callback consumes [channel,frequency]. AM channels 0..11, FM65536..65553.
- `Ipc_New.freqPlus/freqMinus`: command 3/4 with null integer payload.
- `Ipc_New.SeekUp/SeekDown`: command 5/6 with null integer payload.
- `Ipc_New.SelectChannel`: command7[channel].
- `Ipc_New.saveChannel`: command8[channel], with six slots per reported band.
- `com.syu.e.h` overloads establish null/singleton integer-array command shape.
- `Page_Radio` displays FM as frequency/100 MHz; AM uses kHz.

Cabin uses reported presets, not invented station slots. Broad read-value bounds are
sanity checks, not region-selection controls. Frequency entry, region changes, auto scan,
RDS text, radio audio activation, and native driver replacement are not implemented here.
Use the configured factory Radio shortcut to activate the audio source. Firmware-specific
raw CAN steering events and ACC power commands are still unverified and are not sent.

## Validation limits

Tests use Binder doubles and Robolectric. Actual head-unit testing remains necessary.
A full-suite run hit a JBR21 JIT SIGSEGV in Node::uncast; rerun uses
JAVA_TOOL_OPTIONS="-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m"
and --no-daemon, with no change to APK runtime settings. The first workaround run
exhausted its default compiler cache and stalled during test-worker shutdown; it was
stopped before this fresh-JVM rerun.

Final validation: 613 app tests and 17 Hardware Lab tests pass; assembleDebug succeeds.
The suite covers radio Binder callbacks, epochs, stale feedback, tuning/preset bounds,
preset confirmation, portrait launcher entry, motion gating, vehicle resume and quiet
refresh, key dispatch, diagnostics and six-language resource coverage. An existing
Sound-test teardown race was corrected to accept the framework’s already-stopped
handler outcome while still asserting that the worker terminates.
