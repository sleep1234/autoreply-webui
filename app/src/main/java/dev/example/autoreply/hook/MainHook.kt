package dev.example.autoreply.hook

import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.example.autoreply.trigger.BufferedMessageTrigger
import dev.example.autoreply.trigger.MessageTrigger
import dev.example.autoreply.ui.WhitelistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class MainHook : IXposedHookZygoteInit, IXposedHookLoadPackage {

    companion object {
        @Volatile
        var modulePath: String? = null
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return
        if (!lpparam.isFirstApplication) return

        XposedBridge.log("[AutoReply] WeChat main process started. Initializing…")

        // Wipe Tinker hot-update patch before it loads. Tinker silently replaces
        // DEX classes (including WCDB) when the patch dir exists, which breaks
        // all message-table hooks. This runs in the main process, before WeChat
        // fully initializes, so no stale DEX is loaded.
        runCatching {
            val tinkerDir = java.io.File(lpparam.appInfo.dataDir, "tinker")
            if (tinkerDir.exists()) {
                tinkerDir.deleteRecursively()
                XposedBridge.log("[AutoReply] Tinker patch directory deleted")
            } else {
                XposedBridge.log("[AutoReply] No Tinker patch found")
            }
        }

        loadNativeLibrary(lpparam)

        // ---- Keyword match reply (no LLM needed) ----
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val trigger = MessageTrigger(
            contentRegex = null,
            talkerRegex = null,
            debounceMillis = 1_500L,
            maxEvents = 5,
            maxWaitMillis = 5_000L,
            cooldownMillis = 3_000L,
            filterOwnEvents = true,
        )

        val buffer = BufferedMessageTrigger(scope = scope, config = trigger) { messages ->
            val target = messages.lastOrNull() ?: return@BufferedMessageTrigger
            val talker = target.talker ?: return@BufferedMessageTrigger
            val content = target.content ?: return@BufferedMessageTrigger

            val text = content.replaceFirst(Regex("^wxid_\\w+:"), "").trim()
            XposedBridge.log("[AutoReply] onFlush: talker=$talker text=$text")

            // Record this talker so the settings UI can show it for whitelist selection
            val isGroup = talker.endsWith("@chatroom")
            runCatching { WhitelistStore.addSeenTalker(talker, talker, isGroup) }

            // Whitelist check: if non-empty, only reply to listed talkers
            val whitelist = WhitelistStore.list()
            if (whitelist.isNotEmpty() && whitelist.none { target.talker == it.id }) {
                XposedBridge.log("[AutoReply] skipped: talker=$talker not in whitelist")
                return@BufferedMessageTrigger
            }

            val reply = when {
                text.contains("在吗") -> "在的，自动回复"
                else -> "已收到：「$text」——这是自动回复 🤖"
            }

            // 防风控：随机延迟 2~5 秒再发送，模拟真人回复节奏
            val delayMs = (2_000L..5_000L).random()
            XposedBridge.log("[AutoReply] delaying ${delayMs}ms before reply")
            kotlinx.coroutines.delay(delayMs)

            XposedBridge.log("[AutoReply] sending reply: $reply")
            hook.sendText(talker, reply)
        }

        hook.onEnable()
        hook.addInsertListener(buffer)
        XposedBridge.log("[AutoReply] Keyword reply engine started. Waiting for messages…")
    }

    @Suppress("UnsafeDynamicallyLoadedCode")
    private fun loadNativeLibrary(param: XC_LoadPackage.LoadPackageParam) {
        val apk = modulePath ?: return
        try {
            val tmpSo = File(param.appInfo.dataDir, "libdexkit_extracted.so")
            java.util.zip.ZipFile(apk).use { zip ->
                val abi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val entry = zip.getEntry("lib/$abi/libdexkit.so") ?: return
                zip.getInputStream(entry).use { it.copyTo(tmpSo.outputStream()) }
            }
            System.load(tmpSo.absolutePath)
            XposedBridge.log("[AutoReply] native lib loaded")
        } catch (e: Exception) {
            XposedBridge.log("[AutoReply] lib load failed: ${e.message}")
        }
    }
}