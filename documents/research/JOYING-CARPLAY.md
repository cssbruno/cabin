# Joying built-in CarPlay inspection

**Historical stock-client research.** The current implementation imports the
engine into Cabin. See [app-owned Carlink](CARLINK-INDEPENDENT-ENGINE.md). The
stock service and handoff descriptions below describe the earlier implementation.

Inspected the local `artifacts/joying-uis7862` firmware reference (2023-08-31) on 2026-09-13. This identifies the software in that image; it does not establish the firmware installed on the live head unit.

## Confirmed in the image

- APK: `extracted/applications/app/190000000_com.syu.carlink/190000000_com.syu.carlink.apk`.
- App label: **Car Link 2.0**. Package: `com.syu.carlink`.
- Version: `2.23.0712.1954`, version code `2123071219`.
- Launcher activity: `com.syu.carlink.MainActivity` (singleInstance).
- Exported service: `com.syu.carlink.CarLinkService`, with actions `com.syu.carlink` and `com.syu.carlink.CarLinkService`.
- Manifest specifies `android.uid.systemui` as shared UID and disables activity resizing.
- Bundled ARM64 native libraries include `libcps_7862.so`, `libcps_7862A12.so`, and CarPlay plugin libraries.
- `extracted/vendor-files/etc/init/hw/lsec.carplay.rc` defines a root `CarplayServer` service running `/system/bin/cps.sh`, with startup triggers involving `sys.fyt.carplay=1`. It also configures iAP2/NCM USB gadget functions for CarPlay.
- `extracted/system-files/system/bin/cps.sh` launches `/system/bin/CarplayService` with library paths belonging to `com.syu.carlink` and `com.syu.cpres`.

APK metadata and components were inspected using Android SDK `aapt dump badging` and `aapt dump xmltree ... AndroidManifest.xml`; scripts were read directly. No head-unit changes were made.

## Direct native interface

The exported Android `CarLinkService` returns an in-process Binder (`CarLinkService$a`), but this is distinct from the native **CarplayServer** registered with Android ServiceManager. The earlier launcher-only implementation was based on an incomplete inspection and has been replaced.

Verified client behavior, with disassembly retained in `artifacts/joying-uis7862/inspection/carplay/`:

- `f.a.run`: ServiceManager lookup of `CarplayServer`.
- `f.a.k/d`: interface token `CarplayServer.ICarplayService`, scalar integer arguments without an array count, transaction `(command << 8) | 2`, signed integer result (negative indicates failure).
- `f.a.m`: screen command 210 (`0xd202`), argument 3 to show video and 2 to hide.
- `CarplayFragment$touchHandle$1`: touch command 202 (`0xca02`), six integers representing two contacts: x, y, pressed for each.
- `c.j`: a `LocalServerSocket` named `/proc/mysocket`. This is the abstract socket namespace, not a filesystem pathname to create or modify.
- `f.g$a`: little-endian four-byte frame lengths followed by Annex-B H.264 payloads; stream reads can split or combine frames.
- `f.a.run` and `f.d`: native listener registration is transaction 3. Callbacks use `(notification << 8) | 1` and token `CarplayServer.ICarplayListener`. Notification processing includes Bluetooth, audio, phone state, and Wi-Fi coordination. Cabin now registers its own native listener after acquiring the video socket.

## Current embedded implementation

Cabin owns the projection UI and implements the following native client paths:

- A dedicated foreground service owns the session, Bluetooth, hotspot, and audio focus independently of the Activity. Home, pairing consent, and Activity recreation detach only the video surface. A parked decoder surface keeps video draining in the background. The notification provides an explicit disconnect action.
- Listener registration (transaction 3), callback decoding, native Binder death handling, and listener release. Retry retires the old session, and late status callbacks cannot replace the new session's state.
- Screen-size negotiation (command 218), orientation-aware bounds and eight-pixel alignment, wired auto-connect (223), initial phone-state query (219), and screen ownership (210).
- The video socket accepts replacement connections after EOF or I/O failure, flushing decoder reference pictures between streams. Length-prefixed H.264 reception, bounded ordered buffering, MediaCodec decoding to Cabin's SurfaceView, two-contact touch forwarding (202), and Siri press/release (208).
- Android Bluetooth discovery and bond requests in Cabin's phone picker, followed by the inspected RFCOMM UUID `00000000-deca-fade-deca-deafdecacafe`. Native Bluetooth identity configuration (226), `SV` connection notification in a PersistableBundle (229), incoming bytes (230), and outgoing callback bytes (111) are implemented. The local MAC is normalized to the stock twelve-hex-character form. Bluetooth names are bounded to the native 64-byte buffer.
- FYT toolkit module 2 subscriptions for remote MAC (6), phone state (9), hands-free cut state (13), local MAC (14), and local name (15). Validated factory identity is published through native command 226. The inspected `f.d` → `CarLinkService$c` callbacks map `_btcmd` prefixes `AT#SP` to RFCOMM reconnect, `AT#SH` to RFCOMM close, and `AT#CD` to `CarLinkService.g(true)`: module 2 command 13 with `[1]`. Cabin only cuts an active factory phone connection with a known uncut state and restores its owned cut with `[0]` on disconnect/cleanup. Raw external Bluetooth driver I/O remains in the firmware.
- Android 10 soft-AP configuration and tethering through the inspected ConnectivityManager/IConnectivityManager contract. Callback completion publishes actual channel/security/band/credentials through command 225. Session-created credentials are random and never logged. Cleanup restores previous Wi-Fi settings only while Cabin still owns the AP configuration.
- Native audio-state callbacks drive Android media, assistant, and call focus. Media focus changes use native command 216. Old listeners cannot pause newer sessions. PCM playback stays in the vendor native engine; Cabin does not create duplicate PCM playback.
- In-app **Settings → CarPlay** controls for wireless setup, phone selection, Retry, Disconnect and **Use Cabin for CarPlay**. Configuration is separate from the projection surface; there are no stock-app settings or restore shortcuts. Handoff uses existing firmware privileges to stop `com.syu.carlink` and request the firmware's `sys.fyt.carplay=1` startup trigger if necessary. The optional [ADB handoff tool](../../tools/joying/README.md) provides a separate shell-authorized handoff path. No root, flashing, hidden-API exemption, or SELinux modification is attempted.

