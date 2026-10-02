# Cabin: 100 improvement goals

Created 2026-09-30. **100 implemented goal areas · 65 verified complete · 35 awaiting physical acceptance.**

This is the next improvement backlog after the [50-feature delivery](50-FEATURE-DELIVERY.md). Each goal describes additional behavior or release quality work. Implementation evidence is recorded per goal in the JSON tracker. Completion still requires the listed automated, UI and physical-device checks.

**Priority:** P1 = first (38) · P2 = next (53) · P3 = later (9). Original priorities are retained for context; dependencies and acceptance criteria remain attached to each goal.

**Initial priorities (now implemented):**

- G001: Structured connection failure reasons
- G029: Frame-accurate PCM ring buffering
- G034: Undo app-library changes
- G041: Search all settings
- G073: Expand portable configuration backups

All ten batches are implemented. See the [delivery report](100-IMPROVEMENT-DELIVERY.md) for validation results and remaining physical checks.

## How to track progress

Use the [JSON tracker](100-IMPROVEMENT-GOALS.json) as the source of truth. Move each goal through `planned`, `in_progress`, `blocked` and `complete`; add implementation and verification references to its `evidence` list. Keep the corresponding checklist and totals here in sync. Goal IDs stay stable.

A goal is complete when all of these apply:

- Meet the goal-specific completion criteria and integrate the behavior into Cabin.
- Run relevant automated checks and record the result; user-visible workflows also receive UI or manual verification.
- Provide any new user-facing text in all six supported languages: English, Portuguese, Spanish, French, German and Italian.
- Preserve existing settings and supported backup imports, including migration checks where storage changes.
- Record implementation and verification evidence before changing status to complete. Goals marked for hardware validation also require the stated device evidence.

**Device tags:** “Head unit” requires testing on compatible target hardware. “Android device” requires physical Android validation. An untagged goal still needs the relevant automated or emulator checks. Record hardware model, firmware and configuration with the result.

## Batches

| Batch | Focus | Goals |
| --- | --- | --- |
| 1 | Connection reliability | G001–G010 |
| 2 | Projection experience | G011–G020 |
| 3 | Audio behavior | G021–G030 |
| 4 | Launcher and dashboard | G031–G040 |
| 5 | Usability and accessibility | G041–G050 |
| 6 | Vehicle and climate | G051–G060 |
| 7 | Trips, maintenance and parking | G061–G070 |
| 8 | Profiles, privacy and backups | G071–G080 |
| 9 | Diagnostics and support | G081–G090 |
| 10 | Updates, performance and release quality | G091–G100 |

## Batch 1: Connection reliability

Implementation starting points: [CabinManager.kt](../../app/src/main/kotlin/com/cabin/CabinManager.kt), [UsbDeviceWrapper.kt](../../app/src/main/kotlin/com/cabin/usb/UsbDeviceWrapper.kt), [CabinProjectionService.kt](../../app/src/main/kotlin/com/cabin/background/CabinProjectionService.kt), [CarlinkEngineConnection.kt](../../app/src/main/kotlin/com/cabin/carlink/CarlinkEngineConnection.kt), [ProjectionHealthStore.kt](../../app/src/main/kotlin/com/cabin/platform/ProjectionHealthStore.kt).

- [x] **G001 — Structured connection failure reasons** (P1)
  Done when: USB permission denial, missing adapters, failed writes, corrupt packets and phone timeouts produce typed reasons with specific next steps; presentation no longer infers causes from log strings.

- [ ] **G002 — Connection stage timers** (P1 · Head unit)
  Done when: Each waiting stage shows elapsed time and a bounded timeout; a stalled stage exits with its actual reason, and Stop cancels its timer.
  Requires: G001.

- [x] **G003 — Visible retry countdown and pause** (P2)
  Done when: Existing automatic retries display the next-attempt countdown and let users pause or resume retrying without losing saved phone settings.

- [ ] **G004 — Preferred USB adapter selection** (P2 · Head unit)
  Done when: With multiple known adapters attached, users can remember a distinguishable adapter; unavailable or ambiguous identities lead to an explicit choice instead of silent first-device selection.

- [ ] **G005 — Per-phone automatic connection preference** (P2 · Head unit)
  Done when: Each known phone can be marked manual-only or preferred for Cabin-initiated connections; explicit phone selection overrides the preference and an unavailable preferred phone produces a clear fallback choice.

