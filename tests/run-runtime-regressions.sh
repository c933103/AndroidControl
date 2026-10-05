#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
build_tools="$ANDROID_HOME/build-tools/36.0.0"
android_jar="$ANDROID_HOME/platforms/android-36/android.jar"
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$sdk" = 33 || "$sdk" = 35 ]]
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

# A different installed package exercises selection rather than the old hard-coded target.
sed -e 's/package="org.androidcontrol.regression.target"/package="org.androidcontrol.regression.other"/' \
    -e 's/android:name=".RegressionGame"/android:name="org.androidcontrol.regression.target.RegressionGame"/' \
    tests/portrait-fixture/AndroidManifest.xml > "$test_dir/AndroidManifest.xml"
"$build_tools/aapt2" link -I "$android_jar" --manifest "$test_dir/AndroidManifest.xml" -o "$test_dir/other.apk"
(cd "$test_dir/dex" && zip -q "$test_dir/other.apk" classes.dex)
"$build_tools/zipalign" -p 4 "$test_dir/other.apk" "$test_dir/other-aligned.apk"
"$build_tools/apksigner" sign --ks "$test_dir/fixture.jks" --ks-pass pass:android "$test_dir/other-aligned.apk"

mkdir -p "$test_dir/checkout-classes" "$test_dir/checkout-dex"
javac --release 8 -cp "$android_jar" -d "$test_dir/checkout-classes" tests/checkout-fixture/CheckoutActivity.java
jar --create --file "$test_dir/checkout.jar" -C "$test_dir/checkout-classes" .
"$build_tools/d8" --min-api 24 --lib "$android_jar" --output "$test_dir/checkout-dex" "$test_dir/checkout.jar"
"$build_tools/aapt2" link -I "$android_jar" --manifest tests/checkout-fixture/AndroidManifest.xml -o "$test_dir/checkout.apk"
(cd "$test_dir/checkout-dex" && zip -q "$test_dir/checkout.apk" classes.dex)
"$build_tools/zipalign" -p 4 "$test_dir/checkout.apk" "$test_dir/checkout-aligned.apk"
"$build_tools/apksigner" sign --ks "$test_dir/fixture.jks" --ks-pass pass:android "$test_dir/checkout-aligned.apk"

package=org.androidcontrol.app
mkdir -p "$test_dir/browser-classes" "$test_dir/browser-dex"
javac --release 8 -cp "$android_jar" -d "$test_dir/browser-classes" tests/browser-fixture/BrowserActivity.java
jar --create --file "$test_dir/browser.jar" -C "$test_dir/browser-classes" .
"$build_tools/d8" --min-api 24 --lib "$android_jar" --output "$test_dir/browser-dex" "$test_dir/browser.jar"
"$build_tools/aapt2" link -I "$android_jar" --manifest tests/browser-fixture/AndroidManifest.xml -o "$test_dir/browser.apk"
(cd "$test_dir/browser-dex" && zip -q "$test_dir/browser.apk" classes.dex)
"$build_tools/zipalign" -p 4 "$test_dir/browser.apk" "$test_dir/browser-aligned.apk"
"$build_tools/apksigner" sign --ks "$test_dir/fixture.jks" --ks-pass pass:android "$test_dir/browser-aligned.apk"
runner="$package.test/org.androidcontrol.app.regression.RuntimeRegressionInstrumentation"
run_phase() {
    timeout 90s adb shell am instrument -w -e phase "$1" "$runner" | tee "runtime-results/$1.txt"
    if ! grep -q "regression=PASS $1" "runtime-results/$1.txt"; then
        adb shell run-as "$package" cat cache/regression-failure-windows.txt > "runtime-results/$1-before-finish-windows.txt" || true
        adb shell run-as "$package" cat cache/regression-failure-input.txt > "runtime-results/$1-before-finish-input.txt" || true
        adb shell run-as "$package" cat cache/regression-failure-activities.txt > "runtime-results/$1-before-finish-activities.txt" || true
        adb shell run-as "$package" cat cache/regression-failure-surfaces.txt > "runtime-results/$1-before-finish-surfaces.txt" || true
        adb exec-out run-as "$package" cat cache/regression-failure-screen.png > "runtime-results/$1-before-finish-screen.png" || true
        adb shell dumpsys activity activities > "runtime-results/$1-activities.txt"
        adb shell dumpsys window > "runtime-results/$1-windows.txt"
        adb logcat -d > "runtime-results/$1-log.txt"
        adb exec-out screencap -p > "runtime-results/$1-screen.png" || true
        return 1
    fi
}

