# Original Honda vehicle settings and service actions — Civic applicability audit

**OEM Honda firmware only. No FYT/TEYES/Joying mappings are used here.** Scope is the supplied Honda/Mitsubishi Electric 1.F197.60 code, original `vehicle_customize_config.xml`, `VehicleInfoManagerApService` Binder implementation and `CpuComService` interface/selected implementation paths. No app edits, vehicle connection or command execution.

## What the original code actually contains

- **218 non-meter XML entries**, of which **121 have route metadata** and **97 do not**. The existing meter category contains95 entries; together these are the313-entry XML. Variants and duplicate feature labels remain separate category/ID identities.
- **103 exposed methods** in the actual `VehicleInfoManagerApService$7` Binder implementation, fully enumerated and classified below. These include reads, subscriptions, writes, mode changes and one-shot actions. They are not103 customizable vehicle settings.
- **237 signatures** in the lower-level `ICpuComService` interface, exhaustively saved in `cpu-service-api-signatures.json`. This is a broad OEM head-unit/system-controller API, including audio/radio/device functions; only vehicle-customization and diagnostic-related paths discussed below were behaviorally inspected.
- Dedicated **maintenance reset, maintenance-reset mode, TPMS calibration and customization reset APIs** exist. DTC read/clear is a separate lower-level diagnostic interface. Thus313 settings is not the entire OEM service surface.

## Civic applicability: evidence versus unknown

The supplied XML has no year, trim, country or Civic identity attached to individual entries. Searching the reviewed VehicleInfoManager code and XML for Civic/EXL/Brasil/Brazil did not establish a Civic2020EXL allowlist. A class or method existing in this shared firmware is therefore **not evidence that the user's Civic supports it**.

The positive evidence is dynamic: `requestCustomizeCategory()` clears function flags/source discovery, requests B-CAN data, and starts `notifyCustomCheckStart()`. Category/setting support then comes from vehicle responses. `CustomizeSettingControl.getInfoFrom(category,id)` chooses an available source; missing routes cannot be filled by guessing. `Function=false` is the initial discovery state, not a permanent unsupported flag.

No powered-tailgate, sliding-door, rear-wiper, seat-memory, steering-entry, HUD or IDS feature is classified here as Civic-supported merely because its shared-firmware entry exists. Those categories are retained for completeness with applicability **unestablished**. Equipment-specific entries must be filtered by actual OEM capability/readback or a separate Civic-specific source. Even familiar Civic-relevant feature groups below are research candidates, not hardware confirmations.

## Non-meter customization groups

The exact218 entries, Japanese original comments, category/ID, XML line, route metadata and encoded-value maps are preserved in `non-meter-catalog-inventory.json`. It is a filtered copy of the original XML-derived catalog, with explicit unknown-Civic applicability on every row. Existing English titles come from the earlier local client catalog and are not represented as new OEM English strings.

| Category | Entries / with route | Original feature groups | Civic evidence |
|---|---:|---|---|
| 01 | 1 / 1 | TPMS calibration | Dedicated action/API plus XML01/01; runtime applicability unverified |
| 02 | 68 / 19 | Driver-assistance behaviors: collision warning, ACC/LKAS, sign recognition, RDM, other assist variants | OEM capability discovery required; no EXL-specific support proof |
| 04 | 6 / 3 | Driving-position linkage, entry/exit seat/steering movement, seat-belt tension | Equipment-specific; no Civic inference |
| 05 | 28 / 12 | Smart/keyless access, remote start, unlock modes, acknowledgement beeps/lights; also shared PTG/PTL variants | Filter dynamically; no remote-start or powered-tailgate claim for Civic |
| 06 | 22 / 13 | Interior/headlight timing, light sensitivity, wiper/headlight linkage, accessory/welcome variants | Candidate common vehicle settings; no per-Civic values proven |
| 07 | 55 / 38 | Door locking/unlocking, security, windows/sunroof, walk-away locking, folding mirrors; also sliding-door variants | Candidate door/window groups; equipment-specific entries remain unknown |
| 08 | 34 / 31 | Wiper behavior, washer interlocks, startup/reverse linkage, wiper/headlight linkage | Includes rear-wiper variants; no Civic applicability assumed |
| 0A | 3 / 3 | Powered-tailgate hands-free/keyless/outer-handle settings | Shared-firmware metadata only; not established for Civic |
| 0B | 1 / 1 | Preferred IDS mode | Shared-firmware metadata only; not established for Civic |

