package dev.example.autoreply.ctwing

import android.os.Environment
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Captures every JS/CSS/JSON bundle loaded by the CTWing H5 inside WeChat.
 *
 * Strategy: Hook WebViewClient.shouldInterceptRequest (called for EACH
 * subresource). When a request targets ctwing.cn and ends with .js/.css,
 * we immediately re-fetch that URL ourselves and save the body to
 * /sdcard/dsh_ctwing_bundles/.
 *
 * This runs ONCE during page load — no bridge, no JS, no callbacks needed.
 */
object CtwingBundleCapture {

    private const val TAG = "[CTWing-Bundle]"
    private const val BUNDLE_DIR = "dsh_ctwing_bundles"

    private val outputDir: File by lazy {
        File(Environment.getExternalStorageDirectory(), BUNDLE_DIR).also {
            it.mkdirs()
            log("output dir: ${it.absolutePath}")
        }
    }

    /** URLs we've already captured (dedup). */
    private val capturedUrls = mutableSetOf<String>()

    fun hook(classLoader: ClassLoader) {
        // X5/TBS WebView uses com.tencent.smtt.sdk.WebViewClient (or com.tencent.xweb.WebViewClient)
        // Try multiple class names.
        for (clsName in listOf(
            "com.tencent.smtt.sdk.WebViewClient",
            "com.tencent.xweb.WebViewClient",
            "android.webkit.WebViewClient"
        )) {
            hookShouldIntercept(classLoader, clsName)
        }
    }

    private fun hookShouldIntercept(classLoader: ClassLoader, clsName: String) {
        runCatching {
            val wvcCls = XposedHelpers.findClass(clsName, classLoader) ?: return
            // Hook ALL shouldInterceptRequest overloads
            var hooked = 0
            for (method in wvcCls.declaredMethods) {
                if (method.name != "shouldInterceptRequest") continue
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (arg in param.args) {
                            val url = when (arg) {
                                is String -> arg
                                else -> try {
                                    arg?.javaClass?.getMethod("getUrl")?.invoke(arg)?.toString()
                                } catch (_: Exception) {
                                    arg?.toString()
                                }
                            }
                            if (url != null && url.contains("tywlonestop.ctwing.cn")) {
                                interceptAndSave(url)
                                break
                            }
                        }
                    }
                })
                hooked++
            }
            if (hooked > 0) log("hooked $clsName.shouldInterceptRequest ($hooked overloads)")
        }.onFailure { /* class not found, try next */ }
    }

    private fun interceptAndSave(urlStr: String) {
        if (!urlStr.contains("tywlonestop.ctwing.cn")) return
        if (!urlStr.endsWith(".js") && !urlStr.endsWith(".css") &&
            !urlStr.endsWith(".json") && !urlStr.endsWith(".html") &&
            !urlStr.contains("/js/") && !urlStr.contains("/css/") &&
            !urlStr.contains("/static/")) return

        synchronized(capturedUrls) {
            if (capturedUrls.contains(urlStr)) return
            capturedUrls.add(urlStr)
        }

        log("capturing: ${urlStr.take(150)}")

        // Offload to background thread to not block the WebView
        Thread {
            try {
                saveUrlContent(urlStr)
            } catch (e: Exception) {
                log("save failed for ${urlStr.take(80)}: ${e.message}")
            }
        }.start()
    }

    private fun saveUrlContent(urlStr: String) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", 
            "Mozilla/5.0 (Linux; Android 14; 2311DRK48C) AppleWebKit/537.36 Chrome/121.0.0.0 Mobile Safari/537.36")

        // Copy session cookies from WebView? Not needed for static bundles.

        val status = conn.responseCode
        val contentType = conn.contentType ?: ""
        val input: InputStream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream ?: return
        }

        val body = input.readBytes()
        input.close()
        conn.disconnect()

        // Generate filename from URL path
        val path = try {
            val u = java.net.URI(urlStr)
            (u.path ?: "/index").replace("/", "_").replace(".", "_")
                .trim('_').take(80)
        } catch (_: Exception) { "unknown_${System.currentTimeMillis()}" }

        val ext = when {
            contentType.contains("javascript") -> ".js"
            contentType.contains("css") -> ".css"
            contentType.contains("json") -> ".json"
            contentType.contains("html") -> ".html"
            urlStr.endsWith(".js") -> ".js"
            urlStr.endsWith(".css") -> ".css"
            else -> ".dat"
        }
        val filename = "${path}_${body.size}${ext}"

        val file = File(outputDir, filename)
        file.writeBytes(body)
        log("saved: ${file.name} (${body.size}B, status=$status, type=$contentType)")
    }

    // ============================================================
    // Also hook onPageFinished to enumerate inline scripts
    // ============================================================
    fun hookPageFinished(classLoader: ClassLoader) {
        runCatching {
            val wvcCls = XposedHelpers.findClass(
                "com.tencent.xweb.WebViewClient", classLoader)
            XposedHelpers.findAndHookMethod(wvcCls, "onPageFinished",
                Any::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val url = param.args[1]?.toString() ?: return
                        if (!url.contains("tywlonestop.ctwing.cn")) return
                        log("onPageFinished: ${url.take(150)}")
                        // Try to dump page HTML
                        try {
                            val wv = param.args[0]
                            val getUrlMethod = wv?.javaClass?.getMethod("getUrl")
                            val evaluateJs = wv?.javaClass?.methods?.find { 
                                it.name == "evaluateJavascript" && it.parameterCount == 2 
                            }
                            if (evaluateJs != null) {
                                // Dump outerHTML to capture inline scripts
                                val dumpScript = """
                                    (function(){
                                        try {
                                            var h = document.documentElement.outerHTML;
                                            return h.substring(0, 50000);
                                        } catch(e) { return 'err:'+e.message; }
                                    })();
                                """.trimIndent()
                                // Note: evaluateJavascript callback is async and unreliable.
                                // We save inline scripts through the loadUrl hook instead.
                            }
                        } catch (_: Exception) {}
                    }
                })
            log("hooked X5 onPageFinished for URL tracking")
        }.onFailure { /* ok */ }
    }

    private fun log(msg: String) {
        XposedBridge.log("$TAG $msg")
    }
}