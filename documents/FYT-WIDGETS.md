# FYT vehicle widgets

Cabin connects to the shared `com.syu.ms` FYT/SYU toolkit, without checking the head-unit brand. Compatible TEYES, Joying and other FYT firmware can use the same integration. The toolkit action is resolved inside that package to support firmware-specific component names; the standard `app.ToolkitService` remains the fallback. Package visibility already includes `com.syu.ms`.

This does not establish compatibility with every FYT firmware or CAN decoder. Binder protocol, selected vehicle profile, field mapping and available hardware still determine support. Climate writes retain the existing verified vehicle-profile checks. No generic or guessed write commands have been added.

From the dashboard, choose Edit → + to add Climate, Fan, Rear climate, Seats or Defrost. Move or resize them with the existing editor; add another page when the current page is full. Existing layouts remain unchanged and are stored per driver, vehicle profile and selected field layout.

Climate shows both front temperatures, AC state and fan speed, plus a button opening the existing climate controls. Rear climate shows rear temperature and fan speed. Seats shows each front seat's heating and cooling level. Defrost shows front and rear status. Each field requires a fresh sample; temperature also requires unit metadata. Missing/stale fields display a dash, never a default zero or off state.

The widgets use built-in CAN telemetry only, with no OBD adapter. English, Portuguese, Spanish, French, German and Italian labels are included. Validation uses simulated Android services and UI tests; physical FYT hardware validation is still required.

## A/C controls and refresh

The Climate widget now exposes AC on/off and fan down/up callbacks from the existing vehicle controller. Controls require live feedback, the appropriate field, and the controller's verified profile permission; unsupported profiles are not promoted to writable. Values only change when vehicle feedback arrives. Short cards retain access to the full panel.

The widget and full climate panel have a refresh action that rebinds the vendor data subscription. Opening a manual climate panel also retries a disconnected/stale service. A manually opened panel stays open until closed; automatic notices keep their existing timeout and cannot dismiss the manual panel. A specific head-unit firmware, CAN decoder and vehicle profile are still needed to diagnose unsupported commands or field mappings.
