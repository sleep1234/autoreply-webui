package dev.example.autoreply.ctwing

import android.content.Context
import android.os.PowerManager
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level API facade for the CTWing H5 bridge.
 *
 * Works in two modes:
 *   SAME-PROCESS — WebView is in the current process; [callJs] uses
 *     [CtwingWebViewHook.evaluateJs] directly.
 *   CROSS-PROCESS (IPC) — WebView is in :tools process; requests are
 *     written to a shared file under WeChat's data dir, the tools-side
 *     poller executes them, and results come back through the same IPC.
 */
object CtwingFacade {

    private const val TAG = "[CTWing-Facade]"
    private val ridGen = AtomicLong(0)

    /** Max wait for a single JS API call (ms). */
    private const val TIMEOUT_MS = 15_000L

    /**
     * 全局互斥锁，串行化所有 WebView 操作。
     * CtwingJsBridge.lastApiResponse 是单例共享变量，
     * 并发操作会互相覆盖响应，导致结果串掉（e.g. 重绑拿到查询的数据）。
     */
    val webViewMutex = Mutex()

    /** 重建互斥锁：防止 pullTokenOrRebuild 与 TokenKeepAlive 并发重建，导致多窗口叠加。 */
    private val rebuildMutex = Mutex()

    /** 上一次成功 pullToken 的时间戳（毫秒），10 分钟内跳过重新读取 WebView。 */
    @Volatile
    private var lastTokenPullAt = 0L
    private const val TOKEN_CACHE_TTL_MS = 10 * 60 * 1000L  // 10 minutes

    // ------------------------------------------------------------------
    //  callJs — dispatches via local WebView or IPC
    // ------------------------------------------------------------------

    private suspend fun callJsLocal(script: String): String {
        val wv = CtwingWebViewHook.currentWebView()
            ?: throw IllegalStateException("WebView reference lost")
        return CtwingWebViewHook.evaluateJsForResult(wv, script, TIMEOUT_MS)
            ?: throw RuntimeException("CTWing API call timed out after ${TIMEOUT_MS}ms")
    }

    private suspend fun callJs(script: String): String {
        if (CtwingWebViewHook.isInjected()) {
            return callJsLocal(script)
        }
        val dir = CtwingIpcBridge.wechatDataDir
        if (dir == null) throw IllegalStateException("IPC data dir not set")
        return callJsIpc(script)
    }

    // ----------------------------------------------------------------
    //  IPC path (WebView in another process)
    // ----------------------------------------------------------------

    private suspend fun callJsIpc(script: String): String {
        val rid = "r${ridGen.incrementAndGet()}"
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        CtwingJsBridge.pendingRequests[rid] = deferred

        val reqJson = buildString {
            append("{\"rid\":\"$rid\",\"ts\":${System.currentTimeMillis()},\"script\":")
            append(org.json.JSONObject.quote(script))
            append("}")
        }

        if (!CtwingIpcBridge.postRequest(reqJson)) {
            CtwingJsBridge.pendingRequests.remove(rid)
            throw IllegalStateException("IPC write failed")
        }

        XposedBridge.log("$TAG [IPC-REQ] rid=$rid script_preview=${script.take(80)}")

        // Poll response file for up to TIMEOUT_MS
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val resp = CtwingIpcBridge.readResponse()
            if (resp != null) {
                val obj = org.json.JSONObject(resp)
                val respRid = obj.optString("rid", "")
                if (respRid == rid) {
                    CtwingIpcBridge.clearResponse()
                    CtwingJsBridge.pendingRequests.remove(rid)
                    val ok = obj.optBoolean("ok", false)
                    if (ok) {
                        val result = obj.optString("result", "")
                        XposedBridge.log("$TAG [IPC-RESP] rid=$rid OK len=${result.length}")
                        return result
                    } else {
                        val error = obj.optString("error", "unknown")
                        throw RuntimeException("CTWing IPC error: $error")
                    }
                }
            }
            kotlinx.coroutines.delay(200L)
        }

