# Independent MCU framing: Hardware Lab 0.4

Hardware Lab now contains an independently written MCU envelope encoder/streaming
decoder and an Android screen for analyzing saved binary captures. They do not load
SYU libraries. This is a transport-protocol component, not a working replacement for
the installed SYU service. The existing simulated Binder backend is separate.

## Pinned evidence

Static inspection on 2026-09-13 of the existing Joying UIS7862 2023-08-31 reference:

- APK: `artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.ms/190000000_com.syu.ms.apk`
- APK SHA-256: `4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577`
- Both `classes.dex` and `classes2.dex` inspected using Android SDK baksmali 3.0.9.
- Transmit envelope: `Ly/k;->b([I)[B`; MCU sender: `Ly/k;->t([I)V`.
- Receive envelope: `Ly/i;->b([B)V`; payload dispatch: `Ly/i;->f([BII)V`.
- MCU startup: `Ly/k;->j(I)V` creates `Ly/i`, connects the reader and selects a
  platform-dependent device path. It passes its baud argument to `Li1/c0;->e(I)V`.
- Serial abstraction `Li1/c0` uses `JniSerial.open/setup/read/write`, not
  `SerialNative.native_open_mbx`. The latter's declarations are not proof of this
  firmware's active transport. `ControlNative.fyt_vehicle_read` is a separate path;
  its name alone does not identify the MCU stream.

For reproducibility, disassemble both DEX files with SDK baksmali's `disassemble`
command, then inspect those method descriptors. The inspected `y/k.smali` SHA-256
was `67b4de3f52163c19e083fbbd68363de3a23f6d8d9ae707584aac66ce2964b606` and
`y/i.smali` was `f1a169fceda442381d6240d4e973f6a4df7a72dcd82d4655e778fd7c0d99357d`.
Smali formatting hashes are tool-version dependent; the APK hash pins the evidence.
No vendor implementation or binary is included in the Cabin diagnostics library.

## Observed envelope

| Offset | Contents |
| --- | --- |
| 0–1 | `88 55` |
| 2–3 | Unsigned payload length, big endian |
| 4 onward | Payload, exactly the declared number of bytes |
| 4 + length | XOR of both length bytes and every payload byte |

The reference receiver rejects payload lengths greater than 512. Zero-length
envelopes pass framing; they have no first payload byte and no inferred meaning.
Both the observed sender and receiver use this envelope. Direction cannot be
inferred from the header. This is an MCU serial envelope, not a raw CAN frame:
there is no established CAN arbitration ID/DLC mapping here.

Hand-calculated vectors (synthetic, not hardware captures):

```
88 55 00 02 C1 04 C7
88 55 00 03 80 FF 00 7C
88 55 00 00 00
```

`C1 04` is also visible in the reference's steering-learning query sender, but the
Lab does not send it or interpret its response. An envelope checksum proves only
internal framing consistency, not firmware identity or correct vehicle behavior.

## Implementation and recovery

`FytMcuCodec` is pure Java. It consumes arbitrary input chunks, retains at most 516
incomplete bytes between calls, and stores at most 517 bytes while decoding. It
copies completed payloads and reports their absolute capture offsets. Encoding
returns bytes only and has no transport access.

On checksum or length failure it advances one byte and searches again, including
overlapping headers. Valid payloads may contain header bytes. A plausible but
incomplete length waits for more bytes; the decoder does not guess that an embedded
header must start a new frame. At EOF the pending bytes are reported as incomplete.
Create a fresh decoder after a disconnect; no previous partial frame should carry
into a new connection. Instances are thread-confined and callbacks must not re-enter.

The recovery implementation is independent; it does not reproduce the reference's
1024-byte append/reset strategy. Tests cover literal vectors, all split points,
maximum payloads, overlap, corrupt candidates containing valid frames, truncation,
mutation isolation, invalid arguments, and a deterministic noisy-stream test.

`McuCaptureAnalyzer` reads raw binary files up to 8 MiB with a 4096-byte input buffer.
It reports SHA-256, counts of complete/invalid/incomplete data, the first eight frame
offsets, and a bounded histogram of first payload bytes. It does not retain all
frames, decode vehicle state, claim direction, or accept text hex dumps as binary.
Read errors and oversized inputs fail the analysis instead of returning a partial
success. Caller owns and closes the input stream.

## Try it

Build with `./gradlew :diagnostics:testDebugUnitTest :diagnostics:assembleDebug`.
Open Cabin → Car Settings → My car → Diagnostics → **Analyze saved MCU capture** → **Analyze example data**.
Expected: 3 valid frames, 1 checksum failure, 1 invalid length, 5 incomplete bytes,
13 discarded bytes. **Open capture file** uses Android's file picker; **Save analysis
report** exports JSON. Example reports are explicitly marked `synthetic-example`.

Example asset SHA-256: `fca9ce65932dfd56221df57e6295b8aa58ce1d92d9b2b4da9f259698b723e3e9`.

Validation on 2026-09-13: `:diagnostics:testDebugUnitTest`,
`:diagnostics:assembleDebug`, and `:diagnostics:lintDebug` succeeded offline.
All 33 tests passed, including 16 new codec/analyzer tests. Lint reports no errors
and 27 warnings, including six new untranslated-text warnings in the English-only
capture screen. UI interaction on a physical unit has not been tested.

## Next replacement milestones

1. Match the current head unit's SYU APK, native libraries, platform and MCU version
   to evidence. The local Joying package remains a public reference, not a unit dump.
2. Validate framing against real, direction-separated MCU captures and map a small
   read-only payload subset to telemetry. An opcode count alone cannot do this.
3. Implement the matching native serial adapter and ownership/reconnect policy.
   Existing SYU also reads/writes that stream; a second reader can steal its data.
4. Implement verified command routing, acknowledgments, startup, sleep/wake,
   audio/DSP and the required Binder modules before attempting service replacement.

No real device node was opened, SYU service disabled, firmware flashed, or head-unit
compatibility established by this work. The production Cabin integration still uses
the existing SYU service.
