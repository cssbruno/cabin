# Original Honda action implementation evidence

The honda-customizer action paths use the original OEM Binder interfaces. No raw diagnostic command API was added.

| Action | Request transaction | Matching callback result type | Completion boundary |
|---|---|---|---|
| Vehicle customization defaults | 0x50, no payload | 0 | OEM acknowledgement plus complete category/value reload; known-default equality is not asserted. |
| TPMS calibration | 0x54, no payload | 1 | OEM acknowledgement plus TPMS-category read; drive-cycle completion is not asserted. |
| Maintenance reset | 0x56: type/item pair | 2 | OEM acknowledgement plus re-subscribed maintenance status; physical service completion is not asserted. |

`IVehicleInfoManagerApService$Stub.smali` declares these transaction constants; `VehicleInfoManagerApService.smali` callback emitters `sendRequestCustomizeReset` (line 5363), `sendTPMSNotifyCustomizeResult` (5507), and `sendMaintenanceNotifyCustomizeResult` (5196) prove the result types. Result zero is successful acknowledgement. Customize-mode transaction is 0x4f; maintenance-reset mode is 0x55. Maintenance status registration/unregistration is 0x16/0x17, callback `IVehicleMaintenanceInformationListener` transaction 1 carries int[].

Maintenance item selection is reproduced from saved `action-source/MaintenanceInfoActivity.smali`: `getResetItemArray` line969 selects US labels for status[2]=1, EU/HEV for2 or6, turbo labels for7. Due flags start at6; individual supported-reset flags start at22; flags must equal1 and resource labels must be nonempty. `clickListItem` string prefixes A/B map10/11 and digits0..9 preserve integer codes (around2097–2354). `onNotifyUpdate` lines2801/2834 sends `(0,0)` for all due or `(1,item)` individually. Exact label arrays and default strings are saved in `action-source/maintenance-resources.txt`. No feature is inferred for Civic unless the runtime status advertises it.

System-menu tachometer differs from generic vehicle customization: `SystemSettingActivity.requestTachometerSettingChange` (saved system-source line2241) first persists the head-unit preference, then changes03/5C. Wrapper `setTachometer` writes UnitInfo type0x21; `UnitInfoManagerConst` lines4731–4743 maps OFF0/ON1, whereas vehicle OFF2/ON1. UnitInfo transactions set0x1d/get0x1e accept SettingData: type, Parcel.writeValue(Integer), null list, null int array. Getter returns status then an in/out SettingData presence flag and object. The new explicit `changeSystemTachometer` preserves that order and adds preference readback; generic `change` keeps the generic vehicle contract. Failures after preference attempts explicitly disclose partial/unknown outcome, with no assumed rollback.

All action APIs require the current ready connection and declared parked state. TPMS additionally requires live01/01 type2. Maintenance choices come only from current status. Defaults require discovered ordinary customization settings. Operations serialize Binder work, use a20-second timeout, discard callbacks after session invalidation, and attempt mode/subscription/binding cleanup. The stock service does not provide request correlation IDs; parallel stock-client operations are not independently attributable to this app.
