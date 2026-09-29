package moe.shizuku.manager.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Message
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import moe.shizuku.manager.R

/** Lives in the portrait host; closing a page never recreates the target app. */
class PortraitWebPanel(context: Context, private val returnToApp: () -> Unit) : LinearLayout(context) {
    private val pages = mutableListOf<WebView>()
    private val frame = FrameLayout(context)
    private val address = TextView(context).apply {
        setTextColor(Color.WHITE)
        setPadding(16, 8, 16, 8)
        setTextIsSelectable(true)
        maxLines = 2
    }
    private val message = TextView(context).apply {
        setTextColor(Color.WHITE)
        setPadding(16, 8, 16, 8)
        visibility = GONE
    }
    private var lastUrl: String? = null
    private var resumed = true

    init {
        orientation = VERTICAL
        setBackgroundColor(0xff173333.toInt())
        val controls = LinearLayout(context)
        controls.addView(Button(context).apply {
            text = context.getString(R.string.target_web_return)
            isAllCaps = false
            setOnClickListener { returnToApp() }
        }, LayoutParams(0, -2, 1f))
        controls.addView(Button(context).apply {
            text = context.getString(R.string.target_web_reload)
            isAllCaps = false
            setOnClickListener {
                message.visibility = GONE
                if (pages.isEmpty()) lastUrl?.let { open(it) } else pages.last().reload()
            }
        }, LayoutParams(-2, -2))
        addView(controls)
        addView(address, LayoutParams(-1, -2))
        addView(message, LayoutParams(-1, -2))
        addView(frame, LayoutParams(-1, 0, 1f))
    }

    fun open(url: String) {
        require(PortraitWebLaunch.isWebUrl(url)) { "Invalid web link" }
        lastUrl = url
        address.text = url
        createPage().loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPage(): WebView {
        val page = WebView(context)
        page.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
        }
        page.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (PortraitWebLaunch.isWebUrl(request.url.toString())) return false
                // Keep browser redirects from silently launching apps or ending
                // the portrait session. No intent://, file:// or JS bridge escape.
                if (request.isForMainFrame) {
                    message.setText(R.string.target_web_unsupported_link)
                    message.visibility = VISIBLE
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (pages.lastOrNull() === view) address.text = url
                if (PortraitWebLaunch.isWebUrl(url)) lastUrl = url
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Android calls this for every WebView sharing the dead renderer.
                // Remove each affected instance without touching the game's display.
                pages.remove(view)
                frame.removeView(view)
                view.destroy()
                pages.lastOrNull()?.visibility = VISIBLE
                message.setText(R.string.target_web_renderer_stopped)
                message.visibility = VISIBLE
                return true
            }
        }
        page.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean {
                if (!gesture || pages.size >= 8) return false
                (result.obj as WebView.WebViewTransport).webView = createPage()
                result.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) {
                if (pages.size == 1) returnToApp() else removePage(window)
            }
        }
        pages.lastOrNull()?.apply { visibility = GONE; onPause() }
        pages.add(page)
        frame.addView(page, FrameLayout.LayoutParams(-1, -1))
        if (!resumed) page.onPause()
        page.requestFocus()
        return page
    }

    fun back() {
        val page = pages.lastOrNull()
        when {
            page == null -> returnToApp()
            page.canGoBack() -> page.goBack()
            pages.size > 1 -> removePage(page)
            else -> returnToApp()
        }
    }

    private fun removePage(page: WebView) {
        pages.remove(page)
        frame.removeView(page)
        page.destroy()
        pages.lastOrNull()?.apply {
            visibility = VISIBLE
            if (resumed) onResume()
            address.text = url
            requestFocus()
        }
    }

    fun resume() { resumed = true; pages.lastOrNull()?.onResume() }
    fun pause() { resumed = false; pages.lastOrNull()?.onPause() }

    fun destroy() {
        CookieManager.getInstance().flush()
        pages.toList().forEach { frame.removeView(it); it.destroy() }
        pages.clear()
    }
}
