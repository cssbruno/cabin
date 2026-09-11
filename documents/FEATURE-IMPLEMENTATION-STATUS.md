# Cabin feature implementation status

Updated 2026-09-09. This is an implementation ledger, not a claim that the 30-item scope is complete. Hardware behavior still needs validation on matching head units.

| # | Requested feature | Current status |
|---|---|---|
| 1 | Interactive vehicle overview | Added an interactive car widget: tire and door buttons show confirmed readings; the climate button opens the existing controls. Unknown door states stay unknown. |
| 2 | Quick-controls drawer | Added a quick-controls sheet for media volume, app-window brightness, climate, a configured camera page and temporary glance mode. |
| 3 | Body styles | Added sedan, hatchback, SUV and pickup to the expanded tire illustration. |
| 4 | Car colors | Added six saved paint choices, scoped to vehicle profile. |
| 5 | Dashboard presets | Added commute, navigation, parking and glance pages; existing pages remain intact. Parking includes only available modules. |
| 6 | Layout undo/redo | Added 40 in-memory undo steps, persisted restored layouts, and redo invalidation after a new edit. History resets when the dashboard preferences instance is recreated. |
| 7 | Alignment guides | Added dynamic guides for edges aligned with neighboring widgets at the drag snap destination. |
| 8 | Steering-wheel page navigation | Added next/previous page actions to key learning, including compact widget pages. Foreground Android key events only. |
| 9 | Steering-wheel long presses | Added separate short/long mappings, 650 ms threshold, release-only execution and schema-3 backup support. |
| 10 | Universal media widget | Added Android media-session discovery through explicitly granted notification access, plus existing CarPlay playback. Players must expose Android media sessions; factory apps without sessions cannot supply track controls. |
| 11 | Audio-source switcher | Added active media-source selection and user-configured Radio/Bluetooth app shortcuts. Configure those launcher apps in accessory shortcuts; this does not add a proprietary hardware audio-routing API. |
| 12 | Per-source volume memory | Added volume save/restore when changing sources through the media widget. Changes made outside that selector are not automatically attributed to a source. |
| 13 | Scheduled quiet startup | Added configurable quiet-start hours with a 20% media-volume ceiling on activity startup. It only lowers volume and does not raise it again automatically. |
| 14 | Sunrise/sunset themes | Added solar day/night calculation with permitted foreground GPS and system-theme fallback. Manual Day/Night choices still take priority. The computed system appearance is also forwarded to projection. |
| 15 | Glance mode | Added temporary glance mode with restoration of the prior dashboard page and compact-widget position; saved layouts are not replaced. |
| 16 | Parking dashboard | Partial: preset combines verified camera mode, parking settings, tires and doors. Camera video embedding is pending. |
| 17 | Live radar visualization | Pending verified distance decoding and UI. Parking configuration values are not obstacle distances. |
| 18 | Camera shortcuts | Partial: verified wide/standard/downward selectors exist. Front/rear/side feed activation is pending. |
| 19 | Unified vehicle alerts | Added paged dashboard widget combining fresh reported tire warnings, open doors/hood/trunk and service-due readings. It does not infer unreported warnings. |
| 20 | Tire-pressure history | Added local recording for the six verified TPMS profiles, one sample per minute, maximum 720 samples per profile. Widget shows the latest 120 points per wheel and breaks lines across missing data. Clear history is available in vehicle compatibility settings. |
| 21 | Trip history | Added per-profile trip segments from fresh verified CAN speed, with distance explicitly estimated, 60-second checkpoints and a 100-trip bound. Data gaps end a segment; two minutes stopped ends a segment. No ignition/odometer semantics are inferred. |
| 22 | Trip costs | Added estimated trip cost using explicitly entered L/100 km and price per litre. Cost remains unavailable until both inputs are valid; checkpoints/final trip records store the estimate. |
| 23 | Maintenance reminders | Added a date-based service reminder, shown in the unified alerts widget when due. Verified odometer-based scheduling remains unavailable without an odometer mapping. |
| 24 | Last parked location | Added explicit save/find/forget parked-location actions with GPS permission, a recent accurate fix and a two-minute request timeout. Coordinates are excluded from Android backup; a traffic stop is not automatically classified as parking. |
| 25 | Hybrid energy flow | Added confirmed battery percentage and charging/discharging direction for WC hybrid profile 655377. No inferred power, plug connection, or regeneration classification. |
| 26 | Charging dashboard | Added capability-gated current limit, cabin temperature, climate-on-battery and minimum-charge settings for profile 655377. |
| 27 | Ambient-light controls | Added recommended/theme palette choice for Honda profile 4260138. Other ambient actuators remain unverified. |
| 28 | Seat comfort | Partial: verified SYU heating/ventilation actions exist in climate. Added Default/Save/Activate seat-preset actions for Megane profile 1769874; no numbered positions are inferred. |
| 29 | Compatibility screen | Added factory-feature support/live-feedback rows and current SYU climate action readiness in settings. |
| 30 | Redacted diagnostic export | Added a separate allowlist-only JSON diagnostic export through the Android document picker. It excludes raw logs, names, phone addresses, GPS coordinates, tokens, metadata and CAN payloads. Existing raw log export is unchanged. |

## Access

- Dashboard edit → **+** → undo, redo, or add preset page.
- Teyes/FYT settings → **Steering shortcuts** → short/long press → learn action.
- Teyes/FYT settings → **Car appearance** or **Vehicle compatibility**.

Verified charging, palette and seat-preset command mappings were added for the exact profiles listed above. Selecting an illustration changes appearance only. New backup exports use schema 3; imports continue to accept schemas 1 and 2. Older Cabin builds may reject schema-3 backups.

## Remaining hardware work

Items 16–18 remain incomplete: native camera video, live radar distances and front/rear/side feed activation require verified hardware interfaces. Items 25–28 now have narrowly scoped integrations; broader vehicle support still requires additional verified mappings. The exact head-unit model and CAN profile ID have been requested. Existing selectors and battery indicators are not substitutes for those features.

Reference reviewed: public FYT source at commit `755c9ae89ef255a975bcc0ee6a149e68cce2128c`. The camera screens establish lens-mode commands but do not establish a usable video surface/provider. The inspected parking view draws guidance rather than providing verified obstacle-distance units. Ambient and posture screens contain generic resource IDs, and the inspected resources do not identify every actuator label. Hybrid charge-limit and scheduled-charge configuration must not be presented as measured charging power or energy flow.

Solar calculation reference: [NOAA General Solar Position Calculations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF).

New access points: dashboard lightning icon for quick controls; media source menu for Android media permission and source selection; settings for Radio/Bluetooth shortcuts, automation, trips, maintenance, parked location and diagnostic export. Native vehicle integration and physical-head-unit validation remain outstanding.
