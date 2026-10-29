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
parser.add_argument('--suite', choices=('all', 'session', 'timestamps', 'opus', 'opus-rebase'), default='all')
args = parser.parse_args()
fixture = Path(__file__).resolve().parent
source = args.source or fixture.parents[1] / 'manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ScrcpyAudioMuxer.kt'
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
if not stdlib.is_file():
    parser.error('Expected Gradle 8.14 Kotlin compiler and stdlib jars')
session_cases = ('sample-success', 'no-config', 'no-samples', 'stop-failure', 'write-failure',
         'duplicate-close', 'duplicate-initialize', 'reuse-clears-result', 'reuse-empty-session')
timestamp_cases = ('queued-timestamps', 'capture-gap', 'capture-discontinuity', 'reuse-timestamps')
rebase_cases = ('opus-reset-shorter', 'opus-duplicate-shorter')
opus_cases = ('opus-end-granule', 'opus-all-toc-durations', 'opus-multiple-frames', 'opus-empty-packet', 'opus-missing-count', 'opus-zero-count', 'opus-oversize-duration', 'opus-reuse-end') + rebase_cases
cases = rebase_cases if args.suite == 'opus-rebase' else opus_cases if args.suite == 'opus' else session_cases if args.suite == 'session' else timestamp_cases if args.suite == 'timestamps' else session_cases + timestamp_cases + opus_cases
if args.expect_baseline_failure and args.suite == 'all':
    parser.error('Choose a specific --suite for the predecessor sensitivity control')
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
    expected = (list(rebase_cases) if args.suite == 'opus-rebase' else list(opus_cases) if args.suite == 'opus' else list(timestamp_cases) if args.suite == 'timestamps' else ['reuse-clears-result', 'reuse-empty-session']) if args.expect_baseline_failure else []
    if failed != expected:
        raise SystemExit(f'Unexpected failures: {failed}; expected {expected}')
    print(f'PASS expected failure set: {failed}')
