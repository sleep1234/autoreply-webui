package dev.example.autoreply.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher

/**
 * 群聊真实 @ 通知：在发送回复消息入库前，向 msgsource 注入 atuserlist。
 *
 * 参照 WeKit MentionMembers 的"隐蔽@"模式核心原理：
 *  1. 发送前设置 pending(talker → senderWxId)
 *  2. Hook MsgInfoStorage.insert → before 注入 <atuserlist> 到 msgSource
 *  3. 微信服务器看到 atuserlist 后推送 "有人@我" 提醒
 */
object AtMentionHook {

    private const val TAG = "[AtMention]"

    /** pending: talker → atUserList (逗号分隔的 wxid) */
    @Volatile
    private var pending: Pair<String, String>? = null

    /** 在群聊回复前调用，设置本次发送需要 @ 的人（逗号分隔的多 wxid）。 */
    fun pend(talker: String, atUserList: String) {
        pending = talker to atUserList
    }

    /**
     * 解析 DexKit 并安装 Hook。由 MainHook 在启动时调用。
     */
    fun init(classLoader: ClassLoader) {
        val bridge = runCatching { DexKitBridge.create(classLoader, true) }
            .onFailure { XposedBridge.log("$TAG DexKitBridge.create FAILED: ${it.message}") }
            .getOrNull() ?: return

        try {
            // 1. 找 MsgInfoStorage 的消息入库方法
            //    锚点：日志 "MicroMsg.MsgInfoStorage" + "protect:c2c msg should not here"
            val insertMethod = bridge.findMethod(FindMethod().apply {
                searchPackages("com.tencent.mm.storage")
                matcher {
                    usingEqStrings("MicroMsg.MsgInfoStorage", "protect:c2c msg should not here")
                }
            }).singleOrNull()
            if (insertMethod == null) {
                XposedBridge.log("$TAG insertMethod not found")
                return
            }
            val methodObj = insertMethod.getMethodInstance(classLoader)
            XposedBridge.log("$TAG found insertMethod: ${insertMethod.name}")

            // 2. 找 MsgSourceHelper 节点合并方法
            //    锚点：正则 "(?s)<alnode[^>]*>.*?</alnode>" + static(3 args) + void
            val mergeMethod = bridge.findMethod(FindMethod().apply {
                matcher {
                    usingEqStrings("(?s)<alnode[^>]*>.*?</alnode>")
                    paramCount(3)
                    returnType("void")
                    modifiers(AccessFlagsMatcher(java.lang.reflect.Modifier.STATIC))
                }
            }).singleOrNull()
            if (mergeMethod == null) {
                XposedBridge.log("$TAG mergeMethod not found")
                return
            }
            val mergeObj = mergeMethod.getMethodInstance(classLoader)
            XposedBridge.log("$TAG found mergeMethod: ${mergeMethod.name}")

            // 3. Hook 入库方法
            XposedBridge.hookMethod(methodObj, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pendingPair = pending ?: return
                    pending = null

                    val msgInfo = param.args[0] ?: return
                    val klass = msgInfo.javaClass

                    // 递归搜索字段（查找自身及父类）
                    fun findField(name: String): java.lang.reflect.Field? {
                        var c: Class<*>? = klass
                        while (c != null) {
                            runCatching { c.getDeclaredField(name) }.getOrNull()?.let { return it }
                            c = c.superclass
                        }
                        return null
                    }

                    val talkerField = findField("field_talker") ?: findField("talker") ?: return
                    talkerField.isAccessible = true
                    val talker = talkerField.get(msgInfo) as? String ?: return

                    if (talker != pendingPair.first) {
                        pending = pendingPair // restore for retry
                        return
                    }

                    val isSendField = findField("field_isSend") ?: findField("isSend")
                    val typeField = findField("field_type") ?: findField("type")
                    if (isSendField != null && typeField != null) {
                        isSendField.isAccessible = true; typeField.isAccessible = true
                        val isSend = (isSendField.get(msgInfo) as? Int) ?: 0
                        val type = (typeField.get(msgInfo) as? Int) ?: 0
                        if (isSend != 1 || type != 1) {
                            pending = pendingPair
                            return
                        }
                    }

                    // 注入 atuserlist（微信服务器据此推送 "@ 我" 通知）
                    val atXml = "<atuserlist><![CDATA[${pendingPair.second}]]></atuserlist>"
                    mergeObj.invoke(null, msgInfo, atXml, false)
                }
            })

            XposedBridge.log("$TAG init OK")
        } finally {
            runCatching { bridge.close() }
        }
    }
}