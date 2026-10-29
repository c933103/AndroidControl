# Installed recorder media verification

The independent `Recorder synthetic media` API33/API35 workflow runs only
`RecorderMediaInstrumentation` through `tests/run-recorder-media-checks.sh`.
It installs debug/test APKs without granting permissions, uses software emulation
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
