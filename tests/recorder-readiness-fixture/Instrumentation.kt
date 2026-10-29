// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.app
import android.content.Context
import android.os.Bundle
object ProbeState {
    val events = mutableListOf<String>()
    var idle = false
    var rejectIdle = false
    var result: Bundle? = null
}
open class Instrumentation {
    val targetContext = Context()
    open fun onCreate(arguments: Bundle?) {}
    open fun onStart() {}
    fun start() { ProbeState.events.add("start-thread") }
    fun sendStatus(code: Int, result: Bundle) { result.getString("recorder_media_stage")?.let { ProbeState.events.add(it) } }
    fun waitForIdleSync() {
        ProbeState.events.add("waitForIdleSync")
        check(!ProbeState.rejectIdle) { "Synthetic idle failure" }
        ProbeState.idle = true
    }
    fun finish(code: Int, result: Bundle) { ProbeState.result = result; ProbeState.events.add("finish") }
}
