package dev.example.autoreply.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhitelistScreen() {
    var entries by remember { mutableStateOf(WhitelistStore.list()) }
    var seen by remember { mutableStateOf(WhitelistStore.seenTalkers()) }
    var showDialog by remember { mutableStateOf(false) }

    fun refresh() {
        entries = WhitelistStore.list()
        seen = WhitelistStore.seenTalkers()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动回复白名单") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 最近会话（从微信消息中捕获的 talker） ----
            if (seen.isNotEmpty()) {
                item {
                    Text(
                        "最近会话（点选加入白名单）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(seen, key = { "seen-${it.id}" }) { s ->
                    val alreadyAdded = entries.any { it.id == s.id }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.medium,
                        onClick = {
                            if (!alreadyAdded) {
                                WhitelistStore.add(s.id, s.name, s.isGroup)
                                refresh()
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (s.isGroup) Icons.Default.Group else Icons.Default.Person,
                                contentDescription = null,
                                tint = if (s.isGroup) MaterialTheme.colorScheme.tertiary
                                       else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (s.isGroup) "群聊" else "私聊",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    s.id,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                )
                            }
                            if (alreadyAdded) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "已加入",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text("添加", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                            }
                        }
                    }
                }
                item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            }

            // ---- 白名单 ----
            item {
                Text(
                    "白名单（${entries.size}）",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        "白名单为空，将自动回复所有会话。\n添加后仅名单内的会话会自动回复。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    WhitelistRow(
                        entry = entry,
                        onDelete = {
                            WhitelistStore.remove(entry.id)
                            refresh()
                        }
                    )
                }
            }
        }
    }

    if (showDialog) {
        AddWhitelistDialog(
            onDismiss = { showDialog = false },
            onAdd = { id, name, isGroup ->
                WhitelistStore.add(id, name, isGroup)
                refresh()
                showDialog = false
            }
        )
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
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(
                    if (entry.isGroup) "群聊 · ${entry.id}" else "私聊 · ${entry.id}",
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

@Composable
fun AddWhitelistDialog(
    onDismiss: () -> Unit,
    onAdd: (id: String, name: String, isGroup: Boolean) -> Unit,
) {
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isGroup by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到白名单") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("会话ID (wxid 或 @chatroom)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("备注名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("会话类型：", modifier = Modifier.padding(end = 8.dp))
                    FilterChip(
                        selected = !isGroup,
                        onClick = { isGroup = false },
                        label = { Text("私聊") },
                        leadingIcon = { Icon(Icons.Default.Person, null, Modifier.size(18.dp)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    FilterChip(
                        selected = isGroup,
                        onClick = { isGroup = true },
                        label = { Text("群聊") },
                        leadingIcon = { Icon(Icons.Default.Group, null, Modifier.size(18.dp)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(id.trim(), name.trim().ifBlank { id.trim() }, isGroup) },
                enabled = id.isNotBlank(),
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}