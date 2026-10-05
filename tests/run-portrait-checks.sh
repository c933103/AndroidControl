#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_classes=$(mktemp -d)
trap 'rm -rf "$test_classes"' EXIT
javac -d "$test_classes" manager/src/main/java/org/androidcontrol/app/control/PortraitActivityGeometry.java tests/PortraitActivityGeometryTest.java
java -cp "$test_classes" org.androidcontrol.app.control.PortraitActivityGeometryTest
javac -d "$test_classes" manager/src/main/java/org/androidcontrol/app/control/PortraitWebLaunch.java tests/PortraitWebLaunchTest.java
java -cp "$test_classes" org.androidcontrol.app.control.PortraitWebLaunchTest
javac -d "$test_classes" manager/src/main/java/org/androidcontrol/app/control/PortraitStateFiles.java tests/PortraitStateFilesTest.java
java -cp "$test_classes" org.androidcontrol.app.control.PortraitStateFilesTest
