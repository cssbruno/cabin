# Native SYU port — active work

Scope: all vehicle profiles, data and controls in the supplied July 2023 firmware.
Runtime code must be Cabin-owned Kotlin/Java. Vendor bytecode is development
reference only. Raw-field inventories and enum tables do not establish full parity.

## Reference inventory

`SYU-PORT-INVENTORY.json` indexes 3,349 profiles, 616 callback families, and 1,474
Java source screens/helpers. Reproduce with:

```
python3 tools/syu_port_inventory.py --sources /tmp/cabin-stock-client-src/sources
```

The source directory comes from offline JADX decompilation of the pinned local
`com.syu.canbus` APK. The inventory records hashes, literal IDs and unresolved
review requirements; it is not an automatic coverage verifier. Computed IDs,
helper methods, resource labels and profile branches require individual review.

## Native implementations added to the registry

| Family / source | Implemented | Still requires work |
| --- | --- | --- |
| Honda 0298 settings | Explicit option commands, temperature and maintenance conversions | Remaining screens, numeric readings and conditional controls |
| Honda 0298 / WC0321 trip | Current/previous consumption, independent A/B histories and units, range, time, EV battery for exact profile; corrected widgets | Other Honda callback families; remaining EV pages |
| Honda WC0321 controls | Own lighting, remote, door, panel, camera, tailgate, surround/speed-volume and driver-assistance settings; confirmed history/service/settings/TPMS actions; profile restrictions | Remaining amplifier integration and pages |
| Honda RZC/ZX camera | Reverse delay and five voice reminders on five routed profiles; grouped writes preserve other fresh values; six native panorama controls on RZC settings profiles | Other RZC variants |
| Honda amplifier 0298 | Volume, fader, balance, tone controls, speed compensation, DTS and confirmed reset on six routed profiles | WC amplifier uses a different contract; remaining amplifier families |
| Ford 0334 | Tires, basic/Transit trip readings, Escape/Transit and earlier settings, language, routed factory amplifier, Explorer seat support/massage, Mustang radio/CD | Navigator-specific panel, Sync integration, other Ford families and complete routing audit |
| WC GM 0036 | Basic information, fresh dashboard motion, 28 capability-gated comfort/lock/remote/light settings | Remaining settings, OnStar, tire and air pages |
| Bagoo Audi 286 | Verified speed conversion | Other data and controls |
| WC/RZC Golf mirrors | Five options; availability and fold exception | Other Golf callback families and physical verification |
| WC/RZC Golf parking | Sensor settings, warning volume/tone, sound, exit assist and brake settings | Parking-mode command/feedback relationship; non-WC/RZC variants |
| WC/RZC Golf opening | Window/door options, auto locking, Easy Open, RZC window/sunroof and monitoring options | OD-specific language and remaining variant options |
| WC/RZC Golf multifunction display | Ten display toggles; trip reset actions with confirmation | Other callback families |
| WC/RZC Golf units | Distance, speed, temperature, volume, consumption, pressure, exact hybrid/electric unit profiles | OD language selector and other callback families |
| WC/RZC Golf lighting | Ten base controls, ranges, traffic-side differences | Ambient colors, additional lighting, variant controls |
| WC hybrid 655377 | Charging settings/capability, battery, energy flow and distance data; corrected existing widgets | Remaining energy-management pages; RZC's separate contract |
| Other families | Existing integrations plus bounded enum tables | Individual semantic and command parity audit |

## Bugs found in old reference assumptions

- Golf mirrors: July raw 51–55, not older canonical 148–152.
- Golf parking: July raw 19–23, not older canonical 116–120.
- WC hybrid charging: capability 271 and settings 272–275, not 299–303.
- WC hybrid battery/flow: raw 303/294, not 321/312.
- WC/RZC labels and command values differ for some units and opening settings.

