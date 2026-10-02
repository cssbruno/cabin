# Log and export follow-up — 2026-10-01

This pass reviewed log search, continuation, live sessions, export accuracy, keyboard search, and recovery when Android cannot open a document picker.

## Confirmed fixes

1. **Files and filters must match the exported results.** A successful search now commits its input and source snapshot together. Editing any search input disables continuation and export until another successful search, with an explanation beside the results. Cancelled or failed searches cannot silently replace the snapshot behind existing results.
2. **Missing selected files cannot produce an unnoticed partial export.** Search verifies every selected path against a fresh inventory. If one disappears, the selection is reconciled and a source-change message requires another explicit search. This issue was found during the settings agent's independent review.
3. **Live results cannot export an older static search.** Starting live follow clears the old export snapshot. Pausing explains how to create a fresh export. Follow also waits for a logging session that starts after the workspace opens, and adds newly available files to the chooser.
4. **Validation and keyboard actions are useful.** Invalid date/time input shows the range validation message. The search field supports the keyboard Search action and dismisses focus after submission. Empty bookmarks now explain how to create one, and an empty file list disables Select all.
5. **A missing document picker preserves recovery and releases the export button.** Single-file exports retain the queued copy, explain where to resume it, and allow another export attempt. An unavailable picker no longer locks exporting until the viewer closes.

## Regression coverage

Six new cases cover changed filters plus keyboard search and exact exported content; invalid dates after a prior search; a missing selected file and explicit recovery; paused live results; a session created after opening; and unavailable picker recovery plus a new export attempt.

The settings agent independently reviewed these changes and rechecked the missing-file fix and notice scrolling. English, Portuguese, Spanish, German, French, and Italian strings are included. Final execution passed all eight workspace UI cases, two single-file explorer cases, and seven existing support acceptance cases. The compact log layout test now configures actual system font scale and asserts the rendered text uses 200% scaling. Final suite/build results are recorded in the combined follow-up report.