        CtwingJsBridge.pendingRequests.remove(rid)
        throw RuntimeException("CTWing IPC timed out after ${TIMEOUT_MS}ms")
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Actively PULL token + bond from the SPA via evaluateJavascript.
     * Does NOT rely on JS bridge push (addJavascriptInterface is unreliable
     * in X5 sandbox). Returns true if token was captured.
     */
    suspend fun pullToken(): Boolean {
        // 缓存时效：10 分钟内刚拉过且缓存有效，直接返回，避免频繁 evaluateJavascript IPC
        val cached = NativeHttp.cachedToken
        if (cached != null && cached.isNotBlank() &&
            (System.currentTimeMillis() - lastTokenPullAt) < TOKEN_CACHE_TTL_MS) {
            return true
        }
        return try {
            val obj = pullTokenRaw() ?: return false
            val token = obj.optString("token", "")
            val bond = obj.optString("bond", "")
            val cookie = obj.optString("cookie", "")
            if (token.isNotBlank()) {
                NativeHttp.cachedToken = token
                lastTokenPullAt = System.currentTimeMillis()
                XposedBridge.log("$TAG pullToken: token captured (${token.length} chars)")
            } else {
                // token 为空但旧缓存可能有效：WebView 刚 reload OAuth 时 SPA 未就绪会读不到 cookie。
                // 不清空旧缓存——如果旧 token 已过期，NativeHttp 会收到 401 并由 nativeGetWithRetry 兜底重建。
                XposedBridge.log("$TAG pullToken: empty token from WebView (keeping cached if any)")
            }
            if (bond.isNotBlank()) {
                NativeHttp.cachedBond = bond
                XposedBridge.log("$TAG pullToken: bond captured (${bond.length} chars)")
            }
            if (cookie.isNotBlank()) {
                NativeHttp.cachedCookie = cookie
                XposedBridge.log("$TAG pullToken: cookie cached (${cookie.length} chars)")
            }
            token.isNotBlank()
        } catch (e: Exception) {
            XposedBridge.log("$TAG pullToken failed: ${e.message}")
            false
        }
    }

    /** pullToken + 兜底重建：WebView 被杀时自动静默重建后重试。 */
    suspend fun pullTokenOrRebuild(): Boolean {
        if (pullToken()) return true
        XposedBridge.log("$TAG pullToken failed, rebuilding H5 silently…")
        if (!ensureReady(40_000L)) {
            XposedBridge.log("$TAG rebuild failed, giving up")
            return false
        }
        kotlinx.coroutines.delay(2_000L)
        return pullToken()
    }

