#!/usr/bin/env python3
"""Package/install Cabin through an existing, user-authorized Magisk installation."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid
import zipfile

PACKAGE = 'zeno.carlink'
MODULE = 'cabin_privapp'
PERMISSIONS = '''<?xml version="1.0" encoding="utf-8"?>
<permissions><privapp-permissions package="zeno.carlink">
    <permission name="android.permission.BIND_APPWIDGET" />
</privapp-permissions></permissions>
'''
CUSTOMIZE = '''[ "$BOOTMODE" = true ] || abort "Install from running Android, not recovery."
[ "$API" -ge 27 ] || abort "Cabin requires Android 8.1 or newer."
installed=$(pm path zeno.carlink | sed -n 's/^package://p' | head -n 1)
[ -n "$installed" ] && [ -f "$installed" ] || abort "Install this APK normally before installing its module."
expected=$(sha256sum "$MODPATH/system/priv-app/Cabin/Cabin.apk" | cut -d ' ' -f 1)
actual=$(sha256sum "$installed" | cut -d ' ' -f 1)
[ -n "$actual" ] && [ "$actual" = "$expected" ] || abort "Module APK must exactly match the normally installed Cabin APK."
ui_print "Cabin privileged app: widget binding only."
ui_print "Keep the factory launcher installed. Reboot manually after installation."
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
'''


def run(*args):
    result = subprocess.run([str(x) for x in args], text=True, capture_output=True, check=True)
    return result.stdout.strip()


def sdk_tool(sdk, name):
    candidates = [p / name for p in (sdk / 'build-tools').glob('*') if (p / name).is_file()]
    if not candidates:
        raise ValueError(f'Android SDK build-tools missing: {name}')
    return sorted(candidates, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)))[-1]


def inspect_apk(apk, sdk, allow_debug):
    run(sdk_tool(sdk, 'apksigner'), 'verify', apk)
    info = run(sdk_tool(sdk, 'aapt2'), 'dump', 'badging', apk)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", info, re.M)
    if not match or match[1] != PACKAGE:
        raise ValueError('Expected a signed Cabin APK with package zeno.carlink.')
    if 'application-debuggable' in info and not allow_debug:
        raise ValueError('Use a release APK. --allow-debug explicitly permits a development APK.')
    return int(match[2])


def package(apk, output, version):
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation prevents accidentally overwriting an APK or existing archive.
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('module.prop', f'id={MODULE}\nname=Cabin privileged app\nversion={version}\nversionCode={version}\nauthor=Cabin\ndescription=Cabin launcher with privileged widget binding\n')
        archive.writestr('customize.sh', CUSTOMIZE)
        archive.writestr('system/etc/permissions/privapp-permissions-cabin.xml', PERMISSIONS)
        archive.write(apk, 'system/priv-app/Cabin/Cabin.apk')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'install', 'status', 'disable'])
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--output', type=Path, default=Path('cabin-privapp.zip'))
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk')))))
    parser.add_argument('--serial', help='Exact authorized ADB device serial; required for device operations')
    parser.add_argument('--allow-debug', action='store_true')
    args = parser.parse_args()
    if args.action != 'build' and not args.serial:
        parser.error('--serial is required; no device is selected automatically')
    adb = shutil.which('adb') or str(args.sdk / 'platform-tools/adb')
    def device(*parts):
        return run(adb, '-s', args.serial, *parts)
    if args.action == 'status':
        dump = device('shell', 'dumpsys package zeno.carlink')
        for line in dump.splitlines():
            if any(key in line for key in ['codePath=', 'pkgFlags=', 'privateFlags=', 'BIND_APPWIDGET:']):
                print(line.strip())
        return
    if args.action in ['install', 'disable']:
        if device('shell', "su -c 'id -u'").strip() != '0':
            raise ValueError('Existing authorized root access is required.')
        device('shell', "su -c 'magisk -v'")
    if args.action == 'disable':
        device('shell', "su -c 'test -d /data/adb/modules/cabin_privapp && touch /data/adb/modules/cabin_privapp/disable'")
        print('Module disabled for next boot. Reboot manually. Cabin data is preserved.')
        return
    if not args.apk or not args.apk.is_file():
        parser.error('--apk must point to an existing APK')
    version = inspect_apk(args.apk.resolve(), args.sdk, args.allow_debug)
    if args.action == 'build':
        package(args.apk.resolve(), args.output.resolve(), version)
        print(args.output.resolve())
        return
    # PackageManager checks the existing signer/version and preserves app data.
    # Never uninstall, downgrade, or bypass signature verification on failure.
    print(device('install', '-r', args.apk.resolve()))
    remote = '/data/local/tmp/cabin-privapp-' + uuid.uuid4().hex + '.zip'
    with tempfile.TemporaryDirectory(prefix='cabin-privapp-') as folder:
        archive = Path(folder) / 'cabin-privapp.zip'
        package(args.apk.resolve(), archive, version)
        try:
            device('push', archive, remote)
            print(device('shell', f"su -c 'magisk --install-module {remote}'"))
        finally:
            device('shell', 'rm -f ' + remote)
    print('Module staged. Reboot manually, then run status. Factory launcher was preserved.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        detail = error.stderr if isinstance(error, subprocess.CalledProcessError) else str(error)
        raise SystemExit('Cabin installer stopped: ' + (detail or str(error)))