Native Binder operations and cleanup run off the UI thread. A process-wide session gate prevents a retiring session from stopping a new session's native screen. No stock activity is launched by Connect. The stock APK's native libraries and firmware daemon remain installed and provide the licensed underlying engine; they are not bundled into Cabin.

## Deployment and validation boundaries

The implementation targets the inspected Android 10 native ABI. Firmware permissions still determine whether Cabin's UID can obtain the native service, own the abstract video socket, stop the stock package, read the real Bluetooth MAC, and use tethering. Declaring permissions in the manifest does not grant signature privileges. The ADB handoff tool only transfers ownership; it does not grant those permissions or establish app-side Binder access.

The Android Bluetooth adapter path and the inspected factory metadata/hands-free callbacks are implemented. A standalone replacement for an external Bluetooth MCU driver is not implemented; compatibility with that hardware variant still requires a live test of the installed daemon. The foreground service retains a session while the screen is away, and requests Android sticky-service recreation after process termination. Native daemon, startup and decoder failures trigger up to three retries after 2, 4 and 8 seconds per explicit connection/retry. Disconnect and service destruction cancel queued retries; callbacks from retired sessions cannot restart projection. After the budget is exhausted, the user can retry explicitly. Android controls whether and when a killed service is recreated; force-stop and reboot recovery are not provided by this change. Main controls and notification actions have English, Portuguese, Spanish, French, German, and Italian resources. Runtime diagnostic messages and some pairing notices remain English.

Initial geometry follows the stock 221 mm reference width, proportional height, and 30 FPS configuration. These are protocol defaults from the image, not measurements of the user's display.

Validation: Kotlin compilation, debug APK build, and focused automated tests for service lifetime across screen unbind, explicit stop/retry, stale callbacks/surfaces, factory identity and hands-free ownership, alongside existing tests covering video framing, native request/callback envelopes, Bluetooth/AP messages, geometry negotiation, audio-focus lifecycle, and Connect routing. This is code-level validation, not a successful physical CarPlay session. No APK installation, device handoff, pairing, or audio/video validation on the head unit has occurred.


### Rendered-video status ordering

A late native link-state transition could put “Waiting for CarPlay video” back
on screen after the decoder had already cleared it. Rendered-video state now
shares the decoder lock with status updates. Native link transitions request the
screen but only publish waiting status while no frame has rendered. Disconnect,
new stream and decoder flush reset that state. This corrects status ordering;
it does not establish that a firmware-denied or missing native stream works.
No physical Android device was connected during this check.


### Native listener registration result (2026-09-16)

The local CarLink reference's `f.a.run` treats transaction 3's first reply integer
as a boolean (nonzero = registered), then consumes the exception trailer. Cabin
previously accepted zero, confusing it with an ordinary command success code.
`JoyingNativeProtocol.registerListener` now rejects zero/negative registration
results after reading the trailer. Local Binder tests cover success/refusal.
Ordinary command status zero remains successful. This fixes a path that could
wait for callbacks after registration was refused; it is not proof that the
physical unit now streams video.


### Carlink routing and naming (2026-09-16)

The connection is labeled **Carlink** in all six UI locales and in session status
messages. Backend discovery probes the native `CarplayServer` Binder or the
installed `/system/bin/CarplayService` and `/system/bin/cps.sh` runtime files.
It no longer queries or requires the stock Android launcher activity. Settings
and Connect target Cabin-owned code; the stock-app settings redirect is removed.
If another client owns the video socket, the existing privileged release path
can stop that client. Denied ownership is reported inside Cabin.

This is an embedded Android client implementation, not a replacement native
CarPlay engine. The firmware daemon and its library dependencies described above
are still required. Removing the stock APK's engine libraries is not supported.
