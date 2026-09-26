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

    private val compatOverridePackagesFile =
        File("/data/local/tmp/androidcontrol-portrait-compat-packages")

    private val fallbackTaskStateFile =
        File("/data/local/tmp/androidcontrol-portrait-fallback-tasks")

    @Volatile
    private var fallbackWatcherRunning = false

    @Volatile
    private var fallbackWatcherThread: Thread? = null

    private companion object {
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
        if (wmApi == WmApi.UNSUPPORTED) {
            throw UnsupportedOperationException(
                "This Android build does not expose the required WindowManager rotation controls."
            )
        }

        if (enabled) {
            val portraitRotation = getPortraitRotation()
            try {
                enableForceResizableActivities()
                enablePerAppPortraitCompatOverrides()
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

                if (getSdkInt() == 33) {
                    startAndroid13TaskFallbackWatcher()
                }
            } catch (t: Throwable) {
                restoreNormalRotationBestEffort()
                throw t
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

        return when (wmApi) {
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
                    ignoreOrientationOk &&
                    isForceResizableActivitiesEnabled()
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
                        .containsMatchIn(dump) &&
                    isForceResizableActivitiesEnabled()
            }

            WmApi.UNSUPPORTED -> false
        }
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

    private fun restoreNormalRotation() {
        stopAndroid13TaskFallbackWatcher()
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
            restorePerAppPortraitCompatOverrides()
        } catch (t: Throwable) {
            if (failure == null) {
                failure = t
            } else {
                failure!!.addSuppressed(t)
            }
        }

        try {
            restoreForceResizableActivities()
        } catch (t: Throwable) {
            if (failure == null) {
                failure = t
            } else {
                failure!!.addSuppressed(t)
            }
        }

        failure?.let { throw it }
    }

    private fun enablePerAppPortraitCompatOverrides() {
        val sdk = getSdkInt()

        val packages = runCommand("/system/bin/pm", "list", "packages", "-3")
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() && it != "moe.shizuku.privileged.api" }
            .distinct()
            .toList()

        val changed = mutableListOf<String>()

        packages.forEach { packageName ->
            val appliedChanges = mutableListOf<String>()

            if (sdk >= 33) {
                try {
                    runAm(
                        "compat", "disable", "--no-kill",
                        FORCE_NON_RESIZE_APP, packageName
                    )
                    appliedChanges.add(FORCE_NON_RESIZE_APP)
                } catch (_: Throwable) {
                    // Android 13+ only; some vendor builds may omit the override.
                }
            }

            try {
                runAm(
                    "compat", "disable", "--no-kill",
                    NEVER_SANDBOX_DISPLAY_APIS, packageName
                )
                appliedChanges.add(NEVER_SANDBOX_DISPLAY_APIS)
            } catch (_: Throwable) {
                // Older builds may not expose this compat change.
            }

            try {
                runAm(
                    "compat", "enable", "--no-kill",
                    ALWAYS_SANDBOX_DISPLAY_APIS, packageName
                )
                appliedChanges.add(ALWAYS_SANDBOX_DISPLAY_APIS)
            } catch (_: Throwable) {
                // Older builds may not expose this compat change.
            }

            try {
                runAm(
                    "compat", "enable", "--no-kill",
                    OVERRIDE_SANDBOX_VIEW_BOUNDS_APIS, packageName
                )
                appliedChanges.add(OVERRIDE_SANDBOX_VIEW_BOUNDS_APIS)
            } catch (_: Throwable) {
                // Older builds may not expose this compat change.
            }

            try {
                runAm(
                    "compat", "enable", "--no-kill",
                    FORCE_RESIZE_APP, packageName
                )
                appliedChanges.add(FORCE_RESIZE_APP)
            } catch (_: Throwable) {
                // Some packages/ROMs may reject the override. Continue with others.
            }

            if (sdk >= 34) {
                try {
                    runAm(
                        "compat", "enable", "--no-kill",
                        OVERRIDE_ANY_ORIENTATION, packageName
                    )
                    runAm(
                        "compat", "enable", "--no-kill",
                        OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT, packageName
                    )
                    appliedChanges.add(OVERRIDE_ANY_ORIENTATION)
                    appliedChanges.add(OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT)
                } catch (_: Throwable) {
                    // Not all vendor Android 14 builds expose both orientation overrides.
                }
            }

            if (sdk >= 35) {
                try {
                    runAm(
                        "compat", "enable", "--no-kill",
                        OVERRIDE_ANY_ORIENTATION_TO_USER, packageName
                    )
                    appliedChanges.add(OVERRIDE_ANY_ORIENTATION_TO_USER)
                } catch (_: Throwable) {
                    // Android 15+ fullscreen/user-orientation override is optional on vendor builds.
                }
            }

            if (appliedChanges.isNotEmpty()) {
                changed.add(packageName + "\t" + appliedChanges.joinToString(","))
            }
        }

        compatOverridePackagesFile.writeText(changed.joinToString("\n"))
    }

    private fun restorePerAppPortraitCompatOverrides() {
        if (!compatOverridePackagesFile.exists()) return

        compatOverridePackagesFile.readLines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                val separator = line.indexOf('\t')
                val packageName =
                    if (separator >= 0) line.substring(0, separator) else line
                val changeIds =
                    if (separator >= 0 && separator + 1 < line.length) {
                        line.substring(separator + 1)
                            .split(',')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                    } else {
                        emptyList()
                    }

                changeIds.forEach { changeId ->
                    try {
                        runAm("compat", "reset", changeId, packageName)
                    } catch (_: Throwable) {
                        // The package may have been removed or the vendor build may
                        // reject resetting an optional compat change. Keep restoring.
                    }
                }
            }

        compatOverridePackagesFile.delete()
    }

    private data class ResumedTask(
        val taskId: Int,
        val packageName: String,
        val fullscreen: Boolean
    )

    private fun startAndroid13TaskFallbackWatcher() {
        if (fallbackWatcherRunning) return

        fallbackWatcherRunning = true
        val thread = Thread({
            val thirdPartyPackages = try {
                runCommand("/system/bin/pm", "list", "packages", "-3")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:") }
                    .filter { it.isNotBlank() && it != "moe.shizuku.privileged.api" }
                    .toSet()
            } catch (_: Throwable) {
                emptySet()
            }

            while (fallbackWatcherRunning) {
                try {
                    val task = findResumedTask()
                    if (
                        task != null &&
                        task.fullscreen &&
                        thirdPartyPackages.contains(task.packageName) &&
                        !wasFallbackTaskChanged(task.taskId)
                    ) {
                        applyAndroid13TaskFallback(task.taskId)
                    }
                } catch (_: Throwable) {
                    // The compat-based path remains active if a vendor ROM rejects task fallback.
                }

                try {
                    Thread.sleep(750)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "androidcontrol-portrait-task-fallback")

        fallbackWatcherThread = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun stopAndroid13TaskFallbackWatcher() {
        fallbackWatcherRunning = false
        fallbackWatcherThread?.interrupt()
        fallbackWatcherThread = null
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
