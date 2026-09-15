# Cabin TEYES ecosystem

This is an app-side integration for the `teyes` build, Android 8.1/API 27 or newer.
It is not replacement vehicle firmware. Software builds, tests and lint have been run;
the complete integration has **not** been tested on the user's actual TPRO/Civic.

## Start here

1. Install the `teyes` APK and open Cabin to grant the permissions you need.
2. Connect the existing projection adapter. Projection remains the default view.
3. Tap **Tools → Vehicle Hub** for the native dashboard, or **Open Hub** on the connection
   screen. The header's full-screen icon returns to
   projection; a connected phone also gets an **Open projection** button. The same
   projection surface/session remains mounted underneath the hub.
4. While parked, open **Hub → Settings → TEYES**. Confirm parked status when requested.
5. Choose a driver profile and set any accessory shortcuts. Vehicle data comes only from TEYES/SYU.

The dashboard shows connection state/history, supported vehicle readings, custom A/C,
now playing, phone-supplied guidance, TEYES subsystem health, accessory shortcuts and health export.
It is not a standalone navigation engine, CarPlay replacement, or video inside an
Android home-screen widget. The widget opens a separate compact projection Activity.

## UI and navigation

The native screens share a teal/mint day/night palette, readable secondary text and
rounded cards. The Hub puts the connection action, large source-labelled speed/RPM,
media controls, climate and fresh guidance ahead of detailed setup. Its grid adapts
between one, two and three columns; enlarged text gets more space. On short, narrow
or large-font screens the header scrolls with the cards so it cannot hide the controls.
Compact actions have at least 56 dp touch targets; the central media button is 64 dp.
Text uses Android font scaling, not a fixed screenshot-sized layout.

- **Your apps:** configured accessory launchers, or **Choose apps** when none are set.
- **Vehicle readings:** fresh supported TEYES/SYU fields. No external OBD pairing or
  connection controls. Missing or expired data shows `—`, never a fabricated zero.
- **System health → Health & compatibility:** vehicle profile/freshness, connection
  history, retry, privacy-filtered report export and hardware limitations.
- **Settings:** wide screens use a navigation rail; narrow screens use a horizontally
  scrollable tab row. TEYES setup groups Driver profile, Appearance, Audio and Vehicle
  readings. Preferred phone, Camera & wake recovery, Steering-wheel shortcuts,
  Accessory app shortcuts and Backup & restore expand when needed.
- **Back:** returns to the previous Cabin view and keeps the session running.
  **Exit app → Stop and exit** explicitly stops connections before closing the app.

Configuration, accessory launches and diagnostic actions still use the parked guard.
Simplified summaries do not remove compatibility limits: expand the relevant details
to see the reason a feature is unavailable.

### Projection experience: quiet defaults

Open **Settings → Control → Projection experience** while parked. These choices apply
app-wide and are stored separately from driver profiles. Schema-2 backups include these
choices and the System/Metric/Imperial display-unit preference; schema-1 restores leave them unchanged.
Focus view is available in every build; the remaining choices below are TEYES-specific.

| Setting | Default | Behavior |
| --- | --- | --- |
| Focus view | On | One **Tools** button over full-screen projection; extra Hub/A/C buttons appear only when disabled |
| Vehicle speed HUD | Off | Optional fresh vehicle readout over projection; enabling it cannot create missing telemetry |
| A/C update notices | Summary | Brief climate summary with no reserved video height and no projection resize |
| Return to projection when ready | On | One recent, explicit Hub **Connect phone** can return to projection under the conditions below |

**Tools** provides Siri/voice assistant and previous/play-pause/next commands for the
active phone, plus **Recover picture**, Settings, Vehicle Hub and manual A/C when available.
These are existing projection controls, not new phone or CAN interfaces. Recover picture
requests video recovery without restarting USB or the phone connection. The small Tools
panel does not renegotiate projection dimensions. Closing it restores touch forwarding;
compact projection still has its own Full screen/Close controls.

**Summary** is the quiet automatic climate mode. **Open climate panel** opts into full
controls and proportional projection resizing after supported climate updates. **Off**
suppresses automatic notices only: **Tools → A/C** remains available manually. The manual
panel aspect-fits the picture into its remaining space; it must not stretch the phone UI.
Missing/stale climate data stays unavailable in every notice mode, and none of these
presentation choices changes the verified-profile restriction on climate writes.

