# Cabin-owned FYT protocols

## Current implementation (unreleased)

Active full-port work is tracked in [SYU-NATIVE-PORT-STATUS.md](SYU-NATIVE-PORT-STATUS.md).
The machine-readable source inventory is a work list, not a coverage certificate.

Cabin now selects its own bundled protocol registry using `com.syu.ms` package
version metadata and the full live CAN profile ID. It does not open vendor APKs,
scan DEX, load vendor classes, or interpret their instructions at runtime.
`dexlib2` and the comparison interpreter exist only in test sources.

The registry targets version family `2.23.07xx.xxxx`, including the researched
2.23.0711.1001 and the reported 2.23.0718.1700. Family matching is a compatibility
selection, not proof that every revision has identical behavior. Unknown families
and unmapped fields remain unavailable; no Civic profile is substituted.

### Actual coverage

- 3,349 profile entries, deduplicated into 713 protocol templates.
- 3,328 profiles have reference callback inventories; 21 have no packet reader.
- 584 profiles have bounded enum display tables (658 entries across templates).
- Own Kotlin Honda 0298 conversions cover temperatures, units, maintenance,
  seat levels and selected settings; Audi profile 286 has its verified speed scale.
- Own Honda setting commands use explicit command 105 parameters and profile
  exceptions. Other enum tables are read-only and never infer write capability.
- CAN and MAIN subscriptions remain separate, and stale feedback is removed.
  Option writes require current profile, connection ownership, fresh feedback and
  an explicitly supported target. Display changes wait for actual service feedback.

These counts are not complete data/control parity for all profiles. Numeric formats,
commands and conditional screens outside the explicit implementations still need
individual protocol work. Real head-unit behavior has not been tested locally.

### Native Golf/MQB mirror and wiper settings

The July 2023 reference `Golf7FunctionalMirrorsAndWipersActi` uses fields
51–55 and commands 67–71 for mirror synchronization, reverse dip, parked folding,
automatic rain wiping and rear wiping in reverse. This differs from the older
reference's fields 148–152, which overlap climate fields in this firmware.

`CabinGolfSettings` implements those conversions and command frames directly in
Kotlin. Selection intersects the exact `ConstGolf.isWcGolf` / `isRZCGolf` lists
with the registry's corresponding callback: 44 WC and 52 RZC profiles. WC requires
an availability byte except for parked folding; RZC accepts plain boolean feedback.
Missing/invalid values cannot enable writes. The existing WC mirror widget receives
normalized values from 51–55; old raw climate IDs cannot populate its switches.
Commands retain the controller's profile, connection and fresh-feedback guards.
The focused protocol/Binder run passed 42 tests, including raw-field collision,
WC availability, RZC values and reconnecting after a profile change.

Subsequent native work adds parking and opening options, multifunction display
toggles and confirmed trip resets, unit selection, base lighting, and WC hybrid
charging/energy readings. The latest focused run passed 72 tests with zero failures
or errors. Coverage is still partial; see the active port status for remaining
pages, families and protocol differences.

### Removal audit

The local release build and 53 selected tests passed (protocol, Binder, settings
navigation, car tools, and option-picker checks). The final APK's 5,592 classes
were inspected with Android SDK `dexdump`; the vendor DEX reader/interpreter
classes and `org.jf` implementation are absent. The release dependency model also
contains no dexlib/smali dependency, and the APK registry matches the source asset.

The previously completed v0.1.5 release was withdrawn to a draft because it still
contained the old reader. This replacement has not been published. These checks
prove the architecture change and local software behavior, not vehicle testing or
full SYU feature parity.

### Development reference tools

`app/src/test/kotlin/com/cabin/platform/reference/` contains the offline research
and comparison tools. `FytProtocolExportTest` is opt-in with
`CABIN_EXPORT_FYT_REGISTRY=1` and `CABIN_SYU_RESOURCE_DUMP` pointing to an `aapt2 dump
resources` text file. It uses local reference APKs to generate
`app/src/main/assets/syu/protocols-2023.json`. The shipped file contains field maps,
finite value/text ranges and profile facts, not executable vendor instructions.

The original-settings launcher fallback has been removed from this viewer.

---

## Historical implementation: v0.1.5 and earlier

The following notes describe the previous runtime inspector and the reference
research behind the bundled registry. They are **not** the current runtime architecture.


## Reference and scope

The inspected `com.syu.ms` APK SHA-256 is
`4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577`.
The stock CAN UI's `FinalCanbus` supplies 3,349 distinct positive profile IDs;
`profile-catalog.json` retains their names/aliases and source hash. Catalog names
are metadata, not permission to apply a decoder or send commands.

The runtime reads the **installed** base/split APK DEX files. APK hash, version and
brand are diagnostic metadata, never a substitute for checking the code. The
active profile is dispatched through `f0/wp.V`, including IDs outside the catalog.
Inherited packet readers are resolved. Code inspection runs on the worker and
never loads or executes vendor classes.

## Discovery

