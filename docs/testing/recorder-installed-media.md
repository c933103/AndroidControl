# Installed recorder media verification

The independent `Recorder synthetic media` API33/API35 workflow runs only
`RecorderMediaInstrumentation` through `tests/run-recorder-media-checks.sh`.
It installs debug/test APKs without granting permissions, uses Linux software emulation on API33 and native macOS virtualization on API35
without host KVM permission changes, and performs no root, settings, privileged
service, telephone, microphone or foreground recording service operations.
The original general runtime harness and workflow remain unchanged. The build-only `-PrecorderMediaTests=true` option selects the synthetic runner;
the general regression runner remains the default. The generated instrumentation
declaration exists only in the test APK; production components,
signing configuration, permissions and service exposure remain unchanged.

## Eleven required cases per API

- AAC/M4A and Opus/OGG, each twice: generate one second of 440Hz stereo PCM in
  memory; encode with the installed Android MediaCodec; frame the resulting bytes
  as scrcpy packets; send them through a real ParcelFileDescriptor pipe into the
  production ScrcpyClient and ScrcpyAudioMuxer; reopen with MediaExtractor and
  decode with MediaCodec.
- Assert a single matching 48kHz stereo track, strictly increasing container
  timestamps, a 0.75–1.25 second timestamp span, 0.75–1.30 seconds of decoded PCM,
  and recovered tone amplitude/energy. Bursty queued delivery must not compress
  the capture timeline. Record actual codec names, decoded frame counts, signal
  metrics, output sizes and SHA-256 values—not just a constructor success.
- For both codecs: a partial header after successfully written media must report
  truncation, an empty configured session must not claim finalized audio, and a
  mismatched codec must be rejected before any media is written.
- An AAC muxer must reject a non-seekable pipe output.
- Each pipe drain includes explicit producer-stop closure; reader/writer threads
  must terminate. Repeated close and closed output-descriptor checks run on the
  actual Android implementations, including negative paths.

No missing codec or failure is silently skipped. Any failed case fails this
independent job. General runtime checks are neither removed nor weakened.

## Evidence

`recorder-media-api-33` / `recorder-media-api-35` artifacts contain
`instrumentation.txt`, `report.json` and four generated-tone M4A/OGG outputs.
These are synthetic test signals, never people's call audio. Only round-trip
outputs are exported; partial/empty negative files remain test-cache data.
The report records failures and already completed cases even if a later case
fails. Evidence is bound to the exact workflow head/merge tree; old artifacts do
not establish a new head. No real-user logs or app data are collected.

Implementation validation is pending until the first exact-head hosted run; local
Android SDK compilation alone is not an emulator pass.

## Protocol / API references and boundaries

