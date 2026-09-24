package moe.shizuku.manager.control

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader

@Keep
class AndroidControlService : IAndroidControlService.Stub {

    @Keep
    constructor()

    @Keep
    constructor(context: Context)

    override fun destroy() {
        System.exit(0)
    }

    override fun setForcePortrait(enabled: Boolean): Boolean {
        if (enabled) {
            val portraitRotation = getPortraitRotation()
            try {
                runWm("user-rotation", "lock", portraitRotation.toString())
                runWm("fixed-to-user-rotation", "enabled")
                runWm("set-ignore-orientation-request", "true")
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
        val userRotation = runWm("user-rotation").trim()
        val fixedToUserRotation = runWm("fixed-to-user-rotation").trim()
        val ignoreOrientationRequest = runWm("get-ignore-orientation-request")

        val portraitRotation = getPortraitRotation()
        return userRotation == "lock $portraitRotation" &&
            fixedToUserRotation == "enabled" &&
            Regex("""ignoreOrientationRequest\s+true\b""").containsMatchIn(ignoreOrientationRequest)
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
        return if (height >= width) 0 else 1
    }

    private fun restoreNormalRotation() {
        var failure: Throwable? = null
        listOf(
            arrayOf("set-ignore-orientation-request", "false"),
            arrayOf("fixed-to-user-rotation", "default"),
            arrayOf("user-rotation", "free")
        ).forEach { command ->
            try {
                runWm(*command)
            } catch (t: Throwable) {
                if (failure == null) failure = t else failure!!.addSuppressed(t)
            }
        }
        failure?.let { throw it }
    }

    private fun restoreNormalRotationBestEffort() {
        try {
            restoreNormalRotation()
        } catch (_: Throwable) {
        }
    }

    private fun runWm(vararg args: String): String {
        val command = ArrayList<String>(args.size + 1)
        command.add("wm")
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
                "wm ${args.joinToString(" ")} failed with exit code $exitCode: ${output.trim()}"
            )
        }
        return output
    }
}