The old widget IDs remain internal canonical IDs after explicit normalization.
They must not be read directly from July firmware callbacks. Other pre-existing
integrations still require an audit. Honda trip now uses fields 1/2/7 instead of
99/100/105, and Ford 0334 tires use 78–89 instead of the old pressure indices.
Tire history records the originating profile and rejects another profile's data.
Do not infer that old integrations are correct merely because earlier tests passed.

Honda WC0321 and RZC0298 share trip value formats, but WC service `x.cmd`
does not implement RZC query 100. WC receives the automatically published values;
it does not fall back to the old RZC query. WC history reset 101/[3] is implemented
by service x and is deliberately unavailable on service v (RZC0298), which has no
matching command. Golf reset actions remain a separate explicit allowlist.

Honda WC settings reference: `Wc_16Civic_{LightActi,RemoteActi,DoorActi,Pannel,SaftyActi}`
and service x. Field 94 delay uses 110/[12,4 or 5], whereas field 93 enable uses
110/[12,0 or 1]. Invalid zero values for several enumerations are not writable
options. Panel/lighting profile restrictions are retained. Language command 112/[1,n]
has no observed feedback field in that screen and still requires a separate UI contract.

## Completion gate

Full completion has not been established. For each applicable profile and screen:

1. Resolve the live service contract and profile-specific routes.
2. Implement values, units, sentinels, availability and source-defined controls.
3. Expose the corresponding UI, including parameterized/destructive actions.
4. Verify command frames, profile changes, expiry and connection ownership locally.
5. Record unsupported source behavior and evidence gaps explicitly.

Passing tests prove their particular software cases. Physical vehicle behavior
cannot be asserted without head-unit evidence. The new implementation is not released.

## Latest local checks

Honda WC trip/settings plus binder, UI and history checks passed in
`/tmp/cabin-honda-wc-full-check.log`. The amplifier/legacy-widget batch is a
subsequent change and has its own validation log. Smali confirms service x
command switch starts at 101, while v starts at 102; x's history reset sends
E3/02/F2/06/FF. Neither path delegates an unknown command to a parent.

The July WC service does not publish the old amplifier fields 201/202, so those
legacy widget values are discarded for this dialect. Honda0298 lighting widgets
now normalize actual 61/62/63 into their internal 122/123/124 IDs.

Honda WC camera/auxiliary commands also follow `WCCommpassActi` and
`ActivityBatteryDoor`, with service x confirmation. Camera fields 105–108 share
selector 13 and encode independent features as 6/7, 4/5, 2/3, 0/1; these values
must not be sent as generic booleans. Tailgate options are restricted to the eight
profiles with the source entry visible. Service/settings reset and TPMS calibration
use the existing action-confirmation and parked-action UI; no action is executed
by adding support.

The Honda WC/amplifier/settings batch passed the selected local unit/UI/binder
checks and `assembleRelease` (`/tmp/cabin-native-honda-batch-final.log`).
Subsequent RZC grouped controls and per-action confirmation text are checked in
`/tmp/cabin-native-honda-rzc-check.log`. No physical head-unit result is implied.

RZC/ZX command 152 replaces five reminder switches at once. Cabin requires all
five current values (227–231), preserves the four unchanged switches and refuses
to write from incomplete/invalid data. This corrects the stock pattern of taking
all values from an unvalidated shared array.

## Next concrete source findings

- `Acrivity_RZC_Aodes360Settings` is reached through RZCCommpassActi for
  `HondaIndexActi.showRZCSettings()` profiles (same low-298 exclusions as
  `CabinHondaTrip.supportsTripB`). Native 360 controls have now been added in `CabinHondaPanorama`:
  fields 167/168 in 0..3 and 169/170/171/172 in 0..1 map to command 105
  selectors 53/54/55/56/57/58. The stock rear-view buttons incorrectly read
  field 105 while their notify/display uses 168. Service v publishes 168 from
  bits 4–5 of the camera settings packet; implement from 168, not the bad
  button code. Field 172 is the intersection monitor, verified in the layout resources.
