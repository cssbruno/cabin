# App-owned Carlink engine

Carlink now imports the user-authorized native libraries into Cabin’s APK. Cabin
creates the receiver object in its private `:carlink` process and receives its
Binder directly through a non-exported Android service. It does not launch or
bind to `com.syu.carlink`, look up/register a global `CarplayServer`, start
`cps.sh`, set `sys.fyt.carplay`, or force-stop the stock client. The existing video,
touch, Bluetooth, wireless and audio-focus implementation uses this owned engine.

## Imported ABI and files

This import is restricted to ARM64 Android 10. The bridge uses the inspected
`libcps_7862.so` constructor, a 440-byte receiver allocation, BBinder offset 8,
and RefBase offset 424. The original factory instructions establishing those
values are retained in [the ABI disassembly](CARLINK-ENGINE-ABI.txt). The bridge
retains the native object and wraps its Binder using the platform's
`javaObjectForIBinder` entry point. It deliberately does not invoke the factory
function, which registers the global stock service.

Ten engine/support libraries are committed under `app/src/main/jniLibs/arm64-v8a`.
Their original hashes, imports and precise string patches are pinned in
[`tools/carlink/libraries.json`](../../tools/carlink/libraries.json). Every build
verifies the imported bytes. Re-import from the local firmware reference using
`python3 tools/carlink/import_libraries.py`; verify without that reference using
`python3 tools/carlink/import_libraries.py --verify`.

The plugin's abstract video socket is changed from `/proc/mysocket` to
`cabin.carlink`. Its keychain paths become relative `KeyChains` paths, and the
private engine process changes working directory to Cabin’s `files/carlink`
directory before loading native code. These length-preserving patches do not
move instructions, relocations or object layouts. The application label is
Carlink across all six connection UI locales.

## Lifetime and errors

Binding alone does not create the receiver. A separate IPC request initializes
one receiver per engine process, off the main thread. Startup has a ten-second
binding deadline and a fifteen-second native initialization deadline. Errors
return through IPC and session diagnostics. Session cleanup unbinds; the private
service terminates its own process to retire all imported native threads. Cabin’s
UI process survives engine failure. Retry creates a fresh runtime. There is no
fallback to the stock daemon. The foreground projection service continues to
own Bluetooth, wireless, audio focus and screen detachment/re-attachment.

## Platform requirements and validation limits

Independent of the stock **app/service** does not mean independent of Android’s
platform and hardware. The imported binary uses system `libbinder`, `libutils`,
`libcutils`, `libc++`, `libaudioclient`, `libaudiomanager`, `libaudioutils`, and core
C/math/log/dl libraries. These are not copied into the APK: duplicating Android’s
Binder/audio runtime would make the JNI Binder interface unsafe. Native-loader
visibility and private ABI compatibility therefore remain firmware requirements.
Android’s isolated classloader namespaces can restrict private-library loading;
see [AOSP native-loader implementation](https://android.googlesource.com/platform/system/core/+/android-8.1.0_r1/libnativeloader/native_loader.cpp).
No namespace bypass, permission grant or firmware modification is performed.

The engine still needs the head unit’s authentication device, USB setup and audio
hardware access. The import does not bypass authentication or replace kernel
interfaces. Only the inspected Android 10 ABI is advertised as available, based
on packaged libraries, not stock-package installation.

Validation: all three app JNI build targets compile; the ARM64 APK entries match
all ten expected hashes; the old socket and keychain paths are absent from the
imported libraries; focused engine IPC, error propagation, lifecycle, protocol,
backend-routing and UI tests pass. No physical Android device is attached over
ADB, so native loading, authentication, pairing and real audio/video playback
remain **unverified**. This is an integrated build candidate, not a proven
working CarPlay session on the head unit.
