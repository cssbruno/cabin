# SYU vehicle integrations

This is a partial native port, not full support for every SYU vehicle feature.

## Implemented

- Fuel-consumption widget: 34 exact profiles listed by HondaTripActi.init.
  Current and previous averages decode in tenths; unit feedback is required.
  The widget currently displays the current average. Value 65535 is unavailable.
- Hybrid battery widget: 12 exact profiles enabled by
  HondaIndexActi.showHondaEVSettings. The reference displays 0–10 segments;
  Cabin does not invent a battery percentage or voltage conversion.
- Lighting widget: seven BNR Honda profiles routed by
  HondaIndexActi.isBNRSiYuOrGuanDao to AcrivitySiYuSettings. Supports light
  sensitivity, headlight-off delay and interior-light delay using command 105
  and source-defined enumeration values. Not enabled for RZC Civic.
- Generic registered SYU profiles now use the shared door fields 0–5 and cannot
  leak climate/seat fields into the legacy Civic speed, RPM or oil gauges.
- New widgets appear in the picker only when their profile capability is
  identified (lighting also requires a valid feedback sample). Saved widgets
  show unavailable data after disconnect or sample expiry.
- New labels support English, Portuguese, Spanish, French, German and Italian.

All data uses the existing Binder interface validation and its
registered callback ownership checks; this is not cryptographic authentication
of vehicle measurements. New measurements expire after 60 seconds; generic
shared door measurements expire after 30 seconds. Lighting commands require a
fresh valid field, the same profile and connection epoch, and a bounded enum.
The widget uses the existing parked-action guard. Values are not updated
optimistically when a command is sent.

## Additional integrations

- Ford TPMS: six exact profiles (1376590, 1442126, 1507662, 1573198,
  1638734, 1704270) referenced by FordTireAct. Raw pressure is multiplied
  by 2.75 to display kPa; 255 is unavailable. Each tire has independent
  feedback and source warning codes 0–7. Tire temperatures are deliberately
  absent for these profiles because the source suppresses them.
- Factory amplifier: profile 393537 only, matching the explicit visibility
  check in Wc_16Civic_FunctionalActi. Balance and fader use fields 201/202,
  command 2 and keys 2/3, with values 0–18. The display centers on 9.
  Tone controls are not included because their labels remain unverified.
- Steering-wheel mapping now includes media-stream volume up/down and mute.
  These use Android audio APIs for foreground key events, not raw CAN keys.
  Learning, repeat suppression and execution on release remain unchanged.
- Supported trip and tire profiles send the source screen's initial read
  requests once when detected on a new connection. Unknown profiles do not.

Small TPMS tiles show one wheel per horizontal page; larger tiles position all
four readings around a vehicle diagram and highlight each warning at its wheel. New labels remain localized in all six languages. Factory amplifier
controls use the existing parked-action guard and confirmed fresh feedback.

Additional reference files: carinfo/ford/FordTireAct.java,
carinfo/honda/Wc_16Civic_FunctionalActi.java and Wc_16Civic_AMPSetActi.java.

## Still missing from the requested scope

Live parking/radar distances and camera video/automatic activation, broader TPMS support and tire
temperatures, steering-wheel launcher navigation, factory amplifier tone controls,
automatic locks and additional mirror protocols,
seat positioning, hybrid energy-flow interpretation, charging information,
range/voltage decoding, and broader vehicle coverage are not implemented by
this change. Existing climate/seat heat/ventilation controls remain separate.
No generic writes or guessed field mappings were added for these features.

## Reference

Pinned revision: `755c9ae89ef255a975bcc0ee6a149e68cce2128c` of
[the public FYT reference](https://github.com/vasyl91/FYT-Launcher-Mod/tree/755c9ae89ef255a975bcc0ee6a149e68cce2128c/app/src/main/java/com/syu).

Relevant files: module/canbus/FinalCanbus.java and carinfo/honda/
HondaTripActi.java, HondaIndexActi.java, RZC_Honda_ElectricActi.java,
AcrivitySiYuSettings.java. This is source-based verification; actual head-unit
and vehicle testing is still required. No release was published.

## Camera, mirror and parking settings

Implemented from the same pinned reference, without requiring an APK upload:

- Camera view selection for Honda profiles **131114 and 131109**: wide,
  standard and downward. The first uses feedback 134 and command 15 with
  `[mode + 4, 255]`; the second uses feedback 4 and command 2 with `[mode]`.
  Field 4 on profile 131109 is excluded from shared door decoding.
- Mirror synchronization, reverse dip, parked folding, rain wiping and rear
  wiping in reverse on **37 explicit WC Golf/MQB profiles**. The set is the
  intersection of ConstGolf.isWcGolf and Golf7IndexAct's explicit profile cases.
- Automatic parking sensor activation and front/rear warning volume/tone on
  those same WC profiles. No parking-brake or autonomous maneuver commands.
- WC availability is decoded from the high byte independently of the selected
  low-byte value. Parked mirror folding follows the source's explicit exception
  to the availability flag. Missing, invalid or expired readings disable writes.
- The dashboard picker offers Camera view, Mirrors and wipers, and Parking
  settings only for the corresponding profile. Large tiles show all mirror
  switches or parking sliders together. Compact tiles use horizontal pages;
  narrow numeric widgets use an in-place selection menu. Large camera tiles
  show three direct view selectors with a confirmed-selection indicator. Confirmed feedback,
  profile/connection checks and the parked-action guard remain required.
- Labels are available in all six supported languages.

References: Accord9HBackCamera.java, ConstGolf.java, Golf7IndexAct.java,
Golf7FunctionalActi.java, Golf7FunctionalMirrorsAndWipersActi.java and
Golf7FunctionalParkingAndManoeurvrinActi.java.

Camera view selection is not a video feed or camera activation. Parking settings
are not measured obstacle distances. RZC Golf settings remain excluded because
some fields and command formats differ. ParkingHelper's commented-out window
operations were not copied as a functioning overlay.

## Additional verified factory widgets (2026-09-09)

Pinned source: `vasyl91/FYT-Launcher-Mod` commit `755c9ae89ef255a975bcc0ee6a149e68cce2128c`.

- WC hybrid **655377**: `Golf7Electric_information_Acti` fields 312 (1 charging, 2 discharging) and 321 (battery percentage); read request 98/[3]. Direction does not identify plug charging versus regeneration.
- Same profile: `Golf7FunctionalHybridCarActi` capability field 299 gates fields 300–303. Command 145 selectors 1–4 set current limit (5/10/13/MAX), temperature (16–29.5 °C plus LOW/HIGH), climate-on-battery and minimum battery percentage (10% increments).
- Honda **4260138**: `Acrivity_RZC_Yage23_AmbientSettings` field 270 and command 109/[1,1 or 2] choose recommended/theme palette. Generic unlabeled actuators are deliberately not mapped.
- Megane **1769874**: `KeLeiJia_Set_SeatSet` field 200 and command 1/[152,0–2] select Default/Save/Activate. The widget uses direct selection so reaching Activate never sends Save as an intermediate step.

These widgets are offered only for their exact verified profiles. Commands use the existing connection/profile/fresh-feedback guards; feedback is not changed optimistically. Physical head-unit verification remains required.
