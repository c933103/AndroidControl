"""No network, credentials, release writes or APK builds are used by these tests."""

import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error

SPEC = importlib.util.spec_from_file_location(
    "publisher", Path(__file__).resolve().parents[1] / "tools/publish_accepted_test_release.py")
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)


def accepted_release(draft=True):
    return {
        "id": p.RELEASE_ID, "tag_name": p.TAG, "target_commitish": p.TARGET_SHA,
        "name": "AndroidControl test fd94daf", "draft": draft, "prerelease": True,
        "body": p.DRAFT_BODY if draft else p.PUBLISHED_BODY,
        "assets": [copy.deepcopy(p.ASSET)],
        "published_at": None if draft else "2026-10-16T04:00:00Z",
    }


def accepted_tag():
    return {"ref": f"refs/tags/{p.TAG}", "object": {"type": "commit", "sha": p.TARGET_SHA}}


class FakeGitHub:
    def __init__(self, draft=True):
        state = {"commit_sha": p.REVIEWED_SHA, "status": "completed",
                 "has_any_findings": False, "has_unresolved_findings": False}
        self.review = {kind: copy.deepcopy(state) for kind in ("code_review", "security_review")}
        self.data = {
            p.RELEASE_PATH: accepted_release(draft),
            p.TAG_PATH: None if draft else accepted_tag(),
            "pulls/29": {"merged": True, "merge_commit_sha": p.TARGET_SHA,
                         "head": {"sha": p.REVIEWED_SHA}, "base": {"ref": "master"}},
            f"issues/comments/{p.REVIEW_COMMENT_ID}": {
                "id": p.REVIEW_COMMENT_ID,
                "user": {"login": "chatgpt-codex-connector[bot]", "type": "Bot"},
            },
        }
        self.update_review()
        for run_id, path in p.RUNS.items():
            self.data[f"actions/runs/{run_id}"] = {
                "id": run_id, "path": path, "head_sha": p.TARGET_SHA,
                "head_branch": "master", "event": "push", "status": "completed",
                "conclusion": "success", "repository": {"full_name": p.REPOSITORY},
            }
        self.calls = []
        self.writes = []
        self.before_write_recheck = None
        self.release_reads = 0
        self.write_error = False
        self.bad_published_tag = False

    def update_review(self):
        self.data[f"issues/comments/{p.REVIEW_COMMENT_ID}"]["body"] = (
            "<!-- codex-review-state:v1 " + json.dumps(self.review) + " -->")

    def request(self, path, *, authenticated=False, payload=None, missing_ok=False):
        self.calls.append((path, authenticated, payload))
        if payload is not None:
            self.writes.append((path, copy.deepcopy(payload)))
            if self.write_error:
                raise RuntimeError("Uncertain write response")
            self.data[path].update(payload)
            self.data[path]["published_at"] = "2026-10-16T04:00:00Z"
            self.data[p.TAG_PATH] = accepted_tag()
            if self.bad_published_tag:
                self.data[p.TAG_PATH]["object"]["sha"] = "0" * 40
        elif path == p.RELEASE_PATH:
            self.release_reads += 1
            if self.release_reads == 2 and self.before_write_recheck:
                self.before_write_recheck(self)
        return copy.deepcopy(self.data[path])


