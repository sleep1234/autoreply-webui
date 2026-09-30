package dev.example.autoreply.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.example.autoreply.ctwing.CtwingFacade
import dev.example.autoreply.ctwing.CtwingWebViewHook
import dev.example.autoreply.ctwing.NativeHttp
import dev.example.autoreply.tunnel.TunnelManager
import dev.example.autoreply.hook.TinkerGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务状态面板：由 PopupMenuHook 点击「服务状态」菜单项后弹出。
 * 显示当前模块运行状态 + token 信息 + 一键续期按钮。
 */
@Composable
fun StatusScreen(
    onClose: (() -> Unit)? = null,
) {
    var webViewAlive by remember { mutableStateOf<Boolean?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    var tokenValid by remember { mutableStateOf<Boolean?>(null) }
    var whitelistCount by remember { mutableStateOf(0) }
    var whitelistEmpty by remember { mutableStateOf(true) }
    var tinkerSummary by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var renewing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun loadStatus() {
        scope.launch {
            loading = true
            message = ""
            withContext(Dispatchers.IO) {
                try {
                    val wv = CtwingWebViewHook.currentWebView()
                    webViewAlive = wv != null
                    token = NativeHttp.cachedToken
                    val whitelist = WhitelistStore.list()
                    whitelistCount = whitelist.size
                    whitelistEmpty = whitelist.isEmpty()
                    tinkerSummary = TinkerGuard.statusSummary()
                    // 真实验证
                    tokenValid = if (token.isNullOrBlank()) {
                        false
                    } else {
                        runCatching {
                            val body = NativeHttp.basicInfo(token!!, "iccid", "89860620140020723456")
                            !body.contains("\"code\":401")
                        }.getOrDefault(false)
                    }
                } catch (e: Exception) {
                    message = "加载失败：${e.message}"
                }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadStatus() }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("服务状态", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (onClose != null) TextButton(onClick = onClose) { Text("关闭") }
        }

        Spacer(Modifier.height(20.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // 状态卡片
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(16.dp)) {
                    StatusRow("后台服务", if (webViewAlive == true) "✅ 正常" else "❌ WebView 丢失")
                    Spacer(Modifier.height(8.dp))
                    val tokenState = when {
                        token.isNullOrBlank() -> "❌ 无 token"
                        tokenValid == true -> "✅ 有效"
                        tokenValid == false -> "⚠️ 已过期"
                        else -> "❓ 未知"
                    }
                    StatusRow("登录态", tokenState)
                    if (!token.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "token：${token!!.take(12)}…",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val wlState = if (whitelistEmpty) "未启用（不回复任何人）" else "已启用（${whitelistCount} 个会话）"
                    StatusRow("白名单", wlState)

                    Spacer(Modifier.height(8.dp))
                    val tunnel = TunnelManager.status()
                    val tunnelEnabled = tunnel["enabled"] as? Boolean ?: false
                    val tunnelRunning = tunnel["running"] as? Boolean ?: false
                    val tunnelState = when {
                        !tunnelEnabled -> "未启用"
                        tunnelRunning -> "✅ 已连接（远程端口 ${tunnel["remotePort"]}）"
                        else -> "🔴 未连接"
                    }
                    StatusRow("内网穿透", tunnelState)
                }
            }

            // 热更新拦截（TinkerGuard）
            if (tinkerSummary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("🛡️ 热更新拦截", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tinkerSummary,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 续期按钮
            Button(
                onClick = {
                    scope.launch {
                        renewing = true
                        message = "正在强制刷新…"
                        withContext(Dispatchers.IO) {
                            try {
                                CtwingFacade.forceRebuild(40_000L)
                                CtwingFacade.pullToken()
                                loadStatus()
                            } catch (e: Exception) {
                                message = "续期失败：${e.message}"
                            }
                        }
                        renewing = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !renewing,
            ) {
                if (renewing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (renewing) "续期中…" else "🔄 一键续期")
            }

            Spacer(Modifier.height(8.dp))

            // 刷新状态按钮
            OutlinedButton(
                onClick = { loadStatus() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
            ) {
                Text("🔄 刷新状态")
            }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}