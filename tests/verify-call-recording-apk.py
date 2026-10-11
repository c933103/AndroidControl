#!/usr/bin/env python3
"""Verify release-critical recording assets in a fully assembled AndroidControl APK.

The scrcpy server is executed under shell UID at runtime. Fail release if Gradle
omits it, changes its expected bytes, or strips the required GPL/Apache notices.
This intentionally never downloads binaries or modifies the APK.
"""

import hashlib
import pathlib
import sys
import zipfile

EXPECTED_SCRCPY_SHA256 = "84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a"
ASSETS = (
    "assets/scrcpy-server",
    "assets/licenses/ShizuCallRecorder-GPL-3.0.txt",
    "assets/licenses/scrcpy-Apache-2.0.txt",
)


def check(apk: pathlib.Path) -> None:
    if not apk.is_file() or apk.suffix != ".apk":
        raise ValueError(f"Not an APK: {apk}")
    with zipfile.ZipFile(apk) as package:
        names = set(package.namelist())
        missing = [name for name in ASSETS if name not in names]
        if missing:
            raise ValueError(f"{apk.name}: required assets absent: {', '.join(missing)}")
        data = package.read(ASSETS[0])
        digest = hashlib.sha256(data).hexdigest()
        if digest.lower() != EXPECTED_SCRCPY_SHA256:
            raise ValueError(
                f"{apk.name}: scrcpy-server SHA-256 mismatch: {digest}"
            )
        gpl = package.read(ASSETS[1]).decode("utf-8")
        apache = package.read(ASSETS[2]).decode("utf-8")
        if (
            "GNU GENERAL PUBLIC LICENSE" not in gpl
            or "Additional Terms under GNU GPL version 3 Section 7" not in gpl
            or "ShizuCallRecorder" not in gpl
        ):
            raise ValueError(f"{apk.name}: GPL-3 with Section 7 notices incomplete")
        if "Apache License" not in apache or "Version 2.0" not in apache:
            raise ValueError(f"{apk.name}: scrcpy Apache-2.0 licence incomplete")
    print(f"{apk.name}: verified scrcpy v4.0 ({len(data)} bytes) and both licences")


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: verify-call-recording-apk.py FILE.apk [...]", file=sys.stderr)
        return 2
    try:
        for filename in sys.argv[1:]:
            check(pathlib.Path(filename))
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
