# Stock Honda language settings review

Static audit of supplied Honda 1.F197.60 firmware; no vehicle connection, FYT command changes, or application edits.

## Result

**15 language-related XML entries: 8 have nonempty stock routes and 7 do not.** Ten entries map to named option arrays in the stock Settings UI; five fall through to its "No Data" case. These are regional/vehicle variants, not 15 languages or 15 supported controls on one Civic.

**South American Portuguese is specifically implemented:** category 0x03, setting 0x0E, stock source 0x50/menu 0x23. API value 1 means Português and encodes as 0x00; API value 2 means English and encodes as 0x01. The ordering is established by the Settings bytecode and resource array, not inferred from the numeric value.

## Complete language inventory

All setting IDs below belong to category 0x03. Route means XML InfoFrom source/menu, not an OBD PID or FYT command. UI source lines refer to `VehicleSettingDataControl.smali` in the source directory listed below.

| ID | XML description | XML line | Stock route | UI block line | Option array | Named choices / XML values |
|---|---|---:|---|---:|---|---:|
| 0x09 | 言語設定 (US) | 1564 | 0x50/0x02 | 4663 | 0x7f0500a0 | 3 / 3 |
| 0x0A | 言語設定 (US) | 1587 | None | 4693 | 0x7f0500a1 | 3 / 3 |
| 0x0B | ﾒｰﾀｰ表示言語切り替え | 1608 | None | 4497 | No Data | 0 / 0 |
| 0x0C | 言語設定 (中国) | 1622 | 0x50/0x03 | 4723 | 0x7f0500a2 | 2 / 2 |
| 0x0D | 言語設定 (EU) 6ヶ国語 | 1644 | 0x50/0x04 | 4753 | 0x7f0500a3 | 6 / 7 |
| 0x0E | 言語設定（南米ポルトガル語） | 1671 | 0x50/0x23 | 4783 | 0x7f0500a4 | 2 / 2 |
| 0x0F | 言語設定 (EU) 12ヶ国語 | 1693 | 0x50/0x1F | 4813 | 0x7f0500a5 | 12 / 13 |
| 0x10 | 言語設定 (EU) 13ヶ国語 | 1726 | 0x50/0x24 | 4843 | 0x7f0500a6 | 13 / 14 |
| 0x11 | 言語設定 (ロシア) | 1760 | 0x50/0x22 | 4873 | 0x7f0500a7 | 2 / 2 |
| 0x3C | 言語設定 (アラビア語) | 2702 | 0x50/0x21 | 5817 | 0x7f0500c9 | 2 / 2 |
| 0x4C | DISPLY表示言語切り替え | 3016 | None | 4497 | No Data | 0 / 3 |
| 0x4D | DISPLY表示言語切り替え | 3037 | None | 4497 | No Data | 0 / 2 |
| 0x4E | DISPLY表示言語切り替え | 3057 | None | 4497 | No Data | 0 / 2 |
| 0x4F | DISPLY表示言語切り替え | 3077 | None | 4497 | No Data | 0 / 13 |
| 0x5F | DISPLY表示言語切り替え | 3433 | None | 6369 | 0x7f0500df | 13 / 20 |

## Verified option labels

The numbered positions below are **API values beginning at 1**. For all named options in this list, the XML encoded value is API value minus one. Labels are the default resource strings, retained as supplied. Additional XML values without a resource label are explicitly called out; their meaning is not inferred.

- **03/09:** 1 = English; 2 = Français; 3 = Español.
- **03/0A:** 1 = English; 2 = Français; 3 = Español.
- **03/0C:** 1 = 中文; 2 = English.
- **03/0D:** 1 = English; 2 = Français; 3 = Español; 4 = German; 5 = Italian; 6 = Português.
  Unlabelled XML values: 7 → 0xFF.
- **03/0E:** 1 = Português; 2 = English.
- **03/0F:** 1 = English; 2 = German; 3 = Italian; 4 = Français; 5 = Español; 6 = Português; 7 = Dutch; 8 = Danish; 9 = Swedish; 10 = Norwegian; 11 = Finnish; 12 = русский.
  Unlabelled XML values: 13 → 0xFF.
