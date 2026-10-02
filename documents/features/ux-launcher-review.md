# Launcher and dashboard UX review — 2026-09-30

Scope: dashboard media, app drawer, pagination, selection, recovery, narrow displays, enlarged text, and accessible touch targets. Existing launcher behavior and driver data were preserved.

## Findings and changes

| Finding | Change | Regression coverage |
| --- | --- | --- |
| A populated narrow app drawer reserved 168dp for page dots plus 112dp for toolbar actions, squeezing search to 40dp on a 320dp display. Screenshot review also exposed a three-line search label at 200% text. | Below 640dp, an accessible numbered page menu replaces the dots. Below 400dp, Refresh moves into the existing options menu. Wide layouts retain direct page dots. The search label is bounded to one line while its accessible name stays complete. | 320dp drawer with 12 apps at 200% text: search remains at least 200dp wide and at most 112dp high, page selection works, Refresh remains reachable, and entering a search resets pagination. |
| Empty filtered views gave no direct way back to the app list. An unmatched Recent search incorrectly suggested enabling history that was already enabled. | Empty results live in the available content area and offer Clear search and/or Show all apps. Search misses use the search-empty message. | Empty pinned view recovers with one action; recorded Recent results survive an unmatched search and clear. |
| Bulk selection required tapping the checkbox itself, leaving most of its row inactive. | The complete labelled row is a keyboard-focusable checkbox with a minimum 64dp height. Long names are bounded to two lines. | Clicking the label selects the row and updates its checked state and count. |
| Pending app actions could remain open when the active vehicle/preferences context changed. | Integration and local draft/confirmation state now follow the full preferences identity. | Changing vehicle preferences dismisses Clear pins without applying it. |
| The clear-pins confirmation incorrectly said the operation could not be undone. | Confirmation explains the existing Undo app changes recovery. | Clear followed by Undo restores the original pins through the actual UI. |
| At enlarged text sizes, the three installation-period chips could exceed the options dialog width. | Chips wrap to another row instead of being clipped. | Included in the enlarged-text options flow. |
| The first-launch dashboard media tile contained an empty body and enabled controls that the manager rejects without a streaming phone. Source selection looked like a static heading. | Added an idle explanation, a visible source dropdown chevron, and source-specific enabled states. Projection controls require STREAMING; Android media-session controls still work independently. Previous/next targets are 56dp. | Idle→streaming metadata/controls transition and independent Android media-source selection/session loss. |
| Four media controls plus card padding could exceed the previous 220dp compact breakpoint. | The breakpoint now accounts for four 56dp targets and padding (248dp). Enlarged text also triggers the compact layout earlier. | Existing compact dashboard tests are included in the coordinated launcher run. |
| The actual 800×480/200% dashboard screenshot showed the compact idle heading truncated to “Ready for …”. | Compact cards combine the source control with the track/idle label, using a short localized idle state. This leaves space for two text lines above the transport row. | A 188×188dp tile at 200% text asserts no text-layout overflow and visible 56dp controls; screenshot recorded. |

## Verification

- New UI regression class: `app/src/test/kotlin/com/cabin/launcher/LauncherUxReviewTest.kt`: **9 passed, zero failures/errors** in the final integrated run. Verified directly from the JUnit XML.
- Strings localized in English, Portuguese, Spanish, German, French, and Italian. All six resource files parse and contain matching keys.
- `git diff --check` passed after these edits.
- The root agent's final integrated run passed 1,065 app tests and 155 diagnostics tests, with one existing app-test skip. The combined UX delivery report records the full validation and minified-device results.
- Baseline visual evidence inspected: the actual 1280×720 fresh-install dashboard. Its empty media card was the basis for the media-state changes.
- Final screenshot review: `app/build/reports/ui/debug/ux-launcher-narrow-populated-200.png` shows a single-line search label, visible Options/page controls, and an unobstructed app row at 320dp/200% text. `ux-launcher-compact-idle-media-200.png` shows the complete “No music” state and separate visible transport targets at 188×188dp/200% text. No additional visual blocker was found in either image.
- The compact label uses its allocated width instead of shrinking around the glyphs. The regression retains the strict `TextLayoutResult.hasVisualOverflow == false` assertion; it also checks the source and transport touch heights.
- Physical head-unit input, lighting, and driving-condition evaluation still require the device. This review does not claim those checks were performed.

## Support recovery follow-up

The root agent delegated a second focused review of saved support exports and health-report comparison.

- Missing or permission-denied Android document pickers now show a recoverable error. Saved export payloads and already selected reports are retained; controls can be retried.
- Discard opens a confirmation naming the exact saved export and explaining that only its saved copy is removed. Cancel preserves interrupted preparations as well as complete payloads. Original log files are not removed.
- Health comparison uses a single bounded scrolling area for category controls and fields, including at 200% text in short landscape. Empty categories explain how to continue.
- Closing comparison retains the computed results. Show comparison reopens them without importing again. New report pairs and Clear selected reports reset the category to Changed; the clear button no longer misleadingly says Clear filters.
- `SupportWorkspaceAcceptanceUiTest` contains three additional cases and extends existing cases to exercise picker exceptions, payload preservation, discard Cancel/Confirm, large-text scrolling, close/reopen without picker calls, and category reset.
- Recovery strings have matching keys in all six supported languages.
- The final integrated run passed all **7 `SupportWorkspaceAcceptanceUiTest` cases**, with zero failures/errors. Verified directly from the JUnit XML.
