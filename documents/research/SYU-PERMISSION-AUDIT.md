# SYU permission audit — inspected Joying 2023 APKs

Inspected with Android build-tools 36 `aapt2 dump permissions` and `dump xmltree`:
- `applications/app/190000000_com.syu.carlink/190000000_com.syu.carlink.apk`
- `applications/app/190000000_com.syu.ms/190000000_com.syu.ms.apk`
- `applications/app/190000000_com.syu.canbus/190000000_com.syu.canbus.apk`

These are manifest requests and component declarations, **not proof of grants on the user's unit**.

## Relevant findings

- Car Link requests `NETWORK_STACK`, `WRITE_SETTINGS`, `WRITE_SECURE_SETTINGS`, `DUMP`,
  `READ_LOGS`, `SYSTEM_ALERT_WINDOW`, audio, location, Bluetooth and networking permissions.
  Its tether permission is misspelled as `android.Manifest.permission.TETHER_PRIVILEGED`;
  Cabin uses the actual `android.permission.TETHER_PRIVILEGED` definition.
- Car Link and CAN bus declare shared UID `android.uid.systemui`; MS declares
  `android.uid.system`. These are platform signing/provisioning relationships, not
  runtime permissions that Cabin can request or copy into an installed app.
- `com.syu.carlink.CarLinkService` is exported without a component permission.
  `app.ToolkitService` in MS has an intent filter and no component permission.
  This does not establish native Binder or SELinux access, and service stop does not close
  the stock socket thread (see JOYING-CARPLAY-SOCKET-LIFETIME.md).
- Four CAN bus receiver components require `com.syu.canbus`; these are vehicle-specific
  OnStar/PIP receivers, not the ToolkitService used for Cabin's CAN subscription.
  No new grant is added for unrelated receivers.
- MS and CAN bus request `DEVICE_POWER`, `INJECT_EVENTS`, `REBOOT`, `REMOVE_TASKS`,
  `FORCE_STOP_PACKAGES` and `READ_LOGS`. Cabin does not need the first four or global logs
  for its current native projection and module-control calls.

## Cabin integration

The privileged allowlist contains the six permissions with implemented call sites:
`BIND_APPWIDGET`, `FORCE_STOP_PACKAGES`, `LOCAL_MAC_ADDRESS`, `TETHER_PRIVILEGED`,
`OVERRIDE_WIFI_CONFIG`, `INSTALL_PACKAGES`. The self-update path verifies its own package,
newer version, signature and downloaded hash before committing a private installer session.
No untrusted package/URI is accepted by the non-exported install-result receiver.

`NETWORK_STACK` remains platform-signature-only on AOSP Android 10; a privileged XML entry
would not confer it. Hidden APIs, native service policy and system properties still require
on-device verification. No central was connected during this audit.
