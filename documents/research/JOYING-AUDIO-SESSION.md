# C7604 audio session

`C7604AudioSession` owns the configured DSP writer and amplifier mute controller. The caller must establish exclusive hardware ownership and supply the matching program image, routing profile, platform switch and calibration. Construction performs no I/O.

Startup holds amplifier mute while resetting the DSP, checking readiness, loading the program, selecting the source, and applying initial gain and balance/fade. Only successful completion releases the startup mute reason. Source changes hold a separate mute reason and preserve user mute.

Calibrated volume changes now run through `volume(...)` on the same synchronized session as routing and shutdown. Invalid calibration is rejected before I/O. Successful coefficient and commit writes update `lastAppliedGainWord`; this is OS-write completion state, not physical readback. A failed volume write invalidates that state, attempts amplifier fault mute, closes the writer, and retires the issued sound endpoint. Uncertain writes are not retried.

The optional module-4 endpoint publishes initialized balance/fade and subsequent EQ/field changes. Closing it closes the audio session and applies shutdown mute. Live MCU session termination retires this endpoint on the service polling worker. A failed amplifier write leaves mute state unknown; software cannot promise the physical output is muted.

`C7604AudioSessionTest` covers startup ordering, user mute across routing and volume, invalid volume arguments without I/O, failed commit retirement, startup route failure, and stale endpoint rejection. These tests use fake devices.

Remaining work includes MAIN volume/source Binder integration, target calibration selection and persistence, full DSP configuration, call and sleep/wake policy, exclusive vendor handover, boot integration and physical validation. This session alone is not a complete SYU replacement.
