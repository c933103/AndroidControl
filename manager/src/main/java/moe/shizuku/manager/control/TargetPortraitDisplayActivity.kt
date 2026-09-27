package moe.shizuku.manager.control

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import moe.shizuku.manager.R

class TargetPortraitDisplayActivity : Activity(), SurfaceHolder.Callback, View.OnTouchListener {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView

    private var virtualDisplay: VirtualDisplay? = null
    private var displayId: Int = -1
    private var virtualWidth: Int = 0
    private var virtualHeight: Int = 0
    private var stopping = false
    private var launched = false
    private var startAttempted = false
    private val hostToken = Binder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 16, 24, 16)
            text = getString(R.string.target_portrait_display_starting)
        }

        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = 24
        }
        root.addView(statusView, statusParams)

        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val existing = virtualDisplay
        if (existing != null) {
            existing.surface = holder.surface
            return
        }

        startWhenSized(holder, surfaceView.width, surfaceView.height)
    }

    private fun startWhenSized(holder: SurfaceHolder, width: Int, height: Int) {
        if (stopping || isDestroyed || startAttempted) return
        if (width <= 0 || height <= width) {
            statusView.text = getString(R.string.target_portrait_display_bad_size)
            return
        }

        startAttempted = true
        virtualWidth = width
        virtualHeight = height

        val displayManager =
            getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        val flags =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        virtualDisplay = try { displayManager.createVirtualDisplay(
            "AndroidControl-HololiveDreams-Portrait",
            virtualWidth,
            virtualHeight,
            resources.displayMetrics.densityDpi,
            holder.surface,
            flags
        ) } catch (t: Throwable) {
            statusView.text = "Could not create portrait display: ${t.message}"
            return
        }

        val display = virtualDisplay?.display
        if (display == null) {
            statusView.text =
                getString(R.string.target_portrait_display_create_failed)
            return
        }

        displayId = display.displayId
        statusView.text = getString(
            R.string.target_portrait_display_launching,
            virtualWidth,
            virtualHeight,
            displayId
        )

        TargetPortraitDisplayClient.start(
            displayId,
            virtualWidth,
            virtualHeight,
            hostToken
        ) { ok, status ->
            if (stopping || isDestroyed) return@start
            launched = ok
            statusView.text =
                status ?: if (ok) {
                    getString(R.string.target_portrait_display_running)
                } else {
                    getString(R.string.target_portrait_display_launch_failed)
                }

            if (!ok) {
                virtualDisplay?.release()
                virtualDisplay = null
                displayId = -1
            }
            if (ok) {
                statusView.postDelayed(
                    { statusView.visibility = View.GONE },
                    1500
                )
            }
        }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        if (virtualDisplay == null) startWhenSized(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        virtualDisplay?.surface = null
    }

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        if (!launched || displayId < 0) return true

        val viewWidth = surfaceView.width.toFloat()
        val viewHeight = surfaceView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return true

        val copy = MotionEvent.obtain(event)
        if (
            virtualWidth > 0 &&
            virtualHeight > 0 &&
            (
                virtualWidth.toFloat() != viewWidth ||
                    virtualHeight.toFloat() != viewHeight
                )
        ) {
            val matrix = Matrix()
            matrix.setScale(
                virtualWidth / viewWidth,
                virtualHeight / viewHeight
            )
            copy.transform(matrix)
        }

        TargetPortraitDisplayClient.inject(displayId, copy)
        copy.recycle()
        return true
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        stopAndFinish()
    }

    private fun stopAndFinish() {
        if (stopping) return
        stopping = true
        launched = false
        statusView.visibility = View.VISIBLE
        statusView.text = getString(R.string.target_portrait_display_restoring)

        val id = displayId
        if (id >= 0) {
            TargetPortraitDisplayClient.stop(
                id,
                relaunchOnDefaultDisplay = true
            ) { failure ->
                if (failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                virtualDisplay?.release()
                virtualDisplay = null
                finish()
            }
        } else {
            virtualDisplay?.release()
            virtualDisplay = null
            finish()
        }
    }

    override fun onDestroy() {
        launched = false
        val retiringDisplay = virtualDisplay
        virtualDisplay = null
        if (!stopping && displayId >= 0) {
            // Keep the display alive until the service has found and removed its task.
            // Releasing it first can migrate the task to display 0 during launch.
            retiringDisplay?.surface = null
            TargetPortraitDisplayClient.stop(displayId, relaunchOnDefaultDisplay = false) {
                retiringDisplay?.release()
            }
        } else {
            retiringDisplay?.release()
        }
        super.onDestroy()
    }
}
