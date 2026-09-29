package moe.shizuku.manager.control

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.AtomicFile
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@Keep
class AndroidControlService @Keep constructor() : IAndroidControlService.Stub() {

    init {
        // Only this dedicated control daemon needs a package-backed UID for
        // DisplayManager attribution. Keep the parent/root server and other tools
        // untouched. Shell already has every permission used by these controls.
        if (android.os.Process.myUid() == 0) {
            val names = listOf("target-app-compat", "target-app-tasks",
                "portrait-display-session", "freeform-support-prev", "multiwindow-config-prev",
                "portrait-target-package", "system-rotation-prev", "portrait-native-handoff")
            for (name in names) for (suffix in listOf("", ".bak", ".new")) {
                val path = "/data/local/tmp/androidcontrol-$name$suffix"
                try {
                    val stat = android.system.Os.lstat(path)
                    check(android.system.OsConstants.S_ISREG(stat.st_mode)) { "Invalid control state file: $path" }
                    if (stat.st_uid == 0) android.system.Os.lchown(path, 2000, 2000)
                } catch (error: android.system.ErrnoException) {
                    if (error.errno != android.system.OsConstants.ENOENT) throw error
                }
            }
            android.system.Os.setgid(2000)
            android.system.Os.setuid(2000)
        }
    }

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context
    }

    private var serviceContext: Context? = null
    private val shellContext: Context by lazy {
        val context = serviceContext ?: error("AndroidControl service context unavailable")
        // UserService runs as shell. DisplayManager validates its attribution
        // package against that UID; the manager's package would be rejected.
        context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
    }
    private var ownedPortraitDisplay: VirtualDisplay? = null
    private var portraitHostTaskId = -1
    private var lastPhoneFocusState = ""
    @Volatile private var portraitSurfaceAttached = false
    private var portraitFocusNeedsRefresh = true
    private var lastFocusBridgeAt = 0L
    private var portraitOriginalResizeMode = 0
    private var handedOffToken: IBinder? = null
    private var handedOffDisplayId = -1
    private val handoffFile = AtomicFile(File("/data/local/tmp/androidcontrol-portrait-native-handoff"))
    @Volatile private var portraitBrowserVisible = false
    private var portraitPendingLink: String? = null
    private var portraitInitialTaskIds = emptySet<Int>()
    private var closingBrowser: BrowserClose? = null
    private data class BrowserClose(val taskId: Int, val component: android.content.ComponentName,
        val launch: PortraitWebLaunch, val deadline: Long, val backPending: Boolean)

    private enum class WmApi {
        MODERN,
        LEGACY,
        UNSUPPORTED
    }

    private val wmHelp: String by lazy {
        runWmHelp()
    }

    private val wmApi: WmApi by lazy {
        when {
            Regex("""(?m)^\s*user-rotation\b""").containsMatchIn(wmHelp) &&
                Regex("""(?m)^\s*fixed-to-user-rotation\b""").containsMatchIn(wmHelp) ->
                WmApi.MODERN

            wmHelp.contains("set-user-rotation") &&
                wmHelp.contains("set-fix-to-user-rotation") ->
                WmApi.LEGACY

            else -> WmApi.UNSUPPORTED
        }
    }

    private val supportsIgnoreOrientationRequest: Boolean by lazy {
        wmHelp.contains("set-ignore-orientation-request") &&
            wmHelp.contains("get-ignore-orientation-request")
    }

    private val targetCompatStateFile =
        File("/data/local/tmp/androidcontrol-target-app-compat")

    private val fallbackTaskStateFile =
        File("/data/local/tmp/androidcontrol-target-app-tasks")

    // Written before am start: even a failed launch or daemon restart has a cleanup target.
    private val portraitDisplayStateFile =
        File("/data/local/tmp/androidcontrol-portrait-display-session")

    private val freeformSupportStateFile =
        File("/data/local/tmp/androidcontrol-freeform-support-prev")

    private val multiWindowConfigStateFile =
        File("/data/local/tmp/androidcontrol-multiwindow-config-prev")

    @Volatile
    private var portraitWatcherRunning = false

    @Volatile
    private var portraitWatcherThread: Thread? = null

    private val portraitOperationLock = Any()

    @Volatile
    private var targetPortraitStatus =
        "Target app: overrides inactive"

    @Volatile
    private var android13OrientationOverrideAccepted = false

    @Volatile
    private var targetPortraitDisplayId = -1

    private val targetPortraitSessionLock = Any()

    @Volatile
    private var targetPortraitHostToken: IBinder? = null

    @Volatile
    private var targetPortraitHostDeathRecipient: IBinder.DeathRecipient? = null

    private val targetOwnerFile = AtomicFile(File("/data/local/tmp/androidcontrol-portrait-target-package"))
    private var targetPackage: String = readTargetOwner()

    private fun readTargetOwner(): String {
        return try {
            PortraitTarget.validate(targetOwnerFile.openRead().bufferedReader().use { it.readText() })
        } catch (t: java.io.FileNotFoundException) {
            DEFAULT_TARGET_PACKAGE
        }
    }

    private fun saveTargetOwner(packageName: String) {
        val stream = targetOwnerFile.startWrite()
        try {
            stream.write(packageName.toByteArray(Charsets.UTF_8))
            targetOwnerFile.finishWrite(stream)
        } catch (t: Throwable) {
            targetOwnerFile.failWrite(stream)
            throw t
        }
        targetPackage = packageName
    }

    private companion object {
        const val DEFAULT_TARGET_PACKAGE = "game.qualiarts.hololive.dreams.jp"

        const val FORCE_RESIZE_APP = "174042936"
        const val FORCE_NON_RESIZE_APP = "181136395"
        const val NEVER_SANDBOX_DISPLAY_APIS = "184838306"
        const val ALWAYS_SANDBOX_DISPLAY_APIS = "185004937"
        const val OVERRIDE_SANDBOX_VIEW_BOUNDS_APIS = "237531167"
        const val OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT = "265452344"
        const val OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA = "265456536"
        const val OVERRIDE_ANY_ORIENTATION = "265464455"
        const val OVERRIDE_ANY_ORIENTATION_TO_USER = "310816437"

        const val WINDOWING_MODE_FULLSCREEN = 1
        const val WINDOWING_MODE_FREEFORM = 5
        const val RESIZE_MODE_SYSTEM = 0
        const val RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY = 6
    }

    override fun destroy() {
        System.exit(0)
    }

    private val systemPortrait by lazy {
        SystemPortraitController(
            File("/data/local/tmp/androidcontrol-system-rotation-prev"),
            { wmApi == WmApi.MODERN && supportsIgnoreOrientationRequest },
            { args -> runWm(*args) },
            { runSettings("get", "system", "user_rotation").trim().toIntOrNull() ?: 0 },
            { getPortraitRotation() }
        )
    }

    override fun setForcePortrait(enabled: Boolean) = systemPortrait.setEnabled(enabled)
    override fun isForcePortraitEnabled() = systemPortrait.isEnabled()
    override fun isSystemPortraitSupported() = systemPortrait.isSupported()
    override fun hasSystemPortraitOverride() = systemPortrait.hasOverride()

    override fun validateTargetPackage(packageName: String) {
        val name = PortraitTarget.validate(packageName)
        check(resolveTargetLauncherComponent(name) != null) {
            "No launchable app installed for $name in the current Android user"
        }
    }

    private fun hasTargetPortraitState(): Boolean {
        return targetCompatStateFile.exists() ||
            fallbackTaskStateFile.exists() ||
            freeformSupportStateFile.exists() ||
            multiWindowConfigStateFile.exists() ||
            portraitWatcherRunning
    }

    override fun toggleForcePortrait(): Boolean {
        return systemPortrait.toggle()
    }

    override fun getTargetPortraitStatus(): String {
        return targetPortraitStatus
    }

    override fun createTargetPortraitSession(
        surface: Surface, width: Int, height: Int, densityDpi: Int,
        hostToken: IBinder, packageName: String, hostTaskId: Int
    ): Int = synchronized(targetPortraitSessionLock) {
        check(targetPortraitDisplayId < 0 && ownedPortraitDisplay == null) { "Portrait session already running" }
        require(surface.isValid && width > 0 && height > width && densityDpi > 0)
        validateTargetPackage(packageName)
        val taskManager = getActivityTaskManagerService()
        portraitInitialTaskIds = (getRecentTasks(taskManager) + getRunningTasks(taskManager))
            .map { it.taskId }.toSet()
        val component = android.content.ComponentName.unflattenFromString(resolveTargetLauncherComponent(packageName)!!)
            ?: error("Invalid launcher component")
        val info = shellContext.packageManager.getActivityInfo(component, 0)
        HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ActivityInfo;")
        portraitOriginalResizeMode = info.javaClass.getField("resizeMode").getInt(info)
        val manager = shellContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // A trusted display permits ordinary cross-UID activities. A separate display
        // group prevents the physical keyguard leaving this offscreen display asleep.
        // It is NOT secure: protected external UI is moved to the native phone display.
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or (1 shl 6) or (1 shl 10)
        if (android.os.Build.VERSION.SDK_INT >= 33) flags = flags or (1 shl 11) or (1 shl 12)
        if (android.os.Build.VERSION.SDK_INT >= 35) flags = flags or (1 shl 14) or (1 shl 16)
        val display = manager.createVirtualDisplay("AndroidControl-Target-Portrait", width, height,
            densityDpi, surface, flags) ?: error("Could not create portrait display")
        ownedPortraitDisplay = display
        portraitHostTaskId = hostTaskId
        portraitSurfaceAttached = true
        handedOffToken = null
        handedOffDisplayId = -1
        try {
            launchTargetOnPortraitDisplay(display.display.displayId, width, height, hostToken, packageName)
            return@synchronized display.display.displayId
        } catch (t: Throwable) {
            ownedPortraitDisplay = null
            portraitSurfaceAttached = false
            display.release()
            throw t
        }
    }

    override fun attachTargetPortraitSurface(displayId: Int, hostToken: IBinder, surface: Surface?) {
        synchronized(targetPortraitSessionLock) {
            if (displayId != targetPortraitDisplayId || targetPortraitHostToken !== hostToken) return
            val valid = surface?.takeIf { it.isValid }
            ownedPortraitDisplay?.surface = valid
            portraitSurfaceAttached = valid != null
            portraitFocusNeedsRefresh = true
            if (valid != null) restoreSessionFocus()
        }
    }

    override fun getTargetPortraitSessionState(displayId: Int, hostToken: IBinder): Int =
        synchronized(targetPortraitSessionLock) {
            when {
                handedOffDisplayId == displayId && handedOffToken === hostToken -> 2
                targetPortraitDisplayId == displayId && targetPortraitHostToken === hostToken -> 1
                else -> 0
            }
        }

    override fun sendTargetBack(displayId: Int, hostToken: IBinder) {
        synchronized(targetPortraitSessionLock) {
            if (displayId != targetPortraitDisplayId || targetPortraitHostToken !== hostToken ||
                !portraitSurfaceAttached || portraitBrowserVisible) return
            portraitFocusNeedsRefresh = true
            restoreSessionFocus()
            val now = SystemClock.uptimeMillis()
            injectEvent(displayId, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            injectEvent(displayId, KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            restoreSessionFocus()
        }
    }

    override fun getTargetPortraitLink(displayId: Int, hostToken: IBinder): String? =
        synchronized(targetPortraitSessionLock) {
            if (targetPortraitDisplayId == displayId && targetPortraitHostToken === hostToken) portraitPendingLink else null
        }

    override fun setTargetBrowserVisible(displayId: Int, hostToken: IBinder, visible: Boolean) {
        synchronized(targetPortraitSessionLock) {
            check(targetPortraitDisplayId == displayId && targetPortraitHostToken === hostToken) { "Portrait session ended" }
            portraitBrowserVisible = visible
            portraitPendingLink = null
            portraitFocusNeedsRefresh = true
            restoreSessionFocus()
        }
    }

    override fun handoffTargetToPhone(displayId: Int, hostToken: IBinder) {
        synchronized(targetPortraitSessionLock) {
            if (displayId != targetPortraitDisplayId || targetPortraitHostToken !== hostToken) return
            if (!handoffFile.baseFile.exists()) {
                val tasks = getRunningTasks(getActivityTaskManagerService())
                    .filter { getRunningTaskDisplayId(it) == displayId }
                    .map { "${it.taskId}|${it.baseActivity?.packageName ?: error("Task owner unavailable")}" }
                val stream = handoffFile.startWrite()
                try {
                    stream.write((listOf(displayId.toString(), portraitOriginalResizeMode.toString()) + tasks)
                        .joinToString("\n").toByteArray())
                    handoffFile.finishWrite(stream)
                } catch (t: Throwable) {
                    handoffFile.failWrite(stream)
                    throw t
                }
            }
            completeNativeHandoff()
            handedOffDisplayId = displayId
            handedOffToken = hostToken
            unregisterTargetPortraitHostLocked()
            targetPortraitDisplayId = -1
            portraitSurfaceAttached = false
            ownedPortraitDisplay?.release()
            ownedPortraitDisplay = null
            targetPortraitStatus = "$targetPackage: continued on phone display; external screen preserved"
        }
    }

    private fun completeNativeHandoff() {
        if (!handoffFile.baseFile.exists()) return
        val saved = handoffFile.openRead().bufferedReader().use { it.readLines() }
        val displayId = saved[0].toInt()
        val resizeMode = saved[1].toInt()
        val preserved = saved.drop(2).associate { it.substringBefore('|').toInt() to it.substringAfter('|') }
        val atm = getActivityTaskManagerService()
        // Move entire roots, retaining the ActivityResult chain and external UI.
        // No force-stop, relaunch, or edits to another package's compatibility flags.
        val roots = if (getLogicalDisplaySize(displayId) == null) emptyList<Any>() else
            invokeActivityTaskManager(atm, "getAllRootTaskInfosOnDisplay", displayId) as? List<*>
                ?: error("Cannot inspect portrait tasks for native handoff")
        for (root in roots.filterNotNull().asReversed()) {
            val id = root.javaClass.getField("taskId").getInt(root)
            invokeActivityTaskManager(atm, "moveRootTaskToDisplay", id, 0)
        }
        for (task in getRunningTasks(atm)) {
            if (preserved[task.taskId] != null && task.baseActivity?.packageName == preserved[task.taskId]) {
                check(getRunningTaskDisplayId(task) == 0) { "Task did not reach the phone display" }
                setTaskFullscreen(task)
                if (task.baseActivity?.packageName == targetPackage) {
                    invokeActivityTaskManager(atm, "setTaskResizeable", task.taskId, resizeMode)
                }
            }
        }
        check(getRunningTasks(atm).none { getRunningTaskDisplayId(it) == displayId }) {
            "Portrait display still has tasks; handoff will retry"
        }
        // Mark the task as preserved before restoring flags; interrupted cleanup
        // must never remove a live checkout task on the next daemon start.
        portraitDisplayStateFile.delete()
        restoreAndroid13SupportSettingsBestEffort()
        restoreTargetPortraitCompat(preserveProcess = true)
        check(!hasTargetPortraitState()) { "Native handoff restoration is incomplete" }
        targetOwnerFile.delete()
        handoffFile.delete()
    }

    private fun setTaskFullscreen(task: ActivityManager.RunningTaskInfo) {
        configureTaskWindow(task, WINDOWING_MODE_FULLSCREEN, Rect())
    }

    private fun configureTaskWindow(task: ActivityManager.RunningTaskInfo, mode: Int, bounds: Rect) {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/window/", "Landroid/app/TaskInfo;")
        val token = task.javaClass.getField("token").get(task)
        val tokenClass = Class.forName("android.window.WindowContainerToken")
        val transactionClass = Class.forName("android.window.WindowContainerTransaction")
        val transaction = transactionClass.getConstructor().newInstance()
        transactionClass.getMethod("setWindowingMode", tokenClass, Int::class.javaPrimitiveType)
            .invoke(transaction, token, mode)
        transactionClass.getMethod("setBounds", tokenClass, Rect::class.java).invoke(transaction, token, bounds)
        val organizerClass = Class.forName("android.window.WindowOrganizer")
        organizerClass.getMethod("applyTransaction", transactionClass)
            .invoke(organizerClass.getConstructor().newInstance(), transaction)
    }

    private fun restoreSessionFocus() {
        val unlocked = isPhoneUnlocked()
        if (!portraitSurfaceAttached || portraitHostTaskId < 0 || !unlocked) {
            val state = "surface=$portraitSurfaceAttached; host=$portraitHostTaskId; unlocked=$unlocked; browser=$portraitBrowserVisible"
            if (state != lastPhoneFocusState) {
                android.util.Log.d("AndroidControlService", "Portrait focus deferred: $state")
                lastPhoneFocusState = state
            }
            return
        }
        val atm = getActivityTaskManagerService()
        // getTasks is ordered by last-active time on Android 13, NOT window
        // stacking order. It can pick another app and leave global focus on the
        // virtual display, which sends an unqualified Home key to that display.
        val roots = invokeActivityTaskManager(atm, "getAllRootTaskInfosOnDisplay", 0) as? List<*>
            ?: return
        val top = roots.filterNotNull().firstOrNull { it.javaClass.getField("visible").getBoolean(it) }
            ?: return
        val rootId = top.javaClass.getField("taskId").getInt(top)
        val children = top.javaClass.getField("childTaskIds").get(top) as? IntArray
        val activity = top.javaClass.getField("topActivity").get(top) as? android.content.ComponentName
        val ownsTop = (rootId == portraitHostTaskId || children?.contains(portraitHostTaskId) == true) &&
            activity?.className == TargetPortraitDisplayActivity::class.java.name
        val focusState = "host=$portraitHostTaskId; physicalRoot=$rootId; hostOnTop=$ownsTop; browser=$portraitBrowserVisible"
        if (focusState != lastPhoneFocusState) {
            android.util.Log.d("AndroidControlService", "Portrait focus: $focusState")
            lastPhoneFocusState = focusState
        }
        // Never bring the host in front of Home, the lock screen or a native dialog.
        if (ownsTop) {
            val desiredDisplay: Int
            val desiredRoot: Int
            if (portraitBrowserVisible || android.os.Build.VERSION.SDK_INT >= 35) {
                // OWN_FOCUS keeps the game focused while global keys go to the host.
                desiredDisplay = 0
                desiredRoot = rootId
            } else {
                // Older phones have one global focused window. Taking it away
                // from a Unity/game surface can suspend rendering. Keep the live
                // virtual task focused; host buttons still accept touch, and Home
                // from the phone's navigation bar explicitly targets display 0.
                val virtualRoots = invokeActivityTaskManager(atm, "getAllRootTaskInfosOnDisplay",
                    targetPortraitDisplayId) as? List<*> ?: return
                val virtualTop = virtualRoots.filterNotNull().firstOrNull {
                    it.javaClass.getField("visible").getBoolean(it)
                } ?: return
                desiredDisplay = targetPortraitDisplayId
                desiredRoot = virtualTop.javaClass.getField("taskId").getInt(virtualTop)
            }
            invokeActivityTaskManager(atm, "setFocusedRootTask", desiredRoot)
            if (android.os.Build.VERSION.SDK_INT < 35) {
                val focused = invokeActivityTaskManager(atm, "getFocusedRootTaskInfo")
                val actualRoot = focused?.javaClass?.getField("taskId")?.getInt(focused)
                if (portraitFocusNeedsRefresh) {
                    android.util.Log.d("AndroidControlService", "Portrait focus requested=$desiredRoot; actual=$actualRoot")
                }
                if (actualRoot != desiredRoot && SystemClock.uptimeMillis() - lastFocusBridgeAt > 2000) {
                    // Later Android 13 builds consider an activity focused within
                    // its own display and skip moving that DISPLAY to the front.
                    // A new transparent task takes the normal display-activation
                    // path, then immediately finishes back to the existing app.
                    // Do not send a new launch Intent to the game or change bounds.
                    lastFocusBridgeAt = SystemClock.uptimeMillis()
                    runAm("start", "--display", desiredDisplay.toString(),
                        "-f", "0x18010000", // NEW_TASK | MULTIPLE_TASK | NO_ANIMATION
                        "-n", "${moe.shizuku.manager.BuildConfig.APPLICATION_ID}/${PortraitFocusActivity::class.java.name}")
                }
            }
            portraitFocusNeedsRefresh = false
        }
    }

    private fun isPhoneUnlocked(): Boolean =
        shellContext.getSystemService(PowerManager::class.java).isInteractive &&
            !shellContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun launchTargetOnPortraitDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        hostToken: IBinder,
        packageName: String
    ): Boolean = synchronized(targetPortraitSessionLock) {
        try {
            launchTargetOnPortraitDisplayLocked(displayId, width, height, hostToken, packageName)
        } catch (t: Throwable) {
            val failure = t.cause?.message ?: t.message ?: t.javaClass.simpleName
            if (targetPortraitDisplayId == displayId && targetPortraitHostToken === hostToken) {
                try {
                    stopTargetPortraitDisplay(displayId, false)
                } catch (cleanup: Throwable) {
                    targetPortraitStatus = "$failure; restoration pending: ${cleanup.message}"
                    throw IllegalStateException(targetPortraitStatus, t)
                }
            }
            targetPortraitStatus = failure
            throw IllegalStateException(failure, t)
        }
    }

    private fun launchTargetOnPortraitDisplayLocked(
        displayId: Int,
        width: Int,
        height: Int,
        hostToken: IBinder,
        packageName: String
    ): Boolean {
        if (displayId <= 0) {
            throw IllegalArgumentException("Invalid display ID: $displayId")
        }
        if (width <= 0 || height <= 0 || width >= height) {
            throw IllegalArgumentException(
                "Portrait display must have positive dimensions with width < height: " +
                    width + "x" + height
            )
        }

        val (actualWidth, actualHeight) = getLogicalDisplaySize(displayId)
            ?: error("Portrait display no longer exists")
        check(actualWidth == width && actualHeight == height) {
            "Logical display is ${actualWidth}x${actualHeight}, expected ${width}x${height}"
        }

        validateTargetPackage(packageName)
        check(targetPortraitDisplayId < 0) { "A portrait display session is already running" }
        stopPortraitAppWatcher()
        completeNativeHandoff()
        restoreTargetDisplayTasks()
        restoreAndroid13FallbackTasksBestEffort()
        restoreAndroid13SupportSettingsBestEffort()
        restoreTargetPortraitCompat()
        check(!hasTargetPortraitState()) { "Previous restoration is incomplete; retry before launching" }
        check(!portraitDisplayStateFile.exists()) { "Previous task restoration is incomplete" }
        saveTargetOwner(PortraitTarget.validate(packageName))
        registerTargetPortraitHost(hostToken, displayId)
        targetPortraitDisplayId = displayId
        targetPortraitStatus =
            "$targetPackage: launching on portrait display $displayId " +
                "(" + width + "x" + height + ")"

        // This session always owns a fresh task. Discarding it on exit restores its
        // original manifest resize policy on the next normal launch.
        portraitDisplayStateFile.writeText(displayId.toString())
        enableTargetDisplayCompat()
        runWm("set-ignore-orientation-request", "-d", displayId.toString(), "true")
        runWm("fixed-to-user-rotation", "-d", displayId.toString(), "enabled")
        prepareAndroid13FreeformSupportBestEffort()
        runAm("force-stop", targetPackage)

        val component = resolveTargetLauncherComponent()
            ?: throw IllegalStateException(
                "Could not resolve launcher activity for $targetPackage"
            )

        val startOutput = runAm(
            "start",
            "--display",
            displayId.toString(),
            "-f",
            "0x18000000", // NEW_TASK | MULTIPLE_TASK: isolate the temporary session.
            "--windowingMode",
            WINDOWING_MODE_FREEFORM.toString(),
            "-n",
            component
        )

        var taskId: Int? = null
        for (attempt in 0 until 30) {
            check(hostToken.isBinderAlive) { "Portrait host disconnected during launch" }
            taskId = findTargetTaskIdOnDisplay(displayId)
            if (taskId != null) break
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
            }
        }

        val id = taskId
            ?: throw IllegalStateException(
                "Target activity did not appear on portrait display $displayId: " +
                    startOutput.trim()
            )

        // Record before the first task mutation, including cases where the game
        // crashes or its task migrates off the virtual display before cleanup.
        portraitDisplayStateFile.appendText("\n$id")
        val errors = mutableListOf<String>()
        val atm = getActivityTaskManagerService()

        if (atm != null) {
            try {
                invokeActivityTaskManager(
                    atm,
                    "setTaskResizeable",
                    id,
                    RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                )
            } catch (t: Throwable) {
                errors.add("resizeable=" + (t.message ?: t.javaClass.simpleName))
                try {
                    runAm("task", "resizeable", id.toString(), RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY.toString())
                } catch (shell: Throwable) {
                    errors.add("resizeable-shell=" + (shell.message ?: shell.javaClass.simpleName))
                }
            }
        }

        // The activity may recreate after being moved to the virtual display. Reassert
        // freeform + portrait task bounds until the ActivityRecord itself reports
        // width < height. Its requested orientation may remain landscape; that is OK.
        var geometry: TargetActivityGeometry? = null
        var fillsDisplay = false
        for (attempt in 0 until 20) {
            check(hostToken.isBinderAlive) { "Portrait host disconnected during configuration" }
            check(findTargetTaskIdOnDisplay(displayId) == id) { "Target task left portrait display" }
            if (atm != null) {
                try {
                    // The old setTaskWindowingMode Binder method is absent on
                    // current Android. A launch may initially fall back to
                    // fullscreen before freeform support settings are observed;
                    // resizeTask alone cannot change that mode. Apply both
                    // mode and bounds in one window-container transaction.
                    val task = getRunningTasks(atm).first { it.taskId == id }
                    configureTaskWindow(task, WINDOWING_MODE_FREEFORM, Rect(0, 0, width, height))
                } catch (t: Throwable) {
                    if (attempt == 0) {
                        errors.add("freeform=" + (t.cause?.message ?: t.message ?: t.javaClass.simpleName))
                    }
                }

                try {
                    invokeActivityTaskManager(
                        atm,
                        "resizeTask",
                        id,
                        Rect(0, 0, width, height),
                        RESIZE_MODE_SYSTEM
                    )
                } catch (t: Throwable) {
                    if (attempt == 0) {
                        errors.add("resize=" + (t.message ?: t.javaClass.simpleName))
                    }
                }
            }

            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
            }

            geometry = getTargetActivityGeometry(id)
            val bounds = invokeActivityTaskManager(atm, "getTaskBounds", id) as? Rect
            fillsDisplay = bounds == Rect(0, 0, width, height)
            if (geometry?.hasPortraitBounds == true && fillsDisplay) break
        }

        val portraitBounds = geometry?.hasPortraitBounds == true && fillsDisplay

        targetPortraitStatus =
            if (portraitBounds) {
                "Target app: portrait app bounds and full-display task verified; landscape policy unchanged; " +
                    geometry!!.describe() + "; display=$displayId " +
                    width + "x" + height
            } else {
                "Target app: portrait bounds not verified; taskFillsDisplay=$fillsDisplay; " +
                    (geometry?.describe() ?: "activity geometry unavailable") +
                    if (errors.isEmpty()) "" else "; " + errors.joinToString(" | ")
            }

        if (!portraitBounds) throw IllegalStateException(targetPortraitStatus)
        monitorTargetDisplay(displayId, hostToken)
        return true
    }

    private fun getLogicalDisplaySize(displayId: Int): Pair<Int, Int>? {
        HiddenApiBypass.addHiddenApiExemptions(
            "Landroid/hardware/display/DisplayManagerGlobal;", "Landroid/view/DisplayInfo;"
        )
        val displayGlobal = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val displayService = displayGlobal.getMethod("getInstance").invoke(null)
        val info = displayGlobal.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            .invoke(displayService, displayId) ?: return null
        return info.javaClass.getField("logicalWidth").getInt(info) to
            info.javaClass.getField("logicalHeight").getInt(info)
    }

    private fun monitorTargetDisplay(displayId: Int, hostToken: IBinder) {
        Thread({
            while (targetPortraitDisplayId == displayId && targetPortraitHostToken === hostToken) {
                try {
                    Thread.sleep(500)
                    synchronized(targetPortraitSessionLock) {
                        if (targetPortraitDisplayId != displayId || targetPortraitHostToken !== hostToken) return@Thread
                        if (!hostToken.isBinderAlive || getLogicalDisplaySize(displayId) == null) {
                            stopTargetPortraitDisplay(displayId, false)
                        } else {
                            val tasks = getRunningTasks(getActivityTaskManagerService())
                            val roots = invokeActivityTaskManager(getActivityTaskManagerService(),
                                "getAllRootTaskInfosOnDisplay", displayId) as? List<*>
                            val top = roots?.filterNotNull()?.firstOrNull { it.javaClass.getField("visible").getBoolean(it) }
                            val topActivity = top?.javaClass?.getField("topActivity")?.get(top) as? android.content.ComponentName
                            if (topActivity?.packageName == moe.shizuku.manager.BuildConfig.APPLICATION_ID &&
                                topActivity.className == PortraitFocusActivity::class.java.name &&
                                SystemClock.uptimeMillis() - lastFocusBridgeAt < 2000) return@synchronized
                            val external = topActivity?.packageName?.let { it != targetPackage } == true
                            // Billing/identity/permission screens may be secure or sized for
                            // the native display. Preserve their live result chain there.
                            val webHandled = !handoffFile.baseFile.exists() && isPhoneUnlocked() && try {
                                routePortraitWebLink(tasks, topActivity, displayId)
                            } catch (_: Exception) {
                                // Unsupported OEM inspection must not strand a browser
                                // or native screen behind the portrait host.
                                closingBrowser = null
                                false
                            }
                            if (webHandled) {
                                restoreSessionFocus()
                            } else if ((external || handoffFile.baseFile.exists()) && isPhoneUnlocked()) {
                                handoffTargetToPhone(displayId, hostToken)
                            } else if (tasks.none { getRunningTaskDisplayId(it) == displayId }) {
                                stopTargetPortraitDisplay(displayId, false)
                            } else {
                                restoreSessionFocus()
                            }
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    targetPortraitStatus = "Portrait display monitor: ${t.message}"
                    android.util.Log.w("AndroidControlService", "Portrait display monitor retry", t)
                    // Keep lifecycle recovery retryable; never silently abandon a
                    // live display because one task snapshot or handoff failed.
                }
            }
        }, "androidcontrol-portrait-display-monitor").apply { isDaemon = true }.start()
    }

    private fun routePortraitWebLink(
        tasks: List<ActivityManager.RunningTaskInfo>, top: android.content.ComponentName?, displayId: Int
    ): Boolean {
        val closing = closingBrowser
        if (closing != null) {
            val previous = tasks.firstOrNull { it.taskId == closing.taskId }
            // A single Back must actually dismiss the browser before we cover the
            // scene. Never keep popping a user's browser history to reach the game.
            if ((previous == null || previous.topActivity?.packageName == targetPackage) &&
                top?.packageName == targetPackage) {
                closingBrowser = null
                portraitPendingLink = closing.launch.url
                portraitBrowserVisible = true
                return true
            }
            if (closing.backPending && isActivityInputFocused(displayId, closing.component)) {
                // Task metadata changes before the new activity has a window.
                // Sending Back earlier can finish the game underneath it.
                val now = SystemClock.uptimeMillis()
                injectEvent(displayId, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
                injectEvent(displayId, KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
                closingBrowser = closing.copy(backPending = false, deadline = now + 2500)
                return true
            }
            if (SystemClock.uptimeMillis() < closing.deadline) return true
            closingBrowser = null
            return false
        }
        if (top == null || top.packageName == targetPackage || portraitBrowserVisible) return false
        val task = tasks.firstOrNull { getRunningTaskDisplayId(it) == displayId && it.topActivity == top }
            ?: return false
        // Only a general-purpose browser qualifies. Verified app links, identity,
        // billing and permission activities keep their live native result chain.
        val probe = android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://example.com/"))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE).setPackage(top.packageName)
        HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ResolveInfo;")
        if (shellContext.packageManager.queryIntentActivities(probe, 0).none {
                it.javaClass.getField("handleAllWebDataURI").getBoolean(it)
            }) return false
        val launch = PortraitWebLaunch.find(runCommand("/system/bin/dumpsys", "activity", "activities"),
            task.taskId, top.flattenToShortString(), targetPackage) ?: return false
        val atm = getActivityTaskManagerService()
        if (task.taskId !in portraitInitialTaskIds && task.numActivities == 1 &&
            task.baseActivity?.packageName == top.packageName) {
            // This browser task was created during this session. Do not remove
            // pre-existing browser tasks, tabs, or a task containing the game.
            if (invokeActivityTaskManager(atm, "removeTask", task.taskId) != true) return false
        } else if (task.baseActivity?.packageName != targetPackage) return false
        closingBrowser = BrowserClose(task.taskId, top, launch, SystemClock.uptimeMillis() + 5000,
            task.baseActivity?.packageName == targetPackage)
        return true
    }

    private fun isActivityInputFocused(displayId: Int, component: android.content.ComponentName): Boolean =
        runCommand("/system/bin/dumpsys", "input").lineSequence().any {
            val line = it.trim()
            line.startsWith("displayId=$displayId, name='") &&
                line.endsWith(" ${component.flattenToString()}'")
        }

    override fun injectTargetMotionEvent(displayId: Int, event: MotionEvent) {
        if (displayId <= 0 || displayId != targetPortraitDisplayId || !portraitSurfaceAttached ||
            portraitBrowserVisible || !isPhoneUnlocked()) return
        try {
            injectEvent(displayId, event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                portraitFocusNeedsRefresh = true
                restoreSessionFocus()
            }
        } catch (t: Throwable) {
            targetPortraitStatus = "Portrait input: ${t.cause?.message ?: t.message}"
        }
    }

    private fun injectEvent(displayId: Int, event: InputEvent) {
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/InputEvent;",
                "Landroid/hardware/input/InputManager;"
            )
        } catch (_: Throwable) {
        }

        try {
            val setDisplayId = Class.forName("android.view.InputEvent")
                .getDeclaredMethod(
                    "setDisplayId",
                    Int::class.javaPrimitiveType
                )
            setDisplayId.isAccessible = true
            setDisplayId.invoke(event, displayId)

            val inputManagerClass =
                Class.forName("android.hardware.input.InputManager")
            val getInstance =
                inputManagerClass.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            val inputManager = getInstance.invoke(null)
                ?: return

            val inject = inputManagerClass.methods.firstOrNull {
                it.name == "injectInputEvent" &&
                    it.parameterTypes.size == 2
            } ?: return

            inject.isAccessible = true
            // Wait for dispatch on the service's Binder thread, never the UI thread.
            // Restoring host focus before dispatch can redirect or drop a Back event.
            check(inject.invoke(inputManager, event, 2) == true) { "Input dispatch failed" }
        } catch (t: Throwable) {
            throw IllegalStateException("Could not forward input", t)
        }
    }

    override fun stopTargetPortraitDisplay(
        displayId: Int,
        relaunchOnDefaultDisplay: Boolean
    ) {
        synchronized(targetPortraitSessionLock) {
            // A delayed callback from an old Activity must not tear down a new session.
            if (displayId <= 0 || displayId != targetPortraitDisplayId) return
            unregisterTargetPortraitHostLocked()
            val failures = mutableListOf<String>()
            fun restore(action: () -> Unit) {
                try { action() } catch (t: Throwable) {
                    failures.add(t.cause?.message ?: t.message ?: t.javaClass.simpleName)
                }
            }
            val preservingNativeTask = handoffFile.baseFile.exists()
            if (preservingNativeTask) {
                restore { completeNativeHandoff() }
            } else {
                restore { restoreTargetDisplayTasks() }
                restore { runAm("force-stop", targetPackage) }
            }
            restore { restoreAndroid13FallbackTasksBestEffort() }
            restore { restoreAndroid13SupportSettingsBestEffort() }
            restore { restoreTargetPortraitCompat(preserveProcess = preservingNativeTask) }
            targetPortraitDisplayId = -1
            portraitSurfaceAttached = false
            ownedPortraitDisplay?.release()
            ownedPortraitDisplay = null
            if (hasTargetPortraitState() || portraitDisplayStateFile.exists()) {
                failures.add("Restoration records retained for retry")
            }
            if (failures.isNotEmpty()) {
                targetPortraitStatus = "Target app: restoration incomplete; " + failures.joinToString(" | ")
                throw IllegalStateException(targetPortraitStatus)
            }
            targetOwnerFile.delete()
            targetPortraitStatus = "$targetPackage: portrait display inactive; session task removed"
            if (relaunchOnDefaultDisplay && !preservingNativeTask) {
                val component = resolveTargetLauncherComponent()
                    ?: throw IllegalStateException("Cannot resolve the game's normal launcher")
                runAm("start", "--display", "0", "-n", component)
            }
        }
    }

    private fun restoreTargetDisplayTasks() {
        if (!portraitDisplayStateFile.exists()) return
        val saved = portraitDisplayStateFile.readLines().map { it.trim().toInt() }
        val displayId = saved.first()
        check(displayId > 0) { "Invalid saved portrait display" }
        val atm = getActivityTaskManagerService()
        // Query before force-stop so no task vanishes from getTasks while retaining
        // a changed resize mode in recents. This also catches am start succeeding
        // before launch verification fails or the service dies.
        val running = getRunningTasks(atm)
        val recent = getRecentTasks(atm)
        val sessionIds = saved.drop(1).toMutableSet()
        running.filter { info ->
            getRunningTaskDisplayId(info) == displayId &&
                info.baseActivity?.packageName == targetPackage
        }.forEach { sessionIds.add(it.taskId) }
        // Save discovered tasks before removing any of them; a partial failure is retryable.
        portraitDisplayStateFile.writeText((listOf(displayId) + sessionIds).joinToString("\n"))
        sessionIds.forEach { id ->
            val task = (running + recent).firstOrNull { it.taskId == id }
            if (task != null) {
                val packageName = task.baseActivity?.packageName ?: task.baseIntent.component?.packageName
                check(packageName != null) { "Cannot verify owner of saved task $id" }
                if (packageName == targetPackage) {
                    val removed = invokeActivityTaskManager(atm, "removeTask", id) as? Boolean
                    check(removed == true) { "Could not remove portrait session task $id" }
                }
            }
        }
        portraitDisplayStateFile.delete()
    }

    private fun getRunningTasks(atm: Any): List<ActivityManager.RunningTaskInfo> {
        var failure: Throwable? = null
        for (method in atm.javaClass.methods.filter { it.name == "getTasks" }) {
            val args: Array<Any?> = when (method.parameterTypes.size) {
                1 -> arrayOf(Int.MAX_VALUE)
                2 -> arrayOf(Int.MAX_VALUE, false)
                3 -> arrayOf(Int.MAX_VALUE, false, false)
                4 -> arrayOf(Int.MAX_VALUE, false, false, -1)
                else -> continue
            }
            try {
                method.isAccessible = true
                val tasks = method.invoke(atm, *args) as? List<*>
                    ?: error("Task list is unavailable")
                return tasks.filterIsInstance<ActivityManager.RunningTaskInfo>()
            } catch (t: Throwable) { failure = t }
        }
        throw IllegalStateException("Cannot inspect tasks for restoration", failure)
    }

    private fun getRecentTasks(atm: Any): List<android.app.TaskInfo> {
        val userId = runAm("get-current-user").trim().toInt()
        val slice = invokeActivityTaskManager(atm, "getRecentTasks", Int.MAX_VALUE, 0, userId)
            ?: error("Recent task list is unavailable")
        val method = slice.javaClass.getMethod("getList")
        method.isAccessible = true
        val list = method.invoke(slice) as? List<*> ?: error("Recent tasks are unavailable")
        return list.filterIsInstance<android.app.TaskInfo>()
    }

    private fun registerTargetPortraitHost(
        hostToken: IBinder,
        displayId: Int
    ) {
        synchronized(targetPortraitSessionLock) {
            unregisterTargetPortraitHostLocked()

            val deathRecipient = IBinder.DeathRecipient {
                Thread({
                    try {
                        synchronized(targetPortraitSessionLock) {
                            if (targetPortraitHostToken === hostToken) {
                                stopTargetPortraitDisplay(displayId, false)
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }, "androidcontrol-portrait-host-death").start()
            }

            hostToken.linkToDeath(deathRecipient, 0)
            targetPortraitHostToken = hostToken
            targetPortraitHostDeathRecipient = deathRecipient
        }
    }

    private fun unregisterTargetPortraitHost() {
        synchronized(targetPortraitSessionLock) {
            unregisterTargetPortraitHostLocked()
        }
    }

    private fun unregisterTargetPortraitHostLocked() {
        portraitPendingLink = null
        portraitBrowserVisible = false
        closingBrowser = null
        val token = targetPortraitHostToken
        val recipient = targetPortraitHostDeathRecipient

        targetPortraitHostToken = null
        targetPortraitHostDeathRecipient = null

        if (token != null && recipient != null) {
            try {
                token.unlinkToDeath(recipient, 0)
            } catch (_: Throwable) {
            }
        }
    }

    private fun getPortraitRotation(): Int {
        val output = runWm("size")
        val match = Regex("""Physical size:\s*(\d+)x(\d+)""").find(output)
            ?: return 0
        val width = match.groupValues[1].toIntOrNull() ?: return 0
        val height = match.groupValues[2].toIntOrNull() ?: return 0

        // Rotation values are relative to the panel's natural orientation.
        return if (height >= width) 0 else 1
    }

    private fun enableTargetPortraitCompat(): Set<String> {
        val sdk = getSdkInt()
        targetCompatStateFile.delete()
        val applied = linkedSetOf<String>()

        fun applyCompat(mode: String, changeId: String) {
            // Record intent before applying. If the process dies after the compat
            // command succeeds, Restore can still reset this ID on the next run.
            appendTargetCompatChange(changeId)
            try {
                val output = runAm(
                    "compat", mode, "--no-kill", changeId, targetPackage
                )
                if (compatOutputSaysUnknown(output)) {
                    removeTargetCompatChange(changeId)
                    return
                }
                applied.add(changeId)
            } catch (_: Throwable) {
                // This call definitely did not complete successfully, so remove the
                // provisional ledger entry. A process death after a successful call
                // still leaves the pre-written entry available for later restoration.
                removeTargetCompatChange(changeId)
            }
        }

        if (sdk >= 33) {
            applyCompat("disable", FORCE_NON_RESIZE_APP)
        }

        applyCompat("disable", NEVER_SANDBOX_DISPLAY_APIS)
        applyCompat("enable", ALWAYS_SANDBOX_DISPLAY_APIS)
        applyCompat("enable", OVERRIDE_SANDBOX_VIEW_BOUNDS_APIS)
        applyCompat("enable", FORCE_RESIZE_APP)

        // These orientation overrides landed during the Android 13 QPR cycle.
        // Do not gate them behind API 34: OEM Android 13 builds may already expose
        // them through PlatformCompat. Unsupported builds simply reject the command
        // and applyCompat removes the provisional ledger entry.
        if (sdk >= 33) {
            // If an OEM enabled the camera-only gate, disable it so the portrait
            // treatment applies to the game at all times, not only while using camera.
            applyCompat("disable", OVERRIDE_ORIENTATION_ONLY_FOR_CAMERA)
            applyCompat("enable", OVERRIDE_ANY_ORIENTATION)
            applyCompat("enable", OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT)
        }

        if (sdk >= 35) {
            applyCompat("enable", OVERRIDE_ANY_ORIENTATION_TO_USER)
        }

        return applied
    }

    private fun compatOutputSaysUnknown(output: String): Boolean {
        val text = output.lowercase()
        return text.contains("not known yet") ||
            text.contains("unknown change") ||
            text.contains("unknown or invalid change") ||
            text.contains("no such change") ||
            text.contains("could have no effect")
    }

    @Synchronized
    private fun appendTargetCompatChange(changeId: String) {
        val ids =
            if (targetCompatStateFile.exists()) {
                targetCompatStateFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toMutableSet()
            } else {
                linkedSetOf()
            }

        if (ids.add(changeId)) {
            targetCompatStateFile.writeText(ids.joinToString("\n"))
        }
    }

    @Synchronized
    private fun removeTargetCompatChange(changeId: String) {
        if (!targetCompatStateFile.exists()) return

        val ids = targetCompatStateFile.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != changeId }
            .distinct()

        if (ids.isEmpty()) {
            targetCompatStateFile.delete()
        } else {
            targetCompatStateFile.writeText(ids.joinToString("\n"))
        }
    }

    private fun restoreTargetPortraitCompat(preserveProcess: Boolean = false) {
        val ids =
            if (targetCompatStateFile.exists()) {
                targetCompatStateFile.readLines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            } else {
                emptyList()
            }

        val failedIds = mutableListOf<String>()

        ids.forEach { changeId ->
            try {
                if (preserveProcess) runAm("compat", "reset", "--no-kill", changeId, targetPackage)
                else runAm("compat", "reset", changeId, targetPackage)
            } catch (t: Throwable) {
                if (!isIgnorableCompatResetFailure(t)) {
                    failedIds.add(changeId)
                }
            }
        }

        if (failedIds.isEmpty()) {
            targetCompatStateFile.delete()
            return
        }

        targetCompatStateFile.writeText(failedIds.joinToString("\n"))
        throw IllegalStateException(
            "Could not restore ${failedIds.size} compatibility override(s) for " +
                targetPackage + "; they were retained for retry."
        )
    }

    private fun isIgnorableCompatResetFailure(t: Throwable): Boolean {
        val message = (t.message ?: "").lowercase()
        return message.contains("unknown change") ||
            message.contains("unknown id") ||
            message.contains("no such change") ||
            message.contains("not a known change") ||
            message.contains("unknown package") ||
            message.contains("package not found")
    }

    private fun startPortraitAppWatcher() {
        if (portraitWatcherRunning) return

        portraitWatcherRunning = true
        val thread = Thread({
            var activeTaskId: Int? = null
            var firstSeenAt = 0L
            var configured = false

            while (portraitWatcherRunning) {
                try {
                    val taskId = findTargetTaskId()
                    if (taskId == null) {
                        activeTaskId = null
                        firstSeenAt = 0L
                        configured = false
                        targetPortraitStatus =
                            "Target app: waiting for " + targetPackage +
                                " to become foreground"
                    } else {
                        if (activeTaskId != taskId) {
                            activeTaskId = taskId
                            firstSeenAt = System.currentTimeMillis()
                            configured = false
                        }

                        val geometry = getTargetActivityGeometry(taskId)
                        if (geometry?.hasPortraitBounds == true) {
                            configured = true
                            targetPortraitStatus =
                                "Target app: portrait-shaped app bounds active; " +
                                    geometry.describe() + "; " +
                                    describeTargetTask(taskId)
                        } else if (!configured) {
                            val graceMs =
                                if (android13OrientationOverrideAccepted) 2000L else 0L
                            val elapsed = System.currentTimeMillis() - firstSeenAt

                            if (elapsed < graceMs) {
                                targetPortraitStatus =
                                    "Target app: orientation override accepted; " +
                                        "waiting for activity recreation; " +
                                        (geometry?.describe()
                                            ?: "activity geometry unavailable")
                            } else {
                                synchronized(portraitOperationLock) {
                                    if (portraitWatcherRunning) {
                                        configured =
                                            enforceAndroid13PortraitTask(taskId)
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    targetPortraitStatus =
                        "Target game fallback error: " +
                            (t.message ?: t.javaClass.simpleName)
                }

                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "androidcontrol-target-app-portrait")

        portraitWatcherThread = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun stopPortraitAppWatcher() {
        portraitWatcherRunning = false
        portraitWatcherThread?.interrupt()

        synchronized(portraitOperationLock) {
        }

        portraitWatcherThread = null
    }

    private fun findTargetTaskId(): Int? {
        // Prefer the binder task list. This avoids depending on OEM-specific dumpsys
        // formatting, which was a likely reason the Android 13 fallback never ran.
        try {
            val atm = getActivityTaskManagerService()
            val methods = atm.javaClass.methods
                .filter { it.name == "getTasks" }
                .sortedBy { it.parameterTypes.size }

            methods.forEach { method ->
                val args: Array<Any?> = when (method.parameterTypes.size) {
                    1 -> arrayOf(100)
                    2 -> arrayOf(100, false)
                    3 -> arrayOf(100, false, false)
                    4 -> arrayOf(100, false, false, -1)
                    else -> return@forEach
                }

                try {
                    @Suppress("UNCHECKED_CAST")
                    val tasks = method.invoke(atm, *args) as? List<*> ?: return@forEach

                    tasks.forEach { entry ->
                        val info = entry as? ActivityManager.RunningTaskInfo
                            ?: return@forEach
                        val topPackage = info.topActivity?.packageName
                        val basePackage = info.baseActivity?.packageName

                        if (
                            topPackage == targetPackage ||
                            basePackage == targetPackage
                        ) {
                            return info.taskId
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        // Fallback for vendor builds whose IActivityTaskManager proxy does not
        // expose a callable getTasks signature to reflection.
        val dump = runCommand("/system/bin/dumpsys", "activity", "activities")
        val lines = dump.lineSequence().toList()

        val prioritized = lines.firstOrNull { line ->
            line.contains(targetPackage) &&
                (
                    line.contains("topResumedActivity") ||
                    line.contains("mResumedActivity") ||
                    line.contains("mFocusedApp")
                )
        }

        val candidate = prioritized ?: lines.firstOrNull { line ->
            line.contains(targetPackage) && Regex("""\bt\d+\b""").containsMatchIn(line)
        } ?: return null

        return Regex("""\bt(\d+)\b""")
            .find(candidate)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun isTargetRunningOrTaskPresent(): Boolean {
        if (findTargetTaskId() != null) return true

        return try {
            runCommand("/system/bin/pidof", targetPackage).trim().isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveTargetLauncherComponent(packageName: String = targetPackage): String? {
        return try {
            runCommand(
                "/system/bin/cmd",
                "package",
                "resolve-activity",
                "--brief",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                packageName
            )
                .lineSequence()
                .map { it.trim() }
                .lastOrNull {
                    it.startsWith("$packageName/")
                }
        } catch (_: Throwable) {
            null
        }
    }

    private fun findTargetTaskIdOnDisplay(displayId: Int): Int? {
        try {
            val atm = getActivityTaskManagerService()
            val methods = atm.javaClass.methods
                .filter { it.name == "getTasks" }
                .sortedBy { it.parameterTypes.size }

            methods.forEach { method ->
                val args: Array<Any?> = when (method.parameterTypes.size) {
                    1 -> arrayOf(100)
                    2 -> arrayOf(100, false)
                    3 -> arrayOf(100, false, false)
                    4 -> arrayOf(100, false, false, -1)
                    else -> return@forEach
                }

                try {
                    @Suppress("UNCHECKED_CAST")
                    val tasks =
                        method.invoke(atm, *args) as? List<*> ?: return@forEach

                    tasks.forEach { entry ->
                        val info =
                            entry as? ActivityManager.RunningTaskInfo
                                ?: return@forEach
                        val packageName =
                            info.topActivity?.packageName
                                ?: info.baseActivity?.packageName

                        if (
                            packageName == targetPackage &&
                            getRunningTaskDisplayId(info) == displayId
                        ) {
                            return info.taskId
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        return null
    }

    private fun getRunningTaskDisplayId(
        info: ActivityManager.RunningTaskInfo
    ): Int? {
        // TaskInfo.displayId exists on the Android runtime used here, but it is
        // absent from this project's compile-time public stubs on some API levels.
        // Resolve it at runtime instead of baking a newer SDK field reference into
        // the APK.
        try {
            val field = info.javaClass.getField("displayId")
            return field.getInt(info)
        } catch (_: Throwable) {
        }

        try {
            var clazz: Class<*>? = info.javaClass
            while (clazz != null) {
                try {
                    val field = clazz.getDeclaredField("displayId")
                    field.isAccessible = true
                    return field.getInt(info)
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
        } catch (_: Throwable) {
        }

        try {
            val method = info.javaClass.methods.firstOrNull {
                it.name == "getDisplayId" && it.parameterTypes.isEmpty()
            }
            val value = method?.invoke(info)
            if (value is Int) return value
        } catch (_: Throwable) {
        }

        return null
    }

    private fun enableTargetDisplayCompat() {
        val sdk = getSdkInt()
        check(!targetCompatStateFile.exists()) { "Compatibility restoration is still pending" }

        fun applyCompat(mode: String, changeId: String) {
            appendTargetCompatChange(changeId)
            try {
                val output = runAm(
                    "compat",
                    mode,
                    "--no-kill",
                    changeId,
                    targetPackage
                )
                if (compatOutputSaysUnknown(output)) {
                    removeTargetCompatChange(changeId)
                }
            } catch (_: Throwable) {
                // Retain the prewritten intent: command failure may be ambiguous.
            }
        }

        if (sdk >= 33) {
            applyCompat("disable", FORCE_NON_RESIZE_APP)
        }
        applyCompat("disable", NEVER_SANDBOX_DISPLAY_APIS)
        applyCompat("enable", ALWAYS_SANDBOX_DISPLAY_APIS)
        applyCompat("enable", OVERRIDE_SANDBOX_VIEW_BOUNDS_APIS)
        applyCompat("enable", FORCE_RESIZE_APP)
    }

    private fun restartTargetGameBestEffort(preferFreeform: Boolean) {
        targetPortraitStatus =
            "Target app: restarting once to apply Android 13 portrait compatibility"

        try {
            runAm("force-stop", targetPackage)
        } catch (_: Throwable) {
            return
        }

        try {
            Thread.sleep(250)
        } catch (_: InterruptedException) {
        }

        val component = try {
            runCommand(
                "/system/bin/cmd",
                "package",
                "resolve-activity",
                "--brief",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                targetPackage
            )
                .lineSequence()
                .map { it.trim() }
                .lastOrNull { it.contains('/') && !it.startsWith("No activity") }
        } catch (_: Throwable) {
            null
        }

        if (component != null) {
            if (!preferFreeform) {
                // Let the activity recreate normally first. The watcher verifies its
                // real app bounds and escalates to freeform if width still exceeds height.
                // A landscape orientation enum by itself is not considered a failure.
                try {
                    runAm("start", "-n", component)
                    return
                } catch (_: Throwable) {
                }
            }

            // Fallback: launch directly into freeform. The watcher then applies the
            // exact portrait task bounds and verifies the activity's own app bounds.
            prepareAndroid13FreeformSupportBestEffort()

            try {
                runAm(
                    "start",
                    "--windowingMode",
                    WINDOWING_MODE_FREEFORM.toString(),
                    "-n",
                    component
                )
                return
            } catch (_: Throwable) {
            }

            try {
                runAm("start", "-n", component)
                return
            } catch (_: Throwable) {
            }
        }

        try {
            runCommand(
                "/system/bin/monkey",
                "-p",
                targetPackage,
                "-c",
                "android.intent.category.LAUNCHER",
                "1"
            )
        } catch (_: Throwable) {
            targetPortraitStatus =
                "Target app: compatibility applied; reopen the game manually"
        }
    }

    private fun enforceAndroid13PortraitTask(taskId: Int): Boolean {
        val size = getPortraitDisplayBounds()

        val before = getTargetActivityGeometry(taskId)
        if (before?.hasPortraitBounds == true) {
            targetPortraitStatus =
                "Target app: portrait activity already active; " +
                    before.describe() + "; " + describeTargetTask(taskId)
            return true
        }

        targetPortraitStatus =
            "Target app: app bounds are still landscape-shaped; applying Android 13 fallback; " +
                (before?.describe() ?: "activity geometry unavailable")

        // Record before the first resize/windowing mutation. Even if every attempt
        // fails afterward, Restore normal rotation must know this task may have had
        // its resize mode or windowing mode changed.
        rememberFallbackTask(taskId)

        val directBinderError =
            tryResizeTargetTaskWithBinder(taskId, size, forceFreeform = false)

        waitForActivityConfigurationUpdate()
        if (hasTargetPortraitBounds(taskId)) {
            val geometry = getTargetActivityGeometry(taskId)
            targetPortraitStatus =
                "Target app: portrait-shaped app bounds active after resize; " +
                    (geometry?.describe() ?: "activity geometry unavailable") +
                    "; " + describeTargetTask(taskId)
            return true
        }

        prepareAndroid13FreeformSupportBestEffort()

        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }

        val freeformBinderError =
            tryResizeTargetTaskWithBinder(taskId, size, forceFreeform = true)

        waitForActivityConfigurationUpdate()
        if (hasTargetPortraitBounds(taskId)) {
            val geometry = getTargetActivityGeometry(taskId)
            targetPortraitStatus =
                "Target app: portrait-shaped app bounds active in freeform; " +
                    (geometry?.describe() ?: "activity geometry unavailable") +
                    "; " + describeTargetTask(taskId)
            return true
        }

        val shellError = tryResizeTargetTaskWithShell(taskId, size)

        waitForActivityConfigurationUpdate()
        if (hasTargetPortraitBounds(taskId)) {
            val geometry = getTargetActivityGeometry(taskId)
            targetPortraitStatus =
                "Target app: portrait-shaped app bounds active after shell resize; " +
                    (geometry?.describe() ?: "activity geometry unavailable") +
                    "; " + describeTargetTask(taskId)
            return true
        }

        val details = listOfNotNull(
            directBinderError,
            freeformBinderError,
            shellError
        )
            .distinct()
            .joinToString(" | ")

        val activityGeometry =
            getTargetActivityGeometry(taskId)?.describe()
                ?: "activity geometry unavailable"
        val taskGeometry = describeTargetTask(taskId)

        targetPortraitStatus =
            if (details.isBlank()) {
                "Target app: task changed but activity remained landscape; " +
                    activityGeometry + "; " + taskGeometry
            } else {
                "Target app: portrait-shaped app bounds failed: " + details + "; " +
                    activityGeometry + "; " + taskGeometry
            }

        return false
    }

    private fun tryResizeTargetTaskWithBinder(
        taskId: Int,
        size: Pair<Int, Int>,
        forceFreeform: Boolean
    ): String? {
        return try {
            val atm = getActivityTaskManagerService()

            invokeActivityTaskManager(
                atm,
                "setTaskResizeable",
                taskId,
                RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
            )

            if (forceFreeform) {
                val result = invokeActivityTaskManager(
                    atm,
                    "setTaskWindowingMode",
                    taskId,
                    WINDOWING_MODE_FREEFORM,
                    true
                )
                if (result is Boolean && !result) {
                    throw IllegalStateException(
                        "setTaskWindowingMode(FREEFORM) returned false"
                    )
                }
            }

            val result = invokeActivityTaskManager(
                atm,
                "resizeTask",
                taskId,
                Rect(0, 0, size.first, size.second),
                RESIZE_MODE_SYSTEM
            )
            if (result is Boolean && !result) {
                throw IllegalStateException("resizeTask returned false")
            }

            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    private fun tryResizeTargetTaskWithShell(
        taskId: Int,
        size: Pair<Int, Int>
    ): String? {
        return try {
            runAm("task", "resizeable", taskId.toString(), "2")
            runAm(
                "task",
                "resize",
                taskId.toString(),
                "0",
                "0",
                size.first.toString(),
                size.second.toString()
            )
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    private data class TargetActivityGeometry(
        val width: Int,
        val height: Int,
        val orientation: String?
    ) {
        val hasPortraitBounds: Boolean
            get() = width < height

        fun describe(): String {
            val orientationText = orientation ?: "orientation?"
            return "orientation=" + orientationText +
                " appBounds=" + width + "×" + height +
                if (hasPortraitBounds) " (portrait-shaped bounds)" else " (landscape-shaped bounds)"
        }
    }

    private fun waitForActivityConfigurationUpdate() {
        try {
            Thread.sleep(500)
        } catch (_: InterruptedException) {
        }
    }

    private fun hasTargetPortraitBounds(taskId: Int): Boolean {
        return getTargetActivityGeometry(taskId)?.hasPortraitBounds == true
    }

    private fun getTargetActivityGeometry(taskId: Int): TargetActivityGeometry? {
        val dump = try {
            runCommand("/system/bin/dumpsys", "activity", "activities")
        } catch (_: Throwable) {
            return null
        }

        val geometry = PortraitActivityGeometry.parse(dump, targetPackage, taskId) ?: return null
        return TargetActivityGeometry(geometry.width, geometry.height, geometry.orientation)
    }

    private fun describeTargetTask(taskId: Int): String {
        try {
            val atm = getActivityTaskManagerService()
            val bounds = invokeActivityTaskManager(
                atm,
                "getTaskBounds",
                taskId
            ) as? Rect

            if (bounds != null && !bounds.isEmpty) {
                return "task=$taskId bounds=${bounds.width()}×${bounds.height()}"
            }
        } catch (_: Throwable) {
        }

        val dump = try {
            runCommand("/system/bin/dumpsys", "activity", "activities")
        } catch (_: Throwable) {
            return "task=$taskId geometry unavailable"
        }

        val taskLine = dump.lineSequence().firstOrNull { line ->
            (
                line.contains("#$taskId") ||
                    line.contains("taskId=$taskId")
                ) &&
                (line.contains("bounds=") || line.contains("windowingMode="))
        }

        return if (taskLine != null) {
            "task=$taskId " + taskLine.trim().take(220)
        } else {
            "task=$taskId geometry unavailable"
        }
    }

    private fun isTaskPortrait(taskId: Int): Boolean {
        try {
            val atm = getActivityTaskManagerService()
            val bounds = invokeActivityTaskManager(
                atm,
                "getTaskBounds",
                taskId
            ) as? Rect

            if (bounds != null && !bounds.isEmpty) {
                return bounds.width() < bounds.height()
            }
        } catch (_: Throwable) {
        }

        val dump = try {
            runCommand("/system/bin/dumpsys", "activity", "activities")
        } catch (_: Throwable) {
            return false
        }

        val taskLine = dump.lineSequence().firstOrNull { line ->
            (
                line.contains("#$taskId") ||
                    line.contains("taskId=$taskId")
                ) &&
                line.contains("bounds=")
        } ?: return false

        val bracketBounds = Regex(
            """bounds=\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""
        ).find(taskLine)

        if (bracketBounds != null) {
            val left = bracketBounds.groupValues[1].toInt()
            val top = bracketBounds.groupValues[2].toInt()
            val right = bracketBounds.groupValues[3].toInt()
            val bottom = bracketBounds.groupValues[4].toInt()
            return right - left < bottom - top
        }

        val rectBounds = Regex(
            """bounds=Rect\((\d+),\s*(\d+)\s*-\s*(\d+),\s*(\d+)\)"""
        ).find(taskLine)

        if (rectBounds != null) {
            val left = rectBounds.groupValues[1].toInt()
            val top = rectBounds.groupValues[2].toInt()
            val right = rectBounds.groupValues[3].toInt()
            val bottom = rectBounds.groupValues[4].toInt()
            return right - left < bottom - top
        }

        return false
    }

    private fun prepareAndroid13FreeformSupportBestEffort() {
        if (!freeformSupportStateFile.exists()) {
            try {
                val previous = runSettings(
                    "get",
                    "global",
                    "enable_freeform_support"
                ).trim()
                freeformSupportStateFile.writeText(
                    previous.ifEmpty { "null" }
                )
                if (previous != "1") {
                    runSettings(
                        "put",
                        "global",
                        "enable_freeform_support",
                        "1"
                    )
                }
            } catch (_: Throwable) {
            }
        }

        if (!multiWindowConfigStateFile.exists()) {
            try {
                val output = runWm("get-multi-window-config")
                val supports = Regex(
                    """Supports non-resizable in multi window:\s*(-?\d+)"""
                ).find(output)?.groupValues?.getOrNull(1)
                val respects = Regex(
                    """Respects activity min width/height in multi window:\s*(-?\d+)"""
                ).find(output)?.groupValues?.getOrNull(1)

                if (supports != null && respects != null) {
                    multiWindowConfigStateFile.writeText(
                        supports + "\t" + respects
                    )
                }
            } catch (_: Throwable) {
            }
        }

        if (!multiWindowConfigStateFile.exists()) return
        try {
            runWm(
                "set-multi-window-config",
                "--supportsNonResizable",
                "1",
                "--respectsActivityMinWidthHeight",
                "-1"
            )
        } catch (_: Throwable) {
        }
    }

    private fun restoreAndroid13SupportSettingsBestEffort() {
        if (multiWindowConfigStateFile.exists()) {
            try {
                val parts = multiWindowConfigStateFile.readText()
                    .trim()
                    .split('\t')

                check(parts.size == 2) { "Invalid saved multi-window configuration" }
                if (parts.size == 2) {
                    runWm(
                        "set-multi-window-config",
                        "--supportsNonResizable",
                        parts[0],
                        "--respectsActivityMinWidthHeight",
                        parts[1]
                    )
                }
                multiWindowConfigStateFile.delete()
            } catch (_: Throwable) {
            }
        }

        if (freeformSupportStateFile.exists()) {
            try {
                val previous = freeformSupportStateFile.readText().trim()
                if (previous.isEmpty() || previous == "null") {
                    runSettings(
                        "delete",
                        "global",
                        "enable_freeform_support"
                    )
                } else {
                    runSettings(
                        "put",
                        "global",
                        "enable_freeform_support",
                        previous
                    )
                }
                freeformSupportStateFile.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun restoreAndroid13FallbackTasksBestEffort() {
        if (!fallbackTaskStateFile.exists()) return
        val atm = getActivityTaskManagerService()
        val tasks = getRunningTasks(atm) + getRecentTasks(atm)
        val pending = mutableListOf<Int>()
        fallbackTaskStateFile.readLines().map { it.trim().toInt() }.distinct().forEach { id ->
            val info = tasks.firstOrNull { it.taskId == id } ?: return@forEach
            val owner = info.baseActivity?.packageName ?: info.baseIntent.component?.packageName
            if (owner != targetPackage) {
                if (owner == null) pending.add(id)
                return@forEach
            }
            var failed = false
            // Attempt each restoration independently. Keep failed entries for retry.
            try {
                invokeActivityTaskManager(atm, "setTaskResizeable", id, 0)
            } catch (_: Throwable) { failed = true }
            try {
                invokeActivityTaskManager(atm, "setTaskWindowingMode", id, WINDOWING_MODE_FULLSCREEN, false)
            } catch (_: Throwable) { failed = true }
            if (failed) pending.add(id)
        }
        if (pending.isEmpty()) fallbackTaskStateFile.delete()
        else fallbackTaskStateFile.writeText(pending.joinToString("\n"))
    }

    @Synchronized
    private fun rememberFallbackTask(taskId: Int) {
        val ids =
            if (fallbackTaskStateFile.exists()) {
                fallbackTaskStateFile.readLines()
                    .mapNotNull { it.trim().toIntOrNull() }
                    .toMutableSet()
            } else {
                mutableSetOf()
            }

        if (ids.add(taskId)) {
            fallbackTaskStateFile.writeText(ids.sorted().joinToString("\n"))
        }
    }


    private fun getActivityTaskManagerService(): Any {
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/app/ActivityTaskManager;",
                "Landroid/app/IActivityTaskManager;",
                "Landroid/app/TaskInfo;",
                "Landroid/content/pm/ParceledListSlice;",
                "Landroid/content/pm/BaseParceledListSlice;"
            )
        } catch (_: Throwable) {
        }

        val clazz = Class.forName("android.app.ActivityTaskManager")
        val method = clazz.getDeclaredMethod("getService")
        method.isAccessible = true
        return method.invoke(null)
            ?: throw IllegalStateException("ActivityTaskManager service is unavailable")
    }

    private fun invokeActivityTaskManager(
        service: Any,
        methodName: String,
        vararg args: Any?
    ): Any? {
        val method = service.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == args.size
        } ?: throw NoSuchMethodException(
            "${service.javaClass.name}.$methodName/${args.size}"
        )

        method.isAccessible = true
        return method.invoke(service, *args)
    }

    private fun getPortraitDisplayBounds(): Pair<Int, Int> {
        val output = runWm("size")
        val override = Regex("""Override size:\s*(\d+)x(\d+)""").find(output)
        val physical = Regex("""Physical size:\s*(\d+)x(\d+)""").find(output)
        val match = override ?: physical
            ?: throw IllegalStateException("Unable to determine display size")

        val first = match.groupValues[1].toInt()
        val second = match.groupValues[2].toInt()
        return minOf(first, second) to maxOf(first, second)
    }

    private fun getSdkInt(): Int {
        return runCommand("/system/bin/getprop", "ro.build.version.sdk")
            .trim()
            .toIntOrNull() ?: 0
    }

    private fun runWmHelp(): String {
        val command = listOf("/system/bin/wm", "help")
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.readText()
        }

        // Some Android builds print valid wm help text but return 255.
        // For capability detection the help text itself is what matters.
        process.waitFor()
        if (output.isBlank()) {
            throw IllegalStateException("wm help returned no output")
        }
        return output
    }

    private fun runWm(vararg args: String): String {
        return runCommand("/system/bin/wm", *args)
    }

    private fun runSettings(vararg args: String): String {
        return runCommand("/system/bin/settings", *args)
    }

    private fun runAm(vararg args: String): String {
        return runCommand("/system/bin/am", *args)
    }

    private fun runCommand(executable: String, vararg args: String): String {
        val command = ArrayList<String>(args.size + 1)
        command.add(executable)
        command.addAll(args)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            reader.readText()
        }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(
                "${command.joinToString(" ")} failed with exit code $exitCode: ${output.trim()}"
            )
        }

        return output
    }
}
