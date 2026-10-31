#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
import errno
import importlib.util
from pathlib import Path
from unittest.mock import patch
spec = importlib.util.spec_from_file_location('kvm', Path(__file__).with_name('check-recorder-kvm-access.py'))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
with patch.object(m.platform, 'system', return_value='Linux'), patch.object(m.platform, 'machine', return_value='x86_64'), patch.object(m.os, 'cpu_count', return_value=4):
    calls = []
    def opened(path, flags):
        assert path == '/dev/kvm' and flags == m.os.O_RDWR | m.os.O_CLOEXEC
        calls.append('open'); return 7
    def closed(fd):
        assert fd == 7; calls.append('close')
    def ioctl(fd, request):
        assert fd == 7 and request == 0xAE00
        calls.append('ioctl'); return 12
    assert m.probe(opened, closed, ioctl)['usable'] is True
    assert calls == ['open', 'ioctl', 'close']
    assert m.probe(opened, closed, lambda fd, request: 11)['usable'] is False
    def denied(path, flags):
        raise PermissionError(errno.EACCES, 'test')
    result = m.probe(denied, closed, ioctl)
    assert result['usable'] is False and result['errno'] == errno.EACCES
    with patch.object(m.os, 'cpu_count', return_value=1):
        assert m.probe(opened, closed, ioctl)['error'] == 'unsupported-host'
print('PASS KVM existing-access, version, denial, close and host controls')
workflow = Path(__file__).parents[1].joinpath('.github/workflows/recorder-media.yml').read_text()
assert 'sudo' not in workflow and 'chmod' not in workflow and 'udev' not in workflow
assert "disable-linux-hw-accel: ${{ matrix.mode != 'decoder-only' || runner.os != 'Linux' }}" in workflow
assert workflow.index('tests/check-recorder-kvm-access.py') < workflow.index('Build unchanged debug signing')
print('PASS KVM preflight before build and no permission mutation/software fallback')
