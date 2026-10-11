#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Execute the actual recording service service against controlled notification/provider fixtures."""
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
source = args.source or fixture.parents[1] / 'manager/src/main/java/org/androidcontrol/app/recording/CallRecordingService.kt'
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
if not stdlib.is_file():
    parser.error('Expected Gradle 8.14 Kotlin compiler and stdlib jars')
with tempfile.TemporaryDirectory(prefix='call-recording-service-') as directory:
    classes = Path(directory) / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib),
                    '-d', str(classes), str(source), *map(str, fixture.glob('*.kt'))], check=True)
    failed = []
    for scenario in ('blocked-app-api31', 'blocked-app-api35', 'blocked-channel', 'blocked-group',
                     'missing-group', 'missing-channel', 'query-failure', 'permission-denied',
                     'hidden-during-setup', 'visible-api31', 'visible-api35', 'visible-group',
                     'revoke-app', 'revoke-channel', 'revoke-group'):
        result = subprocess.run(['java', '-cp', f'{classes}:{stdlib}',
                                 'org.androidcontrol.app.recording.ServiceProbeKt', scenario],
                                capture_output=True, text=True, timeout=10)
        print(result.stdout, end='')
        if result.returncode:
            failed.append(scenario)
            print(f'FAIL {scenario}: {result.stderr.strip()}')
    if args.expect_baseline_failure:
        if failed != ['blocked-app-api31', 'blocked-app-api35', 'blocked-channel', 'blocked-group', 'missing-group', 'missing-channel', 'query-failure', 'hidden-during-setup', 'revoke-app', 'revoke-channel', 'revoke-group']:
            raise SystemExit(f'Unexpected baseline failures: {failed}')
        print('PASS baseline sensitivity: notification visibility defects reproduced in all 11 affected scenarios')
    elif failed:
        raise SystemExit(f'Receiver regressions: {failed}')
