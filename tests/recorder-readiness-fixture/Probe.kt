// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression
import android.app.ProbeState
import android.os.Bundle
fun main(args: Array<String>) {
    val runner = RecorderMediaInstrumentation()
    val bundle = Bundle()
    if (args.single() == "diagnostic") bundle.putString("anr_pid", "123")
    if (args.single() == "idle-failure") ProbeState.rejectIdle = true
    runner.onCreate(bundle)
    check("media" !in ProbeState.events && "waitForIdleSync" !in ProbeState.events)
    runner.onStart()
    when (args.single()) {
        "ready" -> {
            check(ProbeState.result?.getString("recorder_media") == "PASS") { ProbeState.events.toString() }
            check(ProbeState.events.indexOf("waiting-for-application-idle") < ProbeState.events.indexOf("waitForIdleSync"))
            check(ProbeState.events.indexOf("waitForIdleSync") < ProbeState.events.indexOf("application-idle"))
            check(ProbeState.events.indexOf("application-idle") < ProbeState.events.indexOf("media"))
        }
        "idle-failure" -> {
            check(ProbeState.result?.getString("recorder_media") == "FAIL")
            check("media" !in ProbeState.events && "application-idle" !in ProbeState.events)
        }
        "diagnostic" -> {
            check("diagnostic" in ProbeState.events && "media" !in ProbeState.events)
            check(ProbeState.result?.getString("recorder_anr_diagnostic") == "finished")
        }
        else -> error("Unknown scenario")
    }
    println("PASS ${args.single()}: ${ProbeState.events}")
}
