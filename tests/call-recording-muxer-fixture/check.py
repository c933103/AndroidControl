#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Run the production muxer state machine with controlled Android media stubs."""
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
source = args.source or fixture.parents[1] / 'manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ScrcpyAudioMuxer.kt'
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
if not stdlib.is_file():
    parser.error('Expected Gradle 8.14 Kotlin compiler and stdlib jars')
cases = ('sample-success', 'no-config', 'no-samples', 'stop-failure', 'write-failure',
         'duplicate-close', 'duplicate-initialize', 'reuse-clears-result', 'reuse-empty-session')
with tempfile.TemporaryDirectory(prefix='recording-muxer-') as directory:
    classes = Path(directory) / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib),
                    '-d', str(classes), str(source), *map(str, fixture.glob('*.kt'))], check=True)
    failed = []
    for scenario in cases:
        result = subprocess.run(['java', '-cp', f'{classes}:{stdlib}',
                                 'org.androidcontrol.app.recording.integrations.scrcpy.MuxerProbeKt', scenario],
                                capture_output=True, text=True, timeout=10)
        print(result.stdout, end='')
        if result.returncode:
            failed.append(scenario)
            print(f'FAIL {scenario}: {result.stderr.strip()}')
    expected = ['reuse-clears-result', 'reuse-empty-session'] if args.expect_baseline_failure else []
    if failed != expected:
        raise SystemExit(f'Unexpected failures: {failed}; expected {expected}')
    print(f'PASS expected failure set: {failed}')
