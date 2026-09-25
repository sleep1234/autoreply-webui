package dev.example.autoreply.ctwing

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * Detects the CTWing H5 WebView inside WeChat and injects the JS bridge.
 *
 * WeChat hosts H5 pages in `com.tencent.xweb.WebView` (X5). We hook
 * `loadUrl` (String and String+Map overloads) in `afterHookedMethod`
 * so the page has actually started loading when we inject.
 *
 * Injection is deferred to the UI thread because `evaluateJavascript`
 * must be called on the thread that owns the WebView.
 */
object CtwingWebViewHook {

    private const val TAG = "[CTWing-WebView]"
    private const val CTWING_URL_MARKER = "tywlonestop.ctwing.cn"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The WebView instance (any type) currently holding the CTWing page. */
    @Volatile
    private var targetView: Any? = null

    /** Prevent re-injection within the same page lifetime. */
    @Volatile
    private var injected = false

    fun hook(classLoader: ClassLoader) {
        // ---- X5 WebView (com.tencent.xweb) — the one WeChat actually uses ----
        // loadUrl(String)
        runCatching {
            val x5 = XposedHelpers.findClass("com.tencent.xweb.WebView", classLoader)
            XposedHelpers.findAndHookMethod(x5, "loadUrl", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as? String ?: return
                        checkUrl(param.thisObject, url)
                    }
                })
            log("hooked com.tencent.xweb.WebView.loadUrl(String)")
        }.onFailure { log("X5 loadUrl(String) failed: ${it.message}") }

        // loadUrl(String, Map<String,String>) — additional HTTP headers variant
        runCatching {
            val x5 = XposedHelpers.findClass("com.tencent.xweb.WebView", classLoader)
            XposedHelpers.findAndHookMethod(x5, "loadUrl",
                String::class.java, MutableMap::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as? String ?: return
                        checkUrl(param.thisObject, url)
                    }
                })
            log("hooked com.tencent.xweb.WebView.loadUrl(String,Map)")
        }.onFailure { log("X5 loadUrl(Map) failed: ${it.message}") }

        // ---- System WebView fallback (rarely used by WeChat, but keep) ----
        runCatching {
            val wv = XposedHelpers.findClass("android.webkit.WebView", classLoader)
            XposedHelpers.findAndHookMethod(wv, "loadUrl", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as? String ?: return
                        checkUrl(param.thisObject, url)
                    }
                })
            log("hooked android.webkit.WebView.loadUrl (fallback)")
        }.onFailure { log("system WebView failed: ${it.message}") }

        // ---- onPageFinished: catch URL regardless of load method ----
        hookWebViewClientCallbacks(classLoader)

        // ---- MMWebViewUI.onResume: find WebView from foreground Activity ----
        hookActivityResumeForInjection(classLoader)
    }

    private fun checkUrl(webView: Any, url: String) {
        if (!url.contains(CTWING_URL_MARKER)) return
        if (injected) return  // already injected this page
        log("CTWing URL detected: ${url.take(120)}")
        injected = true
        inject(webView)
    }

    /**
     * Inject JS bridge via reflection. All operations are posted to the UI
     * thread's message queue because `evaluateJavascript` must be called on
     * the WebView's owning thread.
     */
    private fun inject(webView: Any) {
        targetView = webView
        val bridge = CtwingJsBridge()

        mainHandler.post {
            try {
                val settings = webView.javaClass.getMethod("getSettings").invoke(webView)
                settings.javaClass
                    .getMethod("setJavaScriptEnabled", Boolean::class.javaPrimitiveType!!)
                    .invoke(settings, true)
            } catch (e: Exception) {
                log("setJavaScriptEnabled failed: ${e.message}")
            }

            try {
                webView.javaClass
                    .getMethod("addJavascriptInterface", Any::class.java, String::class.java)
                    .invoke(webView, bridge, CtwingJsInjector.BRIDGE_NAME)
                log("addJavascriptInterface OK")
            } catch (e: Exception) {
                log("addJavascriptInterface failed: ${e.message}")
            }

            // Inject JS — must be on UI thread
            evaluateJsOnUI(webView, CtwingJsInjector.build())

            // Start IPC poller in background thread so the main process
            // can send requests to this tools-process WebView.
            startIpcPoller()
        }
    }

    /**
     * Evaluate JS on the main thread. Tries multiple ValueCallback types
     * because X5's `evaluateJavascript(String, ValueCallback)` may use
     * `com.tencent.xweb.ValueCallback` instead of `android.webkit.ValueCallback`.
     *
     * Strategy: find the method by name + param count (2), pass null as callback.
     * Passing null is safe — both system and X5 accept null ValueCallback.
     */
    private fun evaluateJsOnUI(webView: Any, script: String) {
        try {
            // Find the evaluateJavascript method with exactly 2 parameters
            val methods = webView.javaClass.methods.filter {
                it.name == "evaluateJavascript" && it.parameterCount == 2
            }
            val method: Method = methods.firstOrNull() ?: run {
                // Fallback: try with android.webkit.ValueCallback (most common)
                webView.javaClass.getMethod(
                    "evaluateJavascript",
                    String::class.java,
                    android.webkit.ValueCallback::class.java
                )
            }
            method.invoke(webView, script, null)
        } catch (e: Exception) {
            log("evaluateJavascript failed: ${e.message}")
        }
    }

    /**
     * Public API for [CtwingFacade] — evaluate JS on the main thread.
     * Works on both system and X5 WebView.
     */
    fun evaluateJs(webView: Any, script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluateJsOnUI(webView, script)
        } else {
            mainHandler.post { evaluateJsOnUI(webView, script) }
        }
    }

    fun currentWebView(): Any? = targetView

    fun isInjected(): Boolean = targetView != null

    /**
     * Evaluate JS and BLOCK for the string result. Uses evaluateJavascript's
     * ValueCallback (both android.webkit.ValueCallback and X5 ValueCallback
     * are handled via reflection + a proxy). This is the reliable channel —
     * addJavascriptInterface does NOT work on already-loaded pages, so we
     * read results through the callback instead.
     */
    fun evaluateJsForResult(webView: Any, script: String, timeoutMs: Long = 5000): String? {
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = java.util.concurrent.atomic.AtomicReference<String?>()

        mainHandler.post {
            try {
                // Try all evaluteJavascript(String, Xxx) overloads on this
                // WebView (system uses android.webkit.ValueCallback<String>,
                // X5 may use its own type).  Create a proxy for the first
                // compatible callback type we find.
                val candidates = webView.javaClass.methods
                    .filter { it.name == "evaluateJavascript" && it.parameterCount == 2 }
                var ok = false
                for (m in candidates) {
                    val cbType = m.parameterTypes[1]
                    if (cbType == null) continue
                    try {
                        val cb = java.lang.reflect.Proxy.newProxyInstance(
                            cbType.classLoader,
                            arrayOf(cbType),
                            java.lang.reflect.InvocationHandler { _, _, args ->
                                val raw = args?.getOrNull(0)?.toString() ?: "null"
                                holder.set(raw)
                                latch.countDown()
                                null
                            }
                        )
                        m.invoke(webView, script, cb)
                        ok = true
                        break
                    } catch (_: Exception) { /* try next overload */ }
                }
                if (!ok) {
                    // Last resort: evaluate with null callback (fire-and-forget).
                    try {
                        val m2 = webView.javaClass.methods.first {
                            it.name == "evaluateJavascript" && it.parameterCount == 2
                        }
                        m2.invoke(webView, script, null)
                    } catch (_: Exception) {}
                    holder.set(null)
                    latch.countDown()
                }
            } catch (e: Exception) {
                log("evaluateJsForResult err: ${e.message}")
                holder.set(null)
                latch.countDown()
            }
        }

        val ok = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) log("evaluateJsForResult TIMEOUT after ${timeoutMs}ms")
        val result = holder.get()
        if (result != null) log("evaluateJsForResult OK: ${result.take(200)}")
        return result
    }

    /**
     * Reset injection state when the WebView navigates away from CTWing.
     */
    fun onLeaveCtwing() {
        injected = false
    }

    /**
     * Start the cross-process IPC poller. Runs on a background thread in the
     * tools process: polls [CtwingIpcBridge] for requests from the main
     * process, evaluates the JS, and lets the bridge's onIpcResult/onIpcError
     * write back the response file (no cross-process memory needed).
     */
    fun startIpcPoller() {
        val webView = targetView ?: return
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            log("IPC poller started")
            while (true) {
                try {
                    val req = CtwingIpcBridge.readRequest()
                    if (req != null) {
                        val obj = org.json.JSONObject(req)
                        val script = obj.optString("script","")
                        val rid = obj.optString("rid","")
                        if (script.isNotEmpty()) {
                            CtwingIpcBridge.clearRequest()
                            log("IPC: executing rid=$rid")
                            mainHandler.post {
                                evaluateJsOnUI(webView, """
(function(){try{var r=$script;if(r&&typeof r.then==='function'){r.then(function(v){window.${CtwingJsInjector.BRIDGE_NAME}.onIpcResult('$rid',typeof v==='string'?v:JSON.stringify(v));}).catch(function(e){window.${CtwingJsInjector.BRIDGE_NAME}.onIpcError('$rid',String(e.message||e));});}else{window.${CtwingJsInjector.BRIDGE_NAME}.onIpcResult('$rid',typeof r==='string'?r:JSON.stringify(r||{}));}}catch(e){window.${CtwingJsInjector.BRIDGE_NAME}.onIpcError('$rid',String(e.message||e));}})();
""".trimIndent().replace("\n"," "))
                            }
                        }
                    }
                    Thread.sleep(500)
                } catch (e: Exception) {
                    log("IPC poller err: ${e.message}")
                    Thread.sleep(2000)
                }
            }
        }, "ctwing-ipc").start()
    }

    private fun log(msg: String) = XposedBridge.log("$TAG $msg")

    // ----------------------------------------------------------------
    //  onPageFinished-style hooks: catch the URL from WebViewClient callbacks
    // ----------------------------------------------------------------

    private fun hookWebViewClientCallbacks(classLoader: ClassLoader) {
        // Hook the abstract onPageFinished / onPageStarted on any WebViewClient subclass.
        // WeChat uses com.tencent.xweb.WebView (X5) with X5 WebViewClient.
        // We hook onPageFinished (most common callback) on WebView itself —
        // but WebViewClient is set via setWebViewClient, so hook that setter.
        runCatching {
            val xwv = XposedHelpers.findClass("com.tencent.xweb.WebView", classLoader)
            XposedHelpers.findAndHookMethod(xwv, "setWebViewClient",
                Class.forName("com.tencent.xweb.WebViewClient", false, classLoader),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val wv = param.thisObject
                        val url = try {
                            wv.javaClass.getMethod("getUrl").invoke(wv) as? String
                        } catch (e: Exception) { null }
                        if (url != null && url.contains(CTWING_URL_MARKER)) {
                            log("WebViewClient set: URL=$url")
                            checkUrl(wv, url)
                        }
                    }
                })
            log("hooked setWebViewClient")
        }.onFailure { log("setWebViewClient hook failed: ${it.message}") }
    }

    // ----------------------------------------------------------------
    //  Activity.onResume hook: find WebView in MMWebViewUI's view tree
    // ----------------------------------------------------------------

    @Volatile
    private var uiTreeScanDone = false

    private fun hookActivityResumeForInjection(classLoader: ClassLoader) {
        // Hook MMWebViewUI.onResume — every time the H5 page comes to
        // foreground we scan its view tree for a WebView holding a CTWing URL.
        val actClassNames = listOf(
            "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI",
        )
        for (cname in actClassNames) {
            runCatching {
                val cls = XposedHelpers.findClass(cname, classLoader)
                XposedHelpers.findAndHookMethod(cls, "onResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (uiTreeScanDone) return
                            val activity = param.thisObject as? android.app.Activity ?: return
                            uiTreeScanDone = true
                            mainHandler.postDelayed({
                                scanActivityViewTree(activity)
                            }, 1500L) // wait for WebView to load
                        }
                    })
                log("hooked $cname.onResume")
            }.onFailure { /* not found in this process */ }
        }

        // Also hook MMWebViewUI.onCreate — the URL may be available early.
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.tencent.mm.plugin.webview.ui.tools.WebViewUI", classLoader)
            XposedHelpers.findAndHookMethod(cls, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (uiTreeScanDone) return
                        val activity = param.thisObject as? android.app.Activity ?: return
                        uiTreeScanDone = true
                        mainHandler.postDelayed({
                            scanActivityViewTree(activity)
                        }, 2000L)
                    }
                })
            log("hooked WebViewUI.onResume")
        }.onFailure { /* not the right process */ }
    }

    private fun scanActivityViewTree(activity: android.app.Activity) {
        try {
            val root = activity.window?.decorView ?: return
            val webViews = mutableListOf<Any>()
            collectWebViews(root, webViews)
            log("view tree scan: found ${webViews.size} WebView(s) in $activity")
            for (wv in webViews) {
                val url = try {
                    wv.javaClass.getMethod("getUrl").invoke(wv) as? String ?: ""
                } catch (e: Exception) { "" }
                log("  WebView URL: ${url.take(200)}")
                if (url.contains(CTWING_URL_MARKER) && !injected) {
                    log("CTWing WebView found via view tree scan!")
                    checkUrl(wv, url)
                }
            }
        } catch (e: Exception) {
            log("view tree scan err: ${e.message}")
        }
    }

    private fun collectWebViews(view: android.view.View, out: MutableList<Any>) {
        val name = view.javaClass.name
        if (name.contains("WebView") || name.contains("XWebView") || name.contains("X5WebView")) {
            out.add(view)
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                collectWebViews(view.getChildAt(i), out)
            }
        }
    }
}