A bounded control-flow analysis tracks constant callback IDs and callback-array
origins. It follows receiver methods, constructors, helpers and instantiated
Runnable bodies, retaining only IDs with a statically known publication. The
publisher wrappers are checked structurally. Direct `i1/v` publication is routed
according to its callback array:

| Callback array | Module | Reader |
| --- | --- | --- |
| `f0/tp.e` | CAN (7) | Existing CAN connection |
| `p0/b.c` | MAIN (0) | Separate live-only subscription |

A MAIN field never enters CAN decoding, even if the numeric IDs coincide. MAIN
subscriptions use `notify=0`: no cached-register branches, commands or readback
requests are issued. They reconnect independently and are stopped when the CAN
profile/connection is cleared. Queued old callbacks are rejected by ownership.

The inventory is an over-approximation of possible publications for a receiver;
branches and asynchronous helpers need not all run on a particular vehicle. It
is not evidence that every listed field will emit data. Unknown computed IDs,
reflection/native code and publication outside the inspected call graph remain
limitations. Bounds are 2,048 reachable methods and 100,000 control-flow steps per
method. CAN IDs are bounded to 0–1199; MAIN uses the inspected 256-slot interface.

## Semantic interpretation

`module/canbus/v.M2` packet 0x21 publishes A/C from byte 2 bit 6, fan from the
low nibble of byte 3 (clamped to 7), and climate flags. Packet 0x24 publishes door
flags, including the `f0/tp.U` door-order option. Canonical fields are 20–24,
26–30, 32–34 and 36–41.

Every profile selecting this inspected receiver is analyzed separately. There is
no four-profile Honda whitelist. Symbolic expressions and branch conditions must
match a known field uniquely; relocated IDs can be subscribed and normalized.
Partial matches retain their verified fields. Failed semantic analysis preserves
the raw inventory. New read IDs do not authorize new command IDs.

A numeric publication alone does not establish meaning, scaling or units. For
example, field 181 also carries clock hours in the supplied receiver and must not
be displayed as oil-service distance. Unverified fields never populate legacy
speed/RPM/oil gauges. Existing separately defined SYU air/factory integrations
remain available; they are not counted as newly verified by this inspector.

## Local viewer and payloads

**FYT → Raw FYT fields** displays CAN and MAIN separately without a download.
Complete bounded integer, float and text arrays are retained, using the shared
SYU parcel codec (64 KiB total, 1,024 numeric values, 64 strings, 4,096 characters
per string). Only single integer values enter semantic CAN decoding. Raw samples
expire after 60 seconds and clear on ownership changes. Cached CAN notifications
are not proof of fresh physical measurements; MAIN displays live callbacks only.

Diagnostic exports contain module field IDs/counts, receiver, matching status and
profile metadata, never raw arrays/text. Existing scalar CAN journal entries
retain their existing behavior.

## Latest local coverage (2026-09-15)

The audit checked all 3,349 stock catalog IDs against the supplied service:

| Result | Profiles |
| --- | ---: |
| Callback inventory available (CAN and/or MAIN) | 3,328 |
| Packet reader empty in this firmware | 21 |
| Of those with an inventory: all 19 inspected climate/door fields matched | 64 |
| Of those with an inventory: partial semantic match | 1 |
| Of those with an inventory: raw fields only | 3,263 |

The 21 empty-reader profiles are reported as `no_packet_reader`, not as failed
hardware detection. This identifies the implemented packet reader, not whether
another firmware, external app or uninspected route can support the vehicle.

The local test writes `app/build/reports/fyt-profile-coverage.txt` with the result
for every profile. Tests cover the actual supplied APK, repacked multidex,
relocated IDs, unknown semantic instructions, both modules, duplicate numeric IDs,
profile ownership, full arrays and malformed payloads, and private report exports.
The proprietary firmware remains local and is not committed to CI.

**This is not full semantic support for every vehicle field.** Motion scales,
temperature conversions, maintenance meanings and profile-specific settings not
already verified require additional decoder evidence. The coverage counts are
static analysis, not tests on 3,349 vehicles or a confirmation on the user's Joying.

Final local validation: 58 focused tests passed, zero failures/skips. The minified
release APK compiled successfully (unsigned local artifact). No release was
published and no head-unit hardware test was performed.

## Comparison with the supplied SYU CAN Bus client

Client APK SHA-256:
`98103bc4b64cfc87cd5957a3659304c078a69d8388ae4878fe4950119f15110d`.
The installed client's `HandlerCanbus.getCallbackCanbusById` is now interpreted
statically for the complete profile ID. This is the actual client dispatcher,
not a guessed callback filename or a low-word-only lookup. All 3,349 reference
profiles selected a callback; 3,009 callbacks supply named `U_*` fields. Names
are intersected with fields discovered in the installed service. Marker constants
are excluded. Missing/unreadable client code adds no guessed names. Updating
either vendor package invalidates the cache. The viewer supports name/ID search.

The original `Callback_0298_XP1_2015SIYU_CRV`, its four `Air_0298_*` renderers,
`AcrivitySiYuSettings` and `ActivityAirControl` were compared directly:

