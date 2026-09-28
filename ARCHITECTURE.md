# 天翼物联一站式服务工具 — 技术架构文档

> 更新：2026-09-27 | 微信 8.0.76 | Xiaomi 14 Pro KernelSU

---

## 一、整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│  MainHook (Xposed entry)                                         │
│    ├─ TinkerGuard: 热更新清理                                     │
│    ├─ 进程路由: 主进程→引擎, xweb/tools→WebView hook               │
│    ├─ DexKit 加载 libdexkit.so                                    │
│    │                                                              │
│    ├─ WeChatHook: WCDB insertWithOnConflict → message 表捕获     │
│    │    └→ IncomingMessage(msgSvrId, type, talker, content,       │
│    │        isSend, createTime, lvBuffer)                         │
│    │                                                              │
│    ├─ BufferedMessageTrigger (1.5s 聚合, 5条上限, 5s 最大等待)     │
│    │    └→ onFlush: 去重 → 白名单 → @过滤 → CTWing路由 → 兜底回复  │
│    │                                                              │
│    ├─ PopupMenuHook: "+"菜单注入白名单入口                          │
│    ├─ TokenKeepAlive: 25分钟 token 心跳                            │
│    └─ 方案A: 静默启动 (NEW_DOCUMENT+MULTIPLE_TASK + moveTaskToBack) │
└─────────────────────────────────────────────────────────────────┘
```

### 1.1 进程路由策略

| 进程 | 初始化内容 |
|------|-----------|
| `com.tencent.mm` (主) | DexKit + TinkerGuard + 引擎 + 白名单 + TokenKeepAlive + H5预初始化 |
| `:xweb_*` / `:tools` | 仅 CtwingWebViewHook + CtwingNetworkHook + CtwingBundleCapture |
| `:push` / `:appbrand*` | 跳过（仅打日志） |

原因：DexKit 原生库多进程加载会 SIGSEGV。

---

## 二、完整消息处理链路

```
WCDB message 表 INSERT
  │  ContentValues → IncomingMessage
  ▼
MessageTrigger 过滤
  ├─ type != 1 → 跳过
  ├─ isSend == 1 → 跳过 (防回环)
  └─ content/talker 匹配
  ▼
BufferedMessageTrigger (debounce 1.5s, maxWait 5s, batch≤5)
  │  去重: messages.asReversed().distinctBy { talker to content }.reversed()
  ▼
onFlush → 逐条处理:
  │
  ├─ ① 内容剥离
  │     content.replaceFirst("^wxid_\\w+:", "")
  │            .replaceFirst("^@\\S+[\\s\\u2005]+", "")
  │
  ├─ ② 白名单 Gate
  │     WhitelistStore.list() 非空 → 检查 talker 是否在列表
  │
  ├─ ③ 群聊 @ Gate
  │     talker.endsWith("@chatroom") →
  │       lvBuffer → AtParser.isAtMeOrAll() → 检查 atuserlist
  │       失败时兜底: WeDatabaseApi.isAtMe() → rawQuery lvbuffer
  │
  ├─ ④ CTWing 关键词路由
  │     CtwingKeywordRouter.tryHandle() → 见 §三
  │
  └─ ⑤ 兜底回复（非 CTWing 命令统一回复欢迎帮助）
        """
        🤖 欢迎使用天翼物联一站式服务工具！
        
        📋 可用命令：
        · 查询 ICCID或接入号 — 查询卡片详情
        · 诊断 ICCID或接入号 — 诊断卡片情况
        · 重绑 ICCID或接入号 — 机卡重绑
        
        💡 使用方式：@我 + 命令
        """
```

### 2.1 @ 解析流程 (AtParser)

```
lvBuffer (ByteArray, 361 bytes)
  → parseMsgSource()
     ├─ 结构: '{' + string2B + int4B + msgSource2B + '}'
     └─ 兜底: UTF-8 全文搜 <msgsource>...</msgsource>
  → parseAtUserList()
     提取 <atuserlist> 内容 → 剥 CDATA → split(",")
  → isAtMeOrAll()
     atUserList.contains(selfWxId) || contains("notify@all")
