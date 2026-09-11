# Cabin Home

Cabin is an Android Home launcher. Install the APK, open Cabin, and use **Settings → Launcher → Default launcher → Set Cabin as default launcher**. Select **Cabin Home** in the Android prompt. Android 10+ uses the system Home-role request; older or customized head units fall back to default-app settings. The status updates when you return, including if you cancel. Once selected, Android routes Home to Cabin and starts it as the Home screen after normal boot. Both app icons open the dashboard. Use **Change default launcher** to switch back. Firmware that locks its Home app may require a manufacturer setting; Cabin does not disable factory apps.

The driving dashboard uses one compact header, icon controls, page indicators and
charcoal cards with a soft blue accent. Empty media and navigation modules use icons;
missing vehicle readings use a dash. Passive helper paragraphs are hidden from the
driving dashboard, while screen-reader labels and explicit setup/error messages remain.

## Included

- Searchable installed-app drawer with icons and app information.
- Up to eight ordered favorites per driver profile. Use Manage to pin, unpin, or reorder apps.
- Up to three Android widgets, with Android binding/configuration consent, removal, and vertical resizing for compatible providers.
- Live, resizable CarPlay/Android Auto module plus full-screen projection.
- Live projection track information, playback controls, voice activation, and fresh navigation guidance.
- Existing TEYES vehicle dashboard, supported climate controls, steering-wheel integration, themes, and settings.
- English, Portuguese, Spanish, French, German and Italian UI with an in-app language picker, measurement preferences, fixed controls and a compact layout for short screens.

Home adopts the existing projection manager and background session. A cold Home launch does not automatically connect; use Connect to request a session. Opening the projection screen from Home also preserves that choice. The dashboard embeds the actual native projection surface, while other modules show phone metadata and built-in vehicle readings. Projection still requires the supported adapter and phone setup.

## Hardware compatibility

| Platform | Integration |
| --- | --- |
| Supported TEYES/SYU firmware | Existing verified vehicle telemetry and climate profiles, plus installed factory-app shortcuts. Availability depends on firmware and CANBUS configuration. |
| Joying and other Android head units | Standard Android Home, installed-app discovery, widgets, and supported adapter projection. Pin exposed radio, DSP, Bluetooth, or camera launcher activities. Direct proprietary vehicle commands are not implemented without a verified model/firmware contract. |

The aftermarket APK requires Android 8.1 or later. Firmware may restrict replacing its default launcher or hosting widgets. Factory functions that do not expose a launchable activity will not appear in the drawer. Real head-unit validation is still required, particularly sleep/wake, audio focus, CANBUS behavior, widget providers, and reverse-camera takeover.

Known movement disables app browsing, app launch, and customization through the existing driving guard. Unknown vehicle speed is not proof that the vehicle is parked.

Favorites are stored per driver locally. Widget IDs and sizes are device-local; launcher layout is not included in configuration backup schema 2. Removing a widget releases its Android host ID.

To extend direct Joying integration, record the exact head-unit model, Android/firmware versions, vehicle, and CANBUS decoder. Do not reuse SYU write commands on an unverified platform.

## Swipe navigation

Pages are ordered **CarPlay → Main menu → Apps → Settings → Widgets**. Tap the floating icon in the upper-right corner to choose a page, or swipe the icon horizontally. There is no bottom navigation bar. Use the floating switcher to leave a live dashboard; swiping inside the phone module is reserved for the phone. Dashboard subpages have previous/next controls. The popup marks the current page. Home opens the main menu.

The first page exposes the existing live projection surface. CarPlay map pans and phone gestures remain inside projection; swipe the floating icon to leave it. The icon overlays the full-size projection view; no bottom strip reserves screen space. The video surface remains composed while navigating, and the same manager continues to own USB and audio. Page navigation alone never starts a stopped connection.

Main menu contains a modular dashboard with up to six fixed pages. Each saved page uses a four-column, two-row grid. Short or narrow displays present these modules on individual fixed subpages instead of shrinking all their controls into one screen. **Edit layout** while parked to add modules, tap an outlined module to resize or move it, or move it to a different page. Changes are validated before saving: overlaps, off-grid placement, duplicate projection modules, and duplicate Android widget instances are rejected. Vehicle/driver/decoder profiles keep separate layouts.

Modules include live projection, media playback/voice, navigation guidance, speed, RPM, oil life, service distance, doors, climate temperatures, clock, and installed Android widgets. Projection supports 2×2, 3×2, and 4×2 sizes. Move neighboring modules to another page before expanding into their space. Other modules support 1×1, 2×1, 2×2, and 4×2. Android providers must fit the selected module's minimum dimensions; incompatible sizes show a message.

