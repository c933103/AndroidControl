# Recorder startup ownership host fixture

Compile the actual `ShellAudioPipeline.kt` against explicit Android descriptor,
local-socket and process fixtures, plus the Kotlin/coroutine jars already bundled
in Gradle 8.14. No application process, privileged executable, device, call or
private audio is used. The single output byte is synthetic.

```sh
python3 tests/call-recording-lifecycle-fixture/check.py \
  --gradle-lib /path/to/gradle-8.14/lib
```

Six separately executed cases cover hash rejection, pipe creation failure,
server-socket construction failure, argument construction failure, process start
failure, and successful ownership transfer/stop. Failures repeat four times on
the same pipeline. Assertions check closure of both unreturned pipe ends, listening
and accepted sockets, no remaining blocked `accept`, and cleared session resource
references. The successful case preserves the caller-owned read descriptor, rejects
a duplicate start, drains a synthetic final byte, destroys the fake process and
checks repeated stop is harmless. Process-start failure waits until the real
coroutine reaches the controlled blocking accept, rather than merely inspecting text.

Baseline sensitivity:

```sh
git show f1cb16bc3e225b93753b86a615609395ac517c9d:manager/src/main/java/org/androidcontrol/app/recording/services/shell/ShellAudioPipeline.kt > /tmp/ShellAudioPipeline.before.kt
python3 tests/call-recording-lifecycle-fixture/check.py \
  --gradle-lib /path/to/gradle-8.14/lib \
  --source /tmp/ShellAudioPipeline.before.kt --expect-baseline-failure
```

The baseline must fail only the socket/arguments/process partial-allocation cases.
Android CI runs the fixed-source fixture after the ordinary build. These modeled
resource checks supplement Android builds and installed tests; they do not measure
real file-descriptor counts or establish Android socket, Binder, telephony or audio
behavior on a device. Existing recorder release gates remain open.
