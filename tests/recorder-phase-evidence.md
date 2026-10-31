# Recorder initialization evidence contract

The decoder-only probe retains the same synthetic fixture, codec calls, 15 s
loop deadline, 180 s command bound and acceptance assertions. No warmup, retry,
watchdog override, permission change or alternative codec is introduced.

On failure, `decoderFailure.initializationCalls` contains create/configure/start
Java API call brackets in elapsed-realtime nanoseconds. `returned` distinguishes
normal return from an exception; -1 endpoints mean not attempted. Brackets exclude
checkpoint writes and codec-name/identity lookup. They still include scheduling
and JNI overhead: they are not native allocation duration or watchdog-fire time.
Existing lifecycle, loop CPU/wall and EOS/progress fields remain separate.

Before APK installation, `runtime-identity.json` records only emulator and installed
API/google_apis/x86_64 package revisions plus four allowlisted guest properties:
build fingerprint, incremental build, API and ABI. Missing identity or mismatched
API/ABI stops the probe rather than generating another unidentified result. This
records the current environment; it does not retroactively identify old guests,
pin package downloads, or prove that two runs have the same scheduling conditions.

Failure-only codec logs retain the same target PID, four codec tags and 300-line
bound, now with explicit threadtime and monotonic formatting (see the official
[logcat format documentation](https://developer.android.com/tools/logcat)). Logcat
and elapsedRealtime have different suspend-time semantics; do not subtract them
as if they were a single calibrated clock. Their individual ordering and intervals
remain useful without pretending to know exact native watchdog timestamps.

Host checks: `python3 tests/test-recorder-runtime-identity.py` and existing
`tests/recorder-readiness-fixture/check.py --gradle-lib <Gradle 8.14 lib>`.
Neither host controls nor decoder-only success is eleven-case integration, actual
call recording, service/Binder/SAF, physical-device or release acceptance.
