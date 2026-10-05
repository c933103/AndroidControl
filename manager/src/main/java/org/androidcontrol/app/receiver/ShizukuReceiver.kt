package org.androidcontrol.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.androidcontrol.app.shell.ShellBinderRequestHandler

class ShizukuReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if ("rikka.shizuku.intent.action.REQUEST_BINDER" == intent.action) {
            ShellBinderRequestHandler.handleRequest(context, intent)
        }
    }
}
