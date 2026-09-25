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
    }

    // ------------------------------------------------------------------
    //  API result callback — resolves pendingRequests[rid]
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