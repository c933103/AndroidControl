// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package org.androidcontrol.app
object ShizukuSettings {
    object LaunchMethod { const val UNKNOWN = -1 }
    var initialized: Any? = null
    var mode = LaunchMethod.UNKNOWN
    fun initialize(context: Any) { initialized = context }
    fun getLastLaunchMode(): Int { check(initialized != null); return mode }
}
