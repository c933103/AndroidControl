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

## Standard Linux/KVM resource baseline, without permission changes

The macOS emulator source forces one vCPU on hosts with fewer than six logical
cores; standard Intel macOS runners have four. Changing the requested core count
alone does not remove that guard. See [emulator source](https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android-qemu2-glue/main.cpp)
and [standard runner specifications](https://docs.github.com/en/actions/reference/runners/github-hosted-runners).

Historical head71e0bdff selected only on the exact diagnostic branch the existing standard ubuntu-latest
runner family, API35/google_apis/x86_64, explicit acceleration on and at least two
verified online guest CPUs. The normal three-entry integration matrix is unchanged.
An early preflight opens existing `/dev/kvm` and queries KVM_GET_API_VERSION; no VM
is created by that preflight. Inaccessible KVM stops before build/media and archives
only error number, host architecture/OS/logical CPU count and runner image version.
No sudo, chmod, group membership, udev rule, module installation or fallback is used.
A successful preflight is not media acceptance: emulator -accel-check and guest
identity/CPU-count gates must also pass before the unchanged synthetic probe.

[GitHub's KVM announcement](https://github.blog/changelog/2024-04-02-github-actions-hardware-accelerated-android-virtualization-now-available/)
documents permission changes commonly needed on Linux; this experiment deliberately
does not make them. If existing access is unavailable, that is a concrete runner
capability boundary, not permission to bypass it or relabel software emulation.
The purpose is an existing-access multicore baseline, not a claim that host CPU
resources have already been proved causal. All original media deadlines remain.

## Single-core HVF initialization observation (separate from pending KVM comparison)

The diagnostic branch now explicitly selects macos-15-intel/HVF for one changed
observation, with artifact suffix decoder-only-single-core-init. This is not an
automatic fallback and cannot satisfy the separately pending Linux multicore
comparison. The existing KVM preflight remains Linux-only; no access change is made.
The normal three-entry integration matrix and all media deadlines are unchanged.

The new initializationScheduler window starts before create and ends in finally
after start returns or initialization fails. Unlike the direct initializationCalls
brackets it includes intervening name lookup/checkpoint I/O and its own bounded
schedstat reads. callerScheduler remains a separate decode-loop window.
callerStates contains only numeric own-thread priority and own-process importance
before create and on failure. Unsupported calls, invalid priority ranges and
negative importance are unavailable, not guessed. Diagnostic failures are contained
so the original initialization exception is preserved. No priority is modified.
Guest runqueue accounting may be unavailable/disabled and excludes important host
and codec-worker effects; none of these observations alone establishes root cause.

Host controls cover observed/unavailable/malformed snapshots, separate windows,
throwing observation functions, original exception identity, placement outside the
unchanged 15-second loop, explicit single-core lane and unchanged normal matrix.
Older allocation, pre-EOS slow progress and a031 post-inputEOS failures remain open.

## Normal coverage and success-path observations

After the single-core probe PASS at591432a2 (which retained no successful-path
timing snapshot), the workflow restores the exact existing normal matrix for this
branch too: API33 manager on Ubuntu's original software-emulation lane, API35
manager and API35 control on macOS/HVF. These run all eleven original cases;
there is no decoder-only branch override. API33 is existing normal coverage,
not an alternate execution of the blocked Linux/KVM resource comparison.

Each successful decode case now includes decodeObservations from the same bounded
snapshot used on failure: initialization calls/scheduler, before-create numeric
own-state, separate loop timings/scheduler and progress counters. Snapshot creation
occurs after measured windows and the original duration/signal assertions, with
no file I/O or additional sampling inside the windows. On-failure own-state remains
failure-only. Diagnostic publication exceptions are contained in both paths.

A normal pass would establish only these installed synthetic media paths, not
recording service/Binder/SAF failure, telephony/handset/privacy or release acceptance.
Original failures remain evidence, and15s remains the existing test guard rather
than an asserted owner/product SLA. No permission change or retry-until-pass.

## Controlled official emulator build comparison

The prior API33 full-pass fd6e2f89 jobs123252635076/123252613915 used37.2.12
(build16428233), the same Ubuntu image20261025.353.1,2 requested CPUs and2048MB.
Current37.2.13 API33 completed11 body assertions but missed the whole180s command
budget. Earlier API35 failures already occurred on37.2.12, so version drift is not
a proved cause or remedy. No observed CPU/RAM regression justifies changing them.
Concurrent jobs are distinct hosted VMs; no host-load measurement proves contention.

The exact diagnostic branch pins the existing action's supported emulator-build
input to16428233, downloaded by that action from Google's official repository.
Other branches retain its previous default. Before APK install the identity gate
requires37.2.12 package revision AND executable37.2.12.0/build16428233, plus exactly
the baseline API33 rev17/build12342917/fingerprint/2CPUs or API35 rev9/build12960925/
fingerprint/1CPU. A changed guest fails closed as a confounded comparison, never
silently standing in for the requested experiment. All three normal modes/cases,
AVD options, readiness checks and15s/180s/1200s/45min limits remain unchanged.

The host command receipt now records monotonic start/end/duration and wall-clock
start/end (clock adjustments remain visible), keeping its original timeout/exit.
Instrumentation stage statuses add guest elapsed/wall clocks and media-returned/
finish-requested boundaries outside measured media loops. Different host/guest
clocks are not presumed synchronized; these observations are not performance SLA
evidence. A body report collected after host timeout still cannot pass the lane.

## Build-process residency comparison

The installed-media CI build now requests --no-daemon and Kotlin in-process,
keeping the same tasks/signing/source. This uses Gradle's single-use process when
JVM arguments require it, which exits after the build; Kotlin does not create a
separate compiler daemon. Before emulator launch a bounded jps class inventory
requires zero Gradle/Kotlin daemons and a positive jps self-check. No process is
killed, no raw command lines/PIDs/inventory are archived, and unavailable inventory
fails closed. Numeric CPU/memory/load values describe that instant, not causality.
No claim follows that previously retained Java processes were consuming CPU or
causing memory pressure. All matrix/runner/emulator/guest/media gates are unchanged.

The secondary executable-version receipt now distinguishes timeout, nonzero exit,
oversize and unmatched version format/build, with numeric elapsed/byte counts only.
This does not bypass its gate or repair media; it prevents repeating the previous
unclassified API33 failure. Original comparison failures remain durable evidence.

## Headless executable-version probe repair, API33-only recovery

The5314319 API33 probe conclusively exited127 in15.568ms while the actual
headless emulator booted. The secondary query had omitted -no-window; AOSP launcher
source selects a different headless QEMU executable when that flag is present.
Verification now uses emulator -no-window -version to match actual launch, retaining
the exact build/revision/guest gate and10s bound. On nonzero exit, only an allowlisted
loader-library basename may be retained, never raw stderr or paths. The precise
former missing dependency is not asserted without evidence.

Only this exact diagnostic branch selects the EXISTING API33 manager integration
row to test the repair; all normal branches keep the original three rows. It must
complete its original180s invocation, final marker and all11 cases to pass.
No unchanged API35 rerun is launched; previous API35 failures remain open.
