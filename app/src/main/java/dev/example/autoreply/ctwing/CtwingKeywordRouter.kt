package dev.example.autoreply.ctwing

import de.robv.android.xposed.XposedBridge
import dev.example.autoreply.hook.IncomingMessage
import dev.example.autoreply.ui.WhitelistStore
import kotlinx.coroutines.sync.withLock

/**
 * Routes incoming WeChat messages to CTWing operations.
 *
 * Keyword grammar (Chinese, case-insensitive):
 *
 *   查询 <iccid>              → card basic info
 *   诊断 <iccid>              → intelligent diagnosis
 *   重绑 <iccid> <imei>       → rebind operation
 *   查卡 <iccid>              → alias for 查询
 *
 * ICCID is 19-20 digits; IMEI is 15 digits. The router extracts these
 * tokens, calls [CtwingFacade], formats the result, and sends it back via
 * the [IWeChatHook.sendText] channel.
 */
object CtwingKeywordRouter {

    private const val TAG = "[CTWing-Router]"

    // ICCID: 19-20 digits (starts with 89). Access number (接入号): shorter
    // numeric account, typically 8-14 digits. IMEI: 15 digits.
    private val ICCID_REGEX = Regex("\\d{19,20}")
    private val ACCESS_NUM_REGEX = Regex("\\d{8,14}")
    private val IMEI_REGEX = Regex("\\d{15}")

    // 重绑幂等：记录最近重绑的卡号 → 提交时间戳（毫秒），30 秒窗口内不重复提交
    private val recentRebind = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val REBIND_IDEMPOTENT_WINDOW_MS = 30_000L

