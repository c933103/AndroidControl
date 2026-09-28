package moe.shizuku.manager.files

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File
import java.io.FileNotFoundException

@Keep
class AdbFileService @Keep constructor() : IAdbFileService.Stub() {

    @Keep
    constructor(context: Context) : this()

    override fun destroy() {
        System.exit(0)
    }

    override fun listDirectory(path: String): Array<String> {
        val directory = resolve(path)
        if (!directory.isDirectory) {
            throw FileNotFoundException("Not a directory: $path")
        }

        return directory.listFiles()
            ?.mapNotNull { child ->
                try {
                    child.canonicalPath
                } catch (_: Throwable) {
                    child.absolutePath
                }
            }
            ?.sortedWith(
                compareBy<String> { candidate ->
                    try {
                        !File(candidate).isDirectory
                    } catch (_: Throwable) {
                        true
                    }
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { candidate ->
                    File(candidate).name
                }
            )
            ?.toTypedArray()
            ?: emptyArray()
    }

    override fun stat(path: String): String {
        val file = resolve(path)
        if (!file.exists()) {
            throw FileNotFoundException(path)
        }

        val type = when {
            file.isDirectory -> "d"
            file.isFile -> "f"
            else -> "o"
        }

        return listOf(
            type,
            file.length().toString(),
            file.lastModified().toString(),
            if (file.canRead()) "1" else "0",
            if (file.canWrite()) "1" else "0"
        ).joinToString("\u0000")
    }

    override fun openRead(path: String): ParcelFileDescriptor {
        val file = resolve(path)
        if (!file.exists() || file.isDirectory) {
            throw FileNotFoundException(path)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun resolve(path: String): File {
        if (!path.startsWith('/')) {
            throw SecurityException("Only absolute paths are allowed")
        }
        return File(path).canonicalFile
    }
}