- RZC compass area field 18 must be constrained to 1..15 by service v command
  102; the source local counter allows 0, which the service clamps to 1.
  Command 103 starts calibration; 104/[value] changes right-turn camera
  option field 50. These are not yet native controls. Never reuse these IDs
  on WC, where field 50 means lighting sensitivity.
- `Wc_16Civic_Pannel` exposes language command 112/[1,1..3] with no observed
  live language field. A separate choice action is needed; do not manufacture
  a feedback value or claim the selected language is confirmed.

### Verified Honda batch (2026-09-15)

`/tmp/cabin-native-honda-rzc-check.log`: 75 tests, zero failures/errors/skips;
`assembleRelease` succeeded. Local unsigned APK: 9,775,145 bytes, SHA-256
`470ec4d1d9235cb44c7500cca25fef7716283728a55ca2aff3ebbe0aa82adb63`.
SDK dexdump inspected 5,602 class descriptors; no dexlib2 or moved reference
reader/interpreter classes were present. This artifact includes the native Honda
WC controls, BNR/RZC amplifier, grouped RZC reminders and action-specific
confirmations. It remains unpublished, and full profile/screen parity is pending.

The panorama batch also makes native Honda field ownership explicit even when
values are absent or invalid. A generic reference enum can no longer render a
contradictory value for a rejected native temperature/settings field. Current
validation: `/tmp/cabin-honda-panorama-check.log`. The APK hash recorded above
predates these panorama and ownership changes and must be rebuilt before delivery.

Panorama/field-ownership verification completed: 63 selected tests passed, zero failures/errors/skips (`/tmp/cabin-honda-panorama-check.log`). No build or test process remains running for this batch.

## Compass and additional RZC batch

- `CabinHondaCompass`: live zone 18 (1..15), camera preference 50 (0/1),
  commands 102/[zone], 104/[value], and confirmed calibration 103/[] for
  the Commpass/RZCCommpass routes. BNR's separate settings profiles are excluded;
  WC never receives these commands. Service v clamps zone 1..15 and O3 persists
  camera preference, confirming the client contract. Compass batch tests passed
  (`/tmp/cabin-honda-compass-check.log`).
- RZC settings routes now support confirmed service/settings/TPMS actions via
  105/[14,0], 105/[15,0], 105/[17,0]. WC keeps its separate payloads.
- `CabinHondaRzcSettings`: 21 native switches from the 17CRV settings screen,
  including camera guides, seat/door options and traffic warnings. Field 109
  reverse tone uses selector 50 in RZC and 36 in BNR; the row and command are
  selected per profile, without duplicate display rows. Exact layout labels
  were checked by resolving XML sibling labels and resource strings, not by
  assuming sequential row positions. Labels for field 195 remain conservative
  because the supplied resource only says traffic sign recognition twice.
  Validation: `/tmp/cabin-rzc-extra-check.log`.
- Pending in that same screen: numeric options 158/177/179/193/197, language
  selection without feedback, and initial panorama action 105/[48,0]. Other
  profile families and screens remain in the full inventory queue.

Additional RZC value selectors are now implemented: parking width 158→46,
mirror folding 177→71, straight-line assistance activation 179→73, overspeed
warning margin 193→79 (+0/+5/+10/+15 km/h), and blind-spot warning 197→82.
Their UI uses named choices rather than boolean checkboxes. The preceding
21-switch batch passed local checks in `/tmp/cabin-rzc-extra-check.log`; these
additional enums have separate verification in `/tmp/cabin-rzc-enums-check.log`.
Language selection and the initial panorama action remain pending in that screen.

Field 195 label resolved: the default English resource duplicates traffic-sign
recognition, but the original zh-rCN `traffice_sign_recognition` resource explicitly
says recognition warning (交通标志识别警告). Cabin labels this separately as
“Traffic sign recognition warning” / “Aviso de reconhecimento de placas”.

