#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Synthetic-only log fixtures: preserve target stacks, exclude other processes."""
import subprocess
import sys
from pathlib import Path
script = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name('filter-recorder-crash.py')
fixtures = (
    ('java', 'E/AndroidRuntime( 123): FATAL EXCEPTION: main\nE/AndroidRuntime( 123): Process: org.androidcontrol.app, PID: 123\nE/AndroidRuntime( 123): synthetic-java-stack\nE/AndroidRuntime( 999): unrelated-line\n', 'synthetic-java-stack'),
    ('native-padded', 'F/DEBUG   ( 456): pid: 789, tid: 789, name: test >>> org.androidcontrol.app <<<\nF/DEBUG   ( 456): synthetic-native-stack\nF/DEBUG   ( 999): unrelated-line\n', 'synthetic-native-stack'),
    ('other-only', 'F/DEBUG   ( 999): pid: 222 >>> another.app <<<\nF/DEBUG   ( 999): unrelated-line\n', 'No target-app crash record'),
    ('prefix-lookalike', 'E/AndroidRuntime( 999): Process: org.androidcontrol.app.other, PID: 999\nE/AndroidRuntime( 999): unrelated-line\n', 'No target-app crash record'),
)
for name, source, expected in fixtures:
    output = subprocess.check_output([sys.executable, str(script)], input=source, text=True)
    assert expected in output, (name, output)
    assert 'unrelated-line' not in output, (name, output)
    print('PASS', name)
