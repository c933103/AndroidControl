package org.androidcontrol.app.appops

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * AC-002 controlled Activity lifecycle regression fixture.
 *
 * The production Activity, backup parser, Android Activity/Intent/Bundle/Parcel,
 * ContentResolver and AlertDialog lifecycle/callbacks execute under Robolectric.
 * Only the app shell, display adapter and privileged daemon are stand-ins.
 * Streams are registered in memory; this fixture never uses Binder, a device,
 * live profiles, or privileged files. It is not native device instrumentation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppOpsPickerRecoveryTest {
    private val controllers = mutableListOf<ActivityController<AppOpsActivity>>()
    private var serial = 0

    @Before fun reset() {
        AppOpsClient.reset()
        MaterialAlertDialogBuilder.dialogs.clear()
    }

    @After fun tearDown() {
        MaterialAlertDialogBuilder.dialogs.toList().forEach { if (it.isShowing) it.dismiss() }
        controllers.asReversed().forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        AppOpsClient.queue.clear()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun field(activity: AppOpsActivity, name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun setField(activity: AppOpsActivity, name: String, value: Any?) =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun invoke(activity: AppOpsActivity, name: String) =
        activity.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun status(activity: AppOpsActivity) = (field(activity, "status") as TextView).text.toString()
    private fun newActivity(state: Bundle? = null): ActivityController<AppOpsActivity> =
        Robolectric.buildActivity(AppOpsActivity::class.java).also {
            controllers.add(it)
            it.create(state).start().resume().visible()
        }

    private fun step() {
        assertFalse("A backend task must be queued", AppOpsClient.queue.isEmpty())
        val runnable = AppOpsClient.queue.removeFirst()
        // Keep the actual Activity.runOnUiThread handoff; no synchronous production shortcut.
        val thread = Thread(runnable, "controlled-appops-fixture")
        thread.start()
        thread.join(5000)
        assertFalse("Controlled backend task timed out", thread.isAlive)
        idle()
    }
    private fun drain() {
        var count = 0
        while (AppOpsClient.queue.isNotEmpty()) {
            check(++count < 40) { "Unexpected work loop" }
            step()
        }
        idle()
    }
    private fun ready(user: Int = 10): ActivityController<AppOpsActivity> {
        val controller = newActivity()
        drain()
        val activity = controller.get()
        setField(activity, "user", user)
        setField(activity, "selectedPackage", if (user == 10) "other.app" else "primary.app")
        invoke(activity, "loadApps")
        drain()
        assertEquals(user, field(activity, "user"))
        assertFalse(field(activity, "busy") as Boolean)
        MaterialAlertDialogBuilder.dialogs.toList().forEach { it.dismiss() }
        MaterialAlertDialogBuilder.dialogs.clear()
        AppOpsClient.calls.clear()
        return controller
    }
    private fun launch(activity: AppOpsActivity, code: Int) {
        // Use the unchanged entry route in baseline and candidate, then a real list callback.
        val before = MaterialAlertDialogBuilder.dialogs.size
        invoke(activity, "backupMenu")
        assertEquals("Backup menu opened", before + 1, MaterialAlertDialogBuilder.dialogs.size)
        val dialog = MaterialAlertDialogBuilder.dialogs.last()
        val index = if (code == 501) 0 else 1
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
        idle()
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotNull("Document picker launched", started)
        assertEquals(code, started.requestCode)
        assertEquals(if (code == 501) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        assertTrue(started.intent.hasCategory(Intent.CATEGORY_OPENABLE))
        if (code == 501) {
            assertEquals("application/json", started.intent.type)
            assertEquals("androidcontrol-appops-user-${field(activity, "user")}.json", started.intent.getStringExtra(Intent.EXTRA_TITLE))
        }
    }
    private fun parcelCopy(state: Bundle): Bundle {
        val written = Parcel.obtain()
        val restored = Parcel.obtain()
        return try {
            written.writeBundle(state)
            val bytes = written.marshall()
            restored.unmarshall(bytes, 0, bytes.size)
            restored.setDataPosition(0)
            restored.readBundle(AppOpsActivity::class.java.classLoader)!!
        } finally { written.recycle(); restored.recycle() }
    }
    private fun recreate(controller: ActivityController<AppOpsActivity>): ActivityController<AppOpsActivity> {
        val state = Bundle()
        controller.saveInstanceState(state).pause().stop().destroy()
        val restored = parcelCopy(state)
        val next = newActivity(restored)
        assertNotSame(controller.get(), next.get())
        return next
    }
    private fun uri(label: String) = Uri.parse("content://appops.fixture/$label/${++serial}")
    private fun output(activity: AppOpsActivity, uri: Uri, stream: OutputStream = ByteArrayOutputStream()): OutputStream {
        shadowOf(activity.contentResolver).registerOutputStream(uri, stream)
        return stream
    }
    private fun input(activity: AppOpsActivity, uri: Uri, text: String = backup(10)) {
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
    }
    private fun backup(user: Int): String = """{"format":"androidcontrol-appops","version":2,"user":$user,"apps":[{"package":"${if (user == 10) "other.app" else "primary.app"}","ops":[{"name":"android:camera","mode":1}]}]}"""
    @Suppress("DEPRECATION")
    private fun result(activity: AppOpsActivity, code: Int, uri: Uri? = null, result: Int = Activity.RESULT_OK, nullIntent: Boolean = false) {
        activity.javaClass.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            .apply { isAccessible = true }
            .invoke(activity, code, result, if (nullIntent) null else Intent().setData(uri))
        idle()
    }
    private fun confirmation(): AlertDialog = MaterialAlertDialogBuilder.dialogs.lastOrNull {
        it.isShowing && it.findViewById<TextView>(android.R.id.message)?.text?.contains("Apply 1 changes") == true
    } ?: throw AssertionError("Expected an import confirmation; visible messages: " + MaterialAlertDialogBuilder.dialogs.filter { it.isShowing }.map { it.findViewById<TextView>(android.R.id.message)?.text })
    private fun assertConfirmUser(dialog: AlertDialog, user: Int) {
        assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.toString().contains("to user $user?"))
        assertTrue("No writes before explicit import confirmation", AppOpsClient.calls.none { it.operation == "set" })
    }
    private fun accept(dialog: AlertDialog) { dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); idle() }
    private fun assertExport(stream: ByteArrayOutputStream, user: Int) {
        assertTrue("Export must write bytes", stream.size() > 0)
        val json = JSONObject(stream.toString("UTF-8"))
        assertEquals("androidcontrol-appops", json.getString("format"))
        assertEquals(user, json.getInt("user"))
        assertEquals(1, json.getJSONArray("apps").length())
        assertEquals(if (user == 10) "other.app" else "primary.app", json.getJSONArray("apps").getJSONObject(0).getString("package"))
        assertEquals(1, json.getJSONArray("apps").getJSONObject(0).getJSONArray("ops").length())
    }

    @Test fun controllerConfigurationRecreationRestoresProfileAndSelectedPackage() {
        val first = ready()
        val old = first.get()
        launch(old, 501)
        first.recreate()
        assertNotSame(old, first.get())
        assertEquals("Selected secondary profile survives framework recreation", 10, field(first.get(), "user"))
        assertEquals("Selected package survives framework recreation", "other.app", field(first.get(), "selectedPackage"))
        drain()
        val target = uri("framework-recreated-export")
        val stream = output(first.get(), target) as ByteArrayOutputStream
        result(first.get(), 501, target)
        drain()
        assertExport(stream, 10)
    }

    @Test fun exportAfterParcelRecreationUsesCapturedSecondaryProfileAndFreshEnumeration() {
        val first = ready()
        launch(first.get(), 501)
        val next = recreate(first)
        drain()
        val target = uri("secondary-export")
        val stream = output(next.get(), target) as ByteArrayOutputStream
        AppOpsClient.calls.clear()
        result(next.get(), 501, target)
        drain()
        assertExport(stream, 10)
        assertEquals(listOf(AppOpsClient.Call("apps", 10, null), AppOpsClient.Call("snapshot", 10, "other.app")), AppOpsClient.calls)
    }

    @Test fun importAfterParcelRecreationReadsAndWritesCapturedSecondaryProfile() {
        val first = ready()
        launch(first.get(), 502)
        val next = recreate(first)
        drain()
        val source = uri("secondary-import")
        input(next.get(), source)
        AppOpsClient.calls.clear()
        result(next.get(), 502, source)
        drain()
        assertEquals(listOf(AppOpsClient.Call("ops", 10, "other.app")), AppOpsClient.calls)
        val dialog = confirmation()
        assertConfirmUser(dialog, 10)
        accept(dialog)
        drain()
        assertEquals(listOf(AppOpsClient.Call("set", 10, "other.app")), AppOpsClient.calls.filter { it.operation == "set" })
        assertTrue(AppOpsClient.calls.all { it.user == 10 })
    }

    @Test fun exportResultDuringRecreatedStartupIsDeferredThenRunsExactlyOnce() {
        val first = ready()
        launch(first.get(), 501)
        val next = recreate(first)
        assertTrue(field(next.get(), "busy") as Boolean)
        val target = uri("busy-export")
        val stream = output(next.get(), target) as ByteArrayOutputStream
        result(next.get(), 501, target)
        assertEquals(0, stream.size())
        assertEquals(1, AppOpsClient.queue.size)
        drain()
        assertExport(stream, 10)
        assertEquals(1, AppOpsClient.calls.count { it.operation == "snapshot" })
    }

    @Test fun importResultDuringRecreatedStartupWaitsThenShowsOneCapturedUserConfirmation() {
        val first = ready()
        launch(first.get(), 502)
        val next = recreate(first)
        val source = uri("busy-import")
        input(next.get(), source)
        result(next.get(), 502, source)
        assertTrue(field(next.get(), "busy") as Boolean)
        assertTrue(AppOpsClient.calls.none { it.operation == "set" })
        drain()
        assertConfirmUser(confirmation(), 10)
        assertEquals(1, MaterialAlertDialogBuilder.dialogs.count { it.findViewById<TextView>(android.R.id.message)?.text?.contains("Apply 1 changes") == true })
    }

    @Test fun alreadyQueuedExportUriSurvivesAnotherParcelRecreation() {
        val first = ready()
        launch(first.get(), 501)
        val second = recreate(first)
        val target = uri("twice-recreated-export")
        val stream = output(second.get(), target) as ByteArrayOutputStream
        result(second.get(), 501, target)
        val third = recreate(second)
        output(third.get(), target, stream)
        drain()
        assertExport(stream, 10)
        assertEquals(1, AppOpsClient.calls.count { it.operation == "snapshot" })
    }

    @Test fun alreadyQueuedImportUriSurvivesAnotherParcelRecreation() {
        val first = ready()
        launch(first.get(), 502)
        val second = recreate(first)
        val source = uri("twice-recreated-import")
        input(second.get(), source)
        result(second.get(), 502, source)
        val third = recreate(second)
        input(third.get(), source)
        drain()
        val dialog = confirmation()
        assertConfirmUser(dialog, 10)
        accept(dialog)
        drain()
        assertEquals(listOf(AppOpsClient.Call("set", 10, "other.app")), AppOpsClient.calls.filter { it.operation == "set" })
    }

    @Test fun cancellationAndNullResultsReleasePickerWithoutAnyIoAndPermitRetry() {
        val controller = ready()
        val activity = controller.get()
        for (kind in 0..2) {
            launch(activity, 501)
            when (kind) {
                0 -> result(activity, 501, result = Activity.RESULT_CANCELED)
                1 -> result(activity, 501, nullIntent = true)
                else -> result(activity, 501)
            }
            drain()
            assertTrue(AppOpsClient.calls.none { it.operation == "snapshot" || it.operation == "set" })
        }
        launch(activity, 501)
        val target = uri("retry-after-cancel")
        val stream = output(activity, target) as ByteArrayOutputStream
        result(activity, 501, target)
        drain()
        assertExport(stream, 10)
    }

    @Test fun canceledRecreatedPickerCannotBeResurrectedByLateSuccess() {
        val first = ready()
        launch(first.get(), 501)
        val next = recreate(first)
        val target = uri("canceled-export")
        val stream = output(next.get(), target) as ByteArrayOutputStream
        result(next.get(), 501, result = Activity.RESULT_CANCELED)
        drain()
        result(next.get(), 501, target)
        drain()
        assertEquals("A stale success after cancellation must not create a backup", 0, stream.size())
        assertTrue(AppOpsClient.calls.none { it.operation == "snapshot" })
        launch(next.get(), 501)
    }

    @Test fun mismatchedRequestCodeDoesNotConsumeThePendingExport() {
        val controller = ready()
        val activity = controller.get()
        launch(activity, 501)
        val wrong = uri("wrong-import")
        input(activity, wrong)
        result(activity, 502, wrong)
        drain()
        assertTrue("Mismatched callback must not run any backend work", AppOpsClient.calls.isEmpty())
        val correct = uri("correct-export")
        val stream = output(activity, correct) as ByteArrayOutputStream
        result(activity, 501, correct)
        drain()
        assertExport(stream, 10)
    }

    @Test fun duplicateExportCallbacksCannotScheduleOrWriteASecondBackup() {
        val controller = ready()
        val activity = controller.get()
        launch(activity, 501)
        val first = uri("first-export")
        val duplicate = uri("duplicate-export")
        val firstStream = output(activity, first) as ByteArrayOutputStream
        val duplicateStream = output(activity, duplicate) as ByteArrayOutputStream
        result(activity, 501, first)
        result(activity, 501, duplicate)
        drain()
        result(activity, 501, duplicate)
        drain()
        assertExport(firstStream, 10)
        assertEquals(0, duplicateStream.size())
        assertEquals(1, AppOpsClient.calls.count { it.operation == "snapshot" })
    }

    @Test fun queuedSuccessfulExportRejectsDuplicateUriAndCancellation() {
        val first = ready()
        launch(first.get(), 501)
        val next = recreate(first)
        val original = uri("queued-original")
        val duplicate = uri("queued-duplicate")
        val originalStream = output(next.get(), original) as ByteArrayOutputStream
        val duplicateStream = output(next.get(), duplicate) as ByteArrayOutputStream
        result(next.get(), 501, original)
        result(next.get(), 501, duplicate)
        result(next.get(), 501, result = Activity.RESULT_CANCELED)
        drain()
        assertExport(originalStream, 10)
        assertEquals("Duplicate URI must never replace an already accepted result", 0, duplicateStream.size())
        assertEquals(1, AppOpsClient.calls.count { it.operation == "snapshot" })
    }

    @Test fun queuedSuccessfulImportRejectsDuplicateUriAndCancellation() {
        val first = ready()
        launch(first.get(), 502)
        val next = recreate(first)
        val original = uri("queued-import-original")
        val duplicate = uri("queued-import-duplicate")
        input(next.get(), original)
        input(next.get(), duplicate, "{invalid duplicate payload")
        result(next.get(), 502, original)
        result(next.get(), 502, duplicate)
        result(next.get(), 502, result = Activity.RESULT_CANCELED)
        drain()
        val dialog = confirmation()
        assertConfirmUser(dialog, 10)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue("Canceling the confirmation never applies changes", AppOpsClient.calls.none { it.operation == "set" })
    }

    @Test fun duplicateImportCallbackDoesNotOpenAnotherConfirmationOrWriteWithoutConsent() {
        val controller = ready()
        val activity = controller.get()
        launch(activity, 502)
        val source = uri("import-once")
        input(activity, source)
        result(activity, 502, source)
        drain()
        val original = confirmation()
        val dialogs = MaterialAlertDialogBuilder.dialogs.size
        input(activity, source)
        result(activity, 502, source)
        drain()
        assertEquals(dialogs, MaterialAlertDialogBuilder.dialogs.size)
        assertSame(original, confirmation())
        assertTrue(AppOpsClient.calls.none { it.operation == "set" })
    }

    @Test fun importReadConfirmationAndWritesStayPinnedWhenCurrentUserChanges() {
        val controller = ready()
        val activity = controller.get()
        launch(activity, 502)
        // A changing screen selection must not reinterpret an already launched request.
        setField(activity, "user", 0)
        setField(activity, "selectedPackage", "primary.app")
        val source = uri("pinned-import")
        input(activity, source)
        result(activity, 502, source)
        drain()
        assertEquals(listOf(AppOpsClient.Call("ops", 10, "other.app")), AppOpsClient.calls)
        val dialog = confirmation()
        assertConfirmUser(dialog, 10)
        accept(dialog)
        drain()
        assertEquals(listOf(AppOpsClient.Call("apps", 10, null)), AppOpsClient.calls.filter { it.operation == "apps" })
        assertEquals(listOf(AppOpsClient.Call("set", 10, "other.app")), AppOpsClient.calls.filter { it.operation == "set" })
    }

    @Test fun importConfirmationKeepsItsTargetIfSelectionChangesBeforeApproval() {
        val controller = ready()
        val activity = controller.get()
        launch(activity, 502)
        val source = uri("approval-target")
        input(activity, source)
        result(activity, 502, source)
        drain()
        val dialog = confirmation()
        assertConfirmUser(dialog, 10)
        setField(activity, "user", 0)
        setField(activity, "selectedPackage", "primary.app")
        accept(dialog)
        drain()
        assertEquals(listOf(AppOpsClient.Call("apps", 10, null)), AppOpsClient.calls.filter { it.operation == "apps" })
        assertEquals(listOf(AppOpsClient.Call("set", 10, "other.app")), AppOpsClient.calls.filter { it.operation == "set" })
    }

    @Test fun exportStaysPinnedToLaunchProfileEvenWhenScreenSelectionChanges() {
        val controller = ready()
        val activity = controller.get()
        launch(activity, 501)
        setField(activity, "user", 0)
        setField(activity, "selectedPackage", "primary.app")
        val target = uri("pinned-export")
        val stream = output(activity, target) as ByteArrayOutputStream
        result(activity, 501, target)
        drain()
        assertExport(stream, 10)
        assertEquals(listOf(AppOpsClient.Call("apps", 10, null), AppOpsClient.Call("snapshot", 10, "other.app")), AppOpsClient.calls)
    }

    @Test fun failedStartupDoesNotDropAnAlreadyReturnedExportAndBackendCanRecover() {
        val first = ready()
        launch(first.get(), 501)
        val next = recreate(first)
        val target = uri("startup-failure-export")
        val stream = output(next.get(), target) as ByteArrayOutputStream
        result(next.get(), 501, target)
        AppOpsClient.failApps = true
        step()
        assertEquals(0, stream.size())
        AppOpsClient.failApps = false
        drain()
        assertExport(stream, 10)
        assertFalse(field(next.get(), "busy") as Boolean)
    }

    @Test fun failedExportStreamReleasesBusyAndAllowsFreshPickerRetry() {
        val controller = ready()
        val activity = controller.get()
        launch(activity, 501)
        val failing = uri("write-failure")
        output(activity, failing, object : OutputStream() { override fun write(value: Int) { throw IOException("controlled output failure") } })
        result(activity, 501, failing)
        drain()
        assertTrue(status(activity).contains("controlled output failure"))
        assertFalse(field(activity, "busy") as Boolean)
        launch(activity, 501)
        val retry = uri("write-retry")
        val stream = output(activity, retry) as ByteArrayOutputStream
        result(activity, 501, retry)
        drain()
        assertExport(stream, 10)
    }

    @Test fun malformedImportHasNoWritesAndAllowsFreshImportRetry() {
        val controller = ready()
        val activity = controller.get()
        launch(activity, 502)
        val invalid = uri("malformed-import")
        input(activity, invalid, "{invalid")
        result(activity, 502, invalid)
        drain()
        assertTrue(status(activity).startsWith("AppOps error:"))
        assertFalse(field(activity, "busy") as Boolean)
        assertTrue(AppOpsClient.calls.none { it.operation == "set" })
        launch(activity, 502)
        val valid = uri("valid-import-retry")
        input(activity, valid)
        result(activity, 502, valid)
        drain()
        assertConfirmUser(confirmation(), 10)
    }

    @Test fun primaryAndSecondaryExportImportRemainStrictlyIsolated() {
        for (user in listOf(0, 10)) {
            val controller = ready(user)
            val activity = controller.get()
            launch(activity, 501)
            val target = uri("profile-$user-export")
            val stream = output(activity, target) as ByteArrayOutputStream
            result(activity, 501, target)
            drain()
            assertExport(stream, user)
            launch(activity, 502)
            val source = uri("profile-$user-import")
            input(activity, source, stream.toString("UTF-8"))
            result(activity, 502, source)
            drain()
            val dialog = confirmation()
            assertConfirmUser(dialog, user)
            accept(dialog)
            drain()
            assertTrue("Every daemon call stays in user $user", AppOpsClient.calls.all { it.user == user })
            assertEquals(listOf(AppOpsClient.Call("set", user, if (user == 10) "other.app" else "primary.app")), AppOpsClient.calls.filter { it.operation == "set" })
            controller.pause().stop().destroy()
        }
    }
}
