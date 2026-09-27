package dev.example.autoreply.ctwing

import android.webkit.JavascriptInterface
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * JS → Java bridge injected into CTWing H5 WebView.
 *
 * The injected JS calls these methods to report API results, diagnostics,
 * and captured credentials back to the LSPosed module.
 *
 * Each call with a [rid] (request id) resolves the corresponding
 * [CompletableDeferred] so the coroutine on the Java side can await the result.
 *
 * Diagnostic channels ([onDiagnostic]) write straight to Xposed logcat so the
 * developer can observe the SPA's request mechanism (axios vs fetch, where
 * encryption happens, what the plaintext looks like) without a round-trip.
 */
class CtwingJsBridge {

    companion object {
        private const val TAG = "[CTWing-Bridge]"

        /** In-flight requests waiting for JS results: rid → CompletableDeferred */
        val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

        /** The most recently captured authorization header (Bearer token). */
        @Volatile
        var lastAuthHeader: String? = null

        /** The most recently captured cookie string. */
        @Volatile
        var lastCookie: String? = null

        /** Whether the CTWing SPA has signalled it is fully loaded and ready. */
        @Volatile
        var isPageReady: Boolean = false

        /** The last reported page name (e.g. "service/basic", "query/card", "diagnose"). */
        @Volatile
        var currentPage: String? = null

        /**
         * The last JSON structure captured by the diagnostic hooks. This is
         * how we discover the real request/response shape. Persisted here so
         * the Java side can introspect it programmatically.
         */
        @Volatile
        var lastDiagnostic: String? = null

        /**
         * The most recent CTWing API response body captured by XHR/fetch
         * hooks (FULL text, not truncated). Set by the injected JS when a
         * ctwing.cn XHR completes. Router reads this after uiQuery triggers
         * a request.
         */
        @Volatile
        var lastApiResponse: String? = null

        /** The three AES passphrases captured by the CryptoJS hook.
         *  These are device-constant (fingerprint-based), captured once
         *  after login and reused for all API calls. */
        @Volatile
        var aesKey1: String? = null   // 层1: obfuscation key
        @Volatile
        var aesKey2: String? = null   // 层2: chain hash key
        @Volatile
        var aesKey3: String? = null   // 层3: payload encryption key
        @Volatile
        var bondId: String? = null    // ctl-dync-ct-bond header value

        /** True once all three AES keys + bondId are captured. */
        fun hasKeys(): Boolean = aesKey1 != null && aesKey2 != null && aesKey3 != null
    }

    // ==================================================================
    //  Crypto key capture — called by injected JS hook
    // ==================================================================

    /** Called by the CryptoJS.AES.encrypt hook when it captures a key. */
    @JavascriptInterface
    fun onCryptoKey(layer: String, keyHex: String) {
        when (layer) {
            "1" -> { aesKey1 = keyHex; dlog("aesKey1=$keyHex") }
            "2" -> { aesKey2 = keyHex; dlog("aesKey2=$keyHex") }
            "3" -> { aesKey3 = keyHex; dlog("aesKey3=$keyHex") }
        }
    }

    @JavascriptInterface
    fun onBondId(id: String) {
        bondId = id
        dlog("bondId=$id")
    }

    // ==================================================================
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun onApiResult(rid: String, json: String) {
        dlog("onApiResult rid=$rid len=${json.length}")
        pendingRequests.remove(rid)?.complete(json)
    }

    @JavascriptInterface
    fun onApiError(rid: String, error: String) {
        dlog("onApiError rid=$rid error=$error")
        pendingRequests.remove(rid)?.completeExceptionally(
            RuntimeException("CTWing API error: $error")
        )
    }

    /**
     * Called by injected JS to report auth token + bond cookie for native
     * HTTP fallback. Persisted so NativeHttp can reuse it without WebView.
     */
    @JavascriptInterface
    fun onAuthData(token: String, bond: String) {
        if (token.isNotBlank()) {
            lastAuthHeader = "Bearer $token"
            NativeHttp.cachedToken = token
        }
        if (bond.isNotBlank()) NativeHttp.cachedBond = bond
        lastCookie = "ACCESS_TOKEN=$token; ctl-dync-ct-bond=$bond"
        dlog("onAuthData token_len=${token.length} bond_len=${bond.length}")
    }

    // ------------------------------------------------------------------
    //  Diagnostic channel — the injected JS pushes SPA introspection here
    // ------------------------------------------------------------------

    /**
     * Receives a diagnostic record (JSON string) from the injected JS.
     * Logged to Xposed logcat and stored in [lastDiagnostic].
     */
    @JavascriptInterface
    fun onDiagnostic(json: String) {
        lastDiagnostic = json
        val preview = if (json.length > 1500) json.take(1500) + "…(total ${json.length})" else json
        XposedBridge.log("$TAG [diag] $preview")
    }

