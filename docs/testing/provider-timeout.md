# Provider attachment timeout

`ShizukuManagerProvider` waits at most five seconds for the Shizuku binder
callback to complete a requested user-service attachment. `CountDownLatch.await`
returns false on timeout; it does not throw `TimeoutException`. Previously this
returned an empty bundle and retained the callback listener.

The provider now returns null on timeout, always removes the listener, and
restores the calling thread's interrupt flag after interruption. A one-shot
claim prevents duplicate or already-queued late callbacks from starting another
attachment. An attachment that started before timeout may still finish; the
provider cannot cancel an in-flight Binder call and does not claim to undo it.
Its completion cannot replace the null result already returned to the caller.

Run `python3 tests/provider-timeout-fixture/check.py --gradle-lib DIR`, where
DIR is the existing Gradle8.14 lib directory. The fixture compiles the complete
production class, replacing only Android/Shizuku imports with host doubles.
It uses real JVM latches/threads and the unchanged five-second production wait.
Six cases cover success/duplicates, attachment failure, timeout/late callback,
interruption, registration failure and an in-flight attachment across timeout.
The original source fails five cases, including both timeout cases; the fix
passes all six. The App workflow also compiles the real Android application and
runs this fixture using its existing compiler dependencies.

This fixes a provider reply/listener lifecycle defect. It does not establish
the cause of the separate API35 instrumentation-start race, recorder codec
failure, or PassengerCount automatic restart failure. Installed service and
full-product acceptance remain separate.
