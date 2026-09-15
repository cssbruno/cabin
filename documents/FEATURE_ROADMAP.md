# CarPlay-first feature delivery

This tracks the user's 60-feature request. **The full list is not complete.**
“Available” means a software path exists, not that every adapter/TEYES firmware has
been hardware-certified. No external OBD adapter or guessed CAN interface is added.

## Current status — 2026-09-14

Use [the implementation ledger](FEATURE-IMPLEMENTATION-STATUS.md) for launcher and
vehicle features, [Joying CarPlay](research/JOYING-CARPLAY.md) for native projection,
and [live replacement status](research/JOYING-LIVE-SERVICE.md) for SYU independence.
The original batch notes below are historical; their version number is not the
current release version. Software coverage does not establish hardware validation.

Current additions: dark defaults for new driver profiles, bounded Joying session
recovery, and app/diagnostics unit-test gates before release publication.

## Original batch

TEYES build146 (`1.0.0-teyes.146`); other distribution version numbers are unchanged.

- Screen off → tap/Back to wake, without removing the projection surface or touching
  the audio/USB session. This does not turn off the physical backlight.
- Left/right projection Tools and help placement.
- Bounded in-memory connection-event history: 40 events and five stream-end snapshots.
  Snapshots contain enums/timing, not arbitrary log strings or personal media/location data.
- Return-to-streaming duration. Includes user waiting and intentional stops; it is not
  presented as measured autonomous recovery time or proof of a defective cable.
- Actual USB detach observations in health details. Repeated detach events suggest
  checking power/connectors but do not establish the cause.
- Exact JSON preview before choosing a destination for a health report. No upload.
- App version/build/flavor and event snapshots included in exported health reports.
- File logging capped at 20 files / approximately100 MiB / seven days on enable and
  rotation, with the active writer protected. Old/excess local logs are deleted;
  save important logs first. A flush batch may temporarily exceed the soft byte cap.

The integrated workspace also includes English/Portuguese resources, metric/imperial
display preferences, schema-2 backups of presentation settings, a reopenable connection
setup guide, and foreground microphone-level/speaker/GPS diagnostics. Diagnostics require
explicit user action and an idle projection session; microphone samples and coordinates
are not saved. These are software capabilities, not proof of head-unit microphone routing
or on-vehicle compatibility.

## Status of all 60 ideas

