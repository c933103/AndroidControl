# Third-party notices

## Shizuku

The manager/backend originated from [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and retains the upstream API submodule and source copyright notices. License: [Apache-2.0](LICENSE). AndroidControl is an independently branded fork.

## AppOpsX

[8enet/AppOpsX](https://github.com/8enet/AppOpsX), copyright (c) 2016 Zheng li. License: [MIT](licenses/AppOpsX-MIT.txt).

Operation-label resources are adapted from the AppOpsX English, Czech, Spanish, Russian,
Simplified Chinese and Traditional Chinese translations at commit
[`1ba0eefb`](https://github.com/8enet/AppOpsX/commit/1ba0eefb47055fb6d3e636a9833b7687221e8dd9).
This does not import AppOpsX's outdated daemon/startup text or its full UI.
New AndroidControl-specific warnings and menu text are independently translated.

The legacy backup decoder adapts the `v: 1`, `opbacks`, `pkg`, and comma-separated `ops` compatibility behavior from AppOpsX’s `ConfigPresenter` and `PreAppInfo`. The UI/backend are implemented within AndroidControl; AppOpsX’s old socket server, API stubs, and starter script are not bundled. The full MIT notice is also shipped in `manager/src/main/assets/licenses/AppOpsX-MIT.txt`, and attribution appears in About.

## Additional App Ops localization terminology

[RikkaApps/AppOps-Localization](https://github.com/RikkaApps/AppOps-Localization/tree/071eab5b984c41ee4dec44bb55dc9cd402e2ad06)
is a separate localization project for Rikka's App Ops product and is **not**
the AppOpsX project. It is used as a reference for conventional short UI
terms (permission groups and mode names) in 16 additional languages.
The inspected repository does not include a root LICENSE file; AndroidControl
does not assume blanket permission to redistribute its entire XML corpus.
No long-form messages, feature-specific text or application assets are included.

Other existing dependencies retain their original licenses and notices.
