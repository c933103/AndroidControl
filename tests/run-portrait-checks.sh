#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_classes=$(mktemp -d)
trap 'rm -rf "$test_classes"' EXIT
javac -d "$test_classes" manager/src/main/java/moe/shizuku/manager/control/PortraitActivityGeometry.java tests/PortraitActivityGeometryTest.java
java -cp "$test_classes" moe.shizuku.manager.control.PortraitActivityGeometryTest
javac -d "$test_classes" manager/src/main/java/moe/shizuku/manager/control/PortraitWebLaunch.java tests/PortraitWebLaunchTest.java
java -cp "$test_classes" moe.shizuku.manager.control.PortraitWebLaunchTest
javac -d "$test_classes" manager/src/main/java/moe/shizuku/manager/control/PortraitStateFiles.java tests/PortraitStateFilesTest.java
java -cp "$test_classes" moe.shizuku.manager.control.PortraitStateFilesTest
