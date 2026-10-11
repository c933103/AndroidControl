// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.system
import java.io.FileDescriptor
import org.androidcontrol.app.recording.Fixture
class ErrnoException : Exception("ESPIPE")
object OsConstants { const val SEEK_SET = 0 }
object Os {
    fun lseek(fd: FileDescriptor, offset: Long, whence: Int): Long {
        check(offset == 0L && whence == OsConstants.SEEK_SET)
        val output = Fixture.outputs.single { it.fileDescriptor === fd }
        if (!output.seekable) throw ErrnoException()
        return 0L
    }
}
