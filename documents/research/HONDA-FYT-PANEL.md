# Honda instrument panel through FYT

Cabin Car Settings now includes a Honda instrument-panel section when the connected SYU profile is supported. It uses the existing SYU module 7 controller, subscriptions, freshness checks and parked-action UI guard. Values change on vendor feedback rather than on a successful send. Profile changes invalidate the controller connection and old values.

## Source and profile selection

Inspected the local Joying UIS7862 2023-08-31 CANBUS APK, class `com.syu.carinfo.honda.Wc_16Civic_Pannel`, its listeners `$1` through `$21`, layout `layout_321_civic_pannel` and `Callback_0321_WC2_Honda_AllCom`. Saved bytecode, decoded layout and APK SHA-256 are in `../honda-cluster-analysis/fyt/` relative to the Cabin repository root.

`onResume` explicitly compares profiles 0x40141, 0x50141, 0x60141, 0xB0141, 0xC0141 and 0xD0141. These are the implemented allowlist, not a comprehensive list of compatible Civics. Additional profiles require source evidence. Those six IDs apply to the WC panel dialect. Additional RZC/BNR profiles are listed below. Cabin reads the actual profile via field 1000; selecting a year or trim does not enable controls.

## Implemented commands

All rows use SYU command 106 with integers `[key, wireValue]` and null float/string arrays. These identifiers are unrelated to Honda Binder transaction numbers or generic OBD codes.

| Control | Callback field | Key | Wire values | Profile visibility |
|---|---:|---:|---|---|
| Navigation directions | 109 | 16 | 0–1 | 0x40141, 0xC0141, 0xD0141 |
| Warning messages | 110 | 15 | 0–1 | same three |
| Panel configuration | 111 | 14 | 0–2 (display Type 1–3) | same three |
| Reverse tone | 88 | 9 | 0–1 | 0x50141, 0x60141, 0xB0141 |
| Speed reminders | 65 | 6 | 0–1 | all except 0x50141/0x60141 |
| Message reminders | 66 | 7 | 0–1 | all except 0x50141/0x60141 |
| Idle-stop reminders | 67 | 8 | 0–1 | all six |
| Eco background lighting | 68 | 5 | 0–1 | all six |
| Traffic sign display | 102 | 13 | 0–1 | 0xB0141 |
| Alarm volume | 69 | 4 | 1 high, 2 medium, 3 low | all six |
| Trip B reset condition | 70 | 3 | 1 refuel, 2 ignition off, 3 manual | all six |
| Trip A reset condition | 71 | 2 | 1 refuel, 2 ignition off, 3 manual | all six |
| Outside-temperature adjustment | 72 | 1 | 1–7, displayed −3 through +3 | all six |

The source masks setting feedback to its low byte. Zero means invalid for the last four fields; Cabin does not expose zero as a selectable wire value. Unknown, missing, expired and out-of-range values remain unavailable. UI values for the last four are normalized to zero-based indices and translated back on write. The actual meanings of panel Type 1–3 were not established, so they retain neutral labels. Temperature adjustment follows the vendor UI’s numeric offsets without assuming a temperature unit.

## Remaining scope

This is a source-backed FYT client implementation, not confirmation on a Brazilian 2020 EXL or all Civic variants. Wallpaper, opening animation and ECU diagnostic operations have no mapped FYT controls in this implementation. Units and tachometer settings use the separate RZC/BNR mappings below. The stock Honda catalog remains reference evidence; its IDs are not substituted into FYT commands. The source also includes a language picker, which has not been ported.

No firmware was flashed and no vehicle messages were sent during development. Hardware validation is outstanding.

## Validation

`testDebugUnitTest` passed 42 focused tests across `SyuHondaPanelProtocolTest`, `SyuFactoryProtocolTest`, `TeyesClimateBinderIntegrationTest` and `CarSettingsScreenTest`. Coverage includes profile visibility, command keys, invalid/missing feedback, one-based values, actual Binder dispatch, disconnect rejection, parked UI actions and waiting for returned values. `assembleDebug` succeeded. Output: `app/build/outputs/apk/debug/app-debug.apk`.

Build used the installed Android SDK and JBR with the repository's documented JIT workaround. Existing unrelated workspace changes were retained. These tests do not establish in-car compatibility.

## Integrated RZC / BNR settings

The mapped controls are available in Cabin → Car Settings → Honda instrument panel through Cabin’s shared FYT connection.

| Profiles | Controls | Command / keys | Feedback |
|---|---|---|---|
| RZC Civic `0x10012a`, `0x11012a`, `0x29012a` | Speed/distance units; tachometer display; separate vendor tachometer option | 105 / 21, 22, 35, with values 0–1 | 77, 78, 87 |
| BNR `0x6012a`, `0x7012a`, `0x8012a`, `0x9012a`, `0xa012a`, `0xb012a`, `0xf012a`, `0x28012a` | Both tachometer options | 105 / 22, 35, with values 0–1 | 78, 87 |

BNR units are excluded because the stock entry route hides them. RZC/BNR feedback uses the full integer, never WC low-byte decoding. Unmapped profiles and invalid/missing/expired values do not authorize writes. The second tachometer option keeps the vendor wording because its distinction from display visibility remains unresolved. Metric/imperial choices display units rather than On/Off. All six Cabin languages include the new control labels.

The integrated vehicle-compatibility panel includes Honda support and feedback status; existing Cabin report export and live logs carry the actual profile and FYT service connection. Source evidence: `../../honda-cluster-analysis/fyt-additional/other-honda/findings.md` from the workspace root and its saved `HondaIndexActi`, `FinalCanbus`, `AcrivitySiYuSettings` and `Acrivity_RZC_17CRVSettings` bytecode. Hardware validation remains outstanding.
