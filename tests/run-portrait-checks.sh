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

javac -d "$test_classes" \
  manager/src/main/java/org/androidcontrol/app/control/TargetCompatOverrides.java \
  manager/src/main/java/org/androidcontrol/app/control/TargetCompatJournal.java \
  tests/TargetCompatOverridesTest.java \
  tests/compat-overrides-fixture/android/util/AtomicFile.java \
  tests/compat-overrides-fixture/TargetCompatJournalTest.java
java -cp "$test_classes" org.androidcontrol.app.control.TargetCompatOverridesTest
java -cp "$test_classes" org.androidcontrol.app.control.TargetCompatJournalTest
