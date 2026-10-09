#!/usr/bin/env bash
# Host control only: fake Binder/Looper/Shizuku, no Android device or privileged I/O.
set -euo pipefail
cd "$(dirname "$0")/.."
source_dir=${1:-manager/src/main/java/org/androidcontrol/app/files}
classes=$(mktemp -d)
trap 'rm -rf "$classes"' EXIT
sources=("$source_dir/AdbFileClient.kt")
if [[ -f "$source_dir/RetryingServiceBinding.kt" ]]; then
  sources+=("$source_dir/RetryingServiceBinding.kt")
fi
"${KOTLINC:-kotlinc}" "${sources[@]}" tests/adb-file-client-fixture/*.kt -d "$classes"
java "-Dadb.fixture.forbidPing=${AC001_FORBID_PING:-false}" -cp "$classes:${KOTLIN_STDLIB:?Set KOTLIN_STDLIB to your kotlin-stdlib JAR}" org.androidcontrol.app.files.AdbFileClientProbeKt
