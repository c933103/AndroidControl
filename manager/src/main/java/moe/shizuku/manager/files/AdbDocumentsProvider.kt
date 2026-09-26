package moe.shizuku.manager.files

import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.util.Base64

class AdbDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = BuildConfig.APPLICATION_ID + ".adbfiles"
        const val ROOT_ID = "adb-files"
        const val ROOT_DOCUMENT_ID = "root"

        private val ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    private data class Stat(
        val directory: Boolean,
        val size: Long,
        val lastModified: Long,
        val readable: Boolean,
        val writable: Boolean
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        cursor.newRow()
            .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            .add(DocumentsContract.Root.COLUMN_TITLE, context?.getString(R.string.adb_files_root_title))
            .add(DocumentsContract.Root.COLUMN_SUMMARY, context?.getString(R.string.adb_files_root_summary))
            .add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            )
            .add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_folder_open_24)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        includeDocument(cursor, pathFromDocumentId(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val parentPath = pathFromDocumentId(parentDocumentId)
        val service = service()

        try {
            service.listDirectory(parentPath).forEach { childPath ->
                try {
                    includeDocument(cursor, childPath, service)
                } catch (_: Throwable) {
                    // A single disappearing or inaccessible entry must not hide the directory.
                }
            }
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: parentPath)
        }

        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode.contains('w') || mode.contains('a') || mode.contains('+')) {
            throw FileNotFoundException("ADB file browser is read-only in this version")
        }

        return try {
            service().openRead(pathFromDocumentId(documentId))
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: documentId)
        }
    }

    override fun getDocumentType(documentId: String): String {
        val path = pathFromDocumentId(documentId)
        return mimeType(path, stat(path))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = pathFromDocumentId(parentDocumentId)
        val child = pathFromDocumentId(documentId)
        if (parent == "/") return child.startsWith("/")
        val prefix = if (parent.endsWith('/')) parent else "$parent/"
        return child.startsWith(prefix)
    }

    private fun includeDocument(
        cursor: MatrixCursor,
        path: String,
        existingService: IAdbFileService? = null
    ) {
        val service = existingService ?: service()
        val stat = stat(path, service)
        val id = documentIdFromPath(path)
        val displayName =
            if (path == "/") {
                context?.getString(R.string.adb_files_root_title) ?: "ADB filesystem"
            } else {
                File(path).name.ifBlank { path }
            }

        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(path, stat))
            .add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            .add(
                DocumentsContract.Document.COLUMN_SIZE,
                if (stat.directory) null else stat.size
            )
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, stat.lastModified)
    }

    private fun stat(path: String, existingService: IAdbFileService? = null): Stat {
        val raw = try {
            (existingService ?: service()).stat(path)
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: path)
        }

        val parts = raw.split('\u0000')
        if (parts.size < 5) throw FileNotFoundException("Invalid stat response for $path")

        return Stat(
            directory = parts[0] == "d",
            size = parts[1].toLongOrNull() ?: 0L,
            lastModified = parts[2].toLongOrNull() ?: 0L,
            readable = parts[3] == "1",
            writable = parts[4] == "1"
        )
    }

    private fun mimeType(path: String, stat: Stat): String {
        if (stat.directory) return DocumentsContract.Document.MIME_TYPE_DIR

        val extension = path.substringAfterLast('.', "").lowercase()
        return if (extension.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }
    }

    private fun service(): IAdbFileService {
        return try {
            AdbFileClient.requireService()
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: "Shizuku is not running")
        }
    }

    private fun documentIdFromPath(path: String): String {
        if (path == "/") return ROOT_DOCUMENT_ID
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(path.toByteArray(StandardCharsets.UTF_8))
        return "p:$encoded"
    }

    private fun pathFromDocumentId(documentId: String): String {
        if (documentId == ROOT_DOCUMENT_ID) return "/"
        if (!documentId.startsWith("p:")) throw FileNotFoundException(documentId)

        return try {
            String(
                Base64.getUrlDecoder().decode(documentId.removePrefix("p:")),
                StandardCharsets.UTF_8
            ).also {
                if (!it.startsWith('/')) throw FileNotFoundException(documentId)
            }
        } catch (t: Throwable) {
            throw FileNotFoundException(t.message ?: documentId)
        }
    }
}
