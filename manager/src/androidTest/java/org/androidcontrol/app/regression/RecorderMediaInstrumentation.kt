// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

import android.app.Instrumentation
import android.os.Bundle

/** Independent synthetic-only runner: no permission, shell or service operations. */
class RecorderMediaInstrumentation : Instrumentation() {
    private var anrArguments: Bundle? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (arguments?.getString("anr_pid") != null) anrArguments = Bundle(arguments)
        sendStatus(0, Bundle().apply { putString("recorder_media_stage", "created"); putInt("recorder_media_pid", android.os.Process.myPid()); putLong("recorder_media_started_ms", System.currentTimeMillis()) })
        start()
    }
    override fun onStart() {
        anrArguments?.let {
            RecorderAnrTrace.collect(targetContext, it)
            finish(0, Bundle().apply { putString("recorder_anr_diagnostic", "finished") })
            return
        }
        sendStatus(0, Bundle().apply { putString("recorder_media_stage", "started") })
        val result = Bundle()
        try {
            sendStatus(0, Bundle().apply { putString("recorder_media_stage", "waiting-for-application-idle") })
            waitForIdleSync()
            sendStatus(0, Bundle().apply { putString("recorder_media_stage", "application-idle") })
            RecorderMediaChecks.run(targetContext)
            result.putString("recorder_media", "PASS")
            finish(-1, result)
        } catch (failure: Throwable) {
            result.putString("recorder_media", "FAIL")
            result.putString("failure", failure.stackTraceToString())
            finish(0, result)
        }
    }
}
