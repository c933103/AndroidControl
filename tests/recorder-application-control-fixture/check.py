#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Test the actual control subclass and inherited recorder runner with controlled APIs."""
import argparse
from pathlib import Path
import subprocess
import tempfile
parser = argparse.ArgumentParser()
parser.add_argument('--gradle-lib', type=Path, required=True)
args = parser.parse_args()
fixture = Path(__file__).resolve().parent
root = fixture.parents[1]
source = root / 'manager/src/androidTest/java/org/androidcontrol/app/regression'
stubs = fixture.parent / 'recorder-readiness-fixture'
lib = args.gradle_lib.resolve(); stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
with tempfile.TemporaryDirectory(prefix='recorder-control-') as directory:
    classes = Path(directory) / 'classes'
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib), '-d', str(classes),
        str(source / 'RecorderMediaInstrumentation.kt'), str(source / 'RecorderControlInstrumentation.kt'), str(source / 'RecorderControlApplication.kt'),
        str(fixture / 'ShizukuSettings.kt'),
        str(fixture / 'Probe.kt'), *[str(p) for p in stubs.glob('*.kt') if p.name != 'Probe.kt']], check=True)
    subprocess.run(['java', '-cp', f'{classes}:{stdlib}', 'org.androidcontrol.app.regression.ProbeKt'], check=True, timeout=10)
