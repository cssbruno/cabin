#!/usr/bin/env python3
"""Read-only FYT inventory. Never invokes firmware updaters, root, or system writes."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

PROPERTIES = (
    'ro.product.manufacturer', 'ro.product.model', 'ro.board.platform',
    'ro.hardware', 'ro.product.cpu.abilist', 'ro.build.version.sdk',
    'ro.build.version.release', 'ro.build.display.id', 'ro.build.fytmanufacturer',
)
REFERENCE = 'https://github.com/hvdwolf/FYTuis7862BinRepo/tree/b5e22b6e94b27fcd7a4dadedf7bc060bfe5d2872'


def analyze(properties):
    values = {key: str(properties.get(key, '')) for key in PROPERTIES}
    tokens = set((values['ro.board.platform'] + ' ' + values['ro.hardware']).lower().split())
    families = []
    if tokens & {'ums512', 'uis7862'}:
        families.append('UIS7862 / UMS512')
    if tokens & {'sc9863a', 'uis8581', 'uis8581a'}:
        families.append('SC9863A / UIS8581')
    if 'sc9853i' in tokens:
        families.append('SC9853I')
    try:
        sdk = int(values['ro.build.version.sdk'])
    except ValueError:
        sdk = None
    return {
        'properties': values,
        'chip_family_hint': families[0] if len(families) == 1 else 'unknown or conflicting',
        'android_meets_cabin_minimum': sdk >= 27 if sdk is not None else None,
        'fyt_manufacturer_property_present': bool(values['ro.build.fytmanufacturer']),
        'firmware_installation_compatibility': 'unverified',
        'privileged_permission_support': 'unverified',
        'reference': REFERENCE,
        'next_step': 'Confirm the exact build and supported app-provisioning process with the head-unit vendor.',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--serial', help='Exact authorized ADB device serial')
    source.add_argument('--properties', type=Path, help='Analyze an existing JSON property object offline')
    parser.add_argument('--adb', default=shutil.which('adb') or 'adb')
    args = parser.parse_args()
    if args.properties:
        properties = json.loads(args.properties.read_text())
        if not isinstance(properties, dict):
            parser.error('Properties must be a JSON object.')
    else:
        properties = {}
        for key in PROPERTIES:
            result = subprocess.run([args.adb, '-s', args.serial, 'shell', 'getprop', key],
                                    check=True, capture_output=True, text=True, timeout=15)
            properties[key] = result.stdout.strip()
    print(json.dumps(analyze(properties), indent=2, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        raise SystemExit('FYT inventory stopped: ' + str(error))