    /**
     * Receives the FULL response body of a CTWing API call captured by
     * the XHR hook. Stored in [lastApiResponse] for the router to format.
     */
    @JavascriptInterface
    fun onApiCapture(fullBody: String) {
        lastApiResponse = fullBody
        // Do NOT write to IPC here — the uiQuery path already returns its
        // result via onIpcResult/onIpcError, and writing here would race
        // with (and possibly clobber) that response. lastApiResponse is
        // for same-process reads only.
        val preview = if (fullBody.length > 800) fullBody.take(800) + "…" else fullBody
        XposedBridge.log("$TAG [api-capture] len=${fullBody.length} preview=$preview")
    }

    /**
     * IPC response channel — writes the JS result directly to the IPC
     * response file so the main process can read it. Used by the tools-
     * process poller when the WebView lives in another process.
     */
    @JavascriptInterface
    fun onIpcResult(rid: String, result: String) {
        dlog("onIpcResult rid=$rid len=${result.length}")
        val respJson = buildString {
            append("{\"rid\":\"$rid\",\"ts\":${System.currentTimeMillis()}")
            append(",\"ok\":true,\"result\":")
            append(org.json.JSONObject.quote(result))
            append("}")
        }
        CtwingIpcBridge.postResponse(respJson)
    }

    @JavascriptInterface
    fun onIpcError(rid: String, error: String) {
        dlog("onIpcError rid=$rid error=$error")
        val errJson = "{\"rid\":\"$rid\",\"ts\":${System.currentTimeMillis()},\"ok\":false,\"error\":\"${error.replace("\"","\\\"")}\"}"
        CtwingIpcBridge.postResponse(errJson)
    }

    // ------------------------------------------------------------------
    //  Page lifecycle
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun onPageReady(pageName: String) {
        dlog("onPageReady page=$pageName")
        isPageReady = true
        currentPage = pageName
        // token 上报由 JS ready() 内部定时 pushAuth 完成
    }

    /**
     * Receives the full page HTML (outerHTML), saves it to /sdcard/ for
     * offline analysis. The HTML contains <script src="..."> tags pointing
     * to the SPA JS bundles we need to reverse-engineer.
     */
    @JavascriptInterface
    fun onDumpHtml(pageName: String, html: String) {
        dlog("onDumpHtml page=$pageName html_len=${html.length}")
        try {
            val baseDir: java.io.File = CtwingIpcBridge.wechatDataDir?.let {
                java.io.File(it, "dsh_ctwing_bundles")
            } ?: java.io.File(android.os.Environment.getExternalStorageDirectory(), "dsh_ctwing_bundles")
            baseDir.mkdirs()
            val safeName = pageName.replace("/", "_").replace("\\", "_").replace(":", "_").take(60)
            val file = java.io.File(baseDir, "page_${safeName}_${System.currentTimeMillis()}.html")
            file.writeText(html)
            dlog("html saved: ${file.absolutePath} (${html.length}B)")
        } catch (e: Exception) {
            dlog("html save failed: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onScriptUrls(urlsJson: String) {
        dlog("onScriptUrls len=${urlsJson.length}")
        try {
            val baseDir: java.io.File = CtwingIpcBridge.wechatDataDir?.let {
                java.io.File(it, "dsh_ctwing_bundles")
            } ?: java.io.File(android.os.Environment.getExternalStorageDirectory(), "dsh_ctwing_bundles")
            baseDir.mkdirs()
            val file = java.io.File(baseDir, "script_urls_${System.currentTimeMillis()}.json")
            file.writeText(urlsJson)
            dlog("script urls saved: ${file.absolutePath}")
        } catch (e: Exception) {
            dlog("script urls save failed: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onPageUnloaded() {
        dlog("onPageUnloaded")
        isPageReady = false
        currentPage = null
    }

    // ------------------------------------------------------------------
    //  Credential capture — the injected JS reports captured auth data
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun onCredential(authHeader: String, cookie: String) {
        dlog("onCredential auth=${authHeader.take(30)}... cookie_len=${cookie.length}")
        lastAuthHeader = authHeader
        lastCookie = cookie
    }

    // ------------------------------------------------------------------
    //  Logging (for debugging in Xposed logcat)
    // ------------------------------------------------------------------

    @JavascriptInterface
    fun log(msg: String) {
        XposedBridge.log("$TAG $msg")
    }

    private fun dlog(msg: String) {
        XposedBridge.log("$TAG $msg")
    }
}