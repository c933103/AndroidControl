// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

/** Pure bounded selector: no other processes, threads, trace headers or free-form descriptions. */
object RecorderAnrThreads {
    const val PACKAGE = "org.androidcontrol.app"
    const val MAX_BYTES = 512 * 1024
    private const val INSTRUMENTATION = "Instr: org.androidcontrol.app.regression.RecorderMediaInstrumentation"

    fun select(trace: String, pid: Int): List<String> {
        require(pid > 0 && trace.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val result = mutableListOf<String>()
        var targetPid = false
        var targetPackage = false
        var retainThread = false
        var threadLines = 0
        var threads = 0
        for (line in trace.lineSequence()) {
            if (line.startsWith("----- pid ")) {
                targetPid = Regex("----- pid $pid at .+ -----").matches(line)
                targetPackage = false; retainThread = false
            } else if (line.startsWith("----- end ")) {
                targetPid = false; targetPackage = false; retainThread = false
            } else if (line.startsWith("Cmd line: ")) {
                targetPackage = targetPid && line == "Cmd line: $PACKAGE"
                retainThread = false
            } else if (line.startsWith('"')) {
                val name = line.substringAfter('"').substringBefore('"')
                retainThread = targetPackage && threads < 2 && (name == "main" || name == INSTRUMENTATION)
                if (retainThread) { threads++; threadLines = 1; result.add(line.take(1024)) }
            } else if (retainThread && threadLines < 100 && line.length <= 1024 &&
                (line.startsWith("  at ") || line.startsWith("  native: ") ||
                 line.startsWith("  - ") || line.startsWith("  | "))) {
                result.add(line); threadLines++
            }
        }
        return result
    }
}
