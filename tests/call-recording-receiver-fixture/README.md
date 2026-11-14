# Direct Boot receiver host fixture

Compile and execute the actual `PhoneStateCallReceiver.kt` with explicit Android,
preferences and service stubs. The credential-preference constructor throws while
locked, modeling Android's credential-encrypted storage restriction. No call,
private audio, service, permissions or device settings are used or changed.

```sh
python3 tests/call-recording-receiver-fixture/check.py \
  --gradle-lib /path/to/gradle-8.14/lib
```

Fourteen cases cover locked/missing-user-manager OFFHOOK, normal unlocked startup,
automatic opt-out, unconfigured destination, already-active recorder, revoked phone
permission, refused background FGS launch, pre-Android-12 devices, unrelated
broadcasts, RINGING, absent state, and IDLE stop/no-op while locked. Assertions count
preference reads, start attempts and stop attempts. A locked OFFHOOK must return
without opening preferences; IDLE remains able to stop an active recorder.

Baseline sensitivity:

```sh
git show f1cb16bc3e225b93753b86a615609395ac517c9d:manager/src/main/java/org/androidcontrol/app/recording/PhoneStateCallReceiver.kt > /tmp/PhoneStateCallReceiver.before.kt
python3 tests/call-recording-receiver-fixture/check.py \
  --gradle-lib /path/to/gradle-8.14/lib \
  --source /tmp/PhoneStateCallReceiver.before.kt --expect-baseline-failure
```

The baseline must fail only the locked/missing-manager credential-access cases.
Android CI runs the fixed-source fixture. This host contract fixture supplements
Android builds; it does not prove a real reboot/unlock/broadcast/FGS sequence or
replace the existing installed-device recorder release gates. Consent and the
selected destination stay in credential-protected storage.
