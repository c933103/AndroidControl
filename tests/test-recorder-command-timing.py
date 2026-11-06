#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Exercise the actual phase supervisor, without adb or real-time waits."""
import importlib.util
from pathlib import Path
import subprocess
from unittest.mock import Mock

spec = importlib.util.spec_from_file_location('command', Path(__file__).with_name('recorder-command.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
nonce = 'a' * 32

def created(s, now, token=nonce, pid='42'):
    for line in ('recorder_run_nonce=' + token, 'recorder_media_pid=' + pid, 'recorder_media_stage=created'):
        s.line('INSTRUMENTATION_STATUS: ' + line, now)
    s.line('INSTRUMENTATION_STATUS_CODE: 0', now)

for moment, ok in ((0, True), (179.999, True), (180, False), (181, False)):
    s = m.Status(nonce, 0)
    try: created(s, moment)
    except TimeoutError: assert not ok
    else: assert ok and s.deadline() == min(360, moment + 180)
    print('PASS startup boundary', moment)
for token, pid in ((nonce, '0'), ('b'*32, '42'), ('', '42'), (nonce, 'malformed')):
    try: created(m.Status(nonce, 0), 1, token, pid)
    except ValueError: pass
    else: raise AssertionError('invalid identity accepted')
s = m.Status(nonce, 0); created(s, 170)
try: created(s, 171)
except ValueError: pass
else: raise AssertionError('deadline reset by duplicate')
assert s.deadline() == 350
try: s.line('irrelevant', 350)
except TimeoutError: pass
else: raise AssertionError('execution boundary accepted')
for token, pid, terminal, expected in ((nonce, '42', '-1', True), ('b'*32, '42', '-1', False), (nonce, '43', '-1', False), (nonce, '42', '0', False)):
    s = m.Status(nonce, 0); created(s, 1)
    for key, val in [('recorder_media', 'PASS'), ('recorder_run_nonce', token), ('recorder_media_pid', pid)]:
        s.line('INSTRUMENTATION_RESULT: ' + key + '=' + val, 2)
    assert not s.passed('recorder_media')
    s.line('INSTRUMENTATION_CODE: ' + terminal, 2)
    assert s.passed('recorder_media') == expected
for outcomes, expected in (([(0,b'',b''),(1,b'',b'')], 'stopped-and-absent'), ([(1,b'',b'')], 'force-stop-failed'), ([(0,b'',b''),(0,b'42',b'')], 'caller-disappearance-unverified'), ([(0,b'',b''),(1,b'',b'error')], 'caller-disappearance-unverified')):
    run = Mock(side_effect=[subprocess.CompletedProcess([], *x) for x in outcomes])
    assert m.cleanup('org.androidcontrol.app', 10, run, lambda: 1) == expected
    assert all(c.kwargs['timeout'] == 9 for c in run.call_args_list)
assert m.cleanup('org.androidcontrol.app', 10, Mock(), lambda: 10) == 'deadline'
assert m.cleanup('org.androidcontrol.app', 10, Mock(side_effect=subprocess.TimeoutExpired('adb', 9)), lambda:1) == 'unavailable'
assert (m.STARTUP,m.EXECUTION,m.WORK,m.TOTAL) == (180,180,360,370)
print('PASS nonce/terminal identity, no deadline reset, cleanup boundaries/failures')

# Real streaming pipe + real child reaping; only replace the adb command with a
# local status emitter, and target cleanup with its already-tested stub.
import contextlib
import io
import json
import os
import sys
import tempfile
from unittest.mock import patch
emitter = '''import os,sys
nonce=sys.argv[1]; case=sys.argv[2]
if case == 'early': sys.exit(0)
if case == 'flood': os.write(1,b'x'*70000); sys.exit(0)
created='INSTRUMENTATION_STATUS: recorder_run_nonce='+nonce+'\\nINSTRUMENTATION_STATUS: recorder_media_pid=42\\nINSTRUMENTATION_STATUS: recorder_media_stage=created\\nINSTRUMENTATION_STATUS_CODE: 0\\n'
terminal='INSTRUMENTATION_RESULT: recorder_run_nonce='+nonce+'\\nINSTRUMENTATION_RESULT: recorder_media_pid=42\\nINSTRUMENTATION_RESULT: recorder_media=PASS\\nINSTRUMENTATION_CODE: -1\\n'
text=created+terminal
if case=='bad-nonce': text=text.replace(nonce,'b'*32)
if case=='duplicate': text=created+created+terminal
if case=='partial': text=created
for ch in text.encode(): os.write(1,bytes([ch]))
if case=='nonzero': sys.exit(1)
if case=='native255': sys.exit(255)
if case=='signal': os.kill(os.getpid(), 15)
'''
real_popen = subprocess.Popen
for case, clean, expected in [('split', 'stopped-and-absent', 0), ('early', 'stopped-and-absent',1), ('flood','stopped-and-absent',1), ('bad-nonce','stopped-and-absent',1), ('duplicate','stopped-and-absent',1), ('partial','stopped-and-absent',1), ('nonzero','stopped-and-absent',1), ('native255','stopped-and-absent',1), ('signal','stopped-and-absent',1), ('split','unavailable',1)]:
    children=[]
    def spawn(command, **kwargs):
        assert command[:5] == ['adb','shell','am','instrument','-w']
        assert command[5:8] == ['-e','recorder_mode','integration']
        assert command[8:10] == ['-e','recorder_run_nonce']
        child=real_popen([sys.executable,'-c',emitter,command[10],case],**kwargs)
        children.append(child); return child
    with tempfile.TemporaryDirectory() as directory:
        old=os.getcwd()
        try:
            os.chdir(directory); Path('recorder-media-results').mkdir()
            with patch.object(m.subprocess,'Popen',spawn), patch.object(m,'cleanup',return_value=clean), patch.object(m.sys,'argv',['recorder-command.py','org.androidcontrol.app','integration','org.androidcontrol.app.test/org.androidcontrol.app.regression.RecorderMediaInstrumentation']):
                assert m.main() == expected, case
            receipt=json.loads(Path('recorder-media-results/media-command.json').read_text())
            assert receipt['totalCeilingAdhered'] and receipt['hostDurationNs'] < 370*10**9
            assert all(c.poll() is not None for c in children)
            if case in ('split', 'early', 'partial', 'nonzero', 'native255', 'signal'):
                native = {'nonzero': 1, 'native255': 255, 'signal': -15}.get(case, 0)
                assert receipt['commandNativeExitCode'] == native, (case, receipt)
                assert receipt['localChildExitCode'] == native, (case, receipt)
                assert receipt['localChildCleanup'] == 'reaped'
                assert receipt['supervisorTerminationRequested'] is False
            if clean != 'stopped-and-absent':
                assert receipt['commandExitCode'] == 0 and receipt['outcome'] == 'terminal-pass'
                assert receipt['exitCode'] == 1
        finally: os.chdir(old)
    print('PASS streaming/reaping',case,clean)

# EOF is not process exit. Exercise the actual wait-timeout path without waiting
# for the 180-second budget. An uncreated caller is a startup timeout, not execution.
# Failed local reaping must not replace either phase's original result.
for reached_created in (False, True):
    for reaped in (False, True):
        child = Mock()
        child.stdout = Mock()
        child.stdout.fileno.return_value = 12345
        child.pid = 424242
        child.poll.return_value = None
        child.wait.side_effect = [subprocess.TimeoutExpired('adb', 180),
                                  -9 if reaped else subprocess.TimeoutExpired('adb', 10)]
        poll = Mock()
        poll.select.return_value = [(None, None)]
        @contextlib.contextmanager
        def selector():
            yield poll
        RealStatus = m.Status
        def status(token, started):
            state = RealStatus(token, started)
            if reached_created:
                created(state, started, token)
            return state
        with tempfile.TemporaryDirectory() as directory:
            old = os.getcwd()
            try:
                os.chdir(directory); Path('recorder-media-results').mkdir()
                with patch.object(m.subprocess, 'Popen', return_value=child), \
                     patch.object(m.selectors, 'DefaultSelector', selector), \
                     patch.object(m.os, 'read', return_value=b''), \
                     patch.object(m.os, 'killpg') as kill, \
                     patch.object(m, 'Status', status), \
                     patch.object(m, 'cleanup', return_value='stopped-and-absent'), \
                     patch.object(m.sys, 'argv', ['recorder-command.py', 'org.androidcontrol.app', 'integration', 'org.androidcontrol.app.test/org.androidcontrol.app.regression.RecorderMediaInstrumentation']):
                    assert m.main() == (124 if reaped else 1)
                receipt = json.loads(Path('recorder-media-results/media-command.json').read_text())
                assert receipt['commandExitCode'] == 124 and receipt['timedOut'] is True
                assert receipt['outcome'] == ('execution' if reached_created else 'startup')
                assert receipt['commandNativeExitCode'] is None
                assert receipt['localChildExitCode'] == (-9 if reaped else None)
                assert receipt['localChildCleanup'] == ('reaped' if reaped else 'not-reaped')
                assert receipt['supervisorTerminationRequested'] is True
                kill.assert_called_once_with(child.pid, m.signal.SIGKILL)
            finally:
                os.chdir(old)
        print('PASS original timeout survives local cleanup', reached_created, reaped)
