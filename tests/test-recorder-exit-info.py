#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Synthetic positive/negative controls for package/PID/time-bound diagnostics."""
import importlib.util
from pathlib import Path
spec = importlib.util.spec_from_file_location('exit_info', Path(__file__).with_name('recorder-exit-info.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
start = 1793250000000  # 2026-10-29 05:00:00 UTC


def record(package=module.PACKAGE, process=module.PACKAGE, pid=123, stamp='2026-10-29 05:00:01.123'):
    return f'''  package: {package}
    Historical Process Exit for uid=10123
      ApplicationExitInfo #0:
        timestamp={stamp} pid={pid} realUid=10123 packageUid=10123 definingUid=10123 user=0
        process={process} reason=3 (LOW_MEMORY) subreason=0 (UNKNOWN) status=9
        importance=100 pss=1MB rss=2MB description=DO_NOT_RETAIN state=empty trace=DO_NOT_RETAIN
'''


def select(source, **kwargs):
    return module.select_records(source, kwargs.get('pid', 123), kwargs.get('start', start),
                                 kwargs.get('end', start + 3000), kwargs.get('offset', '+0000'))


for name, source, expected in (
    ('matched', record(), 1),
    ('unrelated-package', record(package='another.app'), 0),
    ('lookalike-package', record(package=module.PACKAGE + '.other'), 0),
    ('unrelated-process', record(process='another.app'), 0),
    ('unrelated-pid', record(pid=999), 0),
    ('stale-same-pid', record(stamp='2026-10-29 04:59:59.999'), 0),
    ('future', record(stamp='2026-10-29 05:00:04.000'), 0),
    ('mixed', record() + record(package='another.app') + record(pid=999), 1),
    ('bounded-records', record() * 8, 4),
):
    result = select(source)
    assert len(result) == expected, (name, result)
    assert 'DO_NOT_RETAIN' not in str(result)
    if result:
        assert result[0]['reason'] == 3 and result[0]['status'] == 9
        assert result[0]['timestampMs'] == start + 1123
    print('PASS', name)
assert len(select(record(stamp='2026-10-29 13:00:01.123'), offset='+0800')) == 1
print('PASS device-timezone')
for name, source, kwargs in (
    ('oversize', 'x' * (module.LIMIT + 1), {}),
    ('clock-rollback', record(), {'end': start - 1}),
    ('stale-window', record(), {'end': start + 300001}),
    ('invalid-pid', record(), {'pid': 0}),
):
    try:
        select(source, **kwargs)
        raise AssertionError(name)
    except ValueError:
        print('PASS', name)

import contextlib
import io
import json
from unittest.mock import patch

for name, values, expected in (
    ('command-scope', [b'1793250003+0000', record().encode()], 'matched'),
    ('command-denied', [OSError('DO_NOT_RETAIN')], 'diagnostic-unavailable'),
    ('missing-record', [b'1793250003+0000', b''], 'no-matching-exit-record'),
):
    calls = []
    def fake_run(args, **kwargs):
        calls.append(args)
        assert kwargs == dict(capture_output=True, timeout=10, check=True)
        value = values.pop(0)
        if isinstance(value, Exception):
            raise value
        return type('Result', (), {'stdout': value})()
    output = io.StringIO()
    with patch.object(module.subprocess, 'run', fake_run), patch.object(module.sys, 'argv', ['script', '123', str(start)]), contextlib.redirect_stdout(output):
        module.main()
    parsed = json.loads(output.getvalue())
    assert parsed['result'] == expected, parsed
    assert 'DO_NOT_RETAIN' not in output.getvalue()
    assert calls[0] == ['adb', 'shell', 'date', '+%s%z']
    if len(calls) > 1:
        assert calls[1] == ['adb', 'shell', 'dumpsys', 'activity', 'exit-info', module.PACKAGE]
    print('PASS', name)
