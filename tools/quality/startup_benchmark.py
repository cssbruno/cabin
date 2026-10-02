#!/usr/bin/env python3
"""Measure Android-reported cold/warm launch time and enforce explicit CI budgets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess


def parse_launch(output):
    if 'Status: ok' not in output:
        raise ValueError('Activity launch failed: ' + output)
    values = {key: int(value) for key, value in re.findall(r'^(TotalTime|WaitTime|ThisTime):\s*(\d+)', output, re.M)}
    if 'WaitTime' not in values:
        raise ValueError('No launch timing returned')
    # A resumed singleTask activity can omit TotalTime; WaitTime still measures the completed request.
    return values.get('TotalTime', values['WaitTime']), values


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--serial')
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runs', type=int, default=7)
    parser.add_argument('--budgets', type=Path, default=Path(__file__).with_name('startup_budgets.json'))
    args = parser.parse_args()
    if not 3 <= args.runs <= 30:
        parser.error('--runs must be between 3 and 30')
    adb = [args.adb] + (['-s', args.serial] if args.serial else [])
    def run(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=60)
    # Instrumentation runners may remove the target APK during their cleanup.
    run('install', '-r', str(args.apk))
    component = 'zeno.carlink/com.cabin.MainActivity'
    samples = {'cold': [], 'warm': []}
    details = []
    for mode in samples:
        for _ in range(args.runs):
            if mode == 'cold':
                run('shell', 'am', 'force-stop', 'zeno.carlink')
            else:
                run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            value, detail = parse_launch(run('shell', 'am', 'start', '-W', '-n', component))
            samples[mode].append(value)
            details.append({'mode': mode, **detail})
    try:
        phases = json.loads(run('shell', 'cat', '/sdcard/Android/data/zeno.carlink/files/quality/startup-phases.json'))
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        phases = {}
    medians = {mode: statistics.median(values) for mode, values in samples.items()}
    budgets = json.loads(args.budgets.read_text())
    report = {'sdk': run('shell', 'getprop', 'ro.build.version.sdk').strip(),
              'device': run('shell', 'getprop', 'ro.product.model').strip(),
              'fingerprint': run('shell', 'getprop', 'ro.build.fingerprint').strip(),
              'apk_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(),
              'samples_ms': samples, 'medians_ms': medians, 'android_timings': details,
              'largest_launch_cost': max(medians, key=medians.get), 'budgets_ms': budgets,
              'startup_phases_ms': phases, 'largest_measured_phase': max(phases, key=phases.get) if phases else None,
              'measurement': 'Android am start -W; cold force-stop versus warm foreground return. Not a rendered-frame or head-unit measurement.'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    for mode, median in medians.items():
        if median > budgets[mode + '_median_ms']:
            raise SystemExit(f'{mode} startup regression: {median}ms > {budgets[mode + "_median_ms"]}ms')
    print(json.dumps(medians))


if __name__ == '__main__':
    main()
