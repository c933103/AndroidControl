#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Failure-only package/PID/time-bound exit metadata; never retain raw dumps."""
import datetime as dt
import json
import re
import subprocess
import sys

PACKAGE = 'org.androidcontrol.app'
LIMIT = 131072


def select_records(source, pid, started_ms, ended_ms, offset):
    if len(source.encode()) > LIMIT or not 0 < pid or not 0 <= ended_ms - started_ms <= 300000:
        raise ValueError('Invalid diagnostic bounds')
    zone = dt.datetime.strptime(offset, '%z').tzinfo
    package = None
    records = []
    stamp = None
    for line in source.splitlines():
        match = re.fullmatch(r'\s*package: (\S+)\s*', line)
        if match:
            package = match[1]
            stamp = None
        if 'ApplicationExitInfo ' in line:
            stamp = None
        match = re.fullmatch(r'\s*timestamp=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) pid=(\d+) realUid=\d+ packageUid=\d+ definingUid=\d+ user=\d+\s*', line)
        if match:
            when = dt.datetime.strptime(match[1], '%Y-%m-%d %H:%M:%S.%f').replace(tzinfo=zone)
            millis = round(when.timestamp() * 1000)
            stamp = millis if package == PACKAGE and int(match[2]) == pid and started_ms <= millis <= ended_ms else None
        match = re.fullmatch(r'\s*process=(\S+) reason=(\d+) \(([A-Z_]+)\) subreason=(\d+) \([^\r\n]*\) status=(-?\d+)\s*', line)
        if match and stamp is not None:
            if match[1] == PACKAGE:
                records.append(dict(package=PACKAGE, pid=pid, timestampMs=stamp,
                                    reason=int(match[2]), reasonName=match[3],
                                    subreason=int(match[4]), status=int(match[5])))
            stamp = None
    return sorted(records, key=lambda item: item['timestampMs'], reverse=True)[:4]


def command(*args):
    # Command is package-scoped; timeout and retained output are bounded. No raw archive.
    result = subprocess.run(['adb', 'shell', *args], capture_output=True, timeout=10, check=True)
    if len(result.stdout) > LIMIT:
        raise ValueError('Diagnostic output exceeded limit')
    return result.stdout.decode('utf-8', errors='strict').strip()


def main():
    try:
        pid, started = map(int, sys.argv[1:3])
        clock = re.fullmatch(r'(\d+)([+-]\d{4})', command('date', '+%s%z'))
        if clock is None:
            raise ValueError('Invalid device clock')
        epoch, offset = clock.groups()
        ended = int(epoch) * 1000 + 999
        records = select_records(command('dumpsys', 'activity', 'exit-info', PACKAGE), pid, started, ended, offset)
        result = dict(package=PACKAGE, pid=pid, startedMs=started, observedThroughMs=ended,
                      deviceOffset=offset, records=records,
                      result='matched' if records else 'no-matching-exit-record')
    except (ValueError, OSError, subprocess.SubprocessError):
        # Do not expose unexpected command output or unrelated metadata on failure.
        result = dict(package=PACKAGE, result='diagnostic-unavailable')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
