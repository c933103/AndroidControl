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
parser.add_argument('--suite', choices=('all', 'notifications', 'storage'), default='all')
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
    notification_cases = ('blocked-app-api31', 'blocked-app-api35', 'blocked-channel', 'blocked-group',
                     'missing-group', 'missing-channel', 'query-failure', 'permission-denied',
                     'hidden-during-setup', 'visible-api31', 'visible-api35', 'visible-group',
                     'revoke-app', 'revoke-channel', 'revoke-group')
    storage_cases = ('storage-aac-seekable', 'storage-aac-w-streams', 'storage-aac-no-rw',
                     'storage-aac-bad-rw', 'storage-open-null', 'storage-producer-failure', 'storage-opus-stream')
    cases = (notification_cases if args.suite == 'notifications' else storage_cases if args.suite == 'storage' else notification_cases + storage_cases)
    for scenario in cases:
        result = subprocess.run(['java', f'-Dfixture.legacyModeControl={str(args.expect_baseline_failure and args.suite == "storage").lower()}', '-cp', f'{classes}:{stdlib}',
                                 'org.androidcontrol.app.recording.ServiceProbeKt', scenario],
                                capture_output=True, text=True, timeout=10)
        print(result.stdout, end='')
        if result.returncode:
            failed.append(scenario)
            print(f'FAIL {scenario}: {result.stderr.strip()}')
    if args.expect_baseline_failure:
        expected = (['storage-aac-w-streams', 'storage-aac-no-rw', 'storage-aac-bad-rw'] if args.suite == 'storage' else ['blocked-app-api31', 'blocked-app-api35', 'blocked-channel', 'blocked-group', 'missing-group', 'missing-channel', 'query-failure', 'hidden-during-setup', 'revoke-app', 'revoke-channel', 'revoke-group'])
        if args.suite == 'all':
            parser.error('Choose a specific --suite for a baseline sensitivity control')
        if failed != expected:
            raise SystemExit(f'Unexpected baseline failures: {failed}')
        print(f'PASS {args.suite} baseline sensitivity: {len(failed)} affected scenarios reproduced')
    elif failed:
        raise SystemExit(f'Receiver regressions: {failed}')
