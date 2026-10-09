package org.androidcontrol.app.regression

import kotlin.math.abs
import kotlin.math.roundToInt

/** Test-only geometry gate. Shell tap coordinates and all bounds here are logical. */
internal data class CheckoutRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun valid() = right > left && bottom > top
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    fun contains(r: CheckoutRect) = r.valid() && contains(r.left, r.top) && contains(r.right - 1, r.bottom - 1)
    fun near(r: CheckoutRect) = listOf(left.toLong() - r.left, top.toLong() - r.top,
        right.toLong() - r.right, bottom.toLong() - r.bottom).all { abs(it) <= 1L }
    override fun toString() = "$left,$top,$right,$bottom"
    companion object {
        fun parse(value: String?): CheckoutRect? {
            val n = value?.split(',')?.map { it.toIntOrNull() ?: return null } ?: return null
            return if (n.size == 4) CheckoutRect(n[0], n[1], n[2], n[3]) else null
        }
    }
}

internal data class CheckoutReadySnapshot(
    val display: Int, val visible: Boolean, val focused: Boolean, val x: Int, val y: Int,
    val generation: Long, val sequence: Long, val sampleMs: Long, val rotation: Int,
    val width: Int, val height: Int, val window: CheckoutRect, val button: CheckoutRect
) {
    fun geometry() = listOf(generation, display, rotation, width, height, window, button, x, y)
    fun evidence() = "generation=$generation;sequence=$sequence;sampleMs=$sampleMs;display=$display;" +
        "rotation=$rotation;width=$width;height=$height;window=$window;button=$button;" +
        "buttonVisible=$visible;focused=$focused;x=$x;y=$y"
    companion object {
        fun parse(text: String): CheckoutReadySnapshot? {
            val pairs = text.trim().split(';').map { it.split('=', limit = 2) }
            if (pairs.any { it.size != 2 } || pairs.map { it[0] }.distinct().size != pairs.size) return null
            val p = pairs.associate { it[0] to it[1] }
            if (p.keys != setOf("display", "buttonVisible", "focused", "x", "y", "generation", "sequence",
                    "sampleMs", "rotation", "width", "height", "window", "button")) return null
            return CheckoutReadySnapshot(p["display"]?.toIntOrNull() ?: return null,
                p["buttonVisible"]?.toBooleanStrictOrNull() ?: return null,
                p["focused"]?.toBooleanStrictOrNull() ?: return null,
                p["x"]?.toIntOrNull() ?: return null, p["y"]?.toIntOrNull() ?: return null,
                p["generation"]?.toLongOrNull() ?: return null, p["sequence"]?.toLongOrNull() ?: return null,
                p["sampleMs"]?.toLongOrNull() ?: return null, p["rotation"]?.toIntOrNull() ?: return null,
                p["width"]?.toIntOrNull() ?: return null, p["height"]?.toIntOrNull() ?: return null,
                CheckoutRect.parse(p["window"]) ?: return null, CheckoutRect.parse(p["button"]) ?: return null)
        }
    }
}

