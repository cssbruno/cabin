#!/usr/bin/env python3
"""Run the minified APK checks and preserve evidence before any runner cleanup."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def instrumentation_passed(output):
    # `adb shell am instrument` can return exit status zero even when tests fail.
    return (re.search(r'^OK \(2 tests\)\s*$', output, re.M) is not None
            and 'INSTRUMENTATION_CODE: -1' in output
            and not re.search(r'^INSTRUMENTATION_(?:FAILED|ABORTED):', output, re.M))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--serial')
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--test-apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    adb = [args.adb] + (['-s', args.serial] if args.serial else [])
    args.output.mkdir(parents=True, exist_ok=True)
    def run(*command, timeout=90):
        return subprocess.check_output(adb + list(command), text=True, stderr=subprocess.STDOUT, timeout=timeout)
    run('install', '-r', str(args.apk))
    run('install', '-r', '-t', str(args.test_apk))
    # This command is deliberately scoped to the disposable test device.
    if 'Success' not in run('shell', 'pm', 'clear', 'zeno.carlink'):
        raise SystemExit('Could not reset the test app for first-launch checks')
    result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-r',
                            'zeno.carlink.test/androidx.test.runner.AndroidJUnitRunner'],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1200)
    (args.output / 'instrumentation.txt').write_text(result.stdout)
    passed = result.returncode == 0 and instrumentation_passed(result.stdout)
    manifest = {
        'apk_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        'test_apk_sha256': hashlib.sha256(args.test_apk.read_bytes()).hexdigest(),
        'sdk': run('shell', 'getprop', 'ro.build.version.sdk').strip(),
        'device': run('shell', 'getprop', 'ro.product.model').strip(),
        'fingerprint': run('shell', 'getprop', 'ro.build.fingerprint').strip(),
        'instrumentation_passed': passed,
        'scope': 'Minified first launch/document export and no-adapter lifecycle resource soak. No physical projection certification.',
    }
    (args.output / 'device.json').write_text(json.dumps(manifest, indent=2) + '\n')
    if not passed:
        print(result.stdout)
        raise SystemExit('Minified device checks failed; see instrumentation.txt')
    for name in ('soak.json', 'startup-phases.json'):
        run('pull', '/sdcard/Android/data/zeno.carlink/files/quality/' + name, str(args.output / name))
        json.loads((args.output / name).read_text())
    print('Passed both minified device tests; saved device identity, APK hashes and resource samples.')


if __name__ == '__main__':
    main()
