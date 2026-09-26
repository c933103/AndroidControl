package moe.shizuku.manager.control

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

    private val forceResizableStateFile =
        File("/data/local/tmp/androidcontrol-force-resizable-prev")

    // Written by older AndroidControl builds that modified many third-party apps.
    private val legacyCompatOverridePackagesFile =
        File("/data/local/tmp/androidcontrol-portrait-compat-packages")

    // New builds only record compat changes for the one target game here.
    private val targetCompatStateFile =
        File("/data/local/tmp/androidcontrol-hololive-dreams-compat")

    private val legacyRecoveryFailuresFile =
        File("/data/local/tmp/androidcontrol-legacy-recovery-failures")

    private val fallbackTaskStateFile =
        File("/data/local/tmp/androidcontrol-portrait-fallback-tasks")

    @Volatile
    private var portraitWatcherRunning = false

    @Volatile
    private var portraitWatcherThread: Thread? = null

    private val portraitOperationLock = Any()

    @Volatile
    private var legacyRecoveryRunning = false

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
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun setForcePortrait(enabled: Boolean): Boolean {
        if (legacyRecoveryRunning) {
            throw IllegalStateException(
                "Legacy portrait recovery is still running; wait for it to finish."
            )
        }

        if (wmApi == WmApi.UNSUPPORTED) {
            throw UnsupportedOperationException(
                "This Android build does not expose the required WindowManager rotation controls."
            )
        }

        if (enabled) {
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

        return forced
    }

    override fun toggleForcePortrait(): Boolean {
        return setForcePortrait(!isForcePortraitEnabled())
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

    override fun isLegacyRecoveryRunning(): Boolean {
        return legacyRecoveryRunning
    }

    override fun recoverLegacyPortraitState(): Int {
        synchronized(this) {
            if (legacyRecoveryRunning) {
                throw IllegalStateException("Legacy portrait recovery is already running")
            }
            legacyRecoveryRunning = true
        }

        try {
            // Recovery is intentionally exhaustive and separate from normal portrait
            // operation. Older builds applied compat overrides to every third-party
            // package before reaching the WindowManager rotation lock.
            stopPortraitAppWatcher()
            restoreAndroid13FallbackTasksBestEffort()
            restoreCoreRotationBestEffort()

            val packages = runCommand("/system/bin/pm", "list", "packages", "-3")
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:") }
                .filter { it.isNotBlank() && it != "moe.shizuku.privileged.api" }
                .distinct()
                .toMutableSet()

            // Include names recorded by previous versions even if package listing
            // formatting or package state changed since the failed operation.
            if (legacyCompatOverridePackagesFile.exists()) {
                legacyCompatOverridePackagesFile.readLines().forEach { line ->
                    val packageName = line.substringBefore('\t').trim()
                    if (packageName.isNotBlank()) {
                        packages.add(packageName)
                    }
                }
            }

            val sdk = getSdkInt()
            val changeIds = buildList {
                add(FORCE_RESIZE_APP)
                add(NEVER_SANDBOX_DISPLAY_APIS)
                add(ALWAYS_SANDBOX_DISPLAY_APIS)
                add(OVERRIDE_SANDBOX_VIEW_BOUNDS_APIS)

                if (sdk >= 33) {
                    add(FORCE_NON_RESIZE_APP)
                }
                if (sdk >= 34) {
                    add(OVERRIDE_ANY_ORIENTATION)
                    add(OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT)
                }
                if (sdk >= 35) {
                    add(OVERRIDE_ANY_ORIENTATION_TO_USER)
                }
            }

            // No short timeout: on a device with hundreds of third-party packages,
            // this is expected to take time. Each known AndroidControl change ID is
            // reset for every package the old implementation could have touched.
            val failures = mutableListOf<String>()

            packages.sorted().forEach { packageName ->
                changeIds.forEach { changeId ->
                    try {
                        runAm("compat", "reset", changeId, packageName)
                    } catch (t: Throwable) {
                        if (!isIgnorableCompatResetFailure(t)) {
                            failures.add(packageName + "\t" + changeId)
                        }
                    }
                }
            }

            try {
                restoreForceResizableActivities()
            } catch (t: Throwable) {
                failures.add("<global>\tforce_resizable_activities")
            }

            if (failures.isEmpty()) {
                legacyCompatOverridePackagesFile.delete()
                legacyRecoveryFailuresFile.delete()
                targetCompatStateFile.delete()
                return packages.size
            }

            // Preserve exact failed package/change pairs so a failed exhaustive
            // recovery is never reported as complete and remains diagnosable/retriable.
            legacyRecoveryFailuresFile.writeText(failures.joinToString("\n"))
            throw IllegalStateException(
                "Scanned ${packages.size} third-party packages, but " +
                    "${failures.size} compatibility reset operations failed. " +
                    "Run legacy recovery again; failed entries were preserved."
            )
        } finally {
            legacyRecoveryRunning = false
        }
    }

    private fun restoreCoreRotationBestEffort() {
        try {
            when (wmApi) {
                WmApi.MODERN -> {
                    if (supportsIgnoreOrientationRequest) {
                        try {
                            runWm("set-ignore-orientation-request", "false")
                        } catch (_: Throwable) {
                        }
                    }
                    try {
                        runWm("fixed-to-user-rotation", "default")
                    } catch (_: Throwable) {
                    }
                    try {
                        runWm("user-rotation", "free")
                    } catch (_: Throwable) {
                    }
                }

                WmApi.LEGACY -> {
                    try {
                        runWm("set-fix-to-user-rotation", "default")
                    } catch (_: Throwable) {
                    }
                    try {
                        runWm("set-user-rotation", "free")
                    } catch (_: Throwable) {
                    }
                }

                WmApi.UNSUPPORTED -> Unit
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreNormalRotation() {
        stopPortraitAppWatcher()
        restoreAndroid13FallbackTasksBestEffort()
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

    private data class ResumedTask(
        val taskId: Int,
        val packageName: String,
        val fullscreen: Boolean
    )

    private fun startPortraitAppWatcher() {
        if (portraitWatcherRunning) return

        portraitWatcherRunning = true
        val thread = Thread({
            while (portraitWatcherRunning) {
                try {
                    val task = findResumedTask()
                    if (
                        task != null &&
                        task.packageName == TARGET_PACKAGE &&
                        task.fullscreen &&
                        !wasFallbackTaskChanged(task.taskId)
                    ) {
                        synchronized(portraitOperationLock) {
                            if (portraitWatcherRunning) {
                                applyAndroid13TaskFallback(task.taskId)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Keep the core portrait lock active if the Android 13
                    // freeform fallback is unavailable on this ROM.
                }

                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "androidcontrol-portrait-target-watcher")

        portraitWatcherThread = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun stopPortraitAppWatcher() {
        portraitWatcherRunning = false
        portraitWatcherThread?.interrupt()

        // Wait for an in-flight compat/task mutation to leave its critical section
        // before restoration reads/deletes the ledger. This prevents a stopped
        // watcher from writing a fresh override after Restore normal rotation.
        synchronized(portraitOperationLock) {
        }

        portraitWatcherThread = null
    }

    private fun findResumedTask(): ResumedTask? {
        val dump = runCommand("/system/bin/dumpsys", "activity", "activities")

        val resumed = Regex(
            """(?:topResumedActivity|mResumedActivity)[^\n]*?\s([A-Za-z0-9_.$]+)/(?:[^\s}]+)[^\n]*?\bt(\d+)\b"""
        ).find(dump) ?: return null

        val packageName = resumed.groupValues[1]
        val taskId = resumed.groupValues[2].toIntOrNull() ?: return null

        val fullscreen =
            Regex(
                """(?m)^\s*\*?\s*Task\{[^\n]*?#$taskId\b[^\n]*?\bmode=fullscreen\b"""
            ).containsMatchIn(dump) ||
                Regex(
                    """(?m)^\s*Task\{[^\n]*?\btaskId=$taskId\b[^\n]*?\bwindowingMode=1\b"""
                ).containsMatchIn(dump)

        return ResumedTask(taskId, packageName, fullscreen)
    }

    private fun applyAndroid13TaskFallback(taskId: Int) {
        val size = getPortraitDisplayBounds()

        // Android 13 still letterboxes fixed-landscape fullscreen activities.
        // Moving the task into a portrait-sized freeform container makes it a
        // multi-window activity, where fixed-orientation handling is bypassed.
        val atm = getActivityTaskManagerService()
        invokeActivityTaskManager(atm, "setTaskResizeable", taskId, 2)
        val moved = invokeActivityTaskManager(
            atm,
            "setTaskWindowingMode",
            taskId,
            WINDOWING_MODE_FREEFORM,
            true
        )
        if (moved is Boolean && !moved) {
            throw IllegalStateException("Unable to move task $taskId into freeform mode")
        }

        invokeActivityTaskManager(
            atm,
            "resizeTask",
            taskId,
            Rect(0, 0, size.first, size.second),
            RESIZE_MODE_SYSTEM
        )
        rememberFallbackTask(taskId)
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

    @Synchronized
    private fun wasFallbackTaskChanged(taskId: Int): Boolean {
        if (!fallbackTaskStateFile.exists()) return false
        return fallbackTaskStateFile.readLines()
            .any { it.trim().toIntOrNull() == taskId }
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

    private fun enableForceResizableActivities() {
        if (!forceResizableStateFile.exists()) {
            val previous = runSettings("get", "global", "force_resizable_activities").trim()
            forceResizableStateFile.writeText(previous.ifEmpty { "null" })
        }
        runSettings("put", "global", "force_resizable_activities", "1")
    }

    private fun restoreForceResizableActivities() {
        if (!forceResizableStateFile.exists()) return

        val previous = forceResizableStateFile.readText().trim()
        if (previous.isEmpty() || previous == "null") {
            runSettings("delete", "global", "force_resizable_activities")
        } else {
            runSettings("put", "global", "force_resizable_activities", previous)
        }

        forceResizableStateFile.delete()
    }

    private fun isForceResizableActivitiesEnabled(): Boolean {
        return runSettings("get", "global", "force_resizable_activities").trim() == "1"
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