Automatic return is deliberately narrow: press **Connect phone** in the Hub, keep that
Hub visible in the foreground with window focus, and projection must become ready within
two minutes. Leaving the Hub, opening Settings or a parked/permission dialog, losing focus,
backgrounding, or turning this preference off cancels the pending return. Compact panels
do not use this navigation shortcut. An unrelated or automatic reconnect cannot navigate
away from what you are doing. If a permission prompt interrupts the return, finish the
prompt and use **Open projection** yourself.

### Explicit connection and help

**Disconnect Phone** records a phone-connection pause. It is distinct from stopping the
USB adapter, and ordinary automatic starts/restarts retain that pause. An explicit
**Connect phone** action from the connection screen, Hub or connection help—and the
widget's **CONNECT** action—resumes phone connection. It does not forget pairing records.

The connection screen's **Connection help** shows a local snapshot of recognized USB
adapters/access, whether the adapter interface is open, observed phone-session state and
optional microphone/location permissions. **Refresh** reads again without reconnecting,
opening permission prompts, changing settings or performing automatic troubleshooting.
An unavailable observation is not treated as proof of a missing adapter. Permission for
the optional app microphone/head-unit GPS does not verify microphone hardware, call audio
or a satellite fix, and missing optional permission does not by itself block projection.
There are no radio signal-strength, connection-quality or display-quality scores.

**Setup guide** is a saved, reopenable five-step walkthrough available from the connection
screen, Phones tab and Connection help. Its audio routing choices are saved for the next
normal adapter connection. Completing the guide is not a hardware-validation result.

Connection help also offers explicit **Audio and GPS checks**, gated by parked confirmation
and a fully stopped projection session. The microphone meter runs for five seconds;
stereo tones use Android media output at the current system volume. GPS accepts only a
new timestamped fix, with a 22-second limit and reported accuracy. No audio recording,
coordinates or device identifiers are retained. Tests release their devices on Stop,
focus/lifecycle loss or when the panel closes. Android app-permission settings can be
opened directly. A heard tone and received microphone samples are different from proving
the phone/adapter audio route; GPS reception does not prove forwarding to the phone.

The native UI includes English, Portuguese, Spanish, French, German and Italian. Choose
**Settings → Control → Language**, or follow Android's language. Display
units can independently follow the region or use Metric/Imperial from Projection experience.
This formats native speed, guidance and oil-service distances without changing raw fields.

