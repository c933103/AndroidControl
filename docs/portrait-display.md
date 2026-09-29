# Portrait display work: recovered requirements and test scope

## Required behavior

The target is configurable in the app; `game.qualiarts.hololive.dreams.jp` is the initial default. Only the selected package is targeted. The game retains its landscape-only orientation policy. AndroidControl supplies a separate logical display with width less than height, puts the game in a resizable/freeform task filling that display, and presents the display in a portrait host with touch forwarding and visible navigation/exit controls.

The one-time, device-wide legacy recovery has already completed. Keep its UI retired and do not repeat its package scan as part of normal launch or restore. Normal changes to compatibility flags are confined to the target package. Temporary global freeform support settings must retain and restore their previous values.

## Evidence and previous attempts

The recovered discussion and game dump reported Unity sizes of 1662×712 and 712×305 on Sony XQ-DQ72, Android 13 / API 33. These are landscape sizes with nearly identical aspect ratios. The earlier discussion inferred that the game was still receiving a landscape-shaped rendering surface; those logs do not establish that a newer build successfully changed Unity rendering.

- PR #7 separated legacy recovery from normal target-only operations.
- PR #8 archived legacy recovery controls.
- PR #9 is an unmerged alternative using a shell-owned trusted virtual display.
- PR #10 added Android 13 task/freeform fallback handling.
- PR #11 added activity-bounds verification and compatibility capability checks.
- PR #12 introduced the app-owned public virtual display and fullscreen host currently in master.
- PR #13 retains landscape orientation and verifies portrait bounds independently of the orientation enum.

This is a continuation of those attempts, not a fresh orientation-flag experiment.

## Session lifecycle in PR #13

Write a durable session record before launch. Launch with NEW_TASK | MULTIPLE_TASK and record the selected task before changing its resize mode. Verify the actual logical display dimensions, task membership on that display, full task bounds, and the exact target ActivityRecord's current app bounds. Never infer success from another task, the host, a stale reported configuration, or a landscape/portrait enum alone.

On Back, launch failure, host process death or display disappearance, remove the temporary session task, restore compatibility and global support settings, and retain failed restoration records for the next attempt. Task removal prevents its portrait-only resize mode being reused by a later normal launch. It does not clear game data. Back relaunches the game normally after successful restoration. A late stop from an old display cannot stop a newer session.

## Validation

`bash tests/run-portrait-checks.sh` checks exact task identity, neighbouring activity isolation, stale configuration rejection, invalid dimensions and portrait bounds with a landscape enum. Android CI also compiles the complete APK.

The r1203 device test exposed a parser bug: desktop OpenJDK accepted an unescaped closing brace that Android's ICU regex engine rejected. The literal is now escaped. CI additionally runs the production parser through `app_process` on an Android 13 emulator, first reproducing the exact old pattern failure and then running the fixed parser's regression cases (`tests/run-portrait-android-checks.sh`). Desktop-only checks are insufficient for Android runtime compatibility.

On-device acceptance is still required on the Sony Android 13 device:

1. Start the mode and verify the actual game scene fills the intended portrait container, with correct touch coordinates. Save the status and game log if Unity continues rendering a wide scene.
2. Exit with Close and verify normal landscape launch, ordinary rotation preference and restored freeform support settings.
3. Exercise failed launch, immediate Back, host destruction and repeated start/stop. Verify no temporary portrait resize mode survives normal launch.

CI proves compilation and the parser regression cases. It cannot prove Unity rendering or OEM display/input behavior without the device.

## Broader requirements retained from the original discussion

AndroidControl is a self-contained Shizuku-derived control toolbox, including its own pairing, server, terminal, root support and app authorization. There is no separate LADB installation in the user's setup. A stable APK signing certificate and the stored ADB pairing key are distinct identities; verifying the first does not test preservation of the second.

The original discussion repeatedly identified the need for AndroidControl's own app identity while retaining its Shizuku functionality. The portrait-only audit missed this outstanding requirement. The user deferred identity changes on 2026-09-28, then authorized AndroidControl display-name and icon branding on 2026-09-29 while explicitly retaining the existing package. Keep applicationId, protocol identifiers, signing identity and pairing storage unchanged.

Current device reports: pairing must be repeated after updates, and the game appears vertically before technical text covers it and interaction stops about two seconds later. Do not treat an earlier parser fix or successful APK compilation as verification of either behavior. The precise new on-device error text has not yet been obtained.

The Android 13 runtime regression job installs the previous APK, creates its ADB identity, updates in place, and compares the identity using real app storage/Keystore. It also exercises the production portrait host against a landscape-only fixture and checks touch forwarding. The fixture is only installed in a fresh CI emulator and is never bundled into AndroidControl. It does not reproduce Unity or Sony-specific behavior.