- [ ] **G006 — USB permission denial recovery** (P1 · Head unit)
  Done when: Denial or revocation enters a stable permission-needed state without repeated prompts; a user action requests permission again and approval resumes the intended connection.
  Requires: G001.

- [ ] **G007 — Bounded native-engine binding** (P1 · Head unit)
  Done when: Native-engine binding has a deadline and capped recovery policy; null binding, process death and late callbacks cannot leave an indefinite spinner or revive a stopped session.
  Requires: G001.

- [x] **G008 — Retained connection session summaries** (P2)
  Done when: An opt-in local history retains bounded session durations, disconnect reasons and recovery durations across restarts, with deletion and retention controls and no phone identifiers or media metadata.
  Requires: G001.

- [ ] **G009 — Reconnect stability recovery window** (P1 · Head unit)
  Done when: A documented interval of healthy streaming clears prior short-session escalation; repeated brief reconnects remain rate-limited and user-requested disconnect never restarts automatically.
  Requires: G001.

- [ ] **G010 — Sleep and wake connection reconciliation** (P1 · Head unit)
  Done when: On head-unit wake, Cabin reconciles USB ownership, permissions and requested-session state before resuming; unchanged live connections stay intact and stale callbacks cannot open duplicate sessions.
  Requires: G001.

## Batch 2: Projection experience

Implementation starting points: [ProjectionPreferences.kt](../../app/src/main/kotlin/com/cabin/platform/ProjectionPreferences.kt), [ProjectionTools.kt](../../app/src/main/kotlin/com/cabin/ui/ProjectionTools.kt), [ProjectionBlackout.kt](../../app/src/main/kotlin/com/cabin/ui/ProjectionBlackout.kt), [ProjectionTouchGeometry.kt](../../app/src/main/kotlin/com/cabin/ui/ProjectionTouchGeometry.kt), [DisplayModeDialog.kt](../../app/src/main/kotlin/com/cabin/ui/settings/DisplayModeDialog.kt), [AdapterConfigurationDialog.kt](../../app/src/main/kotlin/com/cabin/ui/settings/AdapterConfigurationDialog.kt).

- [ ] **G011 — Timed video-settings trial** (P1 · Head unit)
  Done when: Resolution and frame-rate changes offer a Keep/Revert countdown; failure to confirm or obtain a usable picture restores the previous configuration, including after process restart.

- [ ] **G012 — Flicker-free display-mode application** (P2 · Head unit)
  Done when: Applying a previewed display mode never briefly restores the previous system-bar state; cancelling restores the saved state exactly.

- [x] **G013 — Driver-specific projection presentation** (P2)
  Done when: Control side, focus controls, vehicle HUD, climate notices and return behavior can be saved per driver; existing app-wide preferences migrate predictably.

- [ ] **G014 — Adjustable projection-control visibility** (P2 · Head unit)
  Done when: The projection strip supports always-visible and selected auto-hide intervals; active gestures and open panels prevent hiding, and the first reveal gesture does not reach the phone.

- [x] **G015 — Custom projection Tools shortcuts** (P3)
  Done when: Users can choose and order a small set of supported existing Tools actions; unsupported backend actions are excluded and reset restores the default arrangement.

- [ ] **G016 — Optional projection blackout timer** (P3 · Head unit)
  Done when: An optional timer covers projection after a chosen interval without disconnecting audio; interaction resets it and waking consumes the gesture before restoring touch forwarding.

- [ ] **G017 — Live picture delivery status** (P2 · Head unit)
  Done when: An optional panel distinguishes received video, decoder output and rendered-frame progress using measured counters, identifies unsupported measurements and remains bounded in memory.

- [ ] **G018 — Projection bezel inset adjustment** (P2 · Head unit)
  Done when: A parked preview allows bounded safe-area insets for obscured edges; saved insets affect video placement and touch mapping coherently and can be reverted through the timed trial.
  Requires: G011.

- [ ] **G019 — Gesture continuity diagnostic** (P2 · Head unit)
  Done when: The existing touch tests gain a continuous drag across edges and overlays, showing unexpected cancellation or pointer loss without recording projection content or claiming Android calibration.

