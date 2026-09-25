package dev.example.autoreply.hook

import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.example.autoreply.ctwing.CtwingIpcBridge
import dev.example.autoreply.ctwing.CtwingKeywordRouter
import dev.example.autoreply.ctwing.CtwingNetworkHook
import dev.example.autoreply.ctwing.CtwingWebViewHook
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
        TinkerGuard.hookClassLoaderFilter(startupParam)
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return

        val processName = lpparam.processName ?: "unknown"
        val isMain = processName == "com.tencent.mm"

        // ---- CTWing WebView hook: main + xweb processes ----
        // H5 pages (MMWebViewUI) load in the main process, but the X5/XWeb
        // WebView kernel (com.tencent.xweb) may call loadUrl from the
        // xweb_privileged_process_0 / xweb_sandboxed_process_0 processes.
        // We install the lightweight WebView hook (no native lib) in ALL
        // these processes so we catch the CTWing URL regardless of which
        // process performs the load.
        val isWebViewProcess = isMain ||
            processName.contains("xweb") ||
            processName.contains("tools")

        if (isWebViewProcess) {
            runCatching {
                CtwingWebViewHook.hook(lpparam.classLoader)
                XposedBridge.log("[AutoReply] CTWing WebView hook installed ($processName)")
            }.onFailure { XposedBridge.log("[AutoReply] CTWing WebView hook FAILED ($processName): ${it.message}") }

            runCatching {
                CtwingNetworkHook.hook(lpparam.classLoader)
                XposedBridge.log("[AutoReply] CTWing Network hook installed ($processName)")
            }.onFailure { XposedBridge.log("[AutoReply] CTWing Network hook FAILED ($processName): ${it.message}") }
        }

        // ---- Engine (DexKit + TinkerGuard + message): MAIN process ONLY ----
        // DexKit's native lib crashes if loaded from multiple processes.
        if (!isMain) {
            XposedBridge.log("[AutoReply] skip process=$processName (non-main, webview-hook=${isWebViewProcess})")
            return
        }

        XposedBridge.log("[AutoReply] process=$processName (main, engine init)")

        // --- TinkerGuard ---
        try {
            TinkerGuard.wipePatchDirs(lpparam.appInfo.dataDir)
            TinkerGuard.hookTinkerApi(lpparam.classLoader)
        } catch (e: Exception) {
            XposedBridge.log("[AutoReply] TinkerGuard FAILED: ${e.message}")
        }

        loadNativeLibrary(lpparam)
        CtwingIpcBridge.wechatDataDir = lpparam.appInfo.dataDir

        // ---- WeChat message capture + send ----
        val hook = WeChatHook(lpparam.classLoader)

        // ---- Keyword match reply engine ----
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

            val isGroup = talker.endsWith("@chatroom")
            runCatching { WhitelistStore.addSeenTalker(talker, talker, isGroup) }

            val whitelist = WhitelistStore.list()
            if (whitelist.isNotEmpty() && whitelist.none { target.talker == it.id }) {
                XposedBridge.log("[AutoReply] skipped: talker=$talker not in whitelist")
                return@BufferedMessageTrigger
            }

            // ---- CTWing keyword routing ----
            val ctwHandled = CtwingKeywordRouter.tryHandle(target) { replyText ->
                val delayMs = (2_000L..5_000L).random()
                XposedBridge.log("[AutoReply] CTWing delay ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
                XposedBridge.log("[AutoReply] CTWing reply: $replyText")
                hook.sendText(talker, replyText)
            }
            if (ctwHandled) return@BufferedMessageTrigger

            // ---- Fallback keyword replies ----
            val reply = when {
                text.contains("在吗") -> "在的，自动回复"
                text.contains("帮助") || text.contains("help") -> """
                    🤖 自动回复帮助：
                    · 查询 <ICCID> — 查询卡基本信息
                    · 诊断 <ICCID> — 智能诊断
                    · 重绑 <ICCID> <IMEI> — 机卡重绑
                    · 环境 — 诊断 SPA 环境（调试用）
                    · 在吗 — 测试自动回复
                """.trimIndent()
                else -> "已收到：「$text」——这是自动回复 🤖\n发送「帮助」查看可用命令"
            }

            val delayMs = (2_000L..5_000L).random()
            XposedBridge.log("[AutoReply] delaying ${delayMs}ms before reply")
            kotlinx.coroutines.delay(delayMs)

            XposedBridge.log("[AutoReply] sending reply: ${reply.take(60)}…")
            hook.sendText(talker, reply)
        }

        hook.onEnable()
        hook.addInsertListener(buffer)
        XposedBridge.log("[AutoReply] Engine started. Waiting for messages…")
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