# Portrait display work: recovered requirements and test scope

## Required behavior

Only `game.qualiarts.hololive.dreams.jp` is targeted. The game retains its landscape-only orientation policy. AndroidControl supplies a separate logical display with width less than height, puts the game in a resizable/freeform task filling that display, and presents the display in a fullscreen portrait host with touch forwarding.

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
2. Exit with Back and verify normal landscape launch, ordinary rotation preference and restored freeform support settings.
3. Exercise failed launch, immediate Back, host destruction and repeated start/stop. Verify no temporary portrait resize mode survives normal launch.

CI proves compilation and the parser regression cases. It cannot prove Unity rendering or OEM display/input behavior without the device.
