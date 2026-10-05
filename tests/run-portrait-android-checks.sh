#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
remote_jar=/data/local/tmp/androidcontrol-portrait-regression.zip
trap 'adb shell rm -f "$remote_jar" >/dev/null 2>&1 || true; rm -rf "$test_dir"' EXIT
device_sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
test "$device_sdk" = 33
mkdir "$test_dir/classes"
javac --release 8 -d "$test_dir/classes" \
  manager/src/main/java/org/androidcontrol/app/control/PortraitActivityGeometry.java \
  tests/PortraitActivityGeometryTest.java tests/AndroidPortraitRegexTest.java \
  manager/src/main/java/org/androidcontrol/app/control/PortraitWebLaunch.java tests/PortraitWebLaunchTest.java
jar --create --file "$test_dir/classes.jar" -C "$test_dir/classes" .
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 33 \
  --lib "$ANDROID_HOME/platforms/android-33/android.jar" \
  --output "$test_dir/portrait-tests.zip" "$test_dir/classes.jar"
adb push "$test_dir/portrait-tests.zip" "$remote_jar"
adb shell CLASSPATH="$remote_jar" app_process / org.androidcontrol.app.control.AndroidPortraitRegexTest
adb shell CLASSPATH="$remote_jar" app_process / org.androidcontrol.app.control.PortraitWebLaunchTest
