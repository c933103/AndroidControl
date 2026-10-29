#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Synthetic negative controls for the one-shot own-package ANR query."""
import copy
import importlib.util
from pathlib import Path
spec = importlib.util.spec_from_file_location('anr', Path(__file__).with_name('collect-recorder-anr.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
base = dict(package=m.PACKAGE, result='matched', pid=123, startedMs=1000, observedThroughMs=3000,
            records=[dict(package=m.PACKAGE, pid=123, timestampMs=2000, reason=6)])
assert m.select_request(base) == dict(pid=123, started=1000, observed=3000, timestamp=2000)
print('PASS exact-anr-request')
for name, key, value, nested in (
    ('wrong-package', 'package', 'other.app', False),
    ('wrong-record-package', 'package', 'other.app', True),
    ('wrong-pid', 'pid', 999, True),
    ('wrong-reason', 'reason', 5, True),
    ('stale', 'timestampMs', 999, True),
    ('future', 'timestampMs', 3001, True),
    ('clock-rollback', 'observedThroughMs', 999, False),
    ('oversized-window', 'observedThroughMs', 301001, False),
    ('missing-match', 'result', 'no-matching-exit-record', False),
    ('invalid-pid', 'pid', 0, False),
):
    value_copy = copy.deepcopy(base)
    (value_copy['records'][0] if nested else value_copy)[key] = value
    assert m.select_request(value_copy) is None, name
    print('PASS', name)
