# Runtime instrumentation service readiness

This host fixture compiles the actual `controlService()` helper extracted from
`RuntimeRegressionInstrumentation.kt`, using explicit Android/Shizuku stubs.
It checks the actual helper against a modeled callback contract. It does not
build or execute the application or contact a device/service; real Shizuku and
emulator confirmation remains part of Android CI.

The helper must wait for Shizuku's application-attach callback, rather than only
a live raw binder. A raw binder can become visible before the client API has
finished attaching. A bind exception on the main thread must reach the
instrumentation thread so its normal failure capture can report the result.

Ten cases cover already-ready attach, delayed attach with a live raw binder,
attach racing sticky registration, delayed readiness-flag publication,
callback delivery before the readiness flag,
a delayed service callback, a bind exception, death before bind,
the unchanged readiness timeout,
and interrupted readiness cleanup.
The bound helper retains the original 10-second readiness and 15-second service
connection deadlines, and does not retry failed service binds.

## Run

Use Python 3, JDK 21 and an existing Gradle 8.14 distribution's `lib` directory:

```sh
python3 tests/runtime-control-fixture/check.py --gradle-lib /path/to/gradle-8.14/lib
```

The fixture uses the distribution's Kotlin 2.0.21 compiler and downloads nothing.
Android CI runs it after the ordinary Gradle build has installed that distribution.
To verify sensitivity against the previous helper, save that earlier source and run:

```sh
python3 tests/runtime-control-fixture/check.py --gradle-lib /path/to/gradle-8.14/lib \
  --source /path/to/earlier/RuntimeRegressionInstrumentation.kt --expect-baseline-failure
```

The sensitivity control requires failures in both delayed attach and bind exception
handling. It must not be used as the positive CI gate. Stubbed host results supplement
existing API33/API35 instrumentation; they do not establish device or backend behavior.

## Pinned sticky-registration ordering

The stub follows [Shizuku API a27f6e4](https://github.com/RikkaApps/Shizuku-API/blob/a27f6e4151ba7b39965ca47edb2bf0aeed7102e5/api/src/main/java/rikka/shizuku/Shizuku.java#L271-L315):
sticky readiness is read before adding the listener, while notification visits
listeners before setting the flag. One controlled case completes attachment in
that gap. A single sticky registration misses the callback and times out; the
helper re-registers only the listener within the same 10-second deadline. At most
one registration is retained, and cleanup runs on success, interruption or timeout.
Service binding is never retried.

The old fixture assumed add-before-check ordering and did not cover this race.
Its earlier passing result did not establish compatibility with the pinned API's
actual registration ordering. To reproduce the corrected sensitivity control,
use the source at commit b84332d8dfe4be1447fb4596a035bdd7ebaeac82 with
`--expect-missed-notification` instead of `--expect-baseline-failure`.

The model captures controlled interleavings, not the JVM/Android memory model or
all Binder lifecycle races. In the pinned API the service and attach payload are
set before callback delivery, even though the readiness flag is set afterward.
A later Binder death can still make binding fail; that error must propagate to
instrumentation without retry rather than being treated as a successful bind.

These deadlines bound the readiness and connection waits; they do not bound
openManager, main-thread scheduling, or synchronous Binder IPC. The pinned
readiness flag is non-volatile and published outside the listener lock, so this
is bounded missed-notification recovery, not a formal loss-free memory-model
handshake. Missing a usable callback still fails closed at the original timeout.
