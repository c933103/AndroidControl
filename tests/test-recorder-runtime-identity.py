#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('identity', Path(__file__).with_name('recorder-runtime-identity.py'))
identity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(identity)


class IdentityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for path in ('emulator/source.properties', 'system-images/android-35/google_apis/x86_64/source.properties'):
            target = self.root / path
            target.parent.mkdir(parents=True)
            target.write_text('Pkg.Revision=37.2.13\nIgnored=do-not-retain\n')
        self.values = dict(zip(identity.PROPERTIES, ('google/sdk/emulator:15/build/revision:userdebug/test-keys', '12345', '35', 'x86_64')))
        self.calls = []
        self.online = "0-1"

    def run_adb(self, command, **kwargs):
        if command == ['adb', 'shell', 'cat', '/sys/devices/system/cpu/online']:
            self.calls.append(command)
            self.assertEqual(kwargs, dict(capture_output=True, timeout=10))
            return subprocess.CompletedProcess(command, 0, self.online.encode(), b'')
        self.assertEqual(command[:3], ['adb', 'shell', 'getprop'])
        self.assertIn(command[3], identity.PROPERTIES)
        self.assertEqual(kwargs, dict(capture_output=True, timeout=10))
        self.calls.append(command)
        return subprocess.CompletedProcess(command, 0, (self.values[command[3]] + '\r\n').encode(), b'')

    def test_complete_allowlist(self):
        report = identity.collect(self.root, '35', self.run_adb)
        self.assertTrue(report['complete'])
        self.assertEqual(len(self.calls), 5)
        self.assertNotIn('Ignored', str(report))
        self.assertEqual(report['guest'], self.values)

    def test_guest_cpu_baseline(self):
        self.assertTrue(identity.collect(self.root, '35', self.run_adb, min_cpus=2)['complete'])
        for online in ('0', '', '0-1\nprivate', 'bogus'):
            self.online = online
            self.assertFalse(identity.collect(self.root, '35', self.run_adb, min_cpus=2)['complete'])

    def test_missing_revision(self):
        (self.root / 'emulator/source.properties').unlink()
        self.assertFalse(identity.collect(self.root, '35', self.run_adb)['complete'])

    def test_duplicate_revision(self):
        (self.root / 'emulator/source.properties').write_text('Pkg.Revision=1\nPkg.Revision=2\n')
        self.assertFalse(identity.collect(self.root, '35', self.run_adb)['complete'])

    def test_missing_and_mismatched_properties(self):
        for prop, value in [('ro.build.fingerprint', ''), ('ro.build.version.sdk', '33'), ('ro.product.cpu.abi', 'arm64-v8a')]:
            with self.subTest(prop=prop):
                old = self.values[prop]
                self.values[prop] = value
                self.assertFalse(identity.collect(self.root, '35', self.run_adb)['complete'])
                self.values[prop] = old

    def test_oversized_or_multiline(self):
        for value in ('x' * 1025, 'one\ntwo'):
            self.values['ro.build.fingerprint'] = value
            self.assertFalse(identity.collect(self.root, '35', self.run_adb)['complete'])

    def test_timeout(self):
        def timeout(command, **kwargs):
            raise subprocess.TimeoutExpired(command, 10)
        report = identity.collect(self.root, '35', timeout)
        self.assertFalse(report['complete'])
        self.assertEqual(report['guest'], {})

    def test_exact_comparison_and_confounded_identity_rejected(self):
        from copy import deepcopy
        for api in ('33', '35'):
            image, incremental, fingerprint, cpus = identity.COMPARISON_GUESTS[api]
            baseline = dict(packages={'emulator': {'revision': '37.2.12'}, 'systemImage': {'revision': image}},
                            guest={'ro.build.version.incremental': incremental, 'ro.build.fingerprint': fingerprint},
                            guestOnlineCpus=cpus, errors=[])
            def version(command, **kwargs):
                self.assertEqual(command, [str(self.root / 'emulator/emulator'), '-version'])
                self.assertEqual(kwargs, dict(capture_output=True, timeout=10))
                return subprocess.CompletedProcess(command, 0, b'Android emulator version 37.2.12.0 (build_id 16428233) (CL:N/A)', b'')
            self.assertTrue(identity.verify_comparison(deepcopy(baseline), self.root, api, version)['complete'])
            for branch, key in [('packages', 'systemImage'), ('packages', 'emulator'), ('guest', 'ro.build.fingerprint'), ('guest', 'ro.build.version.incremental')]:
                bad = deepcopy(baseline)
                bad[branch][key] = {'revision': 'wrong'} if branch == 'packages' else 'wrong'
                self.assertFalse(identity.verify_comparison(bad, self.root, api, version)['complete'])
            bad = deepcopy(baseline); bad['guestOnlineCpus'] = 99
            self.assertFalse(identity.verify_comparison(bad, self.root, api, version)['complete'])
            for data in (b'Android emulator version 37.2.13.0 (build_id 16740729)', b'x' * 4097, b''):
                def wrong(command, **kwargs):
                    return subprocess.CompletedProcess(command, 0, data, b'')
                self.assertFalse(identity.verify_comparison(deepcopy(baseline), self.root, api, wrong)['complete'])

    def test_runner_gates_before_install_and_retains_timestamp_scope(self):
        runner = Path(__file__).with_name('run-recorder-media-checks.sh').read_text()
        self.assertLess(runner.index('python3 tests/recorder-runtime-identity.py'), runner.index('adb install'))
        self.assertIn('logcat --pid="$pid" -d -v threadtime -v monotonic -t 300 -s MediaCodec CCodec CCodecConfig Codec2Client', runner)
        self.assertIn('timeout=180', runner)


if __name__ == '__main__':
    unittest.main()
