#!/usr/bin/env python3
"""Inspect or hand off the pinned Joying native CarPlay service. No root, flashing, or permission bypass."""
import argparse
import subprocess
import time


def video_socket_rows(table):
    """Match the exact abstract socket, never other paths containing its name."""
    return [line for line in table.splitlines()
            if line.split() and line.split()[-1] in ('@/proc/mysocket', '/proc/mysocket')]


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
    sockets = video_socket_rows(shell('cat', '/proc/net/unix'))
    print('Video socket:', 'occupied' if sockets else 'free')
    stock_processes = [line for line in shell('ps', '-A').splitlines()
                       if line.split() and line.split()[-1] == 'com.syu.carlink']
    print('Stock Car Link process:', '; '.join(stock_processes) or 'not running')
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
        for _ in range(25):
            if not video_socket_rows(shell('cat', '/proc/net/unix')):
                break
            time.sleep(.2)
        else:
            raise RuntimeError('Video socket is still occupied after stopping Car Link. '
                               'Another client or a restarted process still owns it; handoff was not completed.')
        if 'not found' in shell('service', 'check', 'CarplayServer').lower():
            raise RuntimeError('Video socket is free, but the native daemon stopped. Handoff was not completed.')
        print('Verified: video socket free and native daemon registered. '
              'Open Cabin → CarPlay → Retry. Binder and video access still require an on-device check.')
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
