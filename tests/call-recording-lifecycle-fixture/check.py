#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Compile actual pipeline with controlled Android/process fixtures; no calls or audio."""
import argparse
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--gradle-lib', type=Path, required=True)
parser.add_argument('--source', type=Path)
parser.add_argument('--expect-baseline-failure', action='store_true')
args = parser.parse_args()
fixture = Path(__file__).resolve().parent
source = args.source or fixture.parents[1] / 'manager/src/main/java/org/androidcontrol/app/recording/services/shell/ShellAudioPipeline.kt'
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
coroutines = list(lib.glob('kotlinx-coroutines-core-jvm-*.jar'))
if not stdlib.is_file() or len(coroutines) != 1:
    parser.error('Expected Gradle 8.14 Kotlin compiler, stdlib and coroutine jars')
classpath = f'{stdlib}:{coroutines[0]}'
with tempfile.TemporaryDirectory(prefix='call-recording-lifecycle-') as directory:
    classes = Path(directory) / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
                    '-d', str(classes), str(source), *map(str, fixture.glob('*.kt'))], check=True)
    failed = []
    for scenario in ('hash', 'pipe', 'socket', 'arguments', 'process', 'success'):
        result = subprocess.run(['java', '-cp', f'{classes}:{classpath}',
                                 'org.androidcontrol.app.recording.services.shell.PipelineProbeKt', scenario],
                                capture_output=True, text=True, timeout=30)
        print(result.stdout, end='')
        if result.returncode:
            failed.append(scenario)
            print(f'FAIL {scenario}: {result.stderr.strip()}')
    if args.expect_baseline_failure:
        if failed != ['socket', 'arguments', 'process']:
            raise SystemExit(f'Unexpected baseline failures: {failed}')
        print('PASS baseline sensitivity: all three partial-allocation stages fail')
    elif failed:
        raise SystemExit(f'Lifecycle regressions: {failed}')