Compass/RZC extra-enum batch: 65 selected protocol, binder and UI tests passed,
zero failures/errors/skips (`/tmp/cabin-rzc-enums-check.log`). The subsequent
field-195 edit only refines its EN/PT label based on the original resource.
No process from this batch remains running. No new APK was built in this batch;
the previously recorded APK predates these controls.

Next implementation contract: language selectors need an explicit choice command
without fabricated current feedback. WC uses 112/[1,1..3]; RZC 17CRV uses
105/[85,send_lang[position]]. See `Acrivity_RZC_17CRVSettings.java` lines 298+
for the exact list and wire values. Share UI/controller plumbing with profile,
connection-epoch and allowed-option validation; do not present the command's
selection as confirmed firmware state.

## Native choice commands and language UI

`FytVehicleChoice` distinguishes commands without current-value feedback from
readings. The state publishes choices, the UI opens a picker without marking an
assumed selected language, and a dedicated controller entry point validates the
live profile, connection epoch and allowed values. The existing parked-action
wrapper applies. MainActivity, dashboard actions and FYT settings pass the
callback through; no synthetic CAN feedback field is used.

Honda WC language: 112/[1,n], n=1 English, 2 simplified Chinese, 3 traditional
Chinese. RZC settings: 105/[85,n], n=0..32, with the exact order from the source.
The mistranslated “Snowflake” resource is Slovak (verified original Chinese and
Japanese resources), so the picker uses language autonyms. Protocol, binder and
UI tests check invalid values, unsupported profiles, delayed profile changes and
absence of a fabricated current reading. Initial language tests passed in
`/tmp/cabin-language-check.log`.

Also corrected RZC field 65 to a door-selection enum rather than an on/off switch;
BNR keeps its source-specific representation. Honda EV profile 1966378 uses
charging, not refueling, for Trip A/B reset timing value 0. RZC panorama
initialization is now an explicit confirmed action: 105/[48,0]. WC never exposes
that action. Final checks/build: `/tmp/cabin-language-final-check.log`.

Earlier pending notes for language, compass and panorama initialization are
superseded by these implementations. Full profile/screen parity is still pending.


## Golf additional lighting and ambient controls

Own Kotlin implementation now covers the additional Golf7FunctionalLightActi
contracts, checked against the July client source and its layout/resource labels:

- 140/141 brightness -> 105/[13 or 14,value]; 142/143 assist -> 105/[1 or 2,value].
- Overall illumination uses 267 with WC availability flags, or 144 for RZC;
  both issue 105/[15,value]. The wrong dialect's row and command are rejected.
- Headlight range 235 -> 133/[0..6]; WC Lane Assist brightness 258 -> 135/[1..5].
- Ambient color 139 -> 105/[12,value]: WC 0..33 with high-byte availability,
  RZC 0..10, extended RZC profile 3604640 0..20; profile 3342496 uses its separate
  scene/two-color controls. WC palette indices 4..33 are shown as colors 1..30.
- RZC ambient mode 339 -> 160/[74,value], vehicle variant 385 -> 160/[75,value],
  hidden for profile 3342496, matching the source layout gates.
- Profile 2818208: three-color switch/color 371/372 -> 160/[76,value], with
  colors 1/4/5; glovebox brightness 395 -> 160/[77,0..100].
- Profile 3342496: pairing 399 -> 160/[79,value]; scene 400 -> 160/[101,value]
  (source buttons skip 2); colors 401/402 -> 160/[78,value] and
  160/[78,value|128], restricted to 1..10.

Invalid, absent and unavailable current readings do not become writable controls.
No optimistic state update or runtime smali reader is used.
First additional-lighting batch passed `/tmp/cabin-golf-lighting-check.log`.
Expanded color/scene validation and APK build: `/tmp/cabin-golf-ambient-check.log`.
Full inventory parity remains unfinished; these changes do not prove other families.

