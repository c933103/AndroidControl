#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Exercise production scrcpy framing with synthetic bytes and real Java file descriptors."""
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
source = args.source or fixture.parents[1] / 'manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ScrcpyClient.kt'
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
if not stdlib.is_file():
    parser.error('Expected Gradle 8.14 Kotlin compiler and stdlib jars')
cases = ('clean-boundary', 'partial-codec', 'partial-header', 'partial-payload',
         'complete-then-partial', 'listener-no-message', 'oversize', 'missing-config')
with tempfile.TemporaryDirectory(prefix='recording-client-') as directory:
    classes = Path(directory) / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib),
                    '-d', str(classes), str(source), *map(str, fixture.glob('*.kt'))], check=True)
    failed = []
    for scenario in cases:
        result = subprocess.run(['java', '-cp', f'{classes}:{stdlib}',
                                 'org.androidcontrol.app.recording.integrations.scrcpy.ClientProbeKt', scenario],
                                capture_output=True, text=True, timeout=10)
        print(result.stdout, end='')
        if result.returncode:
            failed.append(scenario)
            print(f'FAIL {scenario}: {result.stderr.strip()}')
    expected = list(cases[1:6]) if args.expect_baseline_failure else []
    if failed != expected:
        raise SystemExit(f'Unexpected failures: {failed}; expected {expected}')
    print(f'PASS expected failure set: {failed}')
