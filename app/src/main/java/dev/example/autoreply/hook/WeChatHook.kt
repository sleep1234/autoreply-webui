package dev.example.autoreply.hook

import android.content.ContentValues
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Complete DexKit + reflection based WeChat hook.
 * No version hardcoding — matchers use stable WeChat log-tag strings.
 * Verified present on 8.0.76 via scan_all.py.
 */
class WeChatHook(
    private val classLoader: ClassLoader,
) : IWeChatHook {

    private val insertListeners = CopyOnWriteArrayList<MessageInsertListener>()

    // --- sendText targets (DexKit-resolved) ---
    private var sendMsgObjectGetter: Method? = null
    private var postToQueue: Method? = null
    private var sendMsgCtor: Constructor<*>? = null

    // --- sendImage targets ---
    private var imageServiceClass: Class<*>? = null
    private var imageTaskClass: Class<*>? = null
    private var imageTaskCtor: Constructor<*>? = null
    private var sendImageMethod: Method? = null
    private var crossParamsClass: Class<*>? = null

    // --- getCurrentTalker targets ---
    private var getTalkerMethod: Method? = null
    private var getTalkerReceiver: Any? = null

    override val selfWxId: String?
        get() = runCatching {
            XposedHelpers.callStaticMethod(
                Class.forName("com.tencent.mm.kernel.CoreAccount", false, classLoader),
                "getCurrentUserName"
            ) as? String
        }.getOrNull()

    // ===================================================================
    // Enable
    // ===================================================================

    override fun onEnable() {
        resolveSendPath()
        resolveImageSendPath()
        resolveGetTalker()
        hookMessageTableInsert()
    }

    // ===================================================================
    // DexKit resolutions — fixed API: DexKitBridge.create(classLoader, true)
    // ===================================================================

    private fun buildBridge(): DexKitBridge? {
        return runCatching {
            DexKitBridge.create(classLoader, true)
        }.onFailure { log("DexKitBridge.create FAILED: ${it.message}") }
         .getOrNull()
    }

    private fun resolveSendPath() {
        val bridge = buildBridge() ?: return
        try {
            val netSceneSendMsgList = bridge.findClass(FindClass().apply {
                matcher {
                    methods {
                        add {
                            paramCount(1)
                            usingStrings("MicroMsg.NetSceneSendMsg", "markMsgFailed for id:%d")
                        }
                    }
                }
            })
            val netSceneSendMsg = netSceneSendMsgList.singleOrNull()
                ?: run { log("classNetSceneSendMsg not found"); return }
            log("Found NetSceneSendMsg: ${netSceneSendMsg.name}")

            val netSceneQueue = bridge.findClass(FindClass().apply {
                searchPackages("com.tencent.mm.modelbase")
                matcher {
                    methods {
                        add {
                            paramCount(2)
                            usingStrings("worker thread has not been se", "MicroMsg.NetSceneQueue")
                        }
                    }
                }
            }).singleOrNull() ?: run { log("classNetSceneQueue not found"); return }
            log("Found NetSceneQueue: ${netSceneQueue.name}")

            val netSceneBase = bridge.findClass(FindClass().apply {
                matcher { usingEqStrings("scene security verification not passed, type=") }
            }).singleOrNull() ?: run { log("classNetSceneBase not found"); return }

            val postMethod = bridge.findMethod(FindMethod().apply {
                searchPackages("com.tencent.mm.modelbase")
                matcher {
                    declaredClass(netSceneQueue.name)
                    paramTypes(netSceneBase.name)
                    returnType("boolean")
                    usingNumbers(0)
                }
            }).singleOrNull() ?: run { log("methodPostToQueue not found"); return }
            postToQueue = postMethod.getMethodInstance(classLoader)
            log("Resolved postToQueue: ${postMethod.name}")

            val observerOwnerList = bridge.findClass(FindClass().apply {
                matcher {
                    methods {
                        add {
                            paramCount(4)
                            usingStrings("MicroMsg.Mvvm.NetSceneObserverOwner")
                        }
                    }
                }
            })
            log("observerOwner search: ${observerOwnerList.size} result(s)")
            observerOwnerList.forEach { log("  candidate: ${it.name}") }
            val observerOwner = observerOwnerList.singleOrNull()

            if (observerOwner != null) {
                val getterList = bridge.findMethod(FindMethod().apply {
                    matcher {
                        paramCount(0)
                        returnType(observerOwner.name)
                        modifiers(AccessFlagsMatcher(java.lang.reflect.Modifier.STATIC))
                    }
                })
                log("getter search: ${getterList.size} result(s)")
                // Take the FIRST static getter (WeKit uses allowMultiple=true and
                // iterates; multiple getters exist in 8.0.76 because NetSceneQueue
                // and NetSceneObserverOwner resolve to the same class).
                val getter = getterList.firstOrNull()
                if (getter != null) {
                    sendMsgObjectGetter = getter.getMethodInstance(classLoader)
                    log("Resolved sendMsgObjectGetter: ${getter.name}")
                } else {
                    log("getter not found (${getterList.size} results)")
                }
            } else {
                log("observerOwner not found (${observerOwnerList.size} results)")
            }

            if (sendMsgObjectGetter == null) {
                log("sendMsgObjectGetter not resolved; fallback to NetSceneQueue instance")
            }

            val cls = netSceneSendMsg.getInstance(classLoader)
            for (c in cls.declaredConstructors) {
                c.isAccessible = true
                val p = c.parameterTypes
                if (p.size == 5 && p[0] == String::class.java &&
                    p[1] == String::class.java &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType) {
                    sendMsgCtor = c
                    log("sendMsgCtor (5 args): ${c}")
                }
                if (p.size == 6 && p[0] == String::class.java &&
                    p[1] == String::class.java &&
                    p[2] == Int::class.javaPrimitiveType &&
                    p[3] == Int::class.javaPrimitiveType &&
                    p[5] == String::class.java) {
                    sendMsgCtor = c
                    log("sendMsgCtor (6 args, preferred): ${c}")
                }
            }
            if (sendMsgCtor == null) log("sendMsgCtor NOT resolved")
        } finally { runCatching { bridge.close() } }
    }

    // -------------------------------------------------------------------
    // Image send resolution
    // -------------------------------------------------------------------

    private fun resolveImageSendPath() {
        val bridge = buildBridge() ?: return
        try {
            val mvvmBase = bridge.findClass(FindClass().apply {
                matcher { usingStrings("MicroMsg.Mvvm.MvvmPlugin", "onAccountInitialized start") }
            }).singleOrNull() ?: run { log("classMvvmBase not found"); return }

            val imgSvc = bridge.findClass(FindClass().apply {
                matcher {
                    usingStrings("MicroMsg.ImgUpload.MsgImgFeatureService")
                    superClass(mvvmBase.name)
                }
            }).singleOrNull() ?: run { log("classImageServiceImpl not found"); return }

            val imgTask = bridge.findClass(FindClass().apply {
                matcher { usingStrings("msg_raw_img_send") }
            }).singleOrNull() ?: run { log("classImageTask not found"); return }

            imageServiceClass = imgSvc.getInstance(classLoader)
            imageTaskClass = imgTask.getInstance(classLoader)

            val ctor = imageTaskClass!!.declaredConstructors.firstOrNull { it.parameterCount == 5 }
            imageTaskCtor = ctor?.apply { isAccessible = true }
            crossParamsClass = ctor?.parameterTypes?.get(4)

            sendImageMethod = imageServiceClass!!.declaredMethods.firstOrNull { m ->
                m.parameterCount == 1 &&
                    m.parameterTypes[0] == imageTaskClass &&
                    m.returnType.name.contains("flow", ignoreCase = true)
            }?.apply { isAccessible = true }

            log("image: ctor=${imageTaskCtor!=null} method=${sendImageMethod!=null}")
        } finally { runCatching { bridge.close() } }
    }

    // -------------------------------------------------------------------
    // GetTalker resolution
    // -------------------------------------------------------------------

    private fun resolveGetTalker() {
        val bridge = buildBridge() ?: return
        try {
            val chattingCtx = bridge.findClass(FindClass().apply {
                matcher { usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]") }
            }).singleOrNull() ?: run { log("classChattingContext not found"); return }

            val getTalker = bridge.findMethod(FindMethod().apply {
                matcher {
                    declaredClass(chattingCtx.name)
                    usingEqStrings("getTalker returns null.")
                }
            }).singleOrNull() ?: run { log("methodGetTalker not found"); return }

            getTalkerMethod = getTalker.getMethodInstance(classLoader)
            log("getTalker resolved: ${getTalker.name}")
        } finally { runCatching { bridge.close() } }
    }

    // ===================================================================
    // Database hook — aligned with WeKit's WeDatabaseListenerApi:
    // only insertWithOnConflict (message-table INSERT path)
    // ===================================================================

    private fun hookMessageTableInsert() {
        // WeKit hooks com.tencent.wcdb.database.SQLiteDatabase.insertWithOnConflict
        // (resolved via reflekt). We do the same, resolving the concrete class
        // from the host classloader.
        runCatching {
            val clazz = Class.forName("com.tencent.wcdb.database.SQLiteDatabase", false, classLoader)
            XposedHelpers.findAndHookMethod(
                clazz,
                "insertWithOnConflict",
                String::class.java, String::class.java,
                ContentValues::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        checkMessageInsert(param.args[0] as String, param.args[2] as ContentValues)
                    }
                }
            )
            log("hooked com.tencent.wcdb.database.SQLiteDatabase.insertWithOnConflict")
        }.onFailure { log("hook insertWithOnConflict failed: ${it.message}") }
    }

    private fun checkMessageInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val msg = IncomingMessage(
            msgSvrId = values.getAsLong("msgSvrId"),
            type = values.getAsInteger("type"),
            talker = values.getAsString("talker"),
            content = values.getAsString("content"),
            isSend = (values.getAsInteger("isSend") ?: 0) == 1,
            createTime = values.getAsLong("createTime"),
        )
        log("message INSERT: type=${msg.type} isSend=${msg.isSend} talker=${msg.talker} content=${msg.content}")
        for (l in insertListeners) runCatching { l.onMessageInsert(msg) }
    }

    // ===================================================================
    // Listeners
    // ===================================================================

    override fun addInsertListener(listener: MessageInsertListener) { insertListeners.add(listener) }
    override fun removeInsertListener(listener: MessageInsertListener) { insertListeners.remove(listener) }

    // ===================================================================
    // sendText
    // ===================================================================

    override fun sendText(talker: String, text: String): Boolean {
        return runCatching {
            val ctor = sendMsgCtor ?: return false
            val postMethod = postToQueue ?: return false
            val scene = if (ctor.parameterCount == 6)
                ctor.newInstance(talker, text, 1, 0, null, "")
            else
                ctor.newInstance(talker, text, 1, 0, null)

            // Strategy 1: observer route (WeKit's methodGetSendMsgObject)
            val observer = sendMsgObjectGetter?.invoke(null)
            if (observer != null) {
                return postMethod.invoke(observer, scene) as? Boolean ?: false
            }

            // Strategy 2: NetSceneQueue.doScene(NetSceneBase) — static send
            val queueClass = postMethod.declaringClass
            val doScene = queueClass.methods.firstOrNull { m ->
                m.parameterCount == 1 &&
                    java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.parameterTypes[0].name.contains("NetScene")
            }?.apply { isAccessible = true }

            if (doScene != null) {
                doScene.invoke(null, scene)
                log("sent via doScene static method")
                return true
            }

            log("sendText: no viable send path found")
            false
        }.getOrElse { log("sendText failed: ${it.message}"); false }
    }

    // ===================================================================
    // sendImage
    // ===================================================================

    override fun sendImage(talker: String, imagePath: String): Boolean {
        return runCatching {
            val ctor = imageTaskCtor ?: return false
            val sendMethod = sendImageMethod ?: return false
            val paramsClass = crossParamsClass ?: return false
            val svcClass = imageServiceClass ?: return false

            val params = paramsClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            paramsClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
                ?.apply { isAccessible = true }?.setInt(params, 4)

            val task = ctor.newInstance(imagePath, 0, selfWxId ?: "", talker, params)

            val svc = runCatching {
                svcClass.declaredMethods.firstOrNull {
                    it.parameterCount == 0 && it.returnType == svcClass
                }?.apply { isAccessible = true }?.invoke(null)
            }.getOrNull()

            if (svc != null) { sendMethod.invoke(svc, task); true }
            else { log("image service instance not found"); false }
        }.getOrElse { log("sendImage failed: ${it.message}"); false }
    }

    // ===================================================================
    // getCurrentTalker
    // ===================================================================

    override fun getCurrentTalker(): String? {
        return runCatching {
            val method = getTalkerMethod ?: return null
            val receiver = getTalkerReceiver ?: run {
                val cls = method.declaringClass
                val inst = runCatching {
                    cls.declaredMethods.firstOrNull {
                        it.parameterCount == 0 && it.returnType == cls
                    }?.apply { isAccessible = true }?.invoke(null)
                }.getOrNull()
                getTalkerReceiver = inst; inst
            }
            method.invoke(receiver) as? String
        }.getOrNull()
    }

    private fun log(msg: String) { XposedBridge.log("[AutoReply] $msg") }
}