```

### 2.2 selfWxId 获取

```kotlin
// 反射读 WeChat SharedPreferences
val ctx = ActivityThread.currentActivityThread().application
val prefs = ctx.getSharedPreferences("com.tencent.mm_preferences", Context.MODE_PRIVATE)
val wxId = prefs.getString("login_weixin_username", null)
```

不使用 `CoreAccount.getCurrentUserName()`（不可靠，常返回 null）。

---

## 三、CTWing 操作 — 完整技术方案

### 3.1 操作分流表

| 操作 | 识别词 | HTTP 方法 | 传输层 | 原因 |
|------|--------|----------|--------|------|
| 查询 | 查询/查卡/query | GET | **NativeHttp** | GET 无 CSRF |
| 诊断 | 诊断/diagnose | GET | **NativeHttp** | GET 无 CSRF |
| 重绑 | 重绑/rebind | POST | **WebView XHR** | POST 有 CSRF |

### 3.2 查询流程（NativeHttp GET）

```
pullToken()                           ← evaluateJavascript 读 WebView cookie
  └→ NativeHttp.cachedToken = token
queryCard(token, type, id)
  → HttpURLConnection GET /querySimBaseInfo?type=&id=
  → 头: Authorization Bearer + Cookie + Origin/Referer
  → 响应: {"code":0,"data":{"simBasicInfoRespVO":{...}}}
  → extractBestResponse → 优先取 body 字段, 兜底 data/diag/dom
  → formatCardInfo → "📱 查询结果 · 客户名 · 产品名\nSIM状态: …"
```

### 3.3 诊断流程（NativeHttp GET）

```
diagnose(token, type, id)
  → HttpURLConnection GET /intelligentDiagnosis?type=&id=
  → 响应: {"code":0,"data":{"simBasicInfoRespVO":{...},"networkDisconnect":{...}}}
  → extractBestResponse
  → formatDiagnosis → "🔍 诊断报告 · 客户名 · 产品名\n✅ 断网：正常\n…"
```

### 3.4 重绑流程（WebView XHR POST）

```
pullToken()
  → fireJs("window.__ctwing.operationCommit('{...}')")   ← JS 异步 XHR
  → for (1..15) pollDshResult():
       evaluateJsForResult("window.__dshResult", 2000ms)
       等待 "operationCommit-ok" 或 实际数据
  → 解析 JSON {status, body}:
       wrap.has("status") && wrap.has("body")
         → 剥 body 字段                          (兼容 WebView XHR 包装)
         → wrap 自身                             (兼容 NativeHttp 裸 JSON)
  → when { code==401, code!=0, status含成功/失败, else }
  → "✅ 机卡重绑成功（工单：2104070408868220929）"
```

POST 走 WebView XHR 的原因：NativeHttp POST 被 CTWing 服务器返回 403（CSRF 校验），加 Origin/Referer/Cookie 均无效，必须由浏览器内核自带上下文通过。

### 3.5 并发互斥

```kotlin
// CtwingKeywordRouter.kt
CtwingFacade.webViewMutex.withLock {
    when (op) { "query" -> ..., "diagnose" -> ..., "rebind" -> ... }
}
```

`webViewMutex` 是 `Mutex`，串行化三个操作。根因：`CtwingJsBridge.lastApiResponse` 是全局单例，并发操作会互相覆盖响应。

### 3.6 Wake Lock

```kotlin
acquireWakeLock()  // SCREEN_DIM_WAKE_LOCK, 50s
// ... 操作 ...
releaseWakeLock()
```

防止锁屏时 WebView/X5 的 JS 引擎冻结。NativeHttp 查询/诊断不需要此机制（纯 Java HTTP），但重绑的 fireJs + pollDshResult 需要。

---

## 四、关键数据结构

### 4.1 IncomingMessage (IWeChatHook.kt)

```kotlin
data class IncomingMessage(
    val msgSvrId: Long?,        // 消息服务端 ID
    val type: Int?,             // 1=文本
    val talker: String?,        // 发送者 wxid / 群聊 id
    val content: String?,       // 消息正文 (群聊: wxid_xxx:@昵称 正文)
    val isSend: Boolean,        // true=自己发的
    val createTime: Long?,
    val lvBuffer: ByteArray?    // 二进制扩展字段 (含 msgSource/atuserlist)
)
```

### 4.2 CTWing API 端点

| 端点 | 方法 | 参数 |
|------|------|------|
| `/querySimBaseInfo` | GET | `type={iccid\|msisdn\|imsi}&id=...` |
| `/intelligentDiagnosis` | GET | `type=&id=` |
| `/operationCommit` | POST | `{"type","id","imei","source","orderNumber","sessionId","comment","bindType","file":{"ids":[]},"operation":"JKCB"}` |

BASE: `https://tywlonestop.ctwing.cn:8081/webapp-font/admin-api/bpm/service-assistant`

