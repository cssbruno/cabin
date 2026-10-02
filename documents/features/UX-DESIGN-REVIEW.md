# Cabin design and user experience review

Date: 2026-09-30. Goal: audit and refine actual user flows with multiple independent reviewers, implement concrete fixes, and verify the result through automated regressions and rendered screens.

Continued in the [October 1 UX follow-up](UX-FOLLOWUP-2026-10-01.md). Counts and APK hashes below describe this September 30 snapshot. The follow-up also strengthens dialog font-scale verification; outer composition density overrides alone are not accepted as enlarged-text evidence.

## Review process

Three agents reviewed launcher/media, settings/connection, and vehicle/profile/privacy areas. The coordinator reviewed support/update flows and operated the emulators. Reviewers then inspected each other’s changes. Screenshots prompted an additional correction round for enlarged-text navigation and app search. Existing feature work and user data were preserved.

## Delivered changes

- Dashboard idle media has a useful state and source selector; disconnected projection controls reflect availability. Empty connection progress no longer leaves a blank bar.
- Narrow app search retains space beside pagination. Empty filters offer recovery, selection uses full rows, and pending actions reset when driver/vehicle layout changes.
- Setup steps start at the top; preferred-phone labels update together; Back returns through the phone-tools flow. Help and compact dialogs scroll with dismissal actions reachable.
- Settings navigation, search, sliders and update switches have meaningful accessible labels and larger targets. Matching search sections expand without opening unrelated sections.
- Deleting saved presets, maintenance items, history, parking and queued exports uses named scope and confirmation where needed. Vehicle and driver changes dismiss stale destructive actions.
- Backup exports retain encryption intent across recreation. Missing document pickers show recovery guidance; frozen local exports remain available. Service export content stays tied to the reviewed vehicle.
- Health comparisons can reopen without reimport, have accurate Clear reports wording, and scroll categories together with results.
- Log workspaces explain empty states, preserve file selection, wrap time filters on narrow displays and reserve space for results at enlarged text sizes.
- Update preferences can be toggled from their labeled rows; equal version names explain the build change. Failed external backup picking preserves the local backup and never starts installation automatically.
- The parked confirmation uses plain language and a scrollable body.

## Evidence

The final complete regression run passed **1,220 tests**: app 1,065 passed with one existing skip (1,066 total), and diagnostics 155 passed. This adds **37 UX regression cases** to the prior baseline. Debug assembly, minified `releaseCheck` assembly, matching instrumentation assembly and release vital lint all passed.

All three reviewers rechecked the completed changes. The launcher/support reviewer verified 9/9 launcher and 7/7 support cases; the settings reviewer verified 51/51 cases; the vehicle reviewer verified 9/9 new and 13/13 acceptance cases. No confirmed software blocker remained in their scopes.

[Machine-readable verification and APK hashes](ux-review/verification.json) · [Unit/build log](ux-review/unit-and-build.txt) · [Minified build log](ux-review/minified-build.txt).

Rendered evidence includes the [dashboard before](ux-review/screenshots/dashboard-before.png) and [after](ux-review/screenshots/dashboard-after.png), final [dashboard at 200% text](ux-review/screenshots/dashboard-200.png), [settings at 200%](ux-review/screenshots/settings-200.png), [parked confirmation](ux-review/screenshots/parked-confirmation-200.png), and [compact log results](ux-review/screenshots/log-workspace-200.png). The final 800×480 emulator view keeps the selected settings category in view and the compact idle media label readable. The final minified build also shows the [log empty state](ux-review/screenshots/minified-log-empty.png) and [contextual Back control](ux-review/screenshots/minified-settings.png). Unit-rendered drawer/media screenshots are layout evidence; the emulator screenshots provide the actual application surface/colors.

Both minified device checks passed on each emulator, including first launch with permissions denied, settings, log search, frozen document export, and 30 measured no-adapter connection/driver/settings/recreation cycles after warmup.

| Emulator | Device tests | Resource cycles | Cold launch median | Warm launch median |
| --- | --- | --- | --- | --- |
| Android 8.1 / API 27 | 2/2 passed | 30 passed | 240 ms | 39 ms |
| Android 15 / API 35 | 2/2 passed | 30 passed | 468 ms | 39 ms |

Startup budgets remain 2,500 ms cold and 500 ms warm. [API 27 evidence](ux-review/api27/device.json) and [API 35 evidence](ux-review/api35/device.json) preserve device identity, full instrumentation output, all resource samples and startup timings. API 27 initially returned one inconsistent Android `TotalTime` (79,858 ms while `WaitTime` was 666 ms); that [original run is retained](ux-review/api27/startup-first-run.json). A confirming identical seven-cold/seven-warm run had no such inconsistency and supplies the table above. These are emulator launch timings, not phone-projection readiness.

Release validation also caught and repaired the normal-font Back button’s missing contextual accessibility label. The complete unit suite and both minified device checks were rerun successfully after that correction.

Final minified APK SHA-256: `4d0719041f5d1e7751051146dad4b5b6ea2917699ed258df3e35eb9753b15d34`. `releaseCheck` is an optimized, test-signed verification build and is not a production release.

Detailed reviewer notes: [launcher](ux-launcher-review.md), [settings](ux-settings-review.md), [vehicle](ux-vehicle-review.md).

## Limits

Emulators validate software layout and behavior, not physical head-unit reachability, glare, rotary/steering input, phone projection, CAN commands, or driving conditions. The earlier 100-goal tracker’s 35 physical acceptance items remain pending. This review does not claim a perfect or hardware-certified product.
