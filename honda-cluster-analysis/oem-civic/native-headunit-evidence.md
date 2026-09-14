# Native original-Honda head-unit preferences

The standalone app now edits 11 verified integer preference rows through `IUnitInformationManagerApService`. These are head-unit preferences, not instrument-cluster commands or generic OBD codes. Reads prove the service returns a recognized value; they do not independently establish that every hardware feature is fitted or that its visible behavior changed.

## Wire contract

Original `UnitInfoManagerLib/IUnitInformationManagerApService$Stub` declares `setUserSetting=0x1d`, `getUserSetting=0x1e`, and `getUnitInformation=0x21`. `SettingData.writeToParcel` lines456–480 writes type, `Parcel.writeValue(Object)`, list, and shortcut int array. Integer wrappers initialize the last two fields to null. Getter transactions have an in/out SettingData object: request presence1 + payload, response exception + status + presence + updated payload. Status0 means success. The new `HeadUnitSettingsProtocol` implements this independently and restricts writes to the catalog below. Existing `UnitInfoProtocol` remains unchanged.

Destination is read using unit-information type4 (`CommonUnitInformationManagerWrapper.getDestination`). `UnitInfoManagerConst` defines destinations0–35. Missing or unknown destination disables the three regional clock editors; other recognized preferences remain readable/editable. The implementation does not copy the OEM wrapper's fallback-to-KA behavior when destination is missing.

## Proven settings and values

Wrapper references below are physical line numbers in `oem-civic/system-source/CommonUnitInformationManagerWrapper.smali`, extracted from original DaSettings. All values come from `UnitInfoManagerConst` initialization and the matching Settings UI handlers.

| Native row | User-setting type | Values | Setter reference |
|---|---:|---|---:|
| Clock format | 0x4a | UI0=12-hour,1=24-hour; invert raw/UI only for KJ destination1 | 5393 |
| Clock display | 0x15 | 0=Off,1=On | 5302 |
| Clock style | 0x02 | 0=Off,1=Small digital,2=Digital,3=Analog | 5530 |
| Clock background | 0x03 | 0=Blank,1=Galaxy,2=Metallic,3=Time zone; current4=Imported wallpaper is readable only | 5211 |
| Climate popup duration | 0x4b | 0=Never,1=5 seconds,2=10 seconds,3=20 seconds | 5120 |
| Touch-panel sensitivity | 0x20 | 0=Low,2=High; value1 is not accepted | 7420 |
| Voice command tips | 0x17 | 0=Off,1=On | 7981 |
| Voice prompts | 0x40 | 0=Off,1=On | 8072 |
| Swipe direction | 0x4e | 0=Normal,1=Reverse | 7147 |
| Four-way switch gestures | 0x6e | 0=Off,1=On | 4938 |
| Volume gestures | 0x6f | 0=Off,1=On | 8163 |

Clock regional rules are preserved: `ClockWallpaperTypeActivity.getClockList` line1774 selects array0x7f05000d for KJ/KH (destinations1/4), excluding Analog; all other known destinations use0x7f05000c. `getWallpaperPreInstallList` line2999 selects array0x7f05000f including Time Zone only for KA/KL/KC/KX (0/5/6/7); default0x7f05000e contains Blank/Galaxy/Metallic. `setClockBackground` lines5928–6040 only invokes the wallpaper-path setter for imported images, so native controls expose built-in backgrounds only. Clock-format getter and setter symmetrically invert0/1 for KJ (`getClockFormatForSetting` line924; setter5393).

## OEM editors retained deliberately

Menu color is not just user-setting0x10: `SystemSettingActivity` line6934 calls `setMenuColor`, followed by `WallpaperManager.setResource` at6940 using OEM framework drawables selected by color. A preference-only write would be incomplete. The original editor remains responsible for menu color, language/locale and imported wallpaper. No hidden Android permission, external image file, locale mutation, or guessed framework drawable was added. Head-unit screen also links the separate native display-adjustment module.

## Validation and lifecycle

Connect/refresh perform reads only. Unknown/noninteger values, wrong response types, non-null list/shortcut fields, unprovided preferences and unrecognized destinations cannot enable a write. Each write requires a current ready session, a current parked acknowledgement, a known choice and the value displayed during confirmation. The client re-reads destination (where needed) and the preference immediately before mutation; a changed baseline aborts. It then reads back the value and reloads the snapshot before reporting service confirmation. There is no automatic retry or rollback after rejection/timeout.

HeadUnitActivity dismisses dialogs, clears parked state and disconnects on pause. Clearing parked during a pending write invalidates that session. Binder operations serialize off the UI thread, use a20-second timeout, and reject stale completion after disconnect/reconnect/destroy. Observer-driven reentrant cancellation/reconnection is guarded before binding, scheduling or arming timers; stale binding failures cannot tear down a newer session.

`HeadUnitSettingsTest` adds 24 focused Robolectric cases: independent Parcel decoding; the nonconsecutive touch enum; regional clock transforms and choices; unknown/malformed/unavailable responses; parked and confirmation gates; fresh-value/destination conflicts; rejection and mismatch; blocked-read cancellation; blocked-write timeout; stale binding; wrong Binder descriptor; native confirmation/pause; and six observer/binding reentrancy cases. Central build/test results are reported by the release workflow; this document does not assert on-vehicle validation.
