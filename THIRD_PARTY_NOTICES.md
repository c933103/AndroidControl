# Third-party notices

This is a multi-licensed codebase. Consult [LICENSING.md](LICENSING.md) for exact source-file scopes and the combined-APK GPLv3 distribution obligations. The root Apache-2.0 LICENSE covers the Shizuku-derived portion, not the GPL-derived audio stack.

## Shizuku

The manager/backend originated from [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and retains the upstream API submodule and source copyright notices. License: [Apache-2.0](LICENSE). AndroidControl is an independently branded fork.

## AppOpsX

[8enet/AppOpsX](https://github.com/8enet/AppOpsX), copyright (c) 2016 Zheng li. License: [MIT](licenses/AppOpsX-MIT.txt).

Operation-label resources are adapted from the AppOpsX English, Czech, Spanish, Russian,
Simplified Chinese and Traditional Chinese translations at commit
[`1ba0eefb`](https://github.com/8enet/AppOpsX/commit/1ba0eefb47055fb6d3e636a9833b7687221e8dd9).
This does not import AppOpsX's outdated daemon/startup text or its full UI.
New AndroidControl-specific warnings and menu text are independently translated.

The MIT-licensed legacy backup adapter `manager/src/main/java/org/androidcontrol/app/appops/AppOpsBackup.kt` adapts the `v: 1`, `opbacks`, `pkg`, and comma-separated `ops` compatibility behavior from AppOpsX’s `ConfigPresenter` and `PreAppInfo`. The UI/backend are implemented within AndroidControl; AppOpsX’s old socket server, API stubs, and starter script are not bundled. The full MIT notice is also shipped in `manager/src/main/assets/licenses/AppOpsX-MIT.txt`, and attribution appears in About.

Other AppOps UI/backend sources are not relicensed merely because they work with the MIT adapter. Other existing dependencies retain their own licenses and notices.

## Additional App Ops localization terminology

[RikkaApps/AppOps-Localization](https://github.com/RikkaApps/AppOps-Localization/tree/071eab5b984c41ee4dec44bb55dc9cd402e2ad06)
is a separate localization project for Rikka's App Ops product and is **not**
the AppOpsX project. It is used as a reference for conventional short UI
terms (permission groups and mode names) in 16 additional languages.
The inspected repository does not include a root LICENSE file; AndroidControl
does not assume blanket permission to redistribute its entire XML corpus.
No long-form messages, feature-specific text or application assets are included.

## ShizuCallRecorder – adapted call-recording functionality

The AndroidControl **Call recording** module includes modified source files derived from [kitsumed/ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder), original author kitsumed (Med), from upstream commit [41d1dc7be09ec4d1ca648ff62d9cf1aff6e0cd8a](https://github.com/kitsumed/ShizuCallRecorder/commit/41d1dc7be09ec4d1ca648ff62d9cf1aff6e0cd8a). Copyright (C) 2026-present kitsumed (Med) and contributors.

**License:** GPL-3.0-or-later **with additional Section 7 terms** (SPDX `LicenseRef-ShizuCallRecorder-GPL3-Section7`). Full copy: [licenses/ShizuCallRecorder-GPL-3.0.txt](licenses/ShizuCallRecorder-GPL-3.0.txt), also bundled as `manager/src/main/assets/licenses/ShizuCallRecorder-GPL-3.0.txt`. All copied files retain original source copyright and licensing headers, and mark modification for AndroidControl. Unlike the original, AndroidControl uses an integrated screen and its own package name, splash/branding and privilege-service architecture. It is a **modified fork** of the recording functionality, not an official ShizuCallRecorder release, and is not endorsed by the original author. The app's Call recording screen and About dialog provide visible attribution and the upstream link.

**Per-file distinction:** the independently developed AndroidControl recording UI, preferences, service bridge and control layers are individually Apache-2.0, while the imported codec, muxer, scrcpy client and shell pipeline remain GPL-3.0 with Section 7 terms.

**Distributors:** GPL-3.0 obligations apply when conveying the combined derivative application; the project's Apache-2.0 original Shizuku component notices are preserved but the root Apache-2.0 licence does **not** independently describe the combined derivative licensing. Examine the additional Section 7 restrictions on trademark/publicity and modified-fork labelling. Include corresponding complete source and build scripts.

## scrcpy-server – audio capture engine

The recording module uses [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) server binary v4.0. It is downloaded at build time from a pinned official release and verified before bundling and again before running. Required SHA-256: `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`.

**License:** Apache License 2.0; [licence copy](licenses/scrcpy-Apache-2.0.txt) bundled in the app under `manager/src/main/assets/licenses/scrcpy-Apache-2.0.txt`. Source and applicable copyright information are available in the [official scrcpy repository](https://github.com/Genymobile/scrcpy). Review any additional third-party notices required by the actual scrcpy distribution before release.