    /** 从 SPA 读 cookie/localStorage 并解析成 JSONObject（不含缓存写入）。 */
    private suspend fun pullTokenRaw(): org.json.JSONObject? {
        val script = """
            (function(){
                try {
                    var token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || '';
                    var bond = (document.cookie.match(/ctl-dync-ct-bond=([^;]+)/)||[])[1] || '';
                    if (!token) { try { token = sessionStorage.getItem('ACCESS_TOKEN') || localStorage.getItem('ACCESS_TOKEN') || ''; } catch(e){} }
                    return JSON.stringify({token:token,bond:bond,cookie:document.cookie||''});
                } catch(e) { return JSON.stringify({error:e.message}); }
            })()
        """.trimIndent()
        val raw = callJs(script)
        XposedBridge.log("$TAG pullToken raw len=${raw.length}: ${raw.take(120)}")
        // raw 是 JSON 字符串（可能双重转义），用 JSONObject 解析最稳
        return runCatching {
            org.json.JSONObject(raw)
        }.recoverCatching {
            org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String)
        }.getOrNull()
    }
    /** Read last API capture — fast bridge check first, then short evaluateJavascript fallback. */
    suspend fun readApiResponses(): String {
        // 1. JS bridge callback（onApiCapture 写入）：最快
        val resp = CtwingJsBridge.lastApiResponse
        if (resp != null) return resp
        // 2. window.__dshResult（diagnoseCard/queryCard 异步结果写入这里）
        val wv = CtwingWebViewHook.currentWebView() ?: return "null"
        return CtwingWebViewHook.evaluateJsForResult(wv,
            "(function(){var r=window.__dshResult||'';if(!r){r=window.__ctwingRecon&&window.__ctwingRecon._queryRaw||'';}return r;})()",
            2_000L  // short timeout, fail fast so polling loop can retry
        ) ?: "null"
    }

    /** Poll window.__dshResult (async fetch result channel), short 2s timeout. */
    suspend fun pollDshResult(): String {
        val wv = CtwingWebViewHook.currentWebView() ?: return "null"
        return CtwingWebViewHook.evaluateJsForResult(wv,
            "(function(){return window.__dshResult||'';})()",
            2_000L
        ) ?: "null"
    }

    // ------------------------------------------------------------------
    //  H5 rebuild (when X5 gets killed by the OS)
    // ------------------------------------------------------------------

    /**
     * Ensure the CTWing H5 is alive and the JS bridge is injected.
     * If the page was killed by the OS (common after prolonged screen-off),
     * silently rebuild it via am start + wait for ready signal.
     *
     * @return true if ready (or became ready after rebuild), false on timeout.
     */
    suspend fun ensureReady(timeoutMs: Long = 30_000L): Boolean {
        // Quick check: pool WebView exists AND is on main SPA (not wxLogin redirect)
        val wv = CtwingWebViewHook.findForHost("tywlonestop.ctwing.cn")
        if (wv != null) {
            val url = try { wv.javaClass.getMethod("getUrl").invoke(wv) as? String ?: "" } catch (_: Exception) { "" }
            if (url.contains("web-apps") && !url.contains("wxLogin")) {
                // Stable — re-inject payload in case page navigated
                XposedBridge.log("$TAG ensureReady: pool stable at ${url.take(80)}, re-injecting")
                CtwingWebViewHook.forceReinject(wv)
                return true
            }
            // On wxLogin intermediate page — wait a bit then recheck
            XposedBridge.log("$TAG ensureReady: on wxLogin, waiting for SPA…")
            kotlinx.coroutines.delay(3_000L)
            val url2 = try { wv.javaClass.getMethod("getUrl").invoke(wv) as? String ?: "" } catch (_: Exception) { "" }
            if (url2.contains("web-apps") && !url2.contains("wxLogin")) {
                CtwingWebViewHook.forceReinject(wv)
                return true
            }
        }
        return rebuildAndWait(timeoutMs)
    }

    suspend fun forceRebuild(timeoutMs: Long = 30_000L): Boolean {
        XposedBridge.log("$TAG force rebuild (token expired)")
        // 续期：不杀 H5，只在已有 WebView 上 reload OAuth URL 换新 token。
        // 重绑 401 重试用的是 NativeHttp POST（不依赖 WebView XHR），
        // 只需要新鲜 token，不需要新鲜 H5。
        val wv = CtwingWebViewHook.findForHost("tywlonestop.ctwing.cn")
        val ok = if (wv != null) {
            XposedBridge.log("$TAG forceRebuild: reload OAuth on existing WebView")
            reloadOAuthOnPool(wv)
            kotlinx.coroutines.delay(8_000L)
            true
        } else {
            rebuildAndWait(timeoutMs)
        }
        // 重置 token 缓存时间戳，强制下次 pullToken 从 WebView 重读新 token
        lastTokenPullAt = 0L
        return ok
    }

    /**
     * Reload OAuth on the pool WebView without starting a new Activity.
     * The stolen WebView lives in the transparent overlay window so
     * loadUrl works even with screen off. After the redirect chain
     * completes (~8s), the SPA's 免密登录 will write a fresh token to cookie.
     *
     * [wv] 由调用方传入（已确认非 null），避免内部再查一次造成递归。
     */
    suspend fun reloadOAuthOnPool(wv: Any? = null) {
        val target = wv ?: WebViewPool.webView
        if (target == null) {
            XposedBridge.log("$TAG reloadOAuth: no WebView, doing full rebuild")
            rebuildAndWait(40_000L)
            return
        }
        try {
            val oauthUrl = "https://open.weixin.qq.com/connect/oauth2/authorize" +
                "?appid=wx346a088619ba2d65" +
                "&redirect_uri=" + java.net.URLEncoder.encode(
                    "https://tywl.crm.189.cn/WLW-IMR/author/oauthTransfer.do?response_type=code&state=oauthsszc",
                    "UTF-8"
                ) +
                "&response_type=code&scope=snsapi_base&state=oauthsszc#wechat_redirect"
            
            XposedBridge.log("$TAG reloadOAuth: loading OAuth URL on pool WebView")
            // loadUrl 是 WebView UI 操作，必须在主线程执行
            withContext(Dispatchers.Main) {
                target.javaClass.getMethod("loadUrl", String::class.java).invoke(target, oauthUrl)
            }
            // SPA will auto-complete auth + write fresh ACCESS_TOKEN cookie in ~10s
        } catch (e: Exception) {
            XposedBridge.log("$TAG reloadOAuth failed: ${e.message}, fallback to rebuild")
            rebuildAndWait(40_000L)
        }
    }

    /**
     * Pre-initialize H5 on WeChat startup (screen is ON at this point).
     * Silently completes OAuth, steals the WebView into the overlay pool.
     * After this, lock-screen queries use the pool WebView directly.
     */
    suspend fun preInitH5() {
        try {
            // If already in pool, nothing to do
            if (CtwingWebViewHook.findForHost("tywlonestop.ctwing.cn") != null) {
                XposedBridge.log("$TAG pre-init: already ready")
                return
            }
            // Reuse rebuildAndWait (acquireWakeLock + rebuildH5 + poll findForHost)
            val ok = rebuildAndWait(40_000L)
            XposedBridge.log("$TAG pre-init: ${if (ok) "OK — WebView in pool" else "failed"}")
            if (ok) {
                // Pull token once for NativeHttp cache
                kotlinx.coroutines.delay(2_000L)
                pullToken()
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG pre-init failed: ${e.message}")
        }
    }

    private suspend fun rebuildAndWait(timeoutMs: Long): Boolean {
        return rebuildMutex.withLock {
            // 二次检查：等锁期间可能已被其他协程修好
            if (CtwingWebViewHook.findForHost("tywlonestop.ctwing.cn") != null) {
                XposedBridge.log("$TAG rebuild: already ready (fixed while waiting lock)")
                return@withLock true
            }

            XposedBridge.log("$TAG H5 appears dead, rebuilding silently...")
            acquireWakeLock()
            CtwingJsBridge.isPageReady = false
            CtwingJsBridge.lastApiResponse = null
            CtwingWebViewHook.resetForRebuild()
            rebuildH5()

            // Poll findForHost every 3s until OAuth → SPA completes
            val deadline = System.currentTimeMillis() + timeoutMs
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(3_000L)
                if (CtwingWebViewHook.findForHost("tywlonestop.ctwing.cn") != null) {
                    // WebView 已偷取到 overlay。清理残留空壳窗口：
                    // - 保留最新一个 MMWebViewUI 用 moveTaskToBack（保活渲染器）
                    // - finish 掉多余的旧壳（NEW_DOCUMENT 每次建新窗口不会自动杀旧的）
                    val activities = CtwingWebViewHook.findActivitiesByClassName("MMWebViewUI")
                    if (activities.size > 1) {
                        // 多余旧壳 → finish（WebView 已偷走，空壳 finish 不影响渲染器）
                        for (act in activities.dropLast(1)) {
                            if (!act.isFinishing && !act.isDestroyed) {
                                act.runOnUiThread { act.finish() }
                                XposedBridge.log("$TAG cleanup: finished stale ${act.javaClass.simpleName}")
                            }
                        }
                    }
                    // 最新一个 → moveTaskToBack 保活
                    activities.lastOrNull()?.let { act ->
                        act.runOnUiThread { act.moveTaskToBack(true) }
                        XposedBridge.log("$TAG silent: moved ${act.javaClass.simpleName} to back")
                    }
                    XposedBridge.log("$TAG H5 rebuild: found via findForHost")
                    ok = true
                    break
                }
            }
            if (!ok) XposedBridge.log("$TAG H5 rebuild: timeout")
            ok
        }
    }

    /**
     * (Re)open the CTWing H5 inside WeChat SILENTLY.
     *
     * Ported from WX H5 Auto's H5Launcher + KeepAliveHook:
     *   1. Simulate 公众号 menu entry via preUsername/prePublishId extras →
     *      WeChat performs snsapi_base OAuth silently, no user tap needed.
     *   2. FLAG_ACTIVITY_NO_USER_ACTION (65536) → never switches foreground.
     *   3. KeepAliveHook moves the task to back on onResume, so the WebView
     *      renders in the background while the user never sees it.
     */
    private fun rebuildH5() {
        try {
            val ctx = try {
                val atClass = Class.forName("android.app.ActivityThread")
                val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
                currentAt.javaClass.getMethod("getApplication").invoke(currentAt) as? Context
            } catch (_: Exception) { null }
            if (ctx == null) {
                XposedBridge.log("$TAG rebuild: no app context")
                return
            }
            val oauthUrl = "https://open.weixin.qq.com/connect/oauth2/authorize" +
                "?appid=wx346a088619ba2d65" +
                "&redirect_uri=" + java.net.URLEncoder.encode(
                    "https://tywl.crm.189.cn/WLW-IMR/author/oauthTransfer.do?response_type=code&state=oauthsszc",
                    "UTF-8"
                ) +
                "&response_type=code&scope=snsapi_base&state=oauthsszc#wechat_redirect"

            val intent = android.content.Intent().apply {
                setClassName("com.tencent.mm", "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI")
                putExtra("rawUrl", oauthUrl)
                putExtra("preUsername", "gh_cc1856f69ee1")
                putExtra("preChatName", "gh_cc1856f69ee1")
                putExtra("pre_username", "gh_cc1856f69ee1")
                putExtra("geta8key_username", "gh_cc1856f69ee1")
                putExtra("prePublishId", "custom_menu")
                putExtra("KPublisherId", "custom_menu")
                putExtra("preChatTYPE", 6)
                putExtra("from_scence", 1)
                putExtra("showShare", false)
                putExtra("show_bottom", false)
                // WechatMultiWebview 方案：H5 打开在独立任务栈，不抢微信前台
                // NEW_DOCUMENT + MULTIPLE_TASK → MMWebViewUI 独立窗口，用户无感
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    or android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
            }
            ctx.startActivity(intent)
            XposedBridge.log("$TAG rebuild: OAuth MMWebViewUI dispatched (NEW_DOCUMENT+MULTIPLE_TASK, pool steal)")
        } catch (e: Exception) {
            XposedBridge.log("$TAG rebuild failed: ${e.message}")
        }
    }
    /** POST operationCommit via WebView (for rebind JKCB). */
    suspend fun operationCommit(payload: String) {
        acquireWakeLock()
        val escaped = escapeJs(payload)
        fireJs("window.__ctwing.operationCommit('$escaped')")
    }

    /** Release wake lock after result is read (called by router when done). */
    fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {}
    }

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    /** Hold a wake lock so the WebView's JS engine stays alive during screen-off.
     *  Uses SCREEN_DIM_WAKE_LOCK: screen stays on but dimmed (user barely notices),
     *  keeping X5 renderer thawed for the duration of the query, then released.
     *  Fully trigger-based — no background polling. */
    private fun acquireWakeLock() {
        try {
            releaseWakeLock()
            val ctx = try {
                val atClass = Class.forName("android.app.ActivityThread")
                val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
                currentAt.javaClass.getMethod("getApplication").invoke(currentAt) as? Context
            } catch (_: Exception) { null }
            if (ctx == null) { XposedBridge.log("$TAG wake lock: no app context"); return }
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK
                        or PowerManager.ACQUIRE_CAUSES_WAKEUP
                        or PowerManager.ON_AFTER_RELEASE,
                    "ctwing:autoreply"
                )
                wl.setReferenceCounted(false)
                wl.acquire(50_000L)
                wakeLock = wl
                XposedBridge.log("$TAG wake lock acquired (SCREEN_DIM, 50s)")
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG wake lock failed: ${e.message}")
        }
    }

    /** Execute JS via evaluateJavascript (null callback) — fire-and-forget.
     *  WebView 引用丢失时先静默重建，再执行。 */
    private suspend fun fireJs(script: String) {
        val clean = script.removePrefix("javascript:")
        var wv = CtwingWebViewHook.currentWebView()
        if (wv == null) {
            XposedBridge.log("$TAG fireJs: targetView null, rebuilding H5 silently…")
            if (!ensureReady(40_000L)) {
                XposedBridge.log("$TAG fireJs: rebuild failed, abort")
                return
            }
            wv = CtwingWebViewHook.currentWebView() ?: run {
                XposedBridge.log("$TAG fireJs: still null after rebuild, abort")
                return
            }
        }
        // Use evaluateJavascript — the SAME API as evaluateJsForResult,
        // so payload-injected window.__ctwing lives in the same V8 isolate
        // and is visible to subsequent reads.
        XposedBridge.log("$TAG fireJs: executing ${clean.take(60)}")
        CtwingWebViewHook.evaluateJs(wv, clean)
    }

    /** True if the SPA bridge is accessible (local or via IPC). */
    fun isReady(): Boolean = CtwingWebViewHook.isInjected()

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    private fun escapeJs(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
}