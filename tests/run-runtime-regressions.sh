#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
build_tools="$ANDROID_HOME/build-tools/36.0.0"
android_jar="$ANDROID_HOME/platforms/android-36/android.jar"
mkdir -p "$test_dir/classes" "$test_dir/dex" runtime-results

javac --release 8 -cp "$android_jar" -d "$test_dir/classes" tests/portrait-fixture/RegressionGame.java
jar --create --file "$test_dir/classes.jar" -C "$test_dir/classes" .
"$build_tools/d8" --min-api 24 --lib "$android_jar" --output "$test_dir/dex" "$test_dir/classes.jar"
"$build_tools/aapt2" link -I "$android_jar" --manifest tests/portrait-fixture/AndroidManifest.xml -o "$test_dir/fixture.apk"
(cd "$test_dir/dex" && zip -q "$test_dir/fixture.apk" classes.dex)
"$build_tools/zipalign" -p 4 "$test_dir/fixture.apk" "$test_dir/aligned.apk"
# The fixture is a different app and needs no relation to the manager's key.
keytool -genkeypair -keystore "$test_dir/fixture.jks" -storepass android -keypass android \
    -alias fixture -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Runtime Test Fixture' >/dev/null 2>&1
"$build_tools/apksigner" sign --ks "$test_dir/fixture.jks" --ks-pass pass:android "$test_dir/aligned.apk"

package=moe.shizuku.privileged.api
runner="$package.test/moe.shizuku.manager.regression.RuntimeRegressionInstrumentation"
run_phase() {
    adb shell am instrument -w -e phase "$1" "$runner" | tee "runtime-results/$1.txt"
    rg -q "regression=PASS $1" "runtime-results/$1.txt"
}

adb install "$RUNNER_TEMP/baseline.apk"
adb install manager/build/outputs/apk/androidTest/debug/*.apk
run_phase seed
# A real package replacement, without clearing app data or Keystore.
adb install -r manager/build/outputs/apk/debug/*.apk
run_phase upgrade
run_phase key-failure

adb install "$test_dir/aligned.apk"
apk_path=$(adb shell pm path "$package" | sed 's/^package://' | tr -d '\r')
adb shell "${apk_path%/*}/lib/x86_64/libshizuku.so --apk=$apk_path"
adb logcat -c
set +e
run_phase display
result=$?
set -e
adb shell dumpsys activity activities > runtime-results/activities.txt
adb shell dumpsys display > runtime-results/displays.txt
adb logcat -d -s AndroidRuntime ShizukuServer AndroidControlService > runtime-results/runtime-log.txt
adb exec-out screencap -p > runtime-results/screen.png
test "$result" = 0
adb shell run-as game.qualiarts.hololive.dreams.jp cat files/touches | tee runtime-results/touches.txt
rg -q touch runtime-results/touches.txt
