#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
package=org.androidcontrol.app
application_mode=${1:-manager}
media_mode=${2:-integration}
case "$media_mode" in
    integration) result_key=recorder_media ;;
    decoder-only) result_key=recorder_decoder_probe ;;
    *) echo 'Unsupported recorder media mode'; exit 1 ;;
esac
[[ "$media_mode" != decoder-only || "$application_mode" == manager ]] || { echo 'Decoder probe requires manager'; exit 1; }
case "$application_mode" in
    manager) runner_class=RecorderMediaInstrumentation ;;
    control) runner_class=RecorderControlInstrumentation ;;
    *) echo 'Unsupported application control mode'; exit 1 ;;
esac
runner="$package.test/org.androidcontrol.app.regression.$runner_class"
results=recorder-media-results
mkdir -p "$results"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Emulator required'; exit 1; }
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$sdk" == 33 || "$sdk" == 35 ]] || { echo "Unsupported test API: $sdk"; exit 1; }
if [[ "$sdk" == 35 ]]; then
    python3 - <<'ACCEL'
import json
from pathlib import Path
capability = json.loads(Path('recorder-media-results/acceleration.json').read_text())
assert capability.get('usable') is True and capability.get('requestedMode') == 'on', 'API35 acceleration not verified'
ACCEL
fi
# Fail closed before media if exact package/guest identity cannot be retained.
python3 tests/recorder-runtime-identity.py "$sdk" "${3:-1}"
shopt -s nullglob
apps=(manager/build/outputs/apk/debug/*.apk)
tests=(manager/build/outputs/apk/androidTest/debug/*.apk)
[[ ${#apps[@]} == 1 && ${#tests[@]} == 1 ]]
adb install -r "${apps[0]}"
adb install -r -t "${tests[0]}"
adb shell pm list instrumentation | tee "$results/registered-instrumentation.txt"
grep -Fq "$runner" "$results/registered-instrumentation.txt"
status=0
python3 -c 'import subprocess, sys, time
started_wall_ns = time.time_ns()
started_monotonic_ns = time.monotonic_ns()
try:
    result = subprocess.run(sys.argv[1:], timeout=180)
except subprocess.TimeoutExpired:
    code, timed_out = 124, True
else:
    code, timed_out = result.returncode, False
completed_monotonic_ns = time.monotonic_ns()
completed_wall_ns = time.time_ns()
import json
from pathlib import Path
Path("recorder-media-results/media-command.json").write_text(json.dumps(dict(exitCode=code, timedOut=timed_out, timeoutSeconds=180,
    hostStartedWallNs=started_wall_ns, hostCompletedWallNs=completed_wall_ns,
    hostStartedMonotonicNs=started_monotonic_ns, hostCompletedMonotonicNs=completed_monotonic_ns,
    hostDurationNs=completed_monotonic_ns-started_monotonic_ns)) + "\n")
sys.exit(code)
' adb shell am instrument -w -e recorder_mode "$media_mode" "$runner" 2>&1 | tee "$results/instrumentation.txt" || status=1
grep -Fxq "INSTRUMENTATION_RESULT: $result_key=PASS" "$results/instrumentation.txt" || status=1
if [[ "$application_mode" == control ]]; then expected_application=org.androidcontrol.app.regression.RecorderControlApplication
else expected_application=org.androidcontrol.app.ShizukuApplication; fi
grep -Fxq "INSTRUMENTATION_STATUS: recorder_application_class=$expected_application" "$results/instrumentation.txt" || status=1
if [[ "$status" != 0 ]]; then
    adb logcat -b crash -d -v brief -t 200 | python3 tests/filter-recorder-crash.py > "$results/app-crash.txt" || true
    pid=$(sed -n 's/^INSTRUMENTATION_STATUS: recorder_media_pid=//p' "$results/instrumentation.txt" | head -n 1 | tr -d '\r')
    started_ms=$(sed -n 's/^INSTRUMENTATION_STATUS: recorder_media_started_ms=//p' "$results/instrumentation.txt" | head -n 1 | tr -d '\r')
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
        if [[ "$started_ms" =~ ^[0-9]+$ ]]; then
            python3 tests/recorder-exit-info.py "$pid" "$started_ms" > "$results/app-exit-info.json" || true
        fi
        adb logcat --pid="$pid" -d -v threadtime -v monotonic -t 300 -s MediaCodec CCodec CCodecConfig Codec2Client > "$results/app-codec.txt" || true
    fi
fi
names=(report.json aac-1.m4a aac-2.m4a opus-1.ogg opus-2.ogg)
if [[ "$media_mode" == decoder-only ]]; then names=(report.json decoder-input.m4a); fi
for name in "${names[@]}"; do
    if ! adb exec-out run-as "$package" cat "cache/recorder-media/$name" > "$results/$name"; then
        rm -f "$results/$name"
        status=1
    fi
    [[ -s "$results/$name" ]] || status=1
done
# adb exec-out can return zero while remote cat prints an error: validate bytes,
# not only command status, so missing artifacts cannot masquerade as audio/JSON.
python3 - "$media_mode" <<'VALIDATE' || status=1
import json
import hashlib
import sys
from pathlib import Path
mode = sys.argv[1]
assert mode in ('integration', 'decoder-only')
root = Path('recorder-media-results')
valid = True
names = ('report.json', 'decoder-input.m4a') if mode == 'decoder-only' else ('report.json', 'aac-1.m4a', 'aac-2.m4a', 'opus-1.ogg', 'opus-2.ogg')
for name in names:
    path = root / name
    try:
        data = path.read_bytes()
        if name == 'report.json':
            report = json.loads(data)
            if mode == 'decoder-only':
                valid = valid and report.get('mode') == mode and report.get('probePassed') is True and 'passed' not in report
                cases = report.get('cases', [])
                valid = valid and len(cases) == 1 and cases[0].get('case') == 'aac-decoder-probe'
                valid = valid and cases[0].get('sha256') == 'a917896903bc7944cbb6b7c0eab6743c1fe6f99caefebd27d569ea79677d6bd1'
            else:
                valid = valid and report.get('mode') == mode and report.get('passed') is True and len(report.get('cases', [])) == 11
        else:
            assert (data.startswith(b'OggS') if name.endswith('.ogg') else data[4:8] == b'ftyp')
            if mode == 'decoder-only':
                assert len(data) == 7158 and hashlib.sha256(data).hexdigest() == 'a917896903bc7944cbb6b7c0eab6743c1fe6f99caefebd27d569ea79677d6bd1'
    except (OSError, ValueError, AssertionError):
        path.unlink(missing_ok=True)
        valid = False
raise SystemExit(0 if valid else 1)
VALIDATE
if [[ "$status" != 0 ]]; then
    # The original command status and media artifacts are already persisted.
    python3 tests/collect-recorder-anr.py "$runner_class" || true
fi
exit "$status"

