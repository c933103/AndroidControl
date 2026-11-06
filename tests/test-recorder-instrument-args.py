#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Exercise shell arguments and the diagnostic workflow selection, without adb."""
from pathlib import Path
import json
import re
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

# Validate the workflow before building or starting an emulator. A changed host
# must not silently select a paid runner, a decoder subset, or different gates.
root = Path(__file__).resolve().parents[1]
workflow = (root / '.github/workflows/recorder-media.yml').read_text()
matrix_text = workflow.split('include: >-', 1)[1].split('    runs-on:', 1)[0]
branch = 'refs/heads/diagnostic/recorder-aac-decode-832ac432'
assert f"fromJSON(github.ref == '{branch}' &&" in matrix_text
comparison, normal = [json.loads(value) for value in re.findall(r"'(\[.*?\])'", matrix_text)]
assert normal == [
    dict(api=33, runner='ubuntu-latest', **{'acceleration-options': '', 'artifact-suffix': ''}, application='manager', mode='integration'),
    dict(api=35, runner='macos-15-intel', **{'acceleration-options': '-accel on', 'artifact-suffix': ''}, application='manager', mode='integration'),
    dict(api=35, runner='macos-15-intel', **{'acceleration-options': '-accel on', 'artifact-suffix': '-application-control'}, application='control', mode='integration'),
]
assert comparison == [dict(row, runner='macos-26-intel') for row in normal[1:]]
assert f"emulator-build: ${{{{ github.ref == '{branch}' && '16428233' || '' }}}}" in workflow
assert f"RECORDER_EMULATOR_COMPARISON: ${{{{ github.ref == '{branch}' && '37.2.12' || '' }}}}" in workflow
assert 'timeout-minutes: 45' in workflow
assert 'target: google_apis' in workflow and 'arch: x86_64' in workflow
print('PASS exact diagnostic API35 host comparison, pinned emulator and unchanged normal matrix')
