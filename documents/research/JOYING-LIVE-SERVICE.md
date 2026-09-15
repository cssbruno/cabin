# Live SYU replacement implementation

Goal: fully replace the SYU service on the target Joying unit. This goal is not complete.

## Implemented command path

`ReplacementService.startOwnedConnection` accepts ownership of a configured MCU transport and builds a `LiveReplacementSession`. Module 1 Binder commands run the reference radio planner and write the resulting frame through `RadioMcuTransport`. The receive thread decodes radio and optional Honda feedback into toolkit callbacks. Service polling stays off the receive thread. Switching to replay or destroying the service closes the live connection and retires its Binder endpoints.

`RadioMcuTransport` accepts typed radio commands only. One writer runs at a time; overlapping commands fail immediately rather than accumulating an unbounded queue. Unsupported commands fail before I/O. A write failure may indicate partial transmission, so it closes the connection and never retries. EOF also disables transmission. Closing the native transport cancels in-flight I/O. Diagnostics count completed OS writes, not device acknowledgements or successful tuning.

The entry point is internal, not an exported configuration transaction. The replay UI does not select live mode. A deployment/bootstrap layer must establish ownership before passing a native connection; `TIOCEXCL` does not detect existing vendor readers. Startup sends no guessed initialization commands.

## Completion requirements and current evidence

The optional SOUND endpoint can now be owned by a [C7604 audio session](JOYING-AUDIO-SESSION.md), coordinating startup mute, DSP initialization, routing, calibrated volume and shutdown. MAIN Binder volume/source integration and target lifecycle policy remain incomplete.

| Requirement | Current state / evidence needed |
| --- | --- |
| Match exact target hardware and firmware | Static Joying APK/driver hashes exist in `JOYING-SERVICE-CONTRACT.json`; target unit match remains unverified. No ADB device attached at the September 14 check. |
| Standalone installation, boot, service discovery and client compatibility | Toolkit wire contracts implemented; package/action integration, required privileges, boot startup and existing factory client interoperability remain incomplete. |
| Exclusive device ownership and recovery | Native serial cancellation and bounded I/O implemented. Vendor handover, ownership verification, reconnect/bootstrap and rollback require implementation and device validation. |
| MAIN / power / source routing | Sleep sequence observation exists. Power acknowledgements, Android sleep/wake lifecycle, source arbitration and ACC handling remain incomplete. |
| RADIO | Driver-1 command subset now writes an owned transport. Band/current-frequency and indexed AM/FM preset receive subsets exist. Initialization, remaining command/get/state contracts, RDS and hardware validation remain incomplete. |
| BT | Native service implementation incomplete; pairing, calls, microphone, media and audio routing require target evidence and implementation. |
| SOUND / amplifier | C7604 coefficients, packet writer, [native status readback](JOYING-DSP-READBACK.md) , [program-table loading](JOYING-DSP-PROGRAM.md) and [master gain writes](JOYING-DSP-GAIN.md) and [DSP channel mute/balance](JOYING-DSP-CHANNELS.md) exist. Driver initialization, volume/mute, source routing, readback and complete DSP service remain incomplete. |
| CAN | Honda reference receive subset exists; remaining target CAN fields/commands, lifecycle and physical controls remain unverified. |
| DVD, iPod, TV, TPMS, DVR, STEER, CUSTOMER, OBD, TEST, CANUPDATE, AMP, EMITTER, GSENSOR, GESTURE, sensors | Inventory each target capability and implement its required contracts. Unsupported modules cannot be claimed complete simply because the current app does not use them. |
| End-to-end independence from SYU | No test yet proves operation with the vendor service absent. Existing `LiveCanReceiver` uses SYU and is an evidence/client tool, not proof of replacement. |
| Delivery and recovery | Build/signature checks exist; complete replacement APK/deployment package, verified rollback and cold-boot/ACC-cycle validation remain outstanding. |

## Verification of this increment

Tests exercise exact radio bytes, rejection before I/O, concurrent command rejection, failed-write closure without retries, EOF, cancellation, Binder-to-transport execution, stale Binder rejection and service transition back to replay. Native serial code is unchanged. Tests use controlled transports, not a physical tuner. Passing these tests does not demonstrate a fully replaced service.

Next implementation work should address verified startup/ownership and MAIN/audio lifecycle contracts, while extending the remaining module contracts from target evidence. Do not replace the full goal with a preview-only or factory-service-dependent application.