class PublicationTests(unittest.TestCase):
    def assert_blocked(self, api):
        with self.assertRaises((RuntimeError, ValueError)):
            p.publish(api)
        self.assertEqual([], api.writes)

    def test_publishes_only_pinned_draft_and_preserves_caveat(self):
        api = FakeGitHub()
        self.assertIn("Published and verified", p.publish(api))
        self.assertEqual([(p.RELEASE_PATH, {
            "draft": False, "prerelease": True, "make_latest": "false", "body": p.PUBLISHED_BODY,
        })], api.writes)
        self.assertIn("require device validation", api.data[p.RELEASE_PATH]["body"])
        self.assertEqual([p.ASSET], api.data[p.RELEASE_PATH]["assets"])
        self.assertEqual(p.TARGET_SHA, api.data[p.RELEASE_PATH]["target_commitish"])
        # Public evidence reads do not receive the workflow credential.
        for path, authenticated, _ in api.calls:
            self.assertEqual(path in (p.RELEASE_PATH, p.TAG_PATH), authenticated)

    def test_second_run_is_a_verified_noop(self):
        api = FakeGitHub()
        p.publish(api)
        self.assertIn("Already published", p.publish(api))
        self.assertEqual(1, len(api.writes))

    def test_already_published_requires_intact_tag_and_notes(self):
        for mutation in ("missing_tag", "wrong_tag", "changed_notes", "missing_time"):
            with self.subTest(mutation=mutation):
                api = FakeGitHub(draft=False)
                if mutation == "missing_tag":
                    api.data[p.TAG_PATH] = None
                elif mutation == "wrong_tag":
                    api.data[p.TAG_PATH]["object"]["sha"] = "0" * 40
                elif mutation == "changed_notes":
                    api.data[p.RELEASE_PATH]["body"] = p.DRAFT_BODY
                else:
                    api.data[p.RELEASE_PATH]["published_at"] = None
                self.assert_blocked(api)

    def test_every_pinned_release_field_fails_closed(self):
        for field in ("id", "tag_name", "target_commitish", "name", "body", "draft", "prerelease"):
            with self.subTest(field=field):
                api = FakeGitHub()
                api.data[p.RELEASE_PATH][field] = "unexpected"
                self.assert_blocked(api)

    def test_asset_replacement_digest_size_and_extra_assets_fail_closed(self):
        for field in p.ASSET:
            with self.subTest(field=field):
                api = FakeGitHub()
                api.data[p.RELEASE_PATH]["assets"][0][field] = "unexpected"
                self.assert_blocked(api)
        for count in (0, 2):
            api = FakeGitHub()
            api.data[p.RELEASE_PATH]["assets"] = [copy.deepcopy(p.ASSET)] * count
            self.assert_blocked(api)

    def test_existing_correct_tag_is_allowed_but_wrong_or_annotated_tag_is_blocked(self):
        api = FakeGitHub()
        api.data[p.TAG_PATH] = accepted_tag()
        p.publish(api)
        for field, value in (("type", "tag"), ("sha", "0" * 40)):
            api = FakeGitHub()
            api.data[p.TAG_PATH] = accepted_tag()
            api.data[p.TAG_PATH]["object"][field] = value
            self.assert_blocked(api)

    def test_pr_merge_and_reviewed_head_must_match(self):
        for field, value in (("merged", False), ("merge_commit_sha", "0" * 40),
                             ("head", {"sha": "0" * 40}), ("base", {"ref": "other"})):
            api = FakeGitHub()
            api.data["pulls/29"][field] = value
            self.assert_blocked(api)

    def test_both_exact_codex_reviews_must_be_clean_and_complete(self):
        for kind in ("code_review", "security_review"):
            for field, value in (("commit_sha", "0" * 40), ("status", "in_progress"),
                                 ("has_any_findings", True), ("has_unresolved_findings", True)):
                with self.subTest(kind=kind, field=field):
                    api = FakeGitHub()
                    api.review[kind][field] = value
                    api.update_review()
                    self.assert_blocked(api)

    def test_missing_malformed_or_wrong_author_review_blocks(self):
        for field, value in (("body", ""), ("body", "<!-- codex-review-state:v1 broken -->"),
                             ("user", {"login": "other", "type": "Bot"}),
                             ("user", {"login": "chatgpt-codex-connector[bot]", "type": "User"}),
                             ("id", 1)):
            api = FakeGitHub()
            api.data[f"issues/comments/{p.REVIEW_COMMENT_ID}"][field] = value
            self.assert_blocked(api)

    def test_all_accepted_runs_must_match_and_succeed(self):
        for run_id in p.RUNS:
            for field, value in (("id", 1), ("path", "other.yml"), ("head_sha", "0" * 40),
                                 ("head_branch", "other"), ("event", "pull_request"),
                                 ("status", "in_progress"), ("conclusion", "failure"),
                                 ("repository", {"full_name": "other/repo"})):
                with self.subTest(run=run_id, field=field):
                    api = FakeGitHub()
                    api.data[f"actions/runs/{run_id}"][field] = value
                    self.assert_blocked(api)

    def test_changed_draft_during_evidence_reads_is_not_overwritten(self):
        api = FakeGitHub()
        api.before_write_recheck = lambda a: a.data[p.RELEASE_PATH].update(body="Edited by owner")
        self.assert_blocked(api)

    def test_concurrent_matching_publication_is_not_written_again(self):
        api = FakeGitHub()
        def publish_elsewhere(a):
            a.data[p.RELEASE_PATH] = accepted_release(False)
            a.data[p.TAG_PATH] = accepted_tag()
        api.before_write_recheck = publish_elsewhere
        p.publish(api)
        self.assertEqual([], api.writes)

    def test_uncertain_write_is_not_retried(self):
        api = FakeGitHub()
        api.write_error = True
        with self.assertRaisesRegex(RuntimeError, "Uncertain"):
            p.publish(api)
        self.assertEqual(1, len(api.writes))

    def test_wrong_postpublication_tag_is_reported_without_further_mutation(self):
        api = FakeGitHub()
        api.bad_published_tag = True
        with self.assertRaisesRegex(RuntimeError, "Tag does not point"):
            p.publish(api)
        self.assertEqual(1, len(api.writes))

    def test_main_rejects_other_repos_refs_and_events_before_api_use(self):
        env = {"GITHUB_REPOSITORY": p.REPOSITORY, "GITHUB_EVENT_NAME": "push",
               "GITHUB_REF": "refs/heads/master"}
        for key in env:
            with patch.dict(p.os.environ, {**env, key: "other"}, clear=True), patch.object(p, "GitHub") as client:
                with self.assertRaises(RuntimeError):
                    p.main()
                client.assert_not_called()


