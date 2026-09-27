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

    /** All X5 WebView instances ever seen via loadUrl, with their last loadUrl param. */
    private val trackedViews = java.util.WeakHashMap<Any, String>()

    /** Find a tracked WebView whose current URL (getUrl()) contains [host].
     *  If pool is empty, auto-steal. Returns the WebView or null. */
    fun findForHost(host: String): Any? {
        synchronized(trackedViews) {
            for (wv in trackedViews.keys) {
                try {
                    val url = wv.javaClass.getMethod("getUrl").invoke(wv) as? String ?: ""
                    if (url.contains(host)) {
                        log("findForHost($host) hit: ${url.take(120)}")
                        if (!WebViewPool.ready) {
                            try {
                                val ctx = getAppContext()
                                if (ctx != null && WebViewPool.ensureWindow(ctx) && WebViewPool.steal(wv)) {
                                    log("★ WebView stolen into overlay (findForHost)")
                                    targetView = wv
                                    // Inject payload into the stolen WebView
                                    if (!injected) {
                                        injected = true
                                        inject(wv)
                                    }
                                }
                            } catch (e: Exception) { log("auto-steal err: ${e.message}") }
                        }
                        return wv
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    /** The WebView instance (any type) currently holding the CTWing page. */
    @Volatile
    private var targetView: Any? = null

    /** Prevent re-injection within the same page lifetime. */
    @Volatile
    private var injected = false

    fun hook(classLoader: ClassLoader) {
        // ---- X5 WebView (com.tencent.xweb) — register bridge early on loadUrl ----
        // addJavascriptInterface MUST be called BEFORE page scripts run,
        // otherwise the X5 renderer process never sees the bridge.
        // We register the bridge here but do NOT evaluateJavascript —
        // JS injection happens later in onResume (page stable, login done).
        runCatching {
            val x5 = XposedHelpers.findClass("com.tencent.xweb.WebView", classLoader)
            XposedHelpers.findAndHookMethod(x5, "loadUrl", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as? String ?: return
                        // Track ALL WebView instances for findForHost lookup
                        synchronized(trackedViews) { trackedViews[param.thisObject] = url }
                        if (url.contains(CTWING_URL_MARKER)) {
                            log("loadUrl: ${url.take(120)}")
                            registerBridgeEarly(param.thisObject, url)
                        }
                    }
                })
            log("hooked X5 loadUrl(String)")
        }.onFailure { log("X5 loadUrl(String) failed: ${it.message}") }

        runCatching {
            val x5 = XposedHelpers.findClass("com.tencent.xweb.WebView", classLoader)
            XposedHelpers.findAndHookMethod(x5, "loadUrl",
                String::class.java, MutableMap::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as? String ?: return
                        if (url.contains(CTWING_URL_MARKER)) {
                            log("loadUrl(map): ${url.take(120)}")
                            registerBridgeEarly(param.thisObject, url)
                        }
                    }
                })
            log("hooked X5 loadUrl(String,Map)")
        }.onFailure { log("X5 loadUrl(Map) failed: ${it.message}") }

        runCatching {
            val wv = XposedHelpers.findClass("android.webkit.WebView", classLoader)
            XposedHelpers.findAndHookMethod(wv, "loadUrl", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[0] as? String ?: return
                        if (url.contains(CTWING_URL_MARKER)) {
                            log("loadUrl(system): ${url.take(120)}")
                            registerBridgeEarly(param.thisObject, url)
                        }
                    }
                })
            log("hooked system WebView.loadUrl")
        }.onFailure { log("system WebView failed: ${it.message}") }

        // NOTE: We deliberately DO NOT hook addJavascriptInterface globally.
        // Doing so injected our bridge into EVERY WeChat WebView (including
        // the official-account OAuth login page), which broke 公众号免密登录.
        // Instead we only inject on CTWing URLs via loadUrl detection + view
        // tree scan (both URL-gated).

        // ---- onPageFinished: catch URL regardless of load method ----
        hookWebViewClientCallbacks(classLoader)

        // ---- MMWebViewUI.onResume: find WebView from foreground Activity ----
        hookActivityResumeForInjection(classLoader)

        // ---- Keep CTWing WebView alive in background / screen-off ----
        // X5 freezes the JS engine when the Activity is paused or the screen
        // turns off. We neutralize onPause/pauseTimers/onStop for the CTWing
        // WebView so the SPA keeps executing fetch() + bridge callbacks.
        hookKeepAlive(classLoader)
    }

    /**
     * Neutralize WebView + Activity pause/destroy lifecycle so the CTWing
     * page stays alive even when the screen is off or the Activity is
     * backgrounded. We hook BOTH the WebView-level methods AND the
     * Activity-level onPause/onStop to prevent the cascade.
     */
    private fun hookKeepAlive(classLoader: ClassLoader) {
        // 1. WebView-level: onPause / pauseTimers / onStop
        val webViewClasses = mutableListOf<String>(
            "android.webkit.WebView",
            "com.tencent.xweb.WebView",
            "com.tencent.smtt.sdk.WebView",
        )
        val pauseMethods = listOf("onPause", "pauseTimers", "onStop")
        for (cname in webViewClasses) {
            for (mname in pauseMethods) {
                runCatching {
                    val cls = XposedHelpers.findClass(cname, classLoader)
                    XposedHelpers.findAndHookMethod(cls, mname,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (isCtwingWebView(param.thisObject)) {
                                    log("KEEP-ALIVE: blocked $cname.$mname")
                                    param.result = null
                                }
                            }
                        })
                }.onFailure { /* method not present */ }
            }
        }

        // 2. Activity-level: MMWebViewUI.onPause / onStop
        //    Block these ONLY to prevent the WebView JS freeze cascade,
        //    but do NOT block finish/destroy (they're needed for re-auth).
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI", classLoader)
            for (mname in listOf("onPause", "onStop")) {
                runCatching {
                    XposedHelpers.findAndHookMethod(cls, mname,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (hasCtwingWebView()) {
                                    log("KEEP-ALIVE: blocked Activity.$mname (CTWing active)")
                                    param.result = null
                                }
                            }
                        })
                }.onFailure { /* method may not exist */ }
            }
        }.onFailure { log("MMWebViewUI keep-alive failed: ${it.message}") }

        log("keep-alive hooks installed (WebView + Activity onPause/onStop)")
    }

    /** True if we currently hold a CTWing WebView reference. */
    private fun hasCtwingWebView(): Boolean {
        return targetView != null || lastCtwingWebView != null
    }

    /** True if the given WebView is currently holding the CTWing page. */
    private fun isCtwingWebView(webView: Any?): Boolean {
        if (webView == null) return false
        if (webView === targetView || webView === lastCtwingWebView) return true
        return try {
            val url = webView.javaClass.getMethod("getUrl").invoke(webView) as? String ?: ""
            url.contains(CTWING_URL_MARKER)
        } catch (_: Exception) { false }
    }

    /** The last-seen WebView object; reset injected flag on new WebView. */
    @Volatile
    private var lastWebView: Any? = null

    private fun checkUrl(webView: Any, url: String) {
        if (!url.contains(CTWING_URL_MARKER)) return
        // wxLogin is the OAuth redirect — register bridge + store ref,
        // inject payload after a delay (SPA auto-navigates to / after auth)
        if (url.contains("wxLogin")) {
            log("checkUrl: wxLogin detected — registering bridge, deferring injection")
            registerBridgeEarly(webView, url)
            lastCtwingWebView = webView
            // After OAuth completes (~5s), the SPA navigates to /web-apps/.
            // Re-scan then to inject the full payload.
            mainHandler.postDelayed({
                if (lastCtwingWebView === webView) {
                    log("checkUrl: post-wxLogin re-inject")
                    injected = false
                    inject(webView)
                }
            }, 6000L)
            return
        }
        if (webView === lastWebView && injected) return
        lastWebView = webView
        injected = false
        log("CTWing URL detected: ${url.take(120)}")
        injected = true
        inject(webView)
    }

    /**
     * Inject JS bridge via reflection. All operations are posted to the UI
     * thread's message queue because `evaluateJavascript` must be called on
     * the WebView's owning thread.
     */
    /** Single shared bridge instance — held forever to prevent GC.
     *  IMPORTANT: use ONE bridge across all WebView re-injections. Each
     *  inject() previously created a new bridge, but JS holds a reference
     *  to the OLD one; GC then collected it → callbacks became no-ops. */
    private val bridge: CtwingJsBridge by lazy { CtwingJsBridge() }

    private fun inject(webView: Any) {
        targetView = webView

        mainHandler.post {
            // NOTE: addJavascriptInterface does NOT work post-load in X5
            // (sandboxed renderer process doesn't see new Java bridges).
            // We keep it for logging only; the real work (inject JS +
            // dump HTML) is done via loadUrl("javascript:...") which routes
            // to the renderer process synchronously.
            try {
                webView.javaClass
                    .getMethod("addJavascriptInterface", Any::class.java, String::class.java)
                    .invoke(webView, bridge, CtwingJsInjector.BRIDGE_NAME)
                log("addJavascriptInterface OK")
            } catch (e: Exception) {
                log("addJavascriptInterface failed: ${e.message}")
            }

            // Inject payload via evaluateJavascript — MUST use the SAME API
            // as evaluateJsForResult (X5 assigns them to the SAME V8 isolate
            // when they share the same callback interface). loadUrl creates a
            // separate context that evaluateJavascript cannot read from.
            val jsPayload = CtwingJsInjector.build()
            try {
                val evalMethod = webView.javaClass.methods.find {
                    it.name == "evaluateJavascript" &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[1].name.contains("ValueCallback")
                }
                if (evalMethod != null) {
                    val cb = android.webkit.ValueCallback<String> { /* fire-and-forget */ }
                    evalMethod.invoke(webView, jsPayload, cb)
                    log("payload injected via evaluateJavascript (shared V8)")
                } else {
                    webView.javaClass.getMethod("loadUrl", String::class.java)
                        .invoke(webView, "javascript:" + jsPayload.replace("\n", " "))
                    log("payload injected via loadUrl (fallback)")
                }
            } catch (e: Exception) {
                log("payload injection failed: ${e.message}")
            }

            // ★ STEAL into overlay pool — WebView escapes Activity lifecycle
            if (!WebViewPool.ready) {
                try {
                    val ctx = getAppContext()
                    if (ctx != null && WebViewPool.ensureWindow(ctx) && WebViewPool.steal(webView)) {
                        log("★ WebView stolen into overlay pool")
                        targetView = webView
                    }
                } catch (e: Exception) { log("steal err: ${e.message}") }
            }

            // Dump HTML + script URLs
            val dumpScript = """
                javascript:(function(){
                    try {
                        var page = location.pathname || '';
                        var html = document.documentElement.outerHTML;
                        window._dsh_html = html.substring(0, 100000);
                        var scripts = [];
                        var ss = document.getElementsByTagName('script');
                        for (var i = 0; i < ss.length; i++) {
                            scripts.push({src: ss[i].src || '', inline: !ss[i].src, len: ss[i].textContent ? ss[i].textContent.length : 0});
                        }
                        window._dsh_urls = JSON.stringify(scripts);
                    } catch(e) { window._dsh_err = e.message; }
                })();
            """.trimIndent().replace("\n", " ")
            
            try {
                webView.javaClass
                    .getMethod("loadUrl", String::class.java)
                    .invoke(webView, dumpScript)
                log("loadUrl(javascript:) dispatched for dump")
            } catch (e: Exception) {
                log("loadUrl(javascript:) failed: ${e.message}")
            }

            startIpcPoller()
        }
    }

    /**
     * Inject JS payload WITHOUT bridge dependency. Uses loadUrl("javascript:...")
     * to write HTML to sessionStorage, then evaluates JavaScript to read it back.
     * This is the only reliable post-load JS execution path in X5.
     */
    private fun injectPayload(webView: Any) {
        targetView = webView

        mainHandler.post {
            // Step 0: Inject __ctwing + CryptoJS hooks via evaluateJavascript
            // (NOT loadUrl - that creates a separate document context)
            try {
                val method = webView.javaClass.getMethod("evaluateJavascript",
                    String::class.java, android.webkit.ValueCallback::class.java)
                method.invoke(webView, CtwingJsInjector.build(),
                    android.webkit.ValueCallback<String> { result ->
                        log("payload injection callback: ${(result ?: "null").take(200)}")
                    })
                log("evaluateJavascript payload injected")
            } catch (e: Exception) {
                log("payload injection failed: ${e.message}")
            }

            // Step 1: Dump HTML via loadUrl (creates new context, but we only need to
            // read window.__dsh_html back, which goes into the ORIGINAL page context)
            try {
                val dumpJs = "javascript:(function(){try{window.__dsh_html=document.documentElement.outerHTML.substring(0,40000);}catch(e){window.__dsh_err=e.message;}})();"
                webView.javaClass.getMethod("loadUrl", String::class.java)
                    .invoke(webView, dumpJs)
                log("loadUrl(javascript:) dump dispatched")
            } catch (e: Exception) {
                log("loadUrl dump failed: ${e.message}")
            }

            // Step 2: Read back HTML via evaluateJavascript callback
            mainHandler.postDelayed({
                try {
                    val cb = android.webkit.ValueCallback<String> { result ->
                        val text = result ?: ""
                        val unquoted = text.removeSurrounding("\"").unescapeJava()
                        log("HTML readback: ${unquoted.length} chars")
                        if (unquoted.isNotBlank()) {
                            try {
                                val dir = java.io.File(CtwingIpcBridge.wechatDataDir, "dsh_ctwing_bundles")
                                dir.mkdirs()
                                val file = java.io.File(dir, "page_dump_${System.currentTimeMillis()}.html")
                                file.writeText(unquoted)
                                log("HTML saved: ${file.absolutePath}")
                            } catch (e2: Exception) {
                                log("HTML save failed: ${e2.message}")
                            }
                        }
                    }
                    webView.javaClass.getMethod("evaluateJavascript",
                        String::class.java, android.webkit.ValueCallback::class.java
                    ).invoke(webView, "window.__dsh_html || ''", cb)
                } catch (e: Exception) {
                    log("HTML readback failed: ${e.message}")
                }
            }, 2000)
        }
    }

    private fun String.unescapeJava(): String {
        return this
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
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
            val methods = webView.javaClass.methods.filter {
                it.name == "evaluateJavascript" && it.parameterCount == 2
            }
            val method: Method = methods.firstOrNull() ?: webView.javaClass.getMethod(
                "evaluateJavascript",
                String::class.java,
                android.webkit.ValueCallback::class.java
            )
            // MUST pass a real ValueCallback (even dummy) — passing null may
            // route to a different V8 isolate in X5, making window mutations
            // invisible to evaluateJsForResult's callback-based calls.
            val cb = android.webkit.ValueCallback<String> { /* fire-and-forget */ }
            method.invoke(webView, script, cb)
        } catch (e: Exception) {
            log("evaluateJavascript failed: ${e.message}")
        }
    }

    /**
     * Public API for [CtwingFacade] — evaluate JS on the main thread.
     * Works on both system and X5 WebView.
     */
    /** Prompt-pipe: JS calls prompt(rid) → WebChromeClient.onJsPrompt → read result */
    private val promptCallbacks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String>>()

    fun handlePromptResult(message: String) {
        val f = promptCallbacks[message] ?: return
        // Read result from JS global
        val wv = targetView ?: return
        val readJs = "(window.__ctwingP||{})['$message']||null"
        val json = evaluateJsForResult(wv, "JSON.stringify($readJs)", 2000)
        if (json != null && json != "null") {
            f.complete(json)
        } else {
            f.completeExceptionally(RuntimeException("prompt result read failed"))
        }
    }

    fun evaluateJs(webView: Any, script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evaluateJsOnUI(webView, script)
        } else {
            mainHandler.post { evaluateJsOnUI(webView, script) }
        }
    }

    fun currentWebView(): Any? = targetView

    fun isInjected(): Boolean {
        if (targetView != null) return true
        val wv = lastCtwingWebView ?: return false
        log("isInjected: lazy injecting JS payload into stored WebView")
        injectPayload(wv)
        return targetView != null
    }

    /**
     * Aggressively find + inject the CTWing WebView by scanning the foreground
     * Activity. Called when fireJs can't find a target — recovers from wxLogin
     * re-auth where the initial injection was skipped.
     */
    fun forceRescan() {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val activities = currentAt.javaClass
                .getMethod("getActivities").invoke(currentAt) as? Iterable<*> ?: return
            for (rec in activities) {
                val activity = rec?.javaClass?.getMethod("getActivity")?.invoke(rec) as? android.app.Activity ?: continue
                val name = activity.componentName?.className ?: ""
                if (!name.contains("MMWebViewUI") && !name.contains("WebViewUI")) continue
                log("forceRescan: scanning $name")
                scanActivityViewTree(activity)
                if (targetView != null || lastCtwingWebView != null) {
                    if (targetView == null && lastCtwingWebView != null) {
                        injectPayload(lastCtwingWebView!!)
                    }
                    log("forceRescan: recovered WebView reference")
                    return
                }
            }
        } catch (e: Exception) {
            log("forceRescan failed: ${e.message}")
        }
    }

    /** Reset all state before rebuilding the H5 WebView from scratch. */
    fun resetForRebuild() {
        targetView = null
        lastCtwingWebView = null
        lastWebView = null
        injected = false
    }

    /** Re-inject the JS payload into the given WebView (e.g. after SPA navigation). */
    fun forceReinject(wv: Any) {
        injected = false
        targetView = wv
        inject(wv)
        log("forceReinject: payload re-injected")
    }

    /** Direct WebView reference stored by onResume hook (avoids Activity ref issues). */
    @Volatile
    private var lastCtwingWebView: Any? = null

    /**
     * Register addJavascriptInterface EARLY (before page scripts run).
     * This is the ONLY timing that works with X5 sandboxed rendering.
     * We do NOT evaluateJavascript here to avoid breaking the login flow.
     */
    private fun registerBridgeEarly(webView: Any, url: String) {
        if (!url.contains(CTWING_URL_MARKER)) return
        // Register bridge but skip JS injection on wxLogin to protect OAuth
        mainHandler.post {
            try {
                webView.javaClass
                    .getMethod("addJavascriptInterface", Any::class.java, String::class.java)
                    .invoke(webView, bridge, CtwingJsInjector.BRIDGE_NAME)
                log("bridge registered on loadUrl: ${url.take(80)}")
            } catch (e: Exception) {
                log("early bridge register failed: ${e.message}")
            }
        }
    }

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
                // X5 WebView (cz5.j0) has multiple evaluateJavascript overloads.
                // android.webkit.ValueCallback<String> may exist but the
                // X5 kernel uses its own dispatcher, so the callback proxy
                // for the wrong interface type never fires.
                //
                // Strategy: enumerate every evaluateJavascript(String, XXX)
                // overload, create a proxy for each Callback type, and try
                // invoking it. The first one whose callback fires wins.
                val candidates = webView.javaClass.methods.filter {
                    it.name == "evaluateJavascript" && it.parameterCount == 2
                }
                log("evaluateJsForResult: found ${candidates.size} evaluateJavascript overload(s) on ${webView.javaClass.name}")
                var tried = false
                for (m in candidates) {
                    val cbType = m.parameterTypes.getOrNull(1) ?: continue
                    if (!cbType.isInterface) continue
                    log("evaluateJsForResult: trying overload with callback type=${cbType.name}")
                    try {
                        val cb = java.lang.reflect.Proxy.newProxyInstance(
                            cbType.classLoader, arrayOf(cbType),
                            java.lang.reflect.InvocationHandler { _, _, args ->
                                val raw = args?.getOrNull(0)?.toString() ?: "null"
                                holder.set(raw)
                                latch.countDown()
                                null
                            }
                        )
                        m.invoke(webView, script, cb)
                        tried = true
                        log("evaluateJsForResult: sent via ${cbType.name}, waiting for callback...")
                        break // if the callback fires later, holder will be set
                    } catch (e: Exception) {
                        // this overload failed, try next
                    }
                }
                if (!tried) {
                    log("evaluateJsForResult: no compatible overload found, trying null-callback")
                    try {
                        val m2 = candidates.firstOrNull()
                        if (m2 != null) m2.invoke(webView, script, null)
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
        if (!ok) log("evaluateJsForResult TIMEOUT after ${timeoutMs}ms (callback not fired)")
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
                            val activity = param.thisObject as? android.app.Activity ?: return
                            // Re-scan EVERY resume: after login navigation the
                            // WebView context changes, so we must re-inject.
                            mainHandler.postDelayed({
                                scanActivityViewTree(activity)
                            }, 1500L)
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
                        val activity = param.thisObject as? android.app.Activity ?: return
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
            val intent = activity.intent
            val rawUrlFromIntent = intent?.getStringExtra("rawUrl") ?: ""
            val hasCtwingIntent = rawUrlFromIntent.contains(CTWING_URL_MARKER)
                    || rawUrlFromIntent.contains("open.weixin.qq.com/connect/oauth2")
                    || rawUrlFromIntent.contains("tywl.crm.189.cn")

            val root = activity.window?.decorView ?: return
            val webViews = mutableListOf<Any>()
            collectWebViews(root, webViews)
            log("view tree scan: found ${webViews.size} WebView(s) in $activity")
            var sawOAuthMidState = false
            for (wv in webViews) {
                val url = try {
                    wv.javaClass.getMethod("getUrl").invoke(wv) as? String ?: ""
                } catch (e: Exception) { "" }
                val origUrl = try {
                    wv.javaClass.getMethod("getOriginalUrl").invoke(wv) as? String ?: ""
                } catch (e: Exception) { "" }
                val combined = "$url $origUrl"
                log("  WebView URL: ${url.take(120)} / orig: ${origUrl.take(60)}")

                // Activity was started with a CTWing rawUrl but screen is off →
                // WebView URL is empty. Force loadUrl to kick-start the page.
                if (hasCtwingIntent && url.isBlank() && origUrl.isBlank()) {
                    log("  CTWing intent detected but WebView URL is empty — forcing loadUrl")
                    try {
                        wv.javaClass.getMethod("loadUrl", String::class.java)
                            .invoke(wv, rawUrlFromIntent)
                        log("  loadUrl dispatched for screen-off CTWing WebView")
                        registerBridgeEarly(wv, rawUrlFromIntent)
                        lastCtwingWebView = wv
                        mainHandler.postDelayed({ injectIfCtwing(wv) }, 5000L)
                        return
                    } catch (e: Exception) {
                        log("  force loadUrl failed: ${e.message}")
                    }
                }

                // OAuth mid-state (open.weixin.qq.com or tywl.crm.189.cn) — keep
                // re-scanning until it lands on the CTWing SPA (wxLogin?ticket=).
                if (url.contains("open.weixin.qq.com/connect/oauth2") ||
                    url.contains("tywl.crm.189.cn")) {
                    sawOAuthMidState = true
                    lastCtwingWebView = wv
                    log("  OAuth mid-state detected — will rescan until wxLogin")
                }

                if (combined.contains(CTWING_URL_MARKER)) {
                    log("CTWing WebView found via view tree scan!")
                    lastCtwingWebView = wv
                    checkUrl(wv, if (url.isNotBlank()) url else origUrl)

                    // STEAL the WebView into the transparent overlay pool
                    if (!WebViewPool.ready) {
                        try {
                            if (WebViewPool.ensureWindow(activity.applicationContext)) {
                                if (WebViewPool.steal(wv)) {
                                    log("★ CTWing WebView stolen into overlay pool")
                                    targetView = wv
                                }
                            }
                        } catch (e: Exception) {
                            log("  steal failed: ${e.message}")
                        }
                    }
                }
            }

            // Re-scan periodically if we're in the OAuth redirect mid-state,
            // because the 302 chain does not go through loadUrl() and thus
            // never triggers checkUrl directly.
            if (sawOAuthMidState && !WebViewPool.ready && scanAttempts < MAX_RESCAN) {
                scanAttempts++
                mainHandler.postDelayed({ scanActivityViewTree(activity) }, 3000L)
            }
        } catch (e: Exception) {
            log("view tree scan err: ${e.message}")
        }
    }

    private var scanAttempts = 0
    private val MAX_RESCAN = 20  // 20 × 3s = 60s max waiting for OAuth→wxLogin

    /** Inject payload into a WebView that we know should be CTWing. */
    private fun injectIfCtwing(webView: Any) {
        try {
            val url = webView.javaClass.getMethod("getUrl").invoke(webView) as? String ?: ""
            val origUrl = try {
                webView.javaClass.getMethod("getOriginalUrl").invoke(webView) as? String ?: ""
            } catch (e: Exception) { "" }
            if (url.contains(CTWING_URL_MARKER) && !injected) {
                log("injectIfCtwing: injecting into loaded page $url")
                inject(webView)
            } else if ((url.contains("open.weixin.qq.com/connect/oauth2") ||
                        url.contains("tywl.crm.189.cn")) && !injected) {
                // OAuth mid-state — wait for the redirect chain to land on
                // wxLogin?ticket=, then inject + steal.
                log("injectIfCtwing: OAuth mid-state, will recheck in 4s ($url)")
                lastCtwingWebView = webView
                mainHandler.postDelayed({ injectIfCtwing(webView) }, 4000L)
            }
        } catch (e: Exception) {
            log("injectIfCtwing failed: ${e.message}")
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

    /** Get the WeChat application context via ActivityThread reflection. */
    private fun getAppContext(): android.content.Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            currentAt.javaClass.getMethod("getApplication").invoke(currentAt) as? android.content.Context
        } catch (_: Exception) { null }
    }

    /** Find ALL activities (not just top) via ActivityThread, including paused. */
    fun findActivitiesByClassName(classNamePartial: String): List<android.app.Activity> {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = currentAt.javaClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(currentAt) as? Map<*, *> ?: return emptyList()
            activities.values.mapNotNull { record ->
                try {
                    val actField = record?.javaClass?.getDeclaredField("activity")
                    actField?.isAccessible = true
                    val act = actField?.get(record) as? android.app.Activity
                    act?.takeIf { it.javaClass.name.contains(classNamePartial) }
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }
}