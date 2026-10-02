# Cabin UX follow-up — 2026-10-01

This continues the September 30 review with three parallel reviewers and a coordinator. The focus is editing, keyboard input, localization, state recovery, and compact screens. Existing feature work is preserved. Physical acceptance statuses are unchanged.

## Changes

- **Launcher:** folder capacity preserves typed names; bulk pinning requires the complete selection to fit; reorder uses measured positions and preserves keyboard focus; rename has a bounded scrolling editor and a working Done action.
- **Settings:** restored categories remain visible on narrow screens; search takes keyboard focus and restores its query; long translated phone-search labels remain compact. Search also respects the software keyboard's visible area.
- **Vehicle forms:** decimal commas and points work; invalid dates and odometer values identify the field to correct; stale service edits cannot create replacement records; deleting parking clears its saved draft.
- **Logs:** results and exports share the same successful search snapshot; changed filters and missing files require a fresh search; live follow can wait for a new session and cannot export an older static result. Invalid dates and new notices are brought into view. Missing document pickers preserve queued copies and allow another attempt.

## Review and verification

The final complete run passed **1,245 tests**: app 1,090 passed with one existing skip (1,091 total), and diagnostics 155 passed. This adds **25 regression cases**. Debug assembly, optimized `releaseCheck` assembly, matching instrumentation assembly, and release vital lint passed. All reviewers completed their final checks with no remaining confirmed software blocker in the reviewed scope.

[Full unit/build log](ux-review-2026-10-01/unit-and-build.txt) · [Optimized build log](ux-review-2026-10-01/minified-build.txt) · [Verification and APK hashes](ux-review-2026-10-01/verification.json) · [Per-suite counts](ux-review-2026-10-01/unit-suites.json).

Both optimized device checks passed on each emulator: first launch, dashboard/settings, denied permissions, frozen document export, and 30 measured no-adapter lifecycle/resource cycles.

| Emulator | Instrumentation | Measured lifecycle cycles |
| --- | --- | --- |
| [Android 8.1 / API 27](ux-review-2026-10-01/api27/device.json) | 2/2 passed | 30 passed |
| [Android 15 / API 35](ux-review-2026-10-01/api35/device.json) | 2/2 passed | 30 passed |

The Android 15 emulator shut down during the initial device run. Its [interrupted instrumentation](ux-review-2026-10-01/api35/interrupted-instrumentation.txt) and [wrapper error](ux-review-2026-10-01/api35/interrupted-wrapper.txt) are retained; a fresh complete run passed with the same APK. Both owned emulators were closed after verification. Physical hardware and startup benchmarking were not repeated in this follow-up.

Final optimized APK SHA-256: `32c9bc56acde9bc207c8bf3b48a40e8b38be9fee04364a0af51b272bec856ba3`. `releaseCheck` is an optimized, test-signed verification build rather than a production release.

The first complete run exposed a compact rename layout loop and three log-notice visibility failures. These are retained in the [first-run log](ux-review-2026-10-01/first-full-test-run.txt). Cross-review of emulator screenshots also found search rows behind the keyboard.

Screenshot inspection found that overriding `LocalDensity` outside a separate dialog did not reliably enlarge its text. The affected launcher, settings, and log tests now configure the Android resource font scale and assert the scale of the actual rendered text. Earlier unit captures that only used the outer override are not accepted as 200% dialog evidence. Actual emulator captures use the device's font-scale setting.

The final Android 15 screenshots show the [first matching result](ux-review-2026-10-01/screenshots/followup-verified-search-ime-200.png) and [scrolled second result](ux-review-2026-10-01/screenshots/followup-verified-search-second-ime-200.png) above the software keyboard at 800×480 and 200% text. Tapping the second result [opened its intended section](ux-review-2026-10-01/screenshots/followup-verified-search-selection.png). An independent reviewer confirmed the overlap is resolved. The second capture is a partial-scroll position; the regression separately verifies the entire 56dp button at its end-scroll position, with both status-bar and keyboard insets.

Verified unit-rendered layouts include [German folder actions](ux-review-2026-10-01/screenshots/ux-folder-actions-german-200.png), [Portuguese rename with keyboard space](ux-review-2026-10-01/screenshots/ux-app-rename-portuguese-200-keyboard.png), [long-name reorder](ux-review-2026-10-01/screenshots/ux-pin-reorder-long-labels-200.png), and [compact log results](ux-review-2026-10-01/screenshots/ux-log-workspace-200.png). These are layout evidence with asserted rendering scale; the emulator captures show the actual Android keyboard and system bars.

Detailed scope and regression notes: [launcher](ux-review-2026-10-01/launcher.md), [settings](ux-review-2026-10-01/settings.md), [vehicle](ux-review-2026-10-01/vehicle.md), [logs and exports](ux-review-2026-10-01/support.md).

## Remaining acceptance

The 100-goal tracker still has 35 physical acceptance items. Head-unit input, projection, CAN behavior, glare and driving conditions require real devices. This follow-up makes no claim of hardware certification or perfection.
