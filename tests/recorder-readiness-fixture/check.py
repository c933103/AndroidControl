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
    for case in ('ready', 'idle-failure', 'diagnostic', 'decode-progress', 'decoder-only', 'invalid-mode', 'invalid-nonce'):
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
full = dict(runNonce='a'*32, pid=42, mode='integration', passed=True, cases=[dict(case=f'{codec}-{case}') for codec in ('aac', 'opus') for case in ('roundtrip-1', 'roundtrip-2', 'truncated', 'empty', 'mismatch')] + [dict(case='aac-unseekable-output-rejected')])
probe = dict(runNonce='a'*32, pid=42, mode='decoder-only', probePassed=True, cases=[dict(case='aac-decoder-probe', sha256=expected_sha)])
for name, mode, report, corrupt, expected in (
    ('full-valid', 'integration', full, False, 0),
    ('probe-valid', 'decoder-only', probe, False, 0),
    ('probe-not-full', 'integration', probe, False, 1),
    ('full-not-probe', 'decoder-only', full, False, 1),
    ('corrupt-probe', 'decoder-only', probe, True, 1),
    ('unknown-mode', 'other', probe, False, 1),
    ('stale-report', 'integration', dict(full, runNonce='b'*32), False, 1),
    ('wrong-pid', 'integration', dict(full, pid=43), False, 1),
    ('partial-report', 'integration', dict(full, cases=full['cases'][:5]), False, 1),
    ('duplicate-cases', 'integration', dict(full, cases=[full['cases'][0]]*11), False, 1),
    ('false-pass', 'integration', dict(full, passed=False), False, 1),
    ('cleanup-failure', 'integration', full, False, 1),
    ('missing-final', 'integration', full, False, 1),
    ('nonzero-exit', 'integration', full, False, 1),
    ('ceiling-exceeded', 'integration', full, False, 1),
    ('failure-evidence', 'integration', dict(full, passed=False, failure='original exception', decoderFailure=dict(inputEos=False), lifecycle=[dict(event='decoder-create-request')]), False, 1),
    ('missing-receipt', 'integration', full, False, 1),
    ('malformed-receipt', 'integration', full, False, 1),
    ('malformed-report', 'integration', full, False, 1),
):
    with tempfile.TemporaryDirectory(prefix='recorder-validator-') as directory:
        results = Path(directory) / 'recorder-media-results'; results.mkdir()
        (results / 'report.json').write_text(json.dumps(report))
        receipt = dict(exitCode=0, finalPassVerified=True, cleanup='stopped-and-absent', totalCeilingAdhered=True, nonce='a'*32, pid=42)
        receipt.update({'cleanup-failure': dict(cleanup='unavailable'), 'missing-final': dict(finalPassVerified=False), 'nonzero-exit': dict(exitCode=124), 'ceiling-exceeded': dict(totalCeilingAdhered=False), 'failure-evidence': dict(exitCode=124)}.get(name, {}))
        (results / 'media-command.json').write_text(json.dumps(receipt))
        for name_ in ('aac-1.m4a', 'aac-2.m4a', 'decoder-input.m4a'):
            (results / name_).write_bytes(asset_bytes[:-1] if corrupt else asset_bytes)
        for name_ in ('opus-1.ogg', 'opus-2.ogg'):
            (results / name_).write_bytes(b'OggS')
        if name == 'malformed-report': (results / 'report.json').write_text('not-json')
        if name == 'missing-receipt': (results / 'media-command.json').unlink()
        if name == 'malformed-receipt': (results / 'media-command.json').write_text('not-json')
        result = subprocess.run(['python3', '-c', validator, mode], cwd=directory, capture_output=True, timeout=10)
        if name == 'malformed-report':
            assert not (results / 'report.json').exists()
        else:
            # Semantic/command rejection must preserve original diagnostic bytes.
            assert (results / 'report.json').read_text() == json.dumps(report)
        assert (result.returncode == 0) == (expected == 0), (name, result.stderr.decode())
        print('PASS', name)
