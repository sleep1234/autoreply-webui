package dev.example.autoreply.ctwing

import de.robv.android.xposed.XposedBridge
import dev.example.autoreply.hook.IncomingMessage

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

    /**
     * Attempt to handle a message. Returns true if the message matched a
     * CTWing command and a reply was (or will be) sent.
     *
     * [reply] is a suspend function that sends text back to the talker.
     */
    suspend fun tryHandle(msg: IncomingMessage, send: suspend (String) -> Unit): Boolean {
        val raw = msg.content ?: return false
        val content = raw.trim()
        if (content.isEmpty()) return false

        val lower = content.lowercase()

        val op = when {
            lower.startsWith("诊断环境") || lower.startsWith("dump") || lower.startsWith("环境") -> "dump"
            lower.startsWith("加密侦察") || lower.startsWith("recon") -> "recon"
            lower.startsWith("侦察报告") || lower.startsWith("reconreport") -> "reconReport"
            lower.startsWith("重绑") || lower.startsWith("rebind") -> "rebind"
            lower.startsWith("诊断") || lower.startsWith("diagnose") -> "diagnose"
            lower.startsWith("查询") || lower.startsWith("查卡") || lower.startsWith("query") -> "query"
            else -> return false
        }

        if (!CtwingFacade.isReady()) {
            send("⚠️ CTWing 服务未就绪（请先打开物联网卡 H5 页面并登录）")
            return true
        }

        // ---- Diagnostic dump: no ICCID needed ----
        if (op == "dump") {
            try {
                val surface = CtwingFacade.dump()
                send("🔍 SPA 环境：\n${surface.take(600)}")
            } catch (e: Exception) {
                send("❌ 诊断失败：${e.message}")
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
            when (op) {
                "query" -> {
                    // UI automation: fill input + click button → SPA's own
                    // pipeline fires → captured plaintext flows back through
                    // the same callJs return (local or IPC).
                    val r = CtwingFacade.uiQuery(iccid)
                    if (r.isNotBlank() && !isCiphertext(r) && !r.startsWith("{")) {
                        // uiQuery returns a plaintext placeholder normally;
                        // if the SPA actually returned data, use it.
                        send(formatCardInfo(r))
                    } else {
                        send("✅ 已在 H5 页面发起查询 ICCID=$iccid。\n请在 H5 界面确认结果；若未显示请查看 logcat [CTWing-Bridge][api-capture]。")
                    }
                }
                "diagnose" -> {
                    val r = CtwingFacade.diagnose(iccid)
                    send(formatDiagnosis(r))
                }
                "rebind" -> {
                    val imei = IMEI_REGEX.find(content)?.value
                    if (imei == null) {
                        send("请提供 IMEI（15 位数字），例如：重绑 $iccid 866123456789012")
                        return true
                    }
                    val r = CtwingFacade.rebind(iccid, imei)
                    send(formatRebind(r))
                }
            }
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

    /** CTROBF1 ciphertext marker (the API returns this when undecrypted). */
    private fun isCiphertext(s: String): Boolean =
        s.isNotEmpty() && (s[0] == '\u001e' || s.contains("CTROBF"))

    private fun formatCardInfo(json: String): String {
        if (isCiphertext(json)) return "⚠️ 返回密文（CTROBF1），尚未解密。当前为诊断模式，请查看 logcat 的 [diag] 日志以确定 SPA 真实请求机制。"
        return runCatching {
            val sb = StringBuilder("📱 卡信息：\n")
            val obj = org.json.JSONObject(json)
            fun pick(vararg keys: String): String? {
                for (k in keys) if (obj.has(k)) return obj.opt(k)?.toString()
                return null
            }
            pick("iccid")?.let { sb.append("ICCID：$it\n") }
            pick("msisdn", "msisdnNumber")?.let { sb.append("MSISDN：$it\n") }
            pick("status", "simStatus")?.let { sb.append("状态：$it\n") }
            pick("imei")?.let { sb.append("IMEI：$it\n") }
            pick("operator", "carrier")?.let { sb.append("运营商：$it\n") }
            sb.toString().trimEnd('\n')
        }.getOrElse {
            if (json.length > 500) "📱 查询结果（截断）：\n${json.take(500)}…" else "📱 查询结果：\n$json"
        }
    }

    private fun formatDiagnosis(json: String): String {
        if (isCiphertext(json)) return "⚠️ 诊断结果返回密文，尚未解密。"
        return runCatching {
            val sb = StringBuilder("🔍 诊断结果：\n")
            val obj = org.json.JSONObject(json)
            val diagnosis = obj.optString("diagnosis", "")
            val summary = obj.optString("summary", obj.optString("result", ""))
            if (diagnosis.isNotBlank()) sb.append(diagnosis)
            else if (summary.isNotBlank()) sb.append(summary)
            else sb.append(json.take(500))
            sb.toString()
        }.getOrElse { "🔍 诊断结果：\n${json.take(500)}" }
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