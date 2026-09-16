# Embedded Carlink settings

Cabin owns the settings UI and preferences. Settings → CarPlay opens Carlink even
without a connected adapter. Settings → CCPA adapter owns all dongle configuration,
connection management, decoder controls and dongle projection preferences. The
change-device shortcut goes directly to CCPA connection management.

The projection toolbar has a filled, 56 dp minimum “CarPlay settings” button.
Settings are saved immediately; Retry creates a session with a fresh snapshot.

## Verified stock mappings

Inspected APK: Carlink 2.23.0712.1954, firmware UIS7862 Android 10, at
`artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.carlink/190000000_com.syu.carlink.apk`.

- `SettingsFragment`, `g.g`, `c.n`: FPS choices 20, medium 25 (firmware can override),
  60. Cabin includes 30 for compatibility with its previous fixed rate. Command
  218 carries the selected FPS after `0x53667073` in the display payload.
- Automatic wired connection: native command 223, one integer 0/1.
- Wi-Fi: Cabin stores actual Android AP band values (0=2.4 GHz, 1=5 GHz), rather
  than the inverted stock preference. The hotspot uses channels 6/36; command
  225 still reports the actual active AP configuration. Changes require reconnect.
- `android.telecom.i.onCheckedChanged`: microphone recording noise reduction
  writes `persist.lsec.cp.micLR`, None=0, Right=1, Left=2. Cabin performs the same
  firmware property operation and verifies readback. Rejected writes show an
  error and do not change the selected value. Firmware access is required.
- `CarMarkFragment`, `h.b`, `g.b`: 88 images, resource names `car_logo_000` through
  `car_logo_087`. These are copied unmodified from the APK resource-table paths
  into `app/src/main/assets/carlink/logos`. No stock app resources are loaded at
  runtime. Cabin copies the selected image to its private `files/carlink/logo.png`.
- `c.m.b`: logo command 213 writes nullable label, image path, width 180, height
  180, in that order. This differs from generic integer-first native commands.
  Default logo skips the override on the next fresh engine instance.
- Version identifies Cabin's build and the imported Carlink version separately.

Validation: unit tests cover preference persistence, FPS payload, band/channel
mapping, logo parcel order, decoding all 88 assets, native/CCPA navigation, and
localized resource parity. Actual projection, firmware property permissions and
5 GHz hotspot support still require a test on the head unit.
