"""Install and exercise the release APK on one explicitly selected emulator."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from smoke_contract import expected_checks, parse_results

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='Explicit emulator serial; never a vehicle/device')
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Only emulator-NNNN serials are allowed')
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
adb = str(Path(sdk) / 'platform-tools/adb') if sdk else shutil.which('adb')
if not adb:
    parser.error('Set ANDROID_HOME or put adb on PATH')

def run(*command, timeout=90):
    result = subprocess.run([adb, '-s', args.serial, *command],
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()

if run('shell', 'getprop', 'ro.kernel.qemu') != '1':
    parser.error('Selected target is not an Android emulator')
if run('shell', 'getprop', 'sys.boot_completed') != '1':
    parser.error('Wait for the emulator to finish booting')
apk = root / 'app/build/outputs/apk/release/app-release.apk'
test_apk = root / 'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'
reports = root / 'app/build/reports'
reports.mkdir(parents=True, exist_ok=True)
report_path = reports / 'emulator-smoke.json'
# A failed rerun must never leave a prior success report available for packaging.
report_path.unlink(missing_ok=True)
api = int(run('shell', 'getprop', 'ro.build.version.sdk'))
(reports / f'emulator-smoke-api{api}.json').unlink(missing_ok=True)
for path in (apk, test_apk):
    output = run('install', '-r', '-t', str(path))
    if 'Success' not in output:
        raise RuntimeError(output)
run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
if api >= 18:
    run('shell', 'wm', 'dismiss-keyguard')
else:
    run('shell', 'input', 'keyevent', '82')
output = run('shell', 'am', 'instrument', '-w', '-r',
             'com.cabin.hondacustom.test/com.cabin.hondacustom.SmokeInstrumentation', timeout=120)
(reports / 'emulator-smoke.log').write_text(output + '\n')
(reports / f'emulator-smoke-api{api}.log').write_text(output + '\n')
print(output)
try:
    result = parse_results(output, expected_checks(root))
except (ValueError, json.JSONDecodeError) as error:
    sys.exit(f'Emulator smoke checks failed: {error}; see app/build/reports/emulator-smoke.log')
report = {
    **result,
    'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
    'test_apk_sha256': hashlib.sha256(test_apk.read_bytes()).hexdigest(),
    'tested_at_utc': datetime.now(timezone.utc).isoformat(),
    'emulator_api': run('shell', 'getprop', 'ro.build.version.sdk'),
    'emulator_android_version': run('shell', 'getprop', 'ro.build.version.release'),
    'emulator_fingerprint': run('shell', 'getprop', 'ro.build.fingerprint'),
    'display': run('shell', 'wm', 'size') if api >= 18 else run('shell', 'dumpsys', 'display'),
    'scope': 'Release APK installation, navigation and unavailable-service gating; no Honda hardware/services',
    'hardware_tested': False,
}
report_path.write_text(json.dumps(report, indent=2) + '\n')
(reports / f'emulator-smoke-api{api}.json').write_text(json.dumps(report, indent=2) + '\n')
print('Verified release APK:', report['apk_sha256'])
