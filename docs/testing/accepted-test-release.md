# Publish the accepted fd94daf test release

This is a bounded publication of one existing, accepted test APK. It does not
rebuild or replace the APK, publish other drafts, remove device-validation
caveats, change signing, or alter repository access.

## Accepted artifact and evidence

- Source: [fd94dafa153245163f404c330a6b902deb76ea53](https://github.com/c933103/AndroidControl/commit/fd94dafa153245163f404c330a6b902deb76ea53), the merge of [PR29](https://github.com/c933103/AndroidControl/pull/29).
- Reviewed PR head: `36622879ec05e43e0d674d0b5e8c2512fea4ae20`.
- [Codex code/security summary](https://github.com/c933103/AndroidControl/pull/29#issuecomment-6206478845): both completed, no findings, exact reviewed head.
- Postmerge [Android CI](https://github.com/c933103/AndroidControl/actions/runs/38840328632), [App](https://github.com/c933103/AndroidControl/actions/runs/38840329526), and [signed draft build](https://github.com/c933103/AndroidControl/actions/runs/38840328657): successful at the accepted source.
- Existing release ID: `414471568`; intended tag: `androidcontrol-test-fd94daf`; prerelease remains true.
- Existing asset ID: `645876040`; name: `androidcontrol-v13.6.0.r1251.fd94dafa-debug.apk`; size: `13991379` bytes.
- GitHub-computed asset SHA-256: `5add6cc78bf7e2d9ba85abd7bdb1658c65e1b54403dea80c815d2968220a71f7`.
- The signed draft build verified permanent certificate SHA-256 `5f3ab2f36ce4a7856c12b1744b799ff775e6f74f05c0bd2c9d8db824674b7ef1`.

The existing installed API33/API35 suite passed. The new explicit
 enabled/disabled/absent restoration matrix was exercised with production Java
and controlled dependencies, not separately demonstrated on-device. The release
notes retain the portrait-container/restoration device-validation caveat.

## Route and review gate

The existing `draft-test-release.yml` accepts master pushes or manual dispatch
with no inputs, and deliberately creates only drafts. The App workflow uploads
Actions artifacts. Neither publishes an existing draft.

The separate `publish-accepted-test-release.yml` tests its guards on relevant
pull requests. After the publisher PR has exact-head CI and Codex review, merging
it triggers the master-push publication job naturally. The parent workflow and
application CI remain applicable before merge. No publication job runs on a PR.
The new workflow only responds to changes in its own four files, and can only
publish the pinned release. Later runs verify an already-published release and
exit without a write.

Only the publication job uses `contents: write`, the same permission already
used by the draft workflow. Checkout does not persist credentials. Public review
and run evidence is read without authentication, so no Actions or pull-request
permission is added. The existing signing secret is never read by this workflow.
All authenticated calls stay on the fixed GitHub API host; redirects are refused.

Before mutation the script checks exact release metadata, asset ID/size/digest,
notes, prerelease state, any existing tag, PR29 merge identity, exact Codex review,
and all three accepted run identities and outcomes. It then rechecks the draft
and tag, and PATCHes only publication status, preserved prerelease state, the
explicit non-latest setting, and removal of the final `Not published.` sentence.
It verifies the published record and tag afterward. Missing evidence or a changed
artifact fails closed. An uncertain write is not blindly retried: inspect the
release and rerun only after reconciliation; the successful state is idempotent.

GitHub can require workflow-write authority when a release target adds/modifies
workflow files relative to the default branch. This patch adds a separate file
and leaves all existing workflow files untouched. Do not respond to an API
permission error by adding credentials, expanding permissions, changing the
accepted target or substituting another APK. Report the error for review.

## Compact verification receipt

At base `fd94dafa153245163f404c330a6b902deb76ea53`:

- `python3 -m unittest discover -s tests -p 'test_publish_accepted_test_release.py' -v`: 19 tests pass, including identity/digest drift, missing/stale/failed evidence, wrong tags, concurrent changes, idempotency, uncertain writes and transport guards.
- `python3 -m py_compile tools/publish_accepted_test_release.py`: passes.
- YAML parse and explicit trigger/permission assertions: pass.
- Current connector-fetched release, PR29/Codex summary and run snapshots pass the production read-only guards.
- No live release mutation, APK rebuild or claim of new Android/device acceptance was made while preparing this change. Hosted current-head CI, risk-focused review, and the eventual publication result are recorded separately on the PR/run.
