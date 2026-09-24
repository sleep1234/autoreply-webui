package dev.example.autoreply.ui

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences

data class WhitelistEntry(val id: String, val name: String, val isGroup: Boolean)

/**
 * 白名单 + 最近会话存储。
 *
 * 跨进程共享方案：
 *   - 写入：UI 进程通过 Context.SharedPreferences（Editor.commit + sync 保证落地）
 *   - 读取：微信 hook 通过 LSPosed 的 XSharedPreferences（自动刷新）
 */
object WhitelistStore {

    private const val PREFS_NAME = "autoreply_prefs"

    @Volatile
    private var uiPrefs: SharedPreferences? = null

    /** UI 侧（模块进程 dev.example.autoreply）必须调用此方法初始化写入能力 */
    fun initForUi(ctx: Context) {
        uiPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun readPrefs(): SharedPreferences {
        // UI 进程直接用缓存
        uiPrefs?.let { return it }
        // 微信 hook 进程走 XSharedPreferences
        val sp = XSharedPreferences("dev.example.autoreply", PREFS_NAME)
        sp.makeWorldReadable()
        sp.reload()
        return sp
    }

    // ---- 白名单 CRUD ----
    fun list(): List<WhitelistEntry> {
        return decode(readPrefs().getString("whitelist", "[]") ?: "[]")
    }

    fun add(id: String, name: String, isGroup: Boolean) {
        val entries = list().toMutableList()
        if (entries.none { it.id == id }) {
            entries.add(WhitelistEntry(id, name, isGroup))
            write("whitelist", entries)
        }
    }

    fun remove(id: String) {
        write("whitelist", list().filter { it.id != id })
    }

    fun isEmpty(): Boolean = list().isEmpty()

    // ---- 最近会话 ----
    fun addSeenTalker(id: String, displayName: String, isGroup: Boolean) {
        val seen = seenTalkers().toMutableList()
        if (seen.none { it.id == id }) {
            seen.add(0, WhitelistEntry(id, displayName.ifBlank { id }, isGroup))
            if (seen.size > 100) seen.removeAt(seen.lastIndex)
            write("seen", seen)
        }
    }

    fun seenTalkers(): List<WhitelistEntry> {
        return decode(readPrefs().getString("seen", "[]") ?: "[]")
    }

    // ---- 编码 & 写入 ----
    private fun write(key: String, list: List<WhitelistEntry>) {
        val json = encode(list)
        // 只有 UI 进程有 true SharedPreferences（可写）；微信 hook 只能读
        val sp = uiPrefs
        if (sp != null) {
            // 同步写入——apply() 异步可能被进程杀死导致丢失
            sp.edit().putString(key, json).apply()
            // 额外 commit 同步写入 _ts 确保 prefs 文件刷新
            sp.edit().putString("_ts", System.currentTimeMillis().toString()).commit()
        }
    }

    private fun encode(list: List<WhitelistEntry>): String {
        if (list.isEmpty()) return "[]"
        return "[" + list.joinToString(",") { e ->
            """{"id":"${e.id.esc()}","name":"${e.name.esc()}","isGroup":${e.isGroup}}"""
        } + "]"
    }

    private fun decode(raw: String): List<WhitelistEntry> {
        if (raw.isBlank() || raw.trim() == "[]") return emptyList()
        return runCatching {
            val inner = raw.trim().removePrefix("[").removeSuffix("]")
            if (inner.isBlank()) return emptyList()
            inner.split("},{").map { block ->
                val s = block.removePrefix("{").removeSuffix("}")
                var id = ""
                var name = ""
                var isGroup = false
                for (key in listOf("\"id\":\"", "id\":\"")) {
                    val idx = s.indexOf(key)
                    if (idx >= 0) {
                        val start = idx + key.length
                        val end = s.indexOf('"', start)
                        if (end >= 0) { id = s.substring(start, end); break }
                    }
                }
                for (key in listOf("\"name\":\"", "name\":\"")) {
                    val idx = s.indexOf(key)
                    if (idx >= 0) {
                        val start = idx + key.length
                        val end = s.indexOf('"', start)
                        if (end >= 0) { name = s.substring(start, end); break }
                    }
                }
                isGroup = s.contains("\"isGroup\":true") || s.contains("isGroup\":true")
                WhitelistEntry(id, name, isGroup)
            }
        }.getOrDefault(emptyList())
    }

    private fun String.esc(): String = this.replace("\\", "\\\\").replace("\"", "\\\"")
}