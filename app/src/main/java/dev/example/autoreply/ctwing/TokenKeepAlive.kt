package dev.example.autoreply.ctwing

import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.*

/**
 * 定时心跳续期 ACCESS_TOKEN，防止过期。
 *
 * 每 [intervalMin] 分钟调用 basicInfo（带当前 token），
 * 利用服务端滑动过期机制保持 token 活性。
 */
object TokenKeepAlive {

    private const val TAG = "[TokenKeepAlive]"
    private const val INTERVAL_MS = 25 * 60 * 1000L  // 25 minutes

    private var job: Job? = null

    /** Start the keep-alive loop in the given [scope]. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            XposedBridge.log("$TAG started (interval=${INTERVAL_MS / 60_000L}min, first refresh in 10s)")
            // First refresh soon after start (waits for initial token capture)
            delay(10_000L)
            doRefresh()
            // Then loop on the interval
            while (isActive) {
                delay(INTERVAL_MS)
                doRefresh()
            }
        }
    }

    /** Stop the keep-alive loop. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun doRefresh() {
        // If WebView is in the pool, use its onResume to trigger OAuth refresh
        // (the stolen WebView lives in a transparent overlay window)
        val wv = WebViewPool.webView
        if (wv != null && WebViewPool.ready) {
            XposedBridge.log("$TAG refresh via pool onResume (zero foreground)")
            try {
                WebViewPool.onResume(wv)
                // After onResume, the SPA's router.push('/') will retrigger OAuth
                // and update the token cookie within ~10s
            } catch (e: Exception) {
                XposedBridge.log("$TAG pool onResume failed: ${e.message}")
            }
            return
        }
        // Fallback: direct HTTP basicInfo to slide TTL
        val token = NativeHttp.cachedToken ?: return
        XposedBridge.log("$TAG refreshing token via basicInfo...")
        try {
            val body = NativeHttp.basicInfo(token, "iccid", "89860620140020723456")
            val newToken = try {
                val json = org.json.JSONObject(body)
                json.optString("token", "").ifBlank {
                    json.optJSONObject("data")?.optString("token", "") ?: ""
                }
            } catch (_: Exception) { "" }
            if (newToken.isNotBlank()) {
                NativeHttp.cachedToken = newToken
                XposedBridge.log("$TAG token refreshed (${newToken.length} chars)")
            } else {
                XposedBridge.log("$TAG basicInfo returned, server-side sliding likely")
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG basicInfo failed: ${e.message}")
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                NativeHttp.cachedToken = null
                XposedBridge.log("$TAG token expired, cleared cache")
            }
        }
    }
}