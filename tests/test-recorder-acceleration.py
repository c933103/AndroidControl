#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Synthetic capability controls; do not use the local host's hypervisor."""
import importlib.util
from pathlib import Path
spec = importlib.util.spec_from_file_location('accel', Path(__file__).with_name('check-recorder-acceleration.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
for name, code, output, expected in (
    ('official-hvf', 0, 'accel:\n0\nHypervisor.Framework OS X Version 15.0\naccel', True),
    ('hvf-name', 0, 'accel:\n0\nHVF usable\naccel', True),
    ('nonzero-exit', 1, 'accel:\n0\nHypervisor.Framework\naccel', False),
    ('nonzero-status', 0, 'accel:\n8\nHypervisor.Framework unavailable\naccel', False),
    ('wrong-hypervisor', 0, 'accel:\n0\nKVM usable\naccel', False),
    ('empty', 0, '', False),
    ('partial', 0, 'accel:\n0\nHypervisor.Framework', False),
    ('missing-status', 0, 'Hypervisor.Framework', False),
    ('oversized', 0, 'accel:\n0\nHypervisor.Framework' + 'x' * 4096, False),
):
    assert m.usable(code, output) == expected, name
    print('PASS', name)


import contextlib
import io
import json
import os
import tempfile
from unittest.mock import patch

gate = Path(__file__).with_name('run-recorder-media-checks.sh').read_text().split("<<'ACCEL'\n", 1)[1].split('\nACCEL', 1)[0]
with tempfile.TemporaryDirectory() as directory:
    previous = os.getcwd()
    os.chdir(directory)
    try:
        for name, code, output, allowed in (
            ('probe-success', 0, b'accel:\n0\nHypervisor.Framework OS X Version 15.0\naccel\n', True),
            ('probe-failure', 1, b'accel:\n8\nHypervisor.Framework unavailable\naccel\n', False),
            ('probe-partial', 0, b'accel:\n0\nHypervisor.Framework', False),
        ):
            calls = []
            def fake_run(args, **kwargs):
                calls.append(args)
                assert kwargs == dict(capture_output=True, timeout=15)
                return type('Result', (), dict(returncode=code, stdout=output))()
            with patch.object(m.platform, 'system', return_value='Darwin'), patch.object(m.platform, 'machine', return_value='x86_64'), patch.dict(m.os.environ, {'ANDROID_HOME': '/synthetic-sdk'}), patch.object(m.subprocess, 'run', fake_run), patch.object(m.sys, 'argv', ['script', '35']), contextlib.redirect_stdout(io.StringIO()):
                assert m.main() == (0 if allowed else 1)
            assert calls == [['/synthetic-sdk/emulator/emulator', '-accel-check']]
            accepted = True
            try:
                exec(gate, {})
            except AssertionError:
                accepted = False
            assert accepted == allowed, name
            print('PASS', name)
        for name, content in [('missing', None), ('empty-json', '{}'), ('partial-json', '{'), ('false-receipt', '{"usable":false,"requestedMode":"on"}'), ('wrong-mode', '{"usable":true,"requestedMode":"auto"}')]:
            path = Path('recorder-media-results/acceleration.json')
            path.unlink(missing_ok=True)
            if content is not None:
                path.write_text(content)
            try:
                exec(gate, {})
                raise RuntimeError('Gate accepted ' + name)
            except (AssertionError, FileNotFoundError, json.JSONDecodeError):
                print('PASS gate-' + name)
    finally:
        os.chdir(previous)


for code, output, expected in [(0, 'accel:\n0\nKVM (version 12) is installed and usable.\naccel', True), (1, 'accel:\n0\nKVM usable\naccel', False), (0, 'accel:\n0\nHVF usable\naccel', False), (0, '', False)]:
    assert m.usable(code, output, 'kvm') == expected
print('PASS explicit KVM acceleration scope and failure controls')
