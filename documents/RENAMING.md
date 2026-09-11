# Cabin naming and compatibility

The repository folder is `cabin`, the Gradle root project is `cabin-native`, and
the app source namespace is `com.cabin`. Cabin has one head-unit distribution with no product flavors. Product classes, source paths, tests,
resource filenames, themes, UI text, diagnostic log labels and export filenames
use Cabin naming. All six interface languages use the same brand name.

These names remain compatibility identifiers rather than product branding:

- `zeno.carlink`: installed Android application ID and associated task affinity.
- `com.carlink.ipc`, `zeno.carlink.ipc.NaviVideoSourceService`, and its signature
  permission: the public navigation Binder contract used by external consumers.
- `com.carlink.launcher.CarlinkHome`: installed default-Home alias targeting
  Cabin's Activity. The old main Activity name is also a forwarding alias.
- Existing preference/DataStore names and keys, notification channel IDs, and
  explicit intent action strings: existing settings and callers keep working.
- `carlink-teyes-settings` and `Carlink normalized fields`: stored backup/report
  format identifiers. New export filenames use Cabin.

Activity, service and widget implementation classes use the new namespace.
Existing standalone projection widgets may need to be re-added after an upgrade
because Android identifies widget providers by component name. Widgets hosted
inside Cabin retain their stored IDs.

Carlinkit is the hardware manufacturer's name, not the app name. Upstream URLs,
license notices, reference material and Git history are preserved. The upstream
GitHub repository has not been renamed or replaced; configuring Cabin's release
repository remains a separate pending task.
