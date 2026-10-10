# AC-002 document picker lifecycle fixture

`AppOpsPickerRecoveryTest` exercises the production `AppOpsActivity.kt`,
`AppOpsBackup.kt`, and `AppOpsNames.kt` under Robolectric 4.14.1 on Android API 30
and 35. Its 24 test methods execute once on each API level.

## What is real

- Android Activity creation, configuration recreation, save/stop/destroy/create
  transitions, and main-looper dispatch
- Android Bundle and Parcel serialization, including a saved pending document URI
- Intent creation and `startActivityForResult` recording
- ContentResolver input/output methods, backed only by registered in-memory streams
- Android AlertDialog item and button callbacks, including import confirmation
- The unmodified production backup parser and the Activity being tested

The fixture launches each document request through the production `backupMenu`
method and its actual dialog item callback. Private state is accessed reflectively
only to establish the initial selected profile/package or simulate a later
selection change. Since no external picker process exists in this fixture, the
protected `onActivityResult` callback is delivered reflectively with real Android
Intent and Uri values.

## What is replaced

`AppBarActivity`, Material's builder wrapper, RecyclerView, layout wrappers,
resources, and `UserHandleCompat` are minimal test-only stand-ins. Material's
wrapper delegates to an actual Android AlertDialog. `AppOpsClient` is a queued fake
which records operation, user ID, and package. Profile 0 and profile 10 return
different package inventories.

A test explicitly releases each queued backend operation on a background thread
and then idles the main looper. This preserves the Activity's UI handoff while
making the startup/result race reproducible. No fixture task calls Binder, reads
privileged files, accesses a live profile, or uses a connected device.

## Coverage

- Framework configuration recreation and independent Bundle/Parcel round trips
- Secondary-profile export and import after recreation
- Results arriving while recreated startup is still busy
- A successful queued export or import surviving another recreation
- Canceled/null callbacks, mismatched request codes, and duplicate callbacks
- Cancellation without added backend work or an app chooser, including restored startup
- Duplicate success/cancellation while an accepted URI is still queued
- Captured profile/package for export enumeration and import catalog reads
- Import confirmation and writes remaining pinned when the selected user changes
- Startup failure, output failure, malformed input, and fresh-picker retry
- Separate primary/secondary-profile export contents and daemon read/write calls
- No writes before import approval or after declining the import confirmation

This is controlled Android framework lifecycle evidence. It is not native device
instrumentation, a live Storage Access Framework/provider test, a privileged
AppOps/Binder test, or a check of the actual Material/RecyclerView rendering.
