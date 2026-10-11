package org.androidcontrol.app.recording

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Binder
import android.content.Context
import org.androidcontrol.app.BuildConfig
import org.androidcontrol.app.control.AndroidControlService
import org.androidcontrol.app.control.IAndroidControlService
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Connects to the *existing* AndroidControl privileged user service, not an
 * additional Shizuku provider or separately granted application.
 * Must be called on a worker thread; never block the UI thread waiting on Binder.
 */
internal class CallRecordingBridge private constructor(
    val remote: IAndroidControlService,
    private val args: Shizuku.UserServiceArgs,
    private val connection: ServiceConnection
) : Closeable {
    val ownerToken: IBinder = Binder()

    override fun close() {
        // remove=false detaches this client but does not destroy the shared
        // user service used by orientation and other AndroidControl features.
        runCatching { Shizuku.unbindUserService(args, connection, false) }
    }

    companion object {
        fun connect(): CallRecordingBridge {
            check(Shizuku.pingBinder()) { "Start AndroidControl's Shizuku service first" }
            val args = Shizuku.UserServiceArgs(
                ComponentName(BuildConfig.APPLICATION_ID, AndroidControlService::class.java.name)
            ).daemon(true).processNameSuffix("android_control")
                .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)

            val latch = CountDownLatch(1)
            val binderRef = AtomicReference<IAndroidControlService?>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                    if (binder != null && binder.pingBinder()) {
                        binderRef.set(IAndroidControlService.Stub.asInterface(binder))
                    }
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    binderRef.set(null)
                    latch.countDown()
                }
            }
            Shizuku.bindUserService(args, connection)
            try {
                check(latch.await(12, TimeUnit.SECONDS)) { "Timed out connecting to privileged recorder" }
                val remote = binderRef.get() ?: error("Privileged recorder unavailable")
                check(remote.asBinder().pingBinder()) { "Recorder binder is no longer alive" }
                return CallRecordingBridge(remote, args, connection)
            } catch (e: Exception) {
                runCatching { Shizuku.unbindUserService(args, connection, false) }
                throw e
            }
        }
    }
}
