# Hardware Lab 0.6 — module interface, MCU radio and C7604 EQ

This update replaces the development service's scalar-only bridge with a general module interface and adds executable radio/EQ command generation. It still does not provide a production replacement for all SYU subsystems. No vehicle commands are sent by the app.

## Implemented

- `ModulePayload`: immutable integer, float and string arrays; defensive copies; finite floats; bounded messages, array counts and string sizes.
- `ModuleEndpoint` and `ModuleToolkitBridge`: dynamic module lookup, commands, synchronous typed readback, register/unregister, cached indexed tuples, changes/invalidation, one-way callbacks, death cleanup, closed-session rejection and diagnostics for rejected requests. Endpoint `get` methods interpret their own request codes; callback field numbers are not assumed to be readback codes.
- `RadioCommandPlanner`: MCU radio driver **1** commands from `r0/a`, reached through `r0/i.G2`. Produces framed commands without acquiring hardware or inventing feedback. It does not apply to driver 4's native FM implementation.
- `C7604Equalizer`: 48 kHz peaking-filter coefficient generation, all 36 EQ register spans, five-word and ten-word coefficient formats, reference quantization and integration with the existing transactional `C7604Writer`.
- `CommandJournal`: bounded 64-entry offline output journal, with monotonically increasing sequence numbers and explicit MCU/I²C labels. A recorded command means “bytes generated”, never “device acknowledged”.
- App controls for radio step/seek previews and a C7604 EQ example. The entire bench screen scrolls for smaller head-unit displays. Saving a report includes the command journal.

Open **Cabin Hardware Lab → Open replacement bench**. Radio preview buttons generate bytes immediately; the EQ button generates a band-20, 1000 Hz, Q=1, +6 dB reference filter and its commit packet. These examples do not represent the current radio or EQ state of the car.

## Evidence

All implementation evidence is from the local Joying service APK identified by SHA-256 `4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577`. See [the audit](JOYING-REPLACEMENT-AUDIT.md) and [service inventory](JOYING-SERVICE-CONTRACT.json). No vendor code or native library is bundled.

### Binder correction

`x/c$a.onTransact` implements module transactions 1/2/3/4. `x/c$a$a` uses one-way command/subscription requests and a synchronous get request. `x/b$a$a.D0` sends callbacks with `FLAG_ONEWAY` and no reply. The previous bridge incorrectly assumed synchronous callbacks for the replacement path. The original simulator remains separate for compatibility with its existing tests/client.

The new bridge supports both observed one-way command/subscription requests and synchronous bench callers. Get must be synchronous and returns:

1. Exception header.
2. Integer presence marker (0 unavailable, 1 present).
3. Present values: integer array, float array, string array.

Commands that cannot be performed raise exceptions for synchronous callers. One-way rejections are counted in the diagnostics because their callers cannot receive a reply. Rejection count includes malformed payloads and closed-module requests.

Limits: 16 KiB request, 64 integers, 64 floats, 16 strings of at most 256 characters; 20 module IDs, 32 clients per module, 128 fields per client, 64 cached tuples per field. Android's Parcel parser validates string storage; the bridge additionally enforces the whole-message and decoded string limits. Null arrays and empty arrays remain distinct on the wire. Callback field IDs are bounded to 0–4095 for currently inspected modules.

Indexed-field invalidation uses a null-array tuple before publishing a replacement set. This is a Cabin extension for clearing stale data, not proof of undocumented vendor invalidation semantics. A consumer must explicitly support it. No readback values are fabricated for endpoints that do not implement a get operation.

The diagnostics service's transaction 2 returns this new toolkit. CAN module 7 appears after explicitly selecting the Honda reference profile. Radio module 1 appears after selecting an offline radio preview; it only records commands. New replay/configuration selections replace the toolkit, so clients must reacquire it. The service still does not claim the vendor package/action/system UID.

### Radio driver 1

`r0/f` chooses `r0/a` for radio driver ID 1. Driver 4 can select `r0/h` → `com.android.fmradio.NativeRadio` → `FmNative`; those are different native hardware paths. Driver identity cannot be inferred from toolkit module ID 1.

Supported service codes and MCU payloads:

