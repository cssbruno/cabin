# Native original-Honda camera controls

Implemented in the standalone Honda Customizer, using the supplied original Honda firmware. Hardware execution has not been performed. These are Android Binder API calls to the original Honda service, not OBD or aftermarket head-unit commands.

## Exact service ABI

`CameraAPServiceLib.odex`, deodexed with API 17 and the matching system/vendor framework directories, contains `com.mitsubishielectric.ada.appservice.camera.ICameraAPService$Stub$Proxy`. Component package is `com.mitsubishielectric.ada.appservice.camera`; class is `CameraAPService`. The APK manifest exports this service without a service-level permission. Firmware/SELinux policy can still restrict access on a particular unit.

| Transaction | Method | Input after interface token | Reply after exception header |
| --- | --- | --- | --- |
| `0x0d` | `getCameraState` | presence 1, camera int | status, presence, state int |
| `0x22` | `getCameraSettings` | presence 1, camera int | status, presence, Bundle |
| `0x23` | `setCameraSettings` | presence 1, camera int, presence 1, Bundle | status |
| `0x24` | `setDefaultCameraSettings` | presence 1, camera int | status, presence, Bundle |
| `0x5e` | `getAngleSensor` | none | boolean int |
| `0x85` | `setLaneWatchSettingState` | 1 active / 0 inactive | nothing |

All calls are synchronous (`flags=0`). CameraId and CameraState parcel one int each. CameraId rear=0, LaneWatch=4. State OK=0/NONE=1. Settings status success=0. Getter/defaults Bundle parameters are **out-only**, so no input Bundle is serialized. Selected decompiled methods are preserved in [camera-wire-evidence](camera-wire-evidence).

## Five controls and labels

| Camera | Bundle key | Values |
| --- | --- | --- |
| Rear | `REAR_WIDE_CAMERA_STATIC` | 0 Off / 1 On |
| Rear | `REAR_WIDE_CAMERA_DYNAMIC` | 0 Off / 1 On; requires live angle sensor |
| LaneWatch | `LANEWATCH_TURN_SW` | 0 Off / 1 On |
| LaneWatch | `LANEWATCH_DISPLAY_TIME` | 0 **0 seconds** / 1 **2 seconds** |
| LaneWatch | `LANEWATCH_GUIDE_LINE` | 0 Off / 1 On |

The duration is not a seconds integer. `LaneWatchSettingActivity$4.onClick` passes `STR_MM_04_06_03_RES_12` for value 1 and `STR_MM_04_06_03_RES_11` for value 0; Camera.apk default resources decode these as 2 seconds and 0 seconds. `CameraAppSettingBaseActivity.onClickDurationTimeButton` builds the `[1,0]` value array. On/off helpers likewise use `[1,0]`. `RearWideCameraSettingActivity.getAndShowCurrentSettingFromService` only shows dynamic guidelines when `getAngleSensor()` is true.

## Preservation and confirmation

`RearWideCameraManager.setSetting` reads both guideline keys unconditionally and optionally stores `CAMERA_PARKING_SENSOR`. `LaneWatchCameraManager.setSetting` reads all three keys unconditionally. Therefore one-key bundles would silently reset siblings to zero. The new client refreshes the complete bundle before mutation and preserves sibling values.

Both managers persist through VehicleDbDataManager. LaneWatch also calls CPU communication `notifyLwcCustom`. A successful service readback establishes the stored settings reported by this firmware; it does **not** establish visible camera behavior or an independent ECU acknowledgement. No extra guessed store transaction is issued.

Rear defaults also reset `CAMERA_PARKING_SENSOR` view mode to 1; the UI confirmation names this effect, and readback checks it. LaneWatch defaults report turn=1, duration=0, reference lines=1. Defaults are returned by the real API rather than synthesized by the client. Stock LaneWatch defaults contain a CPU notification whose first argument differs from the stored turn value, another reason not to overstate physical confirmation.

The client only exposes live reported cameras, refuses incomplete/out-of-range known bundles, rechecks capabilities before changes, requires the parked checkbox, confirms writes/defaults, performs service readback, and runs Binder work off the UI thread. LaneWatch session entry/exit wraps mutations in `finally`. Leaving the screen clears parked acknowledgement, dialogs, and cached values. A 20-second timeout invalidates late results and reports an unknown write outcome. Android synchronous Binder calls cannot be forcibly cancelled: cleanup runs when an in-flight call returns, not a guaranteed wall-clock deadline.

The original `com.mitsubishielectric.ada.app.camera.activity.CameraSettingActivity` remains an explicit launch option for original camera UI functions beyond these five preferences. No camera calibration, image rendering, or forced camera-power controls are implemented.

Verification: `CameraIntegrationTest` independently decodes transaction ids, Parcelable int order, input/output Bundle shape, full-bundle preservation, defaults, capability/park/range gates, service rejection, readback mismatch, timeout/late results and LaneWatch cleanup. Parent task owns the combined Gradle run and final test result.
