#!/usr/bin/env python3
"""Build additive OEM integration files; not an executable firmware update package."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

from cabin_privapp import PERMISSIONS, inspect_apk

README = '''Cabin additive OEM integration bundle

This is NOT a FYT USB-flash package, a Magisk module, or a self-installing ZIP.
It is input for an authorized firmware integrator using a supported build process.
No factory apps are included, removed or replaced.

Targets:
  system/priv-app/Cabin/Cabin.apk
  system/etc/permissions/privapp-permissions-cabin.xml

The permission allowlist grants only the existing BIND_APPWIDGET request.
It does not grant root, manufacturer signing keys, arbitrary CAN access,
or unrestricted environment editing. No FYT updater binaries or auto-flash hooks
are included.

Scripts:
  install.sh   Add Cabin to writable /system from an existing authorized root shell.
  rollback.sh  Remove only unchanged files created by this exact bundle.

First install the exact bundled APK normally through Android. Extract the bundle
and invoke install.sh with the authorized provisioning shell. The script verifies
the installed APK matches, requires writable system paths, sets ownership/modes,
and uses restorecon. It never remounts partitions or changes SELinux policy.
Existing destination files are rejected. Reboot manually after installation or
rollback, then check Cabin settings for actual system status and widget permission.
An interruption such as power loss can still require manual recovery.
These scripts require running Android/PackageManager; they are not recovery hooks.

The integrator must verify target firmware compatibility, PackageManager scanning,
same-partition permission allowlisting and file labels. /oem/priv-app behavior
has not been verified. Preserve signing compatibility with existing Cabin installs.
Do not bypass package signature checks or force a downgrade over newer app data.

Build type, version code and APK hash are recorded in manifest.json.
Physical FYT head-unit installation has not been validated.
'''


def build(apk, output, sdk, allow_debug=False):
    if not apk.is_file():
        raise ValueError('APK does not exist')
    if output.exists():
        raise ValueError('Output exists; choose a new output path')
    output.parent.mkdir(parents=True, exist_ok=True)
    # Verify and archive the same private snapshot even if the build output changes.
    with tempfile.TemporaryDirectory(prefix='cabin-oem-') as folder:
        snapshot = Path(folder) / 'Cabin.apk'
        snapshot.write_bytes(apk.read_bytes())
        version = inspect_apk(snapshot, sdk, allow_debug)
        metadata = {
            'package': 'zeno.carlink', 'versionCode': version,
            'development_apk_permitted': allow_debug, 'self_installing': False,
            'apk_sha256': hashlib.sha256(snapshot.read_bytes()).hexdigest(),
        }
        with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as archive:
            archive.write(snapshot, 'system/priv-app/Cabin/Cabin.apk')
            archive.writestr('system/etc/permissions/privapp-permissions-cabin.xml', PERMISSIONS)
            for name in ('install.sh', 'rollback.sh'):
                script = (Path(__file__).parent / 'oem' / name).read_text()
                script = script.replace('@APK_SHA@', metadata['apk_sha256']).replace('@XML_SHA@', hashlib.sha256(PERMISSIONS.encode()).hexdigest())
                entry = zipfile.ZipInfo(name)
                entry.external_attr = 0o100755 << 16
                archive.writestr(entry, script)
            archive.writestr('README.txt', README)
            archive.writestr('manifest.json', json.dumps(metadata, indent=2) + '\n')
        with zipfile.ZipFile(output) as archive:
            if archive.testzip() is not None:
                raise ValueError('ZIP integrity check failed')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk')))))
    parser.add_argument('--allow-debug', action='store_true')
    args = parser.parse_args()
    print(build(args.apk.resolve(), args.output.resolve(), args.sdk, args.allow_debug))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        detail = error.stderr if isinstance(error, subprocess.CalledProcessError) else str(error)
        raise SystemExit('OEM bundle stopped: ' + (detail or str(error)))
