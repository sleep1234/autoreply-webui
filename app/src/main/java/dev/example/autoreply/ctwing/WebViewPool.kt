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
 * 不可见 overlay 窗口，承载 CTWing H5 的 X5 WebView。
 *
 * 两种获取 WebView 的方式：
 *  1. [create] — 模块自己 new 一个 `com.tencent.xweb.pinus.sdk.WebView`，
 *     挂到透明 overlay，全程不依赖微信创建 MMWebViewUI，不影响微信使用。
 *     （首选，已验证可行）
 *  2. [steal]  — 从微信 MMWebViewUI 偷取现有 WebView（旧方案，兜底）。
 *
 * WebView 住在全屏 alpha=0 的 overlay 里，锁屏/Doze 不会冻结它，
 * X5 渲染器靠手动 onResume() 保持激活，evaluateJavascript 任何时候都能用。
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
    //  CREATE own X5 WebView (preferred)
    // ====================================================================

    /**
     * 自建一个 Pinus 内核的 X5 WebView，挂到透明 overlay，加载 CTWing H5。
     *
     * 关键：必须用 `com.tencent.xweb.pinus.sdk.WebView`（单参 Context 构造器）。
     * `com.tencent.xweb.WebView` 的裸构造会抛
     * "create webview instance failed … WV_KIND_NONE" 拒绝非 Pinus 初始化。
     */
    fun create(ctx: Context?, classLoader: ClassLoader?): Boolean {
        if (ready) return true
        if (ctx == null || classLoader == null) {
            XposedBridge.log("$TAG create: ctx=$ctx classLoader=$classLoader")
            return false
        }

        // 必须在主线程构造（X5/Pinus 需要 Looper）
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            val result = AtomicBoolean(false)
            mainHandler.post {
                try { result.set(createImpl(ctx, classLoader)) } finally { latch.countDown() }
            }
            latch.await(10, TimeUnit.SECONDS)
            return result.get()
        }
        return createImpl(ctx, classLoader)
    }

    private fun createImpl(ctx: Context, cl: ClassLoader): Boolean {
        try {
            val className = "com.tencent.xweb.pinus.sdk.WebView"
            val cls = cl.loadClass(className)
            // 优先 2-arg (Context, AttributeSet)，其次 1-arg (Context)
            val ctor = cls.constructors
                .sortedBy { it.parameterTypes.size }
                .firstOrNull { it.parameterTypes.size <= 2 }
            if (ctor == null) {
                XposedBridge.log("$TAG create: no usable ctor on $className")
                return false
            }
            val oauthUrl = "https://tywlonestop.ctwing.cn:8081/web-apps/wxLogin?ticket=test"
            ctor.isAccessible = true
            // 单参 (Context) ctor 优先；双参加 null AttributeSet 兜底
            val args = ctor.parameterTypes.map { p ->
                when {
                    p.name.contains("Context") -> ctx
                    else -> null
                }
            }.toTypedArray()
            val wv = ctor.newInstance(*args)
            val ctorSig = ctor.parameterTypes.joinToString(",") { it.simpleName ?: it.name }
            XposedBridge.log("$TAG create: ✅ $className created (ctor=$ctorSig)")

            // 挂到透明 overlay
            if (!windowCreated) ensureWindow(ctx)
            poolView?.addView(wv as View, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            XposedBridge.log("$TAG create: attached to overlay")

            onResume(wv)
            webView = wv
            ready = true

            // 关键时序：reflectInterface 委托由 View 的 onAttachedToWindow
            // 生命周期异步赋值。addView 后需要等渲染进程 binder 回调，
            // 委托才非 null。轮询探测 reflectInterface 就绪后再 loadUrl。
            mainHandler.postDelayed(object : Runnable {
                override fun run() {
                    val ready = try {
                        val f = wv.javaClass.declaredFields
                            .firstOrNull { it.type.name.contains("WebViewInterface") }
                        f?.isAccessible = true
                        val delegate = f?.get(wv)
                        XposedBridge.log("$TAG create: reflectInterface = ${if (delegate != null) "READY(${delegate.javaClass.simpleName})" else "null"}")
                        delegate != null
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG create: reflect check err ${e.message}")
                        false
                    }
                    if (ready) {
                        try {
                            cls.getMethod("loadUrl", String::class.java).invoke(wv, oauthUrl)
                            XposedBridge.log("$TAG create: loadUrl dispatched → $oauthUrl")
                        } catch (e: Exception) {
                            XposedBridge.log("$TAG create: loadUrl failed: ${e.message}")
                        }
                    } else {
                        // 直接尝试 loadUrl（即使委托 null，loadUrl 内部可能有 lazy init）
                        XposedBridge.log("$TAG create: reflect not ready yet, retrying in 300ms")
                        mainHandler.postDelayed(this, 300L)
                    }
                }
            }, 200L)
            return true
        } catch (e: Exception) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause
            val causeMsg = cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "no cause"
            XposedBridge.log("$TAG create: ❌ ${e.javaClass.simpleName} | cause=$causeMsg")
            return false
        }
    }

    // ====================================================================
    //  Window creation
    // ====================================================================

    /** Create the invisible full-screen overlay once. */
    fun ensureWindow(ctx: Context?): Boolean {
        if (windowCreated) return true
        if (ctx == null) return false

        // Must run on the main thread (WindowManager requires a Looper)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val latch = CountDownLatch(1)
            val result = AtomicBoolean(false)
            mainHandler.post {
                try { result.set(ensureWindowMain(ctx)); } finally { latch.countDown() }
            }
            latch.await(5, TimeUnit.SECONDS)
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
    //  Steal WebView from Activity (legacy fallback)
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

    /** Reload OAuth on the pool WebView to refresh token. */
    fun refresh(timeoutMs: Long = 45_000L): Boolean {
        val wv = webView ?: return false
        XposedBridge.log("$TAG refresh: onResume → loadUrl(OAuth) → wait for token")
        onResume(wv)
        return true
    }
}
