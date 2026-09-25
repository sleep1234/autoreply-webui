package dev.example.autoreply.ctwing

import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.*
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

    // ------------------------------------------------------------------
    //  callJs — dispatches via local WebView or IPC
    // ------------------------------------------------------------------

    private suspend fun callJs(script: String): String {
        // If the WebView is in THIS process, use it directly (fast path).
        if (CtwingWebViewHook.isInjected()) {
            return callJsLocal(script)
        }

        // WebView is in another process — use IPC.
        val dir = CtwingIpcBridge.wechatDataDir
        if (dir == null) throw IllegalStateException("IPC data dir not set")
        return callJsIpc(script)
    }

    // ----------------------------------------------------------------
    //  Local path (WebView in same process)
    // ----------------------------------------------------------------

    private suspend fun callJsLocal(script: String): String {
        val rid = "r${ridGen.incrementAndGet()}"

        // The addJavascriptInterface bridge is NOT visible to already-loaded
        // pages, so we read results via evaluateJavascript polling on a
        // window-global result map instead. The boot script defines __ctwing;
        // the call stores its (possibly async) result into
        // window.__ctwingResults[rid]. We poll that map from Java.
        val boot = CtwingJsInjector.build()
        val bridge = CtwingJsInjector.BRIDGE_NAME
        val call = """
        (function() {
            try {
                window.__ctwingResults = window.__ctwingResults || {};
                var result = $script;
                if (result && typeof result.then === 'function') {
                    result.then(function(v) {
                        window.__ctwingResults['$rid'] = { ok: true, v: (typeof v === 'string' ? v : JSON.stringify(v)) };
                    }).catch(function(e) {
                        window.__ctwingResults['$rid'] = { ok: false, e: String(e && e.message || e) };
                    });
                } else {
                    window.__ctwingResults['$rid'] = { ok: true, v: (typeof result === 'string' ? result : JSON.stringify(result || {})) };
                }
            } catch(e) {
                window.__ctwingResults['$rid'] = { ok: false, e: String(e && e.message || e) };
            }
        })();
        """.trimIndent()

        val combined = "$boot\n$call"

        val wv = CtwingWebViewHook.currentWebView()
            ?: throw IllegalStateException("WebView reference lost")
        CtwingWebViewHook.evaluateJs(wv, combined)

        // Poll window.__ctwingResults[rid] from the Java side.
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val pollScript = "JSON.stringify((window.__ctwingResults||{})['$rid']||null)"
            val raw = CtwingWebViewHook.evaluateJsForResult(wv, pollScript, 3000)
            if (raw != null && raw != "null" && raw != "\"\"" && raw.isNotBlank()) {
                // evaluateJavascript may or may not double-encode.
                // Try direct parse first; fall back to JSONArray unwrap.
                val obj = try {
                    org.json.JSONObject(raw)
                } catch (_: Exception) {
                    try {
                        org.json.JSONObject(org.json.JSONArray("[$raw]").getString(0))
                    } catch (_: Exception) { continue }
                }
                val ok = obj.optBoolean("ok", false)
                if (ok) {
                    val v = obj.optString("v", "")
                    XposedBridge.log("$TAG callJs [$rid] OK len=${v.length}")
                    return v
                } else {
                    val err = obj.optString("e", "unknown")
                    throw RuntimeException("CTWing API error: $err")
                }
            }
            kotlinx.coroutines.delay(200L)
        }

        throw RuntimeException("CTWing API call timed out after ${TIMEOUT_MS}ms")
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
    suspend fun reportCredentials(): String = callJs("window.__ctwing.reportCredential()")
    suspend fun uiQuery(iccid: String): String =
        callJs("window.__ctwing.uiQuery('${escapeJs(iccid)}')")
    suspend fun queryCard(iccid: String): String =
        callJs("window.__ctwing.rawQueryCard('${escapeJs(iccid)}')")
    suspend fun getBasicInfo(iccid: String): String =
        callJs("window.__ctwing.rawBasicInfo('${escapeJs(iccid)}')")
    suspend fun diagnose(iccid: String): String =
        callJs("window.__ctwing.rawDiagnose('${escapeJs(iccid)}')")
    suspend fun rebind(iccid: String, imei: String): String =
        callJs("window.__ctwing.rawRebind('${escapeJs(iccid)}', '${escapeJs(imei)}')")

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