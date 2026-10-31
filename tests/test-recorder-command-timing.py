#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Test actual inline host timeout/receipt code without invoking adb or waiting."""
from pathlib import Path
from unittest.mock import patch
import json
import os
import subprocess
import tempfile

source = Path(__file__).with_name('run-recorder-media-checks.sh').read_text()
code = source.split("python3 -c 'import subprocess, sys, time\n", 1)[1].split("\n' adb shell am instrument", 1)[0]
code = 'import subprocess, sys, time\n' + code
for returncode, timeout in ((0, False), (1, False), (124, True)):
    with tempfile.TemporaryDirectory() as directory:
        old = os.getcwd()
        try:
            os.chdir(directory)
            Path('recorder-media-results').mkdir()
            with patch('sys.argv', ['-c', 'adb', 'shell', 'am', 'instrument']), \
                 patch('time.time_ns', side_effect=[1000, 900]), \
                 patch('time.monotonic_ns', side_effect=[10, 190]), \
                 patch('subprocess.run') as run:
                if timeout:
                    run.side_effect = subprocess.TimeoutExpired('adb', 180)
                else:
                    run.return_value = subprocess.CompletedProcess([], returncode)
                try:
                    exec(compile(code, 'actual-command-wrapper', 'exec'), {})
                except SystemExit as result:
                    assert result.code == returncode
                else:
                    raise AssertionError('original exit was not preserved')
                run.assert_called_once_with(['adb', 'shell', 'am', 'instrument'], timeout=180)
            receipt = json.loads(Path('recorder-media-results/media-command.json').read_text())
            assert receipt == dict(exitCode=returncode, timedOut=timeout, timeoutSeconds=180,
                hostStartedWallNs=1000, hostCompletedWallNs=900,
                hostStartedMonotonicNs=10, hostCompletedMonotonicNs=190, hostDurationNs=180)
        finally:
            os.chdir(old)
    print('PASS exact command timing and original result', returncode, timeout)
