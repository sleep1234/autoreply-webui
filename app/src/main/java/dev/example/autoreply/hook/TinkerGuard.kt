package dev.example.autoreply.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * Multi-layered defence against WeChat Tinker hot-update patches.
 *
 * Tinker replaces DEX classes at runtime. If a patch is loaded, the
 * WCDB / okhttp / WebView classes we hook may point at patched versions
 * with different method signatures or obfuscation, breaking every hook.
 *
 * This guard runs three layers:
 *
 *   Layer 1 — Filesystem wipe (delete patch dirs before Tinker reads them)
 *   Layer 2 — Zygote-level ClassLoader filter (block patch dex from
 *             being added to the classpath)
 *   Layer 3 — Tinker API hook (intercept the load call and make it a no-op)
 *
 * Layers 2 & 3 need the class names of WeChat's hotfix subsystem. The
 * documented names cover WeChat 8.0.65–8.0.78; adjust if you target
 * a different version.
 */
object TinkerGuard {

    private const val TAG = "[TinkerGuard]"

    /** WeChat data dir — set once in handleLoadPackage. */
    @Volatile
    private var wechatDataDir: String? = null

    // ---- 拦截留痕（供状态面板展示）----
    private data class Interception(val ts: Long, val layer: String, val detail: String)

    @Volatile
    private var interceptions: List<Interception> = emptyList()

    /** 记录一次成功拦截（Layer 1 wipe / Layer 2 filter / Layer 3 hook）。 */
    private fun record(layer: String, detail: String) {
        val item = Interception(System.currentTimeMillis(), layer, detail)
        interceptions = (interceptions + item).takeLast(50)  // 最多保留 50 条
    }

    /** 面板用摘要：拦截总数 + 最近几条。 */
    fun statusSummary(): String {
        val list = interceptions
        if (list.isEmpty()) return "未拦截（无热更新补丁被加载）"
        val sb = StringBuilder("共拦截 ${list.size} 次")
        list.takeLast(5).forEach {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(it.ts))
            sb.append("\n· [$t] ${it.layer} ${it.detail.take(60)}")
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------
    //  Layer 1: Filesystem wipe
    //  Must run BEFORE Tinker loads the patches (in handleLoadPackage,
    //  which fires before WeChat Application.onCreate completes).
    // ----------------------------------------------------------------

    /**
     * Delete every Tinker-related file in the WeChat data directory.
     * Call this in [MainHook.handleLoadPackage] before any hooks.
     */
    fun wipePatchDirs(lpparamDataDir: String) {
        wechatDataDir = lpparamDataDir

        // --- Tinker patch dirs ---
        val dirs = listOf("tinker", "tinker_temp", "tinker_server", "patch",
            "tinker_patch", "patch_temp", "hotpatch", "app_tinker")

        for (name in dirs) {
            val d = File(lpparamDataDir, name)
            if (d.exists()) {
                val deleted = d.deleteRecursively()
                log("wipe $name: deleted=$deleted (path=${d.absolutePath})")
                if (deleted) record("Layer1-wipe", name)
            } else {
                log("wipe $name: not found")
            }
        }

        // --- X5 / TBS WebView kernel hot-update paths ---
        // WeChat's X5 kernel (TBS, Tencent Browsing Service) also downloads
        // and hot-swaps its own .so / .jar under these dirs. Patching the
        // WebView kernel would break our JS injection + WebView hooks, so
        // wipe them too. Paths:
        //   files/tbs/            → TBS core (x5.tbs.org downloaded kernel)
        //   files/tbslog/         → TBS logs (leave, but harmless)
        //   app_tbs/  app_xwalk/  → XWalk variants
        //   files/x5/             → X5 kernel
        val tbsDirs = listOf("tbs", "app_tbs", "app_xwalk", "x5", "xweb",
            "xwalk", "tbs_sdk", "TbsReaderTemp")
        for (name in tbsDirs) {
            val d = File(lpparamDataDir, "files").resolve(name)
            if (d.exists()) {
                d.deleteRecursively()
                log("wipe X5/TBS $name: deleted (path=${d.absolutePath})")
            }
        }

        // --- Kill stray Tinker artifacts in root data dir ---
        runCatching {
            File(lpparamDataDir).listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val n = f.name
                if (n.startsWith("patch-") || n.startsWith("bsdiff-") ||
                    n.endsWith("_patch.apk") || (n.startsWith("tinker") && n.endsWith(".odex"))) {
                    f.delete()
                    log("wipe stray: $n")
                }
            }
        }
    }

    // ----------------------------------------------------------------
    //  Layer 2: Zygote-level ClassLoader filter
    //  Hooks `DexPathList.makeDexElements` system-wide so that any
    //  DEX/JAR/APK whose path contains "tinker" is silently dropped.
    //  Must be called from initZygote (before any app starts).
    // ----------------------------------------------------------------

