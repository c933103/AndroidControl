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
LANGUAGES = ("cs", "es", "ru", "zh-rCN", "zh-rTW")
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
mapping = SOURCE.read_text(encoding="utf-8")
for key in operations:
    assert "R.string." + key in mapping, f"Unused AppOps operation label {key}"
for language in LANGUAGES:
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
assert "labels.operationWithIdentifier" in (
    ROOT / "manager/src/main/java/org/androidcontrol/app/appops/AppOpsActivity.kt"
).read_text(encoding="utf-8"), "Localized operation choice not connected to UI"
fixture = (ROOT / "tests/appops-picker-fixture/pom.xml").read_text(encoding="utf-8")
assert "<include>AppOpsLabels.kt</include>" in fixture, "Lifecycle fixture omits production label helper"
fixture_resources = (ROOT / "tests/appops-picker-fixture/src/test/kotlin/R.kt").read_text(encoding="utf-8")
for key in operations + ["appops_group_other"]:
    assert "const val " + key + " " in fixture_resources, f"Lifecycle fixture lacks {key}"
print(f"AppOps localization: {len(LANGUAGES)} locales, {len(english)} keys, "
      f"{len(operations)} mapped historical operation labels; placeholders OK")
