#!/usr/bin/env python3
"""Check AppOps translations, placeholders, fallback and mapped operation resources.

Offline, standard-library-only gate. The Android resource compiler and installed tests
are separate; this does not assert physical-device or translation quality verification.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "manager/src/main/res"
SOURCE = ROOT / "manager/src/main/java/org/androidcontrol/app/appops/AppOpsLabels.kt"
FULL_LANGUAGES = ("cs", "es", "ru", "zh-rCN", "zh-rTW")
PARTIAL_LANGUAGES = (
    "ar", "de", "el", "fr", "id", "it", "ja", "ko", "nl",
    "pl", "pt-rBR", "ro", "sv", "tr", "uk", "vi",
)
COMMON_TERMS = {
    "appops_allow", "appops_ignore", "appops_deny", "appops_default",
    "appops_foreground", "appops_user", "appops_save",
    "appops_group_other",
    "appops_group_activity_recognition", "appops_group_sensors",
    "appops_group_phone", "appops_group_sms", "appops_group_contacts",
    "appops_group_camera", "appops_group_location", "appops_group_calendar",
    "appops_group_microphone", "appops_group_storage",
    "appops_group_clipboard", "appops_group_device_identifiers",
}
PH = re.compile(r"(?<!%)%(?:[1-9][0-9]*\$)?[ds]")


def strings(folder):
    path = RES / folder / "appops.xml"
    root = ET.parse(path).getroot()
    values = {}
    for item in root:
        if item.tag != "string":
            raise AssertionError(f"{path}: unexpected resource: {item.tag}")
        key = item.attrib["name"]
        if key in values:
            raise AssertionError(f"{path}: duplicate key {key}")
        values[key] = "".join(item.itertext())
        if not values[key].strip():
            raise AssertionError(f"{path}: empty string {key}")
    return values


english = strings("values")
operations = [key for key in english if key.startswith("appops_op_")]
assert len(operations) == 21, f"Expected 21 historical AppOpsX labels, got {len(operations)}"
groups = [key for key in english if key.startswith("appops_group_")]
assert len(groups) == 13, f"Expected 13 group labels, got {len(groups)}"
mapping = SOURCE.read_text(encoding="utf-8")
for key in operations:
    assert "R.string." + key in mapping, f"Unused AppOps operation label {key}"
for key in groups:
    assert "R.string." + key in mapping, f"Unmapped permission group {key}"
for language in FULL_LANGUAGES:
    translated = strings("values-" + language)
    assert set(translated) == set(english) - {"appops_credits"}, (
        language,
        "missing", sorted(set(english) - set(translated) - {"appops_credits"}),
        "unexpected", sorted(set(translated) - set(english)),
    )
    for key, value in translated.items():
        assert sorted(PH.findall(value)) == sorted(PH.findall(english[key])), (
            language, key, "format placeholder mismatch", value
        )
present_locales = {
    path.parent.name.removeprefix("values-")
    for path in RES.glob("values-*/appops.xml")
}
expected_locales = set(FULL_LANGUAGES) | set(PARTIAL_LANGUAGES)
assert present_locales == expected_locales, (
    "Unexpected or missing AppOps locales",
    sorted(expected_locales - present_locales),
    sorted(present_locales - expected_locales),
)
for language in PARTIAL_LANGUAGES:
    translated = strings("values-" + language)
    assert set(translated) <= COMMON_TERMS, (
        language, "Unexpected scope for partial terminology", sorted(set(translated) - COMMON_TERMS),
    )
    assert translated, f"Empty partial AppOps locale {language}"
    for key, value in translated.items():
        assert sorted(PH.findall(value)) == sorted(PH.findall(english[key])), (
            language, key, "format placeholder mismatch", value
        )
# Upstream Rikka Apps App Ops erroneously reverses Ignore and Deny in Korean.
# A silent Ignore must never be shown as an explicit Deny in the UI.
korean = strings("values-ko")
assert korean["appops_ignore"] == "무시", "Korean Ignore must mean silent ignore"
assert korean["appops_deny"] == "거부", "Korean Deny must mean rejection"
backend = (ROOT / "manager/src/main/java/org/androidcontrol/app/appops/AppOpsService.kt").read_text(encoding="utf-8")
assert '"Other operations"' in backend, "AppOps backend permissionless-group sentinel changed"
assert '"Other", "Other operations" -> R.string.appops_group_other' in mapping, (
    "Permissionless operations must use localized Other group"
)
assert "labels.operationWithIdentifier" in (
    ROOT / "manager/src/main/java/org/androidcontrol/app/appops/AppOpsActivity.kt"
).read_text(encoding="utf-8"), "Localized operation choice not connected to UI"
fixture = (ROOT / "tests/appops-picker-fixture/pom.xml").read_text(encoding="utf-8")
assert "<include>AppOpsLabels.kt</include>" in fixture, "Lifecycle fixture omits production label helper"
fixture_resources = (ROOT / "tests/appops-picker-fixture/src/test/kotlin/R.kt").read_text(encoding="utf-8")
for key in operations + groups:
    assert "const val " + key + " " in fixture_resources, f"Lifecycle fixture lacks {key}"
print(f"AppOps localization: {len(FULL_LANGUAGES)} complete locales; "
      f"{len(PARTIAL_LANGUAGES)} additional upstream terminology locales; "
      f"{len(english)} default keys; "
      f"{len(operations)} historical operation names; "
      f"{len(groups)} group names; placeholders OK")
