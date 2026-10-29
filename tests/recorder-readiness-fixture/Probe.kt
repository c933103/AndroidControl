// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression
import android.app.ProbeState
import android.os.Bundle
fun main(args: Array<String>) {
    if (args.single() == "decode-progress") {
        val progress = RecorderDecodeProgress()
        progress.queuedInputs = 46; progress.queuedBytes = 6400; progress.inputEos = true
        progress.outputBuffers = 12; progress.outputFrames = 10240
        progress.inputUnavailable = 3; progress.outputUnavailable = 4
        progress.operation(0, 100, 140); progress.operation(0, 200, 210)
        progress.operation(4, 100, 120)
        var result: Map<String, Any>? = null
        progress.onFailure { result = it }
        check(result!!.size == 10 && result!!["queuedInputs"] == 46L && result!!["inputEos"] == true)
        check(result!!["outputFrames"] == 10240L && result!!["outputEos"] == false)
        check(result!!["queuedBytes"] == 6400L && result!!["outputBuffers"] == 12L)
        check(result!!["inputUnavailable"] == 3L && result!!["outputUnavailable"] == 4L)
        val operations = result!!["operations"] as List<*>
        check(operations.size == 5)
        check((operations[0] as Map<*, *>)["totalNs"] == 50L)
        check((operations[0] as Map<*, *>)["maxNs"] == 40L)
        check((operations[4] as Map<*, *>)["totalNs"] == 20L)
        progress.outputEos = true
        repeat(10000) { progress.operation(2, 0, 1) }
        progress.onFailure { result = it }
        check(result!!.size == 10 && result!!["outputEos"] == true)
        RecorderDecodeProgress().onFailure { result = it }
        check(result!!["queuedInputs"] == 0L && result!!["inputEos"] == false)
        check((result!!["loop"] as Map<*, *>)["status"] == "not-started")
        for ((cpuStart, cpuEnd, expected) in listOf(
            Triple(100L, 1090L, 990L), Triple(100L, 110L, 10L),
            Triple(-1L, 100L, "unavailable"), Triple(100L, -1L, "unavailable"),
            Triple(100L, 99L, "unavailable")
        )) {
            progress.loopTiming(1000, 2000, cpuStart, cpuEnd, 42)
            progress.onFailure { result = it }
            val loop = result!!["loop"] as Map<*, *>
            check(loop.size == 3 && loop["callerThreadId"] == 42L)
            check(loop["wallNs"] == 1000L && loop["callerCpuNs"] == expected)
        }
        progress.loopTiming(2000, 1000, 100, 110, 42)
        progress.onFailure { result = it }
        check((result!!["loop"] as Map<*, *>)["wallNs"] == "unavailable")
        val original = IllegalStateException("original timeout")
        val caught = runCatching {
            try { throw original } catch (failure: Throwable) {
                progress.onFailure { error("diagnostic write failed") }
                throw failure
            }
        }.exceptionOrNull()
        check(caught === original)
        println("PASS decode-progress: constant counters/timing and original failure preserved")
        return
    }
    val runner = RecorderMediaInstrumentation()
    val bundle = Bundle()
    if (args.single() == "diagnostic") bundle.putString("anr_pid", "123")
    if (args.single() == "idle-failure") ProbeState.rejectIdle = true
    if (args.single() == "decoder-only") bundle.putString("recorder_mode", "decoder-only")
    if (args.single() == "invalid-mode") {
        bundle.putString("recorder_mode", "not-allowed")
        check(runCatching { runner.onCreate(bundle) }.isFailure)
        check(ProbeState.events.isEmpty())
        println("PASS invalid-mode rejected before start")
        return
    }
    runner.onCreate(bundle)
    check("media" !in ProbeState.events && "waitForIdleSync" !in ProbeState.events)
    runner.onStart()
    when (args.single()) {
        "decoder-only" -> {
            check(ProbeState.result?.getString("recorder_decoder_probe") == "PASS")
            check(ProbeState.result?.getString("recorder_media") == null)
            check(ProbeState.events.count { it == "decoder-probe" } == 1 && "media" !in ProbeState.events)
        }
        "ready" -> {
            check(ProbeState.instrumentationClass == RecorderMediaInstrumentation::class.java.name)
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
