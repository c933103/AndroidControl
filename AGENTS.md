# Working in AndroidControl

Read [README.md](README.md), applicable [testing guides](docs/testing/), [Android CI](.github/workflows/android-ci.yml) and [App builds](.github/workflows/app.yml) before changing behavior. Keep existing draft/release holds, device acceptance and signed-update requirements.

## Keep changes and evidence focused

- Define one finishable outcome, its affected behavior and observable acceptance checks. Keep implementation, meaningful regressions and compact failure fixtures together; split unrelated audits or archive work.
- Keep bulky run logs, captures, APKs and duplicate source dumps on durable evidence refs or established artifact storage. In the implementation PR, retain one compact receipt: exact source and test revision, environment, command/run, outcomes, failures, untested scope, artifact hashes where bytes matter, and immutable archive links.
- Preserve existing archives, retention commitments and recovery paths. Inspect consumers, licensing and packaging before moving anything; verify the archived bytes and accessibility first. This guidance authorizes no deletion, expiry or history rewrite.
- Test current behavior, data integrity and compatibility directly. Do not add permanent historical-source hash gates, recursive inverse adapters or full-tree reconstruction as ordinary CI. A one-time migration comparison is distinct from a lasting product contract.
- Match acceptance to the requirement and risk. Preserve negative controls and the original failure; never silently weaken assertions, skip required checks or relabel failed aggregates as passed. A reviewed replacement is required before retiring an existing gate.
- Require applicable current-head CI and risk-focused review before an authorized merge; re-review affected fixes and integrations. Record published, tested, reviewed, merged and released separately. Missing, blocked, cancelled and failed checks remain distinct.
- Keep public records standalone and free of credentials, secrets, private tracker links and unrelated private data. Use the designated private reporting channel for sensitive security findings.

## AndroidControl contracts and verification

- Preserve AppOps user/package isolation, picker recreation and recovery, portrait/session lifecycle, pairing persistence, privileged-call boundaries and signed in-place upgrades. Keep compact predecessor APK/source fixtures that exercise real update compatibility; they are not obsolete historical-source gates.
- Retain host fixtures and the applicable API33/API35 installed checks. A build or mocked host test is not evidence for a real device, provider, call-recording output or permission lifecycle that it did not exercise.
- Keep upstream submodule identity, license texts, notices, component attribution and required corresponding source. For feature branches, inspect their additional component and packaging obligations before separating any captures, runners or archives.
- Root `AGENTS.md` is outside the Android source sets and APK asset inputs inspected in `manager/build.gradle`. However, root `build.gradle` derives version code/name from Git history and HEAD: even a documentation commit changes generated version metadata. Do not claim byte-identical APKs or transfer previous artifact hashes.
- Documentation review checks content, links and the precise diff. Existing current-head CI remains applicable. Any distributed source archive includes the changed guidance; regenerate/bind actual source and APK artifacts when needed for release or artifact acceptance.
- No documentation change alone satisfies open device, recorder, licensing, upgrade-signature or release acceptance, or authorizes changing signing keys, runner permissions or access settings.