- [scrcpy v4.0 Streamer](https://github.com/Genymobile/scrcpy/blob/v4.0/server/src/main/java/com/genymobile/scrcpy/device/Streamer.java): big-endian packet metadata and the OpusHead subset of Android's AOPUSHDR codec configuration. The fixture independently emits this wire layout; it does not change the pinned production server.
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec): codec-config buffers, PCM input, output buffers and end-of-stream semantics.
- [Android MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer) and [MediaExtractor](https://developer.android.com/reference/android/media/MediaExtractor): actual container writing and reopened sample metadata.

This closes only the exercised installed parser/muxer/codec/descriptor paths.
It does **not** establish foreground-service notification/permission revocation,
Binder owner death, SAF provider failure behavior, background auto-start, handset
telephony capture, two-sided audio, routing/SIM/OEM compatibility, original-signer
update continuity, or distribution/privacy/license release acceptance. Those
existing gates remain separate and open. Generated PCM bypasses microphone and
telephony hardware completely.

## First installed evidence and production correction

Head eb5578025d, push run40809433969/API33, successfully ran both real AAC
roundtrips (47104 decoded frames, 960000us timestamp span, recovered tone amplitude
5723) and AAC truncated/empty/mismatch checks. Opus then failed the unchanged
strict timestamp assertion: Android returned `[0, 0, 13500, 33500, ...]`.
The preserved OGG artifact has a zero first audio-page granule; ffprobe independently
reported first timestamps -26500us and -6500us. This was not counted as acceptance.

[AOSP Android13 OggWriter](https://android.googlesource.com/platform/frameworks/av/+/android-13.0.0_r1/media/libstagefright/OggWriter.cpp)
uses the supplied timestamp as a packet-end granule. [RFC7845 §4.5](https://www.rfc-editor.org/rfc/rfc7845.html#section-4.5)
requires the first non-EOS audio-page granule to include its decoded samples.
The muxer now adds Opus packet duration derived independently from the TOC/count
fields in [RFC6716 §3](https://www.rfc-editor.org/rfc/rfc6716.html#section-3).
AAC timing remains unchanged. Seven new host cases reproduce the old failure and
verify all TOC duration families, counts and invalid headers. The installed
monotonicity, duration, tone and cleanup assertions are not relaxed.
Corrected installed output remains pending until its own exact-head run passes.

Codex subsequently found that a reset/duplicate capture PTS together with a
shorter Opus packet could regress the new end granule. Opus starts are now rebased
to at least the previous packet end (with state cleared on close/reuse); normal
forward capture gaps remain. Three additional tests cover reset, duplicate and
reuse; both reset/duplicate regressions reproduce on1229b419. Installed assertions
remain unchanged and corrected-head results are still required.

The1229b419/API33 run reached and passed all10 codec roundtrip/negative cases,
then demonstrated that AAC MediaMuxer construction accepts a pipe. The muxer now
checks seekability before native allocation, retaining the service's existing
earlier provider check. The installed negative assertion is unchanged. Separate
API35 process-crash outcomes before a report remain unresolved, not accepted as
environment-only failures. Runner stage markers and, only on failure, a200-line
crash-buffer read filtered to the synthetic target-app logger PID provide bounded
diagnostics; no general logs, accounts, actual audio or other app data are saved.
Artifact bytes are validated so remote cat error text cannot masquerade as media.

## API35 runner isolation after terminal Linux failures

At fd6e2f89, both push/PR API33 jobs passed all11 installed cases and all four
existing App/AndroidCI runs passed. Both API35 software-emulator jobs installed
the APKs but reported Process crashed before the runner's first stage marker.
Bounded target-app crash buffers had no retained record; root cause remains
uncertain. Earlier API35 also reached an AAC encoder timeout, and separate runs
failed emulator unlock/package-manager startup. These failures are retained, not
reclassified as passes.

Only the synthetic API35 matrix entry now uses standard `macos-15-intel` on the existing GitHub Actions destination.
Logs do not explicitly confirm HVF/hypervisor acceleration; it is unverified. API33 and both original workflows are unchanged.
Repository visibility was verified public on29October; [GitHub's standard-runner
reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
lists this standard Intel runner as available and free for public repositories.
No larger/paid runner, new service/contract, credentials or access change is used.
The same15s codec deadlines,180s instrumentation timeout,1200s boot limit and45min
job bound remain. Python implements the unchanged outer timeout portably; no GNU
coreutils installation is required. All audio assertions and failure diagnostics
remain intact. Native-runner acceptance is pending its own exact-head results.

## API35 decoder lifecycle diagnosis (not acceptance)

On5f1dfea/nativeAPI35 push job123261445874, the first AAC roundtrip passed with
`c2.android.aac.encoder`/`c2.android.aac.decoder`; the second freshly created decoder
failed configure with Released state before any input was queued. Both retained
AAC outputs contain46 identical encoded packet payloads (sequence hash
2e9c74c6ec1183e66aefc143d5f423c7d136658a347e47d7685b4ae34d43ebf4).
Independent ffmpeg decoding yielded47104 frames and RMS4138.13 for each; that is
not proof of AndroidAPI35 compatibility and does not erase the failure.

The fixture now checkpoints exact codec name/MIME/instance identity, create /
configure / start / stop / release requests and completions, encoded payload
hash and output file hashes. Failure-only codec diagnostics retain at most300
log lines from the instrumentation-reported target-app PID and only MediaCodec,
CCodec, CCodecConfig and Codec2Client tags. No other process logs are collected.
There is no blanket retry, codec skip, changed lifecycle ordering or relaxed
assertion/deadline. The purpose is to distinguish fixture lifecycle misuse from
codec-service failure or malformed output using exact installed evidence.


## Package-scoped process exit diagnosis

The60c962 runs died at distinct stages: API35 PR after AAC encoder start, API35
push during encoder creation, and API33 push before runner markers with system
crash. No production muxing was reached in those API35 runs. This does not prove
malformed output or codec-service death. Original host logs confirm emulator
37.2.12.0/build16428233, API35 google_apis x86_64,2560 MiB RAM and requested2 cores
reduced to1; graphics are SwiftShader, CPU acceleration remains unverified.

On failure only, the runner-reported PID and wall-clock start milliseconds bind
an ApplicationExitInfo query to this synthetic process. The exact shell command
is `dumpsys activity exit-info org.androidcontrol.app`; there is no global dump.
[AOSP ActivityManagerService](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java)
routes that package argument to
[AppExitInfoTracker](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/am/AppExitInfoTracker.java),
which selects only its package map. API33/API35 ApplicationExitInfo dump formats
were checked against official source. [Android's API reference](https://developer.android.com/reference/android/app/ApplicationExitInfo)
defines reason/status/timestamp; status is not itself proof of a codec defect.

The diagnostic retains only exact package/process/PID records between the
runner start and observed device time, with a maximum300s window. Device timezone
is read, not changed. Missing PID/start markers, invalid clocks, command failure,
or unavailable history do not become a guessed cause. Each shell read has a10s
timeout; input over128 KiB is rejected, output contains at most4 numeric/enum
records. No descriptions, traces, raw dumps or unrelated packages are archived.
Thirty-five synthetic controls cover matching identity/time, unrelated and stale
records, timezone, bounds, command scope and unavailable history. Existing crash
filter and strict11-case failure exits remain unchanged. This is diagnosis only,
not a recorder fix or acceptance claim.


## Explicit API35 acceleration capability boundary

The48e2aa API35 PR's AAC creation took6582ms; target CCodec logs explicitly report
allocation watchdog timeout, fatal UNKNOWN_ERROR while INITIALIZED, then
Released-state configure rejection. Fixture release only followed the exception.
[Android15 CCodec](https://android.googlesource.com/platform/frameworks/av/+/android-15.0.0_r1/media/codec2/sfplugin/CCodec.cpp)
sets1500ms allocation watchdog and releases on expiry. This supports that failure
path, not codec-service death or malformed media. The matching USER REQUESTED /
FORCE_STOP exit record follows caught failure and is consistent with normal
instrumentation finish cleanup, not owner cancellation. Its push run passed5 AAC
cases then failed the unchanged15s Opus encoder deadline. No timeout is patched.

Only API35 now runs read-only `emulator -accel-check` after SDK installation and
before launch, recording bounded capability output. The actual launch explicitly
uses `-accel on`: [official emulator documentation](https://developer.android.com/studio/run/emulator-commandline)
says this refuses startup when acceleration is unavailable. macOS uses HVF per
[official acceleration documentation](https://developer.android.com/studio/run/emulator-acceleration).
The same standard runner/image/ABI remains; no OS settings or access are changed.

The action catches prelaunch-hook errors, so the media script independently
requires a successful complete capability receipt and requestedMode=on before
installing/running tests. Missing/partial/failed receipts cannot silently permit
software fallback or media acceptance. The check uses15s only for the read-only
capability command, at most4096 bytes retained; original media/workflow deadlines
are untouched.17 synthetic controls cover complete success, unavailable/partial
output and fail-closed gate behavior. API33 remains explicitly software and
unchanged. A capability pass establishes configuration, not performance or codec
recovery; actual11-case media results remain required.

## Exact target ANR stack continuation

At e630e0a, both API35 receipts verified HVF15.7 and explicit `-accel on`, yet
both failed with exact target PID/time-bound reason6 ANR during AAC encoding.
This did not cure runtime and is distinct from the earlier CCodec allocation
watchdog. API35 cases completed:0; no encoded/container output was produced.

Thread inspection found no direct media-on-main dispatch: onCreate emits its
marker and calls start; onStart runs synchronous media checks. Official
[Instrumentation source](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/app/Instrumentation.java)
shows start creates the separate InstrumentationThread that invokes onStart.
Application startup remains unchanged. A blocked-main explanation needs a trace,
not an assumption from the synchronous media loop.

After original command status, crash/codec/exit evidence and media artifacts are
saved, a matching ANR can invoke the same test runner once in diagnostic-only
mode (60s bound, no media rerun). It calls getHistoricalProcessExitReasons for
its own exact package/PID, then requires exact exit timestamp and ANR reason.
[ActivityManager source](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/android/app/ActivityManager.java)
permits own-UID queries without DUMP; no permission is added.
[ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)
provides getTraceInputStream, which may be null if unavailable/overwritten.
AOSP AppExitInfoTracker stores the target ANR trace range rather than requiring
access to the global ANR directory.

No raw trace is written. A512KiB in-memory read bound rejects oversized input;
the exact PID block and exact Cmd line must match. Only main and this runner's
InstrumentationThread headers/stack/lock metadata survive, maximum2 threads,
100 lines each,1024 characters per line. Other processes/threads, free text and
headers are excluded. The report preserves exact PID/time/reason/status binding.
Eleven pure Kotlin selector controls and11 request-identity/time/reason controls
cover positive and unrelated/stale/oversized inputs. Original media-command
exitCode/timedOut is now recorded before diagnostics, with the same180s limit;
the shell still exits with the original failure. Missing trace is not acceptance.
