# Vehicle forms follow-up — 2026-10-01

This pass reviewed current maintenance, trip, parking-note and backup forms after the initial UX review. It preserves pending physical-device acceptance.

## Confirmed fixes

1. **Locale decimal input and actionable validation.** Maintenance mileage, distance intervals and service costs previously used `toDouble` directly and rejected decimal commas offered by Portuguese, German, French, Italian and Spanish keyboards. These fields now accept a decimal comma or point and reject mixed separators, grouping, non-finite values and exponents. Date fields identify their purpose and document their ISO order. Invalid dates, missing prerequisite odometer readings, decreasing readings and backdated/future readings now show an explanation beside the field, focus the first invalid input and preserve drafts. Existing limits are retained.
2. **Stale service edits.** Deleting a service-history entry while its correction editor was open previously caused Apply to fall back to recording a new service completion. The editor now reports the missing record and disables Apply. Apply also re-reads the correction target; the ledger rejects completion of a removed maintenance item. A stale edit cannot create replacement history or advance the service schedule.
3. **Private parking drafts after deletion.** Forgetting parking, deleting it from privacy controls or expiring it previously removed preferences while leaving the saved UI draft visible and restorable. The editor now clears its draft when previously saved parking data disappears. Capturing a new location still preserves an unsaved note.

Backup export encryption intent and memory-only passwords were rechecked against the earlier restoration regression; no new backup source change was needed in this pass.

## Validation

The final coordinated run passed all of the following suites:

- `VehicleFormFollowupTest`: six UI workflows, including Brazilian Portuguese decimal entry, invalid-date focus, stale correction, and parking-note restoration.
- `MaintenanceFormInputTest`: decimal acceptance and rejection boundaries.
- `VehicleGoalsTest`: removed-item completion rejection plus existing vehicle model checks.
- Existing `VehicleUxReviewTest` and `VehicleAcceptanceGoalsUiTest` retained; the prior generic odometer-error assertion now checks the specific explanation.

Fourteen new strings are supplied in all six supported languages and the pt-BR override. No hardware behavior is certified by these form tests.

The six form cases, one decimal-parser case, eighteen vehicle-model cases, nine prior vehicle UX cases, and thirteen vehicle acceptance UI cases all passed. This follow-up added eight vehicle regressions. Independent review of final emulator captures confirmed readable maintenance fields and resolved search/keyboard overlap; no further blocker was found in that scope.
