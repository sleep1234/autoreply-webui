package dev.example.autoreply.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Minimal OpenAI Chat Completions client.
 *
 * This is a distilled single-purpose client (text in, text out).
 * It intentionally omits the full streaming + tool-call loop that WeKit's
 * OpenAiChatCompletionsClient implements — this version just needs to turn
 * an incoming message into a reply string.
 *
 * Extension point: swap in a tool-calling client later (see WeKit's
 * `agent/model/OpenAiChatCompletionsClient.kt`) to let the model decide
 * between text vs image replies.
 */
class OpenAiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o-mini",
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val max_tokens: Int = 500,
        val temperature: Double = 0.7,
    )

    @Serializable
    data class Choice(val message: ChatMessage)

    @Serializable
    data class ChatResponse(val choices: List<Choice>)

    /** Turn a single incoming message into a reply string. */
    fun reply(userMessage: String): String {
        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userMessage),
        )
        val request = ChatRequest(model, messages)

        val body = json.encodeToString(ChatRequest.serializer(), request)
        val httpReq = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()

        client.newCall(httpReq).execute().use { resp ->
            val text = resp.body?.string() ?: return ""
            if (!resp.isSuccessful) {
                throw IllegalStateException("LLM HTTP ${resp.code}: $text")
            }
            val parsed = json.decodeFromString(ChatResponse.serializer(), text)
            return parsed.choices.firstOrNull()?.message?.content?.trim() ?: ""
        }
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "你是用户的微信自动回复助手。请根据收到的消息，用中文简洁、礼貌地回复。"
    }
}