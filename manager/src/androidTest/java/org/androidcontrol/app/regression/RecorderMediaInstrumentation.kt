// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

import android.app.Instrumentation
import android.os.Bundle

/** Independent synthetic-only runner: no permission, shell or service operations. */
class RecorderMediaInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
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
