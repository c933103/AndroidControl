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
shopt -s nullglob
apps=(manager/build/outputs/apk/debug/*.apk)
tests=(manager/build/outputs/apk/androidTest/debug/*.apk)
[[ ${#apps[@]} == 1 && ${#tests[@]} == 1 ]]
adb install -r "${apps[0]}"
adb install -r -t "${tests[0]}"
status=0
timeout 180 adb shell am instrument -w "$package.test/org.androidcontrol.app.regression.RecorderMediaInstrumentation" | tee "$results/instrumentation.txt" || status=1
grep -q '^INSTRUMENTATION_RESULT: recorder_media=PASS' "$results/instrumentation.txt" || status=1
for name in report.json aac-1.m4a aac-2.m4a opus-1.ogg opus-2.ogg; do
    adb exec-out run-as "$package" cat "cache/recorder-media/$name" > "$results/$name" || status=1
    [[ -s "$results/$name" ]] || status=1
done
exit "$status"
