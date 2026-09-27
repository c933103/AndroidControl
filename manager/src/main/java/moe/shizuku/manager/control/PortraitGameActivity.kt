package moe.shizuku.manager.control

import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppActivity

class PortraitGameActivity : AppActivity(),
    SurfaceHolder.Callback,
    View.OnTouchListener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var displayId = Display.INVALID_DISPLAY
    private var creatingDisplay = false
    private var forceRequested = false

    private val stateListener: (OrientationControlClient.State) -> Unit = { state ->
        when {
            state.error != null -> {
                statusView.visibility = View.VISIBLE
                statusView.text = getString(
                    R.string.portrait_game_error,
                    state.error
                )
            }

            state.available && state.forcedPortrait == true -> {
                forceRequested = false
                maybeCreatePortraitDisplay()
            }

            state.available && !forceRequested -> {
                forceRequested = true
                statusView.visibility = View.VISIBLE
                statusView.setText(R.string.portrait_game_applying)
                OrientationControlClient.toggle()
            }

            else -> {
                statusView.visibility = View.VISIBLE
                statusView.setText(R.string.portrait_game_connecting)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        setContentView(R.layout.portrait_game_activity)
        surfaceView = findViewById(R.id.portrait_game_surface)
        statusView = findViewById(R.id.portrait_game_status)

        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener(this)
        surfaceView.isFocusableInTouchMode = true
    }

    override fun onStart() {
        super.onStart()
        OrientationControlClient.addListener(stateListener)
        OrientationControlClient.connect()
    }

    override fun onStop() {
        OrientationControlClient.removeListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        releasePortraitDisplay()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        statusView.visibility = View.VISIBLE
        statusView.setText(R.string.portrait_game_starting)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        surfaceWidth = width
        surfaceHeight = height
        maybeCreatePortraitDisplay()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceWidth = 0
        surfaceHeight = 0
        releasePortraitDisplay()
    }

    private fun maybeCreatePortraitDisplay() {
        if (creatingDisplay || displayId != Display.INVALID_DISPLAY) return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        if (!surfaceView.holder.surface.isValid) return

        val state = OrientationControlClient.state
        if (!state.available || state.forcedPortrait != true) return

        creatingDisplay = true
        statusView.visibility = View.VISIBLE
        statusView.setText(R.string.portrait_game_starting)

        val densityDpi = resources.displayMetrics.densityDpi

        OrientationControlClient.createAndLaunchPortraitVirtualDisplay(
            surfaceView.holder.surface,
            surfaceWidth,
            surfaceHeight,
            densityDpi
        ) { result ->
            creatingDisplay = false

            result.onSuccess { id ->
                if (!surfaceView.holder.surface.isValid) {
                    OrientationControlClient.releasePortraitVirtualDisplay()
                    return@onSuccess
                }

                displayId = id
                statusView.visibility = View.GONE
                surfaceView.requestFocus()
            }.onFailure { error ->
                displayId = Display.INVALID_DISPLAY
                statusView.visibility = View.VISIBLE
                statusView.text = getString(
                    R.string.portrait_game_error,
                    error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    private fun releasePortraitDisplay() {
        if (displayId != Display.INVALID_DISPLAY || creatingDisplay) {
            OrientationControlClient.releasePortraitVirtualDisplay()
        }

        displayId = Display.INVALID_DISPLAY
        creatingDisplay = false
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        val id = displayId
        if (id == Display.INVALID_DISPLAY) return true

        OrientationControlClient.injectPortraitMotionEvent(event, id)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val id = displayId

        if (
            id != Display.INVALID_DISPLAY &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            OrientationControlClient.injectPortraitKeyEvent(event, id)
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveMode()
        }
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
