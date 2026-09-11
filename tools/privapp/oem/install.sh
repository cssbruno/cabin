#!/system/bin/sh
# For an existing authorized root shell with Android running and /system writable.
set -eu
fail() { echo "Cabin: $*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail 'Existing root authorization is required.'
BASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TARGET=/system/priv-app/Cabin
PERM=/system/etc/permissions/privapp-permissions-cabin.xml
APK_SHA='@APK_SHA@'
XML_SHA='@XML_SHA@'
[ -d /system/priv-app ] && [ -d /system/etc/permissions ] || fail 'Required system directories are unavailable.'
[ -w /system/priv-app ] && [ -w /system/etc/permissions ] || fail 'System must already be writable through the authorized provisioning environment.'
[ ! -e "$TARGET" ] && [ ! -L "$TARGET" ] || fail 'Cabin system directory already exists; no files were replaced.'
[ ! -e "$PERM" ] && [ ! -L "$PERM" ] || fail 'Cabin permission file already exists; no files were replaced.'
command -v restorecon >/dev/null 2>&1 || fail 'restorecon is required.'
hash() { sha256sum "$1" | cut -d ' ' -f 1; }
[ "$(hash "$BASE/system/priv-app/Cabin/Cabin.apk")" = "$APK_SHA" ] || fail 'APK checksum mismatch.'
[ "$(hash "$BASE/system/etc/permissions/privapp-permissions-cabin.xml")" = "$XML_SHA" ] || fail 'Permission checksum mismatch.'
# Normal Android installation must have already accepted the exact APK and signer.
INSTALLED=$(pm path zeno.carlink | sed -n 's/^package://p' | head -n 1)
[ -n "$INSTALLED" ] && [ -f "$INSTALLED" ] || fail 'Install this exact APK normally through Android first.'
[ "$(hash "$INSTALLED")" = "$APK_SHA" ] || fail 'Installed Cabin differs from the bundle. Install the matching APK normally first.'
LOCK=/system/etc/permissions/.cabin-provision-lock
mkdir "$LOCK" || fail 'Another provisioning operation is active, or the system is read-only.'
CREATED=0
PERMISSION_CREATED=0
COMPLETE=0
cleanup() {
    if [ "$COMPLETE" = 0 ]; then
        if [ "$PERMISSION_CREATED" = 1 ]; then rm -f "$PERM"; fi
        if [ "$CREATED" = 1 ]; then
            rm -f "$TARGET/Cabin.apk" "$TARGET/.cabin-oem-owned"
            rmdir "$TARGET" || true
        fi
    fi
    rm -f "$LOCK/permission.xml"
    rmdir "$LOCK" || true
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
[ ! -e "$TARGET" ] && [ ! -L "$TARGET" ] && [ ! -e "$PERM" ] && [ ! -L "$PERM" ] || fail 'Destination changed during preflight.'
mkdir "$TARGET"
CREATED=1
chmod 0755 "$TARGET"
chown 0:0 "$TARGET"
cp "$BASE/system/priv-app/Cabin/Cabin.apk" "$TARGET/Cabin.apk"
printf '%s\n' "$APK_SHA" > "$TARGET/.cabin-oem-owned"
chmod 0644 "$TARGET/Cabin.apk" "$TARGET/.cabin-oem-owned"
chown 0:0 "$TARGET/Cabin.apk" "$TARGET/.cabin-oem-owned"
restorecon -R "$TARGET"
cp "$BASE/system/etc/permissions/privapp-permissions-cabin.xml" "$LOCK/permission.xml"
chmod 0644 "$LOCK/permission.xml"
chown 0:0 "$LOCK/permission.xml"
mv "$LOCK/permission.xml" "$PERM"
PERMISSION_CREATED=1
restorecon "$PERM"
[ "$(hash "$TARGET/Cabin.apk")" = "$APK_SHA" ] && [ "$(hash "$PERM")" = "$XML_SHA" ] || fail 'Installed checksum verification failed.'
COMPLETE=1
echo 'Cabin system files added. Reboot manually, then verify app status and widget permission. Factory apps were preserved.'