### 4.3 白名单存储

路径: `/data/data/com.tencent.mm/files/autoreply_whitelist.json`
格式: `[{"id":"wxid_xxx","name":"昵称"},...]`

使用 WeChat dataDir 而非模块自身 prefs，避免 SELinux 跨应用文件访问限制。

---

## 五、WeChat 8.0.76 混淆对照

| 功能 | 混淆类 | 说明 |
|------|--------|------|
| PlusSubMenuHelper | `com.tencent.mm.ui.HomeUI` | 菜单宿主 |
| 内部 helper | `com.tencent.mm.ui.rg` | 菜单逻辑类 |
| MenuItemData | `pg` | ctor(int,String,String,int,int) |
| MenuItemWrapper | `og` | 单参 ctor 包装 pg |
| handleClick | `onItemClick` | rg 内方法 |
| MMKernel | `hm0.j1` | 数据库内核 |
| getStorage | `u` | 获取 SQLiteDatabase |
| WCDB | `com.tencent.wcdb.database.SQLiteDatabase` | 数据库 |
| NetSceneSendMsg | `y11.r0` | 发消息 |
| NetSceneQueue | `com.tencent.mm.modelbase.r1` | 消息队列 |

### 5.1 DexKit 定位方式

- **PlusSubMenuHelper**: `searchPackages("com.tencent.mm.ui")` + `usingEqStrings("MicroMsg.PlusSubMenuHelper", "dyna plus config is null...")`
- **addItem/handleClick**: 同上字符串匹配
- **MenuItemData**: 5 字段匹配 (String, int, int, int, String)
- **SparseArray 陷阱**: 存的是 wrapper(og)，不是 data(pg)，需从现有元素推断 wrapper 类，用单参构造器包装

---

## 六、NativeHttp — 纯 Java HTTP 客户端

```
NativeHttp (object)
  ├─ queryCard(token, type, id)    → GET  /querySimBaseInfo
  ├─ diagnose(token, type, id)     → GET  /intelligentDiagnosis
  ├─ basicInfo(token, type, id)    → GET  /basicInfo
  ├─ operationCommit(token, body)  → POST /operationCommit (被 403，实际不用)
  │
  ├─ cachedToken: String?          ← pullToken 写入, 磁盘持久化
  ├─ cachedCookie: String?         ← pullToken 写入 (完整 cookie 串)
  ├─ cachedBond: String?           ← pullToken 写入
  │
  ├─ httpGet()                     → HttpURLConnection, 15s connect / 20s read
  └─ httpPost()                    → 同上 + errorStream 兜底
```

请求头:
```
Accept: application/json, text/plain, */*
User-Agent: Mozilla/5.0 (Linux; Android 15; WeChat) AppleWebKit/537.36
Authorization: Bearer {token}
Cookie: {cachedCookie}
Origin: https://tywlonestop.ctwing.cn:8081
Referer: https://tywlonestop.ctwing.cn:8081/web-apps/
```

Token 持久化路径: `{wechatDataDir}/dsh_ctwing_bundles/ctwing_token.json`

---

## 七、CtwingFacade — API 门面

