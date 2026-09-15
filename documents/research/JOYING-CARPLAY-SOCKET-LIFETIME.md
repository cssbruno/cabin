# Stock Car Link socket survives service stop

Reference: Joying `com.syu.carlink`, version 2.23.0712.1954, inspected smali in the local firmware audit.

- `c.j` creates `f.g("/proc/mysocket")`, starts the thread and stores it in `CarLinkService.o`.
- `f.g` constructs a `LocalServerSocket` and loops in `accept()`. The inspected class contains no `LocalServerSocket.close()` call.
- `CarLinkService.onDestroy()` unregisters the USB receiver, sends native command 220 and calls its superclass. It does not stop the socket thread or close the server socket.
- The native command envelope and listener registration in `f.a` match Cabin: command ID shifted by eight bits with low byte 2; listener transaction 3; native status integer reply. The socket name is abstract despite its path-like spelling.

Therefore stopping the Android service is not sufficient to transfer the video socket. A still-running stock process can retain it. Cabin's previous `stopService` fallback and two-second retry could not repair that ownership condition. This matches the earlier bind failure, but no new head-unit trace was obtained in this audit.

Cabin now requires process force-stop when the socket is busy. It invokes that operation only when already granted FORCE_STOP_PACKAGES; otherwise it directs the user to Android's stock app settings. A socket that is already free needs no privileged handoff. The prepare action verifies socket acquisition and closes its probe before retrying the real session.

Native service startup now waits up to five seconds for registration after requesting the existing firmware startup trigger. It does not change permissions, SELinux, firmware or stock binaries. Test coverage checks the ineffective service-stop path is not used, an existing daemon avoids startup, delayed startup succeeds and missing startup times out.

Physical validation remains necessary after force-stopping stock Car Link. A different firmware may additionally restrict Cabin's native Binder, socket, Bluetooth or tethering access.
