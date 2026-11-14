// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression
import android.app.ProbeState
import android.content.Context
import android.os.Bundle
object RecorderMediaChecks {
    fun run(context: Context) {
        ProbeState.events.add("media")
        check(ProbeState.idle) { "Media raced application startup" }
    }
}
object RecorderAnrTrace {
    fun collect(context: Context, args: Bundle) { ProbeState.events.add("diagnostic") }
}
