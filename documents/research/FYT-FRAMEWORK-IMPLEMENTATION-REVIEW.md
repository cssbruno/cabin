# FYT framework implementation review

Reviewed 2026-09-12 against the Cabin working tree. Implementation status below records the follow-up changes.

Source: `artifacts/fyt-framework/framework.zip`, downloaded from vasyl91/FYT-Launcher-Mod,
SHA-256 `e263003dd18e3d1d90759d4d7f33c04064991f7671fd656596745fb022b8aa99`.
This is a reference framework, not a firmware export from the user's head unit.
The archive includes a nested `classes.dex/sources/android.zip`, inspected without extracting it.

## Priority 1: match the Binder contract

`classes3.dex/sources/com/syu/ipc/IRemoteModule.java` defines synchronous transactions:
cmd=1, get=2, register=3, unregister=4. Its proxies pass a reply Parcel and flags=0,
then call readException. `IModuleCallback.java` uses the same synchronous reply pattern.

At review time, Cabin's SyuAudioSourceMonitor and TeyesClimateController used FLAG_ONEWAY
with no reply for module subscriptions/commands. Their update callbacks returned without
writing the success exception header. The old fake Binder tests used the same one-way
assumptions and therefore did not verify this reference contract.

Implement a shared bounded Binder transport matching the synchronous protocol on a
background thread. Acknowledge valid callbacks, including safely ignored fields; parse
and enqueue state updates before returning, without blocking on the worker. Add tests
using a faithful synchronous vendor proxy/stub, remote exceptions, disconnects, and
cached callbacks during registration. Keep lifecycle ownership and retry checks.

This is a confirmed contract mismatch with this archive, not proof of the cause of every
reported head-unit issue. Test on the target firmware before claiming compatibility.

## Priority 2: implement typed readback support

IRemoteModule.get returns a nullable ModuleObject containing int[], float[], String[],
in that exact order. Add bounded decoding and protocol tests. Only use readback for
module-specific get codes independently verified in the target service/app; this archive
does not define all valid get codes or DSP parameter meanings.

## Priority 3: investigate framework BSP events separately

FinalBsp defines audio status=0, button event=2, stream volume=3, reverse=4,
touch status=5, button feedback=6, gesture=7, audio focus=8, touch volume=9.
RemoteModuleBsp implements callback registration; its cmd is empty and get returns null.
Only reverse field 4 is explicitly replayed on registration, as a string array.
Do not confuse its BSP module 0 with the installed ModuleService MAIN module 0.

AudioManager, AudioTrack and MediaPlayer obtain a system Binder named `syu` via
ServiceManager. This differs from Cabin's bound com.syu.ms toolkit service. Verify
endpoint access, registration, event producers and payloads on the target before adding
BSP subscriptions. Constants alone do not prove app accessibility or payload meaning.

Potential user features after that verification: physical-button handling beyond foreground
Android KeyEvents, reverse-state integration, and audio-focus diagnostics. Preserve existing
key routing and avoid duplicate key actions.

## Priority 4: audio integration, not duplicate framework notifications

AudioManager already calls sendToSyuServiceAudioInformation(8, [focus, gain, usage], null,
[package]) and uses ismapapplication/procName. Review Cabin's ordinary Android audio-focus
usage first. Do not blindly send the same vendor notifications again from the launcher;
this firmware may already do so. Test navigation prompts, radio ducking and CarPlay audio.

## Not supplied by this archive

No complete DSP coefficients/control implementation, MCU serial framing, raw CAN driver,
or universal sound-module command map was established. RemoteModuleBsp's empty command
method is particularly not a hardware implementation. Native DSP work still requires the
matching service/native libraries and hardware validation.

ActivityView source is present in the nested android.zip. App embedding is a separate,
lower-priority feature requiring privilege and firmware compatibility checks; it is not a
fix for Cabin's own portrait widget layout.

## Existing code to retain

Cabin already has profile-aware CAN/climate telemetry and controls, vehicle/factory widgets,
Android volume controls, SYU source monitoring, configurable factory-app shortcuts and
foreground key mapping. Improve these existing paths rather than creating duplicate widgets
or presenting factory DSP app launching as native DSP support.

## Implemented follow-up

- Shared synchronous Binder transport now reads remote exception headers and bounds parcels.
  CAN/climate and MAIN audio subscriptions use it; valid callbacks acknowledge the sender.
- Hardware Lab simulator/client tests now use the same synchronous callback contract.
- Typed transaction-2 readback helper and bounded ModuleObject codec are implemented. No
  unverified get code is executed or exposed as a widget control.
- The existing health report now checks access to the separate system `syu` endpoint on
  demand. It records only an access enum and marks BSP event support UNVERIFIED. No raw
  button, reverse or package payloads are exported. The check has a 1.5-second result
  deadline and reuses any outstanding task rather than accumulating blocked threads.
- Audio focus applies to media, navigation, calls, Siri and alerts. Denied/delayed focus
  mutes local playback; old abandoned callbacks cannot change the new session's gain.
  Standard navigation speech/ducking attributes remain in use, with no extra vendor notify.

Remaining evidence: the archive does not establish accessible BSP event producers/payloads
for this head unit or native DSP command meanings. Hardware button actions and reverse-state
controls through BSP remain unimplemented pending that evidence; the endpoint presence check
must not be presented as event support. No firmware or privileged service replacement is made.

Validation: affected platform/audio suites, launcher and TEYES presentation regressions,
Hardware Lab unit tests, and Cabin assembleDebug passed on 2026-09-12. Readback string
wire tests use a scoped UTF16 Parcel shadow because Robolectric 4.14.1 has no native Parcel
mode. No head-unit hardware test or GitHub release was performed.
