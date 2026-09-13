# OEM package applicability to Civic Brasil 2020 EXL

This review concerns original Honda/Mitsubishi Electric firmware only. It does not infer support from aftermarket integrations. No application or vehicle changes were made.

## What the original package actually identifies

The original `/home/bruno/Downloads/1.F197.60.zip` contains **40 model-specific `modelinf.xml` files** under `GroupNNN/NR-.../`. These were outside the previously extracted `1110/SwUpdate/system` tree and provide additional OEM configuration evidence.

- All 40 specify `CarModel=TBAA`, `AndroidAutoModel=TBA`, `CarPlayModel=HMCTBA`, and `AndroidAutoMake=Honda`.
- All 40 have `AndroidAutoYear=2016`. The separate `ModelYear` field is `0x16` for 25 configurations and `0x17` for 15. These are package configuration values, not proof of every supported vehicle model year.
- Market codes: KA (18), KC (12), KK (7), KX (3). Their country meanings are not decoded by this audit.
- Comments distinguish 23 four-door (`4DR`) and 17 two-door (`2DR`) configurations.
- No model record explicitly identifies `Civic`, `Brasil`, `Brazil`, `2020`, or `EXL` in the inspected identifying fields. The package uses internal identifiers; the Honda bulletin cross-check below links an included base part number to Civic.

**The package does not establish a 2020 Brazilian EXL match.** It does give concrete internal model codes, OEM part numbers, regional variants and equipment flags for comparison with an authoritative Civic manual/parts source or the original vehicle configuration. It would be incorrect to call this either a proven Brazil-2020 package or a verified list of every Civic option.

## Primary-source Civic identity cross-check

