package dev.example.autoreply.ctwing

import de.robv.android.xposed.XposedBridge
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 原生 HTTP 直连 CTWing API，使用 CtwingCrypto 处理加解密。
 *
 * 完全绕开 WebView/X5/sbu1，可以在锁屏 / 后台 / 无 Activity 环境下直接调用。
 */
object NativeHttp {

    private const val TAG = "[NativeHttp]"

    private const val BASE_URL = "https://tywlonestop.ctwing.cn:8081"
    private const val API_BASE = "$BASE_URL/webapp-font/admin-api/bpm/service-assistant"

    // 通用请求头
    private val COMMON_HEADERS = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 15; WeChat) AppleWebKit/537.36"
    )

    // ---- 核心方法 ----

    /** GET 查询请求：querySimBaseInfo?type=…&id=… */
    fun queryCard(token: String, type: String, id: String): String {
        val query = "type=$type&id=$id"
        return httpGet("$API_BASE/querySimBaseInfo?$query", token)
    }

    /** GET 诊断请求：intelligentDiagnosis?type=…&id=… */
    fun diagnose(token: String, type: String, id: String): String {
        val query = "type=$type&id=$id"
        return httpGet("$API_BASE/intelligentDiagnosis?$query", token)
    }

    /** GET 基础信息：basicInfo?type=…&id=… */
    fun basicInfo(token: String, type: String, id: String): String {
        val query = "type=$type&id=$id&isFromWeb=true&userId=199"
        return httpGet("$API_BASE/basicInfo?$query", token)
    }

    /** POST 机卡重绑：operationCommit，body 为 JSON。 */
    fun operationCommit(token: String, bodyJson: String): String {
        return httpPost("$API_BASE/operationCommit", bodyJson, token)
    }

    // ---- HTTP 核心 ----

    private fun httpGet(urlStr: String, token: String): String {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                for ((k, v) in COMMON_HEADERS) setRequestProperty(k, v)
                setRequestProperty("Authorization", "Bearer $token")
                cachedCookie?.let { setRequestProperty("Cookie", it) }
                setRequestProperty("Origin", "https://tywlonestop.ctwing.cn:8081")
                setRequestProperty("Referer", "https://tywlonestop.ctwing.cn:8081/web-apps/")
            }
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().readText()
            XposedBridge.log("$TAG GET $urlStr → $code (${body.length}B) head: ${body.take(300)}")
            return body
        } catch (e: Exception) {
            XposedBridge.log("$TAG GET $urlStr FAILED: ${e.message}")
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPost(urlStr: String, bodyJson: String, token: String): String {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 20_000
                for ((k, v) in COMMON_HEADERS) setRequestProperty(k, v)
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                cachedCookie?.let { setRequestProperty("Cookie", it) }
                setRequestProperty("Origin", "https://tywlonestop.ctwing.cn:8081")
                setRequestProperty("Referer", "https://tywlonestop.ctwing.cn:8081/web-apps/")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(bodyJson) }
            val code = conn.responseCode
            val body = try {
                conn.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                // 非 2xx 时 inputStream 抛异常，从 errorStream 读
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            XposedBridge.log("$TAG POST $urlStr → $code (${body.length}B) head: ${body.take(300)}")
            return body
        } catch (e: Exception) {
            XposedBridge.log("$TAG POST $urlStr FAILED: ${e.message}")
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    // ---- Token / Bond 管理 ----

    /** 缓存的完整 cookie 串（pullToken 时从 WebView 读取，供原生请求附带）。 */
    @Volatile
    var cachedCookie: String? = null

    /** 缓存的 bond 值（从 cookie 或首次 HTTP 响应提取）。 */
    @Volatile
    var cachedBond: String? = null

    /** 缓存的 ACCESS_TOKEN（内存 + 磁盘双份）。 */
    @Volatile
    var cachedToken: String? = loadToken()
        set(value) {
            field = value
            saveToken(value)
        }

    // ---- Token 持久化 ----

    private fun tokenFile(): java.io.File? {
        val dir = CtwingIpcBridge.wechatDataDir ?: return null
        return java.io.File(dir, "dsh_ctwing_bundles/ctwing_token.json")
    }

    private fun loadToken(): String? {
        return try {
            val f = tokenFile() ?: return null
            if (!f.exists()) return null
            val json = f.readText()
            org.json.JSONObject(json).optString("token", "").ifBlank { null }
        } catch (e: Exception) {
            XposedBridge.log("$TAG loadToken failed: ${e.message}")
            null
        }
    }

    private fun saveToken(token: String?) {
        try {
            val f = tokenFile() ?: return
            f.parentFile?.mkdirs()
            val json = org.json.JSONObject().apply {
                put("token", token ?: "")
                put("savedAt", System.currentTimeMillis())
            }.toString()
            f.writeText(json)
        } catch (e: Exception) {
            XposedBridge.log("$TAG saveToken failed: ${e.message}")
        }
    }
}