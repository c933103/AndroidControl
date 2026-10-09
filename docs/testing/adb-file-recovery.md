# ADB file connection recovery (AC-001)

The regression was reproduced against `b7c3d000fefd67f8e227c11a5aeb47a2f599e9e3`: after an unanswered bind timed out, the next call waited on the old latch without issuing another bind. A delayed successful callback could still recover that baseline; the failure was the permanently unanswered case.

## Fix

Each attempt now owns its callback, service and latch. Timeout invalidates that attempt, wakes its waiters and allows a new bind. Late callbacks and old waiters cannot replace or reset a newer generation. Interruption retains the caller's interrupt flag without abandoning other waiters.

Cleanup uses `unbindUserService(..., false)` and never kills the remote service. The pinned Shizuku API (`a27f6e4151ba7b39965ca47edb2bf0aeed7102e5`) clears all registered callbacks for the service during non-removing unbind, so cleanup and replacement binding are serialized under the same lock. If a stopped backend rejects cleanup, stale-callback identity checks still apply.

Holding the lock during non-removing unbind does not wait for a client callback in the pinned implementation: `UserServiceManager.removeUserService` only unregisters the callback when `remove=false`; the client-side `ShizukuServiceConnection.connected/died` methods post callbacks to a main-thread Handler without awaiting their execution. The compatibility path only clears local registrations. This argument depends on the pinned API behavior and should be rechecked if that dependency changes.

## Repeatable checks

- `./gradlew :manager:testDebugUnitTest --tests '*RetryingServiceBindingTest'`: seven JVM tests for timeout/retry, stale connect/disconnect, shared callers, retiring old waiters, cleanup ordering, bind/cleanup failure, null/disconnected/dead services, and interruption. These run with the existing CI unit-test task.
- `KOTLIN_STDLIB=/path/to/kotlin-stdlib.jar bash tests/run-adb-file-client-checks.sh`: compiles the actual `AdbFileClient` plus its helper against explicitly fake Binder/Looper/Shizuku classes. Set `KOTLINC` if the Kotlin compiler is not on PATH. No device, service or privileged-file access occurs.
- To reproduce the baseline, extract the baseline `AdbFileClient.kt` into an otherwise empty directory and pass that directory to the same script. It must fail with `AC-001 reproduced: retry did not issue a fresh bind after timeout`; fixed source must pass.
- `bash tests/run-portrait-checks.sh`: existing host checks.

## Local evidence and limits

On 9 October 2026, the seven JUnit tests passed using cached Kotlin compiler/stdlib 1.9.24, JUnit 4.13.2 and Java 21. The actual-client probe failed on the baseline exactly as above and passed on fixed source, also checking main-thread/backend guards and retained replacement registration. All three existing portrait host checks passed. Negative controls were also detected: removing timeout retirement produced three failing tests; allowing abandoned callbacks to mutate current state produced four failing tests.

The local Gradle attempt stopped before running any tasks because the uncached Gradle 8.14 distribution could not be reached (`Network is unreachable`). Android builds, Android framework callback dispatch, emulator/device execution and live privileged service recovery were not locally tested. Host controls do not establish device coverage. The normal PR workflow remains the native-build/runtime gate; inspect its exact-head result separately.
