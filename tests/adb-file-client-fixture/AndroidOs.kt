package android.os
interface IBinder { val isBinderAlive: Boolean; fun pingBinder(): Boolean }
class Looper {
    companion object {
        private val main = Looper()
        var onMainThread = false
        @JvmStatic fun myLooper(): Looper? = if (onMainThread) main else null
        @JvmStatic fun getMainLooper() = main
    }
}
