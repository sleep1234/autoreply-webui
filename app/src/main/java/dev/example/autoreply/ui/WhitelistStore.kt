package dev.example.autoreply.ui

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import java.io.File

data class WhitelistEntry(val id: String, val name: String, val isGroup: Boolean)

/**
 * 白名单存储。
 *
 * 白名单数据最终消费者是微信进程（onFlush 过滤）。UI 也跑在微信进程内
 * （WhitelistLauncher），所以白名单直接存微信 dataDir 文件，同进程读写，
 * 零跨进程、零 SELinux 问题。
 *
 *   - 主存储：/data/data/com.tencent.mm/files/autoreply_whitelist.json
 *   - 兜底（独立 SettingsActivity 用）：模块 prefs 的 "whitelist" key
 *
 * 读取优先级：微信 dataDir 文件 → 模块 prefs。
 * 写入：微信进程写 dataDir 文件；模块 UI 进程写 prefs（兼容旧入口）。
 */
object WhitelistStore {

    private const val PREFS_NAME = "autoreply_prefs"
    private const val WHITELIST_FILE = "/data/data/com.tencent.mm/files/autoreply_whitelist.json"

    @Volatile
    private var uiPrefs: SharedPreferences? = null

    /** 模块 UI 进程初始化写入能力（独立 SettingsActivity 用） */
    fun initForUi(ctx: Context) {
        uiPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 读取 prefs：模块 UI 进程用本地 prefs，微信进程用 XSharedPreferences */
    private fun xprefs(): SharedPreferences {
        uiPrefs?.let { return it }
        val sp = XSharedPreferences("dev.example.autoreply", PREFS_NAME)
        sp.makeWorldReadable()
        sp.reload()
        return sp
    }

    // ---- 白名单读取 ----
    fun list(): List<WhitelistEntry> {
        // 1. 微信 dataDir 文件（微信进程写的主存储）
        val f = File(WHITELIST_FILE)
        if (f.exists()) {
            val raw = runCatching { f.readText() }.getOrNull()
            if (!raw.isNullOrBlank()) return decode(raw)
        }
        // 2. 模块 prefs 兜底
        return decode(xprefs().getString("whitelist", "[]") ?: "[]")
    }

    // ---- 白名单写入 ----
    fun setList(entries: List<WhitelistEntry>) {
        val json = encode(entries)
        // 微信进程：写 dataDir 文件（主）
        runCatching {
            File(WHITELIST_FILE).writeText(json)
        }
        // 模块 UI 进程：写 prefs（兼容）
        uiPrefs?.edit()?.putString("whitelist", json)?.commit()
    }

    fun add(id: String, name: String, isGroup: Boolean) {
        val entries = list().toMutableList()
        if (entries.none { it.id == id }) {
            entries.add(WhitelistEntry(id, name, isGroup))
            setList(entries)
        }
    }

    fun remove(id: String) = setList(list().filter { it.id != id })

    fun isEmpty(): Boolean = list().isEmpty()

    fun contains(id: String): Boolean = list().any { it.id == id }

    // ---- 编码 ----
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
                var id = ""; var name = ""; var isGroup = false
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