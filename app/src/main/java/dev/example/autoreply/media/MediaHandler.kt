package dev.example.autoreply.media

/**
 * Extension point for non-text replies (image/voice/file/sticker).
 *
 * The minimal text version leaves this unimplemented. When you're ready
 * to handle image messages, implement [ImageReplyHandler] using WeKit's
 * WeMessageApi.sendImage DexKit targets (documented in WeChatHook.sendImage).
 *
 * Keeping media handling behind an interface means the text auto-reply
 * engine never needs to change to add image support later — you only
 * implement this interface and register it in the engine.
 */
interface MediaHandler {

    /**
     * Given an incoming image message, produce a reply (e.g. describe it,
     * forward a canned image, etc.). Return null if this handler declines.
     */
    suspend fun handleImage(msg: dev.example.autoreply.hook.IncomingMessage): MediaReply?
}

/**
 * A non-text reply the engine can act on.
 * For the text-only version this is unused; it defines the shape
 * the future image handler will return.
 */
sealed class MediaReply {
    data class Image(val localPath: String) : MediaReply()
    data class Text(val content: String) : MediaReply()
}