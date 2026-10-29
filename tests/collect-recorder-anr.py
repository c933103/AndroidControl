#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Collect one exact target ANR through its own public API, never a global trace."""
import json
from pathlib import Path
import subprocess

PACKAGE = 'org.androidcontrol.app'
ROOT = Path('recorder-media-results')


def select_request(evidence):
    if evidence.get('package') != PACKAGE or evidence.get('result') != 'matched':
        return None
    pid, start, end = (evidence.get(k) for k in ('pid', 'startedMs', 'observedThroughMs'))
    if not all(type(v) is int for v in (pid, start, end)) or pid <= 0 or start <= 0 or not 0 <= end - start <= 300000:
        return None
    for record in evidence.get('records', [])[:4]:
        timestamp = record.get('timestampMs')
        if (record.get('package') == PACKAGE and record.get('pid') == pid and record.get('reason') == 6
                and type(timestamp) is int and start <= timestamp <= end):
            return dict(pid=pid, started=start, observed=end, timestamp=timestamp)
    return None


def main():
    receipt = dict(result='not-applicable')
    try:
        request = select_request(json.loads((ROOT / 'app-exit-info.json').read_text()))
        if request is not None:
            args = ['adb', 'shell', 'am', 'instrument', '-w']
            for key, value in request.items():
                args += ['-e', 'anr_' + key, str(value)]
            args += [PACKAGE + '.test/org.androidcontrol.app.regression.RecorderMediaInstrumentation']
            run = subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
            receipt = dict(result='attempted', diagnosticExitCode=run.returncode, **request)
            result = subprocess.run(['adb', 'exec-out', 'run-as', PACKAGE, 'cat', 'cache/recorder-media/anr-threads.json'],
                                    capture_output=True, timeout=10, check=True)
            if len(result.stdout) > 65536:
                raise ValueError('Bound exceeded')
            report = json.loads(result.stdout)
            if not (report.get('package') == PACKAGE and report.get('pid') == request['pid']
                    and report.get('timestampMs') == request['timestamp']):
                raise ValueError('Unbound report')
            (ROOT / 'anr-threads.json').write_text(json.dumps(report, indent=2) + '\n')
    except (ValueError, OSError, subprocess.SubprocessError):
        receipt['result'] = 'diagnostic-unavailable'
    (ROOT / 'anr-diagnostic.json').write_text(json.dumps(receipt, sort_keys=True) + '\n')


if __name__ == '__main__':
    main()
