package moe.shizuku.manager.regression

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.control.AndroidControlService
import moe.shizuku.manager.control.IAndroidControlService
import moe.shizuku.manager.control.OrientationControlClient
import moe.shizuku.manager.control.PortraitTarget
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyStore
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.control.TargetPortraitDisplayActivity
import java.security.MessageDigest
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Runs in the installed app UID, with the real Android Keystore and display host. */
class RuntimeRegressionInstrumentation : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }

    override fun onStart() {
        try {
            waitForIdleSync()
            when (args.getString("phase")) {
                "seed" -> savePairingIdentity()
                "reopen" -> check(fingerprint() == testPreferences().getString("publicFingerprint", null)) {
                    "Baseline app did not retain its ADB identity before updating; ${keyState()}"
                }
                "upgrade" -> checkPairingIdentity()
                "key-failure" -> checkKeyReadFailure()
                "fresh" -> saveFreshIdentity()
                "fresh-reopen" -> check(fingerprint(freshStore()) == testPreferences().getString("freshFingerprint", null)) {
                    "New pairing identity was not durable before returning to the caller"
                }
                "key-recovery" -> checkKeyRecovery()
                "display" -> closeDisplay(checkDisplay())
                "select-target" -> selectTarget()
                "separate-controls" -> checkSeparateControls()
                else -> error("Unknown regression phase")
            }
            finish(-1, Bundle().apply { putString("regression", "PASS ${args.getString("phase")}") })
        } catch (t: Throwable) {
            finish(0, Bundle().apply { putString("regression", "FAIL ${args.getString("phase")}: $t") })
        }
    }

    private fun fingerprint(store: AdbKeyStore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())): String {
        val key = AdbKey(store, "shizuku")
        return MessageDigest.getInstance("SHA-256").digest(key.adbPublicKey)
            .joinToString("") { "%02x".format(it) }
    }

    private fun testPreferences() = targetContext.createDeviceProtectedStorageContext()
        .getSharedPreferences("runtime_regression", Context.MODE_PRIVATE)

    private fun savePairingIdentity() {
        val identity = fingerprint()
        // The baseline uses asynchronous apply(); establish a durably saved
        // identity before killing its process to test the APK replacement.
        check(ShizukuSettings.getPreferences().edit().commit())
        check(testPreferences().edit().putString("publicFingerprint", identity)
            .putString("keyState", keyState()).commit())
    }

    private fun freshStore() = PreferenceAdbKeyStore(targetContext.createDeviceProtectedStorageContext()
        .getSharedPreferences("pairing_write_regression", Context.MODE_PRIVATE))

    private fun saveFreshIdentity() {
        val executor = Executors.newFixedThreadPool(4)
        val identity: String
        try {
            val identities = (1..8).map { executor.submit<String> { fingerprint(freshStore()) } }.map { it.get() }
            check(identities.toSet().size == 1) { "Concurrent callers created different pairing identities" }
            identity = identities.first()
        } finally {
            executor.shutdownNow()
        }
        check(testPreferences().edit().putString("freshFingerprint", identity).commit())
        // Do not flush the key preferences here: production must save before use.
    }

    private fun keyState(): String {
        val preferences = ShizukuSettings.getPreferences()
        val encrypted = preferences.getString("adbkey", null)
        val digest = encrypted?.let {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
        return "preferences=${preferences.javaClass.simpleName}; saved=${preferences.contains("adbkey")}; encryptedDigest=$digest"
    }

    private fun checkPairingIdentity() {
        val expected = testPreferences().getString("publicFingerprint", null)
            ?: error("Baseline identity was not saved")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val identities = (1..8).map { executor.submit<String> { fingerprint() } }
            identities.forEach { check(it.get() == expected) {
                "ADB identity changed across APK update; before=${testPreferences().getString("keyState", null)}; after=${keyState()}"
            } }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun checkKeyReadFailure() {
        var writes = 0
        val corruptStore = object : AdbKeyStore {
            override fun get() = byteArrayOf(1, 2, 3)
            override fun put(bytes: ByteArray) { writes++ }
        }
        var failed = false
        try { AdbKey(corruptStore, "test") } catch (_: IllegalStateException) { failed = true }
        check(failed && writes == 0) { "Unreadable ADB key was silently replaced" }
        checkPairingIdentity()
    }

    private fun checkKeyRecovery() {
        val preferences = ShizukuSettings.getPreferences()
        val encrypted = preferences.getString("adbkey", null)
        val oldFingerprint = fingerprint()
        check(preferences.edit().putInt("regression_sentinel", 42).commit())
        // Simulate the permanent error in this isolated emulator app's namespace.
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry("_adbkey_encryption_key_")
        }
        check(runCatching { fingerprint() }.isFailure)
        check(preferences.getString("adbkey", null) == encrypted)

        startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val reply = Intent(targetContext, AdbPairingService::class.java)
            .setAction("reply").putExtra("paring_code", 1)
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("paring_code").build()), reply,
            Bundle().apply { putCharSequence("paring_code", "123456") })
        targetContext.startForegroundService(reply)
        val notifications = targetContext.getSystemService(NotificationManager::class.java)
        var failure: Notification? = null
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            failure = notifications.activeNotifications.firstOrNull {
                it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                    targetContext.getString(R.string.adb_error_key_store)
            }?.notification
            if (failure != null) break
            SystemClock.sleep(100)
        }
        check(failure != null) { "Key failure left pairing stuck on working notification" }
        check(failure!!.flags and Notification.FLAG_FOREGROUND_SERVICE == 0)
        check(failure!!.contentIntent != null) { "No route to pairing-key recovery" }
        check(preferences.getString("adbkey", null) == encrypted) { "Failure silently reset the pairing key" }

        // Only an explicit user-confirmed reset invokes this operation.
        AdbKey.resetPairing(preferences)
        check(!preferences.contains("adbkey"))
        check(preferences.getInt("regression_sentinel", 0) == 42)
        check(fingerprint() != oldFingerprint) { "Explicit reset did not create a usable new identity" }
    }

    private val otherPackage = "org.androidcontrol.regression.other"

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun controlRecordExists(name: String): Boolean = shell("ls /data/local/tmp")
        .lineSequence().any { it.trim() == name }

    private fun openManager() {
        // NEW_TASK can reuse the current activity, so startActivitySync would
        // wait forever for an onCreate callback that Android will not send.
        runOnMainSync {
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun checkpoint(message: String) {
        sendStatus(0, Bundle().apply { putString("checkpoint", message) })
    }

    private fun controlService(): IAndroidControlService {
        openManager()
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!Shizuku.pingBinder() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(Shizuku.pingBinder())
        var service: IAndroidControlService? = null
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IAndroidControlService.Stub.asInterface(binder)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        runOnMainSync {
            Shizuku.bindUserService(Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID,
                AndroidControlService::class.java.name)).daemon(true).processNameSuffix("android_control")
                .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE), connection)
        }
        check(ready.await(15, TimeUnit.SECONDS)) { "Control service bind timed out" }
        return service!!
    }

    private fun selectTarget() {
        val service = controlService()
        check(runCatching { service.validateTargetPackage("bad;package") }.isFailure)
        check(runCatching { service.validateTargetPackage(BuildConfig.APPLICATION_ID) }.isFailure)
        check(runCatching { service.validateTargetPackage("org.androidcontrol.missing.app") }.isFailure)
        runOnMainSync { OrientationControlClient.connect() }
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!OrientationControlClient.state.available && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        val saved = CountDownLatch(1)
        var failure: String? = "No callback"
        OrientationControlClient.saveTargetPackage(otherPackage) { error -> failure = error; saved.countDown() }
        check(saved.await(10, TimeUnit.SECONDS))
        check(failure == null) { "Target selection failed: $failure" }
        check(PortraitTarget.get() == otherPackage)
    }

    private fun rotationSnapshot(): String = listOf(
        "wm user-rotation -d 0", "wm fixed-to-user-rotation -d 0",
        "wm get-ignore-orientation-request -d 0", "settings get system user_rotation"
    ).joinToString("\n") { shell(it) }

    private fun awaitUserRotation(mode: String, angle: Int) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (shell("wm user-rotation -d 0") == mode &&
                shell("settings get system user_rotation") == angle.toString()) return
            SystemClock.sleep(100)
        }
        error("Test rotation baseline did not settle: ${rotationSnapshot()}")
    }

    private fun checkSeparateControls() {
        // This phase runs in a fresh manager process after selection was saved.
        check(PortraitTarget.get() == otherPackage) { "Target selection did not survive a restart" }
        var service = controlService()
        check(service.isSystemPortraitSupported)
        shell("wm user-rotation -d 0 lock 1")
        shell("wm fixed-to-user-rotation -d 0 disabled")
        shell("wm set-ignore-orientation-request -d 0 false")
        awaitUserRotation("lock 1", 1)
        val previous = rotationSnapshot()
        check(service.setForcePortrait(true))
        val forced = rotationSnapshot()
        check(service.hasSystemPortraitOverride())
        check(!controlRecordExists("androidcontrol-target-app-compat")) {
            "System-wide mode applied target compatibility flags"
        }
        checkpoint("system enabled; restarting daemon")
        runCatching { service.destroy() }
        val restartDeadline = SystemClock.uptimeMillis() + 5000
        while (service.asBinder().pingBinder() && SystemClock.uptimeMillis() < restartDeadline) SystemClock.sleep(100)
        check(!service.asBinder().pingBinder())
        service = controlService()
        check(service.hasSystemPortraitOverride() && service.isForcePortraitEnabled)
        checkpoint("snapshot retained after daemon restart; launching selected target")
        val activity = checkDisplay()
        check(rotationSnapshot() == forced) { "Target launch changed the system-wide override" }
        check(shell("cat /data/local/tmp/androidcontrol-portrait-target-package") == otherPackage)
        check(shell("run-as $otherPackage cat files/touches").contains("touch"))
        // Changing the next selection must not retarget this session's cleanup.
        PortraitTarget.save(PortraitTarget.DEFAULT_PACKAGE)
        checkpoint("selected target received touch; closing session")
        closeDisplay(activity)
        check(rotationSnapshot() == forced) { "Target restoration disabled the global mode" }
        check(!controlRecordExists("androidcontrol-portrait-target-package"))
        check(shell("dumpsys activity activities").contains(otherPackage)) { "Back did not reopen the session's original target" }
        service.setForcePortrait(false)
        check(!service.hasSystemPortraitOverride())
        check(rotationSnapshot() == previous) { "Previous manual rotation policy was not restored; expected=$previous; actual=${rotationSnapshot()}" }
        checkpoint("manual rotation restored")
        // Also verify an automatically rotating display restores its remembered angle.
        shell("wm user-rotation -d 0 free")
        awaitUserRotation("free", 1)
        val automatic = rotationSnapshot()
        service.setForcePortrait(true)
        service.setForcePortrait(false)
        check(rotationSnapshot() == automatic) { "Automatic rotation was not restored; expected=$automatic; actual=${rotationSnapshot()}" }
        checkpoint("automatic rotation restored")
        // Upgrade from a pre-journal version must still offer an explicit way out.
        shell("wm user-rotation -d 0 lock 0")
        shell("wm fixed-to-user-rotation -d 0 enabled")
        shell("wm set-ignore-orientation-request -d 0 true")
        val forcedDeadline = SystemClock.uptimeMillis() + 5000
        while (!service.isForcePortraitEnabled && SystemClock.uptimeMillis() < forcedDeadline) SystemClock.sleep(100)
        check(service.isForcePortraitEnabled && !service.hasSystemPortraitOverride())
        check(!service.toggleForcePortrait()) { "Existing pre-journal portrait policy could not be cleared" }
        check(!service.hasSystemPortraitOverride())
        check(shell("wm user-rotation -d 0") == "free")
        check(shell("wm fixed-to-user-rotation -d 0") == "default")
    }

    private fun closeDisplay(activity: TargetPortraitDisplayActivity) {
        runOnMainSync { activity.onBackPressed() }
        val deadline = SystemClock.uptimeMillis() + 30000
        while (!activity.isFinishing && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(activity.isFinishing) { "Target session did not finish restoration" }
        check(!controlRecordExists("androidcontrol-portrait-target-package")) {
            "Target cleanup did not finish: " + shell("cat /data/local/tmp/androidcontrol-target-app-compat")
        }
    }

    private fun checkDisplay(): TargetPortraitDisplayActivity {
        openManager()
        val binderDeadline = SystemClock.uptimeMillis() + 10000
        while (!Shizuku.pingBinder() && SystemClock.uptimeMillis() < binderDeadline) {
            SystemClock.sleep(100)
        }
        check(Shizuku.pingBinder()) { "Test server did not connect to the manager" }
        val activity = startActivitySync(Intent(targetContext, TargetPortraitDisplayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TargetPortraitDisplayActivity
        val launchedField = activity.javaClass.getDeclaredField("launched").apply { isAccessible = true }
        val statusField = activity.javaClass.getDeclaredField("statusView").apply { isAccessible = true }
        var launched = false
        var status = ""
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) {
            runOnMainSync {
                launched = launchedField.getBoolean(activity)
                status = (statusField.get(activity) as TextView).text.toString()
            }
            if (launched) break
            SystemClock.sleep(250)
        }
        check(launched) { "Portrait host stopped launch: $status" }
        // Covers the reported delayed obstruction and at least two monitor ticks.
        SystemClock.sleep(5000)
        var width = 0
        var height = 0
        runOnMainSync {
            check(launchedField.getBoolean(activity)) { "Launch was revoked after appearing" }
            check((statusField.get(activity) as TextView).visibility != View.VISIBLE) {
                "Technical status is covering the game"
            }
            width = activity.window.decorView.width
            height = activity.window.decorView.height
        }
        check(width > 0 && height > width)
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, width / 2f, height / 2f, 0)
        val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, width / 2f, height / 2f, 0)
        try { sendPointerSync(down); sendPointerSync(up) } finally { down.recycle(); up.recycle() }
        SystemClock.sleep(500)
        // The shell harness checks the fixture's persistent touch counter before cleanup.
        return activity
    }
}
