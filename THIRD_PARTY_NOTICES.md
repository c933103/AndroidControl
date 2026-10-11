# Third-party notices

## Shizuku

The manager/backend originated from [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and retains the upstream API submodule and source copyright notices. License: [Apache-2.0](LICENSE). AndroidControl is an independently branded fork.

## AppOpsX

[8enet/AppOpsX](https://github.com/8enet/AppOpsX), copyright (c) 2016 Zheng li. License: [MIT](licenses/AppOpsX-MIT.txt).

The legacy backup decoder adapts the `v: 1`, `opbacks`, `pkg`, and comma-separated `ops` compatibility behavior from AppOpsX’s `ConfigPresenter` and `PreAppInfo`. The UI/backend are implemented within AndroidControl; AppOpsX’s old socket server, API stubs, and starter script are not bundled. The full MIT notice is also shipped in `manager/src/main/assets/licenses/AppOpsX-MIT.txt`, and attribution appears in About.

Other existing dependencies retain their original licenses and notices.

## ShizuCallRecorder – adapted call-recording functionality

The AndroidControl **Call recording** module includes modified source files derived from [kitsumed/ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder), original author kitsumed (Med), from upstream commit [41d1dc7be09ec4d1ca648ff62d9cf1aff6e0cd8a](https://github.com/kitsumed/ShizuCallRecorder/commit/41d1dc7be09ec4d1ca648ff62d9cf1aff6e0cd8a). Copyright (C) 2026-present kitsumed (Med) and contributors.

**License:** GPL-3.0-or-later **with additional Section 7 terms**. Full copy: [licenses/ShizuCallRecorder-GPL-3.0.txt](licenses/ShizuCallRecorder-GPL-3.0.txt), also bundled as `manager/src/main/assets/licenses/ShizuCallRecorder-GPL-3.0.txt`. All copied files retain original source copyright and licensing headers, and mark modification for AndroidControl. Unlike the original, AndroidControl uses an integrated screen and its own package name, splash/branding and privilege-service architecture. It is a **modified fork** of the recording functionality, not an official ShizuCallRecorder release, and is not endorsed by the original author. The app's Call recording screen and About dialog provide visible attribution and the upstream link.

**Distributors:** GPL-3.0 obligations apply when conveying the combined derivative application; the project's Apache-2.0 original Shizuku component notices are preserved but the root Apache-2.0 licence does **not** independently describe the combined derivative licensing. Examine the additional Section 7 restrictions on trademark/publicity and modified-fork labelling. Include corresponding complete source and build scripts.

## scrcpy-server – audio capture engine

The recording module uses [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) server binary v4.0. It is downloaded at build time from a pinned official release and verified before bundling and again before running. Required SHA-256: `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`.

**License:** Apache License 2.0; [licence copy](licenses/scrcpy-Apache-2.0.txt) bundled in the app under `manager/src/main/assets/licenses/scrcpy-Apache-2.0.txt`. Source and applicable copyright information are available in the [official scrcpy repository](https://github.com/Genymobile/scrcpy). Review any additional third-party notices required by the actual scrcpy distribution before release.
