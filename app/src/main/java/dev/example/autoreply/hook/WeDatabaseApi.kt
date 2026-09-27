package dev.example.autoreply.hook

import android.database.Cursor
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.example.autoreply.ui.WhitelistEntry
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher

object WeDatabaseApi {

    private const val TAG = "[WeDB]"
    private const val WCDB_SQLITE = "com.tencent.wcdb.database.SQLiteDatabase"

    @Volatile
    var isReady = false

    @Volatile
    private var db: Any? = null

    fun init(classLoader: ClassLoader) {
        val bridge = runCatching { DexKitBridge.create(classLoader, true) }
            .onFailure { XposedBridge.log("$TAG DexKitBridge.create FAILED: ${it.message}") }
            .getOrNull() ?: return

        try {
            val mmKernelList = bridge.findClass(FindClass().apply {
                matcher { usingEqStrings("MicroMsg.MMKernel", "Kernel not null, has initialized.") }
            })
            val mmKernel = mmKernelList.singleOrNull()
            if (mmKernel == null) { XposedBridge.log("$TAG MMKernel not found"); return }
            XposedBridge.log("$TAG MMKernel: ${mmKernel.name}")

            val methods = bridge.findMethod(FindMethod().apply {
                matcher {
                    declaredClass(mmKernel.name)
                    modifiers(AccessFlagsMatcher(java.lang.reflect.Modifier.PUBLIC or java.lang.reflect.Modifier.STATIC))
                    paramCount(0)
                    usingStrings("mCoreStorage not initialized!")
                }
            })

            if (methods.isNotEmpty()) {
                val getStorage = methods.first()
                val methodObj = getStorage.getMethodInstance(classLoader)
                hookGetStorage(methodObj)
                XposedBridge.log("$TAG hooked getStorage: ${getStorage.name}")
            } else {
                XposedBridge.log("$TAG getStorage not found (${methods.size}), brute-force")
                hookAllStaticZeroParam(classLoader, mmKernel.name)
            }
        } finally {
            runCatching { bridge.close() }
        }
    }