- [x] **G020 — Backend capability explanations** (P2)
  Done when: Projection settings explain which current backend supports phone switching, video settings, audio gains and diagnostics; unavailable controls show specific reasons and supported controls stay actionable.

## Batch 3: Audio behavior

Implementation starting points: [DualStreamAudioManager.kt](../../app/src/main/kotlin/com/cabin/audio/DualStreamAudioManager.kt), [MicrophoneCaptureManager.kt](../../app/src/main/kotlin/com/cabin/audio/MicrophoneCaptureManager.kt), [AudioRingBuffer.kt](../../app/src/main/kotlin/com/cabin/audio/AudioRingBuffer.kt), [AudioConfig.kt](../../app/src/main/kotlin/com/cabin/platform/AudioConfig.kt).

- [ ] **G021 — Smooth ducking transitions** (P1 · Head unit)
  Done when: Adapter ducking uses its supported duration to ramp gain smoothly; newer commands replace pending ramps and focus loss still silences the affected stream immediately.

- [ ] **G022 — Named projection audio presets** (P3 · Head unit)
  Done when: Drivers can save, rename and apply bounded media/navigation gain presets; values remain within existing limits while calls, assistants and alerts retain their routing.

- [ ] **G023 — Explain the active audio route** (P2 · Head unit)
  Done when: Audio settings identify whether Cabin or adapter transfer owns playback, show the observable Android output route and explain unavailable gain controls.

- [ ] **G024 — Audio output route-change recovery** (P1 · Head unit)
  Done when: Android-reported route loss or replacement triggers bounded track reconciliation while preserving stream purpose and focus; stopped sessions stay stopped and stale PCM is discarded.
  Requires: G023.

- [ ] **G025 — Bounded microphone capture recovery** (P1 · Head unit)
  Done when: Recoverable capture failures permit a capped restart only while the same call or assistant session requests capture; permission revocation and session stop cancel recovery and release resources.

- [ ] **G026 — Observable microphone input selection** (P2 · Head unit)
  Done when: Where Android exposes supported inputs, users can choose a preferred microphone and see the actual routed input; failed routing falls back visibly without enabling undocumented formats.

- [ ] **G027 — Live audio health card** (P2 · Head unit)
  Done when: An optional card shows per-stream buffer fill, underrun/overflow deltas, focus state and microphone progress at a bounded refresh rate without retaining PCM.

- [ ] **G028 — Selectable audio buffering profiles** (P2 · Head unit)
  Done when: Supported backends offer responsive, balanced and resilient buffering profiles with clear latency tradeoffs; changes apply at a safe session boundary and reset to the platform default.
  Requires: G027.

- [x] **G029 — Frame-accurate PCM ring buffering** (P1)
  Done when: Ring capacity, reads and overflow discards preserve whole PCM frames for supported rates and channel counts, including fractional bytes-per-millisecond rates; boundary checks prove no partial frame is returned.

- [ ] **G030 — Actionable audio-focus denial** (P2 · Head unit)
  Done when: When playback is silent because focus was denied or delayed, Cabin explains why and offers a safe retry only for the still-requested session, without overriding a stop or active focus loss.

## Batch 4: Launcher and dashboard

Implementation starting points: [LauncherAppLibrary.kt](../../app/src/main/kotlin/com/cabin/launcher/LauncherAppLibrary.kt), [LauncherAppDrawer.kt](../../app/src/main/kotlin/com/cabin/launcher/LauncherAppDrawer.kt), [DashboardLayout.kt](../../app/src/main/kotlin/com/cabin/launcher/DashboardLayout.kt), [PagedLauncherItems.kt](../../app/src/main/kotlin/com/cabin/launcher/PagedLauncherItems.kt).

- [x] **G031 — App folders** (P2)
  Done when: Drivers can create, rename and remove folders, move apps between them, and retain the arrangement after restart without changing other drivers.

- [x] **G032 — Select several apps at once** (P2)
  Done when: The drawer supports selecting several apps for pin, unpin, hide or restore, previews the affected apps and offers one Undo action.
  Requires: G034.

- [x] **G033 — Drag to reorder pinned apps** (P2)
  Done when: Dragging a pinned app changes its saved order across pages; cancelling a drag preserves the original order and button-based reordering still works.

- [x] **G034 — Undo app-library changes** (P1)
  Done when: Pin, unpin, hide, restore and rename operations can be undone from the drawer, with driver isolation and a bounded history.

