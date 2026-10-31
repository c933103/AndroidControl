#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Allowlisted emulator/guest identity, not a device dump or performance verdict."""
import json
import os
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


def collect(sdk_root, api, run=subprocess.run):
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
    report['complete'] = not report['errors']
    return report


if __name__ == '__main__':
    api = sys.argv[1]
    if api not in ('33', '35'):
        raise SystemExit('Unsupported API')
    root = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not root:
        raise SystemExit('Android SDK path unavailable')
    report = collect(Path(root), api)
    destination = Path('recorder-media-results/runtime-identity.json')
    destination.parent.mkdir(exist_ok=True)
    destination.write_text(json.dumps(report, indent=2) + '\n')
    raise SystemExit(0 if report['complete'] else 1)
