// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression
import android.app.Application
import android.app.ProbeState
import android.content.Context
import android.os.Bundle
fun main() {
    val runner = RecorderControlInstrumentation()
    val app = runner.newApplication(runner.javaClass.classLoader, "org.androidcontrol.app.ShizukuApplication", Context())
    check(app.javaClass == Application::class.java)
    check(ProbeState.applicationClassRequested == "android.app.Application")
    println("PASS only-custom-application-replaced")
    check(runCatching { runner.newApplication(runner.javaClass.classLoader, "unrelated.Application", Context()) }.isFailure)
    println("PASS unexpected-manifest-application-rejected")
    runner.targetContext.applicationContext = app
    runner.onCreate(Bundle()); runner.onStart()
    check(ProbeState.result?.getString("recorder_media") == "PASS")
    check(ProbeState.events.count { it == "media" } == 1)
    check(ProbeState.events.indexOf("application-idle") < ProbeState.events.indexOf("media"))
    println("PASS identical-inherited-readiness-and-media-path")
}