    fun hookClassLoaderFilter(startupParam: Any?) {
        runCatching {
            val dexPathList = XposedHelpers.findClass(
                "dalvik.system.DexPathList", null) // bootstrap CL
            // DexPathList has several makeDexElements overloads across Android
            // versions. Hook all of them defensively.
            for (m in dexPathList.declaredMethods) {
                if (m.name != "makeDexElements") continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // args[0] is the List<File>/List<SplitDexFile> of dex files
                        val files = param.args.getOrNull(0) as? java.util.List<*> ?: return
                        val iter = files.iterator()
                        while (iter.hasNext()) {
                            val f = iter.next()
                            val path = f?.toString() ?: ""
                            if (path.contains("tinker") || path.contains("patch") ||
                                path.contains("hotfix")) {
                                iter.remove()
                                log("ClassLoader filter BLOCKED: ${path.takeLast(80)}")
                                record("Layer2-filter", path.takeLast(60))
                            }
                        }
                    }
                })
            }
            log("Layer 2 (ClassLoader filter) installed on ${dexPathList.declaredMethods.count { it.name == "makeDexElements" }} overload(s)")
        }.onFailure { log("Layer 2 FAILED: ${it.message}") }
    }

    // ----------------------------------------------------------------
    //  Layer 3: Tinker API hook
    //  Hooks the Tinker load path inside WeChat's main process so that
    //  even if a patch file survives Layer 1+2, the load call is a no-op.
    //  Call this in handleLoadPackage with WeChat's class loader.
    // ----------------------------------------------------------------

    fun hookTinkerApi(lpparamClassLoader: ClassLoader) {
        // --- 3a: TinkerLoader.tryLoad* (generic Tinker) ---
        runCatching {
            val tinkerLoader = XposedHelpers.findClass(
                "com.tencent.tinker.loader.TinkerLoader", lpparamClassLoader)
            // tryLoad(TinkerApplication)
            XposedHelpers.findAndHookMethod(tinkerLoader, "tryLoad",
                android.app.Application::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Return a "failed to load" result
                        param.result = android.content.Intent()
                        log("Layer 3a: TinkerLoader.tryLoad → blocked")
                        record("Layer3a-TinkerLoader", "tryLoad blocked")
                    }
                })
            log("Layer 3a (TinkerLoader.tryLoad) installed")
        }.onFailure { log("Layer 3a not found (WeChat may use custom Tinker): ${it.message}") }

        // --- 3b: TinkerApplicationLike.onBaseContextAttached (early init) ---
        runCatching {
            val appLike = XposedHelpers.findClass(
                "com.tencent.tinker.entry.TinkerApplicationLike", lpparamClassLoader)
            XposedHelpers.findAndHookMethod(appLike, "onBaseContextAttached",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        log("Layer 3b: TinkerApplicationLike.onBaseContextAttached → skipping")
                        record("Layer3b-AppLike", "onBaseContextAttached blocked")
                        // Early-return so the patch install never runs
                        param.result = null
                    }
                })
            log("Layer 3b (TinkerApplicationLike) installed")
        }.onFailure { log("Layer 3b not found: ${it.message}") }

        // --- 3c: MM patch manager (WeChat-specific hotfix wrapper) ---
        // WeChat sometimes wraps Tinker under com.tencent.mm.hellhoundlib or
        // com.tencent.mm.plugin.hotpatch. Try common names.
        val mmHotfixClasses = listOf(
            "com.tencent.mm.plugin.hotpatch.HotPatchManager",
            "com.tencent.mm.hellhoundlib.b",
            "com.tencent.mm.pluginsdk.hotpatch.HotPatchService",
            "com.tencent.mm.tinker.MMTinkerPatch",
        )
        for (cls in mmHotfixClasses) {
            runCatching {
                val c = XposedHelpers.findClass(cls, lpparamClassLoader)
                for (m in c.declaredMethods) {
                    if (m.name.contains("load") || m.name.contains("patch") || m.name.contains("install")) {
                        XposedHelpers.findAndHookMethod(cls, lpparamClassLoader, m.name,
                            *m.parameterTypes,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    log("Layer 3c: $cls.${m.name} → blocked")
                                    record("Layer3c-MM", "$cls.${m.name}")
                                    // Return false/0 for boolean/int return types
                                    param.result = when (m.returnType) {
                                        Boolean::class.javaPrimitiveType -> false
                                        Int::class.javaPrimitiveType -> 0
                                        Long::class.javaPrimitiveType -> 0L
                                        else -> null
                                    }
                                }
                            }
                        )
                        log("Layer 3c: hooked $cls.${m.name}")
                    }
                }
            }.onFailure { /* best-effort */ }
        }

        // --- 3d: DexClassLoader bypass — intercept dex load with "tinker" in path ---
        runCatching {
            val dexLoader = XposedHelpers.findClass(
                "dalvik.system.DexClassLoader", null)
            XposedHelpers.findAndHookConstructor(dexLoader,
                String::class.java, String::class.java,
                String::class.java, ClassLoader::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val path = param.args[0] as? String ?: ""
                        if (path.contains("tinker")) {
                            // Replace with empty placeholder so nothing loads
                            param.args[0] = ""
                            log("Layer 3d: DexClassLoader path BLOCKED: ${path.takeLast(60)}")
                            record("Layer3d-DexClassLoader", path.takeLast(50))
                        }
                    }
                }
            )
            log("Layer 3d (DexClassLoader filter) installed")
        }.onFailure { log("Layer 3d FAILED: ${it.message}") }
    }

    private fun log(msg: String) = XposedBridge.log("$TAG $msg")
}