// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package androidx.documentfile.provider
import android.content.Context
import android.net.Uri
import org.androidcontrol.app.recording.Fixture
class DocumentFile(val uri: Uri) {
    val isDirectory = true
    fun canWrite() = true
    fun createFile(mime: String, name: String): DocumentFile {
        Fixture.documents++
        if (Fixture.hideDuringSetup) Fixture.notifications.enabled = false
        return DocumentFile(Uri("content://fixture/new"))
    }
    fun delete(): Boolean { Fixture.deletes++; return true }
    companion object { fun fromTreeUri(context: Context, uri: Uri) = DocumentFile(uri) }
}
