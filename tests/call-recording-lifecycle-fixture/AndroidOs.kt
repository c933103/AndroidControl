// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.os

import org.androidcontrol.app.recording.services.shell.Fixture
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class ParcelFileDescriptor(val end: String) {
    @Volatile var closed = false
    fun close() { closed = true }
    companion object {
        val allocated = CopyOnWriteArrayList<ParcelFileDescriptor>()
        fun createPipe(): Array<ParcelFileDescriptor> {
            if (Fixture.failure == "pipe") throw IOException("controlled pipe failure")
            return arrayOf(ParcelFileDescriptor("read"), ParcelFileDescriptor("write")).also {
                allocated.addAll(it)
            }
        }
    }
    class AutoCloseOutputStream(private val descriptor: ParcelFileDescriptor?) : OutputStream() {
        override fun write(value: Int) {
            check(descriptor != null && !descriptor.closed)
            Fixture.output.add(value)
        }
        override fun close() { descriptor?.close() }
    }
}
