#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import subprocess
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('host', Path(__file__).with_name('recorder-host-resources.py'))
host = importlib.util.module_from_spec(spec); spec.loader.exec_module(host)
assert host.daemon_counts('42 jdk.jcmd/sun.tools.jps.Jps\n') == dict(gradleDaemonCount=0, kotlinDaemonCount=0)
assert host.daemon_counts('41 jdk.jcmd/sun.tools.jps.Jps\n42 org.gradle.launcher.daemon.bootstrap.GradleDaemon\n43 org.jetbrains.kotlin.daemon.KotlinCompileDaemon\n') == dict(gradleDaemonCount=1, kotlinDaemonCount=1)
for text in ('', 'not-a-pid unknown', '42 -- process information unavailable', 'x' * 32769):
    try:
        host.daemon_counts(text)
    except ValueError:
        pass
    else:
        raise AssertionError('malformed inventory accepted')
for inventory, status, absent in (
    ('42 jdk.jcmd/sun.tools.jps.Jps\n', 'observed', True),
    ('41 jdk.jcmd/sun.tools.jps.Jps\n42 org.gradle.launcher.daemon.bootstrap.GradleDaemon\n', 'observed', False),
    ('42 -- process information unavailable\n', 'unavailable', False),
):
    def run(args, **kwargs):
        assert kwargs == dict(capture_output=True, timeout=10)
        if args[-1] == '-l': output = inventory
        elif args[-1] == 'hw.memsize': output = '15032385536\n'
        elif args[-1] == 'hw.physicalcpu': output = '4\n'
        else: output = 'Mach Virtual Memory Statistics: (page size of 4096 bytes)\nPages free: 100.\nPages active: 200.\nUnrelated field: ignored\n'
        return subprocess.CompletedProcess(args, 0, output.encode(), b'')
    with patch.dict(host.os.environ, {'JAVA_HOME': '/test/java'}), patch.object(host.platform, 'system', return_value='Darwin'):
        report = host.collect(run)
    assert report['daemonStatus'] == status and report['buildDaemonsAbsent'] is absent
    assert report['memoryPages'] == {'Pages free': 100, 'Pages active': 200}
    assert 'inventory' not in report and 'Unrelated' not in str(report)
workflow = Path(__file__).parents[1].joinpath('.github/workflows/recorder-media.yml').read_text()
assert './gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process -PrecorderMediaTests=true' in workflow
assert workflow.index('python3 tests/recorder-host-resources.py') < workflow.index('uses: reactivecircus/android-emulator-runner@v2')
assert workflow.index('Check target-only ANR thread selector') < workflow.index('python3 tests/recorder-host-resources.py')
print('PASS numeric host receipt, exact daemon counts, malformed fail-closed and build/launch ordering')
