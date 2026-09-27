package dev.example.autoreply.ctwing

import android.os.Environment
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hooks WeChat's network layer to capture CTWing traffic and dump SPA bundles.
 */
object CtwingNetworkHook {

    private const val TAG = "[CTWing-Net]"
    private const val CTWING_HOST = "tywlonestop.ctwing.cn"

    private val savedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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

            XposedHelpers.findAndHookMethod(
                realCall, "execute",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        handleCallResult(param.thisObject, param.result)
                    }
                }
            )
            log("hooked okhttp3.RealCall.execute")

            XposedHelpers.findAndHookMethod(
                realCall, "getResponseWithInterceptorChain",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        handleCallResult(param.thisObject, param.result)
                    }
                }
            )
            log("hooked okhttp3.RealCall.getResponseWithInterceptorChain")
        }.onFailure { log("okhttp hook failed: ${it.message}") }

        // ---- Fallback: hook java.net.URL.openConnection (HttpURLConnection) ----
        // The X5 WebView may use HttpURLConnection for subresource loads.
        runCatching {
            XposedHelpers.findAndHookMethod(
                "java.net.URL", classLoader, "openConnection",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val thisUrl = param.thisObject?.toString() ?: return
                        if (!thisUrl.contains(CTWING_HOST)) return
                        val conn = param.result ?: return
                        hookConnectionResponse(conn, thisUrl)
                    }
                }
            )
            log("hooked java.net.URL.openConnection")
        }.onFailure { log("URL.openConnection hook failed: ${it.message}") }
    }

    /** Wrap an HttpURLConnection to capture response body for JS/CSS bundles. */
    private fun hookConnectionResponse(conn: Any, url: String) {
        if (!url.contains(CTWING_HOST)) return
        val isAsset = url.contains("/js/") || url.contains("/css/") ||
            url.contains("/static/") || url.endsWith(".js") || url.endsWith(".css")
        if (!isAsset) return
        if (!savedUrls.add(url)) return
        log("URL hook: $url")

        // Hook getInputStream to read the response
        runCatching {
            val connClass = conn.javaClass
            XposedHelpers.findAndHookMethod(connClass, "getInputStream",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stream = param.result as? java.io.InputStream ?: return
                        // Read fully on a background thread
                        Thread {
                            try {
                                val bytes = stream.readBytes()
                                saveBundle(url, bytes, "")
                            } catch (e: Exception) {
                                log("read failed $url: ${e.message}")
                            }
                        }.start()
                    }
                }
            )
        }.onFailure { /* best-effort */ }
    }

    private fun saveBundle(url: String, bytes: ByteArray, contentType: String) {
        try {
            val dir = File(Environment.getExternalStorageDirectory(), "dsh_ctwing_bundles")
            dir.mkdirs()
            val ext = when {
                contentType.contains("javascript") || url.endsWith(".js") -> ".js"
                contentType.contains("css") || url.endsWith(".css") -> ".css"
                url.contains("/js/") -> ".js"
                url.contains("/css/") -> ".css"
                else -> ".dat"
            }
            val path = (java.net.URI(url).path ?: "/unknown")
                .replace("/", "_").replace(".", "_").trim('_').take(80)
            val file = File(dir, "${path}_${bytes.size}$ext")
            file.writeBytes(bytes)
            log("saved bundle: ${file.name} (${bytes.size}B)")
        } catch (e: Exception) {
            log("save failed: ${e.message}")
        }
    }

    private fun handleCallResult(call: Any?, response: Any?) {
        if (call == null || response == null) return
        runCatching {
            val request = XposedHelpers.callMethod(call, "request")
            val url = request?.let { XposedHelpers.callMethod(it, "url")?.toString() } ?: return
            if (!url.contains(CTWING_HOST)) return

            val method = XposedHelpers.callMethod(request, "method")?.toString() ?: "?"
            val headers = readHeaders(request)

            headers["Authorization"]?.let { lastAuthorization = it }
            headers["Cookie"]?.let { lastCookie = it }
            CtwingJsBridge.lastAuthHeader = lastAuthorization
            CtwingJsBridge.lastCookie = lastCookie

            val code = XposedHelpers.callMethod(response, "code") as? Int ?: 0
            val body = runCatching {
                val peek = XposedHelpers.callMethod(response, "peekBody", Long.MAX_VALUE)
                XposedHelpers.callMethod(peek, "string")?.toString()
            }.getOrNull()

            // Save JS/CSS bundles
            val isAsset = url.contains("/js/") || url.contains("/css/") ||
                url.contains("/static/") || url.endsWith(".js") || url.endsWith(".css")
            if (isAsset && body != null && body.isNotEmpty() && savedUrls.add(url)) {
                log("capturing: ${url.take(120)}")
                saveBundle(url, body.toByteArray(), "")
            }

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