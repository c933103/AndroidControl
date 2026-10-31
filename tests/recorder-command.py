#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Single-use installed recorder command; separate bounded startup/execution phases."""
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time
import uuid

STARTUP = 180
EXECUTION = 180
WORK = 360
TOTAL = 370


class Status:
    def __init__(self, nonce, start):
        self.nonce, self.start = nonce, start
        self.created = None
        self.pid = None
        self.block = {}
        self.final = {}
        self.result_code = None

    def deadline(self):
        return min(self.start + WORK, self.start + STARTUP if self.created is None else self.created + EXECUTION)

    def line(self, line, now):
        if now >= self.deadline():
            raise TimeoutError('startup' if self.created is None else 'execution')
        for prefix, target in (('INSTRUMENTATION_STATUS: ', self.block), ('INSTRUMENTATION_RESULT: ', self.final)):
            if line.startswith(prefix):
                key, sep, value = line[len(prefix):].partition('=')
                if sep:
                    if key in target or len(target) >= 64:
                        raise ValueError('duplicate/oversized status')
                    target[key] = value
                return
        if line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            if self.block.get('recorder_media_stage') == 'created':
                pid = self.block.get('recorder_media_pid', '')
                if line != 'INSTRUMENTATION_STATUS_CODE: 0' or self.created is not None or self.block.get('recorder_run_nonce') != self.nonce or not re.fullmatch('[1-9][0-9]{0,9}', pid):
                    raise ValueError('invalid created identity')
                self.created, self.pid = now, int(pid)
            self.block = {}
        elif line.startswith('INSTRUMENTATION_CODE: '):
            if self.result_code is not None:
                raise ValueError('duplicate terminal code')
            self.result_code = line.split(': ', 1)[1]

    def passed(self, key):
        return (self.created is not None and self.result_code == '-1' and
                self.final.get(key) == 'PASS' and
                self.final.get('recorder_run_nonce') == self.nonce and
                self.final.get('recorder_media_pid') == str(self.pid))


def cleanup(package, deadline, run=subprocess.run, clock=time.monotonic):
    # Only the known synthetic target package on this disposable emulator.
    for command in (['adb', 'shell', 'am', 'force-stop', package], ['adb', 'shell', 'pidof', package]):
        remaining = deadline - clock()
        if remaining <= 0:
            return 'deadline'
        try:
            result = run(command, capture_output=True, timeout=remaining)
        except (OSError, subprocess.TimeoutExpired):
            return 'unavailable'
        if command[2] == 'am':
            if result.returncode:
                return 'force-stop-failed'
        elif result.returncode != 1 or result.stdout.strip() or result.stderr.strip():
            return 'caller-disappearance-unverified'
    return 'stopped-and-absent'


def main():
    application, mode, runner = sys.argv[1:]
    assert application == 'org.androidcontrol.app' and mode in ('integration', 'decoder-only')
    assert runner in tuple(application + '.test/org.androidcontrol.app.regression.' + name for name in ('RecorderMediaInstrumentation', 'RecorderControlInstrumentation'))
    root = Path('recorder-media-results')
    nonce = uuid.uuid4().hex
    start, wall = time.monotonic(), time.time_ns()
    state = Status(nonce, start)
    key = 'recorder_media' if mode == 'integration' else 'recorder_decoder_probe'
    receipt = dict(schema=2, nonce=nonce, startupSeconds=STARTUP, executionSeconds=EXECUTION,
                   workCeilingSeconds=WORK, totalCeilingSeconds=TOTAL, hostStartedWallNs=wall)
    child = None
    code, reason = 1, 'not-started'
    pending = b''
    size = 0
    try:
        child = subprocess.Popen(['adb', 'shell', 'am', 'instrument', '-w', '-e', 'recorder_mode', mode,
                                  '-e', 'recorder_run_nonce', nonce, runner], stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, start_new_session=True)
        with selectors.DefaultSelector() as poll, (root / 'instrumentation.txt').open('wb') as output:
            poll.register(child.stdout, selectors.EVENT_READ)
            while True:
                remaining = state.deadline() - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError('startup' if state.created is None else 'execution')
                if not poll.select(min(remaining, .25)):
                    continue
                chunk = os.read(child.stdout.fileno(), 4096)
                if not chunk:
                    break
                size += len(chunk)
                if size > 4 * 1024 * 1024:
                    raise ValueError('output-limit')
                output.write(chunk); output.flush()
                pending += chunk
                while b'\n' in pending:
                    line, pending = pending.split(b'\n', 1)
                    state.line(line.decode('utf-8', errors='replace').rstrip('\r'), time.monotonic())
                if len(pending) > 65536:
                    raise ValueError('line-limit')
            code = child.wait(timeout=max(.001, state.deadline() - time.monotonic()))
            if time.monotonic() >= state.deadline():
                raise TimeoutError('startup' if state.created is None else 'execution')
            if pending or not state.passed(key) or code != 0:
                code, reason = 1, 'missing-or-invalid-terminal'
            else:
                reason = 'terminal-pass'
    except (TimeoutError, subprocess.TimeoutExpired) as failure:
        code, reason = 124, str(failure) if isinstance(failure, TimeoutError) else 'execution'
    except (OSError, ValueError) as failure:
        code, reason = 1, type(failure).__name__ + ':' + str(failure)
    finally:
        work_end = time.monotonic()
        # One cleanup reserve, never reset by output or failed cleanup commands.
        cleanup_deadline = min(start + TOTAL, work_end + 10)
        if child is not None:
            if child.poll() is None:
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            try:
                child.wait(timeout=max(.001, cleanup_deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                code, reason = 1, 'local-child-not-reaped'
            child.stdout.close()
        receipt["commandExitCode"] = code
        cleanup_status = cleanup(application, cleanup_deadline)
        end = time.monotonic()
        if cleanup_status != 'stopped-and-absent' or end > start + TOTAL:
            code = 1
        receipt.update(exitCode=code, timedOut=reason in ('startup', 'execution'), outcome=reason,
                       pid=state.pid, finalPassVerified=state.passed(key), cleanup=cleanup_status,
                       startupDurationNs=int(((work_end if state.created is None else state.created) - start) * 1e9),
                       executionDurationNs=None if state.created is None else int((work_end-state.created)*1e9),
                       hostWorkDurationNs=int((work_end-start)*1e9), hostDurationNs=int((end-start)*1e9),
                       hostCompletedWallNs=time.time_ns(), totalCeilingAdhered=end <= start + TOTAL)
        (root / 'media-command.json').write_text(json.dumps(receipt) + '\n')
    return code


if __name__ == '__main__':
    raise SystemExit(main())