That test reproduced the delayed failure: task bounds filled the portrait display, but the parser rejected every real Android 13 activity header (`Hist  #`, with two spaces). It consequently reported geometry unavailable and tore the game down. The earlier parser tests had used invented headers with one space and missed this. The parser now accepts whitespace between header tokens, and the regression cases include the observed Android 13 format. Successful launch hides the host status immediately instead of briefly covering the game with technical diagnostics.

Pairing key loading now preserves existing ciphertext on read/decryption failure, serializes first-key creation and commits the encrypted key before returning it. The previous `apply()` could leave a just-created identity unsaved when the process was killed; the upgrade fixture explicitly flushes that old baseline to establish a valid saved identity, and separate fresh-key tests check the new production write without such a flush. A passing identity test does not establish why the user's specific update required pairing again.

For a permanently missing or invalid encryption key, the error UI offers a separately confirmed reset of wireless pairing only. Ordinary failures never reset credentials. Notification pairing routes key errors through its failure handler and provides a link to that recovery UI. Native tests cover the missing-alias error, error notification, retained ciphertext before reset and preservation of other settings after explicit reset.

## Configurable target and independent system control

The home screen has separate target-display and system-wide sections. Change target package validates package syntax, rejects AndroidControl itself and packages without a launcher, and saves the choice in the existing app preferences. The host captures the selection at launch. The privileged daemon persists that session’s package before changing compatibility/task state, and restores the previous session before accepting another package. Ledgers from older builds without a package owner belong to the original Target app default. Editing the selection during a session affects the next launch; Close still restores and reopens the session’s original app.

The system-wide button only changes display 0 user rotation, fixed-to-user rotation and ignore-orientation-request. It does not launch apps or apply per-package overrides. The button is enabled when the build advertises the modern query/set commands; a blanket Android-version threshold would misidentify OEM capabilities. A durable snapshot retains the previous rotation mode, remembered angle, fixed rotation policy and ignore-orientation policy. Restore replays and verifies that snapshot, retaining it if anything fails. This mode does not promise to reflow every app’s internal UI.

Target display launch no longer runs the historical display-0 auto-cleanup. Target and system-wide settings can therefore coexist; closing a target session does not disable the independent system-wide setting. The retired legacy recovery UI and broad package scan stay retired.

Native regression coverage includes a second installed landscape-only package, validation and saved selection across manager restart, target launch and touch forwarding while system-wide mode is enabled, editing the next target before Close, and restoring both locked and automatic main-display rotation. These run on Android 13 and Android 15 alongside the existing pairing and portrait-parser regressions.

If an older build or another controller already forced portrait without a saved snapshot, the UI explicitly offers “Clear existing system portrait lock”. This returns to Android’s default fixed-orientation policy and auto-rotate; it does not invent a previous preference. Target mode never clears this state automatically.


## External screens and lock recovery (PR #17)

The previous host always treated Back as session teardown, even when a child dialog was open. Its app-owned display shared the physical display group, hid system navigation, and detached its surface without revoking input readiness. New Android 13 and 15 fixtures reproduced teardown during an external ActivityResult flow and an obscured/blank frame after lock/unlock.

The current host uses a shell-owned trusted virtual display, with a separate display group on Android 13+, and independent focus on Android 15+. This carries forward the relevant PR #9 design with the correct `com.android.shell` UID attribution, while retaining the newer configurable target, task ownership ledgers, exact portrait-bounds verification and independent global rotation controller. No secure-display/capture flag is enabled.

Back now goes to the target so its own dialogs can dismiss normally. Close explicitly ends/restores the session. Phone display moves the live task back to the native display without restarting the app. System navigation and these controls remain visible outside the rendered surface. Closing the host does not wait for a Binder cleanup call; restoration remains with the daemon.

If a different app becomes the top activity on the virtual display (including Play purchase screens), the daemon moves its complete task/root and result chain to display 0 and returns the tasks to fullscreen. This deliberately leaves the session on the native display after the transaction: automatically restarting the game to re-enter portrait could lose the result. The host exits after handoff. A durable handoff journal prevents interrupted restoration from deleting a preserved task, and compatibility reset uses `--no-kill`. This may change the phone’s visible orientation according to the app’s normal policy; it does not change the separate system portrait override.

On surface loss or backgrounding, input is disabled and the display surface is detached. Resume attaches the new surface before enabling input. On older Android versions, focus is returned to the visible physical host after forwarded input; the service does not bring it over Home or another native screen.

Runtime coverage includes an in-app dialog dismissed through forwarded Back, a separate-UID secure checkout fixture with its bottom button used on display 0, a preserved game PID and ActivityResult, resumed touch input, lock/unlock frame and touch recovery, and the ability to leave with Home. These fixtures do not perform a purchase or establish the exact behavior of Google Play, Unity, or the Sony OEM build. Those still require the user's device test.
