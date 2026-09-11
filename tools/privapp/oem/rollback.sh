#!/system/bin/sh
# Removes only the unchanged Cabin files installed by this exact bundle.
set -eu
fail() { echo "Cabin: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail 'Existing root authorization is required.'
TARGET=/system/priv-app/Cabin
PERM=/system/etc/permissions/privapp-permissions-cabin.xml
APK_SHA='@APK_SHA@'
XML_SHA='@XML_SHA@'
[ ! -L "$TARGET" ] && [ ! -L "$PERM" ] && [ ! -L "$TARGET/.cabin-oem-owned" ] && [ ! -L "$TARGET/Cabin.apk" ] || fail 'Unexpected symbolic link.'
[ -f "$TARGET/.cabin-oem-owned" ] || fail 'No Cabin installation ownership marker.'
[ "$(cat "$TARGET/.cabin-oem-owned")" = "$APK_SHA" ] || fail 'This rollback belongs to another bundle.'
hash() { sha256sum "$1" | cut -d ' ' -f 1; }
[ "$(hash "$TARGET/Cabin.apk")" = "$APK_SHA" ] && [ "$(hash "$PERM")" = "$XML_SHA" ] || fail 'Files have changed; refusing to remove them.'
[ -w /system/priv-app ] && [ -w /system/etc/permissions ] || fail 'System must already be writable.'
LOCK=/system/etc/permissions/.cabin-provision-lock
mkdir "$LOCK" || fail 'Another provisioning operation is active, or the system is read-only.'
trap 'rmdir "$LOCK"' EXIT
trap 'exit 1' HUP INT TERM
rm "$PERM" "$TARGET/Cabin.apk" "$TARGET/.cabin-oem-owned"
rmdir "$TARGET"
echo 'Cabin system files removed. Normal Android app data was preserved. Reboot manually.'
