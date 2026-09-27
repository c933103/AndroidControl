package moe.shizuku.manager.control

import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@Keep
class AndroidControlService @Keep constructor() : IAndroidControlService.Stub() {

    @Keep
    constructor(context: Context) : this()

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
    private var android13OrientationOverrideAccepted = false

    private companion object {
        const val TARGET_PACKAGE = "game.qualiarts.hololive.dreams.jp"

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

    override fun setForcePortrait(enabled: Boolean): Boolean {
        if (wmApi == WmApi.UNSUPPORTED) {
            throw UnsupportedOperationException(
                "This Android build does not expose the required WindowManager rotation controls."
            )
        }

        if (enabled) {
            targetPortraitStatus =
                "Target game: waiting for $TARGET_PACKAGE to become foreground"

            val sdk = getSdkInt()
            val targetWasRunning =
                sdk == 33 && isTargetRunningOrTaskPresent()

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
            val targetCompat = try {
                enableTargetPortraitCompat()
            } catch (_: Throwable) {
                emptySet()
            }

            android13OrientationOverrideAccepted =
                sdk == 33 &&
                    targetCompat.contains(OVERRIDE_ANY_ORIENTATION) &&
                    targetCompat.contains(OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT)

            if (android13OrientationOverrideAccepted) {
                targetPortraitStatus =
                    "Target game: Android 13 orientation override accepted; " +
                        "waiting for portrait activity bounds"
            } else if (sdk == 33) {
                targetPortraitStatus =
                    "Target game: Android 13 orientation override unavailable; " +
                        "using task/freeform fallback"
            }

            if (sdk == 33) {
                if (targetWasRunning) {
                    restartTargetGameBestEffort(
                        preferFreeform = !android13OrientationOverrideAccepted
                    )
                }
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
            android13OrientationOverrideAccepted = false
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
                    "compat", mode, "--no-kill", changeId, TARGET_PACKAGE
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
                            topPackage == TARGET_PACKAGE ||
                            basePackage == TARGET_PACKAGE
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

    private fun isTargetRunningOrTaskPresent(): Boolean {
        if (findTargetTaskId() != null) return true

        return try {
            runCommand("/system/bin/pidof", TARGET_PACKAGE).trim().isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun restartTargetGameBestEffort(preferFreeform: Boolean) {
        targetPortraitStatus =
            "Target game: restarting once to apply Android 13 portrait compatibility"

        try {
            runAm("force-stop", TARGET_PACKAGE)
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
                TARGET_PACKAGE
            )
                .lineSequence()
                .map { it.trim() }
                .lastOrNull { it.contains('/') && !it.startsWith("No activity") }
        } catch (_: Throwable) {
            null
        }

        if (component != null) {
            if (!preferFreeform) {
                // Let the Android 13 QPR orientation override recreate the activity
                // normally first. The watcher verifies the activity's real app bounds
                // and escalates to freeform only if they remain landscape.
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
                TARGET_PACKAGE,
                "-c",
                "android.intent.category.LAUNCHER",
                "1"
            )
        } catch (_: Throwable) {
            targetPortraitStatus =
                "Target game: compatibility applied; reopen the game manually"
        }
    }

    private fun enforceAndroid13PortraitTask(taskId: Int): Boolean {
        val size = getPortraitDisplayBounds()
        targetPortraitStatus =
            "Target game: applying portrait task bounds to task $taskId"

        // Record before the first resize/windowing mutation. Even if every attempt
        // fails afterward, Restore normal rotation must know this task may have had
        // its resize mode or windowing mode changed.
        rememberFallbackTask(taskId)

        val directBinderError =
            tryResizeTargetTaskWithBinder(taskId, size, forceFreeform = false)

        if (isTaskPortrait(taskId)) {
            rememberFallbackTask(taskId)
            targetPortraitStatus =
                "Target game: portrait task active (${size.first}×${size.second}); " +
                    describeTargetTask(taskId)
            return true
        }

        prepareAndroid13FreeformSupportBestEffort()

        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }

        val freeformBinderError =
            tryResizeTargetTaskWithBinder(taskId, size, forceFreeform = true)

        if (isTaskPortrait(taskId)) {
            rememberFallbackTask(taskId)
            targetPortraitStatus =
                "Target game: portrait task active (${size.first}×${size.second}); " +
                    describeTargetTask(taskId)
            return true
        }

        val shellError = tryResizeTargetTaskWithShell(taskId, size)

        if (isTaskPortrait(taskId)) {
            rememberFallbackTask(taskId)
            targetPortraitStatus =
                "Target game: portrait task active (${size.first}×${size.second}); " +
                    describeTargetTask(taskId)
            return true
        }

        val details = listOfNotNull(
            directBinderError,
            freeformBinderError,
            shellError
        )
            .distinct()
            .joinToString(" | ")

        val geometry = describeTargetTask(taskId)
        targetPortraitStatus =
            if (details.isBlank()) {
                "Target game: system kept task non-portrait; $geometry"
            } else {
                "Target game: portrait task failed: $details; $geometry"
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