- [x] **G035 — Refresh apps after installation changes** (P1)
  Done when: Install, uninstall and package-replacement events update an open drawer without manual refresh, preserve custom names, and unregister listeners when no longer needed.

- [x] **G036 — Recently installed app filter** (P3)
  Done when: A separate filter lists apps installed within a chosen recent period using package installation dates, independently of launch-history recording.
  Requires: G035.

- [x] **G037 — Explain unavailable app shortcuts** (P1)
  Done when: Unavailable shortcuts distinguish removed apps, disabled apps and failed launches where Android exposes that information, and offer the appropriate recovery action without losing saved pins.
  Requires: G035.

- [x] **G038 — Choose app-drawer density** (P2)
  Done when: Drivers can choose comfortable or compact app tiles; both layouts preserve touch targets, package subtitles, search focus and page position on supported screen sizes.

- [x] **G039 — Name and reorder dashboard pages** (P2)
  Done when: Dashboard pages have editable names and a reorder control; widget placement follows its page and the selected page remains correct after restart.

- [x] **G040 — Duplicate a dashboard page** (P2)
  Done when: A duplicate-page preview identifies items requiring new widget consent or a unique projection surface; supported tiles are copied with new IDs without stealing live widget bindings.
  Requires: G039.

## Batch 5: Usability and accessibility

Implementation starting points: [SettingsScreen.kt](../../app/src/main/kotlin/com/cabin/ui/SettingsScreen.kt), [Theme.kt](../../app/src/main/kotlin/com/cabin/ui/theme/Theme.kt), [SettingsUi.kt](../../app/src/main/kotlin/com/cabin/ui/settings/SettingsUi.kt).

- [x] **G041 — Search all settings** (P1)
  Done when: A localized settings search opens the matching section and control, respects current capabilities, and gives a useful empty-result message.

- [x] **G042 — Reduce interface motion** (P2)
  Done when: A saved reduced-motion option removes nonessential transitions and animated decoration while preserving progress information and navigation behavior.

- [x] **G043 — High-contrast appearance** (P2)
  Done when: A high-contrast theme covers all Cabin screens; normal text meets a documented 4.5:1 contrast target and state information remains understandable without color alone.

- [x] **G044 — Keyboard and directional focus navigation** (P2)
  Done when: Android keyboard and directional input can reach, activate and leave every control in the main workflows, with visible focus and no focus traps.

- [x] **G045 — Complete screen-reader navigation** (P2)
  Done when: Core workflows have ordered accessibility traversal, meaningful labels for icon-only controls, announced state changes and operable custom widgets.

- [x] **G046 — Consistent touch targets on small screens** (P1)
  Done when: Critical controls remain reachable with at least 56dp touch targets at the supported minimum screen sizes and 200% font scaling; automated layout checks cover the main workflows.

- [x] **G047 — Restore settings navigation position** (P2)
  Done when: Returning to settings restores the open section and scroll position after rotation or recreation, without leaking another driver or vehicle profile's position.

- [x] **G048 — Keep unfinished form edits** (P1)
  Done when: Editable settings forms retain in-progress text across recreation, distinguish saved values from drafts, and provide clear Apply or Discard behavior without silently saving invalid input.

- [x] **G049 — Offline help beside unfamiliar controls** (P2)
  Done when: Complex settings offer short, localized explanations of their effect, requirements and recovery steps without requiring an internet connection.

- [x] **G050 — Highlight search matches** (P3)
  Done when: App and settings search results highlight matching words while preserving accent-insensitive search, custom app labels and accessible text descriptions.
  Requires: G041.

## Batch 6: Vehicle and climate

Implementation starting points: [TeyesClimateController.kt](../../app/src/main/kotlin/com/cabin/platform/TeyesClimateController.kt), [VehicleCompatibility.kt](../../app/src/main/kotlin/com/cabin/platform/VehicleCompatibility.kt), [TireHistory.kt](../../app/src/main/kotlin/com/cabin/platform/TireHistory.kt), [ClimateCloseTimer.kt](../../app/src/main/kotlin/com/cabin/ui/ClimateCloseTimer.kt), [VehicleCompatibilityPanel.kt](../../app/src/main/kotlin/com/cabin/ui/settings/VehicleCompatibilityPanel.kt), [VehicleHistoryWidgets.kt](../../app/src/main/kotlin/com/cabin/launcher/VehicleHistoryWidgets.kt).

