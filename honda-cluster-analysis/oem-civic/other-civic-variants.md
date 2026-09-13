# Additional tenth-generation Civic variants: OEM evidence

Scope: 2017 U.S. Civic Sedan and 2020 Civic Sedan/Si/Type R. No other Honda model or eleventh-generation setting is included. None of these findings establishes equipment on the Brazilian 2020 EXL.

## Primary-source findings

- **2017 Civic Sedan:** the [Honda owner’s manual, printed p128](https://techinfo.honda.com/rjanisis/pubs/OM/AH/ATBA1717OM/enu/ATBA1717OM.PDF) documents a three-language cluster selector (English, French, Spanish), speed/distance unit selection (mph/miles or km/h/km), and tachometer visibility. This verifies the existence of those Civic options; it does not specify software IDs.
- **2020 Civic Type R/shared hatchback customization:** Honda’s [Type R customization extract, PDF page 8 of 9](https://owners.honda.com/utility/download?path=%2Fstatic%2Fpdfs%2F2020%2FCivic+Type+R%2F2020_Civic_Type_R_Customized_Settings.pdf) includes rev matching, shift lighting, shift alarm, gear display, and preferences for collision-warning distance, ACC detection sound, LKAS suspension sound, and road-departure behavior. Several entries carry an equipment-dependent footnote; the extract is not a blanket claim that every shown feature is on every Type R.
- The [2020 Civic hatchback Honda manual, printed pp369–370](https://techinfo.honda.com/rjanisis/pubs/OM/AH/ATGG2020OM/enu/ATGG2020OM.PDF) gives On/Off choices for the shift light, shift alarm and gear display. Page 119 explains the shift alerts; the feature table marks them as equipment dependent.
- **2020 Civic Si:** the [Honda Sedan manual, printed p359](https://techinfo.honda.com/rjanisis/pubs/OM/AH/ATBA2020OM/enu/ATBA2020OM.PDF) documents equipment-dependent shift-light and shift-alarm toggles. Honda’s [2020 Si-specific instrument-display guide](https://www.hondainfocenter.com/2020/Civic-Si-Sedan/Feature-Guide/Interior-Features/Driver-Information-Interface/) independently confirms its rev indicator, G-meter and lap timer. These display features are not automatically separate CAN customization settings.
- Honda separately identifies [rev-match control on the 2020 Type R](https://www.hondainfocenter.com/2020/Civic-Type-R/Feature-Guide/Engine-Chassis-Features/Rev-Match-Control/). This review does not assign automatic rev matching to the 2020 Si.
- Honda’s U.S. guides confirm the 2020 [Si sensing suite](https://www.hondainfocenter.com/2020/Civic-Si-Sedan/Feature-Guide/Safety-Features/Honda-Sensing/) and [Type R sensing suite](https://www.hondainfocenter.com/2020/Civic-Type-R/Feature-Guide/Safety-Features/Honda-Sensing/). Regional applicability remains separate.

## OEM firmware candidates

These matches join **manual feature names to locally recovered OEM labels**, not validated commands for the cited vehicle. All numbers are hexadecimal category/setting IDs from `../vehicle_customize_config.xml`. Route means the original Honda service’s source/menu fields. The manuals do not establish these IDs, packet formats, or readback behavior.

| Feature | Candidate category/ID | XML line | Stock route | Evidence boundary |
|---|---|---:|---|---|
| U.S. cluster language | 03/09 | 1564 | 50/02 | Named UI options match the 2017 manual |
| Speed/distance units | 03/57 | 3255 | 50/2F | Combined-unit label matches; do not substitute digital-speed entry 03/25 |
| Tachometer visibility | 03/5C | 3371 | 50/2E | Dedicated stock UI/service path exists |
| Shift-up lighting | 03/23 | 2165 | 50/11 | Stock resource label is Shift Up Backlight |
| Shift-up alarm | **Unresolved** | — | — | No matching entry identified in the supplied 313-item XML; alarm volume is a different control |
| Manual gear display | 03/52 | 3144 | 50/36 | Stock resource label is Gear Position Display |
| Rev matching | 02/44 | 1381 | 00/0E | Stock explicit setter exists; Type R candidate |
| Collision-warning distance | 02/04 | 81 | 00/08 | Stock UI has four choices; cited vehicles may expose a subset |
| ACC detection sound | 02/0A | 221 | 00/01 | Stock named setting and explicit setter |
| LKAS suspension sound | 02/36 | 1084 | 00/04 | Stock named setting and explicit setter |
| Road-departure preference | 02/3A | 1169 | 00/0C | Stock named setting and explicit setter |

Local label evidence is in `/home/bruno/Documentos/ChatGPT/R/honda-customizer/app/src/main/assets/catalog.json`, extracted from OEM `VehicleSettingDataControl` resources. The original service’s explicit setters are in `honda-cluster-analysis/decompiled/VehicleInfoManager/com/mitsubishielectric/ada/appservice/vehicleinfomanager/VehicleInfoManagerApService$7.smali`: FCW at 10082, ACC at 9965, LKAS at 10154, RDM at 10180, rev match at 10232. This report adds no app controls and makes no vehicle writes.

Some large full manuals could not be opened as complete PDFs by the browsing tool; their page-specific text was verified through the indexed content from the exact Honda-hosted PDF URLs. The nine-page Type R extract opened directly. No third-party manual text was used for these conclusions.
