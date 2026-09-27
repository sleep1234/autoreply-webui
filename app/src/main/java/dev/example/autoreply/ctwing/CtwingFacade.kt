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

    private suspend fun awaitDeferred(
        rid: String,
        deferred: kotlinx.coroutines.CompletableDeferred<String>
    ): String {
        try {
            val result = withTimeout(TIMEOUT_MS) { deferred.await() }
            XposedBridge.log("$TAG callJs [$rid] OK len=${result.length}")
            return result
        } catch (e: TimeoutCancellationException) {
            XposedBridge.log("$TAG callJs [$rid] TIMEOUT")
            CtwingJsBridge.pendingRequests.remove(rid)
            throw RuntimeException("CTWing API call timed out after ${TIMEOUT_MS}ms")
        } catch (e: Exception) {
            XposedBridge.log("$TAG callJs [$rid] FAILED: ${e.message}")
            CtwingJsBridge.pendingRequests.remove(rid)
            throw e
        }
    }

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    suspend fun dump(): String = callJs("window.__ctwing.dump()")
    suspend fun recon(): String = callJs("window.__ctwing.recon()")
    suspend fun reconReport(): String = callJs("window.__ctwing.reconReport()")
    suspend fun discover(): String = callJs("window.__ctwing.discover()")
    suspend fun uiDump(): String = callJs("window.__ctwing.uiDump()")

    /**
     * Actively PULL token + bond from the SPA via evaluateJavascript.
     * Does NOT rely on JS bridge push (addJavascriptInterface is unreliable
     * in X5 sandbox). Returns true if token was captured.
     */
    suspend fun pullToken(): Boolean {
        return try {
            // Inline self-contained JS — does NOT depend on window.__ctwing.
            // Reads ACCESS_TOKEN from cookie/localStorage/sessionStorage directly.
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
            val obj = runCatching {
                // 先尝试直接解析，失败则剥一层引号再解析
                org.json.JSONObject(raw)
            }.recoverCatching {
                org.json.JSONObject(org.json.JSONTokener(raw).nextValue() as String)
            }.getOrNull()
            val token = obj?.optString("token", "") ?: ""
            val bond = obj?.optString("bond", "") ?: ""
            val cookie = obj?.optString("cookie", "") ?: ""
            if (token.isNotBlank()) {
                NativeHttp.cachedToken = token
                XposedBridge.log("$TAG pullToken: token captured (${token.length} chars)")
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
        return rebuildAndWait(timeoutMs)
    }

    /**
     * Reload OAuth on the pool WebView without starting a new Activity.
     * The stolen WebView lives in the transparent overlay window so
     * loadUrl works even with screen off. After the redirect chain
     * completes (~8s), the SPA's 免密登录 will write a fresh token to cookie.
     */
    suspend fun reloadOAuthOnPool() {
        val wv = WebViewPool.webView
        if (wv == null) {
            XposedBridge.log("$TAG reloadOAuth: pool WebView is null, doing full rebuild")
            forceRebuild(40_000L)
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
            wv.javaClass.getMethod("loadUrl", String::class.java).invoke(wv, oauthUrl)
            // SPA will auto-complete auth + write fresh ACCESS_TOKEN cookie in ~10s
        } catch (e: Exception) {
            XposedBridge.log("$TAG reloadOAuth failed: ${e.message}, fallback to rebuild")
            forceRebuild(40_000L)
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
        XposedBridge.log("$TAG H5 appears dead, rebuilding silently...")
        acquireWakeLock()
        CtwingJsBridge.isPageReady = false
        CtwingJsBridge.lastApiResponse = null
        CtwingWebViewHook.resetForRebuild()
        CtwingWebViewHook.silentMoveBack = true
        rebuildH5()

        // Poll findForHost every 3s until OAuth → SPA completes
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(3_000L)
            if (CtwingWebViewHook.findForHost("tywlonestop.ctwing.cn") != null) {
                CtwingWebViewHook.silentMoveBack = false
                XposedBridge.log("$TAG H5 rebuild: found via findForHost")
                return true
            }
        }
        CtwingWebViewHook.silentMoveBack = false
        XposedBridge.log("$TAG H5 rebuild: timeout")
        return false
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
                // Simulate 公众号 menu entry (triggers silent OAuth)
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
                // Screen is ON during pre-init (WeChat just started), so
                // NO_USER_ACTION is enough — Activity renders in background,
                // never steals focus, and gets stolen into the overlay pool.
                // Lock-screen queries use reloadOAuthOnPool() which issues
                // loadUrl directly on the pool WebView, no Activity needed.
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    or 0x00010000  // FLAG_ACTIVITY_NO_USER_ACTION
                )
            }
            ctx.startActivity(intent)
            XposedBridge.log("$TAG rebuild: OAuth MMWebViewUI dispatched (NO_USER_ACTION, pool steal)")
        } catch (e: Exception) {
            XposedBridge.log("$TAG rebuild failed: ${e.message}")
        }
    }
    suspend fun extractCredentials(): String = callJs("window.__ctwing.extractCredentials()")

    /** Fire-and-forget via evaluateJavascript. Used by capture/recon probes. */
    suspend fun queryCard(iccid: String): String {
        acquireWakeLock()
        fireJs("window.__ctwing.queryCard('${escapeJs(iccid)}')")
        return "dispatched"
    }

    /** Query basicInfo via WebView (for rebind: extract bindImei/orderNumber/sessionId). */
    suspend fun queryBasicInfo(cardNo: String, type: String) {
        acquireWakeLock()
        fireJs("window.__ctwing.queryBasicInfo('${escapeJs(cardNo)}','$type')")
    }

    /** POST operationCommit via WebView (for rebind JKCB). */
    suspend fun operationCommit(payload: String) {
        acquireWakeLock()
        val escaped = escapeJs(payload)
        fireJs("window.__ctwing.operationCommit('$escaped')")
    }

    /** Parse basicInfo response → BindInfo (bindImei, lastImei, orderNumber, sessionId, bindType). */
    fun parseBindInfo(raw: String): BindInfo? {
        return try {
            // readApiResponses returns evaluateJavascript JSON-encoded string:
            // "{\"code\":0,\"data\":{...}}" → unescape first
            var body = raw.trim()
            if (body.startsWith("\"") && body.endsWith("\"")) {
                body = body.substring(1, body.length - 1)
            }
            body = body.replace("\\\"", "\"").replace("\\\\", "\\")
            val json = org.json.JSONObject(body)
            val data = json.optJSONObject("data") ?: return null
            BindInfo(
                bindImei = data.optString("bindImei", ""),
                lastImei = data.optString("lastImei", ""),
                orderNumber = data.optString("id", "").ifBlank { data.optString("orderNumber", "") },
                sessionId = data.optString("sessionId", ""),
                bindType = data.optString("bindType", "")
            )
        } catch (e: Exception) {
            XposedBridge.log("$TAG parseBindInfo failed: ${e.message}")
            null
        }
    }

    data class BindInfo(
        val bindImei: String,
        val lastImei: String,
        val orderNumber: String,
        val sessionId: String,
        val bindType: String
    )

    /**
     * Self-contained sync-XHR query — ONE evaluateJavascript call.
     * Reads cookie, fires sync XMLHttpRequest, returns JSON result directly.
     * No window.__ctwing / cross-isolate state needed.
     */
    suspend fun queryCardSync(iccid: String, type: String): String? {
        acquireWakeLock()
        val script = """
            (function(){
                try {
                    var id = '${escapeJs(iccid)}';
                    var t = '$type';
                    var token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || '';
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', '/webapp-font/admin-api/bpm/service-assistant/querySimBaseInfo?type='+t+'&id='+id, false);
                    xhr.setRequestHeader('Accept', 'application/json, text/plain, */*');
                    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
                    xhr.send();
                    return JSON.stringify({status: xhr.status, body: xhr.responseText});
                } catch(e) { return JSON.stringify({error: e.message || String(e)}); }
            })()
        """.trimIndent()
        return try {
            val raw = callJsLocal(script)
            XposedBridge.log("$TAG queryCardSync raw len=${raw?.length ?: 0}: ${raw?.take(100) ?: "null"}")
            raw
        } catch (e: Exception) {
            XposedBridge.log("$TAG queryCardSync failed: ${e.message}")
            null
        }
    }
    suspend fun diagnoseCard(iccid: String): String {
        CtwingJsBridge.lastApiResponse = null
        acquireWakeLock()
        fireJs("window.__ctwing.diagnoseCard('${escapeJs(iccid)}')")
        return "dispatched"
    }

    /** Sync-XHR diagnosis — ONE evaluateJavascript call, returns JSON directly (like queryCardSync). */
    suspend fun diagnoseCardSync(iccid: String): String? {
        acquireWakeLock()
        val script = """
            (function(){
                try {
                    var id = '${escapeJs(iccid)}';
                    if (id.length === 20 && id.charAt(0) === '8') id = id.substring(0, 19);
                    var type = 'iccid';
                    if (/^1[0-9]{10,12}${'$'}/.test(id)) type = 'msisdn';
                    else if (/^\d{15}${'$'}/.test(id)) type = 'imsi';
                    var token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || '';
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', '/webapp-font/admin-api/bpm/service-assistant/intelligentDiagnosis?type='+type+'&id='+id, false);
                    xhr.setRequestHeader('Accept', 'application/json, text/plain, */*');
                    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
                    xhr.send();
                    return JSON.stringify({status: xhr.status, body: xhr.responseText});
                } catch(e) { return JSON.stringify({error: e.message || String(e)}); }
            })()
        """.trimIndent()
        return try {
            callJsLocal(script)
        } catch (e: Exception) {
            XposedBridge.log("$TAG diagnoseCardSync failed: ${e.message}")
            null
        }
    }

    /** Sync-XHR operationCommit — ONE evaluateJavascript call, returns JSON directly. */
    suspend fun operationCommitSync(payload: String): String? {
        acquireWakeLock()
        val escaped = escapeJs(payload)
        val script = """
            (function(){
                try {
                    var token = (document.cookie.match(/ACCESS_TOKEN=([^;]+)/)||[])[1] || '';
                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', '/webapp-font/admin-api/bpm/service-assistant/operationCommit', false);
                    xhr.setRequestHeader('Content-Type', 'application/json');
                    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
                    xhr.send('$escaped');
                    return JSON.stringify({status: xhr.status, body: xhr.responseText});
                } catch(e) { return JSON.stringify({error: e.message || String(e)}); }
            })()
        """.trimIndent()
        return try {
            callJsLocal(script)
        } catch (e: Exception) {
            XposedBridge.log("$TAG operationCommitSync failed: ${e.message}")
            null
        }
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
     *  Falls back to lazy injection if the WebView ref was lost (e.g. after
     *  OAuth re-auth where wxLogin skipped the initial injection). */
    private fun fireJs(script: String) {
        val clean = script.removePrefix("javascript:")
        var wv = CtwingWebViewHook.currentWebView()
        if (wv == null) {
            XposedBridge.log("$TAG fireJs: targetView null, trying lazy inject")
            if (CtwingWebViewHook.isInjected()) {
                wv = CtwingWebViewHook.currentWebView() ?: run {
                    XposedBridge.log("$TAG fireJs: still null after lazy inject, abort")
                    return
                }
            } else {
                XposedBridge.log("$TAG fireJs: isInjected false, re-scanning")
                CtwingWebViewHook.forceRescan()
                wv = CtwingWebViewHook.currentWebView() ?: run {
                    XposedBridge.log("$TAG fireJs: no WebView after rescan, abort")
                    return
                }
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