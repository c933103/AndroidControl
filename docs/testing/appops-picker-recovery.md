# AppOps document picker recovery (AC-002)

## Failure and repair

Before this change, the selected Android user and package existed only in Activity fields. Recreating the Activity while the document picker was open lost the operation context. A result could be silently dropped while startup enumeration marked the screen busy, or export could use the recreated default profile/app cache instead of the profile used to launch the picker.

The Activity now saves its selected user/package and an explicit pending document request. A successful result that arrives during startup is queued, including its URI, until the existing work finishes. Both the request and a queued URI round-trip through saved instance state. A matching result is consumed once, only when work can actually be scheduled. Unrelated results are ignored; cancellation clears the request, and duplicates cannot overwrite or cancel an already queued successful result.

Export enumerates the request's captured user directly rather than trusting the recreated Activity's cache. Import uses the request's captured user/package for validation, names that user in its confirmation and keeps the same target for all subsequent writes even if the displayed selection changes. A missing/failed request never falls back to another user's profile.

## Repeatable controlled lifecycle tests

With JDK 21 and Maven installed, from the repository root:

```sh
mvn -B -f tests/appops-picker-fixture/pom.xml test
```

The focused `appops-picker-lifecycle` CI job runs this command independently of the native app build. The POM copies the exact current production `AppOpsActivity`, `AppOpsBackup` and `AppOpsNames` sources without rewriting them. It compiles them with the same Kotlin version as the app and runs the lifecycle test suite on Robolectric API 30 and 35. Maven expands the two AndroidX test AARs into JVM classpath entries; no Android SDK, emulator or privileged service is needed for this fixture.

The fixture uses real Robolectric Android Activity recreation, Bundle/Parcel serialization, Intent/Uri, ContentResolver streams, AlertDialog click delivery and the UI-thread handoff. The app bar/theme shell, RecyclerView rendering adapter, resource lookup and AppOps daemon are explicit test stand-ins. AppOps requests are recorded by an in-memory, profile-aware fake backend, and all document streams stay in memory. No real profiles, Binder requests, privileged files or installed-app settings are touched.

Coverage includes framework recreation and parcel-round-trip restoration; import/export results during busy startup; recreation of an already queued result; null, cancelled, unrelated and duplicate results; cancelled confirmation; fresh profile-scoped enumeration; same-target import validation/confirmation/writes; and recoverable enumeration, output and parsing failures.

## Observed controlled results (9 October 2026)

- Final candidate: all 42 executions passed (21 scenarios on each of API 30 and API 35).
- Exact baseline `b7c3d000fefd67f8e227c11a5aeb47a2f599e9e3`, against the identical test suite: 34 of 42 executions failed. Failures included empty/dropped exports during recreated startup, wrong-profile export/restore and missing import confirmation after saved-state recreation. The baseline comparison is a controlled reproduction, not an observation of a user's device or backup corruption.
- The first candidate passed 36 of 40 executions and failed the four queued duplicate-success/cancellation cases. The successful-result guard fixed those cases before the final 42-execution run.
- The local runner used cached Kotlin 1.9.24, JUnit 4.13.2, Robolectric 4.14.1 and Java 21 with explicit Android framework JARs. This validates the production source and test assertions; the newly added Maven/CI path and native app build must be checked separately on the published head.

## Verification limits

These tests exercise the actual Activity and backup parser with controlled dependencies. They do not establish the behavior of a device's system document picker, OEM activity dispatch, Material/Rikka rendering, Shizuku privilege enforcement or live-profile writes. The existing Android CI build/runtime jobs remain separate gates. Activity recreation during already-started background export/import work, after the picker request has been consumed, is outside this narrowly scoped picker-recovery change.