result=0

adb install "$RUNNER_TEMP/baseline.apk"
baseline_package=$("$build_tools/aapt2" dump badging "$RUNNER_TEMP/baseline.apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
if [[ "$baseline_package" != "$package" ]]; then
    # A package rename must retain the old installation and its private data.
    adb shell run-as "$baseline_package" sh -c 'mkdir -p files'
    adb shell run-as "$baseline_package" sh -c 'echo retained > files/rebrand-regression-marker'
    adb install manager/build/outputs/apk/debug/*.apk
    adb shell run-as "$baseline_package" cat files/rebrand-regression-marker | grep -q retained
fi
adb install manager/build/outputs/apk/androidTest/debug/*.apk
run_phase seed || result=1
run_phase reopen || result=1
# A real package replacement, without clearing app data or Keystore.
adb install -r manager/build/outputs/apk/debug/*.apk
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
# Package replacement broadcasts can still be starting/killing a process after
# install returns. Wait before instrumentation takes ownership of that same UID.
timeout 60s adb shell am wait-for-broadcast-idle
run_phase upgrade || result=1
run_phase key-failure || result=1
run_phase fresh || result=1
run_phase fresh-reopen || result=1
run_phase key-recovery || result=1

adb install "$test_dir/aligned.apk"
adb install "$test_dir/other-aligned.apk"
adb install "$test_dir/checkout-aligned.apk"
adb install "$test_dir/browser-aligned.apk"
apk_path=$(adb shell pm path "$package" | sed 's/^package://' | tr -d '\r')
adb shell "${apk_path%/*}/lib/x86_64/libshizuku.so --apk=$apk_path"
adb logcat -c
run_phase display || result=1
adb shell dumpsys activity activities > runtime-results/activities.txt
adb shell dumpsys display > runtime-results/displays.txt
adb logcat -d -s AndroidRuntime ShizukuServer AndroidControlService > runtime-results/runtime-log.txt
adb exec-out screencap -p > runtime-results/screen.png
adb shell run-as org.androidcontrol.regression.target cat files/touches | tee runtime-results/touches.txt || result=1
grep -q touch runtime-results/touches.txt || result=1
run_phase select-target || result=1
run_phase separate-controls || result=1
run_phase appops || result=1
run_phase appops-user || result=1
run_phase web-links || result=1
run_phase external-dialog || result=1
run_phase interrupted-handoff || result=1
run_phase lock-unlock || result=1
adb shell dumpsys activity activities > runtime-results/final-activities.txt
adb shell dumpsys window > runtime-results/final-windows.txt
adb shell dumpsys display > runtime-results/final-displays.txt
adb exec-out screencap -p > runtime-results/final-screen.png
adb logcat -d -s AndroidRuntime ShizukuServer AndroidControlService > runtime-results/final-log.txt
# The trusted display also needs a valid package attribution when the parent
# toolbox is started with root. Exercise that path on these debuggable emulators.
run_phase shutdown-control || result=1
adb root
timeout 30s adb wait-for-device
root_uid=""
for attempt in {1..30}; do
    root_uid=$(adb shell id -u | tr -d '\r') || true
    [[ "$root_uid" = 0 ]] && break
    sleep 0.2
done
test "$root_uid" = 0
# Restarting adbd can already have terminated the previous shell server.
server_pid=$(adb shell pidof androidcontrol_server | tr -d '\r') || true
if [[ -n "$server_pid" ]]; then adb shell kill "$server_pid"; fi
adb shell am force-stop "$package"
adb shell "${apk_path%/*}/lib/x86_64/libshizuku.so --apk=$apk_path"
run_phase root-display || result=1
exit "$result"
