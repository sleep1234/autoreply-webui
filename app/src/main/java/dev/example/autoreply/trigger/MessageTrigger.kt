package dev.example.autoreply.trigger

/**
 * Filter + debounce for incoming messages before dispatching to the LLM.
 *
 * This is a distilled version of WeKit's TriggerModels + TriggerBuffer + EventTriggerBus,
 * collapsed to the single "MESSAGE" trigger type (no schedule/SQL triggers, no Room).
 */
class MessageTrigger(
    /** Only reply to messages whose content matches this regex (null/blank = all). */
    val contentRegex: String? = null,
    /** Only reply from/to this talker regex (null/blank = any conversation). */
    val talkerRegex: String? = null,
    /** Silence window after the last message before firing (ms). */
    val debounceMillis: Long = 1_500L,
    /** Fire immediately once this many messages accumulate. */
    val maxEvents: Int = 5,
    /** Hard cap on latency: fire this long after the FIRST message even under load. */
    val maxWaitMillis: Long = 5_000L,
    /** Minimum gap between two consecutive replies (anti-loop / rate-limit). */
    val cooldownMillis: Long = 2_000L,
    /** Drop rows with isSend == 1 (covers the user's own sends AND agent replies). */
    val filterOwnEvents: Boolean = true,
) {

    private var lastFiredAt = 0L

    /**
     * Returns true if this message should be queued for a reply.
     * Non-blocking; cheap enough to call on WeChat's WCDB thread.
     */
    fun matches(msg: dev.example.autoreply.hook.IncomingMessage): Boolean {
        // Anti-loop: never reply to our own sends (or anything isSend).
        if (filterOwnEvents && msg.isSend) return false

        // Content filter.
        val contentPattern = contentRegex
        if (!contentPattern.isNullOrBlank()) {
            val content = msg.content ?: return false
            if (!runCatching { Regex(contentPattern).containsMatchIn(content) }.getOrDefault(false)) {
                return false
            }
        }

        // Talker filter.
        val talkerPattern = talkerRegex
        if (!talkerPattern.isNullOrBlank()) {
            val talker = msg.talker ?: return false
            if (!runCatching { Regex(talkerPattern).containsMatchIn(talker) }.getOrDefault(false)) {
                return false
            }
        }

        // Only react to text messages (type 1). Image/voice/other types are skipped
        // in this minimal text version — extend here for media handling.
        if (msg.type != null && msg.type != 1) return false

        return true
    }

    /** True if the cooldown window has elapsed since the last fire. */
    fun canFireNow(): Boolean {
        if (cooldownMillis <= 0) return true
        val now = System.currentTimeMillis()
        return now - lastFiredAt >= cooldownMillis
    }

    fun markFired() {
        lastFiredAt = System.currentTimeMillis()
    }
}