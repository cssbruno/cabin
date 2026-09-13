# OEM Honda Civic instrument settings: code audit

Scope: original Honda/Mitsubishi Electric software only. No FYT implementation is used as evidence. No vehicle commands or app edits were performed.

**The earlier XML-only inventory missed a separate working instrument-panel editor.** The OEM Settings app launches `externaldisplay.CustomizeMeter`, which implements adding/removing/reordering display icons and saving ordered content to a preset. A separate preset screen actually uses category **03 / ID 5B**. Details and exact source anchors are in [instrument-panel editor trace](instrument-panel-editor.md).

## What is actually established for Civic

The parent audit's official Civic Brasil 2020 manual review identifies add/delete/reorder icons, three configurations, and the RPM display toggle. The OEM code contains corresponding editor and tachometer paths. This links documented Civic features to concrete OEM implementation candidates; it does not establish which of the 95 regional/platform catalog variants the user's vehicle advertises at runtime.

The firmware catalog contains **95 meter entries**, **46 with an XML route**, and **74 with an explicit generic Settings UI branch**. The remaining **21** fall through to the generic “No Data” branch. These counts describe this shared firmware, not 95 supported Civic controls. “No generic UI” is not “unimplemented anywhere”: **03/5B is implemented in the separate preset UI**.

Complete coverage, without selective omissions:

- [All 95 entries, names, XML routes and exact UI-source lines](instrument-inventory.md).
- [Machine-readable 95-entry inventory](instrument-evidence/meter-ui-inventory.json): original Japanese descriptions, enum encodings, default/Portuguese labels, generic UI branches, route metadata.
- [Original 95-entry XML map](../meter-settings-catalog.md) and [OEM language options](../stock-language-review.md).

## Requested display features

All identifiers below are category **03**. Source/menu pairs are OEM internal routes, not CAN arbitration IDs or OBD codes. Numbered choices are API values, beginning at 1.

| Feature | ID | Proven OEM choices/path | XML source/menu |
|---|---|---|---|
| South American language | 0E | 1 Português → encoded0; 2 English → encoded1 | 50/23 |
| Speed display unit | 36 | 1 km/h →0; 2 mph →1 | 50/1D |
| Combined speed/distance | 57 | 1 km/h・km →0; 2 mph・miles →1 | 50/2F |
| Digital speedometer automatic units | 25 | Auto, km/h, mph; distinct catalog setting | none |
| HUD speed units | 3A | km/h, mph; HUD-specific catalog setting | none |
| Fuel-efficiency backlight change | 28 | On/Off; generic UI explicitly labels fuel-efficiency backlight | 50/16 |
| Backlight color, no white | 29 | Blue, Violet, Pink, Red, Amber, Yellow, Random | 50/32 |
| Backlight color, with white | 2A | White, Blue, Violet, Pink, Red, Amber, Yellow, Random | 50/33 |
| Tachometer display | 5C | API1 On →0; API2 Off →1; generic and System Settings UI paths | 50/2E |
| Panel configuration/preset | 5B | Actual preset selector in ExternalDisplayOutService; configurations 1–3 | 50/15 |
| Navigation in meter | 38 | Turn By Turn Auto Display, On/Off | 50/20 |
| Wallpaper select/delete | 41 / 43 | CLOCK or IMAGE1–3; delete IMAGE1–3 | none |
| Image load | 42 | XML entry, generic UI “No Data” | none |
| Opening animation | 45 | On/Off UI branch | none |
| Background color | 46 | BLUE, RED, AMBER, GRAY UI branch | none |
| Display contents / favorites XML placeholders | 3D / 3E | generic UI “No Data”; actual content editor uses another service path | none |

Wallpaper/opening/background catalog entries are **not proven Civic features by their presence**. Conversely, their empty XML routes cannot establish absence of a separate implementation elsewhere. The confirmed separate content editor is a concrete example of why catalog-only negatives are insufficient.

## Additional instrument settings found

The full appendix includes speed alarms 1 and 2 with thresholds; outside-temperature correction (Celsius and Fahrenheit variants); fuel/charge-linked and ignition/manual Trip A/B resets; elapsed-time reset behavior; drive-computer selections; alarm volume; welcome lights and scroll timing; shift-up backlight and ECO indicator; stopwatch; ECON retention; keyless-start guidance; idle-stop guidance; turn-by-turn variants; content-change sounds; new message notifications; traffic-sign recognition display; gear indicators; reverse alert tone; tire-angle monitor; and automatic interior dimming.

These are firmware capabilities/candidates, not a Civic feature checklist. XML comments explicitly distinguish PHEV/non-PHEV, HUD-equipped, power-meter/charging, and regional language variants. This audit does not assign those branches to Civic trims without an external model source or runtime capability data. A Portuguese resource translation is not a trim-support flag.

## Actual OEM UI/service flow

`VehicleSettingActivity` receives discovered categories (`onNotifyCustomizeCategory`) and starts `VehicleCommonSetupActivity` with the selected `category` extra (saved source line1026). The common screen requests returned values for that category, then `VehicleSettingDataControl.getDataList` filters the returned items by category and builds their UI rows. Category3 dispatches to `setMeterVehicleSettingData` (line319; method4487). Enum UI indexes correspond to API value minus1; common-screen apply adds1 and calls `requestCustomizeSettingChange` (common screen2834–2851).

The UI does **not** simply show every XML record. `getDataList` requires non-null names, requires enum arrays for type0, and validates returned ranges (saved source17–210). A mapped branch can therefore still be unavailable. `CustomizeSettingControl.getCategory` (existing service source1205) checks runtime function flags. The common Settings callback processes result and refreshes values; API return alone is not an acknowledgement.

SystemSettingActivity has an additional tachometer flow: `requestMeterSetting` requests discovery (2197), and `requestTachometerSettingChange` constructs category3/id5C (2241), calls the UnitInfo wrapper and OEM vehicle service. Availability and driving-operation status are checked. Its callback and readback were already preserved in the prior OEM review.

## Corrections and unresolved inconsistencies

- **03/5B:** it lacks a generic meter-row branch but is implemented by `CustomizeChangePreset`. Do not label it missing UI globally.
- **03/26, 03/51 and 03/5A:** XML routes exist but no generic meter UI branch; another application path has not been established here.
- **03/56:** the XML comment says distance display units, but its UI title/options say “Display Speed Unit”, km/h/mph. Preserve this disagreement; do not invent distance-only semantics.
- **03/2A:** API1 White and API8 Random both encode to7 in the supplied XML. This is a real source collision; do not silently repair it or claim unambiguous white write/readback.
- **03/1F and 03/27:** XML has three enumerated values, while the matching UI arrays contain only two labels. Regional language arrays also contain sentinel/unlabelled values; see the language audit.
- Some Portuguese titles/options are explicitly empty in the source. They cannot be presented as verified translated labels.

The source package lacks a simple inspected “Civic Brasil 2020 EXL” allowlist for these 95 IDs. The vehicle reports supported categories, IDs and content availability. No stock code branch was assigned to a Honda model from a suggestive internal acronym alone.

Evidence includes saved OEM UI classes in `instrument-evidence`, original-resource hashes in `instrument-evidence/source.json`, and the newly deodexed external-display customization code. Validation was static cross-checking of XML, switch tables, original resources and concrete method calls; no hardware test was performed.
