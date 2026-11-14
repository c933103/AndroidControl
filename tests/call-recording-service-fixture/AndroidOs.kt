// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.os
import java.io.FileDescriptor
object Build { object VERSION { var SDK_INT = 35 }; object VERSION_CODES { const val S = 31; const val TIRAMISU = 33 } }
interface IBinder
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun post(callback: Runnable) { callback.run() }
    fun postDelayed(callback: Runnable, delay: Long) {}
    fun removeCallbacks(callback: Runnable) {}
}
class ParcelFileDescriptor(val kind: String, val seekable: Boolean = true) {
    val fileDescriptor = FileDescriptor()
    var closed = false
    fun close() { closed = true }
}
