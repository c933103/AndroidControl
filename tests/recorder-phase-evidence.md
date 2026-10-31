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

## Scheduling versus individual sample operations

The d1af190f identified guest reached initialization but timed out before EOS:
34 inputs, 29,696 frames, 15.354472 s wall versus 0.305201 s caller CPU.
Combined sample-read/queue used 7.156895 s; input-unavailable count was zero and
only four output-unavailable returns occurred. Thus simply adding the requested
10 ms dequeue waits cannot explain elapsed time. It does not establish why calls
were delayed or excuse the failed gate.

The next observation splits the existing sample group into five constant-size
count/total/max counters: getInputBuffer, readSampleData, sampleTime,
queueInputBuffer (including EOS), and advance. No call order, timeout argument,
retry or sample content changes. The original aggregate remains for comparison;
sub-operation totals must not be added to that aggregate (overlapping scopes).

Only `/proc/self/task/<current instrumentation TID>/schedstat` is read, twice,
outside the decoder loop. At most129 bytes are read; input longer than128 or not
exactly three nonnegative Long fields is rejected. No other PID, thread, proc
file or raw content is retained. Access failure remains `unavailable`, with no
permission retry or system changes. The retained CPU/runqueue-wait/timeslice
deltas follow [Linux scheduler-stat definitions](https://www.kernel.org/doc/html/latest/scheduler/sched-stats.html).
The separate sampling window brackets the loop and reads, so it is not precisely
the loop interval. Zero wait does not prove absence of scheduling delay: kernel
accounting may be disabled and host hypervisor scheduling is not fully represented
in guest runqueue counters. A large observed wait can support a guest runnable-wait
contribution; a small/unavailable value cannot alone attribute off-CPU time to IPC.
The prior a031 post-inputEOS observation remains a separate open question.
