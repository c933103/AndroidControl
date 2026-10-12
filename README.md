# AndroidControl

AndroidControl is an Android toolbox for app operations, portrait display sessions, system rotation, privileged file access, local cellular call recording, and shell access. Its privileged backend is derived from [Shizuku](https://github.com/RikkaApps/Shizuku); the integrated operation controls bring [AppOpsX](https://github.com/8enet/AppOpsX) functionality into the same app.

**App name:** AndroidControl · **Package:** `org.androidcontrol.app` · **Minimum Android:** 7.0 (API 24).

**Licensing is per component, not a blanket root licence:** Shizuku/core source is Apache-2.0, the AppOpsX-derived backup adapter is MIT, and ShizuCallRecorder-derived audio code is GPL-3.0-or-later with additional Section 7 terms. **The integrated APK must satisfy GPLv3 combined-work requirements.** See [Component licences](LICENSING.md).

Download AndroidControl from this repository’s [Releases](https://github.com/c933103/AndroidControl/releases) or successful [Actions builds](https://github.com/c933103/AndroidControl/actions). Upstream Shizuku downloads are a different application.

## Features

- **App operations:** search installed apps, choose an Android user, group/filter by permission and find apps by operation, inspect access and rejection timestamps, and set package modes to Allow, Ignore, Deny, Default, or Foreground only (Android 10+). Each write is read back from Android.
- **Backups:** export a user’s package modes with stable operation names; import AndroidControl JSON or AppOpsX v1 `.bak` files with a target-user preview and per-operation failure reporting.
- **New-app rules:** optionally apply selected Ignore rules to newly installed apps for a chosen user. Rules exclude updates and AndroidControl itself and run while the privileged AppOps daemon is alive.
- **Portrait sessions:** edit the target package, open it on a portrait-shaped display, forward touches and web links, and hand off to the normal phone display for external checkout screens.
- **System rotation:** control and restore system portrait behavior separately from target-specific sessions.
- **Call recording (Android 12+):** consent-aware, opt-in local cellular call capture through the existing Shizuku privileged service; select an SAF folder, choose Opus/AAC, manually start/stop recording, or optionally attempt automatic starts on connected calls. Android restrictions may prevent background starts or recording on specific devices. **Under experimental validation; see [Call recording](docs/call-recording.md).**
- **Privileged tools:** retain the manager, server, starter, shell, file provider, and Shizuku API for future extensions.

AppOps do not grant missing runtime permissions. Operations can share a switch and change together. AndroidControl changes **package modes only** and displays effective AppOps modes; UID-wide rules affecting several apps are preserved. See [AppOps behavior and formats](docs/appops.md).

## Start and use

1. Install an AndroidControl APK from this repository.
2. Start its privileged server from the home screen. On Android 11+, wireless-debugging pairing can start it without a computer. Root and wired ADB startup options are also available.
3. Open **Manage app operations**, choose a user/app, and tap an operation’s mode or tick operations for a batch restriction. **Default** returns a package operation to Android’s default handling; the menu can reset all recorded package operations.
4. Open **Call recording** to review the jurisdiction-dependent consent warning, select a writable destination folder and configure recording on Android 12+. Read [known limitations and tests](docs/call-recording.md).
5. Use the separate portrait/session and system-rotation controls as needed. The saved portrait target does not limit AppOps app selection.

An ADB-started server needs restarting after reboot. Automatic new-app rules resume when AndroidControl reconnects its daemon; installs while it was stopped are not retroactively covered. Device/OS privileges can limit supported controls.

## Package transition

The previous fork used upstream Shizuku’s application ID. `org.androidcontrol.app` installs **alongside** that previous app and official Shizuku. Android keeps their settings and Keystore identities separate, so pair wireless debugging again in the new app. The old app’s data is retained; this is not an automatic settings or key migration.

AndroidControl has its own declared private permissions, native starter lookup, server process, and authorization file. Internal upstream Binder keys/namespaces remain where required by the backend. External clients must target AndroidControl’s identity/permissions to discover this fork; the rename does not redirect clients compiled for official Shizuku.

## Build and verify

Use JDK 21, Android SDK platform/build tools 36, NDK `29.0.13113456`, and CMake 3.31.x. The Gradle wrapper is included.

```sh
git clone --recurse-submodules https://github.com/c933103/AndroidControl.git
cd AndroidControl
./gradlew :manager:assembleDebug :manager:assembleDebugAndroidTest
bash tests/run-portrait-checks.sh
```

The APK is under `manager/build/outputs/apk/debug/`. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`. Signing material stays outside Git; updates to the new package require the same signing certificate.

[Android CI](.github/workflows/android-ci.yml) builds the app and exercises Android 13/15 package separation, pairing persistence within the new package, portrait lifecycle, AppOps read/write, backup validation, and user isolation.

## Layout and licenses

| Location | Purpose |
| --- | --- |
| `manager/` | UI, portrait controls, file provider, AppOps client/daemon |
| `server/`, `starter/`, `shell/`, `common/` | Privileged backend and bootstrap |
| `api/` | Pinned upstream Shizuku API submodule |
| `tests/` | Host and Android runtime regressions |
| `docs/` | Behavior and format documentation |
| `manager/src/main/java/org/androidcontrol/app/recording/` | Apache-2.0 first-party recording glue **and** GPL-derived upstream audio stack (file-level SPDX tags) |

The inherited Shizuku implementation retains [Apache-2.0](LICENSE), the AppOpsX backup adapter is [MIT](licenses/AppOpsX-MIT.txt), and first-party recording UI/glue source is Apache-2.0. **The imported ShizuCallRecorder audio stack is GPL-3.0-or-later with additional Section 7 terms**, and combining it with AndroidControl affects the licence obligations for distributions of the combined application. Preserve the full [ShizuCallRecorder GPL-3.0 licence and extra terms](licenses/ShizuCallRecorder-GPL-3.0.txt), corresponding source, source provenance and visible fork attribution when redistributing APKs. See [LICENSING.md](LICENSING.md) for precise file scopes, [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for credits, and **About → Component licences** inside the app for licence texts. AndroidControl uses its own identity and artwork and does not claim upstream endorsement.
