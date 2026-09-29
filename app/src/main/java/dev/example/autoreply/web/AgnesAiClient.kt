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
你是物联卡信息识别助手。用户会给你一张物联卡图片，请从中提取 ICCID。

ICCID 规则：通常是 19 位或 20 位数字，常以 8986 开头（中国电信/联通物联卡）。

要求：
1. 只输出你识别到的 ICCID 纯数字，不要任何其他文字、标点、空格或换行。
2. 如果图中没有 ICCID，输出 "NOT_FOUND"。
3. 如果图片模糊无法确定，输出 "NOT_FOUND"。
4. 如果识别到多个候选，只输出最完整、最像 ICCID 的那个（20位优先，19位次之）。
""".trimIndent()

    /**
     * 识别图片中的 ICCID。
     * @param imageBytes 图片原始字节（PNG/JPEG）
     * @return 识别到的 ICCID（纯数字），失败返回 null
     */
    fun extractIccid(imageBytes: ByteArray): String? {
        return try {
            val b64 = Base64.getEncoder().encodeToString(imageBytes)
            val mime = detectMime(imageBytes)
            val dataUrl = "data:$mime;base64,$b64"

            val body = org.json.JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 64)
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
                                put("text", "请识别这张物联卡图片中的 ICCID。")
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

            XposedBridge.log("$TAG raw response content: ${content.take(80)}")

            when {
                content.equals("NOT_FOUND", ignoreCase = true) -> null
                else -> {
                    // 提取纯数字
                    val digits = content.filter { it.isDigit() }
                    // ICCID 19-20 位
                    if (digits.length in 19..20) digits else null
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG extractIccid failed: ${e.message}")
            null
        }
    }

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