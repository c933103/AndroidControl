#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Exercise the exact shell mode selection and adb argument words, without adb."""
from pathlib import Path
import subprocess

source = Path(__file__).with_name('run-recorder-media-checks.sh').read_text()
setup = source.split('results=recorder-media-results', 1)[0]
command = source.split('python3 tests/recorder-command.py ', 1)[1].split(' || status=1', 1)[0]
assert 'instrument_args' not in source  # Bash 3 nounset rejects empty-array expansion.
assert 'startup180s + execution180s' in source
for application, mode, expected in (
    ('manager', 'integration', 'RecorderMediaInstrumentation'),
    ('control', 'integration', 'RecorderControlInstrumentation'),
    ('manager', 'decoder-only', 'RecorderMediaInstrumentation'),
    ('control', 'decoder-only', None),
    ('manager', 'invalid', None),
    ('invalid', 'integration', None),
):
    script = setup + '\nobserve() { printf "%s\\0" "$@"; }\nobserve ' + command
    result = subprocess.run(['bash', '-c', script, 'argument-fixture', application, mode],
                            capture_output=True, timeout=10)
    if expected is None:
        assert result.returncode != 0, (application, mode)
    else:
        assert result.returncode == 0, result.stderr.decode()
        words = result.stdout.split(b'\0')
        assert words == [b'org.androidcontrol.app',
                         mode.encode(), ('org.androidcontrol.app.test/org.androidcontrol.app.regression.' + expected).encode(), b''], words
    print('PASS instrument arguments', application, mode)
print(subprocess.check_output(['bash', '--version'], text=True).splitlines()[0])
