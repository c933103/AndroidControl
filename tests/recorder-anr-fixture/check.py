#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Compile actual pure Kotlin target-thread selector with synthetic trace controls."""
import argparse
from pathlib import Path
import subprocess
import tempfile
parser = argparse.ArgumentParser()
parser.add_argument('--gradle-lib', type=Path, required=True)
args = parser.parse_args()
fixture = Path(__file__).resolve().parent
source = fixture.parents[1] / 'manager/src/androidTest/java/org/androidcontrol/app/regression/RecorderAnrThreads.kt'
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
with tempfile.TemporaryDirectory(prefix='recorder-anr-') as directory:
    classes = Path(directory) / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib),
                    '-d', str(classes), str(source), str(fixture / 'Probe.kt')], check=True)
    subprocess.run(['java', '-cp', f'{classes}:{stdlib}', 'org.androidcontrol.app.regression.ProbeKt'], check=True, timeout=10)