workflow = (root / '.github/workflows/recorder-media.yml').read_text()
# The explicit diagnostic branch selects ONLY the existing API33 row.
matrix_text = workflow.split('include: >-', 1)[1].split('    runs-on:', 1)[0]
assert "fromJSON(github.ref == 'refs/heads/diagnostic/recorder-aac-decode-832ac432' &&" in matrix_text
single, rows = [json.loads(value) for value in re.findall(r"'(\[.*?\])'", matrix_text)]
assert single == [rows[0]] and single[0]['api'] == 33 and single[0]['mode'] == 'integration'
assert rows == [
    dict(api=33, runner='ubuntu-latest', **{'acceleration-options': '', 'artifact-suffix': ''}, application='manager', mode='integration'),
    dict(api=35, runner='macos-15-intel', **{'acceleration-options': '-accel on', 'artifact-suffix': ''}, application='manager', mode='integration'),
    dict(api=35, runner='macos-15-intel', **{'acceleration-options': '-accel on', 'artifact-suffix': '-application-control'}, application='control', mode='integration'),
]
assert all(row['api'] == 33 for row in single)
print('PASS exact diagnostic branch only existing API33; normal three-entry integration matrix unchanged')

# Milestones follow actual queue/dequeue success; no payload or per-iteration trace.
decode = media.read_text().split('private fun decode(', 1)[1].split('private fun checkNonSeekableAac', 1)[0]
assert decode.index('progress.inputQueued(') > decode.index('decoder.queueInputBuffer(index, 0, size, pts, 0)')
assert decode.index('progress.inputEnded(') > decode.index('decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)')
assert decode.index('progress.outputReceived(') > decode.index('progress.outputBuffers++')
assert 'if (now >= deadline) progress.timeoutMs = now' in decode
assert 'check(now < deadline)' in decode
assert 'progress.formatChanges++' in decode and 'progress.bufferChanges++' in decode
print('PASS decoder milestone placement and unchanged deadline assertion')


# The scheduling reads stay outside the media loop and retain unchanged wait bounds.
media_text = media.read_text()
decoder_text = media_text.split('private fun decode(', 1)[1].split('private fun checkNonSeekableAac', 1)[0]
loop_text = decoder_text.split('while (!outputEnded)', 1)[1].split('val loopCpuEnd', 1)[0]
assert 'callerSchedstat' not in loop_text
assert 'decoder.dequeueInputBuffer(10000)' in loop_text
assert 'decoder.dequeueOutputBuffer(info, 10000)' in loop_text
assert decoder_text.count('callerSchedstat(callerTid)') == 2
assert 'ByteArray(129)' in media_text and 'check(used <= 128)' in media_text
assert 'File("/proc/self/task/$tid/schedstat")' in media_text
assert loop_text.count('progress.sampleOperation(') == 6  # data + EOS share queue counter
print('PASS bounded own-thread scheduler reads outside unchanged media loop')

assert decoder_text.count('callerSchedstat(initTid)') == 2
assert decoder_text.index('progress.callerState("beforeCreate"') < decoder_text.index('MediaCodec.createDecoderByType')
assert decoder_text.index('progress.initializationScheduler(') < decoder_text.index('val deadline =')
assert 'setThreadPriority' not in media_text
assert "runner.os == 'Linux'" in workflow
assert all(row['mode'] == 'integration' for row in rows)
print('PASS separate initialization window, numeric own-state observations and normal integration lanes')

assert decoder_text.index('progress.snapshot { result.put("decodeObservations"') > decoder_text.index('check(samples in')
assert decoder_text.index('progress.snapshot { result.put("decodeObservations"') > decoder_text.index('progress.scheduler(')
assert 'progress.snapshot' not in loop_text
assert 'progress.snapshot { snapshot ->' in decoder_text
print('PASS success and failure retain bounded observations outside timing/media assertions')

assert "emulator-build: ${{ github.ref == 'refs/heads/diagnostic/recorder-aac-decode-832ac432' && '16428233' || '' }}" in workflow
assert "RECORDER_EMULATOR_COMPARISON: ${{ github.ref == 'refs/heads/diagnostic/recorder-aac-decode-832ac432' && '37.2.12' || '' }}" in workflow
assert 'python3 tests/test-recorder-command-timing.py' in workflow
print('PASS explicit official prior-build comparison; other-branch emulator defaults unchanged')
