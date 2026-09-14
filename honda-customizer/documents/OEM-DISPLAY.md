# Native original-Honda display adjustment

The standalone display editor implements brightness (0–10), contrast (−5–5) and black level (−5–5), using the original head-unit AV service. It does not switch display modes, select video sources, or alter tint, density or illumination step.

## Verified contract

- Service package: `com.mitsubishielectric.ada.appservice.avapservice`.
- Service class: `com.mitsubishielectric.ada.appservice.avapservice.AvApService`.
- Binder descriptor: `com.mitsubishielectric.ada.appservice.avapservice.IAvApService`.
- `getDisplayParameter(0)`: transaction `0x2c`, synchronous. Request contains interface token and integer 0. Reply contains exception header, nullable-object presence, then eight integers.
- `setDisplayParameter(0, parameters)`: transaction `0x2d`, synchronous. Request contains interface token, integer 0, presence 1, then eight integers. Reply contains exception header and boolean integer (1 accepted, 0 rejected).
- `DisplayParameter` wire order: **mode, type, brightness, contrast, black level, tint, density, illumination step**.

Exact selected method bodies from the supplied `AvApServiceApiLib.odex` and `AV.odex` are preserved in [display-evidence](display-evidence). `DisplayAdjustmentActivity$2.run` changes the three supported fields on the existing parameter object; contrast/black-level UI progress 0–10 is converted by subtracting five. `DisplayAdjustmentActivity` constructs each corresponding `LevelGaugeController` with minimum 0 and maximum 10.

## Runtime boundaries found in the service

The call chain is `AvApService$2` → `VideoSourceControl` → `BaseVideoSource` → `VideoSettingControl` → `IlluminationControl`.

`IlluminationControl.getDisplayParameter(type)` indexes its current display-mode/source table. Request type 0 therefore must return a parameter whose type remains 0. `AvConst.DisplayMode` names OFF=0, DAY=1, NIGHT=2, ON=3. Crucially, `IlluminationControl.setDisplayParameter` rejects mode≥3 and returns **true without changing anything for mode 0**. The app therefore only allows edits in reported modes 1 and 2.

Before each change, the client reads a fresh full parameter object. It validates the supported field ranges and refuses if the mode/type changed since the value shown to the user. It changes just the selected field and preserves all seven other fields from that fresh read. A successful boolean return is followed by a complete eight-field readback comparison; a mismatch is not reported as success. Switching day/night during a request can therefore require reconnecting and retrying with fresh values.

The service setter updates its cached parameter and applies device settings when the current source/mode matches. A later `VideoSettingControl.setBackupData` saves parameters for both day/night modes and all 12 source indices to VehicleDb. The original settings activity uses the get/set calls above, and this implementation does likewise. It does not add a broad backup/reset call. Service readback establishes current service values, not independent visual or restart-persistence validation.

## Lifecycle and verification

The editor requires a parked checkbox and explicit Apply action. Binder calls run on one background executor. Each request has a 20-second timeout; cancellation invalidates queued work, unbinds the service, and ignores late replies. Once a write has been attempted, rejection, timeout, readback failure and lifecycle disconnect retain an explicit unknown-outcome message until a new connection. Synchronous Binder cannot be forcibly cancelled while in progress.

`DisplayTest` covers exact parcel order, fresh sibling preservation, ranges/park gating, OFF/ON read-only handling, mode changes, null/foreign types, rejection, mismatched readback, cancellation before write, timeout during write, synchronous binding cleanup, observer reentrancy, idempotent lifecycle cleanup and activity launch without automatic connections. The parent task runs the combined Gradle and minimum-API emulator checks.