    /**
     * Attempt to handle a message. Returns true if the message matched a
     * CTWing command and a reply was (or will be) sent.
     *
     * [reply] is a suspend function that sends text back to the talker.
     */
    suspend fun tryHandle(msg: IncomingMessage, send: suspend (String) -> Unit): Boolean {
        val raw = msg.content ?: return false
        // 群聊消息 content 格式为 "发送者wxid:@昵称 正文"，剥离前缀再匹配关键词
        var content = raw.replaceFirst(Regex("^wxid_\\w+:"), "").trim()
        // 剥离群聊 @ 前缀：@昵称 后面是特殊空格（U+2005/U+0020）
        content = content.replaceFirst(Regex("^@\\S+[\\s\u2005]+"), "").trim()
        if (content.isEmpty()) return false

        val lower = content.lowercase()

        // 命令容错：用 contains 而非 startsWith，支持「帮我查一下 8986...」「8986... 查询」等
        // 但保持关键词优先级：重绑 > 诊断 > 查询，避免「查询」误匹配「重绑查询」
        val op = when {
            lower.contains("界面") || lower.contains("uidump") -> "uiDump"
            lower.contains("发现") || lower.contains("discover") -> "discover"
            lower.contains("取凭证") || lower.contains("credential") -> "credential"
            lower.contains("捕获") || lower.contains("capture") -> "capture"
            lower.contains("侦察报告") || lower.contains("reconreport") -> "reconReport"
            lower.contains("加密侦察") || lower.contains("recon") -> "recon"
            lower.contains("重绑") || lower.contains("rebind") || lower.contains("解绑") -> "rebind"
            lower.contains("诊断") || lower.contains("diagnose") -> "diagnose"
            lower.contains("查询") || lower.contains("查卡") || lower.contains("query") -> "query"
            // 状态自检 + 续期（无卡号，需在 ICCID 检查前处理）
            lower.contains("续期") || lower.contains("renew") || lower.contains("刷新token") -> "renew"
            lower.contains("状态") || lower.contains("status") || lower.contains("自检") -> "status"
            else -> return false
        }

        // NOTE: query/diagnose try native HTTP FIRST (no WebView needed),
        // then fall back to WebView via ensureReady. The global guard below
        // is only for UI-dump/discover/recon commands that need a live WebView.

        // ---- UI dump: no ICCID needed ----
        if (op == "uiDump") {
            try {
                val ui = CtwingFacade.uiDump()
                send("📱 界面结构：\n${ui.take(2000)}")
            } catch (e: Exception) {
                send("❌ UI dump失败：${e.message}")
            }
            return true
        }

        // ---- Diagnostic dump: no ICCID needed ----
        if (op == "discover") {
            try {
                val surface = CtwingFacade.discover()
                send("🔍 服务发现：\n${surface.take(1500)}")
            } catch (e: Exception) {
                send("❌ 发现失败：${e.message}")
            }
            return true
        }

        // ---- Crypto recon: no ICCID needed ----
        if (op == "recon") {
            try {
                val scan = CtwingFacade.recon()
                send("🔐 加密侦察：\n${scan.take(1500)}")
            } catch (e: Exception) {
                send("❌ 侦察失败：${e.message}")
            }
            return true
        }

        if (op == "reconReport") {
            try {
                val report = CtwingFacade.reconReport()
                send("🔐 侦察报告：\n${report.take(1500)}")
            } catch (e: Exception) {
                send("❌ 报告失败：${e.message}")
            }
            return true
        }

        // credential needs NO identifier — handle before the ICCID check.
        if (op == "credential") {
            try {
                val creds = CtwingFacade.extractCredentials()
                // Save to WeChat data dir for offline use
                try {
                    val dir = java.io.File(CtwingIpcBridge.wechatDataDir, "dsh_ctwing_bundles")
                    dir.mkdirs()
                    java.io.File(dir, "credentials.json").writeText(creds)
                    XposedBridge.log("$TAG credentials saved to credentials.json")
                } catch (e: Exception) {
                    XposedBridge.log("$TAG cred save failed: ${e.message}")
                }
                send("🔐 凭证：\n${creds.take(1500)}")
            } catch (e: Exception) {
                send("❌ 取凭证失败：${e.message}")
            }
            return true
        }

        // capture needs NO identifier — handle it before the ICCID check.
        if (op == "capture") {
            try {
                // Trigger a queryCard to force sbu1 encryption, then read recon state
                CtwingFacade.queryCard("89860620140020723456")
                kotlinx.coroutines.delay(5_000L)
                val report = CtwingFacade.reconReport()
                send("🔑 密钥捕获结果：\n${report.take(1500)}")
            } catch (e: Exception) {
                send("❌ 捕获失败：${e.message}")
            }
            return true
        }

        // ---- 状态自检：用 basicInfo 真实验证 token 是否有效 ----
        if (op == "status") {
            try {
                val wv = CtwingWebViewHook.currentWebView()
                val whitelist = WhitelistStore.list()
                val webViewAlive = wv != null

                // 真实验证 token：用 basicInfo 探活（最轻量接口）
                val token = NativeHttp.cachedToken
                val tokenValid = if (token.isNullOrBlank()) {
                    false
                } else {
                    runCatching {
                        val body = NativeHttp.basicInfo(token, "iccid", "89860620140020723456")
                        // basicInfo 成功返回 data 对象，而不是 code=401
                        !body.contains("\"code\":401")
                    }.getOrDefault(false)
                }

                // token 过期 → 强制重建换新 token
                if (!tokenValid && NativeHttp.cachedToken != null) {
                    send("⚠️ 登录态已过期，强制重建中…")
                    try {
                        val oldToken = NativeHttp.cachedToken ?: ""
                        CtwingFacade.forceRebuild(40_000L)
                        CtwingFacade.pullToken()
                        val newToken = NativeHttp.cachedToken ?: ""
                        val renewed = runCatching {
                            val body = NativeHttp.basicInfo(newToken, "iccid", "89860620140020723456")
                            !body.contains("\"code\":401")
                        }.getOrDefault(false)
                        val sb = StringBuilder()
                        sb.append("✅ 天翼物联一站式服务工具\n")
                        sb.append("· 后台服务：${if (webViewAlive) "正常" else "异常（WebView 丢失）"}\n")
                        sb.append("· 登录态：${if (renewed) "正常（已重建）" else "重建失败"}\n")
                        sb.append("· 旧 token：$oldToken\n")
                        sb.append("· 新 token：$newToken\n")
                        sb.append("· 白名单：${if (whitelist.isEmpty()) "未启用（不回复任何人）" else "已启用（${whitelist.size} 个会话）"}")
                        send(sb.toString())
                        return true
                    } catch (e2: Exception) {
                        send("⚠️ 续期失败：${e2.message}")
                        return true
                    }
                }

                val tokenState = when {
                    token.isNullOrBlank() -> "无 token（需发送「续期」）"
                    tokenValid -> "正常"
                    !tokenValid -> "已过期（建议发送「续期」）"
                    else -> "未知"
                }
                val sb = StringBuilder()
                sb.append("✅ 天翼物联一站式服务工具\n")
                sb.append("· 后台服务：${if (webViewAlive) "正常" else "异常（WebView 丢失）"}\n")
                sb.append("· 登录态：$tokenState\n")
                sb.append("· token：${token ?: "（空）"}\n")
                sb.append("· 白名单：${if (whitelist.isEmpty()) "未启用（不回复任何人）" else "已启用（${whitelist.size} 个会话）"}")
                send(sb.toString())
            } catch (e: Exception) {
                send("❌ 状态查询失败：${e.message}")
            }
            return true
        }

        // ---- 续期：强制重建 OAuth 换全新 token（不是温和续期） ----
        if (op == "renew") {
            val oldToken = NativeHttp.cachedToken
            send("🔄 正在续期（强制刷新）…")
            try {
                CtwingFacade.forceRebuild(40_000L)
                CtwingFacade.pullToken()
                val newToken = NativeHttp.cachedToken
                val sb = StringBuilder()
                sb.append("✅ 续期完成\n")
                sb.append("· token：${newToken ?: "（空）"}")
                if (oldToken != null && newToken != null && oldToken != newToken) {
                    sb.append("\n· 变化：${oldToken.take(8)}… → ${newToken.take(8)}…（已刷新）")
                } else if (oldToken == newToken) {
                    sb.append("\n⚠️ token 未变化，建议稍后重试")
                }
                send(sb.toString())
            } catch (e: Exception) {
                send("❌ 续期失败：${e.message}")
            }
            return true
        }

        // The query API accepts two identifier types: ICCID (19-20 digits) or
        // access number / 接入号 (8-14 digits). Prefer ICCID when both present.
        val iccid = ICCID_REGEX.find(content)?.value
            ?: ACCESS_NUM_REGEX.find(content)?.value
        if (iccid == null) {
            send("请提供卡号（ICCID 19-20 位，或接入号 8-14 位），例如：查询 89860012345678901234")
            return true
        }

        try {
            CtwingFacade.webViewMutex.withLock {
            when (op) {
                "query" -> {
                    kotlinx.coroutines.delay(2_000L)
                    val idType = inferType(iccid)
                    val raw = nativeGetWithRetry("query") { token -> NativeHttp.queryCard(token, idType, iccid) }
                    // 先检测业务错误（code!=0, data=null）——如"不在查询范围"
                    val errorMsg = raw?.let { extractQueryError(it) }
                    if (errorMsg != null) {
                        // 业务错误（永久性）：告知原因即可，不引导重试
                        send("⚠️ 查询失败：$errorMsg")
                        return@withLock
                    }
                    val bestResp = raw?.let { extractBestResponse(it) }
                    if (bestResp != null) send(formatCardInfo(bestResp))
                    else send("⚠️ 查询未完成，请稍后重试或发送「续期」刷新登录态")
                }
                "diagnose" -> {
                    kotlinx.coroutines.delay(2_000L)
                    val idType = inferType(iccid)
                    val raw = nativeGetWithRetry("diagnose") { token -> NativeHttp.diagnose(token, idType, iccid) }
                    // 先检测业务错误（code!=0）——如 401 未登录
                    val errorMsg = raw?.let { extractQueryError(it) }
                    if (errorMsg != null) {
                        send("⚠️ 诊断失败：$errorMsg")
                        return@withLock
                    }
                    val bestResp = raw?.let { extractBestResponse(it) }
                    if (bestResp != null) send(formatDiagnosis(bestResp))
                    else send("⚠️ 诊断未完成，请稍后重试或发送「续期」刷新登录态")
                }
                "rebind" -> {
                    // 幂等：30s 内同一卡号不重复提交（重绑是真实业务工单，防手抖/重复触发）
                    val lastTs = recentRebind[iccid]
                    val now = System.currentTimeMillis()
                    if (lastTs != null && (now - lastTs) < REBIND_IDEMPOTENT_WINDOW_MS) {
                        send("⏳ $iccid 已提交重绑，请勿重复操作（${(REBIND_IDEMPOTENT_WINDOW_MS - (now - lastTs)) / 1000}s 后可重试）")
                        return@withLock
                    }
                    send("🔄 正在提交重绑…")
                    CtwingFacade.pullTokenOrRebuild()
                    val token = NativeHttp.cachedToken ?: ""
                    val idType = inferType(iccid)
                    // 卡号类型中文标签：根据用户发来的号码类型回显
                    val idTypeLabel = when (idType) {
                        "msisdn" -> "接入号"
                        "imsi" -> "IMSI"
                        else -> "ICCID"
                    }
                    val payload = org.json.JSONObject().apply {
                        put("type", idType)
                        put("id", iccid)
                        put("imei", "")
                        put("source", "其他")
                        put("orderNumber", "")
                        put("sessionId", "")
                        put("comment", "")
                        put("bindType", "")
                        put("file", org.json.JSONObject().put("ids", org.json.JSONArray()))
                        put("operation", "JKCB")
                    }.toString()
                    XposedBridge.log("$TAG operationCommit: $payload")

                    // 策略：优先 WebView XHR（原生 CSRF），轮询失败则 fallback NativeHttp
                    CtwingFacade.operationCommit(payload)
                    var raw: String = "null"
                    var webViewOk = false
                    for (round in 1..6) {
                        kotlinx.coroutines.delay(1_000L)
                        raw = CtwingFacade.pollDshResult()
                        if (raw.contains("operationCommit-ok") || raw.contains("operationCommit-err")) {
                            webViewOk = true; break
                        }
                        if (raw.length > 20 && raw != "null") { webViewOk = true; break }
                    }
                    // WebView XHR 回调未触发（双开/X5 后台常见），fallback NativeHttp
                    if (!webViewOk) {
                        XposedBridge.log("$TAG rebind: WebView XHR stalled, fallback NativeHttp")
                        raw = runCatching {
                            NativeHttp.operationCommit(token, payload)
                        }.getOrElse { e ->
                            XposedBridge.log("$TAG rebind NativeHttp failed: ${e.message}")
                            """{"code":-1,"msg":"${e.message}"}"""
                        }
                    }
                    CtwingFacade.releaseWakeLock()

                    // 401 token 过期：强制重建 OAuth 换新 token，重新提交一次
                    if (raw.contains("\"code\":401")) {
                        XposedBridge.log("$TAG rebind: 401, force rebuild + retry")
                        recentRebind.remove(iccid)  // 清除幂等，允许重试
                        CtwingFacade.forceRebuild(40_000L)
                        CtwingFacade.pullToken()
                        val newToken = NativeHttp.cachedToken ?: ""
                        raw = runCatching {
                            NativeHttp.operationCommit(newToken, payload)
                        }.getOrElse { e ->
                            XposedBridge.log("$TAG rebind retry failed: ${e.message}")
                            """{"code":-1,"msg":"${e.message}"}"""
                        }
                    }

                    XposedBridge.log("$TAG rebind raw(${raw.length}): ${raw.take(600)}")
                    val resultText = try {
                        var cur: Any = raw.trim()
                        var guard = 0
                        while (cur is String && guard < 6) {
                            val t = cur.trim()
                            if (!(t.startsWith("\"") || t.startsWith("{"))) break
                            cur = org.json.JSONTokener(t).nextValue()
                            guard++
                        }
                        val wrap = cur as? org.json.JSONObject ?: throw RuntimeException("not object after $guard peels")
                        // NativeHttp 返回原始 JSON（无 {status,body} 包装），直接作为 body
                        // 旧格式：{status:200, body:"{...}"} → 提取 body 字段
                        val bj: org.json.JSONObject? = when {
                            wrap.has("status") && wrap.has("body") -> {
                                val bodyTok = wrap.opt("body")
                                when (bodyTok) {
                                    is org.json.JSONObject -> bodyTok
                                    is String -> {
                                        var b: Any = bodyTok; var g2 = 0
                                        while (b is String && g2 < 6) {
                                            val bt = b.trim()
                                            if (!(bt.startsWith("\"") || bt.startsWith("{"))) break
                                            b = org.json.JSONTokener(bt).nextValue(); g2++
                                        }
                                        b as? org.json.JSONObject
                                    }
                                    else -> null
                                }
                            }
                            else -> wrap // NativeHttp raw format
                        }
                        if (bj == null) throw RuntimeException("no body obj")
                        val bcode = bj.optInt("code", -1)
                        val bdata = bj.optJSONObject("data")
                        val opStatus = bdata?.optString("status", "") ?: ""
                        val remark = bdata?.optString("remark", "") ?: ""
                        val workId = bdata?.optString("id", "") ?: ""

                        when {
                            bcode == 401 -> {
                                recentRebind.remove(iccid)  // 可重试：token 刷新后就能过
                                "⚠️ 重绑失败：登录已过期\n💡 请发送「续期」刷新后重试"
                            }
                            bcode != 0 -> {
                                val msg = bj.optString("msg", "code=$bcode")
                                // 永久性业务错误（权限、范围、卡归属）→ 保留幂等，重试无用
                                // 临时性错误（网络、超时）→ 清除幂等，允许重试
                                val isPermanent = isPermanentBusinessError(msg)
                                if (isPermanent) {
                                    // 保留幂等记录：这不是网络抖动，重试也不会变
                                    recentRebind[iccid] = System.currentTimeMillis()
                                    "❌ 重绑失败：$msg"
                                } else {
                                    recentRebind.remove(iccid)  // 可重试
                                    "❌ 重绑失败：$msg\n💡 请稍后重试"
                                }
                            }
                            opStatus.contains("成功") -> {
                                // 只有明确成功才记录幂等（防重复工单），失败允许立刻重试
                                recentRebind[iccid] = System.currentTimeMillis()
                                val sb = StringBuilder("✅ 机卡重绑成功")
                                if (workId.isNotBlank()) sb.append("（工单：$workId）")
                                if (remark.isNotBlank()) sb.append("\n$remark")
                                sb.toString()
                            }
                            opStatus.contains("失败") -> {
                                // 业务侧明确失败（工单状态=失败），非网络抖动，保留幂等防重复
                                recentRebind[iccid] = System.currentTimeMillis()
                                val sb = StringBuilder("❌ 机卡重绑失败")
                                if (remark.isNotBlank()) sb.append("：$remark")
                                sb.toString()
                            }
                            else -> {
                                // 已提交（异步工单，结果未定）：记录幂等防重复
                                recentRebind[iccid] = System.currentTimeMillis()
                                val sb = StringBuilder("✅ 机卡重绑已提交")
                                if (workId.isNotBlank()) sb.append("（工单：$workId）")
                                sb.append("\n⏳ 处理结果请稍后查询")
                                sb.toString()
                            }
                        }
                    } catch (e: Exception) {
                        XposedBridge.log("$TAG rebind parse failed: ${e.message}")
                        // 解析失败（可能已提交成功但格式异常）：保守起见记录幂等，防重复工单
                        if (raw.contains("\"code\":0")) {
                            recentRebind[iccid] = System.currentTimeMillis()
                            "✅ 机卡重绑已提交成功"
                        } else {
                            // 明确失败/超时：清除幂等，允许重试
                            recentRebind.remove(iccid)
                            "⚠️ 重绑结果异常，请稍后重试"
                        }
                    }
                    send("$resultText\n$idTypeLabel：$iccid")
                }
            }
            } // withLock
            return true
        } catch (e: Exception) {
            XposedBridge.log("$TAG op=$op failed: ${e.message}")
            send("❌ 操作失败：${e.message}")
            return true
        }
    }

