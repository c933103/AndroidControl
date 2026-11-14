#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Read-only API35 HVF capability check; never install or reconfigure a hypervisor."""
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys


def usable(returncode, output):
    return (returncode == 0 and len(output.encode()) <= 4096
            and re.fullmatch(r'accel:\r?\n0\r?\n(?:Hypervisor\.Framework|HVF)[^\r\n]*\r?\naccel', output.strip()) is not None)


def main():
    if sys.argv[1:] == ['33']:
        return 0  # Existing explicitly software API33 lane is unchanged.
    result = dict(required=True, usable=False, requestedMode='on')
    try:
        if sys.argv[1:] != ['35'] or platform.system() != 'Darwin' or platform.machine() != 'x86_64':
            raise ValueError('Unexpected API35 host')
        emulator = Path(os.environ['ANDROID_HOME']) / 'emulator' / 'emulator'
        probe = subprocess.run([str(emulator), '-accel-check'], capture_output=True, timeout=15)
        output = probe.stdout.decode('utf-8', errors='strict').strip()
        result.update(returncode=probe.returncode, hostSystem='Darwin', hostArch='x86_64')
        if len(output.encode()) <= 4096:
            result['capabilityOutput'] = output
        result['usable'] = usable(probe.returncode, output)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        result['error'] = 'capability-unavailable'
    directory = Path('recorder-media-results')
    directory.mkdir(exist_ok=True)
    (directory / 'acceleration.json').write_text(json.dumps(result, sort_keys=True) + '\n')
    print('API35 hardware acceleration verified' if result['usable'] else 'API35 hardware acceleration unavailable/unverified; media checks must not run')
    return 0 if result['usable'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