The preceding language/panorama batch passed its selected tests and release build.
Its APK audit found 5,613 class descriptors and no dexlib2 or moved reference-reader
classes. SHA256: 08d663124eb7004e658eefac94f9a985539e700c1f4301bdccccab003f8bd25d.
That APK predates the Golf additions and is not a release of those additions.

Scene feedback value 2 is explicitly decoded as Off while remaining unavailable
as a command target, as in the source's skip-2 button logic. This permits a valid
transition from Off to a scene without inventing a command. Final validation log:
`/tmp/cabin-golf-ambient-final-check.log` (supersedes the preceding color batch).

Final Golf batch: 72 selected protocol, binder and UI tests passed with zero
failures/errors/skips; release assembly succeeded. APK SHA256 `a43ac30d65a3f8bcf4515b36e7a4fa59e272d2f851013bcf3dd9509d6954e679`,
5,614 class descriptors; no dexlib2 or reference-reader classes.
No physical head-unit validation, commit, push or publication in this batch.


## Hybrid charging schedules: explicit WC and RZC implementations

Confirmed profile routes in `Golf7IndexAct`: WC 655377 and RZC 655520.
The three schedule screens have distinct WC/RZC encodings. Added native Cabin
implementations using those contracts; no runtime reference reader.

RZC service `f0/x4` confirms command 142 -> two-byte schedule selection and
143 -> ten-byte schedule record. All 19 source fields of the selected record
must be present and valid before editing one. The seven weekday bits, repeat,
charging, climate, night charging, current, night window and charge limit are
preserved. Fields 404..406 preserve all three selection bits. Added clock and
RZC electric-information readings with the source's separate scales/temperature.

WC service `f0/p` confirms command 147 -> nineteen-byte schedule packet and
feedback frame 80. All three records are retained from fresh data, with the
source's update masks 12/20/24 and selection mask 28. Half-hour minutes are
encoded as 0/1 in WC versus 0/30 in RZC. Global current/charge limits remain
under CabinGolfHybrid; stock schedule aliases are suppressed instead of decoded
using RZC enum values. The service publishes the global charge limit to all
three aliases and global current to field 451, confirming why those rows cannot
be independent schedule controls.

RZC checks passed `/tmp/cabin-rzc-charging-check.log`; combined WC/RZC tests and
release build passed `/tmp/cabin-hybrid-charging-check.log`. Follow-up validation
including Joying tests: `/tmp/cabin-charging-carplay-check.log`.
Full firmware parity remains pending. No physical unit appeared in `adb devices -l`.

Final charging/CarPlay validation: 130 selected tests passed, zero
failures/errors/skips; release assembly succeeded in
`/tmp/cabin-charging-carplay-final-check.log`. APK SHA256 `ff92bd854b7f2ba6c5d2d09ab5fbe65da953058bd39bc1f30daeedc6ff18a977`;
5613 class descriptors, no dexlib2 or moved reference readers.
No commit, push or release publication in this batch. Full profile parity remains pending.

## 2026-09-16: Ford settings, data, seats/media and WC GM gauges

Implemented in Cabin Kotlin after inspecting client screens and July service `eb`/`h0`:

- Escape/Transit: fields 133–174, 42 options with exact command-10 selectors;
  sparse consumption units and discrete lighting timers. Profile route restricted
  to 917838/1114446. Read request 0/[40,0].
- Earlier Ford: profile-dependent settings, exact single/two/three-byte commands,
  paired ambient color/intensity preserving fresh companion values, and 25
  language values. Repeated French labels remain distinguished by wire value;
  no unsupported regional name is invented.
- Ford trip: packet 0x63 odometer/range/consumption/fuel and Transit packet 0x69
  average speed, maintenance distance, battery charge/voltage. Average speed
  never feeds the live driving gauge. Invalid reference values remain absent.
- Ford tire request corrected: stock client 0/[0] is ignored by service `eb`,
  whose command 0 requires two integers. Cabin requests packet 0x63 with
  0/[99,0]. That packet publishes both tire and basic trip values.
