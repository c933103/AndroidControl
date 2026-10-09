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

Five cases cover already-ready attach, delayed attach with a live raw binder,
a delayed service callback, a bind exception, and interrupted readiness cleanup.
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
