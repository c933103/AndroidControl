#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Allowlisted emulator/guest identity, not a device dump or performance verdict."""
import json
import os
import re
from pathlib import Path
import subprocess
import sys

PROPERTIES = ('ro.build.fingerprint', 'ro.build.version.incremental',
              'ro.build.version.sdk', 'ro.product.cpu.abi')


def revision(path):
    data = path.read_bytes()
    if len(data) > 16384:
        raise ValueError('oversized package properties')
    values = [line.split('=', 1)[1].strip() for line in data.decode().splitlines()
              if line.split('=', 1)[0].strip() == 'Pkg.Revision' and '=' in line]
    if len(values) != 1 or not values[0] or len(values[0]) > 128:
        raise ValueError('missing/invalid revision')
    return values[0]


def collect(sdk_root, api, run=subprocess.run, min_cpus=1):
    report = {'schema': 1, 'complete': False, 'scope': 'synthetic emulator identity only',
              'packages': {}, 'guest': {}, 'errors': []}
    for name, relative in (
        ('emulator', 'emulator/source.properties'),
        ('systemImage', f'system-images/android-{api}/google_apis/x86_64/source.properties'),
    ):
        try:
            report['packages'][name] = {'revision': revision(sdk_root / relative)}
        except (OSError, ValueError, UnicodeError):
            report['errors'].append(f'{name}: revision unavailable')
    for prop in PROPERTIES:
        try:
            result = run(['adb', 'shell', 'getprop', prop], capture_output=True, timeout=10)
            value = result.stdout.decode().strip()
            if result.returncode or not value or len(value) > 1024 or '\n' in value or '\r' in value:
                raise ValueError('invalid property')
            report['guest'][prop] = value
        except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
            report['errors'].append(f'{prop}: unavailable')
    if report['guest'].get('ro.build.version.sdk') != str(api):
        report['errors'].append('API mismatch')
    if report['guest'].get('ro.product.cpu.abi') != 'x86_64':
        report['errors'].append('ABI mismatch')
    try:
        result = run(['adb', 'shell', 'cat', '/sys/devices/system/cpu/online'], capture_output=True, timeout=10)
        online = result.stdout.decode().strip()
        match = re.fullmatch(r'0(?:-([0-9]{1,3}))?', online)
        if result.returncode or not match:
            raise ValueError('invalid online CPU range')
        count = int(match.group(1) or '0') + 1
        report['guestOnlineCpus'] = count
        if count < min_cpus:
            report['errors'].append('insufficient online guest CPUs')
    except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
        report['errors'].append('guest CPU count unavailable')
    report['complete'] = not report['errors']
    return report


# Exact baseline identities retained in3a9d579939 artifacts. Never change guest
# image/CPU identity silently when comparing the official emulator binary.
COMPARISON_GUESTS = {
    '33': ('17', '12342917', 'google/sdk_gphone64_x86_64/emu64x:13/TE1A.240213.009/12342917:userdebug/dev-keys', 2),
    '35': ('9', '12960925', 'google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys', 1),
}


def verify_comparison(report, sdk_root, api, run=subprocess.run):
    image, incremental, fingerprint, cpus = COMPARISON_GUESTS[api]
    expected = (
        (report['packages'].get('emulator', {}).get('revision'), '37.2.12', 'emulator revision'),
        (report['packages'].get('systemImage', {}).get('revision'), image, 'image revision'),
        (report['guest'].get('ro.build.version.incremental'), incremental, 'guest incremental'),
        (report['guest'].get('ro.build.fingerprint'), fingerprint, 'guest fingerprint'),
        (report.get('guestOnlineCpus'), cpus, 'guest CPUs'),
    )
    for actual, wanted, label in expected:
        if actual != wanted:
            report['errors'].append('comparison mismatch: ' + label)
    try:
        result = run([str(sdk_root / 'emulator/emulator'), '-version'], capture_output=True, timeout=10)
        if result.returncode or len(result.stdout) > 4096:
            raise ValueError('unavailable version')
        match = re.search(r'Android emulator version (37\.2\.12\.0) \(build_id (16428233)\)', result.stdout.decode())
        if not match:
            raise ValueError('wrong executable build')
        report['comparisonEmulator'] = dict(version=match.group(1), buildId=match.group(2))
    except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
        report['errors'].append('comparison executable build unavailable or mismatched')
    report['complete'] = not report['errors']
    report['comparison'] = 'official37.2.12-build16428233-versus3a9d579939'
    return report


if __name__ == '__main__':
    api = sys.argv[1]
    if api not in ('33', '35'):
        raise SystemExit('Unsupported API')
    root = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not root:
        raise SystemExit('Android SDK path unavailable')
    min_cpus = int(sys.argv[2]) if len(sys.argv) == 3 else 1
    if min_cpus not in (1, 2):
        raise SystemExit('Unsupported CPU baseline')
    report = collect(Path(root), api, min_cpus=min_cpus)
    comparison = os.environ.get('RECORDER_EMULATOR_COMPARISON', '')
    if comparison == '37.2.12':
        verify_comparison(report, Path(root), api)
    elif comparison:
        report['errors'].append('unsupported comparison')
        report['complete'] = False
    destination = Path('recorder-media-results/runtime-identity.json')
    destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + '\n')
    raise SystemExit(0 if report['complete'] else 1)