- [ ] **G051 — Show freshness for individual vehicle readings** (P1 · Head unit)
  Done when: Each supported reading exposes its own update age and distinguishes waiting, stale and disconnected states; one fresh callback cannot refresh unrelated readings.

- [ ] **G052 — Confirm climate command outcomes** (P1 · Head unit)
  Done when: Commands with verified feedback show pending, confirmed or unconfirmed status; a timeout never displays assumed success or automatically repeats a command.

- [ ] **G053 — Explain disabled climate controls** (P1 · Head unit)
  Done when: Unavailable controls identify the actual cause: unsupported command, missing feedback, disconnected service or user-selected read-only mode.
  Requires: G056.

- [x] **G054 — Make climate auto-close adjustable** (P2)
  Done when: Users can select 10, 20 or 30 seconds, or keep the panel open; interaction resets and lifecycle pause behavior remain correct.

- [ ] **G055 — Add favorites within the climate panel** (P2 · Head unit)
  Done when: Users can select and order frequently used verified climate actions; unsupported favorites remain clearly unavailable after a vehicle-profile change.

- [ ] **G056 — Add voluntary read-only vehicle mode** (P1 · Head unit)
  Done when: A saved option blocks every Cabin-initiated vehicle write at the controller boundary while retaining live readings and explaining the restriction.

- [ ] **G057 — Compare saved compatibility snapshots** (P2 · Head unit)
  Done when: A dated capability snapshot can be saved and compared after firmware changes; added, missing and changed capabilities are separated from stale feedback.

- [ ] **G058 — Make vehicle-profile changes explicit** (P1 · Head unit)
  Done when: A detected profile change dismisses obsolete editors, clears pending UI command state and displays the new profile before further interaction.

- [ ] **G059 — Label vehicle-data provenance** (P2 · Head unit)
  Done when: Vehicle and trip detail views distinguish live feedback, calculated estimates, saved history and manual entries; unknown provenance is never presented as live data.

- [x] **G060 — Compare all four tire histories** (P2)
  Done when: One view aligns the four existing tire histories on a shared timeline, follows the chosen pressure units and shows gaps instead of connecting missing samples.

## Batch 7: Trips, maintenance and parking

Implementation starting points: [VehicleUtilityModels.kt](../../app/src/main/kotlin/com/cabin/platform/VehicleUtilityModels.kt), [TripHistory.kt](../../app/src/main/kotlin/com/cabin/platform/TripHistory.kt), [CarAutomation.kt](../../app/src/main/kotlin/com/cabin/platform/CarAutomation.kt), [TripToolsPanel.kt](../../app/src/main/kotlin/com/cabin/ui/settings/TripToolsPanel.kt), [TripHistoryBrowser.kt](../../app/src/main/kotlin/com/cabin/ui/settings/TripHistoryBrowser.kt), [CarAutomationPanel.kt](../../app/src/main/kotlin/com/cabin/ui/settings/CarAutomationPanel.kt).

- [x] **G061 — Add custom trip date ranges** (P2)
  Done when: Inclusive start/end dates are validated; the trip list, totals and CSV export use the same selected range across timezone boundaries.

- [x] **G062 — Add trip labels and notes** (P2)
  Done when: Users can label trips personal or business and save bounded notes; labels can be filtered and exporting notes requires an explicit choice.

- [x] **G063 — Add selected-trip bulk actions** (P2)
  Done when: Users can select specific trips for deletion or CSV export, review the count and undo a deletion batch without resurrecting unrelated records.

- [x] **G064 — Compare monthly trip totals** (P2)
  Done when: Two months can be compared by trip count, recorded distance, duration and known estimated cost, with missing cost data and partial retention clearly shown.

- [x] **G065 — Make fuel estimates vehicle-specific** (P1)
  Done when: Fuel rate, price and currency are saved per vehicle; each estimate retains its calculation inputs and recalculation previews the affected records.

- [x] **G066 — Support multiple maintenance items** (P2)
  Done when: Each vehicle can have independently named reminders, intervals and due dates; completing or snoozing one leaves the others untouched.

- [x] **G067 — Keep a service-history ledger** (P2)
  Done when: Service completion appends a dated record with optional note and cost; users can review, correct and export the history instead of retaining only the latest completion.
  Requires: G066.

