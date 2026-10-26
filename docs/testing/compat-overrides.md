# Target compatibility prior-state restoration

The portrait session snapshots the selected package's raw per-change compatibility overrides before applying temporary settings. Restoration preserves three different original states: explicitly enabled, explicitly disabled, or no explicit override. The current effective value is not a substitute for this snapshot.

A package-owned, versioned journal stores each original state before its first mutation. Repeated apply attempts retain that first snapshot; process death, ambiguous command failures, and warning output keep the recovery record. Restoration uses enable, disable, or reset as appropriate, applies `--no-kill` to every mode during a process-preserving handoff, and retains complete failed entries for retry. An unavailable change or package retains the existing best-effort cleanup behavior.

Old ID-only journals are explicitly read as legacy reset records. They never stored original values, so an upgrade cannot reconstruct them. Failed legacy resets remain marked as legacy records when rewritten. Invalid or wrong-owner journals are retained and block mutation.

## Raw-state contract

The parser follows pinned AOSP implementations:

- Android 13/API33, commit [0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6](https://github.com/aosp-mirror/platform_frameworks_base/tree/0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6): [CompatChange](https://github.com/aosp-mirror/platform_frameworks_base/blob/0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6/services/core/java/com/android/server/compat/CompatChange.java), [PackageOverride](https://github.com/aosp-mirror/platform_frameworks_base/blob/0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6/core/java/android/app/compat/PackageOverride.java), [ActivityManagerShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6/services/core/java/com/android/server/am/ActivityManagerShellCommand.java), and [AtomicFile](https://github.com/aosp-mirror/platform_frameworks_base/blob/0d3ff311e6e80dee7fe88a2a2cfa272ce231c3c6/core/java/android/util/AtomicFile.java).
- Android 15/API35, commit [4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3](https://github.com/aosp-mirror/platform_frameworks_base/tree/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3): [CompatChange](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/services/core/java/com/android/server/compat/CompatChange.java) and [AtomicFile](https://github.com/aosp-mirror/platform_frameworks_base/blob/4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3/core/java/android/util/AtomicFile.java).

`dumpsys platform_compat` emits `ChangeId(...)` records. `rawOverrides` contains the stored per-package values; `packageOverrides` contains evaluated values. Raw entries may be `true`, `false`, or a version-ranged `[minimum,maximum,boolean]` value. Version-ranged target entries are left untouched because the enable/disable command cannot faithfully recreate their range. Unknown IDs are skipped. Malformed, incomplete, unreadable, or unsupported snapshot formats fail before compatibility mutation; missing evidence is never treated as an absent override. Handcrafted fixtures follow these source contracts and are not device captures.

`AtomicFile.finishWrite` can suppress sync/rename failures. The adapter explicitly syncs the stream and checks committed base bytes plus `.new`/`.bak` sidecars before allowing a command. Verification does not call `openRead`, which could consume recovery evidence. Reading also rejects a leftover backup if `openRead` silently failed to recover it.

## Host checks

Run with the repository's JDK 21:

```sh
bash tests/run-portrait-checks.sh
```

This includes:

- 72 production parser/controller tests: all five active IDs, enabled/disabled/default round trips, same-value overrides, repeated apply, restart/death, ambiguous results and post-mutation warnings, failed restoration/retry, unknown changes/packages, legacy journals, malformed records, package matching, raw/evaluated disagreement, ranges, and storage failures.
- 7 production journal-adapter tests with a controlled `android.util.AtomicFile` dependency: successful writes, silent rename/backup/drop/corruption failures, failed backup recovery with stream closure and retained evidence, and controller commit/delete failure boundaries.
- Existing portrait geometry, web-launch, and journal-migration host checks.

The small AtomicFile dependency is used only by this host test compilation. It is outside Android source sets, executes no device commands, and is not packaged in the app.

A one-time sensitivity control replaces the production restore mode with unconditional `reset`. The suite must fail its explicit-state round trip. This is a behavior control, not a permanent historical-source hash gate.

## Validation receipt and remaining gates

Prepared against AndroidControl `72334bc52feea25bd148515e5494f6aed95bee78`. On 2026-10-16, production Java host tests passed with installed OpenJDK 21.0.12.1, using its `jdk.compiler` module (the environment has no separate `javac` executable). The complete host script passed through an equivalent temporary compiler launcher. The unconditional-reset sensitivity mutation failed the expected explicit-state assertion. Independent source review rechecked recovery and warning-output repairs and reran 72 controller plus 7 adapter tests successfully.

The earlier baseline reproduction used a source-derived Python command model; its 20 modeled losses and 11 controls are distinct from these executed production Java tests. The generated baseline Kotlin harness was not compiled or run.

Kotlin service integration, the full Android build, and installed API33/API35 checks remain required. The host suite does not establish actual Android AtomicFile behavior, shell permissions, OEM dump compatibility, process-preserving restoration, or device behavior. Existing device/release holds remain in effect.
