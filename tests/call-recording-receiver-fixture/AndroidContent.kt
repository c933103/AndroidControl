// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.content
import android.os.UserManager
abstract class BroadcastReceiver { abstract fun onReceive(context: Context, intent: Intent) }
class Context(val userManager: UserManager?) {
    fun <T> getSystemService(type: Class<T>): T? = type.cast(userManager)
}
class Intent(val action: String?, private val state: String?) {
    fun getStringExtra(key: String): String? = state
}
