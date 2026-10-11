#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 AndroidControl contributors
# SPDX-License-Identifier: Apache-2.0
"""Verify file-level source licences and the component inventory.

Protects against accidental relicensing of GPL-covered upstream source, licence
asset drift, and omission of required notices. Does not confer licence rights.
No external network calls and no modification of the working tree.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
PORT = [
    "manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ScrcpyAudioCodec.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ScrcpyAudioSource.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ScrcpyAudioMuxer.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ScrcpyClient.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ScrcpyConfig.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/ServerExtractor.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/services/shell/ShellAudioPipeline.kt",
    "manager/src/main/res/values/call_recorder_upstream_strings.xml",
]
FIRST_PARTY = [
    "manager/src/main/java/org/androidcontrol/app/recording/CallRecordingActivity.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/CallRecordingBridge.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/CallRecordingPolicy.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/CallRecordingPreferences.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/CallRecordingService.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/PhoneStateCallReceiver.kt",
    "manager/src/main/java/org/androidcontrol/app/recording/utils/AppLogger.kt",
    "manager/src/main/java/org/androidcontrol/app/home/CallRecordingViewHolder.kt",
    "manager/src/main/java/org/androidcontrol/app/licenses/ComponentLicensesActivity.kt",
    "manager/src/test/java/org/androidcontrol/app/recording/CallRecordingPolicyTest.kt",
    "manager/src/main/res/layout/home_call_recording.xml",
    "manager/src/main/res/layout/call_recording_activity.xml",
    "manager/src/main/res/layout/component_licenses_activity.xml",
    "manager/src/main/res/values/call_recording_strings.xml",
    "manager/src/main/res/values/component_licenses_strings.xml",
]
MIT = ["manager/src/main/java/org/androidcontrol/app/appops/AppOpsBackup.kt"]
CUSTOM = "LicenseRef-ShizuCallRecorder-GPL3-Section7"


def content(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def verify() -> None:
    for expected, paths in (
        (CUSTOM, PORT),
        ("Apache-2.0", FIRST_PARTY),
        ("MIT", MIT),
    ):
        for path in paths:
            header = "\n".join(content(path).splitlines()[:22])
            if f"SPDX-License-Identifier: {expected}" not in header:
                raise ValueError(f"{path}: missing/incorrect SPDX licence {expected}")
            if "SPDX-FileCopyrightText:" not in header:
                raise ValueError(f"{path}: missing source copyright notice")
    for path in PORT[:7]:
        if "kitsumed (Med)" not in content(path).split("package ")[0]:
            raise ValueError(f"{path}: lost upstream copyright attribution")

    expected_copies = {
        "LICENSES/Apache-2.0.txt": "LICENSE",
        "LICENSES/MIT.txt": "licenses/AppOpsX-MIT.txt",
        f"LICENSES/{CUSTOM}.txt": "licenses/ShizuCallRecorder-GPL-3.0.txt",
        "manager/src/main/assets/licenses/Shizuku-Apache-2.0.txt": "LICENSE",
        "manager/src/main/assets/licenses/AppOpsX-MIT.txt": "licenses/AppOpsX-MIT.txt",
        "manager/src/main/assets/licenses/ShizuCallRecorder-GPL-3.0.txt": "licenses/ShizuCallRecorder-GPL-3.0.txt",
        "manager/src/main/assets/licenses/scrcpy-Apache-2.0.txt": "licenses/scrcpy-Apache-2.0.txt",
    }
    for copied, original in expected_copies.items():
        if (ROOT / copied).read_bytes() != (ROOT / original).read_bytes():
            raise ValueError(f"{copied}: licence text differs from {original}")

    gpl = content(f"LICENSES/{CUSTOM}.txt")
    for clause in (
        "GNU GENERAL PUBLIC LICENSE",
        "Additional Terms under GNU GPL version 3 Section 7",
        "Brand & Trademark Protection",
        "App Store & Publicity Restrictions",
        "Mandatory Distinction for Modified Versions",
        "Attribution & Linking",
    ):
        if clause not in gpl:
            raise ValueError(f"Custom GPL document is missing: {clause}")
    if "Licensed under the Apache License" not in content("LICENSE"):
        # Apache's full licence contains the phrase in its appendix.
        raise ValueError("Root Shizuku Apache-2.0 licence was unexpectedly replaced")
    if "combined AndroidControl APK" not in content("LICENSING.md"):
        raise ValueError("Missing combined distribution disclosure")

    screen = content("manager/src/main/java/org/androidcontrol/app/licenses/ComponentLicensesActivity.kt")
    for asset in expected_copies:
        if asset.startswith("manager/src/main/assets/licenses/") and asset.split("manager/src/main/assets/")[1] not in screen:
            raise ValueError(f"Licence viewer does not provide {asset}")
    manifest = content("manager/src/main/AndroidManifest.xml")
    about = content("manager/src/main/res/layout/about_dialog.xml")
    home = content("manager/src/main/java/org/androidcontrol/app/home/HomeActivity.kt")
    if "ComponentLicensesActivity" not in manifest or "component_licences_button" not in about or "componentLicencesButton" not in home:
        raise ValueError("On-device licence viewer is not reachable from About")


if __name__ == "__main__":
    try:
        verify()
    except (OSError, ValueError) as exc:
        print(f"Component licence check failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
    print("Component licence check passed: Apache core/glue, MIT AppOpsX adapter, GPLv3+§7 recorder and scrcpy notices")
