package org.androidcontrol.app.appops

import org.json.JSONArray
import org.json.JSONObject

/** Legacy v1 opbacks parsing follows AppOpsX ConfigPresenter (MIT; see notices). */
object AppOpsBackup {
    const val MAX_BYTES = 4 * 1024 * 1024
    data class Change(val pkg: String, val name: String, val mode: Int)

    fun parse(text: String, catalog: JSONArray): List<Change> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds 4 MiB" }
        val root = JSONObject(text)
        val opsByCode = (0 until catalog.length()).associate { i ->
            val op = catalog.getJSONObject(i)
            op.getInt("code") to op.getString("name")
        }
        val names = opsByCode.values.toSet()
        val changes = mutableListOf<Change>()
        if (root.has("opbacks")) {
            require(root.getInt("v") == 1) { "Unsupported AppOpsX backup version" }
            val records = root.getJSONArray("opbacks")
            for (i in 0 until records.length()) {
                val record = records.getJSONObject(i)
                val pkg = packageName(record.getString("pkg"))
                val codes = record.getString("ops").split(',').filter { it.isNotBlank() }
                for (code in codes) {
                    val number = code.trim().toInt()
                    // AppOpsX's original operation IDs are AOSP's fixed 0..77 IDs.
                    require(number in 0..77) { "Unknown legacy AppOpsX operation: $number" }
                    changes.add(Change(pkg, opsByCode[number] ?: error("Legacy operation is unavailable: $number"), 1))
                }
            }
        } else {
            require(root.getString("format") == "androidcontrol-appops" && root.getInt("version") == 2) { "Unsupported backup format" }
            val apps = root.getJSONArray("apps")
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                val pkg = packageName(app.getString("package"))
                val ops = app.getJSONArray("ops")
                for (j in 0 until ops.length()) {
                    val op = ops.getJSONObject(j)
                    val name = AppOpsNames.normalize(op.getString("name"))
                    val mode = op.getInt("mode")
                    require(name in names) { "Operation is unavailable on this device: $name" }
                    require(mode in 0..4) { "Unsupported operation mode: $mode" }
                    changes.add(Change(pkg, name, mode))
                }
            }
        }
        require(changes.size <= 10000) { "Backup contains too many changes" }
        require(changes.map { it.pkg to it.name }.distinct().size == changes.size) { "Backup contains duplicate operations" }
        val byName = (0 until catalog.length()).associate { i ->
            val op = catalog.getJSONObject(i)
            op.getString("name") to opsByCode.getValue(op.getInt("switch"))
        }
        val normalized = changes.map { it.copy(name = byName.getValue(it.name)) }
        require(normalized.groupBy { it.pkg to it.name }.values.all { group -> group.map { it.mode }.distinct().size == 1 }) {
            "Backup has conflicting modes for operations sharing a switch"
        }
        return normalized.distinct()
    }

    private fun packageName(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*"))) { "Invalid backup package name" }
        return value
    }
}
