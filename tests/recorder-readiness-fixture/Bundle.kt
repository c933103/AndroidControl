// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.os
class Bundle() {
    private val data = mutableMapOf<String, Any>()
    constructor(other: Bundle): this() { data.putAll(other.data) }
    fun putString(key: String, value: String) { data[key] = value }
    fun putInt(key: String, value: Int) { data[key] = value }
    fun putLong(key: String, value: Long) { data[key] = value }
    fun getString(key: String): String? = data[key] as? String
}
object Process { fun myPid(): Int = 123 }


object SystemClock { fun elapsedRealtime(): Long = 1234L }
