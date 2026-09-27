package moe.shizuku.manager.control

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
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

    private var serviceContext: Context? = null

    @Volatile
    private var shellPackageContext: Context? = null

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context.applicationContext
    }

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
        "Target game: overrides inactive"

    @Volatile
    private var portraitVirtualDisplay: VirtualDisplay? = null

    @Volatile
    private var portraitVirtualDisplayId = Display.INVALID_DISPLAY

    private val portraitVirtualDisplayLock = Any()

    private companion object {
        const val TARGET_PACKAGE = "game.qualiarts.hololive.dreams.jp"

        const val FORCE_RESIZE_APP = "174042936"
        const val FORCE_NON_RESIZE_APP = "181136395"
        const val NEVER_SANDBOX_DISPLAY_APIS = "184838306"
        const val ALWAYS_SANDBOX_DISPLAY_APIS = "185004937"
        const val OVERRIDE_SANDBOX_VIEW_BOUNDS_APIS = "237531167"
        const val OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT = "265452344"
        const val OVERRIDE_ANY_ORIENTATION = "265464455"
        const val OVERRIDE_ANY_ORIENTATION_TO_USER = "310816437"

        const val WINDOWING_MODE_FULLSCREEN = 1
        const val WINDOWING_MODE_FREEFORM = 5
        const val RESIZE_MODE_SYSTEM = 0

        const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 shl 0
        const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10
        const val INPUT_INJECTION_MODE_ASYNC = 0
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun setForcePortrait(enabled: Boolean): Boolean {
        if (wmApi == WmApi.UNSUPPORTED) {
            throw UnsupportedOperationException(
                "This Android build does not expose the required WindowManager rotation controls."
            )
        }

        if (enabled) {
            targetPortraitStatus =
                "Target game: waiting for $TARGET_PACKAGE to become foreground"
            val portraitRotation = getPortraitRotation()

            // Apply the actual display-orientation policy first. The old implementation
            // spent up to a minute changing compat flags for every installed app before
            // reaching these commands, so a slow/unsupported compat command could make
            // the button appear to do nothing.
            try {
                when (wmApi) {
                    WmApi.MODERN -> {
                        runWm("user-rotation", "lock", portraitRotation.toString())
                        runWm("fixed-to-user-rotation", "enabled")
                        if (supportsIgnoreOrientationRequest) {
                            runWm("set-ignore-orientation-request", "true")
                        }
                    }

                    WmApi.LEGACY -> {
                        runWm("set-user-rotation", "lock", portraitRotation.toString())
                        runWm("set-fix-to-user-rotation", "enabled")
                    }

                    WmApi.UNSUPPORTED -> error("unreachable")
                }
            } catch (t: Throwable) {
                restoreNormalRotationBestEffort()
                throw t
            }

            // Per-app enhancement layers are intentionally limited to the target game.
            // Clean any stale target-only ledger from an interrupted newer run before
            // applying a fresh set. Do not touch the global force_resizable_activities
            // developer setting here.
            try {
                restoreTargetPortraitCompat()
            } catch (_: Throwable) {
            }
            try {
                enableTargetPortraitCompat()
            } catch (_: Throwable) {
            }

            if (getSdkInt() == 33) {
                startPortraitAppWatcher()
            }

            val forced = isForcePortraitEnabled()
            if (!forced) {
                // Never leave compatibility or task changes behind when the core
                // WindowManager portrait policy did not actually stick.
                restoreNormalRotationBestEffort()
                return false
            }
        } else {
            restoreNormalRotation()
            targetPortraitStatus = "Target game: overrides inactive"
        }

        return isForcePortraitEnabled()
    }

    override fun isForcePortraitEnabled(): Boolean {
        if (wmApi == WmApi.UNSUPPORTED) {
            return false
        }

        val portraitRotation = getPortraitRotation()

        val forced = when (wmApi) {
            WmApi.MODERN -> {
                val userRotation = runWm("user-rotation").trim()
                val fixedToUserRotation = runWm("fixed-to-user-rotation").trim()

                val ignoreOrientationOk =
                    if (supportsIgnoreOrientationRequest) {
                        Regex("""ignoreOrientationRequest\s+true\b""")
                            .containsMatchIn(runWm("get-ignore-orientation-request"))
                    } else {
                        true
                    }

                userRotation == "lock $portraitRotation" &&
                    fixedToUserRotation == "enabled" &&
                    ignoreOrientationOk
            }

            WmApi.LEGACY -> {
                val dump = runCommand("/system/bin/dumpsys", "window", "displays")
                val rotationName = when (portraitRotation) {
                    0 -> "ROTATION_0"
                    1 -> "ROTATION_90"
                    2 -> "ROTATION_180"
                    3 -> "ROTATION_270"
                    else -> return false
                }

                Regex("""mUserRotationMode=USER_ROTATION_LOCKED\b""")
                    .containsMatchIn(dump) &&
                    Regex("""mUserRotation=$rotationName\b""")
                        .containsMatchIn(dump) &&
                    Regex("""mFixedToUserRotation=(?:true|enabled)\b""")
                        .containsMatchIn(dump)
            }

            WmApi.UNSUPPORTED -> false
        }

        if (!forced && hasTargetPortraitState()) {
            stopPortraitAppWatcher()
            restoreAndroid13FallbackTasksBestEffort()
            restoreAndroid13SupportSettingsBestEffort()
            try {
                restoreTargetPortraitCompat()
            } catch (_: Throwable) {
            }
            targetPortraitStatus = "Target game: overrides inactive"
        }

        return forced
    }

    private fun hasTargetPortraitState(): Boolean {
        return targetCompatStateFile.exists() ||
            fallbackTaskStateFile.exists() ||
            freeformSupportStateFile.exists() ||
            multiWindowConfigStateFile.exists() ||
            portraitWatcherRunning
    }

    override fun toggleForcePortrait(): Boolean {
        return setForcePortrait(!isForcePortraitEnabled())
    }

    override fun getTargetPortraitStatus(): String {
        return targetPortraitStatus
    }

    override fun createPortraitVirtualDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int
    ): Int {
        require(surface.isValid) { "Portrait container surface is not valid" }
        require(width > 0 && height > 0) { "Invalid portrait display dimensions" }

        synchronized(portraitVirtualDisplayLock) {
            releasePortraitVirtualDisplayLocked()

            val context = requireShellPackageContext()
            val displayManager =
                context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    ?: throw IllegalStateException("DisplayManager is unavailable")

            val portraitWidth = minOf(width, height)
            val portraitHeight = maxOf(width, height)
            val density = densityDpi.coerceAtLeast(120)

            val baseFlags =
                VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL

            val virtualDisplay =
                try {
                    displayManager.createVirtualDisplay(
                        "AndroidControl-TargetApp-Portrait",
                        portraitWidth,
                        portraitHeight,
                        density,
                        surface,
                        baseFlags or VIRTUAL_DISPLAY_FLAG_TRUSTED
                    )
                } catch (_: SecurityException) {
                    // AOSP shell normally holds ADD_TRUSTED_DISPLAY. Keep a fallback
                    // for vendor builds that remove it; shell still has privileged
                    // activity-launch and input-injection permissions on normal AOSP.
                    displayManager.createVirtualDisplay(
                        "AndroidControl-TargetApp-Portrait",
                        portraitWidth,
                        portraitHeight,
                        density,
                        surface,
                        baseFlags
                    )
                } ?: throw IllegalStateException("Could not create portrait virtual display")

            portraitVirtualDisplay = virtualDisplay
            portraitVirtualDisplayId = virtualDisplay.display.displayId

            configurePortraitVirtualDisplay(portraitVirtualDisplayId)

            targetPortraitStatus =
                "Target game: portrait virtual display ready " +
                    "(${portraitWidth}×${portraitHeight}, display " +
                    portraitVirtualDisplayId + ")"

            return portraitVirtualDisplayId
        }
    }

    override fun launchTargetOnPortraitVirtualDisplay(
        displayId: Int,
        width: Int,
        height: Int
    ): Boolean {
        synchronized(portraitVirtualDisplayLock) {
            if (
                portraitVirtualDisplay == null ||
                portraitVirtualDisplayId != displayId
            ) {
                throw IllegalStateException("Portrait virtual display is not active")
            }
        }

        val portraitWidth = minOf(width, height)
        val portraitHeight = maxOf(width, height)

        configurePortraitVirtualDisplay(displayId)

        try {
            restoreTargetPortraitCompat()
        } catch (_: Throwable) {
        }
        enableTargetPortraitCompat()

        if (getSdkInt() == 33) {
            prepareAndroid13FreeformSupportBestEffort()
        }

        // Ensure Android does not reuse an existing landscape task from the default
        // display. This does not remove application data.
        try {
            runAm("force-stop", TARGET_PACKAGE)
        } catch (_: Throwable) {
        }

        // Launch from the shell identity rather than Context.startActivity().
        // UserService runs as UID 2000; keeping the launch under "am" avoids
        // attributing a shell Binder call to the AndroidControl app package.
        val launchWithBoundsError = try {
            runAm(
                "start",
                "--display",
                displayId.toString(),
                "--windowingMode",
                WINDOWING_MODE_FREEFORM.toString(),
                "--bounds",
                "0,0,${portraitWidth},${portraitHeight}",
                "-f",
                "0x18000000",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                "-p",
                TARGET_PACKAGE
            )
            null
        } catch (t: Throwable) {
            t
        }

        if (launchWithBoundsError != null) {
            // Some vendor ActivityManager shells omit the windowing/bounds options.
            // The essential requirement is still --display: the target then receives
            // the portrait VirtualDisplay metrics.
            runAm(
                "start",
                "--display",
                displayId.toString(),
                "-f",
                "0x18000000",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                "-p",
                TARGET_PACKAGE
            )
        }

        try {
            Thread.sleep(700)
        } catch (_: InterruptedException) {
        }

        val taskId = findTargetTaskId()
        if (taskId != null) {
            try {
                runAm("task", "resizeable", taskId.toString(), "2")
                runAm(
                    "task",
                    "resize",
                    taskId.toString(),
                    "0",
                    "0",
                    portraitWidth.toString(),
                    portraitHeight.toString()
                )
            } catch (_: Throwable) {
            }

            try {
                val atm = getActivityTaskManagerService()
                invokeActivityTaskManager(atm, "setTaskResizeable", taskId, 2)
                invokeActivityTaskManager(
                    atm,
                    "setTaskWindowingMode",
                    taskId,
                    WINDOWING_MODE_FREEFORM,
                    true
                )
                invokeActivityTaskManager(
                    atm,
                    "resizeTask",
                    taskId,
                    Rect(0, 0, portraitWidth, portraitHeight),
                    RESIZE_MODE_SYSTEM
                )
            } catch (_: Throwable) {
            }
        }

        targetPortraitStatus =
            "Target game: running on portrait virtual display " +
                "${portraitWidth}×${portraitHeight} (display $displayId)"

        return true
    }

    override fun releasePortraitVirtualDisplay() {
        synchronized(portraitVirtualDisplayLock) {
            releasePortraitVirtualDisplayLocked()
        }
    }

    override fun injectPortraitMotionEvent(event: MotionEvent, displayId: Int) {
        injectPortraitInputEvent(event, displayId)
    }

    override fun injectPortraitKeyEvent(event: KeyEvent, displayId: Int) {
        injectPortraitInputEvent(event, displayId)
    }

    private fun releasePortraitVirtualDisplayLocked() {
        try {
            portraitVirtualDisplay?.release()
        } catch (_: Throwable) {
        } finally {
            portraitVirtualDisplay = null
            portraitVirtualDisplayId = Display.INVALID_DISPLAY
        }
    }

    private fun configurePortraitVirtualDisplay(displayId: Int) {
        try {
            runWm(
                "user-rotation",
                "-d",
                displayId.toString(),
                "lock",
                "0"
            )
        } catch (_: Throwable) {
        }

        try {
            runWm(
                "fixed-to-user-rotation",
                "-d",
                displayId.toString(),
                "enabled"
            )
        } catch (_: Throwable) {
        }

        if (supportsIgnoreOrientationRequest) {
            try {
                runWm(
                    "set-ignore-orientation-request",
                    "-d",
                    displayId.toString(),
                    "true"
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun injectPortraitInputEvent(event: InputEvent, displayId: Int) {
        if (
            displayId == Display.INVALID_DISPLAY ||
            displayId != portraitVirtualDisplayId
        ) {
            return
        }

        try {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/view/InputEvent;",
                "Landroid/hardware/input/InputManager;"
            )

            val setDisplayId = InputEvent::class.java.getDeclaredMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )
            setDisplayId.isAccessible = true
            setDisplayId.invoke(event, displayId)

            val context = requireServiceContext()
            val inputManager = context.getSystemService(Context.INPUT_SERVICE)
                ?: return

            val inputEventClass = Class.forName("android.view.InputEvent")
            val injectMethod = inputManager.javaClass.methods.firstOrNull { method ->
                method.name == "injectInputEvent" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].isAssignableFrom(inputEventClass)
            } ?: Class.forName("android.hardware.input.InputManager")
                .getDeclaredMethod(
                    "injectInputEvent",
                    inputEventClass,
                    Int::class.javaPrimitiveType
                )

            injectMethod.isAccessible = true
            injectMethod.invoke(
                inputManager,
                event,
                INPUT_INJECTION_MODE_ASYNC
            )
        } catch (_: Throwable) {
        }
    }

    private fun requireShellPackageContext(): Context {
        shellPackageContext?.let { return it }

        val base = requireServiceContext()
        val shellContext = try {
            base.createPackageContext(
                "com.android.shell",
                Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (t: Throwable) {
            throw IllegalStateException(
                "Unable to create com.android.shell context for shell UID: " +
                    (t.message ?: t.javaClass.simpleName),
                t
            )
        }

        shellPackageContext = shellContext
        return shellContext
    }

    private fun requireServiceContext(): Context {
        serviceContext?.let { return it }

        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityThread;")
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
            if (currentApplication != null) {
                serviceContext = currentApplication.applicationContext
                return serviceContext!!
            }
        } catch (_: Throwable) {
        }

        throw IllegalStateException("AndroidControl service context is unavailable")
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

    private fun restoreNormalRotation() {
        releasePortraitVirtualDisplay()
        stopPortraitAppWatcher()
        restoreAndroid13FallbackTasksBestEffort()
        restoreAndroid13SupportSettingsBestEffort()
        val commands: List<Array<String>> = when (wmApi) {
            WmApi.MODERN -> buildList<Array<String>> {
                if (supportsIgnoreOrientationRequest) {
                    add(arrayOf("set-ignore-orientation-request", "false"))
                }
                add(arrayOf("fixed-to-user-rotation", "default"))
                add(arrayOf("user-rotation", "free"))
            }

            WmApi.LEGACY -> listOf(
                arrayOf("set-fix-to-user-rotation", "default"),
                arrayOf("set-user-rotation", "free")
            )

            WmApi.UNSUPPORTED -> return
        }

        var failure: Throwable? = null
        commands.forEach { command ->
            try {
                runWm(*command)
            } catch (t: Throwable) {
                if (failure == null) {
                    failure = t
                } else {
                    failure!!.addSuppressed(t)
                }
            }
        }
        try {
            restoreTargetPortraitCompat()
        } catch (t: Throwable) {
            if (failure == null) {
                failure = t
            } else {
                failure!!.addSuppressed(t)
            }
        }

        failure?.let { throw it }
    }

    private fun enableTargetPortraitCompat() {
        val sdk = getSdkInt()
        targetCompatStateFile.delete()

        fun applyCompat(mode: String, changeId: String) {
            // Record intent before applying. If the process dies after the compat
            // command succeeds, Restore can still reset this ID on the next run.
            appendTargetCompatChange(changeId)
            try {
                runAm("compat", mode, "--no-kill", changeId, TARGET_PACKAGE)
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

        if (sdk >= 34) {
            applyCompat("enable", OVERRIDE_ANY_ORIENTATION)
            applyCompat("enable", OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT)
        }

        if (sdk >= 35) {
            applyCompat("enable", OVERRIDE_ANY_ORIENTATION_TO_USER)
        }
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

    private fun restoreTargetPortraitCompat() {
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
                runAm("compat", "reset", changeId, TARGET_PACKAGE)
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
                TARGET_PACKAGE + "; they were retained for retry."
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
            var configured = false

            while (portraitWatcherRunning) {
                try {
                    val taskId = findTargetTaskId()
                    if (taskId == null) {
                        activeTaskId = null
                        configured = false
                        targetPortraitStatus =
                            "Target game: waiting for $TARGET_PACKAGE to become foreground"
                    } else {
                        if (activeTaskId != taskId) {
                            activeTaskId = taskId
                            configured = false
                        }

                        if (!configured) {
                            synchronized(portraitOperationLock) {
                                if (portraitWatcherRunning) {
                                    configured = enforceAndroid13PortraitTask(taskId)
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
        val dump = runCommand("/system/bin/dumpsys", "activity", "activities")
        val lines = dump.lineSequence().toList()

        val prioritized = lines.firstOrNull { line ->
            line.contains(TARGET_PACKAGE) &&
                (
                    line.contains("topResumedActivity") ||
                    line.contains("mResumedActivity") ||
                    line.contains("mFocusedApp")
                )
        }

        val candidate = prioritized ?: lines.firstOrNull { line ->
            line.contains(TARGET_PACKAGE) && Regex("""\bt\d+\b""").containsMatchIn(line)
        } ?: return null

        return Regex("""\bt(\d+)\b""")
            .find(candidate)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun enforceAndroid13PortraitTask(taskId: Int): Boolean {
        val size = getPortraitDisplayBounds()
        targetPortraitStatus =
            "Target game: applying portrait task bounds to task $taskId"

        val firstError = tryResizeTargetTaskWithShell(taskId, size)
        if (isTaskPortrait(taskId)) {
            rememberFallbackTask(taskId)
            targetPortraitStatus =
                "Target game: portrait task active (${size.first}×${size.second})"
            return true
        }

        prepareAndroid13FreeformSupportBestEffort()

        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }

        val secondError = tryResizeTargetTaskWithShell(taskId, size)
        if (isTaskPortrait(taskId)) {
            rememberFallbackTask(taskId)
            targetPortraitStatus =
                "Target game: portrait task active (${size.first}×${size.second}); " +
                    "temporary freeform support enabled"
            return true
        }

        val binderError = try {
            val atm = getActivityTaskManagerService()
            invokeActivityTaskManager(atm, "setTaskResizeable", taskId, 2)
            invokeActivityTaskManager(
                atm,
                "setTaskWindowingMode",
                taskId,
                WINDOWING_MODE_FREEFORM,
                true
            )
            invokeActivityTaskManager(
                atm,
                "resizeTask",
                taskId,
                Rect(0, 0, size.first, size.second),
                RESIZE_MODE_SYSTEM
            )
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }

        if (isTaskPortrait(taskId)) {
            rememberFallbackTask(taskId)
            targetPortraitStatus =
                "Target game: portrait task active (${size.first}×${size.second})"
            return true
        }

        val details = listOfNotNull(firstError, secondError, binderError)
            .distinct()
            .joinToString(" | ")

        targetPortraitStatus =
            if (details.isBlank()) {
                "Target game: system kept the task non-portrait after all resize attempts"
            } else {
                "Target game: portrait task failed: $details"
            }

        return false
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

        try {
            runWm(
                "set-multi-window-config",
                "--supportsNonResizable",
                "1",
                "--respectsActivityMinWidthHeight",
                "0"
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

                if (parts.size >= 2) {
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

        val atm = try {
            getActivityTaskManagerService()
        } catch (_: Throwable) {
            return
        }

        fallbackTaskStateFile.readLines()
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()
            .forEach { taskId ->
                try {
                    invokeActivityTaskManager(
                        atm,
                        "setTaskWindowingMode",
                        taskId,
                        WINDOWING_MODE_FULLSCREEN,
                        false
                    )
                    invokeActivityTaskManager(
                        atm,
                        "setTaskResizeable",
                        taskId,
                        0
                    )
                } catch (_: Throwable) {
                }
            }

        fallbackTaskStateFile.delete()
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
                "Landroid/app/IActivityTaskManager;"
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

    private fun restoreNormalRotationBestEffort() {
        try {
            restoreNormalRotation()
        } catch (_: Throwable) {
        }
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
