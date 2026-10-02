# Vehicle, profile and privacy UX review

Reviewed 2026-09-30. This review refines the existing 100-goal implementation. It does not certify physical CAN, climate, location or adapter behavior.

## Findings and fixes

| Finding | Change | Regression evidence |
| --- | --- | --- |
| Recreating the screen during an encrypted backup export discarded the encryption choice and could export plaintext. | Preserve the encryption choice across state restoration. Keep the password in memory only; a lost password fails the export instead of silently weakening protection. | `VehicleUxReviewTest`: encrypted export returns from the picker after state restoration. |
| A missing system document picker could crash backup import/export or service-history export. | Catch launch failures and show a recovery message. Preserve form input so the user can retry. Trip CSV uses the same guidance. | Missing-picker backup regression; existing export workflows. |
| Backup rejection still advertised the old 32 KB format restriction. | Explain file/password recovery without incorrectly excluding valid larger portable backups. Mark failed operations as errors and disable mutable controls while work is running. | Existing encrypted backup acceptance workflow. |
| Service CSV was generated after the picker returned, so a vehicle change could change its contents. | Freeze the selected vehicle's CSV before opening the picker. Show success or failure afterward. | Vehicle switch during service export regression. |
| Maintenance Delete immediately removed an item. | Name the item in a confirmation; keep completed service records. Dismiss the confirmation if the vehicle changes. Add an empty state. | Cancel, confirm, profile-change and history-preservation regression. |
| Driver-comfort confirmations could outlive the selected driver, and showed raw enums/booleans. | Reset target and confirmation when driver/guest context changes. Show labeled, localized before/after values with percentages and On/Off. | Driver-switch and readable-diff regression; existing copy/reset/guest acceptance. |
| Dialog content could overrun compact screens with larger text; the first compact regression also reproduced a non-idling intrinsic measurement loop in the service text editor. | Use a bounded custom service editor with a scrolling body and pinned actions. Bound and scroll restore preview, trip note/export, and comfort-review bodies. | Compact 600×360 dp, 200% text maintenance completion regression. |
| Trip selections could become invisible after filtering. | Reconcile selection with visible records; clear it on destructive retention/clear actions. Give each selection a dated accessible name. | Period-change selection regression. |
| Forget parking immediately deleted data and left the reminder active. | Confirm the scope, then remove position/note, cancel its reminder, and stop any capture for that vehicle. | Cancel/confirm, reminder cleanup and other-vehicle isolation regression. |
| Disabling privacy history immediately deleted stored records. | Confirm disabling app-launch or connection history when records exist; name the data category in deletion dialogs. | Updated privacy acceptance workflow. |
| A seventh climate favorite failed silently; arrow-only ordering labels were unclear. | Explain the six-item limit, disable additional choices at capacity, expose an explicit Done action, and name reorder actions. | Capacity/removal regression. |
| Settings search opened unrelated car sections. | Supply explicit descendant labels for vehicle categories and nested disclosures so only the target path opens. | Shared settings navigation regressions. |

## Accessibility and localization

- New switches and checkboxes have meaningful accessible labels.
- Utility selectors explicitly preserve a 56 dp minimum; other Material controls use CabinTheme's existing 56 dp minimum.
- New strings are supplied in English, German, Spanish, French, Italian, Portuguese and the Brazilian Portuguese override.
- Forms use numeric/password keyboard types where appropriate. Password fields stay masked.

## Verification status

The final coordinated run passed all 9 `VehicleUxReviewTest` regressions and all 13 `VehicleAcceptanceGoalsUiTest` workflows, with zero failures or errors in their JUnit XML reports. The compact service editor test verifies completion at 600×360 dp and 200% text. The encrypted backup test verifies that state restoration cannot silently produce a plaintext export.

The first run caught a real compact-editor measurement loop and an ambiguous test selector. The bounded custom editor and the scoped selector both passed the final rerun. The combined app suite reports 1,066 tests: 1,065 passed, zero failed, one existing skip; the diagnostics suite passed 155 tests.

Evidence: `app/build/test-results/testDebugUnitTest/TEST-com.cabin.ui.settings.VehicleUxReviewTest.xml` and `TEST-com.cabin.ui.settings.VehicleAcceptanceGoalsUiTest.xml`. Emulator release validation is coordinated separately by the root agent. No physical hardware acceptance is claimed.
