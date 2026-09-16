# Legacy firmware CarPlay handoff

**For earlier Cabin builds only.** Cabin 0.1.7 imports its own Carlink engine and
uses a separate video socket. Do not use this handoff procedure for the current
engine. See [current integration](../../documents/research/CARLINK-INDEPENDENT-ENGINE.md).

Cabin implements a direct client of the licensed CarPlay engine already installed in the inspected Joying firmware. It does not bundle the vendor engine or launch the stock UI.

The stock Car Link process must relinquish the abstract video socket. When the socket is busy, Cabin uses `FORCE_STOP_PACKAGES` only if already granted. Otherwise it directs the user to Android app settings for a manual force stop. `stopService` is insufficient: the inspected stock process retains its socket thread. A force stop is not proof of recovery; a restarted or different client may still own the socket. Known conflicts wait for manual Retry. Access-denied errors do not trigger handoff.

The ADB tool reports the exact video socket and stock process before acting. After `prepare`, it waits up to five seconds for the socket to disappear and checks that the native daemon is still registered. If either check fails, it reports an incomplete handoff instead of success. These checks do not prove Cabin has Binder access or that video frames will arrive.

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