    // ------------------------------------------------------------------
    //  Formatting (best-effort; JSON structure is version-specific)
    // ------------------------------------------------------------------

    /**
     * 通用 NativeHttp GET 请求：自动处理 token 拉取 + 401 重建重试。
     * [request] 是具体请求闭包（queryCard / diagnose），返回 API 原始响应字符串。
     */
    private suspend fun nativeGetWithRetry(
        tag: String,
        request: (token: String) -> String,
    ): String? {
        CtwingFacade.pullTokenOrRebuild()
        var token = NativeHttp.cachedToken ?: ""
        // token 为空（非 null 但空串）：说明 WebView 登录态已彻底失效，需强制重建
        if (token.isBlank()) {
            XposedBridge.log("$TAG $tag: empty token, force rebuild before request")
            CtwingFacade.forceRebuild(40_000L)
            CtwingFacade.pullToken()
            token = NativeHttp.cachedToken ?: ""
        }
        var raw = runCatching { request(token) }.getOrElse { e ->
            XposedBridge.log("$TAG $tag NativeHttp failed: ${e.message}")
            null
        }
        // 401 token 过期：强制重建 OAuth 换新 token，重试一次
        if (raw != null && raw.contains("\"code\":401")) {
            XposedBridge.log("$TAG $tag: 401, force rebuild + retry")
            CtwingFacade.forceRebuild(40_000L)
            CtwingFacade.pullToken()
            val newToken = NativeHttp.cachedToken ?: ""
            raw = runCatching { request(newToken) }.getOrElse { e ->
                XposedBridge.log("$TAG $tag retry failed: ${e.message}")
                null
            }
        }
        CtwingFacade.releaseWakeLock()
        return raw
    }