| Function | Original client | Cabin implementation |
| --- | --- | --- |
| Callback selection | Full-ID dispatcher | Installed dispatcher analyzed |
| Field names | Callback constants | Installed constants shown in field viewer |
| Front/rear temperature | 25, 31, 52; 33 selects C/F; C is half degrees | Named fields plus packet-expression validation; unit required |
| Temperature limits | -1 unavailable, -2 LOW, -3 HIGH | Existing Cabin formatting and unit-gated readings |
| Rear climate and seats | 53–57, 91–97 | Named fields plus packet-expression validation |
| Maintenance distance | 135 unit, 136 negative flag, 137 distance | Mapped to canonical 179/180/181 as an atomic group |
| Front A/C, airflow, fan | Command 105, keys 172/173 on the ordinary 0298 path | Existing verified command path retained |
| All other client settings/formatters | Numerous profile-specific screens and handlers | Not yet full parity; names do not supply enum labels, units or commands |

Maintenance field 137 is **distance**, not oil-life percent, for this reader.
`AcrivitySiYuSettings.uOilSrvLife` displays km/mile and a separate negative sign.
Packet 0x32 also duplicates some source bits into other fields; the installed
client's named constants distinguish 135/136 from those aliases. Both names and
packet expressions must match before using the maintenance group. Missing live
unit/sign removes the whole displayed maintenance group. Clock field 181 cannot
satisfy it. Signed/unsupported distance encodings remain unavailable.

Temperature matching accounts for the firmware's `persist.fyt.reversetemp`
branch without assuming its current value. Boolean path coverage is compared
independently of branch merge order. The four inspected stock renderers all use
half-degree Celsius and integer Fahrenheit; no temperature is displayed without
unit feedback. These formats are inspected protocol templates, not arbitrary
UI-bytecode execution. New read metadata does not infer write commands.

This comparison improves the installed-code reader but **does not establish full
parity with every SYU screen, formatter or vehicle-setting command**. Unknown
scales/enums and unported controls still require their own verified mappings.

Client-comparison validation: 58 focused tests passed with no failures or skips;
local minified release compilation passed. No release or hardware validation was
performed by this change.

## Installed SYU screen read programs

The client reader now follows the installed `ActivityLauncher.launchCanbus`
bytecode with the complete live profile ID. The supplied APK yields candidate
screen routes for 2,772 of the 3,349 catalog IDs. This is a navigation audit, not a
count of fully decoded vehicles. Remaining IDs are not assigned another car's
screen. References to subpages are followed with profile comparisons preserved;
unknown presentation/configuration branches can retain alternative candidate
screens. The local audit is `app/build/reports/fyt-syu-screen-routes.txt`.

`FytSyuReadProgram` interprets a restricted display-only instruction set from
those candidate screens: integer/float/double calculations, switches, stock
string resources, string builders, bounded number formatting, `setText`, and
`setChecked`. It does not load or execute vendor classes. A missing live input,
resource, unsupported instruction/call, instance-state dependency, or exceeded
instruction bound discards that entire program's output. It does not replace
missing values with zero. Conflicting formatters for the same screen/view are
suppressed.

Both direct `DataCanbus.DATA` readers and scalar helpers selected by the screen's
`onNotify` callback are supported. Field-to-helper associations come from the
notification control flow, not method names. Before using a raw scalar as stock
screen data, the selected vehicle callback must forward it unchanged to a
recognized `HandlerCanbus.update` overload. That handler is checked for storage
of the original scalar at the original update ID. Transformed/event-only
callbacks are excluded; for example Honda field 35 toggles an activity rather
than supplying a screen reading. Stateful helper methods remain unavailable.

Results appear under **SYU readings / Dados do SYU** with name/ID search. Screen
provenance is retained because alternative stock subpages can format the same
fields differently. This is a diagnostic presentation of candidate screen
calculations, not a recreation of their Android layouts, visibility rules or
interactive controls. Controller data must also belong to the service's selected
field inventory and be fresh. Cached calculations are invalidated on input
changes/expiry; profile changes and disconnect clear the presentation. Live
formatted data now contributes to the connection status.

The **Original SYU settings** action opens the installed package's launcher. SYU
itself selects and operates its vehicle-specific controls. Missing/unlaunchable
packages are handled without constructing an unverified internal component.
**This action is a fallback, not an implementation of all those controls inside
Cabin.** The new interpreter is read-only and does not infer or send commands.

Evidence includes the actual Honda Trip A enum, exterior-temperature adjustment,
light-sensitivity limits, checked states and attention-monitor notification
helper, plus the Audi stock speed conversion (raw / 16.0f, four decimals, Km/h).
Tests also cover resource/input absence, expiry, changing profiles, unsupported
side effects, bounded loops, and rejection of a changed common-handler scale.
The proprietary APK remains a local fixture; no firmware is uploaded by these
checks. Full semantic parity with every SYU field, layout and command is still
not implemented.

Local validation for the screen-reader addition: 68 focused tests passed, zero
failures/errors/skips; minified release compilation succeeded. The APK is the
unsigned local build. No head-unit validation or publication was performed.
