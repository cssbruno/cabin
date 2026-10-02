# Cabin: 50-feature delivery

Requested 2026-09-30. This list adds software features to the existing Android app.
Hardware compatibility remains governed by verified interfaces and requires testing
on the target head unit. Existing launcher, CarPlay and vehicle functionality is
not counted as newly delivered functionality.

Status: all 50 features implemented and integrated; full software verification passed.

## Launcher app library — Apps drawer

1. Accent-insensitive, multiword app search.
2. Saved app sorting: pinned order, name, recently used or most used.
3. Pinned-only app filter.
4. Recently launched app filter.
5. Hide and restore apps independently for each driver.
6. Custom app display names.
7. Optional package-name subtitles.
8. Opt-in local app launch-history recording control.
9. Clear recorded launch history.
10. Refresh installed apps manually.
11. Review and remove missing pinned apps.
12. Move a pinned app directly to the first or last position.
13. Clear all pinned apps with confirmation.

## Trip, maintenance and parking tools — Car Settings

14. Trip period filters: all, today, seven days or thirty days.
15. Sort trips by date, distance, duration or estimated cost.
16. Selected-period trip totals and average speed.
17. Paginated browsing of retained trips.
18. Delete an individual trip and undo the deletion.
19. Export filtered trips as CSV through the document picker.
20. Recalculate stored fuel-cost estimates from current inputs.
21. Choose trip retention for each vehicle: 25, 50 or 100 records.
22. Schedule maintenance using 30-, 90- or 180-day presets.
23. Snooze a scheduled maintenance reminder by seven or thirty days.
24. Record service completion and schedule the next reminder.
25. Save parking notes for each vehicle profile.
26. Show when a parking location was saved and the elapsed time.

## Projection setup and reports — CarPlay / Tools

27. Search paired phones.
28. Filter phones by platform.
29. Sort phones by name.
30. Manually refresh the paired-phone list.
31. Jump directly between setup steps.
32. Persist an explicitly user-verified audio, microphone and touch checklist.
33. Restart setup with confirmation.
34. Run a five-point touch-target test.
35. Check whether multiple simultaneous touches reach Cabin.
36. Copy a projection health report to the clipboard.
37. Search health-report text.
38. Filter health reports by top-level section.

## Log explorer — Settings → Logs

39. Search across a log file using multiple case-insensitive words.
40. Filter log records by severity.
41. Filter log records by subsystem tag.
42. Follow the selected log file live while the viewer is visible.
43. Show newest matching log records first.
44. Export the displayed filtered log text.
45. Export metadata only: timestamps, severity and line numbers, excluding messages and tags.
46. Show or hide original log line numbers.
47. Adjust viewer text size.
48. Toggle line wrapping and horizontal scrolling.
49. Search the log-file list by filename.
50. Sort log files by newest, oldest, largest or name.

## Verification and limits

Verified 2026-09-30:

- `:app:testDebugUnitTest`: 905 tests, 904 passed, one existing skipped test, no failures.
- `:diagnostics:testDebugUnitTest`: 155 tests, no failures (unchanged module; Gradle cache reused).
- `:app:assembleDebug`: successful for the configured ARM64, ARMv7 and x86-64 build.
- 38 added regression tests; 140 new messages translated into all six supported languages.
- UI interaction coverage includes app management, trip deletion/Undo, filtering,
  export review, report copying, touch input, setup progress and log-view navigation.
- Reviewed generated launcher, trip and log-view screenshots.
- Android SDK `apksigner verify` validates the debug APK signature.

Build command:

```sh
ANDROID_HOME=/home/bruno/Android/Sdk ./gradlew :app:testDebugUnitTest :diagnostics:testDebugUnitTest :app:assembleDebug --offline --no-daemon --no-configuration-cache --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Physical head-unit and on-vehicle validation remain outstanding.


Log scans are bounded to 16 MiB input, 1,000 displayed matches and 4 KiB per line.
The viewer reports limits; original-file export remains available. Live following
refreshes the selected file every three seconds while visible and does not follow
rotation into another file. Metadata export intentionally excludes message bodies.

Trip distance and cost are estimates; these changes do not infer ignition,
odometer readings, camera feeds or CAN commands. Touch tests report what reaches
Cabin and do not alter Android touch calibration or certify projection alignment.

## Implementation map

| Features | Main implementation | Focused regression coverage |
|---|---|---|
| 1–13 | `LauncherAppLibrary`, `LauncherAppDrawer`, `LauncherPreferences` | `LauncherAppLibraryTest`, `LauncherAppDrawerTest` |
| 14–26 | `VehicleUtilityModels`, `TripHistoryBrowser`, `TripToolsPanel`, `CarAutomationPanel` | `VehicleUtilitiesTest`, `VehicleUtilitiesUiTest` |
| 27–38 | `ProjectionDevicePicker`, `ProjectionSetupFlow`, `ProjectionTouchDiagnostics`, `HealthReportPreview` | `ProjectionExtensionsTest`, `ProjectionSetupPreferencesTest` and existing projection UI tests |
| 39–50 | `LogExplorer`, `LogFileViewer`, `LogFilesStore`, `LogsTab` | `LogExplorerTest`, `LogExplorerUiTest` and existing log-storage tests |

All new interface text has English, Portuguese, Spanish, French, German and Italian
resources. App launch history starts disabled and is excluded from Android cloud
backup and device transfer. The paired-phone list additions use the existing
CCPA projection picker; native Carlink pairing retains its own settings.
