# CTWing 物联网卡自动回复模块 — 开发记忆文件

## 项目目标
基于 `minimal_text_autoreply` (LSPosed Xposed 模块)，拦截微信 WebView/网络层，
实现「客户微信发消息 → 自动调用 CTWing API 查询/诊断/重绑 → 自动回复结果」
的**完全无人值守**链路。

## 环境信息
| 项目 | 值 |
|------|-----|
| 微信版本 | 8.0.76 (versionCode=3141) |
| 手机 | 小米 14 Pro (shennong), HyperOS, Android 17 |
| ADB | `C:\Android\Sdk\platform-tools\adb.exe` |
| 设备序列号 | a59f9c76 |
| Root | KernelSU (adb shell su -c 可用) |
| Android SDK | `C:\Android\Sdk` (platforms 33-36, build-tools 34-36) |
| JDK | `C:\JDK\jdk-17.0.2` |
| 工作区 | `C:\Users\zhp\Desktop\ds工作区\微信自动回复\repo` |
| APK | `app\build\outputs\apk\debug\app-debug.apk` |
| CTWing H5 | `https://tywlonestop.ctwing.cn:8081/web-apps/` |

## 当前架构
```
微信进程 (com.tencent.mm, 主进程)
├── MainHook.handleLoadPackage (processName == "com.tencent.mm")
│   ├── TinkerGuard (wipe tinker 目录 + hook TinkerLoader)
│   ├── CtwingWebViewHook (View 树扫描 + JS 注入)
│   ├── CtwingNetworkHook (okhttp hook，当前 ClassNotFoundException)
│   └── 引擎 (DexKit + WeChatHook + BufferedMessageTrigger)
│       ├── WCDB insertWithOnConflict → 消息捕获
│       ├── CtwingKeywordRouter → 关键词匹配
│       └── sendText (NetSceneSendMsg → y11.r0)
├── CtwingFacade → callJsLocal (evaluateJavascript 轮询)
├── CtwingJsInjector.build() → __ctwing 全局对象
└── CtwingJsBridge → 被动接收 JS 回调 (addJavascriptInterface，当前不可用)
```

## ✅ 已验证可用的组件
1. **消息捕获** — WCDB `insertWithOnConflict` hook `message` 表，正常捕获文本消息
2. **自动回复** — `sendText` 通过 DexKit 解析 `NetSceneSendMsg=y11.r0` 正常发送
3. **关键词路由** — 支持 `查询/诊断/重绑/环境/dump`，支持 ICCID(19-20位) + 接入号(8-14位)
4. **View 树扫描** — hook `WebViewUI.onResume`，从 Activity 的 DecorView 递归查找 WebView
5. **JS 通信通道** — `evaluateJavascript` + ValueCallback 代理轮询 `window.__ctwingResults[rid]`
6. **TinkerGuard** — 成功拦截 `tinker_classN.apk` 加载，热更新已阻止

## ❌ 待解决的问题
1. **WebView 需手动打开** — H5 必须由用户手动打开，无法实现无人值守
2. **CTROBF1 加密未逆向** — 裸调 HTTP API 返回密文
3. **okhttp hook 失败** — `ClassNotFoundException: okhttp3$RealCall` (微信混淆了 okhttp)
4. **addJavascriptInterface 对已加载页面不可见** — 改用 evaluateJavascript + 全局变量轮询
5. **SPA 页面路由** — 首页无输入框 (inputFound=false)，查询页有输入框；需要导航到对应页面

## CTWing SPA 环境 (由 dump 命令回传)
| 属性 | 值 |
|------|-----|
| 框架 | Vue 3 |
| HTTP 客户端 | 原生 fetch (无 axios) |
| 加密库 | CryptoJS (全局对象) |
| 输入框选择器 | 未知 (当前 selector 未命中) |
| 页面标题 | "物联1站" |
| Token | Cookie 中 ACCESS_TOKEN=ef2268f2... |
| API Base | `https://tywlonestop.ctwing.cn:8081/webapp-font/admin-api/bpm/service-assistant` |
| window 关键键名 | __ctwing, __ctwingResults, __CTWING_HOOKED, CryptoJS, CustomFullscreenApi, MiWebViewDetector |

## 关键技术细节
- **进程模型**：微信多进程，主进程 `com.tencent.mm` 处理 H5 (MMWebViewUI)。WebView hook 只装主进程
- **X5 内核**：`com.tencent.xweb` WebView 运行在 sandboxed (isolated) + privileged 进程
- **WebView 包装类**：实际 WebView 类型是混淆类 `cz5.j0`，不是裸 `com.tencent.xweb.WebView`
- **evaluateJavascript 返回值**：X5 对返回字符串做了 JSON 编码（如 `"\"quoted\""`），需双路径解析
- **Gradle 增量编译陷阱**：修改代码后必须 `clean assembleDebug`，否则 compileDebugKotlin UP-TO-DATE

## 通信协议
```
Java → JS: evaluateJavascript("boot() + call() + writeResultToWindow")
Java ← JS: evaluateJavascript("JSON.stringify(window.__ctwingResults[rid])", callback) 轮询
```

## A 方案 (CTROBF1 逆向) 入口信息
- CTWing 使用瑞数 (RiverSecurity) CTROBF1 动态加密
- 请求头：`$_ts` (时间戳), `ctl-dync-ct-bond` (动态绑定)
- SPA 加密流程：明文 body → CTROBF1 编码 → CryptoJS.AES 加密 → Base64 → 发送
- SPA 解密流程：响应 → Base64 解码 → CryptoJS.AES 解密 → CTROBF1 解码 → 明文 JSON
- 全局可用的加密对象：`window.CryptoJS`，及相关加密 SDK 全局变量待发现
- 上次 Access Token: `ef2268f2952d4ea2b5045000031ebcea` (Cookie 方式传递)

## 编译 & 部署命令
```powershell
$env:JAVA_HOME = "C:\JDK\jdk-17.0.2"; $env:ANDROID_HOME = "C:\Android\Sdk"
& "C:\Users\zhp\Desktop\ds工作区\微信自动回复\repo\gradlew.bat" -p "." clean assembleDebug

$apk = "app\build\outputs\apk\debug\app-debug.apk"
& $adb push $apk /data/local/tmp/autoreply.apk
& $adb shell pm install -r /data/local/tmp/autoreply.apk
& $adb shell am force-stop com.tencent.mm
& $adb logcat -c
& $adb shell am start -n com.tencent.mm/.ui.LauncherUI
```

## 下一步：A 方案 / CTROBF1 逆向
1. 从 SPA 中提取 CryptoJS 加密配置（key, iv, mode, padding）
2. 分析 CTROBF1 编码/解码逻辑（`ctDynamic`, `_enc`, `_dec`, `security` 等全局对象）
3. 在 Java/Kotlin 中实现加密/解密，直接用 okhttp 调 API