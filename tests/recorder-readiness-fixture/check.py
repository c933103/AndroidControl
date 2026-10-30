#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual synthetic instrumentation's startup ordering on the host."""
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
source = args.source or fixture.parents[1] / 'manager/src/androidTest/java/org/androidcontrol/app/regression/RecorderMediaInstrumentation.kt'
lib = args.gradle_lib.resolve()
stdlib = lib / 'kotlin-stdlib-2.0.21.jar'
with tempfile.TemporaryDirectory(prefix='recorder-ready-') as directory:
    classes = Path(directory) / 'classes'
    media = fixture.parents[1] / 'manager/src/androidTest/java/org/androidcontrol/app/regression/RecorderMediaChecks.kt'
    progress = Path(directory) / 'RecorderDecodeProgress.kt'
    progress.write_text('package org.androidcontrol.app.regression\n' +
                        'internal class RecorderDecodeProgress' + media.read_text().split('internal class RecorderDecodeProgress', 1)[1])
    subprocess.run(['java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(stdlib),
                    '-d', str(classes), str(source), str(progress), *map(str, fixture.glob('*.kt'))], check=True)
    failed = []
    for case in ('ready', 'idle-failure', 'diagnostic', 'decode-progress', 'decoder-only', 'invalid-mode'):
        result = subprocess.run(['java', '-cp', f'{classes}:{stdlib}', 'org.androidcontrol.app.regression.ProbeKt', case], capture_output=True, text=True, timeout=10)
        if result.returncode:
            failed.append(case)
        else:
            print(result.stdout, end='')
    expected = ['ready', 'idle-failure'] if args.expect_baseline_failure else []
    assert failed == expected, (failed, expected)
    print('PASS expected failure set:', failed)

# Exercise the existing shell's exact validator without invoking adb or installing anything.
import hashlib
import json
import re
root = fixture.parents[1]
asset = root / 'manager/src/androidTest/assets/recorder/synthetic-aac-440hz.m4a'
asset_bytes = asset.read_bytes()
expected_sha = 'a917896903bc7944cbb6b7c0eab6743c1fe6f99caefebd27d569ea79677d6bd1'
assert len(asset_bytes) == 7158 and hashlib.sha256(asset_bytes).hexdigest() == expected_sha
validator = (root / 'tests/run-recorder-media-checks.sh').read_text().split("<<'VALIDATE' || status=1\n", 1)[1].split('\nVALIDATE', 1)[0]
full = dict(mode='integration', passed=True, cases=[dict(case=str(i)) for i in range(11)])
probe = dict(mode='decoder-only', probePassed=True, cases=[dict(case='aac-decoder-probe', sha256=expected_sha)])
for name, mode, report, corrupt, expected in (
    ('full-valid', 'integration', full, False, 0),
    ('probe-valid', 'decoder-only', probe, False, 0),
    ('probe-not-full', 'integration', probe, False, 1),
    ('full-not-probe', 'decoder-only', full, False, 1),
    ('corrupt-probe', 'decoder-only', probe, True, 1),
    ('unknown-mode', 'other', probe, False, 1),
):
    with tempfile.TemporaryDirectory(prefix='recorder-validator-') as directory:
        results = Path(directory) / 'recorder-media-results'; results.mkdir()
        (results / 'report.json').write_text(json.dumps(report))
        for name_ in ('aac-1.m4a', 'aac-2.m4a', 'decoder-input.m4a'):
            (results / name_).write_bytes(asset_bytes[:-1] if corrupt else asset_bytes)
        for name_ in ('opus-1.ogg', 'opus-2.ogg'):
            (results / name_).write_bytes(b'OggS')
        result = subprocess.run(['python3', '-c', validator, mode], cwd=directory, capture_output=True, timeout=10)
        assert (result.returncode == 0) == (expected == 0), (name, result.stderr.decode())
        print('PASS', name)
workflow = (root / '.github/workflows/recorder-media.yml').read_text()
expression = workflow.split('include: >-', 1)[1].split('    runs-on:', 1)[0]
assert "fromJSON(github.ref == 'refs/heads/diagnostic/recorder-aac-decode-832ac432' &&" in expression
single, normal = [json.loads(value) for value in re.findall(r"'(\[.*?\])'", expression)]
assert len(single) == 1 and single[0]['api'] == 35 and single[0]['application'] == 'manager'
assert single[0]['runner'] == 'macos-15-intel' and single[0]['acceleration-options'] == '-accel on'
assert single[0]['mode'] == 'decoder-only'
assert [(row['api'], row['application'], row['mode']) for row in normal] == [(33, 'manager', 'integration'), (35, 'manager', 'integration'), (35, 'control', 'integration')]
for ref in ('refs/heads/master', 'refs/heads/feature/call-recording-shizu-integration', 'refs/pull/26/merge'):
    assert ref != 'refs/heads/diagnostic/recorder-aac-decode-832ac432'
print('PASS exact diagnostic branch selects one API35 manager; normal/PR matrix unchanged')

# Milestones follow actual queue/dequeue success; no payload or per-iteration trace.
decode = media.read_text().split('private fun decode(', 1)[1].split('private fun checkNonSeekableAac', 1)[0]
assert decode.index('progress.inputQueued(') > decode.index('decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)')
assert decode.index('progress.inputEnded(') > decode.index('MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true')
assert decode.index('progress.outputReceived(') > decode.index('progress.outputBuffers++')
assert 'if (now >= deadline) progress.timeoutMs = now' in decode
assert 'check(now < deadline)' in decode
assert 'progress.formatChanges++' in decode and 'progress.bufferChanges++' in decode
print('PASS decoder milestone placement and unchanged deadline assertion')
