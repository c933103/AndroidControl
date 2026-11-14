# Component-specific licensing in AndroidControl

**The repository has multiple licences.** An individual source file can retain its original Apache-2.0, MIT, or GPL licence. This is different from the legal conditions for **distributing one combined executable**. The **combined AndroidControl APK** generally must satisfy GPLv3 distribution requirements due to its directly integrated ShizuCallRecorder-derived code. The root [LICENSE](LICENSE) reproduces the original Shizuku Apache-2.0 text; it is **not** a claim that every new or imported component is Apache-2.0.

## Source components

| Component / files | Applicable licence | Provenance and relevant text |
| --- | --- | --- |
| Shizuku-derived manager/server/API/privileged infrastructure, AndroidControl portrait and other independently developed core features | **Apache-2.0**, subject to individual file notices | [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku), AndroidControl contributors; [Apache-2.0 text](LICENSES/Apache-2.0.txt). The `api/` Git submodule keeps its own metadata. |
| **AppOpsX legacy backup compatibility adapter**, `manager/src/main/java/org/androidcontrol/app/appops/AppOpsBackup.kt` | **MIT** | Based on [8enet/AppOpsX](https://github.com/8enet/AppOpsX), © 2016 Zheng li, adapted for AndroidControl; [MIT licence](LICENSES/MIT.txt). **Other AppOps UI/service files are not automatically licensed MIT.** |
| Adapted **ShizuCallRecorder upstream audio stack**: seven Kotlin sources in `manager/src/main/java/org/androidcontrol/app/recording/integrations/scrcpy/` and `.../recording/services/shell/ShellAudioPipeline.kt`, and `manager/src/main/res/values/call_recorder_upstream_strings.xml` | **GPL-3.0-or-later plus upstream Section 7 additional terms**, machine-identified as `LicenseRef-ShizuCallRecorder-GPL3-Section7` | [kitsumed/ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) revision [41d1dc7](https://github.com/kitsumed/ShizuCallRecorder/commit/41d1dc7be09ec4d1ca648ff62d9cf1aff6e0cd8a), © 2026-present kitsumed (Med) and contributors; [full GPL and additional terms](LICENSES/LicenseRef-ShizuCallRecorder-GPL3-Section7.txt). |
| **Independently written AndroidControl recording integration**: `recording/CallRecording*.kt`, `CallRecordingBridge.kt`, `CallRecordingPolicy.kt`, `CallRecordingPreferences.kt`, `PhoneStateCallReceiver.kt`, `recording/utils/AppLogger.kt`, `home/CallRecordingViewHolder.kt`, and first-party recorder UI XML files | **Apache-2.0** for the original source in those files | AndroidControl implementation, separately licensed at file level. **This is not a linking exception** for using the GPL-derived audio stack within the same application. |
| **scrcpy-server v4.0** executable supplied at APK build time | **Apache-2.0** | [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) with pinning and hash verification; [Apache-2.0 text](licenses/scrcpy-Apache-2.0.txt). |
| Other dependencies, third-party artwork/resources and the pinned Shizuku `api/` submodule | Their *respective* upstream terms | See the individual files, dependency/source manifests and [third-party notices](THIRD_PARTY_NOTICES.md). Do not presume a blanket license for outside dependencies. |

**Rule:** Where there is a specific copyright/licence notice in an existing upstream file, that notice takes priority over a broad directory description. We have **not** attempted to change licences owned by other developers. The MIT identifier is specific to the adapted AppOpsX backup file, not a grant of MIT for the rest of AndroidControl's AppOps subsystem.

## Exact GPLv3 Section 7 clauses from ShizuCallRecorder

The author appended **four** conditions to the licence document:

1. **§7(e), trademarks:** no permission to use the original author's or contributors' names/logos as a trademark or for the original branding.
2. **§7(d) and (e), publicity:** limits the use of the project's and authors' names and branding for publicity and app-store listing purposes without permission.
3. **§7(c), modified versions:** a derivative must have a different app name and package ID and prominently say that it is a fork or edited version.
4. **§7(b), attribution:** an in-app credit and link to the [original GitHub repository](https://github.com/kitsumed/ShizuCallRecorder).

The exact original wording is preserved verbatim in [`LICENSES/LicenseRef-ShizuCallRecorder-GPL3-Section7.txt`](LICENSES/LicenseRef-ShizuCallRecorder-GPL3-Section7.txt); this summary is not a substitute. Whether every wording detail is enforceable under GPLv3 §7 and local law is a legal question. Preserve the original terms and avoid inferring permissions that the copyright holder did not grant.

### Why the custom SPDX identifier?

The upstream source is *not plain GPLv3 without additions*. To preserve the exact additional conditions in machine-readable metadata, imported files have:

```text
SPDX-License-Identifier: LicenseRef-ShizuCallRecorder-GPL3-Section7
```

The full composite text (GPL-3.0-or-later base and the Section 7 appendix) is stored in `LICENSES/` under that exact SPDX `LicenseRef` name, and also at `licenses/ShizuCallRecorder-GPL-3.0.txt` and in the APK's `assets/licenses/`. This identifier does **not** invent a new licence or replace the author's original rights. Other files have normal `SPDX-License-Identifier: Apache-2.0` or `MIT` headers.

## Combined APK versus reusable source files

Because this integrated AndroidControl application **incorporates and directly links** GPL-derived ShizuCallRecorder classes with its first-party services and UI, GPLv3's combined-work rules generally require distributors to provide GPLv3 rights and corresponding source **for the covered combination**; the original licence grants for distinct Apache-2.0/MIT source files continue to apply when those files are reused individually.

**Simply placing the GPL code in a separate Gradle module, process or directory does not eliminate these obligations.** Independent apps that interact at arm's length might be separable, but this project intentionally provides one integrated APK and makes **no such claim**. Changing the GPL source to Apache/MIT without the rights holders' permission would not be valid.

For compliant distribution: preserve original attributions and Section 7 notices, provide the correct licences and corresponding source/build inputs, and do not advertise the whole APK as Apache-2.0-only. The in-app **About → Component licences** screen displays the distinct source licences, and explains the combined-application rule.

Further reading: [GNU GPL FAQ: GPL modules](https://www.gnu.org/licenses/gpl-faq.html#GPLModuleLicense), [Mere aggregation](https://www.gnu.org/licenses/gpl-faq.html#MereAggregation), [GPLv3 §§5,7](https://www.gnu.org/licenses/gpl-3.0.html).

## Enforcement and maintenance

Run `python3 tests/check-component-licenses.py` to check SPDX tags, source/component scope, verbatim upstream licence copies, and the in-APK legal files. The package validation at `tests/verify-call-recording-apk.py` inspects assembled APK assets. Add any new copied upstream files to the explicit mapping and do not silently treat GPL-derived code as Apache-2.0.

This is a component inventory and build compliance check, not a legal opinion or a guarantee of compliance with every potential third-party dependency.
