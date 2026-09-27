package moe.shizuku.manager.control

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import moe.shizuku.manager.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

object TargetPortraitDisplayClient {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var remote: IAndroidControlService? = null

    @Volatile
    private var binding = false

    private val pending = mutableListOf<(IAndroidControlService) -> Unit>()

    private val userServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, AndroidControlService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("android_control")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            val service =
                if (binder != null && binder.pingBinder()) {
                    IAndroidControlService.Stub.asInterface(binder)
                } else {
                    null
                }

            val actions: List<(IAndroidControlService) -> Unit>
            synchronized(this@TargetPortraitDisplayClient) {
                binding = false
                remote = service
                actions = pending.toList()
                pending.clear()
            }

            if (service != null) {
                actions.forEach { it(service) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(this@TargetPortraitDisplayClient) {
                remote = null
                binding = false
                pending.clear()
            }
        }
    }

    private fun withService(action: (IAndroidControlService) -> Unit) {
        val current = remote
        if (current != null && current.asBinder().pingBinder()) {
            action(current)
            return
        }

        synchronized(this) {
            val again = remote
            if (again != null && again.asBinder().pingBinder()) {
                action(again)
                return
            }

            pending.add(action)
            if (binding) return
            binding = true
        }

        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (_: Throwable) {
            synchronized(this) {
                binding = false
                pending.clear()
            }
        }
    }

    fun start(
        displayId: Int,
        width: Int,
        height: Int,
        callback: (Boolean, String?) -> Unit
    ) {
        withService { service ->
            executor.execute {
                try {
                    val ok = service.launchTargetOnPortraitDisplay(
                        displayId,
                        width,
                        height
                    )
                    val status = try {
                        service.targetPortraitStatus
                    } catch (_: Throwable) {
                        null
                    }
                    mainHandler.post { callback(ok, status) }
                } catch (t: Throwable) {
                    mainHandler.post {
                        callback(false, t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        }
    }

    fun inject(displayId: Int, event: MotionEvent) {
        val service = remote ?: return
        if (!service.asBinder().pingBinder()) return

        val copy = MotionEvent.obtain(event)
        try {
            service.injectTargetMotionEvent(displayId, copy)
        } catch (_: Throwable) {
        } finally {
            copy.recycle()
        }
    }

    fun stop(
        displayId: Int,
        relaunchOnDefaultDisplay: Boolean,
        callback: (() -> Unit)? = null
    ) {
        withService { service ->
            executor.execute {
                try {
                    service.stopTargetPortraitDisplay(
                        displayId,
                        relaunchOnDefaultDisplay
                    )
                } catch (_: Throwable) {
                } finally {
                    mainHandler.post { callback?.invoke() }
                }
            }
        }
    }
}
