package dev.example.autoreply.ctwing

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hooks WeChat's okhttp network layer to capture CTWing traffic.
 *
 * The H5 runs inside WeChat's WebView, whose HTTP requests are ultimately
 * performed by WeChat's own okhttp instance (the same stack the WebView
 * proxy uses). By hooking okhttp3 `RealCall.execute` / `Call.enqueue`, we
 * can observe:
 *
 *   - the request URL + headers (captures Authorization / Cookie credentials)
 *   - the response body (which, at this layer, is the CTROBF1 ciphertext for
 *     API responses — but we still capture it for offline analysis)
 *
 * This complements [CtwingWebViewHook], which captures the *decrypted*
 * plaintext by injecting JS into the WebView. Together they give us both
 * the credentials and the plaintext API data.
 */
object CtwingNetworkHook {

    private const val TAG = "[CTWing-Net]"
    private const val CTWING_HOST = "tywlonestop.ctwing.cn"

    /** Callback invoked with each intercepted CTWing request/response pair. */
    interface Listener {
        fun onCtwingRequest(method: String, url: String, headers: Map<String, String>, body: String?)
        fun onCtwingResponse(url: String, status: Int, body: String?)
    }

    val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var lastAuthorization: String? = null

    @Volatile
    var lastCookie: String? = null

    /** Hook okhttp3.RealCall#execute and #enqueue to observe CTWing traffic. */
    fun hook(classLoader: ClassLoader) {
        runCatching {
            val realCall = XposedHelpers.findClass("okhttp3.RealCall", classLoader)

            // ---- execute (synchronous) ----
            XposedHelpers.findAndHookMethod(
                realCall, "execute",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        handleCallResult(param.thisObject, param.result)
                    }
                }
            )
            log("hooked okhttp3.RealCall.execute")

            // ---- enqueue (async, callback path) ----
            val callbacks = realCall.declaredClasses
            // WeChat uses the async path heavily; hooking `getResponseWithInterceptorChain`
            // is more robust than enqueue because it's called by both paths.
            XposedHelpers.findAndHookMethod(
                realCall, "getResponseWithInterceptorChain",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        handleCallResult(param.thisObject, param.result)
                    }
                }
            )
            log("hooked okhttp3.RealCall.getResponseWithInterceptorChain")
        }.onFailure { log("hook failed: ${it.message}") }
    }

    private fun handleCallResult(call: Any?, response: Any?) {
        if (call == null || response == null) return
        runCatching {
            val request = XposedHelpers.callMethod(call, "request")
            val url = request?.let { XposedHelpers.callMethod(it, "url")?.toString() } ?: return
            if (!url.contains(CTWING_HOST)) return

            val method = XposedHelpers.callMethod(request, "method")?.toString() ?: "?"
            val headers = readHeaders(request)

            // Capture credentials
            headers["Authorization"]?.let { lastAuthorization = it }
            headers["Cookie"]?.let { lastCookie = it }
            CtwingJsBridge.lastAuthHeader = lastAuthorization
            CtwingJsBridge.lastCookie = lastCookie

            val code = XposedHelpers.callMethod(response, "code") as? Int ?: 0
            val body = runCatching {
                val peek = XposedHelpers.callMethod(response, "peekBody", Long.MAX_VALUE)
                XposedHelpers.callMethod(peek, "string")?.toString()
            }.getOrNull()

            for (l in listeners) runCatching {
                l.onCtwingResponse(url, code, body)
            }
        }.onFailure { /* best-effort */ }
    }

    private fun readHeaders(request: Any?): Map<String, String> {
        if (request == null) return emptyMap()
        return runCatching {
            val headers = XposedHelpers.callMethod(request, "headers")
            val names = XposedHelpers.callMethod(headers, "names") as? List<*>
                ?: return emptyMap()
            names.filterIsInstance<String>().associateWith { name ->
                XposedHelpers.callMethod(headers, "get", name)?.toString() ?: ""
            }
        }.getOrDefault(emptyMap())
    }

    private fun log(msg: String) = XposedBridge.log("$TAG $msg")
}