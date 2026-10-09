package org.androidcontrol.app.files
import android.os.IBinder
class AdbFileService
interface IAdbFileService {
    fun asBinder(): IBinder
    object Stub { fun asInterface(binder: IBinder) = binder as IAdbFileService }
}
