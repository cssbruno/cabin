# Native Honda diagnostic readings

The implemented operations use the original Mitsubishi Electric `DiagService`. They are not generic OBD requests and do not use guessed ECU addresses. Source is the OEM update's DealerDiag/LetDiag/DeveloperDiag/DiagApService/DiagServiceLib code, deodexed with baksmali API17. Evidence fingerprints and exact ABI/schema excerpts are in [diagnostics-evidence](diagnostics-evidence/).

## Implemented operations and proofs

| Native operation | Request kind | Evidence and result |
|---|---:|---|
| Head-unit hardware error history | `0x0A0201` | `HardErrorActivity$8`, `TaskHardwareGetError.run`, `ErrorModuleManager.getHardwareErrorList`. Returns records from `IErrorManagerApService.getHardwareErrorHistoryList`. |
| Delete head-unit hardware error history | `0x0A0901` | `HardErrorActivity$2`, `TaskHardwareDeleteError.run` invokes `requestDeleteHistoryList(listener,1)`. `ErrorModuleManager$MessageHandler` deletes the hardware records individually with `deleteHardwareError`. Does not select ACC history or Dealer Clear All. |
| Telematics DTC table snapshot | `0x2A0201` | `TcuDtcsActivity.doRequest/updateView`, `TaskTcuGetInfomation`, `AcuraLinkModuleManager`. TCU here is the telematics/communications unit, not the transmission ECU. Columns remain the OEM strings. |

Hardware history is reported as its native numeric `ecode`, optional `time`, `info`, and `extra_info`. For example the OEM parameter constants call `0x0515` GPS antenna error. The app presents raw hexadecimal IDs and source descriptions instead of converting them into OBD codes.

No native engine-wide DTC read/clear contract was established. The firmware's `DEALER_CLEAR_ALL` (`0x2F0C01`) is broader and is deliberately absent. `onLetDiagDtcClear` is an incoming factory command handled through LET rather than a proven narrow consumer DTC-clear API. B-CAN/F-CAN screens also exist, but their request-specific ID/field contracts are not exposed by this module.

## Service and request lifecycle

- Package: `com.mitsubishielectric.ada.appservice.diag`.
- Component: `.DiagService`.
- Service descriptor: `com.mitsubishielectric.ada.appservice.diag.IDiagService`.
- Callback descriptor: `com.mitsubishielectric.ada.appservice.diag.IDiagServiceListner` (OEM spelling).
- Transaction1: `initialize(strongBinder listener, int mode)` → boolean-as-int. Mode2 is exactly `DealerDiagApplication.getDiagMode()`; unlike modes0/1 it does not initialize LET/pre-shipment operation. OEM mode2 consumes an existing `/data/data/inlinediag/rcv_dealer_launch` marker if present.
- Transaction2: `terminate()` → boolean-as-int, unregisters the calling UID and cancels its tasks.
- Transaction3: `request(int kind, nullable Bundle)` → request ID. All three implemented requests use a null Bundle. Negative return means rejected; zero is a valid request ID.
- Transaction4: `cancel(int[] requestIds)` → void.

Service calls use a synchronous exception header, flags0. Callback1 `notifyResponse(requestId, Bundle)` and callback7 `notifyCancelEnd(requestId)` are one-way. Other message/factory callbacks never acknowledge these operations. The client correlates the generated **request ID**, not the request kind; callbacks arriving before the synchronous request returns are buffered. Old session callbacks are discarded.

### Hardware history response

`error` is an integer; zero means successful task response, nonzero means failure. Nonempty history supplies `data` as `Parcelable[]` of Bundles, each containing `ecode` and optional string details. **When history is empty, `TaskHardwareGetError` omits `data` entirely and returns `error=0`.** This omission is the real OEM empty response and is tested. Present fields with incompatible types fail closed.

### Hardware deletion response

The task sends progress/final callbacks under the same request ID:

- `error=0`, `status=0`, `continue=true`, `progress=0..100`: work in progress.
- `error=0`, `status=1`, `continue=false`, `progress=100`: finished.
- `status=-1` (write error), `status=-2` (no-empty error), nonzero `error`, or malformed progress: failure.

The client requires a successful prior hardware-history read, parked acknowledgement and explicit destructive confirmation. A terminal success starts a new hardware-history request. It reports verified empty only when that fresh successful OEM response returns no records. An acknowledgement with remaining records is not reported as a successful clear. There is no automatic retry after a timeout or uncertain write outcome.

### Telematics response

`error=0`; optional `rows` is a `Parcelable[]` of Bundles, each holding a `String[]` named `columns`. The task splits source text by CRLF and each row by tabs, preserving empty columns. A null source omits `rows`. No rows is described only as no returned data, not proof of no fault in another ECU.

`TaskTcuGetInfomation.onNotifyTcuInfomation` calls `onResponseFromTask(false,...)`: this is a subscription, not a finished one-shot task. The app cancels that exact request after receiving its first snapshot, then exposes the snapshot for inspection/copying. This prevents an orphan OEM subscription after the UI returns to Ready.

## Access and verification limits

The service manifest declares `com.mitsubishielectric.ada.permission.diag.ACCESS_DIAG_SERVICE` at protection level `0x2` (**signature**) and requires it on `.DiagService`. A normal custom-signed installation cannot obtain it simply by declaring the permission. The UI reports permission denial without suggesting that installation alone grants access. No signature/permission bypass is implemented.

The native client includes a 30-second operation deadline, serial background Binder work, request/session correlation, cancellation/termination cleanup, descriptor checks, idempotent destruction, synchronous-bind cancellation cleanup, and reentrant-observer guards. Disconnecting during deletion cannot roll back already dispatched work; status retains the unknown outcome. Leaving the activity dismisses confirmations, clears parked acknowledgement, invalidates the session and prevents delayed confirmation from dispatching a deletion.

The outer UI scrolls as one page so all controls and records remain reachable on 800×480 displays. The report copies only after the user presses its copy button. Source-confirmed operations have mock ABI/schema/lifecycle/progress/readback tests; the parent task runs combined Gradle/emulator checks. No Honda vehicle was connected during this implementation.
