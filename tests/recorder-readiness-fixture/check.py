#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual synthetic instrumentation's startup ordering on the host."""
import argparse
from pathlib import Path
import subprocess
import tempfile
parser = argparse.ArgumentParser()
parser.add_argument('--gradle-lib', type=Path, required=True)
parser.add_argument('--source', type=Path)
parser.add_argument('--expect-baseline-failure', action='store_true')
args = parser.parse_args()
fixture = Path(__file__).resolve().parent
source = args.source or fixture.parents[1] / 'manager/src/androidTest/java/org/androidcontrol/app/regression/RecorderMediaInstrumentation.kt'
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
with tempfile.TemporaryDirectory(prefix='recorder-ready-') as directory:
    classes = Path(directory) / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib),
                    '-d', str(classes), str(source), *map(str, fixture.glob('*.kt'))], check=True)
    failed = []
    for case in ('ready', 'idle-failure', 'diagnostic'):
        result = subprocess.run(['java', '-cp', f'{classes}:{stdlib}', 'org.androidcontrol.app.regression.ProbeKt', case], capture_output=True, text=True, timeout=10)
        if result.returncode:
            failed.append(case)
        else:
            print(result.stdout, end='')
    expected = ['ready', 'idle-failure'] if args.expect_baseline_failure else []
    assert failed == expected, (failed, expected)
    print('PASS expected failure set:', failed)
