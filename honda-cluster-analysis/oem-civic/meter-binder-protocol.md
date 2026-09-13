# Implemented original Honda instrument-content service

Implementation: `honda-customizer/app/src/main/java/com/cabin/hondacustom/MeterProtocol.java`, `MeterContents.java`, `MeterClient.java`. This is the **original Mitsubishi Electric Honda service ABI**, not an OBD command or an aftermarket CAN-box protocol. No vehicle/device writes were performed during implementation.

## Exact OEM transport evidence

Stable source: [instrument-evidence/meter-binder](instrument-evidence/meter-binder/), deodexed from the original update's `system/vendor/framework/ExternalDisplayLib.odex` and `system/vendor/app/ExternalDisplayApService.odex`. Hashes recorded alongside source. The original service APK manifest exports `com.mitsubishielectric.ada.appservice.externaldisplay.ExternalDisplayApService` with action `com.mitsubishielectric.ada.appservice.externaldisplay.IExternalDisplayApService`.

Service descriptor `com.mitsubishielectric.ada.appservice.externaldisplay.IExternalDisplayApService`; callback descriptor `com.mitsubishielectric.ada.appservice.externaldisplay.IContentsCustomizeListener`.

| Transaction | OEM method | Request | Reply |
|---|---|---|---|
| 3 | registerContentsCustomizeCallback | strong callback Binder | boolean as int |
| 4 | unregisterContentsCustomizeCallback | strong callback Binder | boolean as int |
| 10 | notifyContentsCustomizeStatus | boolean as int | boolean as int |
| 14 | getMeterContentsDispStatus | none | boolean[] |
| 15 | getMeterContentsNoDelId | none | int[] |
| 16 | requestMeterContentsData | request int | boolean as int |
| 17 | requestMeterContentsChangeData | presence int; CustomizeData | boolean as int |
| 23 | isEnableContents | content ID int | status int; enabled exactly 1 |

All service calls use flags0, the interface token, and synchronous exception header. Callback1 is `onReplyMeterContentsData(status, data)` and callback2 is `onReplyMeterContentsChangeData(status)`, both one-way. `ExternalDisplayConst` proves statuses0 NG,1 OK,2 NONE. Binder acceptance is separate from the asynchronous result.

`CustomizeData` Parcel order: preset int, count int, maximum int, intArray. The original service decodes maximum to10/15; original UI `CustomizeBase` validates these two values. The implementation rejects other maxima rather than the OEM UI's fallback10. Outgoing maximum0 mirrors `CustomizeManager.createCustomizeData`, which never assigns it. `ExternalDisplayApService$3.requestMeterContentsChangeData` ignores outgoing max and reads preset/count/IDs; its underlying encoder accommodates15 IDs. The app does not construct raw bus frames.

## Presets and runtime content capabilities

Requests1/2/3 load saved presets; request4 loads default contents; request5 loads current contents. Payload presets are0(default),1/2/3(saved). Original `CustomizeBase.getValueReplyMeterContentsData` deliberately does **not** replace the selected save destination when payload preset0 is received. The implementation therefore loads defaults as a draft for the currently selected saved preset and never writes preset0. Opening a saved preset does not activate it. Actual activation is the separate VehicleInfoManager category03/id5B path, documented in [instrument-panel-editor.md](instrument-panel-editor.md).

The editor's known meter candidates are IDs0–10,14,17,24,25,26,31,32,33,34,35, individually filtered by the live display-status array. Missing status indices are not assumed supported. The original editor always offers DA IDs64/65;66/67 require `isEnableContents(id)==1`. DA icons prove64 Audio,65 Phone,66 Mail,67 Turn-by-turn directions: `CustomizeConst$ResourceId.CUSTOMIZE_DA` IDs0x7f0204e2/f1/ef/fb, accessed at `contentId-64` by `CustomizeDelete$CustomizeDeleteAdapter` and other adapters. Names are backed by resource names, not a claim all are available on a Civic. Unknown existing IDs remain visible as `Display item N` and are preserved when editing; new unknown IDs cannot be added.

Protected IDs come from the live no-delete list. Only protected IDs actually in the baseline must remain; sentinel IDs in that list do not create new contents. Additions must be currently available, duplicates are blocked, order length cannot exceed the returned maximum. The capability lists are re-read immediately before saving, preventing a draft from bypassing newly reported restrictions.

## Client contract

- `connect()`, `disconnect()`, `destroy()`, `refresh()`; independent of `HondaClient`.
- `loadPreset(1..3)`; `loadDefaults()` keeps the selected destination preset.
- `add(id)`, `remove(index)`, `move(from,to)`, `discard()`, `save(parked)`.
- `phase`, `status`, `live`; immutable `draft()`, `available()`; `isProtected(id)`, `dirty()`, `editable()`, `canAdd(id)`.
- `MeterContents`: immutable preset/count/max, defensive `ids()`, immutable `contents()`; static `label(id)`.
- Public client methods and observer run on the Android main thread. Binder work uses one serial executor.

Successful save requires request acceptance, callback status1, then a fresh read of the same preset with exactly matching ordered contents. Wrong-preset callbacks are ignored; rejection, malformed data, timeout or mismatched readback invalidates the connection and all values. Connection generations discard stale callbacks/work. Timeout is a20-second client deadline and does not imply the vehicle rejected or rolled back an already dispatched write. Mode-off/unregistration cleanup runs in order before any subsequent connection's work.

**Transport limit:** the OEM callbacks contain no request token. The client serializes its own operations and filters phases/presets, but cannot identify another stock application's simultaneous matching broadcast. Operate one customization editor at a time. Hardware success remains unverified until this APK is run with the actual OEM service and vehicle. A replacement FYT unit normally does not provide this Binder service; this implementation cannot manufacture that transport.

## Verification

`MeterProtocolTest` contains independent fake-service wire decoding and golden callback packets, descriptor/rejection checks, capability invariants, and defensive-copy checks. `MeterClientTest` exercises editing/protected IDs, live capability changes, parked gating, accepted-write acknowledgement plus readback, defaults/save-destination semantics, rejection, mismatched readback, and timeout/stale-session protection. Parent task runs the combined Android Gradle checks.
