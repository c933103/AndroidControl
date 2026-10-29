// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

import android.app.Application
import android.content.Context

/** Test-only initialization control; never selected by the production manifest. */
class RecorderControlInstrumentation : RecorderMediaInstrumentation() {
    override fun newApplication(loader: ClassLoader, className: String, context: Context): Application {
        check(className == "org.androidcontrol.app.ShizukuApplication")
        return super.newApplication(RecorderControlApplication::class.java.classLoader, RecorderControlApplication::class.java.name, context).also {
            check(it.javaClass == RecorderControlApplication::class.java)
        }
    }
}
