package dev.example.autoreply.web

import de.robv.android.xposed.XposedBridge
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Agnes AI 多模态大模型客户端：识别图片中的 ICCID。
 * 走 OpenAI 兼容接口（/v1/chat/completions）。
 */
object AgnesAiClient {

    private const val TAG = "[AgnesAI]"
    private const val API_URL = "https://apihub.agnes-ai.cn/v1/chat/completions"
    private const val API_KEY = "sk-tNDiUY6VeicB0ZY8lf2AKp5M08fAYVhl9EuBpgHrZ2ZmkrsC"
    private const val MODEL = "agnes-3.0-flash"

    private val SYSTEM_PROMPT = """
你是物联卡信息识别助手。用户会给你一张物联卡图片，请从中提取所有卡号。

识别规则：
1. ICCID：8986 开头的 19 位连续数字（若中断则丢弃，不要截取 20 位数字的前 19 位）。
2. 接入号：1 开头的 13 位连续数字（若中断则丢弃）。
3. 输出格式：把识别到的每个卡号单独放一行，只输出纯数字，不要任何其他文字、标点、空格或换行外的内容。
4. 如果图中没有卡号，只输出 "NOT_FOUND"。
5. 如果识别到多个卡号，每个占一行，按在原图出现的顺序输出。
6. 重复出现的同一卡号只输出一次。
""".trimIndent()

    /**
     * 识别图片中的卡号（ICCID 或接入号）。
     * @param imageBytes 图片原始字节（PNG/JPEG）
     * @return 识别到的卡号列表（去重），失败或未识别返回空列表
     */
    fun extractCards(imageBytes: ByteArray): List<String> {
        return try {
            val b64 = Base64.getEncoder().encodeToString(imageBytes)
            val mime = detectMime(imageBytes)
            val dataUrl = "data:$mime;base64,$b64"

            val body = org.json.JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 200)
                put("messages", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(org.json.JSONObject().apply {
                        put("role", "user")
                        put("content", org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply {
                                put("type", "text")
                                put("text", "请识别这张物联卡图片中的所有 ICCID 和接入号。")
                            })
                            put(org.json.JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", org.json.JSONObject().apply {
                                    put("url", dataUrl)
                                })
                            })
                        })
                    })
                })
            }.toString()

            val resp = post(body)
            // 解析 choices[0].message.content
            val json = org.json.JSONObject(resp)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "").trim()

            XposedBridge.log("$TAG raw response content: ${content.take(120)}")

            if (content.equals("NOT_FOUND", ignoreCase = true)) return emptyList()

            // 按行解析，每行一个卡号，过滤非法格式
            content.lines()
                .map { it.trim().filter { c -> c.isDigit() } }
                .filter { digits ->
                    // ICCID: 8986 开头 19 位；接入号: 1 开头 13 位
                    (digits.startsWith("8986") && digits.length == 19) ||
                        (digits.startsWith("1") && digits.length == 13)
                }
                .distinct()
        } catch (e: Exception) {
            XposedBridge.log("$TAG extractCards failed: ${e.message}")
            emptyList()
        }
    }

    /** 兼容旧调用：返回单个 ICCID（多个时取第一个）。 */
    fun extractIccid(imageBytes: ByteArray): String? = extractCards(imageBytes).firstOrNull()

    private fun post(body: String): String {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $API_KEY")
            setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                XposedBridge.log("$TAG HTTP $code: ${err.take(200)}")
                throw RuntimeException("Agnes AI HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun detectMime(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) return "image/png"
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        ) return "image/jpeg"
        return "image/png" // 默认
    }
}