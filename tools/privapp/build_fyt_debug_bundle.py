#!/usr/bin/env python3
"""Build a FYT USB updater bundle for a signed Cabin debug APK.

The FYT routing executables are vendor firmware components, not generic tools.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

UPDATERS = {
    'lsec6315update': 'UIS7862 / UMS512',
    'lsec6316update': 'SC9863A / UIS8581',
    'lsec6521update': 'SC9853I',
}
APK_ENTRY = 'Cabin.apk'
DESTINATION = '/oem/app/zeno.carlink/Cabin.apk'
REFERENCE = 'https://github.com/hvdwolf/FYTuis7862BinRepo'

SCRIPT = '''#!/system/bin/sh
# Cabin FYT USB updater. Runs only through the vendor-selected updater binary.
set -eu
fail() { echo "Cabin FYT update stopped: $*" >&2; exit 1; }
SOURCE=/storage/sdcard1/Cabin.apk
TARGET=/oem/app/zeno.carlink/Cabin.apk
EXPECTED_SHA=@APK_SHA@
hash() { sha256sum "$1" | cut -d ' ' -f 1; }
[ "$(id -u)" = 0 ] || fail 'Vendor updater root environment is required.'
[ -f "$SOURCE" ] || fail "Missing $SOURCE. Keep the generated ZIP contents at USB root."
[ "$(hash "$SOURCE")" = "$EXPECTED_SHA" ] || fail 'Cabin.apk checksum mismatch.'
[ -d /oem/app ] && [ -w /oem/app ] || fail '/oem/app is not writable on this firmware.'
[ ! -e "$TARGET.bak" ] || fail 'Previous Cabin backup exists; recover or remove it deliberately first.'
mkdir -p /oem/app/zeno.carlink
if [ -e "$TARGET" ]; then
  cp "$TARGET" /storage/sdcard1/CABIN_BACKUP.apk || fail 'Could not back up current Cabin APK to USB.'
  mv "$TARGET" "$TARGET.bak" || fail 'Could not preserve current Cabin APK.'
fi
cp "$SOURCE" "$TARGET" || fail 'Could not copy Cabin APK to /oem.'
chown 0:0 "$TARGET"
chmod 0644 "$TARGET"
[ "$(hash "$TARGET")" = "$EXPECTED_SHA" ] || fail 'Installed checksum verification failed.'
echo 'Cabin debug APK installed to /oem. Remove USB only when the FYT updater permits it, then reboot manually.'
'''

README = '''Cabin debug FYT USB updater

This is a firmware-specific USB update package. It was built from a debug APK
and the three FYT router binaries supplied to CI. It supports only the chip
families selected by those binaries:
  lsec6315update  UIS7862 / UMS512
  lsec6316update  SC9863A / UIS8581
  lsec6521update  SC9853I

The vendor updater chooses a matching lsec_updatesh script. Do not add or swap
router binaries, and do not use this ZIP on a different firmware/vendor without
confirming its provisioning format. The package installs Cabin.apk at:
  /oem/app/zeno.carlink/Cabin.apk

If a Cabin APK is already there, the script saves it on the USB root as
CABIN_BACKUP.apk and preserves the original as Cabin.apk.bak. It refuses to
overwrite an existing .bak, fails if /oem is not writable, verifies checksums,
does not remount partitions, alter SELinux, bypass signatures, replace factory
apps, or reboot. A FYT update can still brick an incompatible head unit.

Use only while parked, with stable power. This is a debug build: it cannot
upgrade a release-signed Cabin installation and must never be published as a
normal release. Physical hardware compatibility is unverified.

Reference format: ''' + REFERENCE + '\n'


def run(*args):
    result = subprocess.run([str(arg) for arg in args], text=True, capture_output=True, check=True)
    return result.stdout.strip()


def sdk_tool(sdk: Path, name: str):
    candidates = [path / name for path in (sdk / 'build-tools').glob('*') if (path / name).is_file()]
    if not candidates:
        raise ValueError(f'Android SDK build-tools missing: {name}')
    return sorted(candidates, key=lambda path: tuple(int(part) for part in re.findall(r'\d+', path.parent.name)))[-1]


def inspect_debug_apk(apk: Path, sdk: Path):
    run(sdk_tool(sdk, 'apksigner'), 'verify', apk)
    badging = run(sdk_tool(sdk, 'aapt2'), 'dump', 'badging', apk)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
    if not match or match[1] != 'zeno.carlink':
        raise ValueError('Expected a signed Cabin APK with package zeno.carlink.')
    if 'application-debuggable' not in badging:
        raise ValueError('Expected a debug APK; release APKs are not accepted.')
    return int(match[2])


def _zip_entry(archive, name, data, mode=0o644):
    entry = zipfile.ZipInfo(name)
    entry.external_attr = (0o100000 | mode) << 16
    archive.writestr(entry, data)


def read_binaries(updater_source: Path):
    if updater_source.is_dir():
        missing = [name for name in UPDATERS if not (updater_source / name).is_file()]
        if missing:
            raise ValueError('Updater directory is missing: ' + ', '.join(missing))
        return {name: (updater_source / name).read_bytes() for name in UPDATERS}
    if not updater_source.is_file() or not zipfile.is_zipfile(updater_source):
        raise ValueError('Updater source must be a directory or an existing ZIP file.')
    with zipfile.ZipFile(updater_source) as source:
        missing = [name for name in UPDATERS if name not in source.namelist()]
        if missing:
            raise ValueError('Updater archive is missing: ' + ', '.join(missing))
        return {name: source.read(name) for name in UPDATERS}


def build(apk: Path, updater_source: Path, output: Path, sdk: Path):
    if not apk.is_file():
        raise ValueError('APK does not exist.')
    if output.exists():
        raise ValueError('Output exists; choose a new output path.')
    version = inspect_debug_apk(apk, sdk)
    apk_sha = hashlib.sha256(apk.read_bytes()).hexdigest()
    binaries = read_binaries(updater_source)
    if any(not value for value in binaries.values()):
        raise ValueError('Updater archive contains an empty router binary.')
    output.parent.mkdir(parents=True, exist_ok=True)
    metadata = {
        'package': 'zeno.carlink', 'versionCode': version,
        'build_type': 'debug', 'apk_sha256': apk_sha,
        'destination': DESTINATION, 'self_installing': True,
        'chip_router_binaries': UPDATERS,
    }
    with tempfile.TemporaryDirectory(prefix='cabin-fyt-') as folder:
        snapshot = Path(folder) / APK_ENTRY
        snapshot.write_bytes(apk.read_bytes())
        with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as archive:
            archive.write(snapshot, APK_ENTRY)
            for name, content in binaries.items():
                _zip_entry(archive, name, content, mode=0o755)
            script = SCRIPT.replace('@APK_SHA@', apk_sha)
            for name in ('lsec.sh', '7862lsec.sh', '8581lsec.sh'):
                _zip_entry(archive, 'lsec_updatesh/' + name, script, mode=0o755)
            _zip_entry(archive, 'README.txt', README)
            _zip_entry(archive, 'manifest.json', json.dumps(metadata, indent=2) + '\n')
        with zipfile.ZipFile(output) as archive:
            if archive.testzip() is not None:
                raise ValueError('ZIP integrity check failed.')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--updater-source', required=True, type=Path,
                        help='Directory or ZIP containing the three FYT router binaries')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk')))))
    args = parser.parse_args()
    print(build(args.apk.resolve(), args.updater_source.resolve(), args.output.resolve(), args.sdk.resolve()))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        raise SystemExit('FYT debug bundle stopped: ' + str(error))