```
CtwingFacade (object)
  ├─ webViewMutex: Mutex              ← 并发互斥锁
  │
  ├─ pullToken(): Boolean             ← 读 WebView cookie → cachedToken/cachedCookie
  │     callJs("return JSON.stringify({token, bond, cookie})")
  │     → JSONTokener 剥双重转义 → optString
  │
  ├─ pollDshResult(): String          ← 读 window.__dshResult (2s timeout)
  ├─ readApiResponses(): String       ← lastApiResponse || evaluateJs 读 __dshResult
  │
  ├─ queryCard(iccid)                 ← fireJs (异步, 调试用)
  ├─ diagnoseCard(iccid)              ← fireJs (异步)
  ├─ operationCommit(payload)         ← fireJs (异步, 重绑用)
  │
  ├─ queryCardSync/DiagnoseCardSync/OperationCommitSync  ← 同步 XHR (已弃用)
  │
  ├─ acquireWakeLock() / releaseWakeLock()
  ├─ fireJs(script)                   ← evaluateJavascript (null callback)
  └─ callJs/callJsLocal               ← evaluateJsForResult (ValueCallback)
```

### 7.1 pullToken JSON 解析

```kotlin
// callJs 返回 JSON.stringify() 的结果, 可能双重转义
val obj = runCatching {
    JSONObject(raw)                                    // 尝试直接解析
}.recoverCatching {
    JSONObject(JSONTokener(raw).nextValue() as String)  // 剥一层引号再解
}.getOrNull()
val token = obj?.optString("token", "") ?: ""
val cookie = obj?.optString("cookie", "") ?: ""
```

### 7.2 通信方式总结

| 方式 | 函数 | 返回 | 超时 | 用途 |
|------|------|------|------|------|
| fireJs (fire-and-forget) | `evaluateJavascript(js, null)` | Unit | N/A | 重绑 XHR |
| callJs (同步) | `evaluateJavascript(js, callback)` | String | 15s | pullToken |
| pollDshResult | `evaluateJavascript("return __dshResult", cb)` | String | 2s | 重绑结果轮询 |

---

## 八、H5 生命周期管理（方案A：静默后台）

### 8.1 启动流程

```kotlin
// MainHook.kt → CtwingFacade.preInitH5()
scope.launch { delay(8_000L); CtwingFacade.preInitH5() }

// CtwingFacade.rebuildH5()
Intent(MMWebViewUI).apply {
    addFlags(NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK)
    // H5 在独立任务栈打开——用户当前任务栈不受影响，微信首页不抢夺
}
```

### 8.2 WebView 偷取 + 退后台

```
MMWebViewUI 创建（独立任务栈，用户无感）
  → onResume → view tree scan → 找到 CTWing WebView
  → WebViewPool.steal(webView)
       → detach 从 MMWebViewUI
       → 挂到透明 overlay (alpha=0, not_touchable)
  → 窗口清理：
       - finish 掉多余的旧 MMWebViewUI 空壳（NEW_DOCUMENT 每次建新窗口）
       - 保留最新一个 moveTaskToBack(true) 退后台保活
  → 用户看到微信首页，H5 在 overlay 里继续运行
```

关键：
- 用 `moveTaskToBack` 而非 `finish()` 保留最新壳——finish 会暂停 WebView 渲染器，导致 pullToken 超时
- 但**多余的旧壳**（WebView 已被偷走、只剩空 Activity）必须 finish，否则任务管理器堆积窗口

### 8.3 重建互斥（防并发多窗口）

`rebuildAndWait` 用 `rebuildMutex` 互斥锁保护，防止 `pullTokenOrRebuild` 与 `TokenKeepAlive` 同时检测到 WebView 死亡并发重建：

```
rebuildMutex.withLock {
  // 二次检查：等锁期间可能已被其他协程修好
  if (findForHost("tywlonestop.ctwing.cn") != null) return true
  
  rebuildH5()          // 开新 MMWebViewUI
  轮询 findForHost     // 等 OAuth → SPA 完成
  finish 多余旧壳       // 窗口清理
  moveTaskToBack 最新   // 保活
}
```

