#!/usr/bin/env python3
"""Compile and exercise the actual instrumentation controlService helper.

Usage: check.py --gradle-lib DIR [--source FILE] [--expect-baseline-failure]
DIR is an existing Gradle 8.14 lib directory with its Kotlin compiler jars.
No downloads, Android device, app code or privileged service is invoked.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--gradle-lib', required=True, type=Path)
parser.add_argument('--source', type=Path)
controls = parser.add_mutually_exclusive_group()
controls.add_argument('--expect-baseline-failure', action='store_true')
controls.add_argument('--expect-missed-notification', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
source = args.source or root / 'manager/src/androidTest/java/org/androidcontrol/app/regression/RuntimeRegressionInstrumentation.kt'
text = source.read_text()
start = text.index('    private fun controlService(): IAndroidControlService {')
end = text.index('\n    private fun selectTarget()', start)
helper = text[start:end].replace('    private fun controlService()', '    fun controlService()', 1)
harness = (Path(__file__).with_name('harness.kt')).read_text().replace('    // ACTUAL_CONTROL_SERVICE', helper)
lib = args.gradle_lib.resolve()
required = ['kotlin-compiler-embeddable-2.0.21.jar', 'kotlin-stdlib-2.0.21.jar']
for jar in required:
    if not (lib / jar).is_file():
        parser.error(f'Missing expected Gradle 8.14 compiler input: {lib / jar}')
with tempfile.TemporaryDirectory(prefix='runtime-control-fixture-') as temporary:
    work = Path(temporary)
    kt = work / 'Harness.kt'
    kt.write_text(harness)
    output = work / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath',
                    str(lib / 'kotlin-stdlib-2.0.21.jar'), '-d', str(output), str(kt)], check=True)
    result = subprocess.run(['java', '-cp', f'{output}:{lib / "kotlin-stdlib-2.0.21.jar"}', 'HarnessKt'],
                            capture_output=True, text=True, timeout=55)
    print(result.stdout, end='')
    print(result.stderr, end='')
    if args.expect_baseline_failure:
        if result.returncode == 0 or 'FAILED delayed attach' not in result.stdout or 'FAILED bind exception' not in result.stdout:
            raise SystemExit('Baseline sensitivity control did not fail the required cases')
        print('PASS: baseline sensitivity control detects both fixture defects')
    elif args.expect_missed_notification:
        failures = [line for line in result.stdout.splitlines() if line.startswith('FAILED ')]
        if result.returncode == 0 or len(failures) != 2 or not any(line.startswith('FAILED attach races sticky registration:') for line in failures) or not any(line.startswith('FAILED delayed sticky flag publication:') for line in failures):
            raise SystemExit('Sticky-registration sensitivity control did not fail only the injected registration/publication races')
        print('PASS: sensitivity control detects both missed sticky notification schedules')
    elif result.returncode:
        raise SystemExit(result.returncode)
