package android.content
import android.os.IBinder
class ComponentName(val pkg: String, val cls: String)
interface ServiceConnection {
    fun onServiceConnected(name: ComponentName, binder: IBinder?)
    fun onServiceDisconnected(name: ComponentName)
    fun onBindingDied(name: ComponentName) { }
    fun onNullBinding(name: ComponentName) { }
}