**The live module is not a preview image or launch button.** CabinApp keeps one MainScreen/SurfaceView mounted and positions its frame behind a transparent dashboard opening. Its identity remains stable during resizing and page navigation. Phone aspect ratio is preserved, so some layouts may have letterboxing. Existing touch normalization uses the current surface/container dimensions; active touches are canceled on a size change. USB/audio ownership stays with the same manager. On disconnected launches the module shows connection status and a Connect action; a phone and supported adapter are required for live video.

Apps, launcher Settings, gauge pages, and Android widget pages use fixed pagination instead of vertically scrolling launcher lists. Advanced settings and system widget pickers can still use scrollable dialogs. Third-party widgets control their own internal UI and may scroll. Bind widgets on the Widgets page, then add their instances through **Edit layout → Add module**. Native widgets remain usable while moving; Android-widget interaction and layout editing are unavailable then.

Validation includes layout persistence/collision tests and native SurfaceView identity, frame bounds, touch routing, and stopped-session checks. This does not replace hardware testing of video, audio, sleep/wake, or on-road usability.

## Widgets page and built-in vehicle data

Open the floating switcher and choose **Widgets**. Customize while parked to select speed, RPM, oil life, and oil-service distance, or add installed Android widgets through the system binding flow. Gauge choices are saved separately for each driver, CANBUS profile ID, and decoder layout; layouts follow the detected profile. Android widget IDs remain installation-local.

All native readings come exclusively from the built-in TEYES/SYU controller. No OBD snapshots, external adapters, coolant, or ECU-voltage gauges are used. Old saved coolant/voltage selections are ignored. Existing driver-only gauge choices act as initial defaults for a vehicle until its layout is customized.

Multiple vehicles use their active CANBUS profiles and verified decoding rules. This is not universal make/model coverage: raw field meanings and supported readings vary by profile, decoder, and firmware. Existing verified profile variants remain supported; unverified speed/RPM mappings stay unavailable. A profile change clears the previous controller session before new readings are accepted. Gauge layout selection never changes the factory CANBUS configuration.

Only connected, fresh, normalized, available fields are displayed; missing readings show an em dash. Oil service is a signed distance until maintenance, distinct from oil-life percentage, and requires unit/sign metadata. Speed and service-distance widgets honor metric/imperial settings. Native gauges remain visible while moving; customization and third-party widget interaction are unavailable then. Gauge layout is not included in backup schema 2.

### Flexible sizing and projection fullscreen

Every dashboard module offers all eight grid sizes (1–4 columns × 1–2 rows) in its editor. The selected size is highlighted. Placement still rejects overlaps and preserves the last valid layout. Hosted Android widgets retain their provider's minimum-size requirements.

The launcher header uses icons and page dots without clock text. CarPlay has a small corner fullscreen icon with a 56 dp touch target. Expanding from a dashboard module keeps the dashboard composed but unplaced, preserving its selected page and layout. Restore or Android Back returns to the same module; the native video surface is retained throughout. The corner control is hidden during editing and other overlays.

### Widget long press and corner resize

Long-press a non-CarPlay card to enter edit mode through the existing parked-action guard. On the full grid, non-CarPlay cards display a 56 dp corner resize handle. Drag it to preview the target grid dimensions and release to apply them. The tile stays anchored; overlapping sizes are rejected without moving neighboring tiles. Canceling a gesture leaves the saved layout untouched. Compact single-card pages retain the tap-to-open size picker. Tap Done to finish editing.

CarPlay never receives the long-press handler or corner drag handle; its normal projection touch path is unchanged. Its explicit size picker remains available through the toolbar editor. Long-press editing and resize handles are disabled while moving.

### Dashboard-first startup and integrated settings

Normal app launches now open the dashboard, including app-icon and HOME launches. Only explicit compact/fullscreen projection actions opt into projection-first startup. Opening the launcher does not automatically request a phone connection.

The dashboard session uses fullscreen immersive display policy regardless of a saved projection display preference. The same policy is used for window bars and initial video viewport calculation, reapplied on resume/focus. The launcher reserves only display-cutout space, avoiding a persistent bottom gap. Android edge swipes can still reveal transient system navigation.

A gear icon in the dashboard opens the actual settings directly. On wide displays, settings appear in a rounded side panel over the existing dashboard, with a dismissible scrim; narrow screens use nearly the full width. Closing settings returns to the retained dashboard page and layout. The page-switcher Settings action opens this same panel. Editing/settings access remains guarded while driving.