Representative exact OEM candidates, all requiring runtime support:

| Feature | Category/ID(s) with route evidence | Example source/menu; decimal inside route |
|---|---|---|
| Keyless-access unlock mode | 05/15,05/16 | 144/2 or24/41 |
| Keyless beep volume / beep / light flash | 05/17–1C | 144/6–8 or24/43–45 |
| Interior-light dimming | 06/04 | source16,19,24 / menu6 |
| Headlight-off delay | 06/05,06/06 | source16,19,24 /8; source17/4 |
| Automatic-light sensitivity | 06/07–09,06/0B | 20/4;16/9;114/1;116/1 |
| Interior-light sensitivity | 06/0A,06/0C,06/14 | 114/2;116/2;24/25 |
| Headlight linked to wipers | 06/15,06/16,08/22 | 114/3;116/3;24/26 |
| Auto door lock/unlock variants | 07/0F–1C | Multiple source16/19/24 menus; preserve individual XML variants |
| Key/remote unlock mode | 07/1D | source16,19,24 /3 |
| Window/sunroof key-cylinder / keyless linkage | 07/20,07/22 | 48/4;48/5 |
| Window ignition-off timers | 07/24,25,27,28,29,2B,2C | Several sources/menus; do not collapse encoded variants |
| Keyless-lock acknowledgement / relock timer | 07/32,07/33 | source16,19,24 /4 or5 |
| Security alarm | 07/34,07/35 | 24/15;16 or19/17 |
| Walk-away lock | 07/36 | 24/49; other WAL entries have no route |
| Automatic folding mirrors | 07/37 | 48/7; this is OEM metadata, independent of any aftermarket map |
| Front wiper, washer and startup behavior | 08/03–11 | Several source16/17/19/24/118 variants |

These source/menu fields are lookup metadata within the OEM implementation, not OBD codes or a ready-to-send frame list.

## Separate service actions, outside the ordinary enum-write path

Primary evidence below is `../decompiled/VehicleInfoManager/com/mitsubishielectric/ada/appservice/vehicleinfomanager/VehicleInfoManagerApService$7.smali`. Line numbers are file lines, not the original Java `.line` directives.

| OEM API | Classification | Actual behavior | Evidence / unresolved boundary |
|---|---|---|---|
| `requestTPMSCalibration()` | Control action | Sets `mCCustomDwsInitRequest=1`, starts request timer, asks for B-CAN receive data and calls `notifyVehicleCustomize(BcanCommonData)` | line10942; TPMS already XML01/01, so this is an extra API path, not a314th independent catalog feature |
| `requestMaintenanceResetMode(boolean)` | Vehicle-mode write | Sets `mCMaintResetMode` to1/0 and transmits customization state | line10860; no EXL applicability or mode prerequisite inferred |
| `requestMaintenanceReset(type,item)` | Maintenance reset action | Sets `mCMaintResetReq=1`, maps requested item, starts reset timer and transmits customization state | line10612; item names unresolved in this pass; callback/return timing is separate from successful reset |
| `requestCustomizeReset()` | Reset action | Builds a default-reset customization request across discovered sources, sends `notifyBcanCustomFrame` | line9308; source-dependent B-CAN construction; not an Android factory-reset API |
| `requestBackupClear(int,int)` | Backup-data clear action | Calls `requestBackupInfoClear`, requires a registered backup-clear callback in inspected implementation | line9065; clear domains/argument semantics not established, so do not relabel as maintenance or DTC reset |
| `clearInputHistory(boolean)` | Service-history mutation | Clears input-history state | line36; not physical vehicle fault clearing |

Maintenance mapping proven by the actual branches: `type==0` maps resetItem0. Otherwise requested item10→1,11→2, items0–9→3–12. Unknown item values log an invalid-item warning and fall back to0, while the method still proceeds to send. A replacement must not invent maintenance names from these integers or mirror the fallback on malformed input. This is source behavior, not a recommendation to invoke it.

`registerMaintenanceCallback` supplies `IVehicleMaintenanceInformationListener.onNotifyMaintenanceInformation(int[])`; the lower CPU callback `onBcanMaintenance(BcanCommonData)` is implemented in `VehicleInfoManagerApService$5.smali:2108`. `onBcanMetCustom` starts at2930 and includes TPMS-request/timer processing. Generic customization result/readback is `IVehicleVehicleCustomizeInformationListener.onNotifyCustomizeResult(type,result)` plus `onNotifyCustomizeSettingValue(List<CustomizeSettingData>)`. An immediate API integer return is not proof that an action completed in the car.

