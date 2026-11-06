package org.androidcontrol.app

import android.os.Bundle
import androidx.core.os.bundleOf
import moe.shizuku.api.BinderContainer
import org.androidcontrol.app.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuManagerProvider : ShizukuProvider() {

    companion object {
        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
    }

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null

        return if (method == METHOD_SEND_USER_SERVICE) {
            try {
                extras.classLoader = BinderContainer::class.java.classLoader

                val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null

                val countDownLatch = CountDownLatch(1)
                val pending = AtomicBoolean(true)
                var reply: Bundle? = Bundle()

                val listener = object : Shizuku.OnBinderReceivedListener {

                    override fun onBinderReceived() {
                        // Removal cannot cancel an already queued callback. A timed-out
                        // or duplicate delivery must not start a new attachment.
                        if (!pending.compareAndSet(true, false)) return
                        try {
                            Shizuku.attachUserService(binder, bundleOf(
                                USER_SERVICE_ARG_TOKEN to token
                            ))
                            reply!!.putParcelable(EXTRA_BINDER, BinderContainer(Shizuku.getBinder()))
                        } catch (e: Throwable) {
                            LOGGER.e(e, "attachUserService $token")
                            reply = null
                        } finally {
                            countDownLatch.countDown()
                        }
                    }
                }

                return try {
                    Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)
                    if (countDownLatch.await(5, TimeUnit.SECONDS)) reply else null
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    null
                } finally {
                    // A callback already attaching may finish after the deadline, but
                    // it cannot turn this call's null timeout result into success.
                    pending.set(false)
                    Shizuku.removeBinderReceivedListener(listener)
                }
            } catch (e: Throwable) {
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            super.call(method, arg, extras)
        }
    }
}

