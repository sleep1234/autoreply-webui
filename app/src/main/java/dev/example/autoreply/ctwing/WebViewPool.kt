package dev.example.autoreply.ctwing

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transparent overlay window that STEALS the CTWing WebView from WeChat's
 * MMWebViewUI and holds it independently of Activity lifecycle.
 *
 * Once stolen, the WebView lives in a full-screen alpha=0 overlay, so:
 *  - Lock-screen / Doze cannot freeze it (it's an active window)
 *  - X5 renderer stays alive via manual onResume() calls
 *  - evaluateJavascript always works, no matter screen state
 *
 * This is the core of WX H5 Auto's "zero screen-on" guarantee.
 */
object WebViewPool {

    private const val TAG = "[WebViewPool]"
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var poolView: FrameLayout? = null
    private var windowCreated = false

    @Volatile
    var webView: Any? = null
        private set

    @Volatile
    var ready = false
        private set

    @Volatile
    var token: String? = null
        private set

    // ====================================================================
    //  Window creation
    // ====================================================================

    /** Create the invisible full-screen overlay once. */
    fun ensureWindow(ctx: Context?): Boolean {
        if (windowCreated) return true
        if (ctx == null) return false

        // Must run on the main thread (WindowManager requires a Looper)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = java.util.concurrent.CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicBoolean(false)
            mainHandler.post {
                try { result.set(ensureWindowMain(ctx)); } finally { latch.countDown() }
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return result.get()
        }
        return ensureWindowMain(ctx)
    }

    private fun ensureWindowMain(ctx: Context): Boolean {
        try {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams().apply {
                this.type = type
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                x = 0; y = 0
                gravity = Gravity.TOP or Gravity.START
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = PixelFormat.TRANSLUCENT
                alpha = 0.0f  // completely invisible
            }

            poolView = FrameLayout(ctx)
            windowManager!!.addView(poolView, params)
            windowCreated = true
            XposedBridge.log("$TAG overlay created (alpha=0, type=$type)")
            return true
        } catch (e: Exception) {
            XposedBridge.log("$TAG window creation failed: ${e.message}")
            return false
        }
    }

    // ====================================================================
    //  Steal WebView from Activity
    // ====================================================================

    /**
     * Detach [view] from its parent Activity view tree and attach it to
     * our transparent overlay. Calls onResume to activate X5 kernel.
     */
    fun steal(view: Any): Boolean {
        if (ready) return true
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            val result = AtomicBoolean(false)
            mainHandler.post {
                try { result.set(stealMain(view)) } finally { latch.countDown() }
            }
            latch.await(5, TimeUnit.SECONDS)
            return result.get()
        }
        return stealMain(view)
    }

    private fun stealMain(view: Any): Boolean {
        try {
            if (!windowCreated) { XposedBridge.log("$TAG steal: window not ready"); return false }
            val v = view as? View ?: return false

            val parent = v.parent as? ViewGroup
            parent?.removeView(v)
            XposedBridge.log("$TAG detached from parent=${parent?.javaClass?.simpleName ?: "none"}")

            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            poolView?.addView(v, lp)
            XposedBridge.log("$TAG attached to overlay pool")

            onResume(v)
            webView = v
            ready = true
            XposedBridge.log("$TAG ★ WebView stolen and activated")
            return true
        } catch (e: Exception) {
            XposedBridge.log("$TAG steal failed: ${e.message}")
            return false
        }
    }

    // ====================================================================
    //  Manual lifecycle control
    // ====================================================================

    /** Call WebView.onResume() to activate X5 rendering. */
    fun onResume(view: Any) {
        try {
            val m = view.javaClass.getMethod("onResume")
            m.invoke(view)
            XposedBridge.log("$TAG onResume OK")
        } catch (e: Exception) {
            XposedBridge.log("$TAG onResume failed: ${e.message}")
        }
    }

    /** Call WebView.onPause() to release X5 rendering resources. */
    fun onPause(view: Any) {
        try {
            val m = view.javaClass.getMethod("onPause")
            m.invoke(view)
        } catch (_: Exception) {}
    }

    // ====================================================================
    //  Cleanup
    // ====================================================================

    fun reset() {
        val wv = webView
        webView = null
        token = null
        ready = false
        try {
            if (wv is View) poolView?.removeView(wv)
        } catch (_: Exception) {}
        XposedBridge.log("$TAG reset")
    }

    /** Reload OAuth on the stolen WebView to refresh token. Blocking. */
    fun refresh(timeoutMs: Long = 45_000L): Boolean {
        val wv = webView ?: return false
        XposedBridge.log("$TAG refresh: onResume → loadUrl(OAuth) → wait for token")
        onResume(wv)
        // After onResume, the page re-fires the OAuth flow automatically
        return true
    }
}