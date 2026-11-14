// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Reads only this application's exact failed ANR record; does not execute media checks. */
object RecorderAnrTrace {
    fun collect(context: Context, arguments: Bundle) {
        val report = JSONObject().put("package", RecorderAnrThreads.PACKAGE)
        try {
            check(context.packageName == RecorderAnrThreads.PACKAGE)
            val pid = arguments.getString("anr_pid")!!.toInt()
            val timestamp = arguments.getString("anr_timestamp")!!.toLong()
            val started = arguments.getString("anr_started")!!.toLong()
            val observed = arguments.getString("anr_observed")!!.toLong()
            check(pid > 0 && started > 0 && observed - started in 0..300_000 && timestamp in started..observed)
            report.put("pid", pid).put("timestampMs", timestamp).put("startedMs", started).put("observedThroughMs", observed)
            val manager = context.getSystemService(ActivityManager::class.java)
            val exit = manager.getHistoricalProcessExitReasons(RecorderAnrThreads.PACKAGE, pid, 8)
                .firstOrNull { it.pid == pid && it.processName == RecorderAnrThreads.PACKAGE &&
                    it.reason == ApplicationExitInfo.REASON_ANR && it.timestamp == timestamp }
            if (exit == null) {
                report.put("result", "matching-anr-unavailable")
            } else {
                report.put("reason", exit.reason).put("status", exit.status)
                val trace = exit.traceInputStream?.use { input ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, RecorderAnrThreads.MAX_BYTES + 1 - bytes.size()))
                        if (count < 0) break
                        bytes.write(buffer, 0, count)
                        check(bytes.size() <= RecorderAnrThreads.MAX_BYTES) { "Trace exceeds bound" }
                    }
                    bytes.toString("UTF-8")
                }
                val threads = trace?.let { RecorderAnrThreads.select(it, pid) }.orEmpty()
                report.put("result", if (threads.isEmpty()) "target-threads-unavailable" else "matched")
                    .put("threads", JSONArray(threads))
            }
        } catch (_: Exception) {
            report.put("result", "diagnostic-unavailable")
        }
        val directory = File(context.cacheDir, "recorder-media").apply { mkdirs() }
        File(directory, "anr-threads.json").writeText(report.toString(2))
    }
}
