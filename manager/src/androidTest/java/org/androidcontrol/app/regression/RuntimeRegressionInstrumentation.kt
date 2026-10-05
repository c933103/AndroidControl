package org.androidcontrol.app.regression

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
import android.view.SurfaceView
import android.view.View
import android.widget.TextView
import org.androidcontrol.app.ShizukuSettings
import org.androidcontrol.app.MainActivity
import org.androidcontrol.app.BuildConfig
import org.androidcontrol.app.control.AndroidControlService
import org.androidcontrol.app.control.IAndroidControlService
import org.androidcontrol.app.control.OrientationControlClient
import org.androidcontrol.app.control.PortraitTarget
import org.androidcontrol.app.R
import org.androidcontrol.app.adb.AdbPairingService
import org.androidcontrol.app.adb.AdbPairingTutorialActivity
import org.androidcontrol.app.adb.AdbKey
import org.androidcontrol.app.adb.AdbKeyStore
import org.androidcontrol.app.adb.PreferenceAdbKeyStore
import org.androidcontrol.app.control.TargetPortraitDisplayActivity
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
                "display" -> {
                    PortraitTarget.save(fixturePackage)
                    closeDisplay(checkDisplay())
                }
                "select-target" -> selectTarget()
                "separate-controls" -> checkSeparateControls()
                "appops" -> checkAppOps()
                "appops-user" -> checkAppOpsUserIsolation()
                "external-dialog" -> checkExternalDialog()
                "web-links" -> checkWebLinks()
                "interrupted-handoff" -> checkInterruptedHandoff()
                "lock-unlock" -> checkLockUnlock()
                "shutdown-control" -> runCatching { controlService().destroy() }
                "root-display" -> {
                    controlService()
                    check(Shizuku.getUid() == 0) { "Root regression did not start a root server" }
                    closeDisplay(checkDisplay())
                }
                else -> error("Unknown regression phase")
            }
            finish(-1, Bundle().apply { putString("regression", "PASS ${args.getString("phase")}") })
        } catch (t: Throwable) {
            // Capture BEFORE instrumentation finish removes the manager's windows.
            runCatching {
                java.io.File(targetContext.cacheDir, "regression-failure-windows.txt").writeText(shell("dumpsys window"))
                java.io.File(targetContext.cacheDir, "regression-failure-input.txt").writeText(shell("dumpsys input"))
                java.io.File(targetContext.cacheDir, "regression-failure-activities.txt").writeText(shell("dumpsys activity activities"))
                java.io.File(targetContext.cacheDir, "regression-failure-surfaces.txt").writeText(shell("dumpsys SurfaceFlinger"))
                uiAutomation.takeScreenshot()?.let { screenshot ->
                    java.io.File(targetContext.cacheDir, "regression-failure-screen.png").outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            }
            finish(0, Bundle().apply { putString("regression", "FAIL ${args.getString("phase")}: ${t.stackTraceToString()}") })
        }
    }

    private fun fingerprint(store: AdbKeyStore = PreferenceAdbKeyStore(ShizukuSettings.getPreferences())): String {
        val key = AdbKey(store, "shizuku")
        return MessageDigest.getInstance("SHA-256").digest(key.adbPublicKey)
            .joinToString("") { "%02x".format(it) }
    }

    private fun testPreferences() = targetContext.createDeviceProtectedStorageContext()
        .getSharedPreferences("runtime_regression", Context.MODE_PRIVATE)

    private fun checkAppOps() {
        controlService()
        val client = org.androidcontrol.app.appops.AppOpsClient
        val user = android.os.Process.myUid() / 100000
        val ops = client.request("ops", user, fixturePackage) as org.json.JSONArray
        val camera = (0 until ops.length()).map { ops.getJSONObject(it) }.first { it.getString("name") == "android:camera" }
        val original = camera.getInt("mode")
        try {
            val denied = client.request("set", user, fixturePackage) { it.put("op", "android:camera").put("mode", 1) } as org.json.JSONObject
            check(denied.getInt("stored") == 1 && denied.getInt("effective") == 1) { "Ignore did not take effect" }
            val shellValue = shell("cmd appops get --user $user $fixturePackage CAMERA")
            check(shellValue.contains("ignore")) { "Independent AppOps read disagrees: $shellValue" }
            client.request("set", user, fixturePackage) { it.put("op", "android:camera").put("mode", 0) }
            check(shell("cmd appops get --user $user $fixturePackage CAMERA").contains("allow")) { "Allow readback failed" }
            check(runCatching { client.request("set", user, fixturePackage) { it.put("op", "android:camera").put("mode", 99) } }.isFailure) { "Invalid mode accepted" }
            check(runCatching { client.request("set", user, "missing.fixture.package") { it.put("op", "android:camera").put("mode", 1) } }.isFailure) { "Missing package accepted" }
            val legacy = """{"v":1,"opbacks":[{"pkg":"$fixturePackage","ops":"26,27"}]}"""
            val parsed = org.androidcontrol.app.appops.AppOpsBackup.parse(legacy, ops)
            check(parsed.any { it.name == "android:camera" && it.mode == 1 }) { "Legacy backup camera mapping failed" }
            val native = org.json.JSONObject().put("format", "androidcontrol-appops").put("version", 2)
                .put("apps", org.json.JSONArray().put(client.request("snapshot", user, fixturePackage)))
            check(org.androidcontrol.app.appops.AppOpsBackup.parse(native.toString(), ops).isNotEmpty()) { "Native backup did not round-trip" }
            val duplicate = """{"v":1,"opbacks":[{"pkg":"$fixturePackage","ops":"26,26"}]}"""
            check(runCatching { org.androidcontrol.app.appops.AppOpsBackup.parse(duplicate, ops) }.isFailure) { "Duplicate import accepted" }
            val invalid = """{"v":1,"opbacks":[{"pkg":"$fixturePackage","ops":"999"}]}"""
            check(runCatching { org.androidcontrol.app.appops.AppOpsBackup.parse(invalid, ops) }.isFailure) { "Unknown legacy operation accepted" }
            // Verify UID rules are observed but not overwritten by a package write.
            shell("cmd appops set --uid --user $user $fixturePackage CAMERA ignore")
            val masked = client.request("set", user, fixturePackage) { it.put("op", "android:camera").put("mode", 0) } as org.json.JSONObject
            check(masked.getInt("stored") == 0 && masked.getInt("effective") == 1) { "UID masking was not reported" }
        } finally {
            shell("cmd appops set --uid --user $user $fixturePackage CAMERA default")
            client.request("set", user, fixturePackage) { it.put("op", "android:camera").put("mode", original) }
        }
    }

    private fun checkAppOpsUserIsolation() {
        val client = org.androidcontrol.app.appops.AppOpsClient
        val user = android.os.Process.myUid() / 100000
        val output = shell("pm create-user AndroidControlRegression")
        val otherUser = Regex("created user id (\\d+)").find(output)?.groupValues?.get(1)?.toInt()
            ?: error("Could not create second Android user: $output")
        try {
            shell("cmd package install-existing --user $otherUser $fixturePackage")
            client.request("set", otherUser, fixturePackage) { it.put("op", "android:camera").put("mode", 1) }
            check(shell("cmd appops get --user $otherUser $fixturePackage CAMERA").contains("ignore"))
            check(!shell("cmd appops get --user $user $fixturePackage CAMERA").contains("ignore")) { "Write leaked to primary user" }
        } finally { shell("pm remove-user $otherUser") }
    }

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

    private val fixturePackage = "org.androidcontrol.regression.target"
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
        shell("wm fixed-to-user-rotation -d 0 disabled")
        shell("wm set-ignore-orientation-request -d 0 false")
        // Establish the locked mode before changing its angle. Android writes
        // accelerometer mode and user angle separately, with asynchronous observers.
        shell("wm user-rotation -d 0 lock")
        awaitState("Manual rotation baseline did not lock") { shell("wm user-rotation -d 0").startsWith("lock ") }
        shell("wm user-rotation -d 0 lock 1")
        awaitUserRotation("lock 1", 1)
        val previous = rotationSnapshot()
        check(service.setForcePortrait(true))
        val forced = rotationSnapshot()
        check(service.hasSystemPortraitOverride())
        check(!controlRecordExists("androidcontrol-target-compat")) {
            "System-wide mode applied target compatibility flags"
        }
        checkpoint("system enabled; restarting daemon")
        // Remove the server's service record as well as the process. Binding
        // during asynchronous death cleanup can otherwise attach to a dead record.
        runOnMainSync {
            Shizuku.unbindUserService(Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID,
                AndroidControlService::class.java.name)), null, true)
        }
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
        PortraitTarget.save(fixturePackage)
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
        shell("wm user-rotation -d 0 lock")
        awaitState("Pre-journal rotation baseline did not lock") { shell("wm user-rotation -d 0").startsWith("lock ") }
        shell("wm user-rotation -d 0 lock 0")
        awaitUserRotation("lock 0", 0)
        shell("wm fixed-to-user-rotation -d 0 enabled")
        shell("wm set-ignore-orientation-request -d 0 true")
        val forcedDeadline = SystemClock.uptimeMillis() + 5000
        while (!service.isForcePortraitEnabled && SystemClock.uptimeMillis() < forcedDeadline) SystemClock.sleep(100)
        check(service.isForcePortraitEnabled && !service.hasSystemPortraitOverride()) {
            "Pre-journal test setup did not establish portrait; state=${rotationSnapshot()}; " +
                "saved=${service.hasSystemPortraitOverride()}"
        }
        check(!service.toggleForcePortrait()) { "Existing pre-journal portrait policy could not be cleared" }
        check(!service.hasSystemPortraitOverride())
        check(shell("wm user-rotation -d 0") == "free") { "Cleared policy is not free: ${rotationSnapshot()}" }
        check(shell("wm fixed-to-user-rotation -d 0") == "default") { "Cleared fixed policy is not default: ${rotationSnapshot()}" }
    }

    private fun awaitState(message: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(250)
        }
        error(message)
    }

    private fun fixtureFile(name: String): String =
        shell("run-as ${fixturePackage} cat files/$name")

    private fun tapScene(activity: TargetPortraitDisplayActivity) {
        val field = activity.javaClass.getDeclaredField("surfaceView").apply { isAccessible = true }
        val point = IntArray(2)
        runOnMainSync {
            val surface = field.get(activity) as SurfaceView
            surface.getLocationOnScreen(point)
            point[0] += surface.width / 2
            point[1] += surface.height / 2
        }
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0].toFloat(), point[1].toFloat(), 0)
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, point[0].toFloat(), point[1].toFloat(), 0)
        try { sendPointerSync(down); sendPointerSync(up) } finally { down.recycle(); up.recycle() }
    }

    private fun checkWebLinks() {
        PortraitTarget.save(fixturePackage)
        val server = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val serving = Thread {
            while (!server.isClosed) {
                try { server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: "/"
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val body = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                        "<title>$path</title></head><body><h1>$path</h1>" +
                        "<a id='next' href='/two'>Next</a><br><br>" +
                        "<a id='popup' target='_blank' href='/popup'>Popup</a></body></html>"
                    val response = if (path == "/redirect") "HTTP/1.1 302 Found\r\nLocation: /one\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        else "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().write(response.toByteArray())
                } } catch (_: java.io.IOException) { }
            }
        }.apply { isDaemon = true; start() }
        val activity = checkDisplay()
        val pid = shell("pidof ${fixturePackage}")
        val displayField = activity.javaClass.getDeclaredField("displayId").apply { isAccessible = true }
        val originalDisplay = displayField.getInt(activity)
        val panelField = activity.javaClass.getDeclaredField("webPanel").apply { isAccessible = true }
        fun page(): android.webkit.WebView? {
            fun find(view: View): android.webkit.WebView? {
                if (view.visibility != View.VISIBLE) return null
                if (view is android.webkit.WebView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            var web: android.webkit.WebView? = null
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                web = (panelField.get(activity) as? View)?.let { find(it) }
            } else runOnMainSync { web = (panelField.get(activity) as? View)?.let { find(it) } }
            return web
        }
        fun url(): String? {
            var value: String? = null
            runOnMainSync { value = page()?.takeIf { it.progress == 100 }?.url }
            return value
        }
        fun script(js: String): String {
            val latch = CountDownLatch(1)
            var result = ""
            val web = checkNotNull(page())
            runOnMainSync { web.evaluateJavascript(js) { result = it; latch.countDown() } }
            check(latch.await(5, TimeUnit.SECONDS)) { "WebView script did not complete" }
            return result
        }
        fun tapLink(id: String) {
            // Chromium intentionally skips history entries without user activation.
            // Exercise an actual tap, not element.click() from injected JavaScript.
            val xy = script("(function(){var r=document.getElementById('$id').getBoundingClientRect();return [Math.round((r.x+r.width/2)*devicePixelRatio),Math.round((r.y+r.height/2)*devicePixelRatio)];})()")
                .removeSurrounding("[", "]").split(',').map { it.toInt() }
            val location = IntArray(2)
            runOnMainSync { page()!!.getLocationOnScreen(location) }
            shell("input -d 0 tap ${location[0] + xy[0]} ${location[1] + xy[1]}")
        }
        var passed = false
        try {
            for (newTask in listOf(true, false)) {
                shell("am broadcast -a org.androidcontrol.regression.WEB_LINK -p ${fixturePackage} --es url http://127.0.0.1:${server.localPort}/redirect --ez new_task $newTask")
                awaitState("Target web link did not open in the portrait WebView (newTask=$newTask)") {
                    url()?.endsWith("/one") == true
                }
                tapLink("next")
                awaitState("Web link escaped the embedded view") { url()?.endsWith("/two") == true }
                runOnMainSync { activity.onBackPressed() }
                awaitState("Web Back did not retain page history") { url()?.endsWith("/one") == true }
                if (newTask) {
                    script("document.body.dataset.survived='yes'")
                    shell("input keyevent KEYCODE_SLEEP")
                    SystemClock.sleep(700)
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    awaitState("Web page did not regain focus after unlocking") {
                        shell("dumpsys window").lineSequence().any {
                            it.contains("mCurrentFocus=") && it.contains("TargetPortraitDisplayActivity")
                        }
                    }
                    check(script("document.body.dataset.survived") == "\"yes\"") { "Unlock recreated the web page" }
                    awaitState("Wake animation still blocks web input") {
                        val input = shell("dumpsys input")
                        !input.contains("ColorFade") &&
                            Regex("DispatchFrozen:\\s*(?:false|0)\\b").containsMatchIn(input)
                    }
                    // A real tap supplies the gesture needed for target=_blank.
                    tapLink("popup")
                    awaitState("New-window web link escaped the panel") { url()?.endsWith("/popup") == true }
                    runOnMainSync { activity.onBackPressed() }
                    awaitState("Closing web popup did not return to its parent") { url()?.endsWith("/one") == true }
                }
                runOnMainSync { (panelField.get(activity) as org.androidcontrol.app.control.PortraitWebPanel).back() }
                awaitState("Closing the web page did not restore the game's focus") {
                    fixtureFile("window-focus").trim() == "true" && panelField.get(activity) == null
                }
                check(!activity.isFinishing && displayField.getInt(activity) == originalDisplay)
                check(shell("pidof ${fixturePackage}") == pid) { "Opening a link restarted the game" }
                val before = fixtureFile("touches")
                tapScene(activity)
                awaitState("Game input failed after returning from WebView") { fixtureFile("touches").length > before.length }
            }
            checkpoint("WebView kept redirects, history, popups and lock/unlock; both browser launch modes returned to the same live game")
            passed = true
        } finally {
            server.close()
            serving.join(1000)
            // On failure, preserve the live UI for onStart's screenshot/dumps.
            // Instrumentation finish subsequently kills the host and its session.
            if (passed) closeDisplay(activity)
        }
    }

    private fun checkExternalDialog() {
        PortraitTarget.save(fixturePackage)
        val activity = checkDisplay()
        val pid = shell("pidof ${fixturePackage}")
        shell("run-as ${fixturePackage} rm -f files/dialog-dismissed")
        shell("am broadcast -a org.androidcontrol.regression.DIALOG -p ${fixturePackage}")
        SystemClock.sleep(500)
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            shell("input -d 0 keyevent KEYCODE_BACK")
        } else {
            // One global focus on older Android: exercise the always-visible
            // host Back button while the target retains rendering focus.
            val point = IntArray(2)
            runOnMainSync {
                fun find(view: View): android.widget.Button? {
                    if (view is android.widget.Button && view.text == targetContext.getString(R.string.target_portrait_back)) return view
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                        find(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                val button = checkNotNull(find(activity.window.decorView))
                button.getLocationOnScreen(point)
                point[0] += button.width / 2
                point[1] += button.height / 2
            }
            shell("input -d 0 tap ${point[0]} ${point[1]}")
        }
        awaitState("Back did not dismiss the target's own dialog") { fixtureFile("dialog-dismissed").contains("dismissed") }
        check(!activity.isFinishing) { "Back closed the portrait host while dismissing a dialog" }
        shell("run-as ${fixturePackage} rm -f files/checkout-returned")
        shell("run-as org.androidcontrol.regression.checkout rm -f files/ready")
        shell("am broadcast -a org.androidcontrol.regression.CHECKOUT -p ${fixturePackage}")
        awaitState("External dialog did not reach the native display with its button visible") {
            val ready = shell("run-as org.androidcontrol.regression.checkout cat files/ready")
            ready.contains("display=0;") && ready.contains("buttonVisible=true")
        }
        awaitState("Portrait host remained over the native transaction screen") { activity.isFinishing }
        // Moving to display 0 can rotate it. A laid-out button is not yet
        // tappable while WindowManager is freezing input for that transition.
        awaitState("Native transaction window did not finish its display transition") {
            val ready = shell("run-as org.androidcontrol.regression.checkout cat files/ready")
            ready.contains("display=0;") && ready.contains("buttonVisible=true") &&
                ready.contains("focused=true") &&
                Regex("DispatchFrozen:\\s*(?:false|0)\\b").containsMatchIn(shell("dumpsys input"))
        }
        checkpoint("secure external dialog and its bottom button are visible on the phone display")
        // Exercise the actual bottom control instead of invoking host Back, which
        // must not own/destroy this external activity or its result callback.
        val ready = shell("run-as org.androidcontrol.regression.checkout cat files/ready")
        val x = ready.substringAfter(";x=").substringBefore(';').toInt()
        val y = ready.substringAfter(";y=").toInt()
        shell("input -d 0 tap $x $y")
        awaitState("Dismissing the external dialog did not return its result to the target") {
            fixtureFile("checkout-returned").contains("returned")
        }
        check(shell("pidof ${fixturePackage}") == pid) { "Native handoff restarted the game process" }
        val before = fixtureFile("touches")
        awaitState("Returned game did not finish its display transition") {
            fixtureFile("window-focus").trim() == "true" &&
                Regex("DispatchFrozen:\\s*(?:false|0)\\b").containsMatchIn(shell("dumpsys input"))
        }
        val screenshot = uiAutomation.takeScreenshot()
        val width = screenshot.width
        val height = screenshot.height
        screenshot.recycle()
        shell("input -d 0 tap ${width / 2} ${height / 2}")
        awaitState("Target no longer receives input after dialog dismissal") { fixtureFile("touches").length > before.length }
        check(!controlRecordExists("androidcontrol-portrait-target-package"))
        check(!controlRecordExists("androidcontrol-portrait-native-handoff"))
    }

    private fun checkInterruptedHandoff() {
        PortraitTarget.save(fixturePackage)
        val service = controlService()
        val activity = checkDisplay()
        val id = activity.javaClass.getDeclaredField("displayId").apply { isAccessible = true }.getInt(activity)
        val pid = shell("pidof ${fixturePackage}")
        check(pid.isNotEmpty())
        // Fault injection: a handoff record that cannot be read must fail closed,
        // retaining the live process even when the host is then closed.
        shell("cp /proc/version /data/local/tmp/androidcontrol-portrait-native-handoff")
        check(controlRecordExists("androidcontrol-portrait-native-handoff")) { "Fault injection failed" }
        try {
            check(runCatching { service.stopTargetPortraitDisplay(id, false) }.isFailure) {
                "Invalid handoff did not report restoration failure"
            }
            runOnMainSync { activity.finish() }
            SystemClock.sleep(1500)
            check(shell("pidof ${fixturePackage}") == pid) {
                "Interrupted handoff cleanup killed the preserved game"
            }
            check(controlRecordExists("androidcontrol-portrait-native-handoff")) {
                "Interrupted handoff discarded its retry record"
            }
        } finally {
            // Remove only this test's injected record. The next fixture launch
            // owns normal restoration of the remaining target/task journal.
            shell("rm /data/local/tmp/androidcontrol-portrait-native-handoff")
        }
    }

    private fun checkLockUnlock() {
        PortraitTarget.save(fixturePackage)
        val activity = checkDisplay()
        val before = fixtureFile("frame").trim().toLong()
        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(700)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        awaitState("Target did not resume after unlocking") {
            (fixtureFile("frame").trim().toLongOrNull() ?: 0) > before + 700
        }
        val field = activity.javaClass.getDeclaredField("surfaceView").apply { isAccessible = true }
        val sample = IntArray(2)
        awaitState("Portrait surface was not reattached after unlocking") {
            var valid = false
            runOnMainSync {
                val surface = field.get(activity) as SurfaceView
                valid = surface.holder.surface.isValid
                surface.getLocationOnScreen(sample)
                sample[0] += surface.width / 4
                sample[1] += surface.height / 4
            }
            valid
        }
        awaitState("Target host is blank or obscured after unlocking") {
            val screenshot = uiAutomation.takeScreenshot()
            try { screenshot.getPixel(sample[0], sample[1]) == 0xff164f37.toInt() }
            finally { screenshot.recycle() }
        }
        val touches = fixtureFile("touches")
        tapScene(activity)
        awaitState("Target input froze after unlocking") { fixtureFile("touches").length > touches.length }
        awaitState("Target lost rendering focus after unlocking") { fixtureFile("window-focus").trim() == "true" }
        val home = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lineSequence().last { it.contains('/') }.substringBefore('/')
        // System navigation on the physical phone supplies display 0. An
        // unspecified CLI display routes Home to the focused virtual display on 13.
        shell("input -d 0 keyevent KEYCODE_HOME")
        awaitState("Home cannot leave the portrait host after unlocking") {
            shell("dumpsys window").lineSequence().any { it.contains("mCurrentFocus=") && it.contains("$home/") }
        }
        checkpoint("lock/unlock restored frames and input; Home remained usable")
        // Cleanup through the retained host instance, without waiting for another Activity onCreate.
        closeDisplay(activity)
    }

    private fun closeDisplay(activity: TargetPortraitDisplayActivity) {
        runOnMainSync {
            activity.javaClass.getDeclaredMethod("stopAndFinish").apply { isAccessible = true }.invoke(activity)
        }
        val deadline = SystemClock.uptimeMillis() + 30000
        while (!activity.isFinishing && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(activity.isFinishing) { "Target session did not finish restoration" }
        awaitState("Target cleanup did not finish") {
            !controlRecordExists("androidcontrol-portrait-target-package")
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
