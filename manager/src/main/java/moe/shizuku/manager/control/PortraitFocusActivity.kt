package moe.shizuku.manager.control

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/** A transparent, separate task that activates a display without relaunching its app. */
class PortraitFocusActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val finishBridge = Runnable { finishAndRemoveTask() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        // A failed focus transfer must not leave an invisible activity on top.
        main.postDelayed(finishBridge, 1500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) main.post(finishBridge)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
