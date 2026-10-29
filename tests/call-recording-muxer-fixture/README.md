# Recorder muxer session and capture-timestamp regression

Run the **production** `ScrcpyAudioMuxer.kt` with controlled Android media stubs:

```sh
python3 tests/call-recording-muxer-fixture/check.py --gradle-lib /path/to/gradle-8.14/lib
```

Thirteen cases cover sample success, missing config, no samples, stop/write
failures, repeat close, duplicate initialization, closed-instance reuse,
backlogged capture timestamps, a forward source gap, duplicate/backward source
timestamps and timestamp reset on reuse. A fresh session must not inherit saved
success or sample count; duplicate initialization must not reset an active one.

Capture timestamps are normalized to zero. A queued sequence of ten packets with
20,000 microsecond capture spacing must retain its 180,000 microsecond span,
regardless of how quickly the reader drains it. Forward source gaps remain intact.
A duplicate/backward source timestamp advances one microsecond and subsequent
capture deltas resume from there; it must not clamp an entire reset segment.

## One-time original-source sensitivity

Save the indicated predecessor muxer file outside the checkout and use:

```sh
# bb7b4caa66ba15a2a8f36e6425292e4f665a8ab4: exactly two session failures
python3 tests/call-recording-muxer-fixture/check.py --gradle-lib /path/to/gradle-8.14/lib --source /path/to/bb7-muxer.kt --suite session --expect-baseline-failure
# b850b21f93b150ff851dac5257bf25546788bf32: exactly four timestamp failures
python3 tests/call-recording-muxer-fixture/check.py --gradle-lib /path/to/gradle-8.14/lib --source /path/to/b850-muxer.kt --suite timestamps --expect-baseline-failure
```

Normal CI tests current behavior and does not download historical source.
Local production-source execution on 2026-10-29 reproduced both failure sets;
the backlogged predecessor span was 455 microseconds instead of 180,000.
Corrected source passes all thirteen cases using Gradle 8.14's Kotlin 2.0.21
compiler, JDK 21 and Java 17 bytecode target.

Android media classes are stubs: this tests real production state/timestamp
logic, **not** Android MediaMuxer, encoded-media validity, installed Binder/SAF
behavior or handset call audio. The current service constructs a fresh muxer per
session, so the reuse correction is a muxer-contract fix, not a demonstrated
service reuse path. Capture timing on actual telephony sources remains subject
to the existing physical-device acceptance matrix. Installed, physical-device,
integration and release gates remain separate.
