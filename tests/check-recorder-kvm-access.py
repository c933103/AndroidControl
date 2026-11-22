#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Observe existing KVM access only. Never chmod, sudo, install, or join groups."""
import json
import os
from pathlib import Path
import platform
import shutil
import stat


def device_context(lstat=os.lstat, geteuid=os.geteuid, getgroups=os.getgroups, which=shutil.which):
    """Numeric current-job/device context; no command execution or ACL modification."""
    result = {'effectiveUid': geteuid(), 'supplementaryGroups': sorted(getgroups()),
              'toolsPresent': {name: which(name) is not None for name in ('getfacl', 'setfacl')}}
    try:
        device = lstat('/dev/kvm')
        result['device'] = dict(character=stat.S_ISCHR(device.st_mode),
                                symlink=stat.S_ISLNK(device.st_mode),
                                mode=stat.S_IMODE(device.st_mode), uid=device.st_uid,
                                gid=device.st_gid, major=os.major(device.st_rdev),
                                minor=os.minor(device.st_rdev))
    except OSError as error:
        result['device'] = {'unavailable': True, 'errno': error.errno}
    return result


def probe(open_device=os.open, close_device=os.close, ioctl=None):
    result = dict(usable=False, hostSystem=platform.system(), hostArch=platform.machine(),
                  logicalCpus=os.cpu_count(), imageVersion=os.environ.get('ImageVersion', 'unknown'))
    if result['hostSystem'] != 'Linux' or result['hostArch'] != 'x86_64' or (result['logicalCpus'] or 0) < 2:
        result['error'] = 'unsupported-host'
        return result
    result.update(device_context())
    try:
        if ioctl is None:
            import fcntl
            ioctl = fcntl.ioctl
        fd = open_device('/dev/kvm', os.O_RDWR | os.O_CLOEXEC)
        try:
            version = ioctl(fd, 0xAE00)  # KVM_GET_API_VERSION, read-only capability ioctl.
        finally:
            close_device(fd)
        result['kvmApiVersion'] = version
        result['usable'] = version == 12
    except OSError as error:
        result['error'] = 'kvm-access-unavailable'
        result['errno'] = error.errno
    return result


if __name__ == '__main__':
    result = probe()
    directory = Path('recorder-media-results'); directory.mkdir(exist_ok=True)
    (directory / 'host-capability.json').write_text(json.dumps(result, sort_keys=True) + '\n')
    print(json.dumps(result, sort_keys=True))
    raise SystemExit(0 if result['usable'] else 1)
