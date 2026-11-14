# Recorder service contract fixture

Compile the complete actual `CallRecordingService.kt` against explicit Android,
notification, provider, Binder and scrcpy fixtures. Service worker and reader
threads are real JVM threads; no device, call, private audio, privileged process,
real provider or notification/permission setting is used or changed.

```sh
python3 tests/call-recording-service-fixture/check.py \
  --gradle-lib /path/to/gradle-8.14/lib
```

Fifteen notification cases exercise app blocking on API31/API35, blocked channel
or group, missing channel/group, failed state query, missing Android-13 notification
permission, visibility lost while preparing output, allowed API31/API35/group
paths, and app/channel/group blocking during capture. They execute the real
service entry point, startup, polling guard and cleanup. Initial failures must
never begin capture; preparation failure closes/deletes only the new unused output;
visibility loss stops the synthetic producer and closes descriptors without deleting
an already-active output. Existing consent and SAF preferences are fixture inputs,
not modified by this code.

To reproduce the review baseline, supply `--source /path/to/CallRecordingService.kt`
from commit `ece3e6923d1f211286c07f462b9d1e15fb34bacc` and
`--suite notifications --expect-baseline-failure`. Exactly eleven notification scenarios fail on that
source; the fixed source passes all fifteen. Android CI also runs this fixture.

This models the documented [channel/app/group notification contract](https://developer.android.com/reference/android/app/NotificationChannel#getImportance()).
It supplements Android CI and does not establish installed notification, Binder,
SAF, muxer or telephony behavior. The original device and release gates remain.

## AAC/SAF output contract

Seven additional cases cover a seekable provider, a provider whose `w` output
streams while `rw` supports seeking, unsupported `rw`, a provider that incorrectly
returns a streaming descriptor for `rw`, a null open result, a producer-start
failure after allocation, and unchanged Opus streaming output. Failed new outputs
must be closed/deleted; the privileged producer must not start with an unusable
AAC descriptor. Successful outputs must be retained. The muxer stub explicitly
rejects streaming AAC descriptors, and the client reports that controlled failure.

Run `--suite storage` to isolate these cases. Supply the source at notification-fix
commit `cad480adca5db858cfd5f0d5cfe6bc0f6c30d8ba` plus `--suite storage
--expect-baseline-failure` to reproduce three streaming-provider failures. This
negative control permits the legacy output-mode string only so seekable-provider,
null-open and producer-failure controls retain their prior behavior; fixed-source
runs also require the exact `rw`/`w` mode contract. Full runs execute all 22 cases.

The [ContentResolver contract](https://developer.android.com/reference/android/content/ContentResolver#openFileDescriptor(android.net.Uri,%20java.lang.String))
permits pipes/sockets for exclusive `w`; `rw` implies random-access disk storage.
The fixture models descriptors and does not generate or validate MP4/OGG media.
Provider deletion remains best-effort as before; no staging/copy/commit protocol or
private temporary audio files are added by this fix.

## Stop-time stream-error regression (2026-10-29)

The suite now also runs `drain-error` and `drain-eof` (24 total service cases).
A controlled client writes an initial frame, waits on an explicit producer-stop
latch, then reports either an I/O error or clean EOF while the real service joins
the reader. Error must produce ERROR with the original message; clean EOF must
still produce IDLE. Both require producer stop, descriptor cleanup, and retention
of the previously active output. This does not claim a real MediaMuxer/SAF pass.

Against production predecessor `b850b21f93b150ff851dac5257bf25546788bf32`,
`--source /path/to/predecessor-service.kt --suite drain --expect-baseline-failure`
reproduces exactly `drain-error`: state incorrectly becomes IDLE. `drain-eof`
passes. The corrected production service passes both plus all existing cases.
The new per-session atomic error field retains the first non-null stream failure
independently from the stopping flag; joining the reader precedes the final error
check. No stop/drain timeout, caller check, permission or acceptance gate changes.
