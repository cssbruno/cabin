# Settings and projection follow-up — 2026-10-01

Reviewed the previous UX report and current navigation, search, projection setup, phone picker, and shared settings components. This pass targets specific remaining interaction gaps.

| Confirmed gap | Change | Regression |
| --- | --- | --- |
| The compact horizontal category row starts at its first tab even when the saved active category is at the far end. | Bring the active chip into view on opening, active-category change, identity change, or width change. User scrolling does not retrigger the effect. | 320 dp / 200% settings opens FYT visibly, restores it, and respects scrolling back to CarPlay. |
| Opening settings search does not direct physical-keyboard input to its query. | Request query focus when the dialog opens or restores. | Query starts focused, survives restoration, then Tab → Close → result → Enter chooses the intended category. |
| Phone search has a floating label with unlimited lines, allowing long translations to inflate its height at large text sizes. | Bound the visual label to one ellipsized line and retain its full localized accessible description. | German / 320 dp / 200% phone search stays at most 112 dp tall, preserves its query after restoration, and leaves phone selection and Close reachable. |
| An emulator screenshot showed two search matches extending beneath the open keyboard with no overflow available to reveal the second match. A subsequent real-IME check exposed clipped category text with the status bar also visible. | Apply keyboard and system-bar insets to the dialog. Below 320 dp usable height, use compact spacing and one ellipsized visible label per result, retaining the complete setting and category in its accessible description. | A 24 px status-bar inset plus 260 px IME inset at 200% text reduces the results viewport. Both complete result buttons, each at least 56 dp tall, must scroll entirely above the keyboard, permit selection, and restore the viewport when the IME disappears. |

Also added a regression for the existing phone-picker Back behavior: Android Back returns to the tools menu without closing the overlay; explicit Close still closes it once.

Final automated acceptance: the saved XML reports confirm **5/5 SettingsFollowupUiTest cases and 11/11 SettingsAccessibilityGoalsTest cases passed**, with no failures, errors, or skips in either class. The full coordinated suite passed with 1,090 app tests, one existing app skip, and 155 diagnostics tests. This includes the final compact-row refinement and the combined 24 px status-bar / 260 px IME regression, which verifies the complete actionable bounds of both results, their minimum 56 dp height, selection, and restoration after the keyboard disappears.

The large-text tests set Android's font scale before composition and assert 2.0 from the rendered text's layout, including the search dialog after restoration; the prior long-help and compact search-dialog tests use the same setup. Existing size, navigation, and restoration comparisons are preserved.

Rendered acceptance: the final 800 × 480 emulator capture at 200% text shows the complete first result button at y142–198, above the keyboard at y219, with no cropped category line. Its accessibility description retains “Maintenance reminder, Car Settings.” The second capture is at a partial scroll position: its label and full accessible description are readable, and tapping that description successfully opens the intended section while the keyboard is still open. That capture stops 4 px short of showing the whole second button; the separate automated inset regression verifies that both entire buttons fit after scrolling fully into view.

Source implementation and independent review are complete; `git diff --check` passes. No new user-facing strings were required. Emulator and automated checks do not certify physical TalkBack, rotary/steering input, projection hardware, or head-unit behavior.