[American Honda Service Bulletin 16-100, March 14, 2017](https://static.nhtsa.gov/odi/tsbs/2017/SB-10108289-9340.pdf), page 1, identifies affected vehicles as 2017 Civic variants and lists failed part `39101-TBA-A31`. Two archive model records, `Group011/NR-000UH7CB21-T/modelinf.xml` and `Group013/NR-000UH7CB81-T/modelinf.xml`, contain `PartNumber=39101TBAA310M1`. Removing separators from the Honda base part number gives the same `39101TBAA31` prefix.

**Inference from the joined evidence:** the package includes a Civic audio-unit part family. This is materially stronger than recognizing the TBA code by memory. It does not prove that every package record applies to Civic, that 1.F197.60 is authorized for the Brazilian 2020 EXL, or that all 313 shared-software settings are Civic features. The bulletin itself concerns software loader 1.F194.30, so it verifies a part-family association rather than the provenance of this exact later package.

## Equipment configuration clues

These are raw OEM configuration fields. A value of 0x01/0x02/0x03 must retain its field-specific semantics; availability and UI behavior require the consuming code.

| Field | Values across the 40 configurations |
|---|---|
| TachoMeter_Setting | 0x01: 40 |
| LaneWatchSetting | 0x01: 40 |
| ParkingSensorSetting | 0x00: 40 |
| IsExt_HUD_Display | 0x00: 40 |
| IsExtBSICTM | 0x00: 40 |
| IsExtMvc | 0x00: 40 |
| IsExtFrontCamera | 0x00: 40 |
| IsExtRearWideCamera | 0x03: 40 |
| EngineType | 0x00: 40 |
| NaviSystem | 0x00: 23, 0x01: 17 |
| DefaultLanguage | 0x01: 33, 0x02: 7 |
| MetorColor | 0x02: 40 |
| TypeR | 0x00: 40 |

The tachometer setting flag exists in every record, while HUD, blind-spot/cross-traffic, multi-view camera and TypeR fields are zero in every record. This is stronger package-specific evidence than generic names in the 313-entry customization table, but it does not substitute for a verified 2020 EXL configuration. Generic settings for other equipment should not automatically be presented as Civic controls.

## Why the 313-entry XML cannot give a Civic-only count

Independently parsed `honda-cluster-analysis/vehicle_customize_config.xml`: **313 Item entries, 313 Setting containers, 0 nonempty Setting containers, 0 Model tags, 0 Destination tags, 0 Transmission tags.**

The parser has a model/market/transmission filter mechanism, but the supplied table does not populate it:

- `CustomizeSettingControl.smali:2713`, `init(model,destination,transmission)`, passes all three fields to `PullVehicleCustomizeConfigParser.parser` at line 2773.
- `PullVehicleCustomizeConfigParser.smali:735`, `parser`, opens `/system/vendor/etc/vehiclesetting/vehicle_customize_config.xml` at line 820.
- `PullVehicleCustomizeConfigParser.smali:2267` handles `Setting/Model`; it compares a configured attribute with the supplied model using `contains` at 2289.
- The same parser handles `Destination` at 2330 and `Transmission` at 2397. A matching nested setting writes `setDestinationFlg` at 2461.
- `VehicleCustomControlTableData.smali:40` defaults `mDestinationFlg` to true; its function flag starts false. With empty Setting containers, there is no per-Civic XML exclusion list to extract.

The stock runtime instead discovers applicable functions from vehicle replies:

- `VehicleInfoManagerApService$7.smali:9147`, `requestCustomizeCategory`, clears/discovers settings, requests B-CAN data at 9194 and calls `notifyCustomCheckStart` at 9201.
- `CustomizeSettingControl.smali:3214`, `setFunctionDirect(source,menus)`, enables matching entries only when the source/menu match and destination flag permits it.
- `CustomizeSettingControl.smali:3422`, `setFunctionIndirect`, handles indirect capabilities.
- `CustomizeSettingControl.smali:1205` and 1708 construct visible category/ID lists from enabled function flags.

Therefore all 313 XML entries remain an OEM software catalog across variants. Even the 167 nonempty XML routes are potential routes, not 167 Civic-supported options. A complete Civic-only inventory must join model-specific documentation/configuration with the discovered category/ID pairs, rather than use the XML entry count as a Civic feature count.

## Origin of model and market values

`VehicleInfoManagerApService$3.smali` gets model information at runtime from UnitInfoManager: SettingData type 7 is constructed near line 260, `getUnitInformation` is called at 278, and its value reaches `VehicleInfoControl.setModel` at 296. The failure fallback is `NOMODEL` at 308. Destination comes through SettingData type 4 near line 93 and `setDestinationUnit` at 151.

`VehicleInfoManagerApService.smali:4283` retrieves the model string, destination and transmission and passes them into customization initialization. The reviewed model selector is not a literal year/trim switch for "2020 EXL". No captured runtime values from this user’s original Honda unit were available in the reviewed artifacts.

## Complete package model inventory

All archive members below are inside `/home/bruno/Downloads/1.F197.60.zip`. Path is relative to the archive. Fields are transcribed from the XML, not translated into retail trims.

| Archive member | OEM part number | Market | ModelYear | Comment |
|---|---|---|---|---|
| 1.F197.60/Group001/NR-000UH6CB11-T/modelinf.xml | 39101TBHA420M1 | KA | 0x16 | Gr001 DA KA PR+XM 2DR #2 |
| 1.F197.60/Group001/NR-000UH7CB10-T/modelinf.xml | 39101TBHA510M1 | KA | 0x17 | Gr001 DA KA PR+XM 2DR #3 |
| 1.F197.60/Group002/NR-000UH6CB10-T/modelinf.xml | 39101TBFA410M1 | KA | 0x16 | Gr002 DA KA PR+XM 4DR |
| 1.F197.60/Group002/NR-000UH7CB12-T/modelinf.xml | 39101TBFA510M1 | KA | 0x17 | Gr002 DA KA PR+XM 4DR #2 |
| 1.F197.60/Group004/NR-000UH6CBA0-T/modelinf.xml | 39101TBCA720M1 | KA | 0x16 | Gr004 DA+ KA PR+XM 4DR |
| 1.F197.60/Group004/NR-000UH7CBA0-T/modelinf.xml | 39101TBCA910M1 | KA | 0x17 | Gr004 DA+ KA PR+XM 4DR #2 |
| 1.F197.60/Group006/NR-000UH6CBG0-T/modelinf.xml | 39101TBFC510M1 | KC | 0x16 | Gr006 DA+ KC PR+XM 4DR #1 |
| 1.F197.60/Group006/NR-000UH6CBG1-T/modelinf.xml | 39101TBCC820M1 | KC | 0x16 | Gr006 DA+ KC PR+XM 4DR #2 |
| 1.F197.60/Group006/NR-000UH7CBG0-T/modelinf.xml | 39101TBCC910M1 | KC | 0x17 | Gr006 DA+ KC PR+XM 4DR #4 |
| 1.F197.60/Group006/NR-000UH7CBG3-T/modelinf.xml | 39101TBFC610M1 | KC | 0x17 | Gr006 DA+ KC PR+XM 4DR #3 |
| 1.F197.60/Group007/NR-000YH6CB70-T/modelinf.xml | 39101TBHX310M1 | KX | 0x16 | Gr007 DA KX PR 2DR |
| 1.F197.60/Group008/NR-000YH6CB30-T/modelinf.xml | 39101TBFK410M1 | KK | 0x16 | Gr008 DA KK PR 4DR |
| 1.F197.60/Group009/NR-000YH6CB32-T/modelinf.xml | 39101TBJK110M1 | KK | 0x16 | Gr009 DA KK PR 2DR |
| 1.F197.60/Group010/NR-000YH6CBC0-T/modelinf.xml | 39101TBJK510M1 | KK | 0x16 | Gr010 DA+ KK PR 2DR |
| 1.F197.60/Group011/NR-000UH6CB20-T/modelinf.xml | 39101TBCA320M1 | KA | 0x16 | Gr011 DA KA NO+XM 4DR #1 |
| 1.F197.60/Group011/NR-000UH6CB21-T/modelinf.xml | 39101TBCA420M1 | KA | 0x16 | Gr011 DA KA NO+XM 4DR #2 |
| 1.F197.60/Group011/NR-000UH7CB20-T/modelinf.xml | 39101TBCA510M1 | KA | 0x17 | Gr011 DA KA NO+XM 4DR #3 |
| 1.F197.60/Group011/NR-000UH7CB21-T/modelinf.xml | 39101TBAA310M1 | KA | 0x17 | Gr011 DA KA NO+XM 4DR #4 |
| 1.F197.60/Group012/NR-000UH6CBB0-T/modelinf.xml | 39101TBCA620M1 | KA | 0x16 | Gr012 DA+ KA NO+XM 4DR |
| 1.F197.60/Group012/NR-000UH7CBB0-T/modelinf.xml | 39101TBCA810M1 | KA | 0x17 | Gr012 DA+ KA NO+XM 4DR #2 |
| 1.F197.60/Group013/NR-000UH6CB80-T/modelinf.xml | 39101TBAC120M1 | KC | 0x16 | Gr013 DA KC NO 4DR |
| 1.F197.60/Group013/NR-000UH6CB82-T/modelinf.xml | 39101TBAA220M1 | KA | 0x16 | Gr013 DA KA NO 4DR |
| 1.F197.60/Group013/NR-000UH7CB80-T/modelinf.xml | 39101TBAC210M1 | KC | 0x17 | Gr013 DA KC NO 4DR #4 |
| 1.F197.60/Group013/NR-000UH7CB81-T/modelinf.xml | 39101TBAA310M1 | KA | 0x17 | Gr013 DA KA NO 4DR #5 |
| 1.F197.60/Group013/NR-000YH6CB84-T/modelinf.xml | 39101TBCX110M1 | KX | 0x16 | Gr013 DA KX NO 4DR |
| 1.F197.60/Group014/NR-000UH6CB81-T/modelinf.xml | 39101TBGC120M1 | KC | 0x16 | Gr014 DA KC NO 2DR |
| 1.F197.60/Group014/NR-000UH7CB82-T/modelinf.xml | 39101TBGC210M1 | KC | 0x17 | Gr014 DA KC NO 2DR #2 |
| 1.F197.60/Group015/NR-000YH6CB42-T/modelinf.xml | 39101TBAK110M1 | KK | 0x16 | Gr015 DA KK NO 4DR #2 |
| 1.F197.60/Group015/NR-000YH6CB44-T/modelinf.xml | 39101TBCK110M1 | KK | 0x16 | Gr015 DA KK NO 4DR #1 |
| 1.F197.60/Group016/NR-000YH6CB43-T/modelinf.xml | 39101TBHK110M1 | KK | 0x16 | Gr016 DA KK NO 2DR |
| 1.F197.60/Group017/NR-000YH6CBK0-T/modelinf.xml | 39101TBCX810M1 | KX | 0x16 | Gr017 DA+ KX NO 4DR |
| 1.F197.60/Group020/NR-000UH6CB12-T/modelinf.xml | 39101TBJA410M1 | KA | 0x16 | Gr020 DA KA PR+XM 2DR |
| 1.F197.60/Group020/NR-000UH7CB11-T/modelinf.xml | 39101TBJA510M1 | KA | 0x17 | Gr020 DA KA PR+XM 2DR #2 |
| 1.F197.60/Group021/NR-000UH6CBA1-T/modelinf.xml | 39101TBHA720M1 | KA | 0x16 | Gr021 DA+ KA PR+XM 2DR |
| 1.F197.60/Group021/NR-000UH7CBA1-T/modelinf.xml | 39101TBHA810M1 | KA | 0x17 | Gr021 DA+ KA PR+XM 2DR #2 |
| 1.F197.60/Group022/NR-000UH6CBG2-T/modelinf.xml | 39101TBJC810M1 | KC | 0x16 | Gr022 DA+ KC PR+XM 2DR |
| 1.F197.60/Group022/NR-000UH7CBG1-T/modelinf.xml | 39101TBJC910M1 | KC | 0x17 | Gr022 DA+ KC PR+XM 2DR #2 |
| 1.F197.60/Group023/NR-000UH6CBG3-T/modelinf.xml | 39101TBHC820M1 | KC | 0x16 | Gr023 DA+ KC PR+XM 2DR |
| 1.F197.60/Group023/NR-000UH7CBG2-T/modelinf.xml | 39101TBHC910M1 | KC | 0x17 | Gr023 DA+ KC PR+XM 2DR #2 |
| 1.F197.60/Group024/NR-000YH6CBC1-T/modelinf.xml | 39101TBHK210M1 | KK | 0x16 | Gr024 DA+ KK PR 2DR |

## Concrete boundaries for a Civic-only report

- Confirmed from source: original Honda settings names, option mappings, service implementations and the package model records above.
- Confirmed user target: Brazilian Civic 2020 EXL. This is user context, not provenance of the supplied firmware.
- Civic part-family association is supported by the Honda bulletin above. Additional evidence is still needed for all TBAA records and their years/markets/trims, a Brazilian 2020 EXL equipment configuration, and the exact runtime-supported category/ID set.
- Exclude unproven cross-vehicle functions from the Civic-supported list. They can remain in a separately marked OEM-reference catalog without claiming applicability.

Source smali directory for line references: `/tmp/honda-inspect/deodex/VehicleInfoManager/com/mitsubishielectric/ada/appservice/vehicleinfomanager/`. Original archive inspected directly with Python zipfile; all 40 model XMLs were parsed and counted.
