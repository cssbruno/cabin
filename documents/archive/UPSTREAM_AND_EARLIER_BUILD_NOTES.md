> Historical reference. This document describes an earlier release, not the current app.

# Archived upstream and earlier build notes

Historical reference: the distribution flavors described below have been removed.
Use the root README for current Cabin build instructions.

# Cabin

Cabin is a modular Android car launcher with live CarPlay/Android Auto projection,
TEYES vehicle widgets and six interface languages. The Gradle project is `cabin-native`.

This project builds on [Carlink by lvalen91](https://github.com/lvalen91/carlink),
a native Kotlin implementation derived from the original
[Flutter app](https://github.com/lvalen91/carlink_flutter). Upstream links and the
historical platform notes below retain their original attribution.

The source namespace is `com.cabin`, and the repository folder is `cabin`.
The Android application ID, external navigation IPC interface, preference keys and backup format stay
unchanged for compatibility with existing installations. GitHub `origin` still points
to upstream; the Cabin release repository has not been configured.
See [rename compatibility details](../RENAMING.md).

### Requirments

- For AAOS 10 (Android 10) and higher
- For (Carlinkit CPC200-CCPA)[https://www.carlinkit.com/ccpa] on firmware 2025.10


## [XDA Developer Forums](https://xdaforums.com/t/carlink.4774308/)


## Work In progress - Something is always changing.. Not always good

> [!IMPORTANT]
>
> Limitations
> Instrument Panel Cluster and Heads Up Display (HUD) Support
>
> Song Information:
> Due to an AAOS bug. Song metadata displaying on the Cluster can go blank. The simplified explanation, the App creates a session 'token' and hands it to the OS. The Native Media Player and the Cluster both read song info from it. The boot/auto-launch, head unit reboot, and adapter config-change cases have been fixed, so those now repopulate the Cluster card correctly. The one case still left is force-stopping the app and relaunching it: the OS-side CarLauncher controller is nulled when the session is destroyed and never rebound (an AOSP limitation), so the Cluster keeps holding the dead controller and goes blank. There are no known ways for third-party apps to fix that last case. Emulator testing shows Apple Music and Spotify hit the same stale card, confirming it's a platform limitation, not app-side.
>
> Navigation Turn-By-Turn
> On STOCK adapter firmware, the firmware strips away the rich nav details iPhones provide and narrows it down to basic manuvers. The custom adapter firmware (see below) restores full iAP2 route information, so CarPlay now gets complete maneuver/route data. CarPlay itself does not send ready-made navigation icons, so the app generates them on-device from the recovered iAP2 geometry (including correct roundabouts drawn from the real junction arms). Android Auto does provide manuver images, which are forwarded to the cluster. They are more detailed and accurate.
>
> Vehicles running the GM VCU radio (Bosch VCUNH1 — most EVs and newer Fuel/ICE vehicles) on GM AAOS 14+ do not use any icon provided by the app. GM VMSPlugin still passes them, but the Cluster ECU ignores it and renders from its own internal icon set based on the manuver type (VMSPlugin's setManeuverType() enum forces the GM glyph). Earlier VCU vehicles on AAOS 12 or 13 may have behaved differently before being upgraded.
>
> Cluster Navigation Icons for forks / other developers (gminfo3.7):
> This mechanism only does anything useful on the **gminfo3.7** platform (Info 3.7, AAOS 12, e.g. my Silverado). The hook itself (the unregistered authority) is firmware-verified to be open/claimable on the newer **GM VCU** platform too (Bosch VCUNH1 — the EV and newer-ICE radios on AAOS 14, which ships the same unmodified Google Templates Host), but on VCUNH1 it is **bypassed**: GM's VMSPlugin force-renders the cluster glyph from a maneuver-type enum (setManeuverType), so the app's icon is masked regardless of who owns the authority (see the GM VCU note above). The way the app gets its own maneuver icons into the gminfo3.7 cluster is by claiming a content provider 'hook' (the authority `com.google.android.apps.automotive.templates.host.ClusterIconContentProvider`) that GM's Templates Host references but never registers — GM leaves it open/unclaimed. The app registers a provider on that authority so the cluster's icon calls land on it and get the forwarded Android Auto maneuver bitmaps. Because I was the first developer to upload a bundle claiming that authority, Google reserved it to me — Google enforces that a content provider authority is globally unique and locked to its first publisher. The catch is for everyone else: because GM exposes only that ONE open hook and I already claimed it, no other developer's bundle can claim the same authority — Google rejects it on upload. The code is left in place so another dev can still build and ship from this repo with one change: a fork changes only its `applicationId` and the `play` flavor automatically derives a unique authority (`<applicationId>.ClusterIconContentProvider`) to get past the Play Console check. But GM AAOS only ever calls the real GM authority, so that derived provider is never invoked on the head unit — it lets a fork upload, it does not actually deliver icons to the cluster. Net result: a fork can publish, but without the GM authority the cluster shows text navigation only — no maneuver icons at all.
>
> My truck doesn't have an HUD, so i cannot test this. However, some Silverado and Hummer EV users have reported that the HUD does show navigation. I can only test for the software in my Silverado. GM and others can easily change how that is controlled so if it works, great. If it doesnt.. too bad.

> [!TIP]
> Before complaining about Audio issues.
> 1. Disconnect and forget the Phone and Vehicle from each others Bluetooth. The adapter defaults to audio routing THROUGH the adapter for both microhone input and audio output. Make sure you allowed microphone access to the app.
> 2. Steering Wheel Voice/Call Control doesn't work. That is a system-level app featuer and this is not that. If you want Steering Wheel Voice Controls, then ignore #1 and set the in-app adapter audio routing to Bluetooth. THe Phone and Vehicle will stay connected for ALL audio related events.

> [!IMPORTANT]
>My 2024 Silverado gminfo3.7 Intel AAOS radio is the target Platform and my only hardware for testing.

> [!WARNING]
> *Compatability on anything else is not verified* and should be treated as **untested**. Optimized for Video and Audio performance on the gminfo3.7. That is **my only focus** you can fork this repo and optimize it for your own needs.

> [!TIP]
Remember kids: (mostly me)
>
>Projection streams are live UI state, not video playback.
Do not buffer, pace, preserve, or “play” frames.
Late frames must be dropped. Corruption must trigger reset.
>
>CarPlay / Android Auto h264 is not media.
It is a real-time projection of UI state.
Correctness is defined by latency, not completeness.
Buffers create corruption. Queues create lies.
>
>Video is a best-effort, disposable representation of UI state.
Audio is a continuous time signal that must never stall.
Video may drop. Audio may buffer. Neither may block the other

> [!IMPORTANT]
> Adapter-SIde Binaries were patched to correct to problems and require use of custom firmware on the adapter itself.
> 1. Carlinkit Stripped away rich iAP2 navigation data rcvd from iPhone, that has been corrected in custom firmware. Full iAP2 route information is now forwarded and rcvd.
> 2. Carlinkit AndroidAuto incorrectly parsed rcvd Vehicle GPS NMEA when app forwarded to Phone. That is now patched so correct and accura NMEA is rcvd by Android Phone.
>
> Custom adapter firmware for complete/correct Carplay Route information and Correct GPS-Forwarding support on Android Auto. While not necessary to have, it provides a better and closer to stock experience if you do load.

```
Video:
- Represents live UI state
- Late == invalid
- Drop aggressively
- Reset on corruption
- Never wait

Audio:
- Represents continuous time
- Late == fill
- Buffer aggressively
- Never stall
- Never block video
```


> [!IMPORTANT]
> My Primary smartphone is an iPhone and therefor Carplay as gotten the most tuning and testing. A Google Pixel 10 is used for testing basic functionality, cannot do real-world 'Day to Day' testing.

## Screen Shots from Android Emulator with USB-PassThrough for CPC200-CCPA Use


## Main App UI/Page

### Projection first

Live projection defaults to a single **Tools** button. It opens voice assistant,
previous/play-pause/next, **Recover picture**, Settings and available TEYES controls.
Recover picture refreshes video without requesting a USB/phone reconnect. Compact
projection also keeps **Full screen** and **Close** available.

Open **Settings → Control → Projection experience** to change these app-wide choices:

- **Focus view: on.** Keeps extra projection buttons inside Tools.
- **Vehicle speed HUD: off** on TEYES. Enable it for available fresh vehicle readings.
- **A/C notices: Summary** on TEYES. Brief notices reserve no video height. Open
  **Tools → A/C** manually for full controls and proportional picture fitting; automatic
  panel notices or no automatic notices are optional.
- **Return when ready: on** on TEYES. A recent explicit **Connect phone** from the Hub
  can return to projection only while that Hub remains foreground and focused. Settings,
  permission dialogs/focus loss or backgrounding cancel the return; automatic reconnects
  never pull you away.

**Connect phone** also resumes a saved user-requested phone pause. **Connection help**
on the connection screen shows observed USB/session status and optional microphone/GPS
permissions. Refresh only rechecks: it is not signal-quality measurement or automated
troubleshooting. Schema-2 settings backups now include these presentation choices.

## Setup, checks, language and units

Open **First-time setup** on the connection screen or **Setup guide** in Phones/Connection
help. The five steps cover USB, phone pairing, audio routing, optional permissions and
completion. Progress is saved and the guide can be reopened. Audio choices are saved for
the next adapter connection; opening/completing the guide does not restart projection.

**Connection help → Audio and GPS checks** adds explicit microphone, stereo-speaker and
fresh GPS tests. Confirm that you are parked and use **Stop session for audio tests** first.
The microphone shows input level/clipping for five seconds; no recording is saved.
Quiet left/right tones check the current Android media output without changing system
volume. GPS waits up to 22 seconds for a new fix and displays its reported accuracy,
without retaining coordinates. Stop, leaving the panel, focus loss, backgrounding or a
new projection session cancels checks. These checks exercise Android's devices; they do
not certify the phone's audio/GPS route or vendor hardware. Optional permissions have a
direct link to Android app settings.

The native interface includes English, Portuguese, Spanish, French, German and Italian.
Choose **Settings → Control → Language**, or **Follow system**. Translations work offline;
Android 13+ also offers these choices in its per-app language settings. See
[language coverage and validation](../LANGUAGES.md).
CarPlay/Android Auto language remains controlled by the phone. **Settings → Control →
Projection experience → Measurement units** selects System, Metric or Imperial
independently from language. This affects native speed, distance and maintenance displays,
plus forwarded navigation presentation; raw vehicle data and the phone interface are unchanged.

## CarPlay Launcher Widget

Cabin includes an optional home-screen widget for AAOS launchers that provide an
Android `AppWidgetHost`. It shows the live adapter/projection status, distinguishes
wired and wireless CarPlay, and opens live projection in a compact panel over the
launcher with one tap. The panel has **Full screen** and **Close** controls. Tapping
**Connect** starts USB, audio, media metadata, controls, microphone, and GNSS in a
foreground service without opening the Activity. Projection video is intentionally
dropped while headless; opening Cabin attaches its display surface to the same
service-owned USB session. Closing or swiping away the Activity returns that session
to headless mode instead of disconnecting it.

The widget adapts to a stacked narrow layout or a wider status layout, and supports
horizontal and vertical resize when the launcher allows it. Its large action changes
from **CONNECT** while disconnected, to **VIEW** during connection progress, to **OPEN**
when projection is live. **VIEW** opens progress without requesting a second connection.
A taller wide widget can show two status lines; the compact layout keeps its title/action
and exposes status through accessibility descriptions.

Android widgets use `RemoteViews` and cannot contain the `SurfaceView` required by the
hardware video decoder. For that reason the live picture is displayed by the compact
panel directly above the widget/launcher, rather than inside the widget's pixels.

Microphone and GNSS continue headlessly only when their runtime permissions were
already granted from the main app. Android may reduce a system-restarted service to
USB/audio operation until the user opens Cabin again.

Android requires an ongoing notification while the background session is active. Use
its **Disconnect** action to stop the adapter session and foreground service.

Some vehicle launchers do not expose third-party widgets. On those systems the widget
receiver remains inert and does not change Cabin's normal launcher or CarPlay behavior.

### TEYES TPRO / Honda Civic G10

Build Cabin for original TPRO units (`./gradlew assembleDebug`). It
supports Android 8.1/API 27 and does not require the certified-AAOS features that block
installation on conventional Android head units. Projection remains the default screen;
tap **Tools → Vehicle Hub** to open the native Cabin dashboard, or **Open Hub** while
connecting. The hub overlays the existing projection surface and keeps the same USB
session. It includes source-labelled vehicle readings,
now-playing controls, fresh phone guidance, accessory shortcuts, connection history and
a privacy-limited health-report export.

#### Native hub and controls

The refreshed UI uses a consistent teal/mint day/night theme, large readings and 56 dp
minimum compact action targets. The Hub adapts to one, two or three columns; its header
scrolls on short, narrow or enlarged-text displays. A clear connection button leads to
projection, followed by music, climate, navigation and configured app shortcuts.
**System health → Health & compatibility** expands on demand; vehicle readings use TEYES/SYU only.
Missing readings stay unavailable; the redesign does not infer new hardware support.

Settings uses a side rail on wide displays and scrollable tabs on narrow ones. **Back**
keeps the session running; **Exit app → Stop and exit** stops connections before closing.
The compact projection and climate controls remain scrollable/reachable at short heights.

Cabin's **Tools → A/C** opens its own bottom climate panel, not the factory app. The
default automatic notice is a quiet summary that does not reserve or resize video height.
Opening the full panel aspect-fits projection into the remaining height instead of
stretching it. Available status comes from the exported `com.syu.ms` service;
missing/stale values are not treated
as current measurements or “off.” Climate writes are restricted to numeric profiles
`262465`, `1048874`, `1114410`, `196906` and `262442` with fresh required fields.
The latter four use the documented 0298 Civic A/C, fan and airflow interface;
on-device validation is still required. Other profiles are read-only. A factory profile
label such as `0298` does **not** establish its numeric binder ID or command compatibility.
No new CAN commands or direct CAN access are introduced.

For Civic 0298 variants, **Settings → TEYES → Vehicle readings → Vehicle data layout**
keeps **Existing firmware (speed/RPM)** by default. The optional **Civic 0298 reference**
layout supports reference A/C, door and signed oil-service-distance fields without mixing
firmware dialects. Its speed/RPM scaling and temperature encoding are not verified, so
those readings remain unavailable in that layout. Coolant, voltage and oil-life percentage
are not newly enabled. Test a layout change while parked; CarPlay is unaffected.

**Reset Decoder** queues video recovery while Hub/Settings covers projection, and applies
it after returning to a valid visible projection surface. **Reset Connection** continues
even if you switch tabs or return to projection; explicit Stop still wins. **Reset cluster
host** requires Android Automotive Templates Host and is unavailable on ordinary TEYES.
It does not reset or reprogram the Honda instrument cluster.

**Tools → Screen off · keep audio** blacks out Cabin without disconnecting USB or
removing the projection surface. Tap or Back wakes it; this does not switch off the
head-unit backlight. **Settings → Projection experience** selects left/right Tools placement.
Hub health now includes bounded connection-event snapshots and return timing. Reports
show an exact privacy preview before saving; no automatic upload takes place. File logs
are pruned on enable/rotation to seven days,20 files and approximately100 MiB, protecting
the active file. Save important logs before they age out or exceed these limits.
The [60-feature delivery tracker](../FEATURE_ROADMAP.md) distinguishes delivered,
partial and unimplemented features; the full roadmap is not yet complete.

The TEYES build also mirrors CarPlay/Android Auto title, artist, album, and playback
state through Android's legacy music metadata notifications for original Android 8.1
firmware. Notifications are restricted to installed TEYES/SYU packages. The vendor
Honda Civic CANBUS profile remains responsible for any final instrument-cluster write;
actual Civic/TPRO output has not been hardware-verified. Cabin conservatively limits
each mirrored text field to 15 Unicode code points.
When cluster navigation sync is enabled, active guidance temporarily replaces those
fields with a bounded turn instruction, road name, maneuver distance, and ETA; music
metadata is restored when navigation ends. Control characters and bidirectional text
overrides are stripped before anything reaches the vendor bridge. Civic G10 profile
0298 has no verified bitmap transport in this implementation, so it deliberately does not
send album art, maneuver images, guessed CAN frames, or arbitrary binder commands.

Use the factory CANBUS profile specified for your installed decoder and Civic variant;
do not change factory configuration merely to make a Cabin control appear. The GM/AAOS
cluster mechanisms described elsewhere in this README do not establish Honda support.

#### TEYES features settings

Open **Hub → Settings → TEYES** in the `teyes` APK. Confirm that you are parked before
configuration. This supplementary UI guard is not a vehicle safety interlock; unknown
speed is not proof that the vehicle is parked.

Driver profile, Appearance, Audio and **Vehicle readings · TEYES** are grouped sections.
Expand Preferred phone, Camera & wake recovery, Steering-wheel shortcuts, Accessory app
shortcuts or **Backup & restore** for their detailed controls. Use **Save backup** or
**Restore backup** inside the latter; restore still requires a validated preview and confirmation.

- **Three driver profiles:** names, preferred paired wireless phone, launch layout,
  appearance, music/navigation gains and recovery preferences. Profile changes do not
  switch the active phone. The preferred phone is used on the next adapter connection;
  select “Adapter default” if that phone is unavailable. Forgetting a phone clears it
  from all profiles. Compact layout applies on an ordinary cold launch; explicit widget
  open/expand actions take precedence.
- **Appearance:** manual day/night or follow Android's night setting, with 10–100%
  brightness for Cabin's own window at night. Projection night-mode commands are
  reapplied when streaming reconnects. This is not a verified headlight/ACC interface;
  automatic headlight linkage requires firmware that updates Android's night setting.
- **Audio:** Android media volume/mute plus independent attenuation of adapter music
  and navigation streams. Existing audio-focus ducking is preserved. Calls, assistant
  and alert streams are untouched. These gains cannot affect Bluetooth-direct audio
  or separate prompts already mixed into the music stream.
- **Factory overlay recovery:** brief focus-loss debounce pauses decoding without
  disconnecting USB/audio, and resumes it on return. The factory app owns reversing
  cameras; Cabin does not open cameras or take over reverse gear. Firmware overlays
  that change neither focus nor Activity lifecycle cannot be detected by this feature.
- **Screen-wake recovery (off by default):** an existing foreground service retries
  a disconnected, user-requested session on screen-on. It respects Stop, coalesces
  starts, does not launch an Activity, and cannot guarantee resumption after process
  termination, cold boot or physical power loss.
- **Steering-wheel learning:** a 15-second learning window maps delivered media,
  search, function and programmable keys to play/pause, next/previous, phone assistant
  or the custom A/C panel. Repeats are consumed; an action occurs once on release.
  Canceled/focus-lost presses do not execute. Power, volume, Home, Back and headset/call
  keys are not captured. Vendor-intercepted keys cannot be remapped here; existing
  MediaSession controls remain the background playback path.
- **Accessory app shortcuts:** choose an installed exported launcher activity for
  EQ/DSP, TPMS and dashcam. Shortcuts survive restart and fail safely if an app is
  removed, no longer a permitted launcher, or access is denied. App presence is not
  evidence that sensors work.
- **TEYES vehicle data only:** speed, RPM and oil life come from the existing TEYES/SYU
  subsystem when its profile publishes fresh supported fields. No external OBD adapter,
  Bluetooth pairing, ELM327 connection or OBD fallback is used. Coolant, ECU voltage and
  trouble codes remain unavailable because no verified TEYES interface is implemented.
  The old OBD shortcut is hidden; saved configuration is not erased.
- **Settings backup:** export/import three profiles, learned buttons, accessory app
  shortcuts, projection presentation choices and display units through Android's file picker. Imports validate a bounded versioned JSON
  document, show a preview and require confirmation. New exports use schema 2; old schema-1
  backups retain current projection preferences and units when restored. The selected driver slot is kept;
  importing does not switch phones, launch apps or change vehicle firmware. Backups
  contain profile names and preferred phone addresses—keep them private.

**Still hardware-dependent or unsupported:** in-app TPMS values, additional OBD PIDs,
direct DSP presets/DVR recording controls, raw ignition/headlight signals and Honda
cluster images. Vehicle speed/RPM/oil-life depend on what the selected SYU profile
actually supplies. Firmware that sends only change events may let unchanged readings
age out; Cabin then shows them as unavailable rather than assuming they remain valid.

Validation: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
Native UI test fixtures are generated under `app/build/reports/ui/`. They are software
renders, not live vehicle/CarPlay proof. Actual TPRO/Civic hardware testing remains required,
including short-landscape/large-font layouts, widget resizing, A/C projection aspect ratio
and touch alignment after resizing.
See [TEYES ecosystem setup, APIs and parked acceptance checklist](../TEYES_ECOSYSTEM.md).
A successful debug build is not a vehicle-hardware compatibility certification.

## Adapter Configuaration Options

These options can be set for user preferance, but will require an adapter reboot upon tapping 'Apply & Restart'


## App specific Setting

Controls what is hidden or shown to allow more space for the Cabin app to configure and render the Projection UI Stream.


### App Logging to File Export

If enabled allows exporting app logs to a file. Uses the createDocument function so the native android documents app (files) must be installed. THis bypasses the need for the app and third-party file browsers needing permission to access various folders. You can save directly to an attached USB.

> [!CAUTION]
>OS restrictions will apply.


### Log Levels

Due to how verbose and active this app can be. Espically regarding troubleshooting (the more the information the easier to diagnose). Various log levels are available to help narrow down and focus on the needed areas.


# Documentation

### Native Android launcher

The TEYES APK includes **Cabin Home** with fixed dashboard pages and movable, resizable modules—including the actual live CarPlay surface. Add media, navigation, built-in CANBUS gauges, doors, climate, clock, and Android widgets. Apps and launcher settings use pagination. Open **Edit layout** to customize modules, or **Page switcher → Settings → Choose default Home** to select the launcher.

Joying and other compatible Android units can use the standard launcher and installed factory-app shortcuts. Direct vehicle integration depends on verified firmware support. See [launcher setup and compatibility](../LAUNCHER.md).

I, or mostly CLAUDE, have tried to collect and organize as much documentation as I can in regards to every aspect of this app, adapter, gminfo etc. To not only help me better understand, but others as well. If updates come across without code changes. It's likely new documentation or corrections.

> [!IMPORTANT]
> I cannot speak for all information to be accurate and free of errors, but its the most detailed and centralized source of information you will likely find anywhere else. Unless you have direct access to the source code of the Adapter itself, GM Radios etc... If you do, i know a guy and a site who will glady take it and publish it anonymously.

Most of your questions are likly answered in [Cabin Documents](/documents/reference/), but reach out on the XDA Forum. Issues use github to report it or the forum as well.

# Other Repos that started this gravy train, provided insights/inspiration. And Helped a lot.
# Check them out

- [Carplay by Abuharsky](https://github.com/abuharsky/carplay) - Original Android implementation
- [Node-Carplay by Rhysmorgan134](https://github.com/rhysmorgan134/node-CarPlay) - Protocol reverse engineering
- [LIVI by f-io](https://github.com/f-io/LIVI) - Linux (Raspberry Pi) and macOS implementation
- [PyCarplay by Electric-Monk](https://github.com/electric-monk/pycarplay) - Python implementation
