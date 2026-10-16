#!/usr/bin/env python3
"""Publish only the accepted fd94daf draft, without rebuilding its APK."""

import json
import os
import re
import urllib.error
import urllib.request

REPOSITORY = "c933103/AndroidControl"
TARGET_SHA = "fd94dafa153245163f404c330a6b902deb76ea53"
REVIEWED_SHA = "36622879ec05e43e0d674d0b5e8c2512fea4ae20"
RELEASE_ID = 414471568
TAG = "androidcontrol-test-fd94daf"
RELEASE_PATH = f"releases/{RELEASE_ID}"
TAG_PATH = f"git/ref/tags/{TAG}"
REVIEW_COMMENT_ID = 6206478845
ASSET = {
    "id": 645876040,
    "name": "androidcontrol-v13.6.0.r1251.fd94dafa-debug.apk",
    "size": 13991379,
    "digest": "sha256:5add6cc78bf7e2d9ba85abd7bdb1658c65e1b54403dea80c815d2968220a71f7",
    "state": "uploaded",
}
PUBLISHED_BODY = (
    "Private test build from merged master. Debug APK signed with the verified "
    "permanent test key. Portrait container and restoration changes require "
    "device validation."
)
DRAFT_BODY = PUBLISHED_BODY + " Not published."
RUNS = {
    38840328632: ".github/workflows/android-ci.yml",
    38840329526: ".github/workflows/app.yml",
    38840328657: ".github/workflows/draft-test-release.yml",
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("Unexpected API redirect; refusing to forward credentials")


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path, *, authenticated=False, payload=None, missing_ok=False):
        # All endpoints are fixed repository API paths; never follow server-provided URLs.
        require(not path.startswith("/") and ":" not in path and ".." not in path,
                "Invalid API path")
        headers = {
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "AndroidControl-accepted-release",
        }
        if authenticated:
            require(bool(self.token), "Missing workflow token")
            headers["Authorization"] = f"Bearer {self.token}"
        if payload is not None:
            require(authenticated and path == RELEASE_PATH, "Unexpected write target")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPOSITORY}/{path}",
            data=None if payload is None else json.dumps(payload).encode("utf-8"),
            headers=headers,
            method="GET" if payload is None else "PATCH",
        )
        try:
            with self.opener.open(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            # Do not print request headers or token values, and never retry a write blindly.
            raise RuntimeError(f"GitHub {request.method} {path}: HTTP {error.code}") from None


def validate_release(release):
    expected = {
        "id": RELEASE_ID,
        "tag_name": TAG,
        "target_commitish": TARGET_SHA,
        "name": "AndroidControl test fd94daf",
    }
    for field, value in expected.items():
        require(release.get(field) == value, f"Release {field} mismatch")
    require(release.get("prerelease") is True, "Release is not a prerelease")
    require(type(release.get("draft")) is bool, "Missing draft state")
    body = DRAFT_BODY if release["draft"] else PUBLISHED_BODY
    require(release.get("body") == body, "Release notes changed; review before publishing")
    assets = release.get("assets", [])
    require(len(assets) == 1, "Expected exactly the accepted APK asset")
    for field, value in ASSET.items():
        require(assets[0].get(field) == value, f"APK {field} mismatch")
    if not release["draft"]:
        require(bool(release.get("published_at")), "Published release has no publication time")


def validate_tag(tag, *, required):
    if tag is None:
        require(not required, "Published tag is missing")
        return
    require(tag.get("ref") == f"refs/tags/{TAG}", "Tag name mismatch")
    require(tag.get("object", {}).get("type") == "commit", "Expected a direct commit tag")
    require(tag["object"].get("sha") == TARGET_SHA, "Tag does not point to the accepted commit")


def validate_acceptance(api):
    # These are public read-only endpoints: no Actions/PR token permission is needed.
    pr = api.request("pulls/29")
    require(pr.get("merged") is True and pr.get("merge_commit_sha") == TARGET_SHA,
            "PR29 is not merged at the accepted commit")
    require(pr.get("head", {}).get("sha") == REVIEWED_SHA, "PR29 reviewed head changed")
    require(pr.get("base", {}).get("ref") == "master", "PR29 base mismatch")
    comment = api.request(f"issues/comments/{REVIEW_COMMENT_ID}")
    require(comment.get("id") == REVIEW_COMMENT_ID, "Review comment mismatch")
    require(comment.get("user", {}).get("login") == "chatgpt-codex-connector[bot]"
            and comment["user"].get("type") == "Bot", "Unexpected review author")
    match = re.search(r"<!-- codex-review-state:v1 (.+?) -->", comment.get("body", ""))
    require(match is not None, "Missing machine-readable Codex review")
    review = json.loads(match.group(1))
    for kind in ("code_review", "security_review"):
        state = review.get(kind, {})
        require(state.get("commit_sha") == REVIEWED_SHA and state.get("status") == "completed"
                and state.get("has_any_findings") is False
                and state.get("has_unresolved_findings") is False,
                f"Accepted {kind} is missing, stale or has findings")
    for run_id, path in RUNS.items():
        run = api.request(f"actions/runs/{run_id}")
        require(run.get("id") == run_id and run.get("path") == path
                and run.get("head_sha") == TARGET_SHA and run.get("head_branch") == "master"
                and run.get("event") == "push" and run.get("status") == "completed"
                and run.get("conclusion") == "success"
                and run.get("repository", {}).get("full_name") == REPOSITORY,
                f"Accepted workflow run {run_id} no longer passes")


def publish(api):
    release = api.request(RELEASE_PATH, authenticated=True)
    validate_release(release)
    validate_tag(api.request(TAG_PATH, authenticated=True, missing_ok=True),
                 required=not release["draft"])
    if not release["draft"]:
        return "Already published; accepted release and tag verified."
    validate_acceptance(api)
    # Recheck immediately before the only mutation; never clobber a changed draft.
    release = api.request(RELEASE_PATH, authenticated=True)
    validate_release(release)
    validate_tag(api.request(TAG_PATH, authenticated=True, missing_ok=True),
                 required=not release["draft"])
    if release["draft"]:
        result = api.request(RELEASE_PATH, authenticated=True, payload={
            "draft": False, "prerelease": True, "make_latest": "false", "body": PUBLISHED_BODY,
        })
        validate_release(result)
        require(result["draft"] is False, "GitHub did not publish the release")
    result = api.request(RELEASE_PATH, authenticated=True)
    validate_release(result)
    require(result["draft"] is False, "Release is still a draft")
    validate_tag(api.request(TAG_PATH, authenticated=True), required=True)
    return f"Published and verified https://github.com/{REPOSITORY}/releases/tag/{TAG}"


def main():
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "Unexpected repository")
    require(os.environ.get("GITHUB_EVENT_NAME") == "push", "Only reviewed master pushes publish")
    require(os.environ.get("GITHUB_REF") == "refs/heads/master", "Only master publishes")
    print(publish(GitHub(os.environ.get("GITHUB_TOKEN", ""))))


if __name__ == "__main__":
    main()
