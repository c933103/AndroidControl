# ADB file connection recovery (AC-001)

The regression was reproduced against `b7c3d000fefd67f8e227c11a5aeb47a2f599e9e3`: after an unanswered bind timed out, the next call waited on the old latch without issuing another bind. A delayed successful callback could still recover that baseline; the failure was the permanently unanswered case.

## Fix

Each attempt now owns its callback, service and latch. Timeout invalidates that attempt, wakes its waiters and starts asynchronous cleanup. A retiring gate prevents replacement registration until cleanup finishes; callers waiting for cleanup still honor their own deadline. Both binding and cleanup run on one serial worker, outside the state lock, so a stalled backend cannot trap the timeout path or accumulate replacement work. Late callbacks and old waiters cannot replace or reset a newer generation. Interruption retains the caller's interrupt flag without abandoning other waiters.

Cleanup uses `unbindUserService(..., false)` and never kills the remote service. The pinned Shizuku API (`a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`) clears all registered callbacks for the service during non-removing unbind, so the explicit retiring gate and serial worker preserve cleanup-before-rebind ordering. If a stopped backend rejects cleanup, stale-callback identity checks still apply.

The original patch performed cleanup synchronously under the lock. Codex correctly identified that a stalled `removeUserService` transaction could then defeat the timeout. That path has been replaced by the asynchronous retiring gate. Controlled tests hold both bind and cleanup indefinitely until released by the test, verify timeout returns, verify 100 retries add no replacement work during retirement, then release the backend and confirm recovery. No timeout claim relies on a stopped backend throwing promptly.

A further source check found that `IBinder.pingBinder()` itself performs synchronous IPC. Liveness checks now use `IBinder.isBinderAlive`, including the initial Shizuku binder guard, so the state-lock predicate reads cached Binder death state. This is not a guarantee that the remote process is responsive; later file operations can still fail or block independently of connection recovery. The distinction is visible in [AOSP BpBinder](https://android.googlesource.com/platform/frameworks/native/+/a7ab5c731ec5a399a4b7f594f5e28cc3b95f22e5/libs/binder/BpBinder.cpp) and the [Android IBinder API](https://developer.android.com/reference/android/os/IBinder).

## Repeatable checks

- `./gradlew :manager:testDebugUnitTest --tests '*RetryingServiceBindingTest'`: nine JVM tests for timeout/retry, stale connect/disconnect, shared callers, retiring old waiters, cleanup ordering, bind/cleanup failure, null/disconnected/dead services, interruption, stalled bind, and stalled cleanup. These run with the existing CI unit-test task.
- `KOTLIN_STDLIB=/path/to/kotlin-stdlib.jar bash tests/run-adb-file-client-checks.sh`: compiles the actual `AdbFileClient` plus its helper against explicitly fake Binder/Looper/Shizuku classes. Set `KOTLINC` if the Kotlin compiler is not on PATH. No device, service or privileged-file access occurs.
- Set `AC001_FORBID_PING=true` when running the actual-client script to make both backend and service pings fail immediately; the fixed client must still pass all checks.
- To reproduce the baseline, extract the baseline `AdbFileClient.kt` into an otherwise empty directory and pass that directory to the same script. It must fail with `AC-001 reproduced: retry did not issue a fresh bind after timeout`; fixed source must pass.
- `bash tests/run-portrait-checks.sh`: existing host checks.

## Local evidence and limits

On 9 October 2026, the nine JUnit tests passed using cached Kotlin compiler/stdlib 1.9.24, JUnit 4.13.2 and Java 21. The actual-client probe failed on the baseline exactly as above and passed on fixed source, also checking main-thread/backend guards and retained replacement registration. All three existing portrait host checks passed. The client probe also passed with every backend/service `pingBinder` call configured to throw; the previous ping-based adapter failed that negative control before binding. Negative controls were also detected: removing timeout retirement produced five failing tests; allowing abandoned callbacks to mutate current state produced four failing tests.

The local Gradle attempt stopped before running any tasks because the uncached Gradle 8.14 distribution could not be reached (`Network is unreachable`). Android builds, Android framework callback dispatch, emulator/device execution and live privileged service recovery were not locally tested. Host controls do not establish device coverage. The normal PR workflow remains the native-build/runtime gate; inspect its exact-head result separately. Initial head `a05ddce18ffeab3e976e017500383ea99ec30ed3` passed Android CI run `37876755967`, including the native/runtime jobs, before this asynchronous cleanup revision; that is not a pass for the revised head.
