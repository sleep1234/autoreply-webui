package dev.example.autoreply.ui

import android.content.Context
import android.content.SharedPreferences
import java.io.File

data class WhitelistEntry(val id: String, val name: String, val isGroup: Boolean)

/**
 * 白名单存储。
 *
 * 白名单数据最终消费者是微信进程（onFlush 过滤）。UI 也跑在微信进程内
 * （WhitelistLauncher），所以白名单直接存模块自己的 dataDir 文件，同进程读写。
 *
 *   - 主存储：{模块dataDir}/files/autoreply_whitelist.json
 *     (如 /data/data/dev.example.autoreply/files/autoreply_whitelist.json)
 *   - 不在微信 dataDir 下存储——双开/多用户环境下路径不同，
 *     且 SELinux 可能拦截跨包写入。
 *
 * 通过 createPackageContext 动态获取模块目录，适配双开/多用户场景。
 */
object WhitelistStore {

    private const val PREFS_NAME = "autoreply_prefs"

    @Volatile
    private var filesDir: File? = null

    /** 在微信进程启动时调用一次，传入微信 dataDir（lpparam.appInfo.dataDir）。
     *  每个微信实例（主微信/双开）有独立 dataDir，双开安全。 */
    fun initWithDataDir(dataDir: String) {
        val dir = File(dataDir, "files/autoreply")
        dir.mkdirs()
        filesDir = dir
    }

    /** 在微信进程启动时调用一次，传入微信 Context 以获取 filesDir。 */
    fun init(ctx: Context) {
        if (filesDir != null) return
        val dir = File(ctx.filesDir, "autoreply")
        dir.mkdirs()
        filesDir = dir
    }

    @Volatile
    private var uiPrefs: SharedPreferences? = null

    /** 模块 UI 进程初始化写入能力（独立 SettingsActivity 用） */
    fun initForUi(ctx: Context) {
        uiPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 获取模块存储目录下的文件，每次调用确保目录存在。 */
    private fun moduleFile(filename: String): File {
        val dir = filesDir
        if (dir == null) {
            // 兜底：直接写微信 filesDir（兼容 init 未调用场景）
            val fallback = File("/data/data/com.tencent.mm/files/autoreply")
            fallback.mkdirs()
            return File(fallback, filename)
        }
        dir.mkdirs()
        return File(dir, filename)
    }

    // ---- 白名单读取 ----
    fun list(): List<WhitelistEntry> {
        val f = moduleFile("autoreply_whitelist.json")
        if (f.exists()) {
            val raw = runCatching { f.readText() }.getOrNull()
            if (!raw.isNullOrBlank()) return decode(raw)
        }
        return emptyList()
    }

    // ---- 白名单写入 ----
    fun setList(entries: List<WhitelistEntry>) {
        val json = encode(entries)
        val f = moduleFile("autoreply_whitelist.json")
        runCatching { f.writeText(json) }
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