package dev.example.autoreply.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.example.autoreply.tunnel.TunnelConfig
import dev.example.autoreply.tunnel.TunnelManager

/**
 * 内网穿透设置面板：全局开关 + serverAddr/serverPort/authToken/remotePort 配置。
 * 由 PopupMenuHook 点击「内网穿透」菜单项后弹出。
 */
@Composable
fun TunnelScreen(
    onClose: (() -> Unit)? = null,
) {
    var cfg by remember { mutableStateOf(TunnelConfig.current()) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("内网穿透", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (onClose != null) TextButton(onClick = onClose) { Text("关闭") }
        }

        Spacer(Modifier.height(16.dp))

        // 全局开关
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("启用内网穿透", fontSize = 16.sp)
            Switch(
                checked = cfg.enabled,
                onCheckedChange = { cfg = cfg.copy(enabled = it) },
            )
        }

        Spacer(Modifier.height(12.dp))

        // 连接状态（仅开启时显示）
        if (cfg.enabled) {
            val status = TunnelManager.status()
            val running = status["running"] as? Boolean ?: false
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (running) "🟢 已连接" else "🔴 未连接",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "远程端口: ${status["remotePort"]}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 配置字段
        if (cfg.enabled) {
            OutlinedTextField(
                value = cfg.serverAddr,
                onValueChange = { cfg = cfg.copy(serverAddr = it) },
                label = { Text("服务器地址（域名或 IP）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = cfg.serverPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { cfg = cfg.copy(serverPort = it) } },
                label = { Text("控制端口") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = cfg.authToken,
                onValueChange = { cfg = cfg.copy(authToken = it) },
                label = { Text("认证 Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = cfg.remotePort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { cfg = cfg.copy(remotePort = it) } },
                label = { Text("远程端口（服务端已分配的固定端口）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        Spacer(Modifier.height(12.dp))

        // 自检测试卡号（与隧道开关无关，始终显示）
        OutlinedTextField(
            value = cfg.selfCheckCard,
            onValueChange = { cfg = cfg.copy(selfCheckCard = it) },
            label = { Text("自检测试卡号（ICCID 或接入号）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        // 保存按钮
        Button(
            onClick = {
                TunnelConfig.save(cfg)
                saved = true
                if (cfg.enabled) {
                    TunnelManager.applyConfigAndRestart()
                } else {
                    TunnelManager.stop()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("💾 保存并生效")
        }

        if (saved) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text("✅ 设置已保存", Modifier.padding(12.dp), fontSize = 14.sp)
            }
        }
    }
}