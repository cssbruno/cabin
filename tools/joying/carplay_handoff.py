#!/usr/bin/env python3
"""Inspect or hand off the pinned Joying native CarPlay service. No root, flashing, or permission bypass."""
import argparse
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True, help='Exact ADB serial for the Joying unit')
    parser.add_argument('--action', choices=['status', 'prepare', 'restore'], default='status')
    args = parser.parse_args()

    def shell(*words):
        result = subprocess.run([args.adb, '-s', args.serial, 'shell', *words],
                                text=True, capture_output=True, timeout=15, check=True)
        output = result.stdout.strip()
        if result.stderr.strip() or 'Error:' in output or 'SecurityException' in output:
            raise RuntimeError(result.stderr.strip() or output)
        return output

    sdk = shell('getprop', 'ro.build.version.sdk')
    package = shell('dumpsys', 'package', 'com.syu.carlink')
    daemon = shell('service', 'check', 'CarplayServer')
    print('Android SDK:', sdk)
    print('CarplayServer:', daemon)
    pinned = 'versionName=2.23.0712.1954' in package
    print('Inspected Car Link version present:', pinned)
    if args.action == 'status':
        return
    if sdk != '29' or not pinned:
        raise RuntimeError('Handoff requires the inspected Android 10 / Car Link 2.23.0712.1954 firmware.')
    if args.action == 'prepare':
        if 'not found' in daemon.lower():
            shell('setprop', 'sys.fyt.carplay', '1')
            for _ in range(25):
                time.sleep(.2)
                if 'not found' not in shell('service', 'check', 'CarplayServer').lower():
                    break
            else:
                raise RuntimeError('Native daemon did not start; stock service was not stopped.')
        shell('am', 'force-stop', 'com.syu.carlink')
        print('Stock service stopped. Open Cabin → CarPlay → Retry. Firmware permissions still apply.')
    else:
        # Stop Cabin first so its socket cannot compete with the restored stock service.
        shell('am', 'force-stop', 'zeno.carlink')
        print(shell('am', 'startservice', '-n', 'com.syu.carlink/com.syu.carlink.CarLinkService'))
        print('Stock Car Link service restore requested. Cabin was stopped to release video ownership.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit('Joying handoff stopped: ' + str(error))
