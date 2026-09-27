package dev.example.autoreply.hook

import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.example.autoreply.ctwing.CtwingBundleCapture
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
import kotlinx.coroutines.launch
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

            // ---- A1: capture SPA JS/CSS bundles for offline reverse engineering ----
            runCatching {
                CtwingBundleCapture.hook(lpparam.classLoader)
                XposedBridge.log("[AutoReply] CTWing Bundle capture installed ($processName)")
            }.onFailure { XposedBridge.log("[AutoReply] CTWing Bundle capture FAILED ($processName): ${it.message}") }
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

        // 初始化白名单存储目录（用微信 dataDir，每个实例独立，双开安全）
        WhitelistStore.initWithDataDir(lpparam.appInfo.dataDir)
        XposedBridge.log("[AutoReply] WhitelistStore.init dataDir=${lpparam.appInfo.dataDir}")

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
            // 按 (talker + 内容) 去重：不同命令各自回复，完全相同才合并
            val deduped = messages.asReversed()
                .distinctBy { (it.talker ?: "") to (it.content ?: "") }
                .reversed()
            if (deduped.isEmpty()) return@BufferedMessageTrigger
            XposedBridge.log("[AutoReply] onFlush: batch=${messages.size} deduped=${deduped.size}")

            for (target in deduped) {
                val talker = target.talker ?: continue
                val content = target.content ?: continue

                // 提取发送者 wxid（群聊消息格式：wxid_xxx:\n@昵称 正文）
                val senderWxId = Regex("^(wxid_\\w+):").find(content)?.groupValues?.get(1)
                val text = content.replaceFirst(Regex("^wxid_\\w+:"), "")
                    .replaceFirst(Regex("^@\\S+[\\s\u2005]+"), "")
                    .trim()
                XposedBridge.log("[AutoReply] onFlush: talker=$talker text=$text")

                val whitelist = WhitelistStore.list()
                // 白名单为空 = 不回复任何人；非空 = 只回复白名单内的会话
                if (whitelist.isEmpty() || whitelist.none { target.talker == it.id }) {
                    XposedBridge.log("[AutoReply] skipped: talker=$talker not in whitelist (whitelistSize=${whitelist.size})")
                    continue
                }

                // ---- 群聊 @ 过滤：只有 @ 了机器人才处理 ----
                val isGroup = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
                if (isGroup) {
                    var atMe = false
                    if (target.lvBuffer != null) {
                        atMe = AtParser.isAtMeOrAll(target.lvBuffer, hook.selfWxId)
                    }
                    if (!atMe) {
                        atMe = WeDatabaseApi.isAtMe(target.msgSvrId, hook.selfWxId)
                    }
                    // 兜底：内容中包含 @机器人昵称（手动打的 @，无 atuserlist）
                    if (!atMe) {
                        val selfWxId = hook.selfWxId
                        val selfNick = selfWxId?.let { WeDatabaseApi.getNickname(it) }
                        atMe = selfNick != null && content.contains("@$selfNick")
                    }
                    if (!atMe) {
                        XposedBridge.log("[AutoReply] skipped: group message not @me (talker=$talker)")
                        continue
                    }
                    XposedBridge.log("[AutoReply] group @me detected: talker=$talker")
                }

                // 群聊回复包装：@昵称 + 回复内容
                fun wrapReply(replyText: String): String {
                    if (!isGroup || senderWxId == null) return replyText
                    val nickname = WeDatabaseApi.getNickname(senderWxId) ?: senderWxId
                    return "@$nickname\n$replyText"
                }

                // 群聊回复前设置真实 @ 通知目标
                fun pendAt() {
                    if (isGroup && senderWxId != null) {
                        AtMentionHook.pend(talker, senderWxId)
                    }
                }

                // ---- CTWing keyword routing ----
                val ctwHandled = CtwingKeywordRouter.tryHandle(target) { replyText ->
                    val delayMs = (2_000L..5_000L).random()
                    XposedBridge.log("[AutoReply] CTWing delay ${delayMs}ms")
                    kotlinx.coroutines.delay(delayMs)
                    XposedBridge.log("[AutoReply] CTWing reply: $replyText")
                    pendAt()
                    hook.sendText(talker, wrapReply(replyText))
                }
                if (ctwHandled) continue

                // 非 CTWing 命令 → 统一回复使用帮助
                val reply = """
                    🤖 欢迎使用天翼物联一站式服务工具！
                    
                    📋 可用命令：
                    · 查询 ICCID或接入号 — 查询卡片详情
                    · 诊断 ICCID或接入号 — 诊断卡片情况
                    · 重绑 ICCID或接入号 — 机卡重绑
                    
                    💡 使用方式：@我 + 命令，例如：
                    @我 查询 89860012345678901234
                """.trimIndent()

                val delayMs = (2_000L..5_000L).random()
                XposedBridge.log("[AutoReply] delaying ${delayMs}ms before reply")
                kotlinx.coroutines.delay(delayMs)

                XposedBridge.log("[AutoReply] sending reply: ${reply.take(60)}…")
                pendAt()
                hook.sendText(talker, wrapReply(reply))
            } // end for deduped
        }

        hook.onEnable()
        hook.addInsertListener(buffer)
        XposedBridge.log("[AutoReply] Engine started. Waiting for messages…")

        // ---- 白名单：初始化数据库 + hook 微信主界面弹选择器 ----
        installWhitelistPicker(lpparam.classLoader, scope)

        // ---- 群聊真实 @ 通知：Hook 消息入库 + 注入 atuserlist ----
        AtMentionHook.init(lpparam.classLoader)

        // Start token keep-alive
        dev.example.autoreply.ctwing.TokenKeepAlive.start(scope)

        // ★ 自建不可见 WebView —— 方向B已证失败，恢复偷取方案。
        // 结论：pinus.sdk.WebView 裸 new + addView 会在 View 布局(sizeChange→
        // onCheckIsTextEditor)时 NPE，因为 reflectInterface 委托必须先由 Pinus
        // 内核(经微信 MMWebViewUI 初始化流程)注入。自建 WebView 不可行。
        scope.launch {
            kotlinx.coroutines.delay(8_000L)
            XposedBridge.log("[AutoReply] init: using preInitH5 steal path")
            dev.example.autoreply.ctwing.CtwingFacade.preInitH5()
        }
    }

    /**
     * 白名单入口：初始化数据库 + 在微信首页右上角"+"菜单中注入菜单项。
     */
    private fun installWhitelistPicker(
        classLoader: ClassLoader,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        // 1. WeDatabaseApi（DexKit 找 MMKernel → 反射拿 SQLiteDatabase）
        WeDatabaseApi.init(classLoader)
        // 2. "+" 菜单注入
        PopupMenuHook.init(classLoader)
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