For manual iPhone checks such as wireless Bluetooth/Wi-Fi, Siri and CarPlay restrictions,
see [Apple's CarPlay help](https://support.apple.com/en-us/105109). These are user-performed
checks, not operations that Cabin runs or proof that a TEYES/Civic installation is compatible.

### Home-screen widget and compact projection

On a launcher with Android widget support, the widget has a stacked narrow layout and
a wider layout with connection details. It requests horizontal **and vertical** resize;
the launcher decides whether and how to honour these sizes. Increasing the wide widget's
height allows a second status line. Narrow widgets retain the title and large action;
their full status remains available to accessibility services.

| Button | Current state | Action |
| --- | --- | --- |
| **CONNECT** | Disconnected | Explicitly resume phone connection and request background USB/audio |
| **VIEW** | Connecting or preparing projection | Open the compact panel to view progress; do not request another connection |
| **OPEN** | Live projection | Attach the compact panel to the existing session |

The widget body also opens the compact panel. **Full screen** and **Close** remain reachable
there, including while connecting. This is a separate Activity above the launcher, not
CarPlay video embedded inside `RemoteViews`. Background microphone/GNSS depend on prior
runtime permission grants and Android foreground-service rules. Use the ongoing
notification's **Disconnect** action to stop the background session.

## What works in the app

| Area | Implemented behavior | Dependency or boundary |
| --- | --- | --- |
| Projection | Full/compact projection, quiet Tools view, optional native hub, background USB/audio | Android foreground-service and permission restrictions still apply |
| Driver profiles | Three named profiles, preferred phone, appearance, gains, launch layout and recovery options | Preferred phone applies at the next adapter connection; no immediate phone switch |
| Appearance | Day/night/follow Android; Cabin-window night brightness | No direct headlight or ignition signal is assumed |
| Audio | Android media volume/mute, separate adapter music/navigation gain, existing focus handling | Cannot separate already-mixed prompts or control Bluetooth-direct audio with these gains |
| Steering buttons | Learn supported key events delivered to foreground Cabin | Vendor-intercepted keys and system safety keys are not remapped |
| Factory overlays | Pause/recover video after observable focus/lifecycle changes; USB/audio session retained | Factory app owns reversing camera; invisible firmware overlays may not be detectable |
| Screen wake | Optional retry of a still-requested session in an existing foreground service | Respects Stop; no guaranteed cold-boot/power-loss restart |
| Vehicle dashboard | Fresh supported TEYES/SYU fields only; no external OBD fallback | Not a substitute for factory instruments; unsupported fields remain unavailable |
| Accessory shortcuts | User-selected installed exported launcher activities for DSP, TPMS and DVR | No native TPMS, DSP preset, or DVR recording API is claimed; the old OBD shortcut is hidden |
| Guidance | Phone-supplied road/distance/ETA when available and fresh | Does not calculate routes; optional missing fields show unavailable |
| Health export | Bounded connection history and capability/status JSON | Omits phone addresses, location, routes, media titles and raw vendor payloads |
| Settings portability | Versioned JSON export/import with preview and confirmation | Contains private profile names/phone addresses; distinct from health export |

## Vehicle service and custom climate

The integration binds the existing exported `com.syu.ms` toolkit/CANBUS service. Binder
connection alone does not prove the vehicle/profile is compatible or its values current.
The hub distinguishes disconnected, connecting, live and stale health, lists the number
of fresh fields, and offers **Retry vehicle service** after a failed connection. Automatic
retries are bounded rather than continuously rebinding a broken/missing service.

Exact numeric profiles **262465**, **1048874**, **1114410**, **196906** and **262442**
are allowlisted for climate writes. Required A/C/fan fields must be present and fresh.
Unknown profiles are read-only.
The factory label **0298** must not be interpreted as binder ID `298`, or as proof it is
equivalent to `262465`. Use the decoder/profile recommended for the actual installed
vehicle hardware; do not change factory settings just to bypass a disabled Cabin control.

The 0298 Civic mapping is based on the public [FYT reference implementation](https://github.com/vasyl91/FYT-Launcher-Mod/tree/755c9ae89ef255a975bcc0ee6a149e68cce2128c/app/src/main/java/com/syu):
`FinalCanbus.java` identifies 1048874/1114410 as RZC 2016 Civic vertical-screen L/H,
and 196906/262442 as XP1 2016 Civic variants. `HandlerCanbus.java` selects callback
0298 from the low 16 bits. `ActivityAirControl.java` and that callback establish
module command 105: (172,1/2) A/C on/off, (172,3..6) airflow and (173,1..7) fan.
The reference contains different generations of field layouts. Old `Air_0298` renderers
use A/C 24 and fan 29, while the active `ActivityAirControl.java` consumes A/C 11,
fan 21 and airflow 18/19/20. Therefore the profile number alone cannot choose a dialect.
This is reference-code compatibility, not certification of TEYES firmware or a hardware test.
Profile 262465 retains its separate command 107 path, including single-key airflow release.
No temperature, defrost or arbitrary CAN write interface is enabled by this change.

Vehicle data format is now selected from the installed `com.syu.ms` APK fingerprint.
The fixed manual Civic selector and saved layout override are removed. The inspected
Joying APK is recognized; an unknown APK does not enable the conflicting legacy/Civic
gauge mappings merely because its profile ID belongs to that family. The settings screen
shows the service version and recognition status, and vehicle diagnostic reports include
the APK SHA-256 for identifying additional firmware. Existing per-profile SYU definitions
remain separate from this firmware-dependent decoder.

Reference149/151 motion scaling, modern temperature encoding, coolant and voltage remain
unverified and unavailable. In the reference,89/90 are seat fields: they must NOT be
used as speed/RPM in that layout. Do not switch away from working readings casually;
compare with factory instruments while parked. Some firmware also reverses rear-door
indications; verify each door before relying on the display. Layout/profile changes clear
samples and rebind with new callback ownership so queued old data cannot cross layouts.
CarPlay is not restarted or resized by this choice. Partial door readings never establish
that all doors are closed. Missing temperature units no longer default to Celsius in the hub.
**Hub → System health → Health & compatibility → Refresh vehicle readings** requests
a fresh subscription replay while parked, even when the service remains connected.
It does not send vehicle-control commands or restart CarPlay. Exported diagnostics include
the selected layout and identify available codes as app-normalized, not raw vendor IDs.

Supported climate updates use a brief summary by default, without reserving video height.
The optional automatic Panel mode or a manual A/C action opens Cabin's own bottom panel.
Initial field replay and duplicate updates are suppressed, with notice throttling. When
the full panel is open, projection fits the remaining height without stretching. Service
loss clears cached measurements and disables
controls; reconnecting must provide fresh state again. No new CAN commands were added.
The panel uses readable selected states, explicit unavailable values, 56 dp controls
and scrolling when the remaining height is too short. Touch mapping follows the fitted
projection area; unused letterbox space is not a stretched extension of the phone screen.

Freshness uses monotonic time and is applied per field:

| Data | Expires after no fresh update |
| --- | --- |
| SYU speed/RPM | 5 seconds |
| SYU doors/boot/hood | 30 seconds |
| SYU climate/status fields | 60 seconds |
| SYU oil life | 300 seconds |
| Phone guidance | 30 seconds |

The profile identifier is retained only within the current service session, rather than
being treated as a changing measurement. Some vendor firmware publishes only changes:
an unchanged value can therefore expire. Unavailable doors are not displayed as confirmed
closed, and unavailable climate values are not silently interpreted as “off.”

## Vehicle/OBD data through TEYES only

Cabin reads the existing TEYES/SYU `com.syu.ms` subsystem, not a separate OBD Bluetooth
adapter. The former ELM327 transport and Bluetooth/Nearby-device permission declarations
have been removed. There is no external OBD scan, pairing, socket, polling or fallback.
This does not change the projection adapter's own phone connection or audio-routing settings.

Open **Hub → Settings → TEYES → Vehicle readings** for the subsystem's available fields.
Speed, RPM and oil life are shown only when the known TEYES profile publishes current
supported readings. Existing per-field expiry and connection health rules still apply.
No new proprietary commands or unverified OBD module IDs are sent.

Coolant, ECU voltage, trouble-code reading/clearing and additional OBD PIDs are **unavailable**
until a verified TEYES interface exists. A Binder connection alone does not enable them.
The former OBD app shortcut is hidden so saved configuration cannot launch an external
adapter workflow from the Hub; stored profile/backup data is retained, not erased.

## Backup, restore and privacy

Expand **Backup & restore → Save backup / Restore backup** in TEYES settings. Android's document picker
grants access to the particular file you select, not broad filesystem access.

Backups contain all three app profiles, learned steering keys and selected accessory
components, app-wide projection-experience preferences and display units. They exclude
logs, credentials, adapter configuration and vehicle firmware.
Profile names and preferred Bluetooth phone addresses **are included**: store/share backups
as private files. Health-report export has a separate privacy-restricted allowlist and
does not include those addresses or route/media content.

Import accepts schema versions 1 and 2, up to 32 KB, and validates the whole strict JSON document
before writing anything. Unknown fields/enums, malformed components/MAC addresses,
unsupported keys, wrong types, duplicate slots/fields and out-of-range values are rejected.
A preview requires explicit confirmation before replacing the three profiles, mappings
and shortcuts, plus schema-2 presentation and unit settings. A schema-1 restore leaves
current presentation/units unchanged. The selected driver slot and unrelated settings
are preserved. Each preference file is committed separately; a storage failure is reported
as potentially partial rather than a successful restore. Active appearance/audio settings apply immediately; import does not switch
phones, launch an accessory or write vehicle firmware. Shortcuts must still pass launch-time
installed/exported/enabled/launcher/permission checks on the destination unit.

## Driving guard and cluster limitations

Settings, accessory launches and diagnostic actions use a supplementary parked-confirmation
guard. A positive speed locks configuration; missing telemetry does not clear previously
observed movement automatically. If the speed signal is lost, the Hub offers an explicit
parked confirmation to recover access; fresh positive speed immediately locks it again.
This guard is **not** a vehicle safety interlock, verified gear/parking-brake
signal or guarantee the driver is parked. Use the factory instruments and configure while parked.

The TEYES build includes a legacy music text bridge; navigation forwarding depends on its
configuration. Vendor software owns any final cluster transport. Actual Honda cluster output
remains unverified. Navigation
temporarily takes text priority over music, with bounded/sanitized fields. Optional missing
guidance values are not presented as real zero distance/ETA. No verified Honda bitmap interface
is implemented: no album art, photographs or cluster map images. The HDMI/external-display
test and its Settings controls have been removed. Main-screen CarPlay is unaffected.
GM/AAOS cluster mechanisms documented elsewhere do not prove Honda compatibility.

### Recovery controls mean different things

- **Reset Decoder** recovers projection video without requesting a USB reconnect.
  If the renderer is covered by Hub/Settings, paused or waiting for a usable surface,
  recovery is queued and the UI asks you to return to projection. It runs on a valid
  visible resume, not underneath the overlay. With no active video session, the UI
  explains that there is nothing to recover yet.
- **Reset Connection** restarts the adapter session. Returning to projection or changing
  tabs does not cancel the restart halfway through; an explicit Stop still takes priority.
- **Reset cluster host** is Android Automotive Templates Host recovery, **not** a Honda
  instrument-cluster reset. It is unavailable on ordinary TEYES units without that host.
  Do not interpret this disabled control as a request to change vehicle firmware.

## Internal APIs for maintainers

These are Kotlin app APIs, not a public remote-control service or vendor SDK:

- `TeyesDashboard`: native hub composition; retains the projection surface beneath it.
- `TeyesDashboardContent(model, actions)`: presentation-only boundary for UI fixtures,
  accessibility and compact/large-font layout checks; no controller or hardware simulation.
- `CabinManager.setVideoOverlayCovered(...)`: prevents hidden hub/settings video decode
  without disconnecting USB/audio; deferred startup is preserved until projection is visible.
- `CabinManager.dashboardState` / `ProjectionHealthStore`: observable projection/media
  status and bounded transition history. Disconnect counts include user-requested Stop.
- `CabinManager.resetVideoDecoder()` reports requested, queued or unavailable recovery;
  a queued user reset is consumed only when a valid projection surface resumes visibly.
- `ProjectionPreferences.getInstance(context).state`: app-wide focus/HUD/notice/return
  preferences, with typed-safe defaults and explicit setters; included in backup schema 2.
- `ProjectionReadinessSnapshot` / `projectionReadinessPresentation(...)`: bounded local
  connection-help observations and copy, not active connection testing or signal-quality telemetry.
- `projectionReturnDecision(...)`: only a recent explicit Hub request can return to video;
  ineligible/focus-lost/background states cancel the pending navigation.
- `TeyesClimateController.state`: vehicle health, supported/fresh fields and control eligibility;
  `TeyesTelemetryFreshness` and control policy enforce expiry/profile boundaries.
- `teyesVehicleReadings(vehicle)`: uses only supported fresh TEYES/SYU fields. Its legacy
  overload deliberately ignores any external OBD snapshot.
- The legacy `ObdController` API is inert compatibility code: no Bluetooth, socket,
  pairing, persisted adapter selection or telemetry polling. No production UI instantiates it.
- `TeyesFeaturePreferences.profile` / `revision`: profile/configuration observation;
  `configurationSnapshot()` / `replaceConfiguration(...)` implement scoped replacement.
- `TeyesConfigurationBackup.encode/decode/read`: validated schema-2 export and schema-1/2 import;
  `TeyesConfigurationTools`: document-picker UI and confirmation flow.
- `TeyesAppShortcuts`: discovery and launch-time validation of selected launcher identities.
- `TeyesDrivingGuard`: supplementary UI gate; `TeyesDiagnostics.encode(...)`: export allowlist.

## Parked acceptance checklist

- [ ] Confirm the APK installs on the actual head unit and note Android/firmware/decoder versions.
- [ ] At the unit's short landscape size (for example 800×480), scroll every Hub/settings
  section and open/close disclosures. Check that Back, projection, Connect and Close remain reachable.
- [ ] Repeat with Android text at 150–200%, narrow split-screen and day/night themes.
  Important action labels must remain distinguishable; controls must not overlap or be clipped.
- [ ] Confirm default live projection shows Tools, with the optional vehicle HUD off. Open
  Tools and check voice/media callbacks, Close and Recover picture without a USB reconnect.
- [ ] Change climate state with Summary selected: a brief notice must not reserve video
  height. Check automatic Panel and Off, and confirm manual A/C remains available in every mode.
- [ ] From Hub, explicitly Connect phone and remain there: when ready, projection may return.
  Repeat while entering Settings, dismissing a permission prompt, switching apps or waiting
  more than two minutes; none should cause a delayed takeover. Check the preference off too.
- [ ] Disconnect Phone, restart normally and confirm the pause is retained; explicitly Connect
  phone to resume. Refresh Connection help and verify it does not reconnect or request permissions.
- [ ] Resize the launcher widget narrow/wide and taller. Verify **CONNECT → VIEW → OPEN**
  follows real connection state and **VIEW** does not start another USB session.
- [ ] Connect projection, open/close Hub and Settings repeatedly, and confirm there is no USB reconnect,
  stretched picture or duplicate audio. Exercise compact/full-screen transitions as well.
- [ ] Open the A/C panel and tap known phone controls near each visible projection edge.
  Check alignment after closing A/C, resizing and expanding the compact panel; letterbox taps
  must not activate a different phone control. Test while stationary, not during a drive.
- [ ] Request Reset Decoder in Settings, return to projection and verify one recovery with
  USB/audio retained. Start Reset Connection, change tabs/return, and verify it completes.
  Confirm Reset cluster host is unavailable when Android Templates Host is absent.
- [ ] Compare each available SYU field with factory indications. Disconnect/reconnect the vendor
  service and confirm stale values/door status become unavailable and controls disable.
- [ ] Verify unknown profile IDs cannot issue climate writes. On a supported actual profile,
  check existing A/C/fan/airflow actions cautiously while parked and compare factory feedback.
- [ ] Learn one delivered steering key; verify repeats trigger once and canceled/focus-lost presses
  do nothing. Confirm system volume, power, Home, Back and call buttons remain unaffected.
- [ ] Check all three profiles remain independent; importing restores the previewed settings
  without changing the active phone. Reject a malformed backup and confirm settings are unchanged.
- [ ] Test separate music/navigation gain only with genuinely separate adapter streams.
- [ ] Exercise factory camera/overlay return safely while stationary. Confirm the factory camera
  retains ownership and projection recovers. Check screen wake before and after pressing Stop.
- [ ] Confirm Vehicle readings uses TEYES/SYU only, with no OBD adapter or Nearby-device
  prompt. Disconnect the TEYES service: all affected values must become unavailable, with
  no external fallback and no invented coolant/voltage/trouble-code data.
- [ ] Configure an accessory shortcut, then disable/remove its app: launching must fail safely.
- [ ] Export health JSON and confirm it has no phone addresses, locations, routes or media titles.
- [ ] Treat cluster text/display behavior as unverified until visually checked on the actual vehicle;
  do not attempt image output or speculative CAN commands to make it work.

Native software-rendered UI fixtures are generated under `app/build/reports/ui/` by the
UI tests for the single Cabin build. They cover native
screens and test data; they are **not** captures of live CarPlay, a working CAN connection
or the user's actual head unit. Review these alongside the parked hardware checklist.

Software check: `./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

Verified on 2026-09-07 after the projection-first and TEYES-only telemetry changes:
284 tests passed per flavour, with no failures/errors/skips; TEYES lint reported 0 errors
and 37 warnings. The debug APK built and its v2 signature verified. Its packaged permission
list contains no Bluetooth/Nearby-device permissions. This is software verification,
not certification of the user's TPRO, projection adapter or Honda Civic installation.
A debug APK and passing software checks do not constitute vehicle compatibility certification.
