package org.androidcontrol.app.legacy

import android.os.Bundle
import android.widget.Toast
import org.androidcontrol.app.app.AppActivity
import org.androidcontrol.app.shell.ShellBinderRequestHandler

class ShellRequestHandlerActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ShellBinderRequestHandler.handleRequest(this, intent)
        finish()
    }
}
