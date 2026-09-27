package moe.shizuku.manager.regression

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyStore
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.control.TargetPortraitDisplayActivity
import java.security.MessageDigest
import java.util.concurrent.Executors

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
            when (args.getString("phase")) {
                "seed" -> savePairingIdentity()
                "upgrade" -> checkPairingIdentity()
                "key-failure" -> checkKeyReadFailure()
                "display" -> checkDisplay()
                else -> error("Unknown regression phase")
            }
            finish(-1, Bundle().apply { putString("regression", "PASS ${args.getString("phase")}") })
        } catch (t: Throwable) {
            finish(0, Bundle().apply { putString("regression", "FAIL ${args.getString("phase")}: $t") })
        }
    }

    private fun fingerprint(): String {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        return MessageDigest.getInstance("SHA-256").digest(key.adbPublicKey)
            .joinToString("") { "%02x".format(it) }
    }

    private fun testPreferences() = targetContext.createDeviceProtectedStorageContext()
        .getSharedPreferences("runtime_regression", Context.MODE_PRIVATE)

    private fun savePairingIdentity() {
        check(testPreferences().edit().putString("publicFingerprint", fingerprint()).commit())
    }

    private fun checkPairingIdentity() {
        val expected = testPreferences().getString("publicFingerprint", null)
            ?: error("Baseline identity was not saved")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val identities = (1..8).map { executor.submit<String> { fingerprint() } }
            identities.forEach { check(it.get() == expected) { "ADB identity changed across APK update" } }
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

    private fun checkDisplay() {
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
    }
}
