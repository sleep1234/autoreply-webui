package dev.example.autoreply.hook

import android.app.Notification
import android.content.ContentValues
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * Multi-layer diagnostic hooks to discover how WeChat 8.0.76 stores
 * incoming messages. Hooks:
 *
 *   Layer A — NotificationManager.notify (catches message notifications,
 *       including chat content visible in the ticker text)
 *   Layer B — MMKV.encode / MMKV.put (WeChat's key-value store)
 *   Layer C — ContentProvider.insert (cross-process data sharing)
 *
 * Once we confirm the right layer, we'll replace this with a proper
 * capture hook and wire it into the message trigger engine.
 */
object MessageDiscoveryHooks {

    private const val TAG = "[MsgDiscovery]"

    /** Callback with the sender and content extracted from any layer. */
    @Volatile
    var onMessageCaptured: ((talker: String, content: String) -> Unit)? = null

    // ----------------------------------------------------------------
    //  Layer A: Notification hook
    // ----------------------------------------------------------------

    fun hookNotifications(classLoader: ClassLoader) {
        runCatching {
            val nmClass = XposedHelpers.findClass(
                "android.app.NotificationManager", classLoader)
            XposedHelpers.findAndHookMethod(nmClass, "notify",
                String::class.java, Int::class.javaPrimitiveType, Notification::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val notif = param.args[2] as? Notification ?: return
                        val tag = param.args[0] as? String ?: "?"
                        val ticker = notif.tickerText?.toString() ?: ""
                        val extras = notif.extras
                        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                        // Unconditional log — discover what WeChat puts in notifications
                        XposedBridge.log("$TAG [NOTIFY] tag=$tag ticker='$ticker' title='$title' text='${text.take(120)}'")
                        if (text.isNotBlank()) {
                            onMessageCaptured?.invoke(title, text)
                        }
                    }
                })
            XposedBridge.log("$TAG hooked NotificationManager.notify (unconditional)")
        }.onFailure { XposedBridge.log("$TAG notify hook failed: ${it.message}") }
    }

    // ----------------------------------------------------------------
    //  Layer B: MMKV hook (WeChat uses com.tencent.mmkv.MMKV)
    // ----------------------------------------------------------------

    fun hookMmkv(classLoader: ClassLoader) {
        runCatching {
            val mmkv = XposedHelpers.findClass("com.tencent.mmkv.MMKV", classLoader)
            // encode(String key, Xxx value) — several overloads
            for (m in mmkv.declaredMethods) {
                if (m.name == "encode" && m.parameterCount == 2) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args[0]?.toString() ?: return
                            if (key.contains("msg") || key.contains("Msg") || 
                                key.contains("chat") || key.contains("Chat") ||
                                key.contains("talker") || key.contains("content")) {
                                val value = param.args[1]?.toString()?.take(200) ?: "?"
                                XposedBridge.log("$TAG [MMKV] key=$key value=$value")
                            }
                        }
                    })
                }
            }
            XposedBridge.log("$TAG hooked MMKV.encode")
        }.onFailure { XposedBridge.log("$TAG MMKV hook failed: ${it.message}") }
    }

    // ----------------------------------------------------------------
    //  Layer C: ContentProvider insert (cross-process message store)
    // ----------------------------------------------------------------

    fun hookContentProvider(classLoader: ClassLoader) {
        runCatching {
            val cp = XposedHelpers.findClass("android.content.ContentProvider", classLoader)
            XposedBridge.hookAllMethods(cp, "insert", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val uri = param.args[0]?.toString() ?: return
                    if (uri.contains("msg") || uri.contains("Msg") || uri.contains("message")) {
                        XposedBridge.log("$TAG [CP-INSERT] uri=$uri")
                    }
                }
            })
            XposedBridge.log("$TAG hooked ContentProvider.insert")
        }.onFailure { XposedBridge.log("$TAG CP hook failed: ${it.message}") }
    }
}