- [x] **G068 — Add manual mileage maintenance reminders** (P2)
  Done when: Dated, explicitly manual odometer readings support distance-based reminders; unexplained decreasing readings are rejected and no live odometer source is inferred.
  Requires: G066.

- [ ] **G069 — Explain parking-location capture results** (P1 · Head unit)
  Done when: Saved parking shows measured accuracy; permission denial, disabled GPS, timeout and success have distinct outcomes, and failed capture preserves the previous position.

- [ ] **G070 — Add a parking expiration reminder** (P2 · Android device)
  Done when: Users can set or cancel a parking expiration time; remaining time survives restarts, notification permission and delivery limitations are visible, and cancelling removes pending reminders.

## Batch 8: Profiles, privacy and backups

Implementation starting points: [TeyesFeaturePreferences.kt](../../app/src/main/kotlin/com/cabin/platform/TeyesFeaturePreferences.kt), [TeyesConfigurationBackup.kt](../../app/src/main/kotlin/com/cabin/platform/TeyesConfigurationBackup.kt), [VehicleAppearancePreferences.kt](../../app/src/main/kotlin/com/cabin/platform/VehicleAppearancePreferences.kt), [TeyesConfigurationTools.kt](../../app/src/main/kotlin/com/cabin/ui/settings/TeyesConfigurationTools.kt).

- [x] **G071 — Copy selected driver comfort preferences** (P2)
  Done when: Appearance, brightness, projection audio gains and wake preferences can be copied between existing driver slots after reviewing changes; phone links require separate selection.

- [x] **G072 — Reset one driver's comfort preferences** (P2)
  Done when: Users can preview and reset one driver's appearance, audio and wake settings without changing other drivers or vehicle configuration.

- [x] **G073 — Expand portable configuration backups** (P1)
  Done when: A versioned schema round-trips newer launcher layouts/preferences, automation, vehicle appearance and trip/maintenance configuration; histories and locations remain excluded by default and existing schemas still import.

- [x] **G074 — Restore selected backup sections with a diff** (P1)
  Done when: Import review compares current and incoming values by section; users can restore only chosen sections and cancelling writes nothing.
  Requires: G073.

- [x] **G075 — Make configuration restore transactional** (P1)
  Done when: An interrupted or failed restore leaves either the old complete configuration or the new complete configuration across preference stores; restart recovery and injected write failures verify this.
  Requires: G073.

- [x] **G076 — Add password-protected backup files** (P2)
  Done when: Optional authenticated encrypted backups reject wrong passwords and modified files before applying any settings; passwords are never stored.
  Requires: G073, G075.

- [x] **G077 — Add a local privacy inventory** (P1)
  Done when: One settings view explains each personal-data category, its recording switch, retention rule, export inclusion and deletion control, with accurate counts where available.

- [ ] **G078 — Make trip recording an explicit choice** (P1 · Head unit)
  Done when: Users can disable recording per vehicle, immediately stop future persistence and separately choose whether to retain or erase existing trips.

- [x] **G079 — Add parking-data retention choices** (P2)
  Done when: Users can keep parking data until cleared or expire its coordinates, timestamp and note after a chosen duration; expiration also applies after app restart.

- [x] **G080 — Add temporary guest comfort settings** (P3)
  Done when: Guest mode allows session-only appearance and projection audio changes; ending the session restores the prior driver and removes temporary values without changing saved preferences.

## Batch 9: Diagnostics and support

Implementation starting points: [LogExplorer.kt](../../app/src/main/kotlin/com/cabin/ui/settings/LogExplorer.kt), [LogFileViewer.kt](../../app/src/main/kotlin/com/cabin/ui/settings/LogFileViewer.kt), [LogFilesStore.kt](../../app/src/main/kotlin/com/cabin/ui/settings/LogFilesStore.kt), [HealthReportPreview.kt](../../app/src/main/kotlin/com/cabin/ui/HealthReportPreview.kt).

- [x] **G081 — Search several log files together** (P1)
  Done when: Users can select multiple log files and search them with file provenance, progress, cancellation and explicit scan limits while memory remains bounded.

- [x] **G082 — Filter logs by time range** (P2)
  Done when: Start and end filters use a clearly displayed time zone, handle boundary timestamps consistently, and distinguish records with missing or ambiguous timestamps.