class TransportTests(unittest.TestCase):
    def test_public_reads_omit_token_and_release_reads_include_it(self):
        api = p.GitHub("test-token")
        requests = []
        def open_request(request, timeout):
            requests.append(request)
            return io.BytesIO(b'{}')
        api.opener.open = open_request
        api.request("pulls/29")
        api.request(p.RELEASE_PATH, authenticated=True)
        self.assertIsNone(requests[0].get_header("Authorization"))
        self.assertEqual("Bearer test-token", requests[1].get_header("Authorization"))
        self.assertTrue(all(r.full_url.startswith("https://api.github.com/repos/" + p.REPOSITORY + "/") for r in requests))

    def test_redirects_are_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "refusing to forward"):
            p.NoRedirect().redirect_request(None, None, 302, "", {}, "https://example.org")

    def test_only_404_is_treated_as_an_absent_tag(self):
        api = p.GitHub("test-token")
        for code in (401, 403, 404, 500):
            api.opener.open = lambda *a, c=code, **k: (_ for _ in ()).throw(
                urllib.error.HTTPError("https://api.github.com", c, "error", {}, None))
            if code == 404:
                self.assertIsNone(api.request(p.TAG_PATH, authenticated=True, missing_ok=True))
            else:
                with self.assertRaisesRegex(RuntimeError, f"HTTP {code}"):
                    api.request(p.TAG_PATH, authenticated=True, missing_ok=True)

    def test_no_missing_token_or_unexpected_write_target(self):
        for client, path in ((p.GitHub(""), p.RELEASE_PATH), (p.GitHub("test"), "releases/1")):
            with self.assertRaises(RuntimeError):
                client.request(path, authenticated=True, payload={"draft": False})


if __name__ == "__main__":
    unittest.main()
