package dev.example.autoreply.web

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 账号存储：JSON 文件，admin 可管理所有用户，普通用户只能改自己密码。
 */
object AccountStore {

    private const val FILE_NAME = "accounts.json"
    private var dataDir: File? = null

    data class Account(
        val username: String,
        val passwordHash: String,
        val role: String = "user",   // "admin" | "user"
        val createdAt: Long = System.currentTimeMillis()
    )

    fun init(dir: File) {
        dataDir = File(dir, FILE_NAME)
        if (!dataDir!!.exists()) {
            // 首次启动：创建默认 admin 账户，密码 admin123
            val admin = Account("admin", sha256("admin123"), "admin")
            saveList(listOf(admin))
        }
    }

    /** 验证用户名密码，返回 Account 或 null。 */
    fun authenticate(username: String, password: String): Account? {
        val hash = sha256(password)
        return loadList().firstOrNull { it.username == username && it.passwordHash == hash }
    }

    /** 列出所有账户（不含密码哈希）。 */
    fun list(): List<Map<String, Any>> {
        return loadList().map {
            mapOf("username" to it.username, "role" to it.role, "createdAt" to it.createdAt)
        }
    }

    /** 添加账户（admin only）。 */
    fun add(operator: Account, username: String, password: String, role: String): String? {
        if (operator.role != "admin") return "仅管理员可操作"
        if (username.isBlank() || password.isBlank()) return "用户名和密码不能为空"
        val accounts = loadList().toMutableList()
        if (accounts.any { it.username == username }) return "用户名已存在"
        if (role !in listOf("admin", "user")) return "角色无效"
        accounts.add(Account(username, sha256(password), role))
        saveList(accounts)
        return null
    }

    /** 删除账户（admin only，不能删自己）。 */
    fun delete(operator: Account, username: String): String? {
        if (operator.role != "admin") return "仅管理员可操作"
        if (username == operator.username) return "不能删除自己"
        val accounts = loadList().toMutableList()
        if (!accounts.removeAll { it.username == username }) return "用户不存在"
        saveList(accounts)
        return null
    }

    /** 修改自己的密码。 */
    fun changePassword(operator: Account, oldPassword: String, newPassword: String): String? {
        val hash = sha256(oldPassword)
        if (hash != operator.passwordHash) return "旧密码错误"
        if (newPassword.isBlank()) return "新密码不能为空"
        val accounts = loadList().toMutableList()
        val idx = accounts.indexOfFirst { it.username == operator.username }
        if (idx < 0) return "账户不存在"
        accounts[idx] = accounts[idx].copy(passwordHash = sha256(newPassword))
        saveList(accounts)
        return null
    }

    /** 管理员重置任意用户密码。 */
    fun resetPassword(operator: Account, username: String, newPassword: String): String? {
        if (operator.role != "admin") return "仅管理员可操作"
        if (newPassword.isBlank()) return "新密码不能为空"
        val accounts = loadList().toMutableList()
        val idx = accounts.indexOfFirst { it.username == username }
        if (idx < 0) return "用户不存在"
        accounts[idx] = accounts[idx].copy(passwordHash = sha256(newPassword))
        saveList(accounts)
        return null
    }

    // ---- 内部 ----

    private fun loadList(): List<Account> {
        val f = dataDir ?: return emptyList()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Account(
                    username = obj.getString("username"),
                    passwordHash = obj.getString("passwordHash"),
                    role = obj.optString("role", "user"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveList(list: List<Account>) {
        val f = dataDir ?: return
        f.parentFile?.mkdirs()
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().apply {
                put("username", a.username)
                put("passwordHash", a.passwordHash)
                put("role", a.role)
                put("createdAt", a.createdAt)
            })
        }
        f.writeText(arr.toString(2))
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}