| # | Feature | Status / remaining work |
|---|---|---|
|1|Preferred phone|Available: TEYES driver profile preferred phone feeds adapter connection target; hardware verification remains.|
|2|Driver switching|Available: paired-phone connect action; automatic handover depends on adapter.|
|3|Quick-stop resume|Partial: headless/wake recovery exists; short ignition-cycle testing remains.|
|4|Connection stages|Available: projection status/readiness screen.|
|5|Actionable errors|Available: readiness/help with Android permission-settings actions and explicit audio/GPS checks; coverage of all vendor failures remains partial.|
|6|USB instability|Partial/new: real detach-event history and repeated-event notice; no cable diagnosis or complete USB-reset classifier.|
|7|Connection history|Available/new: bounded events, snapshots and return timing.|
|8|Recovery escalation|Partial: decoder/reconnect paths exist; Joying native failures now get three delayed retries. Unified adapter audio/video escalation remains.|
|9|Cancel reconnect|Available: explicit phone Disconnect pauses automatic phone connection.|
|10|Manual-connect mode|Available through persistent explicit Disconnect, followed by manual Connect.|
|11|Independent audio volumes|Partial: music/navigation gains exist; independent Siri volume not implemented.|
|12|Smooth ducking|Partial: adapter/system ducking exists; smooth volume transitions remain.|
|13|Microphone recording test|Partial: explicit level test exists; recording playback is not implemented and audio is not saved.|
|14|Mic clipping indicator|Available in the foreground microphone diagnostic; hardware verification remains.|
|15|Audio-route status|Partial: audio diagnostics/logging exist; user-facing route explanation remains.|
|16|Gentle audio resume|Not implemented.|
|17|Startup volume ceiling|Available: configurable quiet-start hours with a 20% media-volume ceiling; see implementation ledger.|
|18|Per-phone audio|Partial: driver-profile gains exist; automatic per-phone association remains.|
|19|Speaker test|Available: bounded left/silence/right tone, explicit idle-session action; verify the actual head-unit route.|
|20|Audio-only recovery|Partial: internal audio/mic recovery exists; separate user-facing recovery action remains.|
|21|Screen-off listening|New software blackout with tap/Back to wake; physical backlight unsupported.|
|22|Large controls|Partial: existing56dp minimum targets; a distinct larger mode remains.|
|23|Control placement|New left/right selection for full-screen Tools and panels.|
|24|Custom quick actions|Not implemented; fixed Tools actions remain.|
|25|Glove-friendly targets|Partial: existing56dp targets; separate spacing mode remains.|
|26|Aspect protection|Available: projection aspect-fits remaining space with climate panel.|
|27|Touch alignment test|Not implemented; geometry regression tests are not an end-user calibration tool.|
|28|Day/night override|Available through TEYES appearance selection.|
|29|Per-phone display|Not implemented; negotiated adapter sizing must be respected.|
|30|Distraction-free mode|Available: Focus view, HUD off and climate notices off.|
|31|Guided compatibility check|Not implemented; explicit firmware-layout selector is only a prerequisite.|
|32|Per-reading freshness|Partial: stale fields expire and become unavailable; per-field age labels remain.|
|33|Door-by-door setup|Not implemented; validated reference mappings alone are not guided setup.|
|34|Partial-door status|Available: missing fields cannot establish all doors closed.|
|35|Oil-service reminder|Partial: verified service-distance display; user-configurable reminder threshold remains.|
|36|Overdue maintenance|Partial: negative distance and explanation exist; proactive notice remains.|
|37|Manual maintenance|Available: date-based service reminder and due alert; odometer scheduling still needs verified mapping.|
|38|Brief A/C notices|Available: non-resizing summary; changed-field-only presentation remains.|
|39|Climate layout comparison|Partial: explicit selector and limitations; detailed comparison remains.|
|40|Camera-return protection|Partial: generic overlay recovery exists; reverse-camera event verification remains.|
|41|Passenger media panel|Available through existing Tools playback controls.|
|42|Short/long steering presses|Partial: key mapping exists; distinct event support must be verified per firmware.|
|43|Accidental-action protection|Partial: existing confirmation/parked checks; consistent long-press policy remains.|
|44|Parking location|Available: explicit save/find/forget parked location with permission and fix-quality checks.|
|45|Parking timer|Not implemented; background reminder delivery needs a complete permission/lifecycle path.|
|46|Trip duration|Available: locally recorded CAN-speed trip segments; distance is estimated and gaps end a segment.|
|47|Driver home screen|Partial: driver profiles exist; per-driver shortcut layout remains.|
|48|Guest mode|Not implemented.|
|49|Return to CarPlay|Available through Hub projection action/conditional explicit-connect return.|
|50|Quiet startup|Partial: focus defaults and initial climate replay suppression exist; broader startup policy remains.|
|51|Drop snapshots|New in-memory bounded stream-end snapshots; survive ordinary UI navigation but not process termination.|
|52|Support report|Available/extended with build details and recorder snapshots.|
|53|Privacy preview|New exact JSON confirmation before document picker.|
|54|Build identification|Available: build details in reports and GitHub release updater in Settings.|
|55|Pre-update backup|Partial: manual settings export exists; automatic pre-update backup remains.|
|56|Previous APK access|Partial: GitHub releases provide previous APK access; in-app archive and guaranteed downgrade compatibility remain unavailable.|
|57|Targeted resets|Partial: video-only and vehicle-only refresh exist; audio-only and narrowly scoped preference reset remain.|
|58|Installation checklist|Partial: reopenable USB/phone/audio/permissions setup guide and explicit diagnostics; vehicle-specific guided verification remains.|
|59|Log storage limits|New count/byte/age retention on enable and rotation; active file protected.|
|60|Unavailable explanations|Partial: connection/vehicle/layout explanations exist; not every feature has a unified explanation.|

## Next delivery order

1. Remaining audio transitions and audio-only recovery; explicit microphone/speaker/GPS checks now exist.
2. Guided parked installation/vehicle checks using observations only.
3. Per-phone profiles and deliberate guest behavior.
4. Optional maintenance/parking utilities after core projection work.
5. Hardware-dependent integration only after interface and on-device verification.
