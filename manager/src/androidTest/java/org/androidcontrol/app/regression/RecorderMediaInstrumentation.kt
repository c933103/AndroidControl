// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock

/** Independent synthetic-only runner: no permission, shell or service operations. */
open class RecorderMediaInstrumentation : Instrumentation() {
    private var anrArguments: Bundle? = null
    private var decoderOnly = false
    private var runNonce = ""
    private fun stage(name: String) {
        runCatching { sendStatus(0, Bundle().apply {
            putString("recorder_media_stage", name)
            putLong("recorder_stage_elapsed_ms", SystemClock.elapsedRealtime())
            putLong("recorder_stage_wall_ms", System.currentTimeMillis())
        }) }
    }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        val mode = arguments?.getString("recorder_mode") ?: "integration"
        check(mode == "integration" || mode == "decoder-only") { "Unsupported recorder mode" }
        decoderOnly = mode == "decoder-only"
        runNonce = arguments?.getString("recorder_run_nonce") ?: ""
        check(runNonce.matches(Regex("[a-f0-9]{32}")) || arguments?.getString("anr_pid") != null) { "Missing run nonce" }
        if (arguments?.getString("anr_pid") != null) anrArguments = Bundle(arguments)
        sendStatus(0, Bundle().apply { putString("recorder_run_nonce", runNonce); putString("recorder_media_stage", "created"); putLong("recorder_stage_elapsed_ms", SystemClock.elapsedRealtime()); putLong("recorder_stage_wall_ms", System.currentTimeMillis()); putInt("recorder_media_pid", android.os.Process.myPid()); putLong("recorder_media_started_ms", System.currentTimeMillis()) })
        start()
    }
    override fun onStart() {
        anrArguments?.let {
            RecorderAnrTrace.collect(targetContext, it)
            finish(0, Bundle().apply { putString("recorder_anr_diagnostic", "finished") })
            return
        }
        stage("started")
        val result = Bundle().apply {
            putString("recorder_run_nonce", runNonce)
            putInt("recorder_media_pid", android.os.Process.myPid())
        }
        val resultKey = if (decoderOnly) "recorder_decoder_probe" else "recorder_media"
        try {
            stage("waiting-for-application-idle")
            waitForIdleSync()
            stage("application-idle")
            sendStatus(0, Bundle().apply {
                putString("recorder_application_class", targetContext.applicationContext.javaClass.name)
                putString("recorder_instrumentation_class", this@RecorderMediaInstrumentation.javaClass.name)
            })
            if (decoderOnly) RecorderMediaChecks.runDecoderProbe(targetContext, context, runNonce)
            else RecorderMediaChecks.run(targetContext, runNonce)
            stage("media-returned")
            result.putString(resultKey, "PASS")
            stage("finish-requested")
            finish(-1, result)
        } catch (failure: Throwable) {
            stage("media-failed")
            result.putString(resultKey, "FAIL")
            result.putString("failure", failure.stackTraceToString())
            stage("finish-requested")
            finish(0, result)
        }
    }
}

