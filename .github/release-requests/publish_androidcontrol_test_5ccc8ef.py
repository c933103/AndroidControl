"""One release only: publish the reviewed AndroidControl test draft."""
import json
import os
from pathlib import Path
import subprocess

REPO = "c933103/AndroidControl"
BRANCH = "release/publish-androidcontrol-test-5ccc8ef"
SOURCE = "5ccc8ef67373b8a5dfdb351cdcab8c1ce3ca55a8"
RELEASE_ID = 408034497
TAG = "androidcontrol-test-5ccc8ef"
ASSET_NAME = "androidcontrol-v13.6.0.r1237.5ccc8ef6-debug.apk"
ASSET_ID = 625320534
ASSET_SIZE = 13935943
APK_SHA256 = "533a5bfdcdb8a1492818ac00ef17a489aeb1f24f4dcca4945eb5688aea106b51"
CERT = "5f3ab2f36ce4a7856c12b1744b799ff775e6f74f05c0bd2c9d8db824674b7ef1"
NOTES = """Test prerelease from merged master 5ccc8ef67373b8a5dfdb351cdcab8c1ce3ca55a8.

Includes unanswered ADB file-bind recovery, the pinned Gradle distribution checksum, and runtime-test readiness and portrait-routing diagnostics (merged PRs #21, #23 and #24).

This is a debuggable test APK signed with the established permanent AndroidControl test certificate. Exact-head Android 13 and 15 CI passed. Physical-device validation remains unverified.

Package: org.androidcontrol.app. It installs separately from upstream Shizuku; old-package data is not migrated automatically.

APK: androidcontrol-v13.6.0.r1237.5ccc8ef6-debug.apk
APK SHA-256: 533a5bfdcdb8a1492818ac00ef17a489aeb1f24f4dcca4945eb5688aea106b51
Signing certificate SHA-256: 5f3ab2f36ce4a7856c12b1744b799ff775e6f74f05c0bd2c9d8db824674b7ef1

Accepted source: https://github.com/c933103/AndroidControl/pull/24
Exact-head checks: https://github.com/c933103/AndroidControl/actions/runs/37949675618
Signed APK provenance: https://github.com/c933103/AndroidControl/actions/runs/37949675656/job/113884886660
"""
EXPECTED_REQUEST = {
    "repository": REPO, "source_commit": SOURCE, "release_id": RELEASE_ID,
    "tag": TAG, "asset_id": ASSET_ID, "asset_name": ASSET_NAME,
    "asset_size": ASSET_SIZE, "apk_sha256": APK_SHA256,
    "certificate_sha256": CERT, "prerelease": True, "notes": NOTES,
}


def api(suffix, *, payload=None, absent_ok=False):
    command = ["gh", "api", f"repos/{REPO}/{suffix}"]
    if payload is not None:
        command += ["--method", "PATCH", "--input", "-"]
    result = subprocess.run(command, input=None if payload is None else json.dumps(payload),
                            text=True, capture_output=True)
    if result.returncode:
        if absent_ok and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(f"GitHub operation failed ({suffix}): {result.stderr.strip()}")
    return json.loads(result.stdout)


def verify_release(release, *, published):
    assert release["id"] == RELEASE_ID
    assert release["tag_name"] == TAG
    assert release["target_commitish"] == SOURCE
    assert release["name"] == "AndroidControl test 5ccc8ef"
    assert release["prerelease"] is True
    assert release["draft"] is (not published)
    assets = release["assets"]
    assert len(assets) == 1, "Unexpected release assets; refusing publication"
    asset = assets[0]
    assert asset["id"] == ASSET_ID and asset["name"] == ASSET_NAME
    assert asset["size"] == ASSET_SIZE and asset["state"] == "uploaded"
    assert asset["digest"] == "sha256:" + APK_SHA256
    if published:
        assert release["body"] == NOTES, "Already-published release differs from approved notes"
        assert release["published_at"], "Publication timestamp missing"
        tag = api("git/ref/tags/" + TAG)
        assert tag["object"]["type"] == "commit" and tag["object"]["sha"] == SOURCE


def main():
    assert os.environ["GITHUB_REPOSITORY"] == REPO
    assert os.environ["GITHUB_REF"] == "refs/heads/" + BRANCH
    assert os.environ["GITHUB_EVENT_NAME"] == "push"
    request = json.loads(Path(".github/release-requests/androidcontrol-test-5ccc8ef.json").read_text())
    assert request == EXPECTED_REQUEST, "Request is not the one reviewed release"
    activation = os.environ["GITHUB_SHA"]
    commit = api("git/commits/" + activation)
    assert [p["sha"] for p in commit["parents"]] == [SOURCE], "Activation must descend directly from approved source"
    assert commit["message"] == "Publish approved AndroidControl test 5ccc8ef"
    release = api("releases/" + str(RELEASE_ID))
    if not release["draft"]:
        verify_release(release, published=True)
        print("Verified existing publication; no changes made: " + release["html_url"])
        return
    assert api("git/ref/heads/master")["object"]["sha"] == SOURCE, "Master moved; re-review required"
    verify_release(release, published=False)
    tag = api("git/ref/tags/" + TAG, absent_ok=True)
    if tag is not None:
        assert tag["object"]["type"] == "commit" and tag["object"]["sha"] == SOURCE
    updated = api("releases/" + str(RELEASE_ID), payload={
        "body": NOTES, "draft": False, "prerelease": True, "make_latest": "false",
    })
    verify_release(updated, published=True)
    verify_release(api("releases/" + str(RELEASE_ID)), published=True)
    print("Verified published prerelease: " + updated["html_url"])


if __name__ == "__main__":
    main()
