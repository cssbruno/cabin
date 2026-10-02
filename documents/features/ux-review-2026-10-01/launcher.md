# Launcher editing review — 2026-10-01

This follow-up reviewed app-library editing, folder names, bulk selection, pinned order, keyboard focus, compact dialogs, and enlarged German/Portuguese text. It preserves the earlier implementation and pending physical-device acceptance.

## Confirmed issues and fixes

1. **Folder capacity discarded a draft.** `createFolder` rejects a 33rd folder, but the dialog previously cleared the field unconditionally. The limit is now a shared constant, explained in the dialog; creation stays disabled at capacity, renaming remains available, and drafts clear only after a successful save. Removing a folder permits the pending draft to be saved with the keyboard's Done action. Folder actions wrap and retain 56dp touch heights.
2. **Bulk Pin could apply only part of its preview.** Confirmation remained enabled when the selection exceeded the remaining pin capacity, after which persistence truncated the selection. Confirmation now reports the remaining slots and stays disabled until the entire selection fits. Cancel retains the selection for correction.
3. **Reorder assumed uniform rows and reused focus by position.** Drag destinations previously divided movement by a fixed 72dp height, despite long/enlarged labels producing taller rows. Destinations now use measured row centers. Rows are keyed by app identity so repeated keyboard moves follow the chosen app, and 56dp arrow controls have localized action names. Visual names are bounded to two lines while accessibility labels remain complete.
4. **App rename was cramped and lacked a useful Done action.** A compact Portuguese input test exposed an AlertDialog measurement loop. Rename now uses a bounded Dialog/Surface with a weighted scrolling body and separate reachable actions; field labels stay on one line, and keyboard Done saves the draft. The dialog consumes software-keyboard and system-bar insets and reduces padding/heading size when the remaining height is compact. Screenshot review then caught a cropped floating field label with the keyboard inset present: the compact heading now scrolls with the form, leaving enough viewport for the complete outlined field. Android Back keeps its existing discard behavior. Saved app data changes only when Save or Done is invoked.

## Validation

- `LauncherEditingUxTest` adds six workflows: folder capacity/recovery, German folder actions at 200%, whole-selection pinning, Portuguese compact rename with Back/Done, repeated keyboard reorder, and measured drag with long names at 200%.
- Existing `LauncherGoalsUiTest` keeps its state-restoration coverage and now uses the explicit localized reorder action name.
- Five follow-up resource keys match across English, Portuguese, Spanish, German, French, and Italian; XML parsing and `git diff --check` passed.
- Native screenshot capture targets the active dialog, producing `ux-folder-actions-german-200.png`, `ux-app-rename-portuguese-200.png`, `ux-app-rename-portuguese-200-keyboard.png`, and `ux-pin-reorder-long-labels-200.png` under the debug UI report directory. The keyboard case dispatches a 160px IME inset plus a 24px status bar, checks action heights and bounds, and verifies the complete outlined field can scroll into view above the keyboard. Padding reduces further to 4dp when the usable height falls below 200dp.
- Screenshot review found that an outer `LocalDensity` override did not enlarge the separate dialog window. All three new enlarged-text workflows now set the Android resource font scale before composition and assert both the dialog resource configuration and its actual `TextLayoutResult.layoutInput.density.fontScale` equal 2.0. Earlier normal-size dialog captures are not accepted as 200% evidence.
- **Final validation passed:** all six `LauncherEditingUxTest` cases plus all six existing `LauncherGoalsUiTest` cases (12/12), including the 24px status-bar + 160px keyboard case and verified 200% dialog font scale. The final root-coordinated suite passed 1,090 app tests (one existing skip) and 155 diagnostics tests.
- Final native screenshot review confirmed readable German folder actions, clean two-line reorder names, and the complete Portuguese outlined rename field: its floating label, entered text, borders, Cancel, and Save remain visible between the injected status bar and keyboard. The compact-heading and 4dp-padding corrections are included in the passing run. No further launcher production or test changes are pending; source and review are complete. No Gradle process or emulator was operated by this reviewer.

## Independent peer check

Read the vehicle follow-up changes in maintenance parsing/forms/model and parking-note handling. No blocking issue was found in the bounded decimal parser, field-specific date validation, correction re-read, removed-item completion guard, or saved-draft clearing. This was a source review; the root agent owns execution evidence.

Physical head-unit keyboard/rotary behavior and driving-condition readability remain device acceptance work. These checks do not certify that hardware behavior.
