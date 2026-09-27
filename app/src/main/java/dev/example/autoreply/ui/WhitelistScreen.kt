package dev.example.autoreply.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 白名单选择器。
 *
 * 两种使用场景：
 *   1. 微信进程内弹窗（WhitelistLauncher）：传入 initialConversations（rconversation 查询结果）
 *      勾选后调用 WhitelistStore.setList 保存。
 *   2. 独立 SettingsActivity：无参，用 WhitelistStore.list() 展示已保存白名单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen(
    initialConversations: List<WhitelistEntry>? = null,
    onClose: (() -> Unit)? = null,
) {
    // 白名单（已保存）
    var entries by remember { mutableStateOf(WhitelistStore.list()) }
    // 勾选状态：初始 = 已保存的白名单（打开时正确回显）
    var selected by remember { mutableStateOf(WhitelistStore.list().map { it.id }.toSet()) }
    // 搜索
    var query by remember { mutableStateOf("") }

    val conversations = initialConversations ?: entries
    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter {
            it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
        }
    }

    fun refresh() {
        entries = WhitelistStore.list()
        selected = entries.map { it.id }.toSet()
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "自动回复白名单",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "勾选后仅这些会话会自动回复（${selected.size} 个）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                if (onClose != null) {
                    TextButton(onClick = onClose) { Text("关闭") }
                }
            }
        }

        // 搜索框
        if (initialConversations != null) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索会话名或 wxid") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 列表
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (initialConversations != null) "没有找到会话" else "白名单为空，将自动回复所有会话",
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                items(filtered, key = { it.id }) { conv ->
                    val checked = conv.id in selected
                    Surface(
                        onClick = {
                            selected = if (checked) selected - conv.id else selected + conv.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (checked) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (conv.isGroup) Icons.Default.Group else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (conv.isGroup) MaterialTheme.colorScheme.tertiary
                                       else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(conv.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                Text(
                                    (if (conv.isGroup) "群聊 · " else "私聊 · ") + conv.id,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    }
                }
            }
        }

        // 底部保存栏（仅微信进程弹窗用）
        if (initialConversations != null) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            // 全选
                            selected = filtered.map { it.id }.toSet()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("全选") }
                    OutlinedButton(
                        onClick = {
                            // 清空并立即保存
                            selected = emptySet()
                            WhitelistStore.setList(emptyList())
                            refresh()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("清空") }
                    Button(
                        onClick = {
                            val saved = conversations.filter { it.id in selected }
                            WhitelistStore.setList(saved)
                            refresh()
                            onClose?.invoke()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存") }
                }
            }
        } else {
            // 独立 SettingsActivity：展示已保存白名单，可删除
            if (entries.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Text(
                        "提示：此页面为独立设置入口。\n完整勾选选择器请在微信内打开。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun WhitelistRow(entry: WhitelistEntry, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.isGroup) Icons.Default.Group else Icons.Default.Person,
                contentDescription = null,
                tint = if (entry.isGroup) MaterialTheme.colorScheme.tertiary
                       else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(
                    (if (entry.isGroup) "群聊 · " else "私聊 · ") + entry.id,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}