- **03/10:** 1 = English; 2 = German; 3 = Italian; 4 = Français; 5 = Español; 6 = Português; 7 = Dutch; 8 = Danish; 9 = Swedish; 10 = Norwegian; 11 = Finnish; 12 = русский; 13 = Polish.
  Unlabelled XML values: 14 → 0xFF.
- **03/11:** 1 = русский; 2 = English.
- **03/3C:** 1 = العربية; 2 = English.
- **03/5F:** 1 = English; 2 = Deutsch; 3 = Italiano; 4 = Français; 5 = Español; 6 = Português; 7 = Nederlands; 8 = Dansk; 9 = Svenska; 10 = Norsk; 11 = Suomi; 12 = русский; 13 = Polski.
  Unlabelled XML values: 14 → 0x0D, 15 → 0x0E, 16 → 0x0F, 17 → 0x10, 18 → 0x11, 19 → 0x12, 20 → 0x13.

The Portuguese resource configuration contains some explicitly empty labels: 03/0D choices 4–5 and multiple 03/5F choices. A client should use verified fallback strings rather than present blanks. For 03/0E both default and pt resource configurations contain Português, English in that order.

## Evidence chain for the Portuguese setting

1. `vehicle_customize_config.xml:1671` declares category 03 / id0E, DataList `1→0, 2→1`, and InfoFrom `0x50→0x23`.
2. `VehicleSettingDataControl.smali:4487`, `setMeterVehicleSettingData(Resources,int,int,VehicleSettingData)`, dispatches id0E through the packed-switch to `:pswitch_100` at line 4783. It passes title resource `0x7f0802c7`, description `0x7f08054a`, and option array `0x7f0500a4` to `VehicleSettingData.setData`.
3. `DaSettings-resource-values.txt:3052` names that array `MM_07_06_03_14`: position 0 references `0x7f0802c8`, position 1 references `0x7f0802c9`. At lines 8108–8111 those resolve to Português and English.
4. `VehicleSettingData.smali:226`, `setData`, reads the string array and converts enum API values to UI positions with `add-int/lit8 v3, p5, -0x1`. Thus API 1 is array position 0; API 2 is position 1.
5. `VehicleCommonSetupActivity.smali:2834` performs the reverse conversion, selected index + 1, calls `CustomizeSettingData.setValue` and dispatches `requestCustomizeSettingChange` at 2851.
6. The stock service write path is `honda-cluster-analysis/decompiled/VehicleInfoManager/com/mitsubishielectric/ada/appservice/vehicleinfomanager/VehicleInfoManagerApService$7.smali:9555`. It resolves runtime route availability, encodes DataList values and calls `notifyBcanCustomFrame`. Readback and runtime discovery remain necessary.

## Head-unit language versus vehicle language

`ChangeLanguageActivity` also contains a separate head-unit language UI. It explicitly maps `LANGUAGE_BRAZILIAN_PORTUGUES` to locale `pt_BR` at lines 1341–1354. Its apply path calls `CommonUnitInformationManagerWrapper.setLanguage` at line 2443. The wrapper forwards through UnitInfoManager; that integer namespace must not be equated with category03/id0E API values. This review did not trace UnitInfoManager internals to prove whether that separate operation propagates to the instrument panel.

The vehicle-setting path above is independently backed by `CustomizeSettingData` and VehicleInfoManager. A claim that changing Android language alone changes the cluster would go beyond this evidence.

## Source locations and reproducibility

- XML: `/home/bruno/Documentos/ChatGPT/R/honda-cluster-analysis/vehicle_customize_config.xml`.
- Full decompiled Settings classes: `/tmp/honda-inspect/deodex/DaSettings/com/mitsubishielectric/ada/app/dasettings/`.
- Resource dump: `/tmp/honda-inspect/DaSettings-resource-values.txt`.
- Original Settings APK: `/tmp/honda-inspect/extracted/system/vendor/app/DaSettings.apk`.
- Existing extracted-label catalog cross-checked against the above: `/home/bruno/Documentos/ChatGPT/R/honda-customizer/app/src/main/assets/catalog.json`; parser: `honda-customizer/tools/` in the workspace.

No stock source/menu, API value or Binder transaction is substituted into an FYT command. The Portuguese stock mapping is verified statically; vehicle compatibility and an FYT equivalent remain separate questions.