## Diagnostic APIs are a different surface

| Original OEM interface method | Read/write/action | Proven path and boundary |
|---|---|---|
| `ICpuComService.requestDtcRead(dtcReadRequest,unitAddress)` | Active diagnostic read | Binder implementation `CpuComService$1.smali:6948` forwards to native `doRequestDtcRead(II)`; native declaration at `CpuComService.smali:7574` |
| `ICpuComService.requestDtcClear(unitAddress,clearAreaCode)` | Diagnostic clear action | Binder implementation `$1:6897` forwards to native `doRequestDtcClear(II)`; native declaration at7571 |
| `notifyLetDiagDtcClear(int)` | Diagnostic-control notification | Interface line458; native `doNotifyLetDiagDtcClear` at7373; argument semantics not fully traced |
| `notifyLetDiagInLineClear(int)` | Manufacturing/in-line diagnostic clear notification | Interface line466; no claim this clears Civic vehicle DTCs |
| `requestLwcDiagResultRead()` | LaneWatch diagnostic result read | Interface line1410 / native declaration7661; equipment-specific, applicability unverified |
| `requestTcuSelfDiag()` | Self-diagnostic request | Interface line1682; no domain/Civic assumption from the acronym alone |
| `registerCallbackDiag(IDiagApServiceListener)` / unregister | Diagnostic subscription lifecycle | Interface754/1794; listener includes `onLetDiagDtcClear()` |
| `requestBcanReceiveData(int,int)` / `requestFcanReceiveData(int)` | Active receive/data requests | Interface1050/1290; not dashboard-setting writes |

The original `IPhoneCoordinationApServiceListener` exposes `onDtcRead(DtcReadData)` and `onDtcClear(DtcClearData)` plus ECU-oriented status callbacks (engine, AT, ACC, EPS, SRS, VSA, LKAS, etc.). This proves broad original service integration, **not valid address/request/clear-area values for the Civic**. No validated OBD PID list, UDS service recipe, Civic ECU address map or in-car diagnostic result was established by this audit.

## Complete VehicleInfoManager exposed-method inventory

All103 public Binder-implementation methods are listed. Full signatures, source lines, parameter labels and direct CpuCom call names are saved in `vehicle-service-api-inventory.json`. Read subscriptions may immediately publish cached/current data. The two `registerSpecific*StatusCallback` methods additionally issue receive requests; subscription is therefore not universally a passive local action.