- Nine routed factory amplifier profiles: treble/mid/bass, front/rear and
  left/right balance, speed volume, sound mode, listening position, volume.
  Commands 7/8 preserve the reference's distinct volume encoding.
- Explorer 590158: independent left/right lumbar and massage modes/levels.
  Lumbar adjustment is an explicit 250 ms step, followed by command 11's
  release value 0. A new step or connection teardown releases the old step;
  delayed releases retain the original binder and cannot target a new profile.
  This is Cabin's step interaction, not a copied touch-hold interface.
- Factory radio/CD: source-qualified readings and commands for routed Mustang/
  Continental profiles; Navigator shares the CD screen but has a different
  radio panel, which is not conflated. String presets, correctly padded FM
  decimals, packed HH:MM:SS and transport controls. Stops are sent for momentary
  commands. Opening the data viewer does not switch the factory audio source.
  Source activation, preset storage and Navigator panel remain separate work.
- WC GM callback 0036: native information screen, scaled consumption/voltage/
  temperature, speed/RPM in the existing dashboard. No reuse of the RZC GM
  callback's IDs. Reference zero/unavailable gauge behavior is retained.
- Field lifetime/refresh: legacy IDs 89/90/149/151 are no longer universally
  excluded when a verified Ford decoder owns their non-motion meanings.
  Audi field 1 and WC GM fields 13/107 are excluded from cached refresh and
  their displayed motion values expire after five seconds.
- Native display contract now accepts fresh typed callback payloads, allowing
  semantic text readings without interpreting any vendor bytecode.

Local regression checks include protocol examples, invalid and missing values,
profile/source separation, grouped writes, Binder refresh, seat release timing,
suspend cleanup and Joying listener registration. Physical head-unit behavior
has not been verified. Full firmware parity remains incomplete.

WC GM settings now decode capability/value bytes separately for 28 comfort,
locking, remote-key and lighting controls (`KlcComfortAct`, `KlcLockAct`,
`KlcRemoteControlAct`, `KlcLightAct`, `KlcFunc`, service `h0`). Only enabled,
valid, fresh settings expose commands. Remote-lock feedback value 3 is Off;
walk-away locking and forgotten-key alert use different selectors 10 and 9.
Remote-start here is an enable preference, not a command to start the engine.
Lane-assist's contradictory source button commands (4/[9,v] versus 3/[8,v])
are not guessed; that separate mapping remains pending.

Tire-reference audit: `YLTireAct` command 8/[74] is not a valid read request
for July `h0`; its command 8 is a two-argument meter setting. Cabin does not
copy that call. The reference layout labels the unscaled pressure number
"Bar" while the service sends a raw 16-bit number; unit/scaling parity needs
further evidence before adding a physical-unit tire-history conversion.

### Integrated local verification

`/tmp/cabin-native-full-local-check.log`: `:app:testDebugUnitTest` and
`:app:assembleRelease` succeeded in 2m27s. 133 suites, 832 discovered tests:
831 passed, zero failures/errors, one intentionally opt-in protocol-export
fixture skipped. Release APK SHA-256:
`b4eb8aa91aad463b93201571e8e7f34ddff29e2125062da1269098dec7777fee`.
DEX class-definition audit: 5,624 definitions, zero runtime dexlib2 or moved
reference-reader classes. `eb.smali` command-0 dispatch was also checked:
`intsOk` receives 2, then forwards both arguments to request opcode 0x90.
This is local software validation only, not physical CAN/CarPlay verification.

### Additional native Honda contracts (2026-09-16)

This batch adds native capabilities on **49 additional exact profiles**, not full
Honda parity. Registry routing requires both the numeric profile and the verified
callback. All implementations are Kotlin; reference APKs/smali remain development
inputs only.

