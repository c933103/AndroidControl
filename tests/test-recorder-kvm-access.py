#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
import errno
import os
import stat
from types import SimpleNamespace
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

# Pure metadata controls do not open /dev/kvm or execute permission commands.
meta = m.device_context(
    lstat=lambda path: SimpleNamespace(st_mode=stat.S_IFCHR | 0o660, st_uid=0, st_gid=108,
                                      st_rdev=os.makedev(10, 232)),
    geteuid=lambda: 1001, getgroups=lambda: [108, 4], which=lambda name: '/tool/' + name)
assert meta['effectiveUid'] == 1001 and meta['supplementaryGroups'] == [4, 108]
assert meta['device'] == dict(character=True, symlink=False, mode=0o660, uid=0, gid=108, major=10, minor=232)
assert meta['toolsPresent'] == {'getfacl': True, 'setfacl': True}
def missing(path):
    raise FileNotFoundError(errno.ENOENT, 'fixture')
meta = m.device_context(missing, lambda: 1001, lambda: [], lambda name: None)
assert meta['device'] == {'unavailable': True, 'errno': errno.ENOENT}
assert not any(meta['toolsPresent'].values())
# Diagnostic standard runners only; unchanged normal rows and no automatic fallback.
import json, re
rows = [json.loads(text) for text in re.findall(r"'(\[.*?\])'", workflow)]
assert [row['runner'] for row in rows[0]] == ['ubuntu-22.04', 'ubuntu-24.04']
assert all(row['mode'] == 'decoder-only' and row['application'] == 'manager' for row in rows[0])
assert len({row['artifact-suffix'] for row in rows[0]}) == 2
assert [(row['api'], row['runner'], row['application'], row['mode']) for row in rows[1]] == [
    (33, 'ubuntu-latest', 'manager', 'integration'),
    (35, 'macos-15-intel', 'manager', 'integration'),
    (35, 'macos-15-intel', 'control', 'integration')]
assert "&& '37.2.12-kvm2' || ''" in workflow
print('PASS numeric capability metadata and explicit standard-runner/no-mutation matrix')
