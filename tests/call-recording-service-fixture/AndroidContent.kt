// SPDX-FileCopyrightText: 2026 AndroidControl contributors
// SPDX-License-Identifier: Apache-2.0
package android.content
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import android.net.Uri
import android.telephony.TelephonyManager
import org.androidcontrol.app.recording.Fixture
open class Context {
    val contentResolver = ContentResolver()
    val packageName = "org.androidcontrol.fixture"
    fun <T> getSystemService(type: Class<T>): T = type.cast(when (type) {
        NotificationManager::class.java -> Fixture.notifications
        TelephonyManager::class.java -> TelephonyManager()
        else -> error("Unknown service")
    })
    fun getString(id: Int) = "fixture"
    fun startService(intent: Intent) {}
    fun sendBroadcast(intent: Intent) {}
}
class Intent {
    var action: String? = null
    constructor(action: String) { this.action = action }
    constructor(context: Context, type: Class<*>)
    fun setAction(action: String) = apply { this.action = action }
    fun setPackage(name: String) = this
}
class UriPermission(val uri: Uri, val isWritePermission: Boolean = true)
class ContentResolver {
    val persistedUriPermissions = listOf(UriPermission(Fixture.folder))
    fun openFileDescriptor(uri: Uri, mode: String): ParcelFileDescriptor? {
        Fixture.modes.add(mode)
        if (Fixture.provider == "no-rw" && mode != "w") throw java.io.FileNotFoundException("rw unsupported")
        if (Fixture.provider == "null") return null
        val seekable = when (Fixture.provider) {
            "w-streams", "no-rw" -> mode == "rw"
            "bad-rw" -> false
            else -> true
        }
        return ParcelFileDescriptor("output", seekable).also { Fixture.outputs.add(it) }
    }
}
