# AndroidControl: project brief and kanban

Discussion and dated evidence: [project-board PR25](https://github.com/c933103/AndroidControl/pull/25).

Reconciled: 26 October 2026. Verified master: `fc4cbedb2aaf454bc6c1e3f338e137650ee40e7b`. This is a dated status snapshot based on public child records and PR/release metadata, not a new application, device or APK-byte verification. The [original 10 October board](https://github.com/c933103/AndroidControl/blob/4aaf25cb75b9c53fec87e140678b03cf148c2281/docs/PROJECT_KANBAN.md) preserves the earlier checkpoint.

## Brief and product decisions
Provide Android orientation/session controls and app operations with clearly separated per-app and system-wide behavior. Retain editable package selection, Shizuku/LADB-style privileged startup options, useful no-PC setup, reliable recovery/revert and usable installable builds.

- Avoid freezes, black screens and overlays that obstruct the target app; preserve target-session input/navigation and safe return to the normal display.
- Keep app identity/branding/package changes coherent, preserve signing continuity and explain the separate-data consequences of the independently branded package.
- AppOps package modes, Android users/profiles, backup/import and new-app rules must preserve their documented scope. They do not grant runtime permissions or replace UID-wide rules.
- Verify actual Android lifecycle/routing behavior in addition to compilation, with physical-device coverage explicitly distinguished from emulator checks.
- Product baseline: [README](../README.md); app operations: [docs/appops.md](appops.md).

## Using this board
Move each card between Backlog, Ready, In progress, Blocked and Done as evidence changes. Keep its stable ID, next action and child/evidence link. Ready means ready for the stated next step, not permission to merge or release. Done applies only to the card's stated scope and requires its exact-source acceptance and applicable integration/release checks. A historical green run, a source patch or a linked PR alone does not satisfy a wider requirement.

This Markdown board is the durable repository tracker; its documentation PR provides discussion until integration. Detailed PRs and evidence registers retain their own scope and acceptance criteria.

## Backlog
- [ ] **AC-DEVICE: physical-device acceptance.** Next: exercise actual no-PC startup/pairing, target portrait sessions, input/web-link handoff, recovery/revert, app operations and profile isolation on representative devices; record device/OS limits. Baseline and CI boundaries: [README](../README.md#build-and-verify). The published prerelease's API33/API35 results do not claim physical-device acceptance.
- [ ] **AC-PRODUCT: reconcile remaining requested behavior.** Next: compare the current toolbox and per-app/system-wide separation against the requested workflow and create bounded child records only for established gaps. Current product scope: [README](../README.md); identity/AppOps integration: [PR20](https://github.com/c933103/AndroidControl/pull/20). Do not infer that old portrait experiments or ancestry alone establish current acceptance.

## Ready
No additional implementation, merge or release is made ready solely by this documentation update. AC-002's former integration gate is complete; AC-RECORDER retains its separate gates below.

## In progress
No new application change or test execution is claimed by this board. Child PRs and their exact-source receipts remain authoritative.

## Blocked
- [ ] **AC-RECORDER: call-recording integration and acceptance.** [PR26](https://github.com/c933103/AndroidControl/pull/26) remains draft/unmerged at `bb7b4caa66ba15a2a8f36e6425292e4f665a8ab4`; current metadata reports merge conflicts. Its [24 October validation plan](https://github.com/c933103/AndroidControl/pull/26#issuecomment-6382672075) distinguishes existing Code review, build and controlled host fixtures from missing recorder-specific installed tests and real handset recording. Next: resolve integration, obtain fresh exact-head review/CI, and establish installed synthetic-media/Binder/permission/provider/lifecycle coverage before separately qualifying consented handset audio and the claimed Android/OEM routes. General API33/API35 orientation/AppOps checks do not establish recorder behavior; current controls expose start/stop, not pause/resume. The [25 October scope clarification](https://github.com/c933103/AndroidControl/pull/26#issuecomment-6401015177) records the source-component inventory and literal notices, not release-specific attribution presentation, corresponding-source/build-input, privacy/platform or distribution acceptance. Those gates remain open, and this board does not change the child's do-not-merge-until-accepted requirement.

## Done
- [x] **AC-002: AppOps picker request/profile continuity.** [PR22](https://github.com/c933103/AndroidControl/pull/22) merged as `834b7c12887a3ac20d9c55faf647c7acc848c8bf`. Its [11 October completion receipt](https://github.com/c933103/AndroidControl/pull/22#issuecomment-6109318840) verifies the accepted tree, exact-head Code review and all eight postmerge check runs, including 48 controlled Activity/parser executions and API33/API35 runtime checks. This completes the bounded source/review/postmerge work, not a fresh separately labelled Security verdict. Real system-picker/OEM URI grants, live privileged-profile writes and recreation after import/export work starts remain outside the picker fixture's coverage.
- [x] **AC-GUIDANCE: focused engineering instructions.** [PR27](https://github.com/c933103/AndroidControl/pull/27) merged as `82e8ccd4164d7da65786be9736e5cad93aa424c1`, adding [AGENTS.md](../AGENTS.md). Instruction adoption is complete; it does not migrate archives or establish device/release acceptance.
- [x] **AC-APP-OPS-I18N: bounded AppOps localization integration.** [PR28](https://github.com/c933103/AndroidControl/pull/28) merged as `72334bc52feea25bd148515e5494f6aed95bee78`. Scope is five complete AppOps UI catalogues, sixteen partial terminology locales and framework permission/group labels, with stable operation identifiers preserved. This records source integration, not whole-app translation or physical-device linguistic acceptance.
- [x] **AC-COMPAT-RESTORE: prior compatibility-override state.** [PR29](https://github.com/c933103/AndroidControl/pull/29) merged as `fd94dafa153245163f404c330a6b902deb76ea53`. The [merge receipt](https://github.com/c933103/AndroidControl/pull/29#issuecomment-6206650788) records clean exact-head Code/Security review and passing hosted API33/API35 regressions. The explicit enabled/disabled/absent restoration matrix was tested in production Java with controlled dependencies, not separately demonstrated on-device.
- [x] **AC-001:** unanswered ADB file-bind recovery integrated. Child and bounded evidence: [PR21](https://github.com/c933103/AndroidControl/pull/21).
- [x] **AC-BUILD/RUNTIME:** pinned existing Gradle distribution checksum and runtime-attachment/portrait-diagnostic corrections integrated in [PR23](https://github.com/c933103/AndroidControl/pull/23) and [PR24](https://github.com/c933103/AndroidControl/pull/24). Their exact post-merge evidence is retained in the repository.
- [x] **AC-TEST-RELEASE: accepted fd94daf APK publication.** [PR30](https://github.com/c933103/AndroidControl/pull/30) published [androidcontrol-test-fd94daf](https://github.com/c933103/AndroidControl/releases/tag/androidcontrol-test-fd94daf), targeting `fd94dafa153245163f404c330a6b902deb76ea53`. Live metadata confirms a published prerelease; its APK is 13,991,379 bytes with GitHub SHA-256 `5add6cc78bf7e2d9ba85abd7bdb1658c65e1b54403dea80c815d2968220a71f7`. The [16 October publication receipt](https://github.com/c933103/AndroidControl/pull/30#issuecomment-6207757009) records independent anonymous download/hash verification; those bytes were not redownloaded for this board update. The release notes retain a historical “Not published.” sentence; live publication state and the receipt supersede that sentence. Physical-device and recorder acceptance remain separate.

## Historical 10 October checkpoint

At 04:01 UTC / 12:01 UTC+08, master was `5ccc8ef67373b8a5dfdb351cdcab8c1ce3ca55a8`. AC-002 was blocked awaiting its recorded review/integration gate; [the original card](https://github.com/c933103/AndroidControl/blob/4aaf25cb75b9c53fec87e140678b03cf148c2281/docs/PROJECT_KANBAN.md#blocked) retains its exact head, tree, checks and next action. The 11 October completion receipt above supersedes that status within its stated scope.

- [x] **AC-TEST-RELEASE (historical):** [androidcontrol-test-5ccc8ef](https://github.com/c933103/AndroidControl/releases/tag/androidcontrol-test-5ccc8ef) remains a published public prerelease, source/tag `5ccc8ef67373b8a5dfdb351cdcab8c1ce3ca55a8`, original signing identity retained. APK SHA-256 `533a5bfdcdb8a1492818ac00ef17a489aeb1f24f4dcca4945eb5688aea106b51`; asset size 13,935,943 bytes. The [release receipt](https://github.com/c933103/AndroidControl/pull/24#issuecomment-6092040575) records API33/API35 evidence and physical-device limits. This source predates PR22's merge and is not the newer fd94daf artifact.

## Maintenance

Keep pending review, accepted source, test coverage and published release distinct. Update this board and linked public child evidence when states change, preserving stable card IDs and dated historical receipts.

Documentation changes still require applicable exact-head review and CI. [Version metadata](../build.gradle) depends on Git count/SHA, so an old APK hash is not the new documentation commit's artifact identity. PR branch updates run normal CI without publishing a release. A master merge also triggers the existing [signed draft-release workflow](../.github/workflows/draft-test-release.yml); it is not a CI-only action. Public publication of the accepted fd94daf artifact has a [separate path-filtered workflow](../.github/workflows/publish-accepted-test-release.yml). This board changes no application code, licensing, workflows, signing configuration or access settings, and establishes no new release or device acceptance.
