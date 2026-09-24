package dev.example.autoreply.hook

import android.content.ContentValues

/**
 * Abstraction over WeChat's internal APIs for message interception and sending.
 *
 * The implementation targets WeChat 8.0.65–8.0.78 via DexKit.
 * To port to a different WeChat version, implement this interface with
 * the equivalent method descriptors for your target APK.
 *
 * This separates the auto-reply engine from WeChat-version specifics,
 * making the engine reusable across WeChat versions with only an implementation
 * swap.
 */
interface IWeChatHook {

    // -------------------------------------------------------------------
    // Message interception — called from the DB-listener hook
    // -------------------------------------------------------------------

    /**
     * Register a listener for `message` table inserts.
     *
     * The listener runs on WeChat's own WCDB thread, so dispatch work
     * (e.g. coroutine) should be deferred by the callee.
     */
    fun addInsertListener(listener: MessageInsertListener)

    fun removeInsertListener(listener: MessageInsertListener)

    /** Called once after DexKit resolution and reflection are ready. */
    fun onEnable()

    // -------------------------------------------------------------------
    // Message sending — uses the WeChat NetSceneSendMsg path
    // -------------------------------------------------------------------

    /**
     * Send a plain-text message to [talker].
     * Returns true if the NetScene was queued successfully.
     */
    fun sendText(talker: String, text: String): Boolean

    /**
     * Send an image message (local file path) to [talker].
     * Implemented via DexKit (classImageServiceImpl + classImageTask),
     * verified present on WeChat 8.0.76.
     */
    fun sendImage(talker: String, imagePath: String): Boolean

    /**
     * Get the currently active conversation id (from ChattingContext.getTalker).
     * Returns null if WeChat isn't showing a chat window.
     */
    fun getCurrentTalker(): String?

    // -------------------------------------------------------------------
    // Self info
    // -------------------------------------------------------------------

    /** The user's own wxid (e.g. "wxid_xxxx"). */
    val selfWxId: String?
}

/**
 * A single incoming message captured from the `message` table INSERT.
 * All fields are nullable because ContentValues may omit columns.
 */
data class IncomingMessage(
    val msgSvrId: Long?,
    val type: Int?,
    val talker: String?,
    val content: String?,
    val isSend: Boolean,
    val createTime: Long?,
)

fun interface MessageInsertListener {
    fun onMessageInsert(msg: IncomingMessage)
}