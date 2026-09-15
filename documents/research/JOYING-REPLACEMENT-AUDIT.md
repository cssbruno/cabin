# Joying service replacement audit

Inspected 2026-09-13. There is enough local evidence to begin an independent serial
adapter and firmware-specific protocol implementation. A full service replacement
also needs CAN-profile selection, audio-driver initialization, and a tested power
state machine. Hardware Lab 0.4 currently supplies only framing and offline analysis.

This audit concerns the downloaded Joying UIS7862 2023-08-31 reference. It does not
establish that this is the firmware installed on the user's current unit.

## Evidence and reproducibility

The extracted system/vendor properties identify Android 10, `ums512`, and
`ums512_1h10`. The SYU service APK identifies itself as version `2.23.0711.1001`,
versionCode `2123071110`, min/target SDK 17. Manifest metadata gives compilation time
`2023-07-11 10:01:56` and platform label `C2`; that label is not the runtime MCU
platform selector.

[JOYING-SERVICE-CONTRACT.json](JOYING-SERVICE-CONTRACT.json) records exact local
paths, SHA-256 hashes, native-library sizes, service factories, and sound-driver
classes. APK hash: `4b428302e29c9e2503ccf7844a127f5bed59450736317629aa42b5eb35a9b577`.

Inspection used Android SDK baksmali 3.0.9 on both DEX files, aapt2 for manifest
metadata, NDK 29 llvm-objdump/readelf on ARM64 libraries, and direct reads of the
extracted init, ueventd and SELinux context files. To repeat the class analysis,
disassemble both DEX files, then inspect the method descriptors listed below.
The JSON maps were extracted from the action constants and factory switch tables;
19 action entries, 20 toolkit entries, and 14 sound-driver cases were checked.
No vendor binary was executed. No firmware installation or vehicle test was performed.

## 1. MCU transport can be implemented without the vendor serial library

Confirmed chain:

```
app/App.m() → y/k.j(baud) → i1/c0 → com.syu.jni.JniSerial
                                          ↓
                                   libjni_serial.so
                                          ↓
                                 Linux serial device
```

`JniSerial.open` at ELF address `0x1080` calls `open` with flag value 2
(`O_RDWR`). `setup` at `0x10f4` uses `tcgetattr`, `cfmakeraw`, speed setters,
`tcflush`, and `tcsetattr`. Java passes 8 data bits, ASCII `N`, and one stop bit.
The native setup stores `VTIME=10`, `VMIN=0`; this is the termios read-timing
configuration, not a timeout passed to `JniSerial.read`.

`i1/c0.c()` requests up to 512 bytes with `JniSerial.read(fd,512,5)`. Native code at
`0x15c4` uses the third argument as a retry counter after empty/failed reads, not
milliseconds. `write` at `0x16c0` wraps a native write. An independent adapter should
explicitly handle partial writes, interruption, errors, and cancellation rather
than assume a Java call means the whole frame was delivered.

The framing established earlier remains `88 55`, big-endian length, payload, XOR.
See [JOYING-MCU-FRAMING.md](JOYING-MCU-FRAMING.md).

### Device and baud selection are conditional

`g/f.t(String,boolean)` resolves `ro.fyt.platform`, falling back to
`sys.fyt.platform`, and sets the internal platform family/subtype. `y/k.j(I)` then
selects the device:

| Internal family (`g/e.a()`) | MCU path |
| --- | --- |
| 1 | `/dev/ttyMbx3` if `openQst()` returns 0; otherwise `/dev/ttyS3` |
| 3, 5, 7, 9, 10, 11 | `/dev/ttyS2` |
| 6 | `/dev/ttyS3` |
| 2, 4, 8 and default | `/dev/ttyS0` |

`App.m()` chooses 115200 for subtype IDs 9, 24 and 39. Otherwise it reads
`ro.fyt.mcu_type`: 1 chooses 115200, other values choose 38400. Do not equate the
processor string `ums512` with a resolved internal family or assume one baud rate
for every Joying unit. Runtime property values are still needed.

Vendor `ueventd.rc` assigns `/dev/ttyS2` and `/dev/ttyS3` mode 0660, owner/group
system/system; `/dev/ttyS0` is root/system 0660. The vendor file-context table labels
`/dev/ttyS2` as `console_device`. DAC permission and SELinux authorization both need
to be satisfied. The native library's presence alone grants neither.

## 2. The Binder surface is larger than the old reference

The inspected Joying `app/ModuleService` advertises **19 actions**, not the 16 in
the old 9853i reference. `b/i.G2(I)` exposes **20 toolkit module factories**, IDs
0–19. IDs 16/17/18 add emitter, gsensor, and `gestrue` (vendor spelling).
ID 19 returns `s0/b` and has no action in the inspected ModuleService action map;
do not invent a compatibility action for it.

The relevant existing IDs remain MAIN=0, RADIO=1, BT=2, SOUND=4, CANBUS=7,
STEER=10, AMP=15. The exact factories are in the JSON. The APK retains descriptors
`com.syu.ipc.IRemoteToolkit` and `com.syu.ipc.IRemoteModule` despite obfuscated
implementation class names (`x/d`, `x/c`). Factory availability is not proof that
all modules work on a particular board.

The manifest requests shared UID `android.uid.system`, DEVICE_POWER, INJECT_EVENTS,
REBOOT and boot-completion handling. Replacing its package with a normally signed
APK is not equivalent to replacing this privileged integration. A deployment design
needs suitable signing/platform integration or an explicitly authorized native
broker with a narrow app-facing interface.

## 3. CAN data is dispatched to a selected decoder

