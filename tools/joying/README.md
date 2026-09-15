# Joying native CarPlay handoff

Cabin implements a direct client of the licensed CarPlay engine already installed in the inspected Joying firmware. It does not bundle the vendor engine or launch the stock UI.

The stock Car Link service must relinquish the abstract video socket. When the socket is busy, Cabin automatically requests that the stock client stop and retries for up to two seconds. It uses `FORCE_STOP_PACKAGES` if already granted, otherwise an explicit `stopService` request to the exported Car Link service. The **Use Cabin for CarPlay** button uses the same release path. A bound or restarted stock service may retain the socket, in which case the ADB handoff below is still needed. After an unsuccessful handoff, known socket conflicts and access denials wait for manual Retry rather than repeating the full session automatically. Sentry retains only a fixed `joying_video_failure` code (`BUSY`, `DENIED`, `HANDOFF_BLOCKED`, or `OTHER`), not vendor error text. Access-denied errors do not trigger handoff; full exception details are logged under `JoyingCarPlay`.

A normal sideload may not hold `FORCE_STOP_PACKAGES`, native Binder access, `LOCAL_MAC_ADDRESS`, or tethering permissions; manifest declarations alone do not grant those permissions. No permission bypass or SELinux changes are performed. Automatic recovery is covered by local tests but still needs validation on a physical Joying unit.

On a normal installation, use **Stock Car Link settings → Force stop**, return to Cabin and press **Retry**. Closing the stock screen is not sufficient when its service still holds the socket. The settings shortcut does not grant force-stop permission to Cabin.

For an authorized ADB connection, the companion tool can hand off socket ownership without modifying the firmware:

```sh
python tools/joying/carplay_handoff.py --serial JOYING_SERIAL
python tools/joying/carplay_handoff.py --serial JOYING_SERIAL --action prepare
```

Then open Cabin's CarPlay page and press Retry. Plug the iPhone into the CarPlay USB port, or enable wireless and choose an iPhone in Cabin’s phone picker. The picker can discover phones and request pairing; Android presents the pairing consent. The foreground service owns the session; leaving the projection screen does not end it. Use Disconnect to release Cabin’s resources.

To return to the stock service:

```sh
python tools/joying/carplay_handoff.py --serial JOYING_SERIAL --action restore
```

Restore stops Cabin before starting the stock service. The tool does not install the APK, grant signature permissions, flash, invoke root, or change SELinux. Handoff is gated to the inspected Android 10 / Car Link 2.23.0712.1954 version. It does not prove native service accessibility to Cabin's UID. Wireless uses the Android Bluetooth adapter path, not a separately connected vendor Bluetooth MCU.
