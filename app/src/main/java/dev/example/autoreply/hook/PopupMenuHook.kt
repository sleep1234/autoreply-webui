package dev.example.autoreply.hook

import android.app.Activity
import android.util.SparseArray
import android.widget.BaseAdapter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Constructor

/**
 * 在微信首页右上角"+"菜单中注入「自动回复白名单」菜单项。
 * 参考 WeKit 的 WeHomeScreenPopupMenuApi。
 */
object PopupMenuHook {

    private const val TAG = "[PopupMenu]"

    @Volatile private var itemDataCtor: Constructor<*>? = null
    @Volatile private var handleClickDeclaringClass: Class<*>? = null
    @Volatile private var menuItemId = 0x7F000001
    @Volatile private var statusMenuItemId = 0x7F000002
    @Volatile private var tunnelMenuItemId = 0x7F000003
    @Volatile private var installed = false

    fun init(classLoader: ClassLoader) {
        if (installed) return

        val bridge = runCatching { DexKitBridge.create(classLoader, true) }
            .onFailure { XposedBridge.log("$TAG DexKitBridge FAILED: ${it.message}") }
            .getOrNull() ?: return

        try {
            // 1. 找 PlusSubMenuHelper（微信 8.0.76 里即 HomeUI）
            val helperList = bridge.findClass(FindClass().apply {
                searchPackages("com.tencent.mm.ui")
                matcher {
                    usingEqStrings("MicroMsg.PlusSubMenuHelper", "dyna plus config is null, we use default one")
                }
            })
            val helperClass = helperList.singleOrNull()
            if (helperClass == null) { XposedBridge.log("$TAG PlusSubMenuHelper not found"); return }
            val hCls = classLoader.loadClass(helperClass.name)
            XposedBridge.log("$TAG PlusSubMenuHelper: ${helperClass.name}")

            // 2. 找 addItem 方法（菜单构建）
            val addItemList = bridge.findMethod(FindMethod().apply {
                searchPackages("com.tencent.mm.ui")
                matcher {
                    usingEqStrings("MicroMsg.PlusSubMenuHelper", "dyna plus config is null, we use default one")
                }
            })
            val addItem = addItemList.firstOrNull()
            if (addItem == null) { XposedBridge.log("$TAG addItem not found"); return }
            val addItemMethod = addItem.getMethodInstance(classLoader)
            XposedBridge.log("$TAG addItem: ${addItem.name} @ ${addItemMethod.declaringClass.name}")

            // 3. 找 handleClick 方法
            val clickList = bridge.findMethod(FindMethod().apply {
                searchPackages("com.tencent.mm.ui")
                matcher {
                    usingEqStrings("MicroMsg.PlusSubMenuHelper", "processOnItemClick")
                }
            })
            val handleClick = clickList.firstOrNull()?.getMethodInstance(classLoader)
            if (handleClick != null) {
                handleClickDeclaringClass = handleClick.declaringClass
                XposedBridge.log("$TAG handleClick: ${handleClick.name} @ ${handleClick.declaringClass.name}")
            }

            // 4. 找 MenuItemData 构造函数
            val dataClassList = bridge.findClass(FindClass().apply {
                searchPackages("com.tencent.mm.ui")
                matcher {
                    addFieldForType("java.lang.String")
                    addFieldForType("int")
                    addFieldForType("int")
                    addFieldForType("int")
                    addFieldForType("java.lang.String")
                    fieldCount(5)
                }
            })
            for (dc in dataClassList) {
                val c = classLoader.loadClass(dc.name)
                for (ctor in c.declaredConstructors) {
                    ctor.isAccessible = true
                    val pts = ctor.parameterTypes
                    if (pts.size == 5 && pts[0] == Int::class.java && pts[1] == String::class.java) {
                        itemDataCtor = ctor
                        XposedBridge.log("$TAG MenuItemData ctor: $ctor")
                        break
                    }
                }
                if (itemDataCtor != null) break
            }

            // 5. Hook addItem（before + after 都打日志诊断）
            XposedBridge.hookMethod(addItemMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    XposedBridge.log("$TAG >>> addItem called, thisObject=${param.thisObject?.javaClass?.simpleName}")
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    injectMenuItem(param)
                }
            })