锁顺序始终 `webViewMutex` → `rebuildMutex`（单向），无死锁。

### 8.4 TokenKeepAlive (25分钟)

首选: `WebViewPool.onResume()` 触发 SPA router.push → OAuth 刷新
兜底: WebView 死亡 → `ensureReady()` 重建 → 重拉 token
最后: `NativeHttp.basicInfo()` 滑动 TTL

---

## 九、已知陷阱清单

1. **lvbuffer CDATA**: atuserlist 值被 `<![CDATA[...]]>` 包裹, 不剥离永远不匹配
2. **@ 空格 U+2005**: 四分之一空格, 正则需 `[\s\u2005]`
3. **群聊 content 格式**: `wxid_xxx:@昵称 正文`, 需两次剥离
4. **selfWxId**: `CoreAccount.getCurrentUserName()` 不可靠 → 读 SharedPreferences
5. **SparseArray wrapper**: 存 og 不存 pg → 单参 ctor 包装
6. **NativeHttp POST 403**: CSRF 校验, 必须走 WebView XHR
7. **pullToken 双重转义**: callJs 返回 JSON.stringify → JSONTokener 剥一层
8. **同步 XHR 不可用**: X5 返回 status:0, 已全部改用异步
9. **并发数据串扰**: lastApiResponse 全局单例 → webViewMutex 串行化
10. **DexKit 多进程崩溃**: libdexkit.so 限单进程加载

---

## 十、文件地图

```
app/src/main/java/dev/example/autoreply/
├── hook/
│   ├── MainHook.kt              — Xposed 入口, 进程路由, 过滤链, 引擎启动
│   ├── WeChatHook.kt            — WCDB hook, sendText/sendImage, selfWxId
│   ├── IWeChatHook.kt           — 接口 + IncomingMessage 数据类
│   ├── PopupMenuHook.kt         — "+"菜单注入 (DexKit → HomeUI → SparseArray)
│   ├── WeDatabaseApi.kt         — rconversation rawQuery, isAtMe 兜底
│   ├── AtParser.kt              — lvbuffer → msgSource → atuserlist
│   ├── WhitelistLauncher.kt     — ComposeView 弹窗 + XposedLifecycleOwner
│   └── TinkerGuard.kt           — 热更新三层防护
│
├── ctwing/
│   ├── CtwingKeywordRouter.kt   — 关键词路由 + 格式化 (625行)
│   ├── CtwingFacade.kt          — API 门面: pullToken/pollDshResult/互斥锁 (614行)
│   ├── NativeHttp.kt            — 原生 HTTP (GET/POST/cookie/token 缓存) (165行)
│   ├── CtwingWebViewHook.kt     — WebView 劫持 + evaluateJs (877行)
│   ├── CtwingJsInjector.kt      — JS 注入: window.__ctwing.* (629行)
│   ├── CtwingJsBridge.kt        — addJavascriptInterface bridge
│   ├── CtwingCrypto.kt          — CTWing 加解密 (CTROBF1)
│   ├── CtwingIpcBridge.kt       — 跨进程文件 IPC
│   ├── CtwingNetworkHook.kt     — okhttp/Cronet 网络 hook
│   ├── CtwingBundleCapture.kt   — SPA JS/CSS bundle 捕获
│   ├── TokenKeepAlive.kt        — 25分钟 token 心跳 (81行)
│   └── WebViewPool.kt           — WebView 偷取 + overlay window (201行)
│
├── ui/
│   ├── WhitelistScreen.kt       — 联系人勾选 Compose UI
│   └── WhitelistStore.kt        — JSON 文件存储
│
├── trigger/
│   ├── MessageTrigger.kt        — 正则过滤 + cooldown
│   └── BufferedMessageTrigger.kt — debounce 聚合
│
├── engine/
│   └── AutoReplyEngine.kt       — 引擎骨架 (被 MainHook 替代)
│
├── llm/
│   └── OpenAiClient.kt          — OpenAI 兼容客户端 (预留)
│
└── media/
    └── MediaHandler.kt          — 图片回复扩展点 (预留)
```