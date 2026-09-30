package dev.example.autoreply.ctwing

import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.*

/**
 * Token 保活调度器。
 *
 * 持续检测 WebView 存活，并负责在正确时机刷新 token：
 *   1. WebView 死了 → 立即 forceRebuild（重建 H5 + 拉新 token）
 *   2. WebView 存活 且 距上次刷新 ≥ 1 小时 → forceRebuild（reload OAuth 换新 token）
 *
 * 注意：真正换 token 的活儿是 [CtwingFacade.forceRebuild] 干的（
 * 存活走 reloadOAuthOnPool，死了走 rebuildAndWait）。本类只负责「调度」。
 */
object TokenKeepAlive {

    private const val TAG = "[TokenKeepAlive]"
    private const val CHECK_INTERVAL_MS = 30 * 1000L          // 每 30s 检测一次 WebView 存活
    private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L   // 存活时每 30 分钟刷新一次 token

    private var job: Job? = null

    /** 上次成功刷新 token 的时间戳（毫秒）。 */
    @Volatile
    private var lastRefreshAt = 0L

    /** Start the keep-alive scheduler in the given [scope]. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            XposedBridge.log("$TAG started (check=${CHECK_INTERVAL_MS / 1000}s, refresh=${REFRESH_INTERVAL_MS / 60_000}min)")
            // 首次启动延迟 15s，等 preInitH5 偷取 WebView 完成后再开始检测
            delay(15_000L)
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    XposedBridge.log("$TAG tick failed: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Stop the scheduler. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** 单次检测：判断 WebView 死活 + 是否需要刷新。 */
    internal suspend fun tick() {
        val alive = CtwingWebViewHook.findForHost("tywlonestop.ctwing.cn") != null
        val due = (System.currentTimeMillis() - lastRefreshAt) >= REFRESH_INTERVAL_MS

        if (!alive) {
            // WebView 死了 → 立即重建
            XposedBridge.log("$TAG WebView dead, forcing rebuild now")
            val ok = CtwingFacade.forceRebuild(40_000L)
            if (ok) {
                CtwingFacade.pullToken()
                lastRefreshAt = System.currentTimeMillis()
                XposedBridge.log("$TAG rebuilt + token pulled")
            } else {
                XposedBridge.log("$TAG rebuild failed, will retry next tick")
            }
            return
        }

        // WebView 存活：到 30 分钟才刷新（reload OAuth 换新 token）
        if (due) {
            XposedBridge.log("$TAG WebView alive, refreshing token (30min interval)")
            val ok = CtwingFacade.forceRebuild(40_000L)
            if (ok) {
                CtwingFacade.pullToken()
                lastRefreshAt = System.currentTimeMillis()
                XposedBridge.log("$TAG token refreshed on schedule")
            } else {
                XposedBridge.log("$TAG scheduled refresh failed, will retry next tick")
            }
        }
        // 否则：存活且未到期，什么都不做
    }
}