            // 6. Hook handleClick
            if (handleClick != null) {
                XposedBridge.hookMethod(handleClick, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        interceptClick(param)
                    }
                })
            }

            installed = true
            XposedBridge.log("$TAG 菜单 Hook 已安装")
        } catch (e: Exception) {
            XposedBridge.log("$TAG init 失败: ${e.message}")
        } finally {
            runCatching { bridge.close() }
        }
    }

    private fun injectMenuItem(param: XC_MethodHook.MethodHookParam) {
        try {
            var thisObj: Any = param.thisObject
            XposedBridge.log("$TAG injectMenuItem: thisObject=${thisObj.javaClass.name}")

            // WeKit 逻辑：如果是 HomeUI，取内部 helper 字段
            if (thisObj.javaClass.simpleName == "HomeUI" && handleClickDeclaringClass != null) {
                val inner = findFieldByType(thisObj.javaClass, handleClickDeclaringClass!!)
                if (inner != null) {
                    thisObj = inner.get(thisObj) ?: return
                    XposedBridge.log("$TAG HomeUI -> inner helper: ${thisObj.javaClass.name}")
                }
            }

            // 拿 SparseArray 字段
            val itemsField = findFieldByType(thisObj.javaClass, SparseArray::class.java)
            val items = itemsField?.get(thisObj) as? SparseArray<*> ?: run {
                XposedBridge.log("$TAG 找不到 SparseArray 字段")
                return
            }
            // 拿 adapter
            val adapterField = findFieldByType(thisObj.javaClass, BaseAdapter::class.java)
            val adapter = adapterField?.get(thisObj) as? BaseAdapter

            // 从现有 SparseArray 元素推断 wrapper 类（第一个非空元素）
            val wrapperClass: Class<*>? = run {
                for (i in 0 until items.size()) {
                    val v = items.get(i) ?: continue
                    XposedBridge.log("$TAG 现有菜单项类型: ${v.javaClass.name}")
                    return@run v.javaClass
                }
                null
            }

            val itemData = itemDataCtor?.newInstance(
                menuItemId, "自动回复白名单", "", android.R.drawable.ic_menu_manage, 0
            ) ?: run { XposedBridge.log("$TAG itemDataCtor 失败"); return }

            val statusItemData = itemDataCtor?.newInstance(
                statusMenuItemId, "服务状态", "", android.R.drawable.ic_menu_info_details, 0
            ) ?: run { XposedBridge.log("$TAG statusItemDataCtor 失败"); return }

            val tunnelItemData = itemDataCtor?.newInstance(
                tunnelMenuItemId, "内网穿透", "", android.R.drawable.ic_menu_manage, 0
            ) ?: run { XposedBridge.log("$TAG tunnelItemDataCtor 失败"); return }

            // 把 itemData 包装成 wrapper 类型（SparseArray 存的是 wrapper，不是 data）
            val wrapper: Any = if (wrapperClass != null && wrapperClass != itemData.javaClass) {
                val ctor = wrapperClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
                    ?.apply { isAccessible = true }
                ctor?.newInstance(itemData) ?: itemData
            } else itemData

            val statusWrapper: Any = if (wrapperClass != null && wrapperClass != statusItemData.javaClass) {
                val ctor = wrapperClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
                    ?.apply { isAccessible = true }
                ctor?.newInstance(statusItemData) ?: statusItemData
            } else statusItemData

            val tunnelWrapper: Any = if (wrapperClass != null && wrapperClass != tunnelItemData.javaClass) {
                val ctor = wrapperClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
                    ?.apply { isAccessible = true }
                ctor?.newInstance(tunnelItemData) ?: tunnelItemData
            } else tunnelItemData

            @Suppress("UNCHECKED_CAST")
            (items as SparseArray<Any>).put(items.size(), wrapper)
            @Suppress("UNCHECKED_CAST")
            (items as SparseArray<Any>).put(items.size(), tunnelWrapper)
            @Suppress("UNCHECKED_CAST")
            (items as SparseArray<Any>).put(items.size(), statusWrapper)
            adapter?.notifyDataSetChanged()
            XposedBridge.log("$TAG 菜单项已注入 (wrapper=${wrapper.javaClass.name}, total=${items.size()})")
        } catch (e: Exception) {
            XposedBridge.log("$TAG injectMenuItem 失败: ${e.message}")
        }
    }

    private fun interceptClick(param: XC_MethodHook.MethodHookParam) {
        try {
            val position = param.args.getOrNull(2) as? Int ?: return
            var thisObj: Any = param.thisObject
            if (thisObj.javaClass.simpleName == "HomeUI" && handleClickDeclaringClass != null) {
                val inner = findFieldByType(thisObj.javaClass, handleClickDeclaringClass!!)
                if (inner != null) thisObj = inner.get(thisObj) ?: return
            }
            val itemsField = findFieldByType(thisObj.javaClass, SparseArray::class.java) ?: return
            val items = itemsField.get(thisObj) as? SparseArray<*> ?: return
            val wrapper = items.get(position) ?: return

            // 提取 id
            val id = extractId(wrapper)
            if (id == menuItemId) {
                param.result = null
                val activity = getCurrentActivity() ?: return
                activity.runOnUiThread {
                    if (WeDatabaseApi.isReady) {
                        val conversations = WeDatabaseApi.queryConversations()
                        WhitelistLauncher.showConversationPicker(activity, conversations)
                    } else {
                        XposedBridge.log("$TAG 数据库未就绪")
                    }
                }
                XposedBridge.log("$TAG 点击了白名单菜单")
            }
            if (id == statusMenuItemId) {
                param.result = null
                val activity = getCurrentActivity() ?: return
                activity.runOnUiThread {
                    StatusLauncher.showStatusPanel(activity)
                }
                XposedBridge.log("$TAG 点击了服务状态菜单")
            }
            if (id == tunnelMenuItemId) {
                param.result = null
                val activity = getCurrentActivity() ?: return
                activity.runOnUiThread {
                    TunnelLauncher.showTunnelPanel(activity)
                }
                XposedBridge.log("$TAG 点击了内网穿透菜单")
            }
        } catch (e: Exception) {
            XposedBridge.log("$TAG interceptClick 失败: ${e.message}")
        }
    }

    private fun extractId(obj: Any): Int {
        for (f in obj.javaClass.declaredFields) {
            f.isAccessible = true
            if (f.type == Int::class.java || f.type == Int::class.javaPrimitiveType) {
                val v = f.get(obj)
                if (v is Int && (v == menuItemId || v == statusMenuItemId || v == tunnelMenuItemId)) return v
            }
            // 递归一层（wrapper -> data）
            if (!f.type.isPrimitive && !f.type.name.startsWith("java.")) {
                val inner = runCatching { f.get(obj) }.getOrNull() ?: continue
                val id = extractId(inner)
                if (id == menuItemId || id == statusMenuItemId || id == tunnelMenuItemId) return id
            }
        }
        return -1
    }

    private fun findFieldByType(cls: Class<*>, targetType: Class<*>): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null) {
            for (f in c.declaredFields) {
                f.isAccessible = true
                if (targetType.isAssignableFrom(f.type)) return f
            }
            c = c.superclass
        }
        return null
    }

    private fun getCurrentActivity(): Activity? {
        return runCatching {
            val am = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val field = am.javaClass.getDeclaredField("mActivities")
            field.isAccessible = true
            val activities = field.get(am) as? Map<*, *> ?: return null
            for (entry in activities.values) {
                val record = entry ?: continue
                val rc = record.javaClass
                val paused = rc.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
                if (!paused) {
                    val actField = rc.getDeclaredField("activity").apply { isAccessible = true }
                    return actField.get(record) as? Activity
                }
            }
            null
        }.getOrNull()
    }
}