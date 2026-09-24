package dev.example.autoreply.engine

import dev.example.autoreply.hook.IWeChatHook
import dev.example.autoreply.hook.IncomingMessage
import dev.example.autoreply.llm.OpenAiClient
import dev.example.autoreply.media.MediaHandler
import dev.example.autoreply.trigger.BufferedMessageTrigger
import dev.example.autoreply.trigger.MessageTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires the pieces together:
 *
 *   WeChatHook (capture + send)
 *        │
 *   BufferedMessageTrigger (filter + debounce + batch)
 *        │
 *   OpenAiClient (LLM text → reply text)
 *        │
 *   IWeChatHook.sendText (send the reply)
 *
 * Media handling is optional and plugged in via [MediaHandler];
 * when null (the default) only text messages are handled.
 */
class AutoReplyEngine(
    private val hook: IWeChatHook,
    private val llm: OpenAiClient,
    private val triggerConfig: MessageTrigger = MessageTrigger(),
    private val mediaHandler: MediaHandler? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val buffer = BufferedMessageTrigger(
        scope = scope,
        config = triggerConfig,
        onFlush = ::onFlush,
    )

    fun start() {
        hook.onEnable()
        hook.addInsertListener(buffer)
    }

    fun stop() {
        buffer.cancel()
        hook.removeInsertListener(buffer)
    }

    private suspend fun onFlush(messages: List<IncomingMessage>) {
        // Cooldown check at the trigger level (not per-buffer).
        if (!triggerConfig.canFireNow()) return
        triggerConfig.markFired()

        // Pick the latest received text message as the LLM input.
        val target = messages.lastOrNull() ?: return
        val talker = target.talker ?: return

        // ---- Media branch (future): image messages route here ----
        if (mediaHandler != null && target.type != null && target.type != 1) {
            val reply = mediaHandler.handleImage(target)
            when (reply) {
                is dev.example.autoreply.media.MediaReply.Image ->
                    hook.sendImage(talker, reply.localPath)
                is dev.example.autoreply.media.MediaReply.Text ->
                    hook.sendText(talker, reply.content)
                null -> Unit // handler declined
            }
            return
        }

        // ---- Text branch (current minimal) ----
        val content = target.content ?: return
        val reply = llm.reply(content)
        if (reply.isNotBlank()) {
            hook.sendText(talker, reply)
        }
    }
}