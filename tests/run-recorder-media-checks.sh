#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
package=org.androidcontrol.app
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
shopt -s nullglob
apps=(manager/build/outputs/apk/debug/*.apk)
tests=(manager/build/outputs/apk/androidTest/debug/*.apk)
[[ ${#apps[@]} == 1 && ${#tests[@]} == 1 ]]
adb install -r "${apps[0]}"
adb install -r -t "${tests[0]}"
adb shell pm list instrumentation | tee "$results/registered-instrumentation.txt"
grep -Fq "$package.test/org.androidcontrol.app.regression.RecorderMediaInstrumentation" "$results/registered-instrumentation.txt"
status=0
python3 -c 'import subprocess, sys
try:
    result = subprocess.run(sys.argv[1:], timeout=180)
except subprocess.TimeoutExpired:
    code, timed_out = 124, True
else:
    code, timed_out = result.returncode, False
import json
from pathlib import Path
Path("recorder-media-results/media-command.json").write_text(json.dumps(dict(exitCode=code, timedOut=timed_out)) + "\n")
sys.exit(code)
' adb shell am instrument -w "$package.test/org.androidcontrol.app.regression.RecorderMediaInstrumentation" 2>&1 | tee "$results/instrumentation.txt" || status=1
grep -q '^INSTRUMENTATION_RESULT: recorder_media=PASS' "$results/instrumentation.txt" || status=1
if [[ "$status" != 0 ]]; then
    adb logcat -b crash -d -v brief -t 200 | python3 tests/filter-recorder-crash.py > "$results/app-crash.txt" || true
    pid=$(sed -n 's/^INSTRUMENTATION_STATUS: recorder_media_pid=//p' "$results/instrumentation.txt" | head -n 1 | tr -d '\r')
    started_ms=$(sed -n 's/^INSTRUMENTATION_STATUS: recorder_media_started_ms=//p' "$results/instrumentation.txt" | head -n 1 | tr -d '\r')
    if [[ "$pid" =~ ^[0-9]+$ ]]; then
        if [[ "$started_ms" =~ ^[0-9]+$ ]]; then
            python3 tests/recorder-exit-info.py "$pid" "$started_ms" > "$results/app-exit-info.json" || true
        fi
        adb logcat --pid="$pid" -d -v brief -t 300 -s MediaCodec CCodec CCodecConfig Codec2Client > "$results/app-codec.txt" || true
    fi
fi
for name in report.json aac-1.m4a aac-2.m4a opus-1.ogg opus-2.ogg; do
    if ! adb exec-out run-as "$package" cat "cache/recorder-media/$name" > "$results/$name"; then
        rm -f "$results/$name"
        status=1
    fi
    [[ -s "$results/$name" ]] || status=1
done
# adb exec-out can return zero while remote cat prints an error: validate bytes,
# not only command status, so missing artifacts cannot masquerade as audio/JSON.
python3 - <<'VALIDATE' || status=1
import json
from pathlib import Path
root = Path('recorder-media-results')
valid = True
for name in ('report.json', 'aac-1.m4a', 'aac-2.m4a', 'opus-1.ogg', 'opus-2.ogg'):
    path = root / name
    try:
        data = path.read_bytes()
        if name == 'report.json':
            report = json.loads(data)
            valid = valid and report.get('passed') is True and len(report.get('cases', [])) == 11
        else:
            assert (data.startswith(b'OggS') if name.endswith('.ogg') else data[4:8] == b'ftyp')
    except (OSError, ValueError, AssertionError):
        path.unlink(missing_ok=True)
        valid = False
raise SystemExit(0 if valid else 1)
VALIDATE
if [[ "$status" != 0 ]]; then
    # The original command status and media artifacts are already persisted.
    python3 tests/collect-recorder-anr.py || true
fi
exit "$status"