`y/i.f([BII)` dispatches MCU payload byte `E3` by stripping that byte and calling
`f0/tp.d.M2(bytes,offset,length)`. The base `f0/rp.M2` is empty: the useful decoder
comes from the selected implementation. Treating the base class as a working
generic CAN decoder would silently discard the vehicle data.

The same dispatch handles `61` as a two-byte, big-endian CAN configuration response.
It explicitly treats `FFFF`, `FFFE` and `E000` specially. This must not be blindly
converted into the launcher's complete profile identity.

There is an alternate outbound path: `y/k.t` routes first-byte `E3` to `g0/v.a.f`
when `f0/tp.h == 1`, bypassing ordinary MCU framing. Therefore a universal rule that
every CAN command goes through one MCU serial port would be wrong.

Next decoder work should trace the current unit's selected CAN profile through
`f0/wp` into its concrete `M2` implementation, then verify one bounded read-only
message against captures. No Honda door, temperature, or raw CAN-ID mapping was
established by this audit.

## 4. Actual DSP driver code is available

`t0/g.W(I)` selects a sound driver and reports the selected sound identity on
callback field 1. Important cases:

| Sound identity (not toolkit module ID) | Implementation |
| --- | --- |
| 6 | `module/sound/C32107` |
| 7 | `module/sound/a` (obfuscated class; retain this exact mapping) |
| 10 | `module/sound/C32107S` |
| 11 | `module/sound/C7604` |
| 12 | `module/sound/DU561` |
| 13 | `module/sound/C7738` |
| 14 | `module/sound/C7738FC` |

For C7604, the path is:

```
eqGain → D0 → b0 → coefficient helper C7604$e → B0
                                                   ↓
                            i1/m.i → JniI2c.writeNormal
```

Confirmed lower-level facts:

- `C7604.F3` initializes to I²C address `0x1C`; active address/board still need
  verification. `B0` writes three bytes per coefficient, most significant byte first.
- `B0` prefixes the data with a command byte and a two-byte register address, then
  issues a separate write containing `A4 00 00`.
- `b0` chooses command `89` or `84` and a register from `U2`, depending on its
  branch. This is a DSP write format, separate from MCU envelope/opcode meanings.
- Native `JniI2c.writeNormal`, address `0x12A8` in ARM64 `libjni_i2c.so`, uses
  ioctl `0x0706` (`I2C_SLAVE_FORCE`), concatenates prefix/data and performs a write.
  This path uses a plain write after selecting the address, without an `I2C_RDWR`
  or SMBus block-write call.
- Vendor ueventd gives `/dev/i2c-*` mode 0666; platform SELinux labels these as
  `i2c_device`. This does not establish that the current app can access them.

This is substantially more evidence than a DSP UI command map. It makes an
independent driver investigation practical, but is not a verified set of safe
coefficients or a tested driver. Still to trace: boot/program loading, address and
bus selection, coefficient formulas/scaling, complete register tables, mute/unmute
ordering, readback, and recovery after power transitions. The Equalizer APK's
`7604` filename alone does not prove the chip fitted to the user's unit.

Radio also has a native path: `r0/h` delegates tuning and source operations to
`com.android.fmradio.NativeRadio`. Replacing the sound driver would not automatically
replace the radio driver or its audio routing. Bluetooth has a separate toolkit
module and implementation; it was located but not fully reconstructed here.

## 5. Sleep is a protocol sequence, not just an Android lifecycle callback

`y/i.f` routes payload prefix `01 00` into `y/i.j`. Inside that handler, `89`
has subcommands `53`, `54`, `55`. Thus these are full incoming payload prefixes
`01 00 89 53/54/55`, before adding the MCU envelope.

- `53` begins/advances sleep preparation, updates `sys.fyt.sleeping`, checks
  counters and platform conditions, and may send `01 AA 5F`.
- `54` clears the local sequence counter and sends `01 AA 61`.
- `55` checks `canSleep`, sends `01 AA 62` on accepted paths, waits, updates
  sleep counters, handles audio/power state and enters the platform sleep path.

These describe observed reference branches, not a complete state machine or
instructions to transmit those bytes. Timing, repeated messages, refusal states,
wake transitions and loss-of-connection behavior remain to be specified and tested.
`app/ToolkitService.onDestroy()` also transmits `AA 56`; even stopping that service
is not behavior-free.

## Development order

1. **Transport:** independent native serial adapter, tested on pseudo-terminals
   with cancellation, short writes, reconnects and frame replay. Use explicit
   platform configuration; never probe by sending guessed MCU commands.
2. **Read-only vehicle backend:** one confirmed CAN profile, full-frame length
   validation, fresh/stale/disconnected state and Binder callback tests.
3. **Power and source coordination:** model the observed handshake and verify
   sleep/wake plus radio/CarPlay/audio-focus interactions on a bench.
4. **One DSP backend:** select by confirmed sound identity, reproduce and validate
   one driver including its initialization/readback, then broaden support.
5. **Replacement integration:** implement required remaining module contracts and
   platform access; validate replacing the vendor process with a recovery path.

Do not run a second reader against the vendor-owned serial stream as a capture
strategy: it can consume messages the original service needs. Saved captures or
instrumentation of the existing owner avoid that race.

The next target report needs the service APK hash, MCU version, current CAN profile
and sound identity, plus `ro.fyt.platform`, `sys.fyt.platform`, `ro.fyt.realplatform`
and `ro.fyt.mcu_type`. Hardware Lab's current property whitelist does not yet collect
those last four keys. Reading them would resolve configuration gaps; it would not
by itself validate hardware compatibility.
