#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Numeric ephemeral-runner resources and build-daemon absence; no raw process dump."""
import json
import os
import platform
from pathlib import Path
import re
import subprocess

DAEMONS = {'org.gradle.launcher.daemon.bootstrap.GradleDaemon': 'gradleDaemonCount',
           'org.jetbrains.kotlin.daemon.KotlinCompileDaemon': 'kotlinDaemonCount'}


def daemon_counts(text):
    if len(text.encode()) > 32768:
        raise ValueError('oversized JVM inventory')
    counts = dict.fromkeys(DAEMONS.values(), 0)
    saw_jps = False
    for line in text.splitlines():
        fields = line.split()
        if len(fields) != 2 or not fields[0].isdigit() or fields[1] == '--':
            raise ValueError('unavailable JVM class')
        saw_jps = saw_jps or fields[1] in ('sun.tools.jps.Jps', 'jdk.jcmd/sun.tools.jps.Jps')
        if fields[1] in DAEMONS:
            counts[DAEMONS[fields[1]]] += 1
    if not saw_jps:
        raise ValueError('JVM inventory self-check unavailable')
    return counts


def command(args, run):
    result = run(args, capture_output=True, timeout=10)
    if result.returncode or len(result.stdout) > 32768:
        raise ValueError('unavailable bounded command')
    return result.stdout.decode()


def collect(run=subprocess.run):
    report = dict(schema=1, hostSystem=platform.system(), hostArch=platform.machine(),
                  logicalCpus=os.cpu_count(), loadAverage=list(os.getloadavg()),
                  scope='ephemeral CI numeric host resources and build-daemon counts')
    try:
        if report['hostSystem'] == 'Darwin':
            report['physicalMemoryBytes'] = int(command(['sysctl', '-n', 'hw.memsize'], run).strip())
            report['physicalCpus'] = int(command(['sysctl', '-n', 'hw.physicalcpu'], run).strip())
            raw = command(['vm_stat'], run)
            page_size = re.search(r'page size of (\d+) bytes', raw)
            if not page_size:
                raise ValueError('missing page size')
            pages = {}
            for line in raw.splitlines():
                match = re.fullmatch(r'(Pages free|Pages active|Pages inactive|Pages speculative|Pages wired down|Pages occupied by compressor):\s+(\d+)\.', line)
                if match:
                    pages[match.group(1)] = int(match.group(2))
            report['memoryPageSizeBytes'] = int(page_size.group(1))
            report['memoryPages'] = pages
        elif report['hostSystem'] == 'Linux':
            raw = Path('/proc/meminfo').read_text()
            report['memoryKiB'] = {m.group(1): int(m.group(2)) for m in re.finditer(
                r'^(MemTotal|MemFree|MemAvailable):\s+(\d+) kB$', raw, re.M)}
        report['memoryStatus'] = 'observed'
    except (OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
        report['memoryStatus'] = 'unavailable'
    try:
        jps = str(Path(os.environ['JAVA_HOME']) / 'bin/jps')
        counts = daemon_counts(command([jps, '-l'], run))
        report.update(counts)
        report['buildDaemonsAbsent'] = all(value == 0 for value in counts.values())
        report['daemonStatus'] = 'observed'
    except (KeyError, OSError, ValueError, UnicodeError, subprocess.TimeoutExpired):
        report['buildDaemonsAbsent'] = False
        report['daemonStatus'] = 'unavailable'
    return report


if __name__ == '__main__':
    report = collect()
    path = Path('recorder-media-results/host-resources.json')
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(report, indent=2) + '\n')
    raise SystemExit(0 if report['buildDaemonsAbsent'] else 1)
