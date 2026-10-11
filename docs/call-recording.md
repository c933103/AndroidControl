# Call recording in AndroidControl (experimental)

## Scope and design

The feature integrates a **modified subset** of [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) (GPL-3.0 with additional Section 7 terms), not an unmodified second APK. It uses scrcpy-server v4.0, the existing AndroidControl Shizuku server/user service, and a foreground audio writer. No second Shizuku provider or manager process is installed.

**Android 12+ (API 31+)** required for capture. The remainder of AndroidControl continues supporting its earlier Android versions.

The interface is reachable by a **Call recording** card on the main screen and offers:
- A consent/law disclaimer that must be acknowledged, with a direct fork attribution and original project link.
- Destination folder chosen through Android's Storage Access Framework (SAF), with persisted read/write grants; recordings kept locally.
- Opus/OGG (default) and AAC/M4A, and voice-call vs. compatibility source selection.
- Manually starting/stopping a recording **during** an active cellular call.
- Optional automatic recording on an `OFFHOOK` state broadcast; disabled by default.
- A saved-file selector that opens recordings in the user's installed audio player.
- Foreground notification while capturing with an explicit Stop action. On Android 13+, grant notification permission so recording stays visible in the notification shade.

The app does not collect phone numbers or contacts, implement voice/audio upload, or ask for `READ_CALL_LOG` or `READ_CONTACTS`. Upstream's contact exclusion rules, recording overlays, per-contact naming, debugging switches and in-call UI are **not yet ported**.

## Use

1. Start the AndroidControl Shizuku service (wireless ADB or other existing startup method) and grant appropriate existing manager permissions.
2. On Android 12+, open Call recording and acknowledge the jurisdiction-specific consent obligations.
3. Choose a writable, persistable folder. Recordings receive UTC timestamps without names/numbers.
4. Grant **Phone state** permission to detect a connected cellular call. On Android 13+, also grant **Notifications** for visible recording status.
5. Place/receive a normal cellular call and tap **Start recording current call**. Finish via **Stop recording and save** or the notification, or end the call.
6. Optionally turn on **Automatically record connected cellular calls**, understanding that Android may block background foreground-service launches. After reboot, restart AndroidControl's privileged service.

This software does not announce recording to other participants. Confirm applicable local consent laws before recording, and do not assume auto-recording is lawful everywhere.

## Security, storage and resilience

- Existing app identity: `org.androidcontrol.app`; no extra app, provider or package.
- Privileged AIDL methods are scoped by AndroidControl app UID, owner Binder token and minimal allowlists (source, codec, bitrate, verified scrcpy jar path). Owner Binder death stops the recorder.
- Server v4.0's SHA-256 is pinned to `84924bd564a1eb6089c872c7521f968058977f91f5ff02514a8c74aff3210f3a`. The build fails if download verification fails; the runtime also validates before execution. The server is stored in shared app external files storage to permit privileged shell access.
- Foreground service and ongoing notification ensure that the user can stop a session. The service tries to drain buffered audio and close `MediaMuxer` before finalizing.
- Audio is written through user-granted SAF URIs. AndroidControl does not upload recordings; **a user-selected cloud-backed storage provider may sync them**, so choose a local folder for strictly local storage. No telephone number or contact label is included.
- A `PHONE_STATE` receiver only starts an automatic session if the user explicitly enabled it, consent was acknowledged and a destination exists.
- The service also polls active call state every 2.5 seconds and closes recording after two consecutive non-active readings, protecting against missed hang-up broadcasts or revoked phone-state permission. If consent is withdrawn, privileged capture is intended to shut down. Validate these behaviors through controlled tests before deployment.
- Risks to investigate include a time-of-check/time-of-use window between server-jar validation and shell execution, concurrent stop/start ordering, user-service reconnects, destination-provider failures and OS-specific foreground restrictions.

## Validation and release gate

**Not yet verified for production use.** Host/CI build and regression coverage, plus physical-device audio checks, must be completed before this PR is merged or shipped.

- [ ] Debug and release builds compile and package licence notices.
- [ ] Existing orientation/AppOps, updates and signing regressions remain green.
- [ ] Android 12/13/14/15/16 service start, permissions, binder death, cancellation, notification and saved-file finalization tested where accessible.
- [ ] Controlled injected scrcpy streams: codec mismatch, invalid/oversize packets, EOF, early hang-up, corrupt asset, stopped server, SAF lost permission/full storage and simulated mid-call restart. Check the added finalize-success flag refuses to claim success when the muxer never wrote audio or failed to stop.
- [ ] Android background auto-start works or shows accurate non-functional status; do not silently claim an automatic recording occurred.
- [ ] Audio from **both sides**, Bluetooth/headset, VoLTE, Wi-Fi calling and multiple-SIM scenarios tested on real devices.
- [ ] GPL Section 7 fork/branding notice and corresponding source requirements reviewed; upstream scrcpy Apache-2.0 attribution/NOTICE packaged.
- [ ] No sensitive call contents or phone identifiers are logged or transmitted.

Source provenance: [upstream ShizuCallRecorder revision 41d1dc7](https://github.com/kitsumed/ShizuCallRecorder/tree/41d1dc7be09ec4d1ca648ff62d9cf1aff6e0cd8a) and [scrcpy v4.0](https://github.com/Genymobile/scrcpy/releases/tag/v4.0).

## Build

`./gradlew :manager:testDebugUnitTest :manager:assembleDebug` checks the host unit suite and creates an installable APK. The build task `prepareCallRecorderScrcpyServer` obtains a fixed release asset over HTTPS, validates SHA-256 and places it in the APK's `assets/scrcpy-server`. Do not disable these integrity checks in CI.
