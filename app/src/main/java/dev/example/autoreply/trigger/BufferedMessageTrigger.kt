package dev.example.autoreply.trigger

import dev.example.autoreply.hook.IncomingMessage
import dev.example.autoreply.hook.MessageInsertListener
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Buffers incoming messages with debounce + batch semantics.
 *
 * Three flush rules (mirrored from WeKit's TriggerBuffer):
 *  1. debounce — each new message (re)starts a silence timer; flush on expiry.
 *  2. cap — flush immediately once [maxEvents] messages are queued.
 *  3. max-wait — flush unconditionally [maxWaitMillis] after the FIRST message.
 *
 * This is a standalone implementation, NOT copied from WeKit;
 * it reproduces the same three-rule buffer logic described in WeKit's AGENTS.md.
 */
class BufferedMessageTrigger(
    private val scope: CoroutineScope,
    val config: MessageTrigger = MessageTrigger(),
    private val onFlush: suspend (messages: List<IncomingMessage>) -> Unit,
) : MessageInsertListener {

    private val mutex = Mutex()
    private val pending = ArrayList<IncomingMessage>()
    private var debounceJob: Job? = null
    private var maxWaitJob: Job? = null

    override fun onMessageInsert(msg: IncomingMessage) {
        if (!config.matches(msg)) {
            XposedBridge.log("[AutoReply] BufferedMessageTrigger: message filtered out by matches()")
            return
        }
        XposedBridge.log("[AutoReply] BufferedMessageTrigger: queuing message for flush")

        scope.launch {
            mutex.withLock {
                pending.add(msg)
                XposedBridge.log("[AutoReply] BufferedMessageTrigger: pending count=${pending.size}")

                // Cap rule
                if (pending.size >= config.maxEvents) {
                    XposedBridge.log("[AutoReply] BufferedMessageTrigger: flushing via cap rule")
                    flushLocked("cap")
                    return@launch
                }

                // Debounce
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(config.debounceMillis)
                    XposedBridge.log("[AutoReply] BufferedMessageTrigger: firing debounce after ${config.debounceMillis}ms")
                    mutex.withLock { flushLocked("debounce") }
                }

                // Max-wait (arm once)
                if (maxWaitJob == null) {
                    XposedBridge.log("[AutoReply] BufferedMessageTrigger: arming max-wait ${config.maxWaitMillis}ms")
                    maxWaitJob = scope.launch {
                        delay(config.maxWaitMillis)
                        XposedBridge.log("[AutoReply] BufferedMessageTrigger: firing max-wait after ${config.maxWaitMillis}ms")
                        mutex.withLock { flushLocked("max-wait") }
                    }
                }
            }
        }
    }

    private suspend fun flushLocked(reason: String) {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        debounceJob?.cancel(); debounceJob = null
        maxWaitJob?.cancel(); maxWaitJob = null

        // Detach so the timer job's cancellation doesn't kill the dispatch.
        scope.launch {
            runCatching { onFlush(batch) }
        }
    }

    fun cancel() {
        debounceJob?.cancel()
        maxWaitJob?.cancel()
        scope.launch { mutex.withLock { pending.clear() } }
    }
}