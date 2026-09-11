# Civic temperature controls

Cabin uses the public decompiled SYU Air implementation to support RZC Civic
vertical-screen profiles **1048874 (L)** and **1114410 (H)**. XP Civic profiles and
other vehicles do not inherit these commands. This is source-verified, not yet
verified on a physical head unit.

Reference revision: `755c9ae89ef255a975bcc0ee6a149e68cce2128c` of
[vasyl91/FYT-Launcher-Mod](https://github.com/vasyl91/FYT-Launcher-Mod).

- [ActivityNewAir.java](https://github.com/vasyl91/FYT-Launcher-Mod/blob/755c9ae89ef255a975bcc0ee6a149e68cce2128c/app/src/main/java/com/syu/carinfo/air/ActivityNewAir.java): routes these exact profiles to the shared Air screen.
- [Air_Activity_RZC_Focus.java](https://github.com/vasyl91/FYT-Launcher-Mod/blob/755c9ae89ef255a975bcc0ee6a149e68cce2128c/app/src/main/java/com/syu/carinfo/air/Air_Activity_RZC_Focus.java): `initCallbackId` maps driver down/up to keys 2/3, passenger down/up to 4/5, and command 107. `onTouch` and `sendCmd` establish payload `[key, 1]` for press and `[key, 0]` for release.

One Cabin arrow tap sends one ordered press/release pair. It does not set fan
speed, send an absolute temperature, repeat while held, or optimistically change
the temperature reading. The controller rechecks profile ownership, connection,
selected data layout, fresh zone readings, units, and LOW/HIGH limits before sending.

In **Civic 0298 reference** layout, raw 27/28 are left/right temperature and 37 is
the unit flag (0 Celsius, 1 Fahrenheit). Celsius uses half-degrees; Fahrenheit uses
whole degrees. -1 means unavailable, -2 LOW, -3 HIGH. Only these two verified RZC
profiles receive that normalization. Existing firmware layout preserves its
previous 25/31/33 reading mapping. No automatic layout switching is performed.

Temperature/unit freshness gates apply independently of fan/A/C readings. With
LOW shown only up is enabled; with HIGH shown only down is enabled. Missing or
stale readings disable both arrows. Very small tiles retain the compact layout.

## Complete Civic climate controls

The same RZC source defines power (key 1), Auto (21), Dual (16), recirculation (25),
front defrost (19), and rear defrost (20). Each action uses the same command-107
press/release pair. The reference layout normalizes raw fields 10/13/14/12/65/16
for those controls respectively. These controls appear in the full climate panel;
recirculation is also actionable in the dashboard widget. Both views share the
same temperature control and controller callbacks. A/C, fan, and airflow retain
the existing verified interfaces.

Incoming sample messages have a 4 KiB envelope limit and a maximum of 16 integer
values; only the first sample is consumed. Unused float/string arrays are never
allocated. Only subscribed integer fields enter the state pipeline. Connection
ownership and an epoch counter reject queued actions after reconnects; profile,
layout, per-field freshness, and valid switch values are checked before writes.
These are targeted hardening changes, not a claim of a complete security audit.
