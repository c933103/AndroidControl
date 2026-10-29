// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.system
import java.io.FileDescriptor
object OsConstants { const val SEEK_CUR=1 }
object Os {
    var failSeek=false
    var seeks=0
    fun lseek(fd:FileDescriptor, offset:Long, whence:Int):Long {
        seeks++; check(!failSeek) { "Controlled non-seekable output" }; return offset
    }
}
