package moe.shizuku.manager.control

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.MotionEvent
import android.view.Surface
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

    private data class PendingAction(
        val run: (IAndroidControlService) -> Unit,
        val fail: () -> Unit
    )

    private val pending = mutableListOf<PendingAction>()
    private val bindTimeout = Runnable { failPending() }

    private fun failPending() {
        val actions: List<PendingAction>
        synchronized(this) {
            remote = null
            binding = false
            actions = pending.toList()
            pending.clear()
        }
        mainHandler.removeCallbacks(bindTimeout)
        actions.forEach { it.fail() }
    }

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

            mainHandler.removeCallbacks(bindTimeout)
            val actions: List<PendingAction>
            synchronized(this@TargetPortraitDisplayClient) {
                binding = false
                remote = service
                actions = pending.toList()
                pending.clear()
            }

            if (service != null) {
                actions.forEach { it.run(service) }
            } else {
                actions.forEach { it.fail() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            failPending()
        }
    }

    private fun withService(
        action: (IAndroidControlService) -> Unit,
        onFailure: () -> Unit
    ) {
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

            pending.add(PendingAction(action, onFailure))
            if (binding) return
            binding = true
        }

        mainHandler.postDelayed(bindTimeout, 10000)
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (_: Throwable) {
            failPending()
        }
    }

    fun start(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        hostToken: IBinder,
        packageName: String,
        hostTaskId: Int,
        callback: (Int, String?) -> Unit
    ) {
        val retained = copySurface(surface)
        withService({ service ->
            executor.execute {
                try {
                    val id = service.createTargetPortraitSession(retained, width, height, densityDpi,
                        hostToken, packageName, hostTaskId)
                    val status = try {
                        service.targetPortraitStatus
                    } catch (_: Throwable) {
                        null
                    }
                    mainHandler.post { callback(id, status) }
                } catch (t: Throwable) {
                    mainHandler.post {
                        callback(-1, t.message ?: t.javaClass.simpleName)
                    }
                } finally {
                    retained.release()
                }
            }
        }, {
            retained.release()
            mainHandler.post {
                callback(-1, "AndroidControl service unavailable")
            }
        })
    }

    private fun copySurface(surface: Surface): Surface {
        val parcel = Parcel.obtain()
        return try {
            surface.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Surface.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
    }

    fun attach(displayId: Int, token: IBinder, surface: Surface?, callback: (String?) -> Unit) {
        val retained = surface?.takeIf { it.isValid }?.let { copySurface(it) }
        withService({ service ->
            executor.execute {
                val error = try {
                    service.attachTargetPortraitSurface(displayId, token, retained)
                    null
                } catch (t: Throwable) { t.message ?: t.javaClass.simpleName }
                finally { retained?.release() }
                mainHandler.post { callback(error) }
            }
        }, {
            retained?.release()
            mainHandler.post { callback("AndroidControl service unavailable") }
        })
    }

    fun back(displayId: Int, token: IBinder, callback: (String?) -> Unit) = action(callback) {
        it.sendTargetBack(displayId, token)
    }

    fun handoff(displayId: Int, token: IBinder, callback: (String?) -> Unit) = action(callback) {
        it.handoffTargetToPhone(displayId, token)
    }

    fun state(displayId: Int, token: IBinder, callback: (Int, String?) -> Unit) {
        withService({ service ->
            executor.execute {
                try {
                    val state = service.getTargetPortraitSessionState(displayId, token)
                    mainHandler.post { callback(state, null) }
                } catch (t: Throwable) {
                    mainHandler.post { callback(0, t.message ?: t.javaClass.simpleName) }
                }
            }
        }, { mainHandler.post { callback(0, "AndroidControl service unavailable") } })
    }

    private fun action(callback: (String?) -> Unit, operation: (IAndroidControlService) -> Unit) {
        withService({ service ->
            executor.execute {
                val error = try { operation(service); null } catch (t: Throwable) { t.message ?: t.javaClass.simpleName }
                mainHandler.post { callback(error) }
            }
        }, { mainHandler.post { callback("AndroidControl service unavailable") } })
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
        callback: ((String?) -> Unit)? = null
    ) {
        withService({ service ->
            executor.execute {
                var failure: String? = null
                try {
                    service.stopTargetPortraitDisplay(
                        displayId,
                        relaunchOnDefaultDisplay
                    )
                } catch (t: Throwable) {
                    failure = t.message ?: t.javaClass.simpleName
                } finally {
                    mainHandler.post { callback?.invoke(failure) }
                }
            }
        }, {
            mainHandler.post { callback?.invoke("AndroidControl unavailable; restoration will retry next launch") }
        })
    }
}
