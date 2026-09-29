package moe.shizuku.manager.control

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import moe.shizuku.manager.R

class TargetPortraitDisplayActivity : Activity(), SurfaceHolder.Callback, View.OnTouchListener {
    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private var displayId = -1
    private var virtualWidth = 0
    private var virtualHeight = 0
    private var stopping = false
    private var launched = false
    private var startAttempted = false
    private var foreground = false
    private var surfaceReady = false
    private var attachGeneration = 0
    private val main = Handler(Looper.getMainLooper())
    private val hostToken = Binder()
    private val packageNameForSession by lazy {
        PortraitTarget.validate(intent.getStringExtra("portrait_target_package") ?: PortraitTarget.get())
    }
    private val poll = Runnable { pollSession() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        // System navigation and the explicit controls remain outside the rendered
        // app. A lost surface must never strand the user behind a black screen.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val scene = FrameLayout(this)
        root.addView(scene, LinearLayout.LayoutParams(-1, 0, 1f))
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@TargetPortraitDisplayActivity)
            setOnTouchListener(this@TargetPortraitDisplayActivity)
        }
        scene.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 16, 24, 16)
            text = getString(R.string.target_portrait_display_starting)
        }
        scene.addView(statusView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val controls = LinearLayout(this).apply { setBackgroundColor(0xff173333.toInt()) }
        fun button(label: Int, action: () -> Unit) {
            controls.addView(Button(this).apply {
                text = getString(label)
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        button(R.string.target_portrait_back) { onBackPressed() }
        button(R.string.target_portrait_phone) { handoffToPhone() }
        button(R.string.target_portrait_close) { stopAndFinish() }
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        attachSurface()
    }

    override fun onPause() {
        foreground = false
        surfaceReady = false
        super.onPause()
    }

    override fun onStop() {
        detachSurface()
        super.onStop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (displayId >= 0) attachSurface()
        else startWhenSized(holder, surfaceView.width, surfaceView.height)
    }

    private fun startWhenSized(holder: SurfaceHolder, width: Int, height: Int) {
        if (stopping || isDestroyed || startAttempted || !holder.surface.isValid) return
        if (width <= 0 || height <= width) {
            statusView.text = getString(R.string.target_portrait_display_bad_size)
            return
        }
        startAttempted = true
        virtualWidth = width
        virtualHeight = height
        TargetPortraitDisplayClient.start(holder.surface, width, height, resources.displayMetrics.densityDpi,
            hostToken, packageNameForSession, taskId) { id, status ->
            displayId = id
            if (stopping || isDestroyed) {
                if (id >= 0) TargetPortraitDisplayClient.stop(id, false)
                return@start
            }
            launched = id >= 0
            if (launched) {
                attachSurface()
                main.post(poll)
            } else {
                statusView.text = status ?: getString(R.string.target_portrait_display_launch_failed)
            }
        }
    }

    private fun attachSurface() {
        if (displayId < 0 || stopping || !::surfaceView.isInitialized) return
        val surface = surfaceView.holder.surface.takeIf { foreground && it.isValid }
        val generation = ++attachGeneration
        surfaceReady = false
        TargetPortraitDisplayClient.attach(displayId, hostToken, surface) { error ->
            if (generation != attachGeneration || stopping || isDestroyed) return@attach
            surfaceReady = error == null && surface != null && surfaceView.holder.surface.isValid && foreground
            if (error != null) showFailure(error)
            else if (surfaceReady) statusView.visibility = View.GONE
        }
    }

    private fun detachSurface() {
        ++attachGeneration
        surfaceReady = false
        if (displayId >= 0 && !stopping) TargetPortraitDisplayClient.attach(displayId, hostToken, null) {}
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (displayId < 0) startWhenSized(holder, width, height) else attachSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = detachSurface()

    override fun onTouch(v: View?, event: MotionEvent): Boolean {
        if (!launched || !surfaceReady || !foreground || displayId < 0 || !surfaceView.holder.surface.isValid) return true
        val width = surfaceView.width.toFloat()
        val height = surfaceView.height.toFloat()
        if (width <= 0f || height <= 0f) return true
        val copy = MotionEvent.obtain(event)
        val matrix = Matrix().apply { setScale(virtualWidth / width, virtualHeight / height) }
        copy.transform(matrix)
        TargetPortraitDisplayClient.inject(displayId, copy)
        copy.recycle()
        return true
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (launched && surfaceReady) {
            TargetPortraitDisplayClient.back(displayId, hostToken) { error -> if (error != null) showFailure(error) }
        } else stopAndFinish()
    }

    private fun handoffToPhone() {
        if (!launched || stopping) return
        TargetPortraitDisplayClient.handoff(displayId, hostToken) { error ->
            if (error != null) showFailure(error) else finishPreservedSession()
        }
    }

    private fun pollSession() {
        if (stopping || isDestroyed || displayId < 0) return
        TargetPortraitDisplayClient.state(displayId, hostToken) { state, error ->
            if (stopping || isDestroyed) return@state
            if (state == 2) finishPreservedSession()
            else if (state == 0) {
                launched = false
                surfaceReady = false
                showFailure(error ?: getString(R.string.target_portrait_display_ended))
            } else main.postDelayed(poll, 750)
        }
    }

    private fun showFailure(message: String) {
        if (isDestroyed || stopping) return
        statusView.text = message
        statusView.visibility = View.VISIBLE
    }

    private fun finishPreservedSession() {
        stopping = true
        launched = false
        surfaceReady = false
        main.removeCallbacks(poll)
        finish()
    }

    private fun stopAndFinish() {
        if (stopping) return
        stopping = true
        launched = false
        surfaceReady = false
        main.removeCallbacks(poll)
        // Leaving the host cannot depend on a long Binder operation. The daemon
        // owns the display and finishes restoration independently after we leave.
        if (displayId >= 0) TargetPortraitDisplayClient.stop(displayId, true) { error ->
            if (error != null) Toast.makeText(applicationContext, error, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        launched = false
        surfaceReady = false
        main.removeCallbacksAndMessages(null)
        if (!stopping && displayId >= 0) TargetPortraitDisplayClient.stop(displayId, false)
        super.onDestroy()
    }
}
