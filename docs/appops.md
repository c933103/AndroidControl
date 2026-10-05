# Integrated AppOps controls

App search, permission grouping, modes, backups, user selection, and optional new-install restrictions are integrated directly into AndroidControl. A separate AppOpsX APK is not required. A dedicated Shizuku UserService calls Android’s AppOpsManager and retains the parent server’s root/shell privilege. The portrait daemon’s downgrade to shell does not downgrade AppOps.

## Mode and scope

| Mode | Behavior |
| --- | --- |
| Allow | Permit the operation subject to other permission checks |
| Ignore | Silently suppress the operation |
| Deny | Reject the operation; the caller can receive an error |
| Default | Use Android’s default handling |
| Foreground only | Permit when the UID satisfies Android’s foreground condition (Android 10+) |

The screen distinguishes the package mode from its effective AppOps mode. UID-wide settings can override package settings. Writes verify the saved package setting; the UI explains masking without changing UID rules affecting other packages. Allow does not grant runtime permissions. This is not an Internet firewall or runtime-permission manager.

Android versions and device policies can refuse package-mode changes. In particular, Android 15 can manage runtime-permission operations such as camera through its permission service and ignore direct package-mode writes. AndroidControl reports the failed readback; it does not change runtime grants or silently substitute a UID-wide write. Non-runtime operations such as clipboard remain available where the framework permits them.

The catalog and shared switches come from the running Android framework. Operation identifiers use the same public-name scheme on Android 7 and newer releases. Earlier debug-name backups and saved rules are migrated, including the legacy emergency-broadcast, high-power-location, phone-state and screen-on aliases; unknown operations remain rejected. Default filtering shows requested-permission operations, recorded operations, and permissionless operations such as clipboard controls; Show all exposes the remaining operations. Groups use Android permission-group identifiers. Find apps by operation lists matching installed apps and their effective modes. The menu can reset recorded package modes to framework defaults without changing UID rules. Last access/rejection timestamps come from Android records; missing timestamps do not prove an app never accessed the resource. This is a history display, not continuous access recording.

## Users and new installs

Enumeration, package/UID lookup, reads, and writes are explicitly scoped to the selected user. A missing app is reported rather than redirected to another profile.

Each manager profile has a separate AppOps service and saved automatic rule. Binder requests must come from that service's manager UID. Non-administrator instances, including ordinary secondary users and work profiles, can list and manage only their own user; selecting another user requires the manager's user to have Android's administrator role. The daemon rechecks that role before each cross-user automatic scan, and failures deny access rather than granting it.

Automatic rules are disabled by default. One saved rule per manager profile selects operation names and a user; saving replaces that profile's previous rule. While enabled, the privileged daemon checks that user’s installed packages every five seconds and sets applicable operations in new installs to Ignore. Existing packages form a baseline when a rule is saved or the daemon restarts. Package updates keep their original install time and are excluded, as is AndroidControl. Reinstalls with a new first-install time are eligible. No scans run while rules are disabled. The monitor can run after the screen closes while the daemon remains alive; this is not an immediate install-time barrier. Stopped-server installs and packages removed before a scan are not covered. Failed scans preserve the previous baseline and report an error; failed writes are logged and included in the last result rather than marked successful.

## Backup formats

Export uses the document picker and snapshots one package per Binder request. It covers package modes, not runtime grants, UID-wide modes, automatic rules, or pairing keys. The native format uses operation names resolved against the destination device:

```json
{"format":"androidcontrol-appops","version":2,"user":0,"time":0,
 "apps":[{"package":"example.app","ops":[{"name":"android:camera","mode":1}]}]}
```

Imports validate the format, 4 MiB size limit, package names, modes, unavailable operations, duplicates, and shared-switch conflicts before offering to apply. A package preflight occurs before any writes. Runtime failures can still yield a partial application; results report each failure and the count applied. Unlisted modes are preserved. The preview explicitly names the selected destination user.

AppOpsX v1 compatibility accepts its original ignored-operations format:

```json
{"v":1,"time":0,"size":1,"opbacks":[{"pkg":"example.app","ops":"26,27"}]}
```

The original fixed AOSP IDs (0–77) resolve against the running catalog and import as Ignore. Unknown/custom IDs are rejected. Same-mode shared-switch changes are collapsed; conflicting modes are rejected. Legacy backups have no user ID, so the selected destination is always shown before applying.

## Backend and identity

The Binder allows AndroidControl’s package UID or its own internal UID and accepts structured operations, not shell command text. Package UID lookups are fresh for each write. Optional rules use AtomicFile with ownership/type checks and owner-only access.

Package: `org.androidcontrol.app`. Server process: `androidcontrol_server`. Authorization file: `androidcontrol.json`. The pinned upstream API submodule is unchanged. Retained upstream Binder keys are protocol details, not AndroidControl installation IDs.
