# Settings and projection UX review

Review date: 2026-09-30. Scope: navigation, search, shared settings controls, projection setup, phone selection, and connection progress. Existing feature work is preserved.

## Corrections

| Finding | Change | Verification |
| --- | --- | --- |
| Idle or streaming connection progress rendered an empty rounded bar on the home screen. | Omit the entire panel without a stage, failure, or retry action; avoid its idle polling timer. | New `ConnectionGoalsAcceptanceUiTest` case checks zero reserved height, failure visibility, and return to zero height. Root captured the original defect on the emulator. |
| Next/Previous in the setup guide inherited the prior step's bottom scroll position. | Reset to the beginning of the selected step so its heading and instructions come first. | New `ProjectionSetupTest` walks all steps from the bottom and checks the new progress heading remains visible. |
| Choosing a preferred phone left the previous phone visually selected as preferred. | Reload every phone preference after a change, matching the exclusive stored preference. | New `ProjectionToolsTest` selects another preferred phone and checks both rows. |
| Phone picker title and three header actions crowded short screens at 200% text. | Keep Back/Close pinned at 56 dp, limit the title to one line, and place the labeled Refresh action inside the scroll area. | New 320 × 240 dp, 200% text test checks dismissal, refresh, and empty-state reachability; saves a screenshot. |
| System Back from the phone picker dismissed the complete tools overlay. | Return to the preceding tools menu before dismissing the overlay. | Code-path review; existing parent dismissal remains unchanged. |
| Large offline help could clip recovery guidance. | Scroll the help text independently and keep its Close target at least 56 dp. | New 200% text test scrolls a long explanation to its recovery guidance and closes it. |
| Search's text Close control took space from the input at large text sizes. | Use a labeled 56 dp Close icon. | New 320 dp, 200% text test checks input width, empty results, and accessible dismissal. |
| Selecting a search result expanded unrelated vehicle disclosures. | Add explicit descendant search labels; matching ancestors open while unrelated sections remain collapsed. Vehicle reviewer wires caller labels. Manual tab navigation clears the previous search target. | New nested-disclosure regression; vehicle reviewer adds actual screen coverage. |
| Short or large-text navigation rails can overflow without a visible scrolling cue. | Draw a persistent scrollbar when the navigation rail overflows. | Updated large-text screenshots requested. Logs is intentionally excluded from debug navigation; the baseline screenshot's missing Logs entry is not a clipping defect. |
| Large text could make fixed-width rail Back/Exit labels consume its available height. | Use labeled 56 dp icons for large-text rail actions; very short large-text screens use horizontal categories. | Two new `SettingsNavigationTest` cases cover 800 × 240 dp and 800 × 480 dp at 200% text, including reaching FYT. |
| Bezel, media gain, and navigation gain sliders did not expose their purpose to screen readers and used the smaller default height. | Label each slider with its existing localized purpose/value and give it a 56 dp minimum height. | New `ConnectionGoalsAcceptanceUiTest` case checks all three labels and touch heights. |
| Connection-history controls deleted stored summaries immediately through their clear action or recording switch. | Confirm both actions, matching the privacy inventory; explain that summaries are shared across drivers. | Existing acceptance flow now checks cancel preserves records and recording, then explicit confirmation deletes them. |
| Audio preset Delete removed a preset immediately. | Name the preset in a confirmation, explain that current levels stay unchanged, and dismiss pending deletion when driver/guest identity changes. | New regression covers cancel, driver change, confirmed deletion, and unchanged live gains. |
| Native projection exposed an advanced-controls switch that revealed nothing. | Show the native capability explanation without the nonfunctional switch. | Existing native-backend acceptance case now verifies the switch is absent. |
| A real 800 × 480 dp emulator run with the 644 dp embedded settings overlay showed Search and the redundant SETTINGS heading wrapping until no tabs remained visible at 200% text. | Use a labeled Search icon in the large-text rail, omit its redundant heading, and keep the version on one line. | Strengthened the rail regression to 644 × 460 dp embedded bounds and require the active category fully visible before user scrolling. Root repeats the emulator scenario. |
| The active category could start partially below the rail viewport at large text sizes. | Scroll the selected tab fully into view on opening, selection, identity or layout change; ordinary user scrolling does not retrigger it. | The embedded rail regression checks the selected Launcher tab fits above Exit, then checks scrolling back to Search is respected and FYT remains reachable. |
| Minified device validation found the normal-font Back action lacked the contextual accessible label used by its icon variant. | Give the button parent the localized “Back to Cabin” description while preserving visible “Back” text. | Extended the existing normal-font short-landscape regression to check the label, 56 dp height, and click navigation; the full suite and both API 27/API 35 minified device checks passed after this correction. |

## Acceptance status

Eleven new regression cases are added across `ProjectionSetupTest`, `ProjectionToolsTest`, `ConnectionGoalsAcceptanceUiTest`, `SettingsInteractionTest`, `SettingsAccessibilityGoalsTest`, and `SettingsNavigationTest`; two existing connection cases now cover confirmed deletion and native-capability presentation.

Final XML results: **51 tests passed, zero failures/errors/skips** across these six classes (6 setup, 10 projection tools, 10 connection acceptance, 11 accessibility, 4 interaction, 10 navigation). The combined app run passed 1,065 tests with one existing skip; diagnostics passed another 155 tests. Root owns those coordinated executions.

The first runs identified Material3 exposing 44 dp track semantics and a separate merging node. The final controls expose purpose, range, disabled state, and SetProgress on one explicit 56 dp target. Verified continuous 50% adjustment, disabled bezel state, and stepped 3.6 → 4 adjustment.

Rendered acceptance: inspected `ux-phone-picker-short-double-font.png`, `ux-settings-short-double-font.png`, `ux-settings-rail-double-font.png`, and the actual emulator's embedded settings screenshots. Back/Close remain pinned in the 320 × 240 dp phone picker, with refreshed/empty content reachable by scrolling. The 800 × 240 dp settings view keeps its action and category rows. The final 644 × 460 dp rail screenshot shows FYT fully selected above Exit after navigating; its regression separately confirms initial Launcher fits fully before any scrolling and user scrolling to Search is respected. White space outside the constrained test viewport is the test activity background, not application layout.

No remaining software blocker was found in this reviewed scope. Physical TalkBack/rotary/steering input and real projection still require device acceptance.

The audio-preset confirmation adds two strings in English, Portuguese, Spanish, German, French, and Italian; XML parsing and matching-key checks pass. Other changes reuse existing localized resources. TalkBack labels and physical rotary/steering navigation still require a real device for complete input validation.