- [x] **G083 — Show context around log matches** (P2)
  Done when: Each match can reveal nearby original lines with their line numbers; context is visually distinct and its inclusion in an export is explicit.

- [x] **G084 — Continue large log searches** (P1)
  Done when: A continuation action scans beyond the current 16MiB and 1,000-result limits using bounded pages, detects file changes and never silently skips or duplicates matches.

- [x] **G085 — Export every filtered log match** (P2)
  Done when: A separate export streams all selected matching records rather than only displayed rows, retains metadata-only mode, reports scope and omissions, and supports cancellation.
  Requires: G084.

- [x] **G086 — Follow log rotation** (P2)
  Done when: An explicit session-follow mode switches to the next rotated file, labels file boundaries, avoids replayed lines and stops cleanly when logging or the visible viewer stops.

- [x] **G087 — Bookmark log findings** (P3)
  Done when: Users can bookmark and revisit a file and line position, remove bookmarks, and see a clear explanation when the source file was deleted or changed.

- [x] **G088 — Summarize log severity** (P2)
  Done when: A summary shows error, warning and informational counts for the declared scan scope; selecting a count applies the corresponding filter and never implies unscanned data was counted.

- [x] **G089 — Recover interrupted support exports** (P1)
  Done when: After process death or restart, unfinished log/report exports offer an explicit resume or discard action, reuse the frozen source, and request a new destination if access was lost.

- [x] **G090 — Compare saved health reports** (P2)
  Done when: Two bounded, validated health-report files can be compared locally; changed, missing and unchanged fields are separated and incompatible schemas receive a clear explanation.

## Batch 10: Updates, performance and release quality

Implementation starting points: [GitHubUpdater.kt](../../app/src/main/kotlin/com/cabin/updates/GitHubUpdater.kt), [GitHubRelease.kt](../../app/src/main/kotlin/com/cabin/updates/GitHubRelease.kt), [UpdateSettingsSection.kt](../../app/src/main/kotlin/com/cabin/updates/UpdateSettingsSection.kt), [release.yml](../../.github/workflows/release.yml), [build.gradle.kts](../../app/build.gradle.kts).

- [x] **G091 — Read release notes inside Cabin** (P2)
  Done when: The update screen displays bounded release notes for the selected verified release, caches them for offline reading and handles missing notes without blocking an update.

- [x] **G092 — Control downloads on metered networks** (P2)
  Done when: Users can require an unmetered network for APK downloads, see why a download is waiting and explicitly override that choice; installation continues to require the existing approval flow.

- [x] **G093 — Resume interrupted APK downloads** (P1)
  Done when: Interrupted downloads resume only when the server validates the same asset; changed assets restart safely, progress remains accurate and checksum/signature checks still precede installation.

- [x] **G094 — Check storage before updating** (P1)
  Done when: Before downloading, Cabin checks required working space, explains a shortage and preserves any already-verified APK; low-space interruptions leave a recoverable state.

- [x] **G095 — Offer a backup before an update** (P1)
  Done when: The update flow offers a recoverable snapshot of the expanded configuration, reports backup success or failure, and provides a clear user choice before continuing installation.
  Requires: G073, G075.

- [x] **G096 — Skip a particular update version** (P3)
  Done when: Users can dismiss one release until they explicitly revisit it or a newer release appears, with the skipped version visible and reversible in update settings.

- [x] **G097 — Choose stable or preview releases** (P3)
  Done when: Stable remains the default; an explicit preview-channel choice is saved and clearly labeled, incompatible assets are rejected, and changing channels never silently downgrades the installed app.

- [x] **G098 — Measure and guard startup performance** (P1)
  Done when: Repeatable cold and warm startup benchmarks record a baseline on a named reference device or emulator, identify the largest delay, and enforce an agreed regression budget in CI.

- [ ] **G099 — Catch resource leaks during long sessions** (P1)
  Done when: A repeatable long-session test exercises projection reconnects, driver changes and UI navigation, checks threads, file descriptors and retained memory against a recorded baseline, and fails on sustained growth.

- [x] **G100 — Smoke-test the minified release APK** (P1)
  Done when: CI installs a minified release-equivalent APK on the supported Android test matrix and exercises first launch, dashboard, settings, permission denial and document export before publication.