internal data class CheckoutInputState(val rotation: Int, val width: Int, val height: Int, val window: CheckoutRect) {
    fun evidence() = "inputRotation=$rotation;inputWidth=$width;inputHeight=$height;inputWindow=$window"
    companion object {
        private const val COMPONENT = "org.androidcontrol.regression.checkout/org.androidcontrol.regression.checkout.CheckoutActivity"
        private fun focusedWindow(lines: List<String>): String? {
            val focus = lines.indexOfFirst { it.trim() == "FocusedWindows:" }
            if (focus < 0) return null
            return lines.drop(focus + 1).takeWhile { it.trim() != "FocusRequests:" }.mapNotNull {
                Regex("^\\s*displayId=0, name='([^']+)'$").find(it)?.groupValues?.get(1)
            }.singleOrNull()?.takeIf { it.endsWith(" $COMPONENT") }
        }
        fun numericEvidence(dump: String): String {
            val lines = dump.lines()
            val focused = focusedWindow(lines)
            val display = lines.indexOfFirst { it.trim() == "Display: 0" }
            val header = if (display >= 0) lines.drop(display + 1).takeWhile { it.trim() != "Windows:" } else emptyList()
            val window = lines.firstOrNull { focused != null && Regex("^\\s*\\d+: name=").containsMatchIn(it) &&
                it.substringAfter("name=").substringBefore(", id=") == focused }?.substringAfter(", id=") ?: ""
            fun numbers(value: String) = Regex("-?\\d+(?:\\.\\d+)?").findAll(value).take(64).joinToString(",") { it.value }
            val flags = window.substringAfter("inputConfig=", "").substringBefore(", alpha=")
                .split(Regex("\\s*\\|\\s*")).filter { it.matches(Regex("[A-Z_]+|0x[0-9a-fA-F]+")) }.joinToString("|")
            return "inputHeaderNumbers=${numbers(header.joinToString(" "))};" +
                "fixtureWindowNumbers=${numbers(window)};fixtureInputFlags=$flags"
        }
        fun parse(dump: String): CheckoutInputState? {
            if (!Regex("DispatchEnabled:\\s*(?:true|1)\\b").containsMatchIn(dump) ||
                !Regex("DispatchFrozen:\\s*(?:false|0)\\b").containsMatchIn(dump)) return null
            val lines = dump.lines()
            val focused = focusedWindow(lines) ?: return null
            val display = lines.indexOfFirst { it.trim() == "Display: 0" }
            if (display < 0) return null
            val header = lines.drop(display + 1).takeWhile { it.trim() != "Windows:" }
            val size = header.firstNotNullOfOrNull { Regex("logicalSize=(\\d+)x(\\d+)").find(it) } ?: return null
            val w = size.groupValues[1].toIntOrNull() ?: return null
            val h = size.groupValues[2].toIntOrNull() ?: return null
            if (w <= 0 || h <= 0) return null
            val transform = header.indexOfFirst { it.trim().startsWith("transform ") }
            if (transform < 0 || header.size <= transform + 3) return null
            val matrix = header.subList(transform + 1, transform + 4).map { line ->
                line.trim().split(Regex("\\s+")).map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null }
            }
            if (matrix.any { it.size != 3 }) return null
            if (matrix[2].zip(listOf(0.0, 0.0, 1.0)).any { abs(it.first - it.second) > 0.0001 }) return null
            val a = matrix[0][0]; val b = matrix[0][1]; val c = matrix[0][2]
            val d = matrix[1][0]; val e = matrix[1][1]; val f = matrix[1][2]
            fun matches(vararg values: Double) = listOf(a, b, c, d, e, f).zip(values.toList()).all { abs(it.first - it.second) < 0.01 }
            // Display's native-to-logical transform is inverse to Display.rotation.
            val rotation = when {
                matches(1.0, 0.0, 0.0, 0.0, 1.0, 0.0) -> 0
                matches(0.0, 1.0, 0.0, -1.0, 0.0, h.toDouble()) -> 1
                matches(-1.0, 0.0, w.toDouble(), 0.0, -1.0, h.toDouble()) -> 2
                matches(0.0, -1.0, w.toDouble(), 1.0, 0.0, 0.0) -> 3
                else -> return null
            }
            val line = lines.firstOrNull { Regex("^\\s*\\d+: name=").containsMatchIn(it) &&
                it.substringAfter("name=").substringBefore(", id=") == focused && it.contains("displayId=0,") } ?: return null
            val flags = line.substringAfter("inputConfig=", "").substringBefore(", alpha=")
            if (flags.isEmpty() || listOf("NOT_VISIBLE", "NOT_TOUCHABLE", "NO_INPUT_CHANNEL", "DROP_INPUT", "PAUSE_DISPATCHING").any { it in flags }) return null
            val rect = Regex("frame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(line) ?: return null
            val n = rect.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            val points = listOf(n[0] to n[1], n[0] to n[3], n[2] to n[1], n[2] to n[3]).map { (x, y) ->
                (a * x + b * y + c).roundToInt() to (d * x + e * y + f).roundToInt()
            }
            val window = CheckoutRect(points.minOf { it.first }, points.minOf { it.second },
                points.maxOf { it.first }, points.maxOf { it.second })
            return CheckoutInputState(rotation, w, h, window).takeIf { window.valid() }
        }
    }
}

/** The caller retains its original overall deadline; this gate never sleeps or taps. */
internal class CheckoutReadiness {
    private var first: CheckoutReadySnapshot? = null
    private var last: CheckoutReadySnapshot? = null
    fun observe(sample: CheckoutReadySnapshot?, input: CheckoutInputState?, now: Long): Boolean {
        val display = input?.let { CheckoutRect(0, 0, it.width, it.height) }
        val valid = sample != null && input != null && display != null && sample.display == 0 &&
            sample.visible && sample.focused && sample.generation >= 0 && sample.sequence > 0 &&
            sample.sampleMs >= sample.generation && now - sample.sampleMs in 0..750 &&
            sample.rotation == input.rotation && sample.width == input.width && sample.height == input.height &&
            sample.window.near(input.window) && display.contains(sample.button) && sample.window.contains(sample.button) &&
            sample.button.contains(sample.x, sample.y)
        if (!valid) { first = null; last = null; return false }
        sample!!
        val previous = last
        if (previous != null && previous.generation == sample.generation &&
            sample.sequence == previous.sequence && sample != previous) {
            first = null; last = null; return false
        }
        if (previous == null || previous.geometry() != sample.geometry() ||
            sample.sequence < previous.sequence || sample.sampleMs < previous.sampleMs) first = sample
        last = sample
        val start = first!!
        return sample.sequence > start.sequence && sample.sampleMs - start.sampleMs >= 500
    }
}
