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
`--expect-baseline-failure`. Exactly eleven notification scenarios fail on that
source; the fixed source passes all fifteen. Android CI also runs this fixture.

This models the documented [channel/app/group notification contract](https://developer.android.com/reference/android/app/NotificationChannel#getImportance()).
It supplements Android CI and does not establish installed notification, Binder,
SAF, muxer or telephony behavior. The original device and release gates remain.