| Implementation | Profiles | Verified capabilities |
| --- | --- | --- |
| `CabinHondaLegacy` | 24, 47, 65560, 131119, 196655, 67, 76 | Factory USB/iPod source, transport, time, track counts/progress; XP compass zone/calibration; Civic WC speed |
| `CabinHondaAccord` | 41, 65577, 77, 65613, 131149, 196685, 262221 | Accord XP settings, screen controls, profile-specific confirmed reset actions, trip/history |
| `CabinHondaAccordWc` | 37, 131109, 42, 59, 65578, 131114 | WC high screen/color/camera; low settings, two languages, relative camera adjustments, supported TPMS reset and trip/history |
| `CabinHondaSpecialized` | 12452293, 12911044, 12976580, 13042116 | Eight XC Acura amplifier controls, signed feedback and request 1/[115] |
| `CabinHondaSpecialized` | 197033, 15729093, 15794629 | Spirior speed/RPM/odometer/seven light states; Civic 2006 odometer |
| `CabinHondaAccordXbs` | 262, 410 | Distinct low/high settings, screen, assistance preferences, confirmed reset actions and trip/history |
| `CabinHondaEarlyTrip` | 19, 64, 65, 117, 65653, 131189, 196725, 141, 166, 196774, 192, 65728, 203, 196811, 262347, 297, 65833, 370, 65906, 131442 | Current/previous consumption, range, A history/units, protocol-specific read and history reset commands |

Corrections verified against service code:

- WC Civic 67 publishes media at 1..7, with speed at 0. The shared stock CR-V
  activity's 11..17 IDs are wrong for Civic; those IDs belong to CR-V 76.
- Accord callback constant names describe unrelated locks/warnings; the native
  UI uses meanings from the packet reader plus the matching stock activity.
- Accord WC camera commands 11/12/13 accept relative steps (-1 up, -2 down),
  not absolute values. Only adjacent choices are exposed and current feedback
  is required. Brightness command 11 is implemented by o0 even though the
  stock activity leaves its brightness handler empty.
- XC Acura fields 81..85/88/89 are signed bytes. Field 87 is three bits.
  Field 89 is subwoofer, so it is eligible for non-motion cached refresh.
- XBS 410 beep volume is field 63. A stock decrement handler incorrectly reads
  field 59, which is beep enable. Native code uses 63 consistently.
- XBS service wd ignores one-argument command 100. Trip requests use 100/[8,1]
  and 100/[8,2]; r8 uses 100/[5,1] and 100/[5,2]. Settings/screen requests have
  their own packet selectors. The reset is 101/[3].
- XBS wd does not implement maintenance command 14 despite the stock button.
  Cabin does not expose that action on 410. r8 lacks callback fields 22/27/28;
  those unavailable stock settings do not appear as working controls.
- The early RZC services b4/l6/kc omit command 101. Their history reset uses
  100/[3], producing the same Honda 0x33/3 request as the XP reset path.

References: client `crv/{XpCrvActi,XpCompassActi,WcCrvActi}`,
`accord9/{xp,wc}`, `xbs/accord9`, `lz/spirior`,
`rzc/sanlin/{XCHondaAmpCarSet,LZHonda06CivicCarInfo}`, `honda/Honda*`;
service `w,t0,j1,s1,n0,t1,i0,o0,c1,r8,wd,cn,le,fo,r,h1,i1,b4,c5,l6,y9,kc`
and `module/canbus/{g,m}`. i0 command dispatch was checked directly in smali
because the Java decompiler incorrectly suggests camera-command fallthrough.

Still not implemented in this batch: remaining factory media systems, additional
Honda camera/climate/amplifier variants, and every remaining callback family.
No physical vehicle validation is implied by local tests or APK compilation.

Local validation of the 49-profile batch: `/tmp/cabin-honda-local-final.log`,
133 test suites, 841 passed, one intentional opt-in reference-export test skipped,
zero failures/errors. `assembleRelease` completed. APK SHA-256
`ae0fec583e4fa9945a04994b24ff91045a156ad9186fed30496a610c1ec6ecd4`;
5,630 class definitions; no runtime reference-reader/interpreter definitions.