| Method | Kind | Implementation line | Direct lower-layer call(s), where present |
|---|---|---:|---|
| `clearInputHistory` | Control/reset action | 36 |  |
| `getCanInformationForAvApService` | Read/cache | 177 |  |
| `getChangeSpeedReference` | Read/cache | 253 |  |
| `getDrivingOperationStatus` | Read/cache | 300 |  |
| `getFootBrakeStatus` | Read/cache | 379 |  |
| `getInputHistory` | Read/cache | 443 |  |
| `getParkSensHistory` | Read/cache | 494 |  |
| `getTransmissionInformation` | Read/cache | 541 |  |
| `getTurnSignalStatus` | Read/cache | 590 |  |
| `getVehicleInformation` | Read/cache | 696 |  |
| `notifyBcanReceiveSetting` | Receive-configuration write | 851 |  |
| `notifyChangeSpeedReference` | Local/service state write | 872 |  |
| `notifyParkSensHistory` | Local/service state write | 904 |  |
| `registerAntiTheftStatusCallback` | Read subscription / current-data notification | 951 |  |
| `registerAntiTheftValidateCallback` | Read subscription / current-data notification | 1115 |  |
| `registerAtSerialCallback` | Read subscription / current-data notification | 1214 |  |
| `registerBackupClearCallback` | Read subscription / current-data notification | 1313 |  |
| `registerBatteryAndVspPulseCallback` | Read subscription / current-data notification | 1412 |  |
| `registerBcanInformationCallback` | Read subscription / current-data notification | 1530 |  |
| `registerBcanParkingBrakeCallback` | Read subscription / current-data notification | 2757 |  |
| `registerCanInformationForAvApServiceCallback` | Read subscription / current-data notification | 2925 |  |
| `registerCanInformationForCameraApServiceCallback` | Read subscription / current-data notification | 3169 |  |
| `registerDrivingOperationStatusCallback` | Read subscription / current-data notification | 3438 |  |
| `registerFOBKeyNumberInformationCallback` | Read subscription / current-data notification | 3663 |  |
| `registerFcanInformationCallback` | Read subscription / current-data notification | 3839 |  |
| `registerGearInformationCallback` | Read subscription / current-data notification | 4828 |  |
| `registerHDSConnectInformationCallback` | Read subscription / current-data notification | 4943 |  |
| `registerIGInformationCallback` | Read subscription / current-data notification | 5119 |  |
| `registerIGVoltageCallback` | Read subscription / current-data notification | 5275 |  |
| `registerIgnitionStatusCallback` | Read subscription / current-data notification | 5374 |  |
| `registerLetDiagInformationCallback` | Read subscription / current-data notification | 5550 |  |
| `registerMaintenanceCallback` | Read subscription / current-data notification | 5721 |  |
| `registerMetCustomCallback` | Read subscription / current-data notification | 5902 |  |
| `registerMeterIllStatusCallback` | Read subscription / current-data notification | 6083 |  |
| `registerMicuBcmCallback` | Read subscription / current-data notification | 6225 |  |
| `registerParkSens2Callback` | Read subscription / current-data notification | 6395 |  |
| `registerParkSensCallback` | Read subscription / current-data notification | 6559 |  |
| `registerParkingInformationCallback` | Read subscription / current-data notification | 6729 |  |
| `registerPassThresholdSpeedCallback` | Read subscription / current-data notification | 6897 |  |
| `registerShiftParkPositionCallback` | Read subscription / current-data notification | 7194 |  |
| `registerShiftPositionCallback` | Read subscription / current-data notification | 7363 |  |
| `registerSpecificBcanStatusCallback` | Subscription + active read request | 7522 | `requestBcanReceiveData` |
| `registerSpecificFcanStatusCallback` | Subscription + active read request | 7646 | `requestFcanReceiveData` |
| `registerSteeringAngleCallback` | Read subscription / current-data notification | 7728 |  |
| `registerVehicleCustomizeCallback` | Read subscription / current-data notification | 7994 |  |
| `registerVinCodeCallback` | Read subscription / current-data notification | 8093 |  |
| `registerVinNoCallback` | Read subscription / current-data notification | 8192 |  |
| `registerVspPulseCallback` | Read subscription / current-data notification | 8356 |  |
| `registerWelcomeStateCallback` | Read subscription / current-data notification | 8510 |  |
| `requestAntiTheftRelease` | Write/control action | 8680 | `notifyAtCodeCollation` |
| `requestAntiTheftTemporaryRelease` | Write/control action | 8752 | `requestAntiTheftForceTemporaryCancel` |
| `requestAntiTheftValidate` | Active read/discovery | 8784 | `requestExamAntiTheftStatus` |
| `requestAntiTheftValidateChange` | Write/control action | 8816 | `notifyExamAntiTheftControl` |
| `requestAtSerialCode` | Active read/discovery | 8864 | `requestAtSerialCode` |
| `requestAtSerialCodeWrite` | Write/control action | 8927 | `requestAtSerialCodeWrite` |
| `requestBackupClear` | Control/reset action | 9065 | `requestBackupInfoClear` |
| `requestCustomizeCategory` | Active read/discovery | 9147 | `requestBcanReceiveData`, `notifyCustomCheckStart` |
| `requestCustomizeMode` | Vehicle control-mode write | 9226 | `notifyVehicleCustomize` |
| `requestCustomizeReset` | Control/reset action | 9308 | `notifyBcanCustomFrame` |
| `requestCustomizeSettingChange` | Write/control action | 9555 | `notifyBcanCustomFrame`, `requestBcanReceiveData`, `notifyVehicleCustomize` |
| `requestCustomizeSettingValue` | Active read/discovery | 10251 | `notifyBcanCustomFrame` |
| `requestIGVoltage` | Active read/discovery | 10580 | `requestExamIgVoltageValue` |
| `requestMaintenanceReset` | Control/reset action | 10612 | `notifyVehicleCustomize` |
| `requestMaintenanceResetMode` | Vehicle control-mode write | 10860 | `notifyVehicleCustomize` |
| `requestTPMSCalibration` | Control/reset action | 10942 | `requestBcanReceiveData`, `notifyVehicleCustomize` |
| `requestVinCode` | Active read/discovery | 11011 | `requestVinCodeInfo` |
| `sendVehicleCustomize` | Write/control action | 11067 | `notifyVehicleCustomize` |
| `unregisterAntiTheftStatusCallback` | Subscription removal | 11127 |  |
| `unregisterAntiTheftValidateCallback` | Subscription removal | 11209 |  |
| `unregisterAtSerialCodeCallback` | Subscription removal | 11291 |  |
| `unregisterBackupClearCallback` | Subscription removal | 11373 |  |
| `unregisterBatteryAndVspPulseCallback` | Subscription removal | 11455 |  |
| `unregisterBcanInformationCallback` | Subscription removal | 11537 |  |
| `unregisterBcanParkingBrakeCallback` | Subscription removal | 11625 |  |
| `unregisterCanInformationForAvApServiceCallback` | Subscription removal | 11707 |  |
| `unregisterCanInformationForCameraApServiceCallback` | Subscription removal | 11790 |  |
| `unregisterDrivingOperationStatusCallback` | Subscription removal | 11872 |  |
| `unregisterFOBKeyNumberInformationCallback` | Subscription removal | 11977 |  |
| `unregisterFcanInformationCallback` | Subscription removal | 12065 |  |
| `unregisterGearInformationCallback` | Subscription removal | 12153 |  |
| `unregisterHDSConnectInformationCallback` | Subscription removal | 12235 |  |
| `unregisterIGInformationCallback` | Subscription removal | 12323 |  |
| `unregisterIGVoltageCallback` | Subscription removal | 12405 |  |
| `unregisterIgnitionStatusCallback` | Subscription removal | 12487 |  |
| `unregisterLetDiagInformationCallback` | Subscription removal | 12575 |  |
| `unregisterMaintenanceCallback` | Subscription removal | 12663 |  |
| `unregisterMetCustomCallback` | Subscription removal | 12751 |  |
| `unregisterMeterIllStatusCallback` | Subscription removal | 12839 |  |
| `unregisterMicuBcmCallback` | Subscription removal | 12921 |  |
| `unregisterParkSens2Callback` | Subscription removal | 13009 |  |
| `unregisterParkSensCallback` | Subscription removal | 13091 |  |
| `unregisterParkingInformationCallback` | Subscription removal | 13179 |  |
| `unregisterPassThresholdSpeedCallback` | Subscription removal | 13267 |  |
| `unregisterShiftParkPositionCallback` | Subscription removal | 13366 |  |
| `unregisterShiftPositionCallback` | Subscription removal | 13449 |  |
| `unregisterSpecificBcanStatusCallback` | Subscription removal | 13532 |  |
| `unregisterSpecificFcanStatusCallback` | Subscription removal | 13620 |  |
| `unregisterSteeringAngleCallback` | Subscription removal | 13677 |  |
| `unregisterVehicleCustomizeCallback` | Subscription removal | 13765 |  |
| `unregisterVinCodeCallback` | Subscription removal | 13847 |  |
| `unregisterVinNoCallback` | Subscription removal | 13929 |  |
| `unregisterVspPulseCallback` | Subscription removal | 14011 |  |
| `unregisterWelcomeStateCallback` | Subscription removal | 14093 |  |

The anti-theft/AT-serial functions are included only because this is a complete service surface inventory. They are security/identity service APIs, not dashboard personalization controls, and no operational unlock or security-bypass procedure is provided or performed. `notifyChangeSpeedReference` writes the service's speed-source/error-selection state (`subChangeSpeedReference` at main-service line6776), not km/h/mph display units. `notifyBcanReceiveSetting` configures receive behavior using a translation table (`bcanReceiveSetting` at3015), not a vehicle setting by that name.

## Limits and next evidence needed

The inventory is complete for the named103-method OEM Binder implementation and the named218-entry non-meter XML subset. The237 lower-level interface signatures are complete as declarations, not a full behavior audit of every native operation. Civic-specific applicability is still unestablished from static shared-firmware evidence. To turn these into a Civic implementation, select supported category/ID pairs from OEM runtime capability results, preserve route/value/group encoding, and verify completion/readback for each setting or control action. No aftermarket identifiers are substituted at any point.