    /** CTROBF1 ciphertext marker (the API returns this when undecrypted). */
    private fun isCiphertext(s: String): Boolean =
        s.isNotEmpty() && (s[0] == '\u001e' || s.contains("CTROBF"))

    /** Infer card id type: msisdn (1x 11-13 digits), imsi (15 digits), else iccid. */
    private fun inferType(id: String): String {
        var normalized = id
        if (normalized.length == 20 && normalized[0] == '8') normalized = normalized.substring(0, 19)
        return when {
            Regex("^1[0-9]{10,12}$").matches(normalized) -> "msisdn"
            Regex("^\\d{15}$").matches(normalized) -> "imsi"
            else -> "iccid"
        }
    }

    /**
     * 检测 CTWing API 返回的业务级错误（code!=0 且 msg 有意义）。
     * 只匹配明确的错误场景（如"不在查询范围"），不拦截正常响应。
     * 返回 null 表示不是业务错误，继续正常解析。
     */
    private fun extractQueryError(raw: String): String? {
        return try {
            val root = org.json.JSONObject(raw)
            val code = root.optInt("code", 0)
            val msg = root.optString("msg", "")
            val data = root.opt("data")
            if (code != 0 && msg.isNotBlank() && (data == null || data === org.json.JSONObject.NULL)) {
                // 友好化：把"不在您的查询范围内"改成"非台州电信开卡"
                msg.replace("不在您的查询范围内", "非台州电信开卡")
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * 判断重绑错误是否为「永久性业务错误」，即重试也不会改善。
     * 永久错误 → 保留幂等记录，防止用户无意义重复提交。
     * 临时错误（网络/超时/401）→ 清除幂等，允许立刻重试。
     */
    private fun isPermanentBusinessError(msg: String): Boolean {
        val lower = msg.lowercase()
        return lower.contains("不在") || lower.contains("范围") ||
            lower.contains("非台州") || lower.contains("权限") ||
            lower.contains("授权") || lower.contains("上限") ||
            lower.contains("次数") || lower.contains("不属于") ||
            lower.contains("未在您") || lower.contains("亲，该")
    }

    /**
     * Parse readApiResponses() JSON and return the most likely card-info body.
     * Prefers a JSON response (200) whose body is an object/array; skips
     * the 403 anti-bot HTML page.
     */
    private fun extractBestResponse(raw: String): String? {
        return try {
            var json = raw.trim()
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = json.substring(1, json.length - 1)
            }
            json = json.replace("\\\"", "\"")
            XposedBridge.log("$TAG extractBestResponse json.len=${json.length}")
            val root = org.json.JSONObject(json)
            // Sync-XHR wrapper: {status: 200, body: "{...}"} — extract body field
            val bodyStr = root.optString("body", "")
            if (bodyStr.isNotBlank() && bodyStr.length > 10) {
                XposedBridge.log("$TAG extractBestResponse body.len=${bodyStr.length}")
                return bodyStr
            }
            // Old format: {data, diag, dom}
            val data = root.optJSONObject("data")
            if (data != null) {
                XposedBridge.log("$TAG extractBestResponse data OK")
                return data.toString()
            }
            val diag = root.optString("diag", "")
            if (diag.isNotBlank() && diag != "null") {
                XposedBridge.log("$TAG extractBestResponse diag.len=${diag.length}")
                return diag
            }
            val dom = root.optString("dom", "")
            XposedBridge.log("$TAG extractBestResponse dom.len=${dom.length}")
            if (dom.isNotBlank() && !dom.startsWith("no-root")) dom else null
        } catch (e: Exception) {
            XposedBridge.log("$TAG extractBestResponse failed: ${e.message}")
            null
        }
    }

    private fun formatCardInfo(text: String): String {
        if (isCiphertext(text)) return "⚠️ 返回密文（CTROBF1），尚未解密。"
        if (text.contains(" | ") && !text.trimStart().startsWith("{")) {
            val sb = StringBuilder("📱 卡信息\n")
            for (part in text.split(" | ")) {
                if (part.isNotBlank()) sb.append("· $part\n")
            }
            return sb.toString().trimEnd('\n')
        }
        return runCatching {
            val root = org.json.JSONObject(text)
            val data = root.optJSONObject("data") ?: root
            val bio = data.optJSONObject("simBasicInfoRespVO") ?: data

            fun v(keys: String): String? {
                for (k in keys.split("|")) {
                    val x = bio.optString(k.trim(), "")
                    if (x.isNotBlank() && x != "null" && x != "-") return x
                }
                return null
            }

            val sb = StringBuilder()

            // ---- 客户 & 产品 ----
            val cust = v("custName")
            val product = v("productName")
            sb.append("📱 查询结果")
            if (cust != null) sb.append(" · $cust")
            if (product != null) sb.append(" · $product")
            sb.append("\n")

            // ---- 卡标识 ----
            line("接入号", v("msisdn|searchText"), sb)
            line("ICCID",  v("iccid"), sb)
            line("IMSI",   v("imsi"), sb)
            line("归属",   v("commonRegionName"), sb)
            data.optJSONObject("machineRebind")?.let { mr ->
                val bimei = mr.optString("bindImei", "")
                if (bimei.isNotBlank() && bimei != "null") line("绑定IMEI", bimei, sb)
                val lastImei = mr.optString("lastImei", "")
                if (lastImei.isNotBlank() && lastImei != "null") line("当前IMEI", lastImei, sb)
            }

            // ---- 卡状态 ----
            data.optJSONArray("scardMainStatusDOS")?.let { arr ->
                if (arr.length() > 0) {
                    val items = mutableListOf<String>()
                    for (j in 0 until arr.length()) {
                        val item = arr.optJSONObject(j) ?: continue
                        val proj = item.optString("project", item.optString("name", ""))
                        val concl = item.optString("operatorDefinitionStatusName", item.optString("conclusion", ""))
                        if (proj.isNotBlank() || concl.isNotBlank()) {
                            items.add(if (proj.isNotBlank()) "$proj $concl" else concl)
                        }
                    }
                    if (items.isNotEmpty()) sb.append("SIM状态：${items.joinToString("；")}\n")
                }
            }

            // ---- 激活 & 网络 ----
            line("激活方式", v("activeWay"), sb)
            line("激活时间", v("activationTime|servActiveDate"), sb)
            line("生效时间", v("effectiveTime|servCreateDate"), sb)
            line("网络制式", v("networkType"), sb)
            line("APN/DNN",  v("apnName"), sb)
            line("断网状态", v("netBlockStatusName"), sb)
            line("断网类型", v("blockTypeName"), sb)
            line("卡形态",   v("cardPhysical"), sb)
            line("号码池",   v("poolNum"), sb)
            line("绑定类型", v("bindTypeName"), sb)

            // ---- 在线状态 ----
            data.optJSONObject("onlineStatus")?.let { os ->
                val parts = mutableListOf<String>()
                if (os.optString("onlineStatus", "0") == "1") parts.add("在线") else parts.add("离线")
                fun p(k: String, label: String) {
                    val x = os.optString(k, "")
                    if (x.isNotBlank() && x != "null") parts.add("$label $x")
                }
                p("ipv4Address", "IP")
                p("provName", "接入省")
                p("eventTime", "最近上线")
                if (parts.isNotEmpty()) sb.append("📶 ${parts.joinToString(" · ")}\n")
            }

            // ---- 机卡绑定详情 ----
            data.optJSONObject("machineRebind")?.let { mr ->
                val bc = mr.optString("conclusion", "")
                val ji = mr.optString("judgment", "")
                if (bc.isNotBlank() && bc != "null") {
                    sb.append("机卡绑定：$bc\n")
                    if (ji.isNotBlank() && ji != "0" && ji != "null") {
                        appendJudgment(ji, sb)
                    }
                }
            }

            sb.toString().trimEnd('\n')
        }.getOrElse {
            if (text.length > 500) "📱 查询结果（截断）：\n${text.take(500)}…" else "📱 查询结果：\n$text"
        }
    }

    // ==================================================================
    //  共用排版辅助
    // ==================================================================

    /** 长值智能换行：含分号或逗号且 >50 字符时，两两一组换行。 */
    private fun wrapLongValue(label: String, value: String, sb: StringBuilder) {
        val sep = when {
            value.contains(";") -> ";"
            value.contains(",") && value.length > 60 -> ","
            else -> null
        }
        if (sep == null) {
            sb.append("$label：$value\n")
            return
        }
        val parts = value.split(sep).map { it.trim() }.filter { it.isNotBlank() }
        sb.append("$label：\n")
        var i = 0
        while (i < parts.size) {
            if (i + 1 < parts.size) {
                sb.append("  ${parts[i]}$sep${parts[i + 1]}\n")
                i += 2
            } else {
                sb.append("  ${parts[i]}\n")
                i++
            }
        }
    }

    /** 单字段一行，若为 null 则跳过。 */
    private fun line(label: String, value: String?, sb: StringBuilder) {
        if (value == null) return
        wrapLongValue(label, value, sb)
    }

    /** 诊断项 judgment：<br/> 直接换行，分号两两一行。 */
    private fun appendJudgment(text: String, sb: StringBuilder) {
        // 1. <br/> / <br> → 真实换行（每条信息一行）
        val withNewlines = text.replace("<br/>", "\n").replace("<br>", "\n")
        // 2. 逐行处理：行内若还含分号，再两两一组拆分
        for (rawLine in withNewlines.split("\n")) {
            val lineText = rawLine.trim()
            if (lineText.isBlank()) continue
            if (lineText.contains(";")) {
                val parts = lineText.split(";").map { it.trim() }.filter { it.isNotBlank() }
                var i = 0
                while (i < parts.size) {
                    if (i + 1 < parts.size) {
                        sb.append("  ${parts[i]}；${parts[i + 1]}\n")
                        i += 2
                    } else {
                        sb.append("  ${parts[i]}\n")
                        i++
                    }
                }
            } else {
                sb.append("  $lineText\n")
            }
        }
    }

    private fun formatDiagnosis(json: String): String {
        XposedBridge.log("$TAG formatDiagnosis input(${json.length}): ${json.take(800)}")
        if (isCiphertext(json)) return "⚠️ 诊断结果返回密文，尚未解密。"
        return runCatching {
            val data = org.json.JSONObject(json)
            val sb = StringBuilder()

            // ---- header: 基础信息在 simBasicInfoRespVO 里 ----
            val bi = data.optJSONObject("simBasicInfoRespVO")
            val cust = bi?.optString("custName", "") ?: ""
            val product = bi?.optString("productName", "") ?: ""
            sb.append("🔍 诊断报告")
            if (cust.isNotBlank()) sb.append(" · $cust")
            if (product.isNotBlank()) sb.append(" · $product")
            sb.append("\n")

            // ---- 诊断项：networkDisconnect / machineRebind / selfNetworkDisConnect / areaLimit / black ----
            // 每个都是 {project, conclusion, judgment, recommendations, status}
            val diagKeys = listOf("networkDisconnect", "machineRebind", "selfNetworkDisConnect", "areaLimit", "black")
            var hasDiag = false
            for (key in diagKeys) {
                val item = data.optJSONObject(key) ?: continue
                val project = item.optString("project", "")
                if (project.isBlank()) continue
                hasDiag = true
                val icon = when (item.optString("status", "")) {
                    "绿色" -> "✅"
                    "红色" -> "❌"
                    "橙色" -> "⚠️"
                    else -> "·"
                }
                val conclusion = item.optString("conclusion", "—")
                sb.append("$icon $project：$conclusion\n")
                val judgment = item.optString("judgment", "")
                if (judgment.isNotBlank() && judgment != "0" && judgment != "null") {
                    appendJudgment(judgment, sb)
                }
                val rec = item.optString("recommendations", "")
                if (rec.isNotBlank() && rec != "无" && rec != "null") {
                    sb.append("  💡 $rec\n")
                }
            }

            // ---- SIM 卡状态数组 (scardMainStatusDOS) ----
            data.optJSONArray("scardMainStatusDOS")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val item = arr.optJSONObject(j) ?: continue
                    val project = item.optString("project", "")
                    if (project.isBlank()) continue
                    hasDiag = true
                    val icon = when (item.optString("status", "")) {
                        "绿色" -> "✅"
                        "红色" -> "❌"
                        "橙色" -> "⚠️"
                        else -> "·"
                    }
                    val conclusion = item.optString("operatorDefinitionStatusName", item.optString("conclusion", "—"))
                    sb.append("$icon $project：$conclusion\n")
                    val judgment = item.optString("judgment", "")
                    if (judgment.isNotBlank() && judgment != "0" && judgment != "null") {
                        appendJudgment(judgment, sb)
                    }
                }
            }
            if (!hasDiag) sb.append("无诊断项\n")

            // ---- 卡标识 ----
            line("接入号", data.optString("msisdn", "").ifBlank { null }, sb)
            line("ICCID", data.optString("iccid", "").ifBlank { null }, sb)
            line("IMSI", data.optString("imsi", "").ifBlank { null }, sb)
            bi?.let {
                line("归属", it.optString("commonRegionName", "").ifBlank { null }, sb)
            }

            // ---- 在线状态 ----
            data.optJSONObject("onlineStatus")?.let { os ->
                val parts = mutableListOf<String>()
                fun o(k: String, label: String?) {
                    val v = os.optString(k, "")
                    if (v.isNotBlank() && v != "null") parts.add(if (label != null) "$label $v" else v)
                }
                if (os.optString("onlineStatus", "0") == "1") parts.add("在线") else parts.add("离线")
                o("ipv4Address", "IP")
                o("apnName", "APN")
                o("provName", "接入省")
                o("eventTime", "最近上线")
                if (parts.isNotEmpty()) sb.append("📶 ${parts.joinToString(" · ")}\n")
            }

            sb.toString().trimEnd('\n')
        }.getOrElse {
            if (json.contains(" | ") && !json.trimStart().startsWith("{")) {
                val sb = StringBuilder("🔍 诊断结果：\n")
                for (part in json.split(" | ")) if (part.isNotBlank()) sb.append("· $part\n")
                sb.toString().trimEnd('\n')
            } else {
                "🔍 诊断结果：\n${json.take(500)}"
            }
        }
    }

    private fun formatRebind(json: String): String {
        if (isCiphertext(json)) return "⚠️ 重绑结果返回密文，尚未解密。"
        return runCatching {
            val obj = org.json.JSONObject(json)
            val success = obj.optBoolean("success", obj.optString("code", "0") == "0")
            if (success) "✅ 机卡重绑已提交成功"
            else "⚠️ 重绑结果：${json.take(300)}"
        }.getOrElse { "📋 重绑提交结果：\n${json.take(300)}" }
    }
}