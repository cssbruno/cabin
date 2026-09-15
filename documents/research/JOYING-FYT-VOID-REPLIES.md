# Joying FYT empty replies

## Evidence checked on 2026-09-15

Reference APK: `artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.ms/190000000_com.syu.ms.apk`

SHA-256: `4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577`

Disassembled classes and methods:

- `app.ToolkitService.onBind` returns `b.i.F2()`.
- `app.ModuleService.onBind` maps `com.syu.ms.canbus` to module 7 and calls `b.i.G2(7)`. Both service paths reach the same module implementation; switching routes cannot repair a reply-format mismatch.
- `x.d$a.onTransact`: toolkit transaction 1 reads the module ID and writes an exception header followed by the module binder.
- `x.c$a.onTransact`: module transactions 1 (command), 3 (register), and 4 (unregister) dispatch the method and return true **without writing any reply body**. Transaction 2 (get) writes an exception header and its result.
- `x.c$a$a`: the vendor proxy sends transactions 1, 3, and 4 with flag 1 (one-way); get uses flag 0. The stub does not branch on flags.
- `x.b$a$a.D0`: callback transaction 1 sends field ID, integer array, float array, string array with a null reply and one-way flag. Cabin already accepts this callback shape.
- Follow-up inheritance check: module 7 resolves to `f0.xp.S2()`, with `f0.xp -> f0.rp -> x.c$a`. Neither CAN subclass overrides `onTransact`, so the empty-reply stub is the actual CAN dispatch path. `f0.xp.register` accepts non-null callbacks and field IDs 0 through 1199. With cached flag 1, field 1000 sends the selected profile in a one-element integer array (and an additional field 1037 callback); Cabin consumes field 1000 and safely ignores unsubscribed fields.

## Defect and correction

Cabin sent synchronous module calls and required at least four reply bytes for every transaction. Joying's successful void calls therefore threw `Invalid SYU reply size`. CAN registered its first field, disconnected, cleared subscriptions, and retried. The separate live CAN diagnostic receiver had the same check.

Both transports now accept an empty reply only for their void module operations. Toolkit lookup and module readback still require a reply, and nonempty exception replies still propagate errors. An unhandled transaction still fails. Calls are not resent after an empty reply, avoiding duplicate commands.

Synchronous dispatch remains for compatibility with legacy FYT stubs that write exception headers. Joying's inspected stub supports that dispatch and leaves the reply empty. This compatibility path does not claim that accepting a command proves a physical effect. IPC remains on background workers.

## Verification and limits

The new empty-reply transport regression failed before the correction. Coverage also exercises the existing exception-header format, actual CAN controller registration/callbacks across 35 seconds of refreshes without rebinding, and live CAN diagnostic registration/cleanup using empty replies.

Follow-up coverage runs the shared transport on Android API 29 and 35, checks registration argument order and exactly one command dispatch, rejects empty toolkit lookup replies, and delivers CAN callbacks with Joying's null reply / one-way flag. Radio, sound, audio-source and readback caller tests cover the other users of the shared transport. The release build check includes R8 minification.

This establishes a concrete protocol defect against the supplied reference firmware. The installed Joying firmware and physical controls still require a head-unit test; this does not establish every vehicle field mapping or fix CarPlay's separate socket issue.