    private fun hookGetStorage(method: java.lang.reflect.Method) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isReady) return
                val obj = param.result ?: return
                val ok = extractDbDeep(obj, 0)
                if (ok) XposedBridge.log("$TAG db acquired via getStorage")
            }
        })
    }

    private fun hookAllStaticZeroParam(classLoader: ClassLoader, className: String) {
        val kernelClass = classLoader.loadClass(className)
        var hooked = 0
        for (m in kernelClass.declaredMethods) {
            if (!java.lang.reflect.Modifier.isStatic(m.modifiers) || m.parameterCount != 0) continue
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isReady) return
                    val obj = param.result ?: return
                    if (extractDbDeep(obj, 0)) XposedBridge.log("$TAG db via ${param.method.name}")
                }
            })
            hooked++
        }
        XposedBridge.log("$TAG brute-force hooked $hooked methods")
    }

    /**
     * 深度递归：不限字段类型名。对 CoreStorage 每个非静态字段值，
     * 尝试找返回 SQLiteDatabase 的 0 参方法，并递归进入容器。
     * 加 visited 防止循环引用。
     */
    private fun extractDbDeep(obj: Any, depth: Int, visited: MutableSet<Int> = HashSet()): Boolean {
        if (depth > 6) return false
        val id = System.identityHashCode(obj)
        if (!visited.add(id)) return false

        if (obj.javaClass.name == WCDB_SQLITE) { db = obj; isReady = true; return true }

        // 1. 该对象本身有返回 SQLiteDatabase 的 0 参方法
        for (m in obj.javaClass.declaredMethods) {
            if (m.parameterCount == 0 && m.returnType.name == WCDB_SQLITE) {
                m.isAccessible = true
                val d = runCatching { m.invoke(obj) }.getOrNull() ?: continue
                db = d; isReady = true
                XposedBridge.log("$TAG db via ${obj.javaClass.simpleName}.${m.name}()")
                return true
            }
        }

        // 2. 遍历字段，递归找
        for (f in obj.javaClass.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            val value = runCatching { f.get(obj) }.getOrNull() ?: continue
            if (value == null) continue
            val vc = value.javaClass
            // 跳过基本类型/字符串/数组/集合包装
            if (vc.isPrimitive || vc == String::class.java || vc.isArray) continue
            if (vc.name.startsWith("java.") || vc.name.startsWith("android.") || vc.name.startsWith("kotlin.")) continue
            if (extractDbDeep(value, depth + 1, visited)) return true
        }
        return false
    }

    fun queryConversations(): List<WhitelistEntry> {
        val db_ = db ?: return emptyList()
        XposedBridge.log("$TAG queryConversations: db class = ${db_.javaClass.name}")
        return runCatching {
            // 找第一个名为 rawQuery、首参 String、第二个参数为 String[] 或 Object[] 的方法
            val rawQuery = db_.javaClass.methods.firstOrNull { m ->
                m.name == "rawQuery" &&
                    m.parameterCount >= 2 &&
                    m.parameterTypes[0] == String::class.java
            } ?: db_.javaClass.declaredMethods.firstOrNull { m ->
                m.name == "rawQuery" &&
                    m.parameterCount >= 2 &&
                    m.parameterTypes[0] == String::class.java
            }
            if (rawQuery == null) {
                XposedBridge.log("$TAG rawQuery method not found")
                return emptyList()
            }
            XposedBridge.log("$TAG rawQuery signature: ${rawQuery}")
            rawQuery.isAccessible = true

            val sql = """
                SELECT c.username, r.nickname, r.conRemark, r.alias
                FROM rconversation c
                LEFT JOIN rcontact r ON c.username = r.username
                ORDER BY c.conversationTime DESC
            """.trimIndent()

            // 根据参数个数构造调用
            val cursor: Cursor? = when (rawQuery.parameterCount) {
                2 -> rawQuery.invoke(db_, sql, null) as? Cursor
                3 -> rawQuery.invoke(db_, sql, null, null) as? Cursor
                4 -> rawQuery.invoke(db_, sql, null, null, null) as? Cursor
                else -> rawQuery.invoke(db_, sql) as? Cursor
            }
            if (cursor == null) {
                XposedBridge.log("$TAG cursor is null")
                return emptyList()
            }
            cursor.use { cur ->
                val result = mutableListOf<WhitelistEntry>()
                val uIdx = cur.getColumnIndexOrThrow("username")
                val nIdx = cur.getColumnIndexOrThrow("nickname")
                val cIdx = cur.getColumnIndexOrThrow("conRemark")
                val aIdx = cur.getColumnIndexOrThrow("alias")
                while (cur.moveToNext() && result.size < 500) {
                    val username = cur.getString(uIdx) ?: continue
                    val nickname = cur.getString(nIdx) ?: ""
                    val conRemark = cur.getString(cIdx) ?: ""
                    val alias = cur.getString(aIdx) ?: ""
                    val name = conRemark.ifBlank { nickname.ifBlank { alias.ifBlank { username } } }
                    result.add(WhitelistEntry(username, name, username.endsWith("@chatroom")))
                }
                XposedBridge.log("$TAG queryConversations: ${result.size} conversations")
                result
            }
        }.onFailure {
            XposedBridge.log("$TAG queryConversations failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 根据 msgSvrId 查 message 表的 lvbuffer 并解析是否 @ 了自己。 */
    fun isAtMe(msgSvrId: Long?, selfWxId: String?): Boolean {
        if (msgSvrId == null || selfWxId == null) return false
        val db_ = db ?: return false
        return runCatching {
            val rawQuery = db_.javaClass.methods.firstOrNull { m ->
                m.name == "rawQuery" && m.parameterCount >= 2 && m.parameterTypes[0] == String::class.java
            } ?: return false
            rawQuery.isAccessible = true
            val sql = "SELECT lvbuffer FROM message WHERE msgSvrId = $msgSvrId"
            val cursor = when (rawQuery.parameterCount) {
                2 -> rawQuery.invoke(db_, sql, null)
                3 -> rawQuery.invoke(db_, sql, null, null)
                else -> rawQuery.invoke(db_, sql)
            } as? android.database.Cursor ?: return false

            cursor.use { cur ->
                if (!cur.moveToFirst()) return false
                val lv = cur.getBlob(0) ?: return false
                XposedBridge.log("$TAG isAtMe: lvbuffer len=${lv.size}")
                return AtParser.isAtMeOrAll(lv, selfWxId)
            }
        }.onFailure {
            XposedBridge.log("$TAG isAtMe failed: ${it.message}")
        }.getOrDefault(false)
    }
}