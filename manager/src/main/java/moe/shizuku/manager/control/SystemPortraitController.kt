package moe.shizuku.manager.control

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Display 0 policy only. No package flags, app launches or task changes. */
internal class SystemPortraitController(
    file: File,
    private val supported: () -> Boolean,
    private val command: (Array<String>) -> String,
    private val savedUserRotation: () -> Int,
    private val portraitRotation: () -> Int
) {
    private val journal = AtomicFile(file)
    private fun wm(vararg args: String) = command(arrayOf(*args)).trim()

    @Synchronized fun isSupported() = supported()
    @Synchronized fun hasOverride() = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()

    @Synchronized fun isEnabled(): Boolean = isSupported() &&
        wm("user-rotation", "-d", "0") == "lock ${portraitRotation()}" &&
        wm("fixed-to-user-rotation", "-d", "0") == "enabled" && readIgnore()

    private fun readIgnore(): Boolean {
        val value = Regex("ignoreOrientationRequest\\s+(true|false)\\b")
            .find(wm("get-ignore-orientation-request", "-d", "0"))?.groupValues?.get(1)
        check(value != null) { "Cannot read the system orientation policy" }
        return value.toBoolean()
    }

    private fun snapshot(): JSONObject {
        val user = wm("user-rotation", "-d", "0")
        check(user == "free" || Regex("lock [0-3]").matches(user)) { "Cannot read user rotation: $user" }
        val rotation = if (user == "free") savedUserRotation() else user.last().digitToInt()
        check(rotation in 0..3) { "Invalid saved user rotation" }
        val fixed = wm("fixed-to-user-rotation", "-d", "0")
        check(fixed in setOf("default", "disabled", "enabled", "enabled_if_no_auto_rotation")) {
            "Cannot read fixed rotation: $fixed"
        }
        return JSONObject().put("free", user == "free").put("rotation", rotation)
            .put("fixed", fixed).put("ignore", readIgnore())
    }

    @Synchronized fun setEnabled(enabled: Boolean): Boolean {
        check(isSupported()) { "This Android build does not support system-wide portrait controls" }
        if (!enabled) {
            restore()
            return isEnabled()
        }
        if (!hasOverride()) {
            val bytes = snapshot().toString().toByteArray(Charsets.UTF_8)
            val stream = journal.startWrite()
            try { stream.write(bytes); journal.finishWrite(stream) }
            catch (t: Throwable) { journal.failWrite(stream); throw t }
        }
        try {
            wm("user-rotation", "-d", "0", "lock", portraitRotation().toString())
            wm("fixed-to-user-rotation", "-d", "0", "enabled")
            wm("set-ignore-orientation-request", "-d", "0", "true")
            check(isEnabled()) { "System-wide portrait policy did not apply" }
        } catch (t: Throwable) {
            try { restore() } catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
            throw t
        }
        return true
    }

    @Synchronized fun toggle(): Boolean = setEnabled(!hasOverride())

    private fun restore() {
        if (!hasOverride()) return
        val previous = JSONObject(journal.openRead().bufferedReader().use { it.readText() })
        val failures = mutableListOf<Throwable>()
        fun attempt(vararg args: String) {
            try { wm(*args) } catch (t: Throwable) { failures.add(t) }
        }
        attempt("set-ignore-orientation-request", "-d", "0", previous.getBoolean("ignore").toString())
        attempt("fixed-to-user-rotation", "-d", "0", previous.getString("fixed"))
        attempt("user-rotation", "-d", "0", "lock", previous.getInt("rotation").toString())
        if (previous.getBoolean("free")) attempt("user-rotation", "-d", "0", "free")
        if (failures.isNotEmpty()) {
            throw IllegalStateException("System rotation restoration is incomplete; tap Restore to retry", failures.first())
        }
        val restored = snapshot()
        check(listOf("free", "rotation", "fixed", "ignore").all { restored.get(it) == previous.get(it) }) { "System rotation restoration did not verify; tap Restore to retry" }
        journal.delete()
    }
}
