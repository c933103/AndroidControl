#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Compile the actual provider against host doubles; use real JVM waits/threads."""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--gradle-lib', required=True, type=Path)
parser.add_argument('--source', type=Path)
parser.add_argument('--expect-baseline-failure', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
source = args.source or root / 'manager/src/main/java/org/androidcontrol/app/ShizukuManagerProvider.kt'
# Only Android/Shizuku imports are replaced. The complete production class,
# real CountDownLatch and its unchanged five-second timeout are compiled.
production = '\n'.join(line for line in source.read_text().splitlines()
                       if not line.startswith('package ') and
                       not (line.startswith('import ') and not line.startswith('import java.')))
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
assert stdlib.is_file(), stdlib
with tempfile.TemporaryDirectory(prefix='provider-timeout-') as temporary:
    work = Path(temporary)
    actual = work / 'Actual.kt'
    actual.write_text(production)
    classes = work / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib),
                    '-d', str(classes), str(actual), str(Path(__file__).with_name('harness.kt'))], check=True)
    result = subprocess.run(['java', '-cp', f'{classes}:{stdlib}', 'HarnessKt'],
                            capture_output=True, text=True, timeout=30)
    print(result.stdout, end='')
    print(result.stderr, end='')
    if args.expect_baseline_failure:
        failures = [line for line in result.stdout.splitlines() if line.startswith('FAIL ')]
        assert result.returncode != 0 and any('timeout' in line for line in failures), failures
        print('PASS original provider reproduces timeout defect')
    else:
        assert result.returncode == 0, result.returncode