| Service code | Operation | Payload |
| --- | --- | --- |
| 0 / 1 | Next / previous preset | `01 03 0A` / `01 03 09` |
| 3 / 4 | Step up / down | `01 03 11` / `01 03 10` |
| 5 / 6 | Seek up / down | `01 03 06` / `01 03 05` |
| 7 | Select AM/FM preset | AM 0..11 → `01 03 (E5+index)`; FM 65536..65553 → `01 03 (81+index-65536)` |
| 8 | Save AM/FM preset | AM → `01 03 (53+index)`; FM → `01 03 (41+index-65536)` |
| 9 / 10 | Scan / save | `01 03 12` / `01 03 08` |
| 11 | Select band | AM 0/1 → `1D/1E`; FM 65536..65538 → `1A/1B/1C`; band -1 → `18`, all prefixed `01 03` |
| 12 | Region 0..4 | `01 03 (21+region)` |
| 13 | Explicit frequency mode 3 | `25 frequencyHigh frequencyLow`, value 0..10800 as bounded by the reference |
| 15 | Auto sensitivity 0/1 | `01 00 9E/9F` |
| 18 / 22 | LOC/search toggle (argument 2 only) | `01 03 0D` / `01 03 04` |

Other commands, relative band selection requiring current state, frequency modes requiring region/range/step state, incorrect argument counts, invalid channels and noninteger payloads are rejected. Low-level frequency bounds reproduce the reference interface; they do not establish a regional tuning plan or current band. These encoders are suitable for offline analysis until the target driver and readiness are verified.

### C7604 peaking EQ

Evidence: `C7604.D0`, `b0`, nested coefficient helper `$e.a/b`, quantizer `$j.a/b/c`, and constructor arrays `U2` and `X2`.

`plan(band, frequencyHz, qTenths, gainStep)` requires:

- Band 0..35.
- Frequency 1..23999 Hz (below the fixed 48 kHz Nyquist limit).
- Q expressed in tenths, restricted by this API to 1..1000. This upper bound is an implementation policy, not a claim about factory UI limits. The reference float division precedes conversion to double.
- Gain step 0..20, mapped by the reference X2 table to -10..+10 dB.

Let `w = 2πf/48000`, `alpha = sin(w)/(2Q)`, `A = 10^(gainDb/40)` and `d = 1 + alpha/A`. In reference memory order the coefficients are:

```text
[(1-alpha*A)/d, -2*cos(w)/d, -(1-alpha/A)/d, 2*cos(w)/d, (1+alpha*A)/d]
```

Bands 0..19 use command `89`, register `62 + 10*band` and two 24-bit words per coefficient. Bands 20..35 use command `84`, register `262 + 5*(band-20)` and one 24-bit word per coefficient. The first path splits a coefficient scaled by 16384 into a 16-bit upper part shifted by eight and a 23-bit fractional part. The second path scales by 4194304. Rounding/clamping follows the inspected helper; packed unsigned words are normalized to signed 24-bit Java values for the existing writer. A successful write is followed by `A4 00 00`.

The quantizer has an observable near-zero negative rounding quirk: at the exact quarter-rate frequency, a tiny negative cosine term becomes -1 in the single-word path, or upper word -256 in the extended path. Tests pin this behavior instead of substituting a guessed conversion. These are reference-compatible encodings, not evidence of optimal DSP behavior.

Tests check register nonoverlap, fixed-point vectors, quantized center gain, DC/Nyquist response, pole stability at representative gains and invalid inputs. Full-chip initialization, persisted EQ settings, non-EQ filters, audio routing, amplifier sequencing and sound Binder feedback remain incomplete. Calling a plan's `apply` requires a correctly initialized and exclusively owned C7604 writer; the app itself uses only the offline journal.

## Still required for “replace all of SYU”

A complete replacement still needs the actual head unit's selected drivers and initialization/lifecycle behavior. The saved APK contains many alternative drivers; a function found there does not establish which is active on the target.

The unresolved work includes NativeRadio/FM driver 4, Bluetooth, full DSP initialization/readback, MAIN audio routing, ACC acknowledgments and sleep/wake recovery, remaining CAN profiles/transmit commands, and the other peripheral modules. The existing APK is a runnable development tool, not authorization to disable the factory service. Next hardware evidence is the unit's inventory and matching firmware, MCU version, selected CAN profile and DSP/radio driver IDs, followed by direction-labeled startup and control captures.

## Validation result

64 JVM/Robolectric tests passed. `assembleDebug` succeeded with ARM64, ARM32 and x86-64 native libraries. Android lint completed with zero errors and 33 warnings. `git diff --check` passed; APKs, native build directories and local firmware artifacts remain ignored by Git. Physical radio, DSP and vehicle integration are not covered by these tests.
