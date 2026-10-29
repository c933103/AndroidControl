// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app.regression

import android.app.Application
import org.androidcontrol.app.ShizukuSettings

/** Test-only control retaining the settings prerequisite of boot receivers. */
class RecorderControlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuSettings.initialize(this)
        check(ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.UNKNOWN) {
            "Control requires fresh settings with no privileged launch mode"
        }
    }
}
