# Separate OEM instrument-panel editor — actual code trace

This is the important implementation omitted by the original XML-only audit. It lives in **ExternalDisplayOutService**, not just the generic vehicle-settings catalog. Source: original supplied Honda `ExternalDisplayOutService.apk/.odex`, deodexed using API17 and the update's system/vendor frameworks. Saved source subset: `instrument-evidence/externaldisplay/`.

## UI entry and capability checks

`DaSettings.SystemSettingActivity` identifies resource **0x7f080172**, whose original default label is **Configuration of Instrument Panel** (original DaSettings resource dump7424–7425). Its click handler uses:

`Intent.setClassName("com.mitsubishielectric.ada.app.externaldisplay", "com.mitsubishielectric.ada.app.externaldisplay.CustomizeMeter")`

Source anchor: existing saved `decompiled/DaSettings/.../SystemSettingActivity.smali:4316`. It calls `getMeterContentsDispStatus()[Z]` at10157, stores the result, and disables this row when the returned array is null at11939.

`CustomizeMeter.onCreate` independently retrieves display-status flags and protected/non-deletable content IDs. It exits with an error if either is null (lines82–222). It then calls `Interface2Display.requestMeterContentsData(5)` at242. Debug hardcoded flags exist but `sCustomizeDebug` initializes false; they are not production capability proof.

## Add, remove and reorder are real implementations

| Operation | Exact source evidence |
|---|---|
| Add icons | `CustomizeMeterMain.onClick` launches `CustomizeAdd`; `CustomizeAdd.addScreenFinish` computes the chosen list and calls manager `setLinkedList` at238. |
| Remove icons | Main screen launches `CustomizeDelete`; its delete method calls `manager.isDeleteId(contentId)` at108 and removes the selected entry from the list at159 only when allowed. Protected IDs come from the service. |
| Reorder icons | Main screen launches `CustomizeChangeOrder`. Its adapter `updateList` rebuilds the linked list in current adapter order (adapter lines937–965); confirmation writes the list back to the manager at`CustomizeChangeOrder:514`. |
| Save | `CustomizeMeterMain.finishCustomize` creates a CustomizeData object, calls `requestMeterContentsChangeData`, and finishes the editing screen (lines96–131). The callback is separate; screen closing alone does not prove vehicle success. |
| Select preset | `CustomizeChangePresetMain` has explicit buttons for1,2,3 and a default option. `changePresetNo` maps1→1,2→2,3→3,0→4 when requesting preset contents. |

`CustomizeManager.createCustomizeData` (846–965) writes **preset number**, **contents count**, and the **ordered integer content-ID array** into the `CustomizeData` object. `Interface2Display.requestMeterContentsChangeData` forwards this object directly to `IExternalDisplayApService.requestMeterContentsChangeData` (1759–1869). This is an OEM service API, not a raw CAN frame recipe.

`CustomizeMeterMain.onReplyMeterContentsChangeData` exists at1884; values0 and2 are explicitly treated as error conditions in that handler. The UI has notification timers/driving checks. No vehicle success status was observed during this static audit.

## Category03/id5B has a separate real selector

`CustomizeChangePreset.onNotifyCustomizeSettingValue` searches returned objects for category **3** and id **0x5B** (saved file688), stores the object-list index, and fails if it is absent. `CustomizeChangePresetMain.changePreset`, when used outside the content-editor context, reads that exact returned object, sets its value to the selected preset number, and calls `InterfaceVehicleInfo.requestCustomizeSettingChange` (lines139–178).

Therefore the OEM XML setting03/5B is not merely a label. It is the actual preset-selection setting in this separate application, with XML source50/menu15 and API1,2,3 encoding0,1,2. Within the editor context, preset selection requests stored contents through the ExternalDisplay service instead. The default-content read request4 is **not** a fourth enumerated03/5B API value.

## What can appear in the icon catalog

`CustomizeConst$ContentsId.CUSTOMIZE_METER` contains21 candidate IDs:0–10,14,17,24,25,26,31,32,33,34,35. The parallel drawable resources name fuel, average fuel, speed alarm, tachometer, traffic-sign recognition, AFP, seat-belt reminder, maintenance, two blank entries, customization, average speed, energy flow, G-meter, boost, stopwatch, another maintenance entry, REV indicator, throttle/brake, and BEV trip/range displays.


| Content ID | Verified drawable-name meaning |
|---|---|
| 0x00 | Fuel |
| 0x01 | Average fuel |
| 0x02 | Speed alarm |
| 0x03 | Tachometer |
| 0x04 | Traffic-sign recognition (TSR) |
| 0x05 | AFP (meaning not expanded from the acronym) |
| 0x06 | Seat-belt reminder |
| 0x07 | Maintenance |
| 0x08 / 0x09 | Blank entries 1 / 2 |
| 0x0A | Customization |
| 0x0E | Average speed |
| 0x11 | Energy flow |
| 0x18 | G-meter |
| 0x19 | Boost |
| 0x1A | Stopwatch |
| 0x1F | Maintenance (second ID) |
| 0x20 | REV indicator |
| 0x21 | Throttle / brake |
| 0x22 | BEV trip computer |
| 0x23 | BEV range bar |

Names above are from the matching `CustomizeConst$ResourceId.CUSTOMIZE_METER[id]` drawable references and original `ExternalDisplayOutService-resources.txt`; they are not guessed from numeric IDs. The two maintenance IDs are kept separate.

These resource names describe a **shared platform catalog**, not21 Civic Brasil icons. `CustomizeManager.getAllContents` checks the returned boolean availability array by content ID. IDs64–67 form a separate DA-content array;66/67 additionally require `isEnableContents(id)==1`. Model/market-specific availability must remain intact; boost/BEV/energy-flow names are not proof those screens are supported in the user's Civic.

The editor obtains a non-deletable ID list and respects it. Add-screen maximum capacity comes through manager/runtime data; no fixed arbitrary capacity was imposed in this audit.

## Scope limit

This trace establishes the complete **client UI → ordered/preset data → OEM Binder service** path, plus the separate03/5B selector. It does not decode the ExternalDisplayApService's downstream transport, validate head-unit permissions, or establish in-car support for every candidate icon. The parent Civic Brasil manual audit supplies model-specific product evidence; these saved classes supply implementation evidence.
