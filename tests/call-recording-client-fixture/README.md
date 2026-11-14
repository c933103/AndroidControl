# Synthetic scrcpy stream framing

This fixture compiles **production ScrcpyClient.kt**, feeds synthetic scrcpy bytes
through real Java file descriptors, and checks its terminal callback. The Android
ParcelFileDescriptor wrapper, codec lookup and logger are controlled stubs. It
uses no device, Binder, call, private audio, codec decoding or permission change.

```sh
python3 tests/call-recording-client-fixture/check.py --gradle-lib /path/to/gradle-8.14/lib
```

Eight cases cover clean packet-boundary EOF, truncated codec/header/payload,
a complete audio frame followed by a partial next header, a listener IOException
without a message, oversize payload rejection and missing initial configuration.
Exactly one terminal callback is required. Only clean EOF may have a null error.
A partially successful recording followed by malformed bytes must still fail.

## Original-source sensitivity

Production predecessor `84742c2987a423b5c62b149dd820f4de3548821d` treats any
EOFException as clean termination and forwards nullable exception messages.
Save its ScrcpyClient.kt outside the checkout, then run:

```sh
python3 tests/call-recording-client-fixture/check.py --gradle-lib /path/to/gradle-8.14/lib --source /path/to/predecessor-client.kt --expect-baseline-failure
```

On 2026-10-29, this reproduced exactly five failures: partial-codec,
partial-header, partial-payload, complete-then-partial and listener-no-message.
Clean boundary, oversize rejection and missing-config rejection remained passing.
The correction recognizes clean EOF only before the first byte of a packet header;
partial framing and exceptions without messages produce non-null errors. Existing
codec/config/size checks are retained. Normal CI does not download old source.

These are parser and descriptor contract checks, not decoded-container quality,
installed recorder/Binder/SAF lifecycle or physical-handset call-audio acceptance.
EOF at a complete packet boundary cannot alone prove that a remote producer ended
intentionally; the service still distinguishes requested Stop from unexpected EOF.
