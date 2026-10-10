# AndroidControl: project brief and kanban

Parent: [private cross-project master](https://github.com/c933103/something-priv-maybe/issues/1) (access restricted).

Snapshot: 10 October 2026, 04:01 UTC / 12:01 UTC+08. Master: `5ccc8ef67373b8a5dfdb351cdcab8c1ce3ca55a8`.

## Brief and product decisions
Provide Android orientation/session controls and app operations with clearly separated per-app and system-wide behavior. Retain editable package selection, Shizuku/LADB-style privileged startup options, useful no-PC setup, reliable recovery/revert and usable installable builds.

- Avoid freezes, black screens and overlays that obstruct the target app; preserve target-session input/navigation and safe return to the normal display.
- Keep app identity/branding/package changes coherent, preserve signing continuity and explain the separate-data consequences of the independently branded package.
- AppOps package modes, Android users/profiles, backup/import and new-app rules must preserve their documented scope. They do not grant runtime permissions or replace UID-wide rules.
- Verify actual Android lifecycle/routing behavior in addition to compilation, with physical-device coverage explicitly distinguished from emulator checks.
- Product baseline: [README](../README.md); app operations: [docs/appops.md](appops.md).

## Using this board
Move each card between Backlog, Ready, In progress, Blocked and Done as evidence changes. Keep its stable ID, next action and child/evidence link. Ready means ready for the stated next step, not permission to merge or release. Done requires exact-source acceptance and applicable integration/release checks. A historical green run, a source patch or a linked PR alone does not satisfy a wider requirement.

Repository Issues are disabled. This Markdown board is the durable repository tracker; its documentation PR provides discussion until integration. Preserve repository settings and visibility. Detailed PRs and evidence registers retain their own scope and acceptance criteria.

## Backlog
- [ ] **AC-DEVICE: physical-device acceptance.** Next: exercise actual no-PC startup/pairing, target portrait sessions, input/web-link handoff, recovery/revert, app operations and profile isolation on representative devices; record device/OS limits. Baseline and CI boundaries: [README](../README.md#build-and-verify). The published prerelease's API33/API35 results do not claim physical-device acceptance.
- [ ] **AC-PRODUCT: reconcile remaining requested behavior.** Next: compare the current toolbox and per-app/system-wide separation against the requested workflow and create bounded child records only for established gaps. Current product scope: [README](../README.md); identity/AppOps integration: [PR20](https://github.com/c933103/AndroidControl/pull/20). Do not infer that old portrait experiments or ancestry alone establish current acceptance.

## Ready
No additional implementation or release is made ready solely by this documentation update. The open integration gate below is the next prerequisite.

## In progress
No new source change is claimed by this board. Current published correction awaits its remaining acceptance gate.

## Blocked
- [ ] **AC-002: AppOps picker request/profile continuity.** [PR22](https://github.com/c933103/AndroidControl/pull/22), head `b33649134e60dd48074c49539dc85badc55a3844`, tree `c1d8266709c6eb5363e40ebe5333148137598c01`, includes current master. [All five Android CI jobs](https://github.com/c933103/AndroidControl/actions/runs/38014340396) passed, including API33/API35, with [current Code review](https://github.com/c933103/AndroidControl/pull/22#issuecomment-6092390829). A separately labelled current-head review result remains unestablished. Next: resolve that exact gate, recheck current-base integration, then perform applicable post-merge/release verification. No merge or completion is claimed.

## Done
- [x] **AC-001:** unanswered ADB file-bind recovery integrated. Child and bounded evidence: [PR21](https://github.com/c933103/AndroidControl/pull/21).
- [x] **AC-BUILD/RUNTIME:** pinned existing Gradle distribution checksum and runtime-attachment/portrait-diagnostic corrections integrated in [PR23](https://github.com/c933103/AndroidControl/pull/23) and [PR24](https://github.com/c933103/AndroidControl/pull/24). Their exact post-merge evidence is retained in the repository.
- [x] **AC-TEST-RELEASE:** [androidcontrol-test-5ccc8ef](https://github.com/c933103/AndroidControl/releases/tag/androidcontrol-test-5ccc8ef) is a published public prerelease, source/tag `5ccc8ef67373b8a5dfdb351cdcab8c1ce3ca55a8`, original signing identity retained. APK SHA-256 `533a5bfdcdb8a1492818ac00ef17a489aeb1f24f4dcca4945eb5688aea106b51`; asset size 13,935,943 bytes. [Release receipt](https://github.com/c933103/AndroidControl/pull/24#issuecomment-6092040575) records API33/API35 evidence and physical-device limits. Live release metadata and asset digest were read back for this tracker. This published source does not include pending PR22.

## Maintenance
Keep pending review, accepted source, test coverage and published release distinct. Update this board, linked child evidence and the private master when states change. Keep private review details and signing material outside this public repository.
