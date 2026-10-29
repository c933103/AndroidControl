# Recorder muxer session-state regression

Run the **production** `ScrcpyAudioMuxer.kt` with controlled Android media stubs:

```sh
python3 tests/call-recording-muxer-fixture/check.py --gradle-lib /path/to/gradle-8.14/lib
```

Nine cases cover sample success, missing config, no samples, stop/write failures,
repeat close, duplicate initialization and two closed-instance reuse boundaries.
A fresh session must not inherit either the saved-result flag or sample count.
The duplicate-initialize case ensures an active session is not accidentally reset.

One-time sensitivity control against PR26 predecessor `bb7b4caa66ba15a2a8f36e6425292e4f665a8ab4`:
provide its muxer file with `--source /path/to/predecessor.kt --expect-baseline-failure`.
Exactly `reuse-clears-result` and `reuse-empty-session` must fail. Normal CI tests
current behavior only and does not download historical source.

Local validation on 2026-10-29: production predecessor passed seven cases and
failed those two; corrected source passed all nine, using Gradle 8.14's Kotlin
2.0.21 compiler and JDK 17 bytecode target. Android classes are stubs; this tests
real production state transitions, **not** Android MediaMuxer, media decodability,
installed Binder/SAF behavior or handset call audio. The current recording service
constructs a new muxer per session; this fixes the reusable muxer object's contract,
not a demonstrated false-success path in that service. All existing installed,
physical-device, integration and release gates remain open.
