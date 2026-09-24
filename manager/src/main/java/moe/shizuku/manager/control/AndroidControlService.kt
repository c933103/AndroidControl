package moe.shizuku.manager.control

import android.content.Context
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.InputStreamReader

class AndroidControlService : IAndroidControlService.Stub {

    constructor()

    @Keep
    constructor(context: Context)

    override fun destroy() {
        System.exit(0)
    }

    override fun setForcePortrait(enabled: Boolean): Boolean {
        if (enabled) {
            runWm("user-rotation", "lock", "0")
            runWm("fixed-to-user-rotation", "enabled")
            runWm("set-ignore-orientation-request", "true")
        } else {
            runWm("set-ignore-orientation-request", "false")
            runWm("fixed-to-user-rotation", "default")
            runWm("user-rotation", "free")
        }
        return isForcePortraitEnabled()
    }

    override fun isForcePortraitEnabled(): Boolean {
        val userRotation = runWm("user-rotation").trim()
        val fixedToUserRotation = runWm("fixed-to-user-rotation").trim()
        val ignoreOrientationRequest = runWm("get-ignore-orientation-request")

        return userRotation == "lock 0" &&
            fixedToUserRotation == "enabled" &&
            Regex("""ignoreOrientationRequest\s+true\b""").containsMatchIn(ignoreOrientationRequest)
    }

    override fun toggleForcePortrait(): Boolean {
        return setForcePortrait(!isForcePortraitEnabled())
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
