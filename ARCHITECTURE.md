# 天翼物联一站式服务工具 — 技术架构文档

> 更新：2026-09-30 | 微信 8.0.76 | Xiaomi 14 Pro KernelSU

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
│    ├─ PopupMenuHook: "+"菜单注入白名单/状态/穿透三入口              │
│    ├─ TokenKeepAlive: 30s tick + 30min token 刷新                │
│    ├─ WebAdminServer: 内置 HTTP 后台 (端口 60080)                  │
│    ├─ TunnelManager: 自研 TCP 反向隧道                             │
│    └─ 方案A: 静默启动 (NEW_DOCUMENT+MULTIPLE_TASK + moveTaskToBack) │
└─────────────────────────────────────────────────────────────────┘
```

### 1.1 进程路由策略

| 进程 | 初始化内容 |
|------|-----------|
| `com.tencent.mm` (主) | DexKit + TinkerGuard + 引擎 + 白名单 + TokenKeepAlive + H5预初始化 + WebAdmin + 内网穿透 |
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
  │     CtwingKeywordRouter.tryHandle() → webViewMutex.withLock
  │       query   → nativeGetWithRetry → formatCardInfo
  │       diagnose → nativeGetWithRetry → formatDiagnosis
  │       rebind   → executeRebind (XHR → fallback NativeHttp)
  │       renew    → forceRebuild + pullToken + 新旧对比
  │       status   → basicInfo 真实验证
  │
  └─ ⑤ 兜底回复（非 CTWing 命令统一回复欢迎帮助）
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
val ctx = ActivityThread.currentActivityThread().application
val prefs = ctx.getSharedPreferences("com.tencent.mm_preferences", Context.MODE_PRIVATE)
val wxId = prefs.getString("login_weixin_username", null)
```

不使用 `CoreAccount.getCurrentUserName()`（不可靠，常返回 null）。

---

## 三、CTWing 操作 — 完整技术方案

### 3.1 操作分流表

| 操作 | 识别词 | HTTP 方法 | 传输层 | 共用函数 |
|------|--------|----------|--------|---------|
| 查询 | 查询/查卡/query | GET | **NativeHttp** | nativeGetWithRetry + formatCardInfo |
| 诊断 | 诊断/diagnose | GET | **NativeHttp** | nativeGetWithRetry + formatDiagnosis |
| 重绑 | 重绑/rebind/解绑 | POST | **WebView XHR → NativeHttp fallback** | executeRebind |
| 续期 | 续期/renew | OAuth reload | **WebView** | forceRebuild + pullToken |
| 自检 | 状态/status | GET | **NativeHttp** | nativeGetWithRetry + basicInfo |

### 3.2 请求重试机制 (nativeGetWithRetry)

```kotlin
suspend fun nativeGetWithRetry(tag: String, request: () -> String): String {
    pullToken()  // 确保 token 有效（10min 缓存 TTL）
    var resp = request()
    if (resp.contains("\"code\":401")) {
        forceRebuild()  // 重走 OAuth 换新 token
        pullToken()     // 重拉
        resp = request() // 重试
    }
    return resp
}
```

### 3.3 重绑流程（executeRebind — 聊天/Web 共用）

```
pullToken()
  ├─ 幂等检查：30s 窗口内同卡号不重复
  ├─ fireJs("window.__ctwing.operationCommit('{...}')")
  ├─ 轮询 pollDshResult() (15次 × 2s)
  ├─ XHR 成功 → 解析 → 返回
  └─ XHR 超时 → Fallback NativeHttp POST（带 cachedCookie）
       → 401 → forceRebuild → 重试 → 解析 → 返回
```

### 3.4 并发互斥

```kotlin
// CtwingKeywordRouter.kt — 聊天端
CtwingFacade.webViewMutex.withLock { ... }

// WebAdminServer.kt — Web 端
CtwingFacade.webViewMutex.withLock { ... }
```

`webViewMutex` 串行化所有 CTWing 操作。锁序：webViewMutex → rebuildMutex（单向，无死锁）。

### 3.5 Wake Lock

```kotlin
acquireWakeLock()  // SCREEN_DIM_WAKE_LOCK, 50s
// ... 操作 ...
releaseWakeLock()
```

防止锁屏时 WebView/X5 的 JS 引擎冻结。

---

## 四、关键数据结构

### 4.1 IncomingMessage

```kotlin
data class IncomingMessage(
    val msgSvrId: Long?,
    val type: Int?,             // 1=文本
    val talker: String?,        // 发送者 wxid / 群聊 id
    val content: String?,       // 群聊: wxid_xxx:@昵称 正文
    val isSend: Boolean,
    val createTime: Long?,
    val lvBuffer: ByteArray?    // 含 msgSource/atuserlist
)
```

### 4.2 CTWing API 端点

| 端点 | 方法 | 参数 |
|------|------|------|
| `/querySimBaseInfo` | GET | `type={iccid\|msisdn\|imsi}&id=...` |
| `/intelligentDiagnosis` | GET | `type=&id=` |
| `/operationCommit` | POST | `{"type","id","imei","source","orderNumber","sessionId","comment","bindType","file":{"ids":[]},"operation":"JKCB"}` |

BASE: `https://tywlonestop.ctwing.cn:8081/webapp-font/admin-api/bpm/service-assistant`

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

---

## 六、NativeHttp — 纯 Java HTTP 客户端

```kotlin
NativeHttp (object)
  ├─ queryCard(token, type, id)    → GET  /querySimBaseInfo
  ├─ diagnose(token, type, id)     → GET  /intelligentDiagnosis
  ├─ basicInfo(token, type, id)    → GET  /basicInfo
  ├─ operationCommit(token, body)  → POST /operationCommit (重绑 fallback)
  │
  ├─ cachedToken: String?          ← pullToken 写入
  ├─ cachedCookie: String?         ← pullToken 写入 (完整 cookie 串)
  ├─ cachedBond: String?           ← pullToken 写入
  │
  ├─ httpGet()                     → HttpURLConnection, 30s connect / 60s read
  └─ httpPost()                    → 同上 + errorStream 兜底
```

配置：`instanceFollowRedirects=false`（手动处理 401/3xx，防重定向死循环）。

---

## 七、CtwingFacade — API 门面

```
CtwingFacade (object)
  ├─ webViewMutex: Mutex              ← 全局互斥锁（聊天+Web 共用）
  ├─ rebuildMutex: Mutex              ← 防并发重建锁
  │
  ├─ pullToken(): Boolean             ← 读 WebView cookie → cachedToken/cachedCookie
  │     10min 缓存 TTL (lastTokenPullAt)，空读取不清空缓存
  │
  ├─ pollDshResult(): String          ← 读 window.__dshResult (2s timeout)
  ├─ forceRebuild()                   ← reloadOAuthOnPool (H5 存活) 或 rebuildAndWait
  ├─ reloadOAuthOnPool()              ← 对现有 WebView loadUrl OAuth
  ├─ rebuildAndWait()                 ← 完整重建 H5（rebuildMutex 保护）
  │
  └─ preInitH5()                      ← 启动延迟 15s 后静默打开 CTWing OAuth
```

### 7.1 Token 策略

- pullToken 有 10 分钟缓存 TTL（`TOKEN_CACHE_TTL_MS`），`lastTokenPullAt` 时间戳
- forceRebuild 成功后重置 `lastTokenPullAt = 0`
- pullToken 空读取不清空 cachedToken（防 OAuth reload 后自毁）
- TokenKeepAlive: 30s tick 检测 WebView 存活 → 死则 forceRebuild；活且超过 30min → forceRebuild

---

## 八、H5 生命周期管理（方案A：静默后台）

### 8.1 启动流程

```kotlin
// MainHook.kt → TokenKeepAlive 启动延迟 15s
scope.launch { delay(15_000L); TokenKeepAlive.start() }
// → 内部 preInitH5: 静默打开 CTWing OAuth（独立任务栈）

Intent(MMWebViewUI).apply {
    addFlags(NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK)
}
```

### 8.2 WebView 偷取 + 退后台

```
MMWebViewUI 创建（独立任务栈，用户无感）
  → onResume → view tree scan → 找到 CTWing WebView
  → WebViewPool.steal(webView)
       → detach 从 MMWebViewUI → 挂到透明 overlay (alpha=0, not_touchable)
  → 窗口清理：
       - finish 多余旧 MMWebViewUI 空壳
       - 保留最新一个 moveTaskToBack(true) 退后台保活
  → 用户看到微信首页，H5 在 overlay 继续运行
```

### 8.3 重建互斥

`rebuildMutex` 保护，防 `pullTokenOrRebuild` 与 `TokenKeepAlive` 并发重建：
- 锁内二次检查（等锁期间可能已被其他协程修好）
- forceRebuild 优先 `reloadOAuthOnPool`（不杀 H5），仅 WebView 已死才 `rebuildAndWait`

---

## 九、Web 管理后台

### 9.1 HTTP Server

- 零依赖 `ServerSocket` 实现，端口 60080
- `BufferedInputStream` + 手动 `readLineBytes`（避免 BufferedReader 预读吞 POST body）
- Cookie 不可靠，改用 `Authorization: Bearer <token>` 头验证会话

### 9.2 路由

| 路由 | 功能 |
|------|------|
| `/api/login` | 账号登录（SHA-256） |
| `/api/status` | 服务状态（Token + TinkerGuard） |
| `/api/diagnose` | CTWing 诊断 |
| `/api/rebind` | CTWing 重绑 |
| `/api/ocr` | AI OCR ICCID 识别 |
| `/api/accounts/*` | 账号管理（list/add/delete/changepw/resetpw） |
| `/api/selfcheck` | 自检（测试卡号 basicInfo 验证） |
| `/api/logout` | 登出 |

### 9.3 业务复用原则

Web 端所有 CTWing 操作**不得**复制业务逻辑，必须复用聊天端同一套函数：
- 查询/诊断/自检 → `nativeGetWithRetry`
- 重绑 → `executeRebind(iccid)`
- 续期 → `forceRebuild` + `pullToken`

所有操作都在 `webViewMutex.withLock` 内执行（统一队列）。

---

## 十、内网穿透（自研 TCP 反向隧道）

替代 frpc（16MB Go 二进制 + SELinux exec 拦截）。纯 Socket 实现：

- **客户端**: `tunnel/TunnelClient.kt`（java.net.Socket，断线 5s 重连，bridge 双线程 pipe）
- **管理器**: `tunnel/TunnelManager.kt` 生命周期管理
- **配置**: `tunnel/TunnelConfig.kt` JSON 持久化（含 `selfCheckCard` 自检测试卡号）
- **服务端**: `tunnel_server.py`（Python3 标准库，零依赖）部署在 NAS，systemd 开机自启

协议：`AUTH <token>` → `AUTH_OK` → `REGISTER <port>` → `REGISTER_OK` → `PING`/`PONG` 心跳(35s) → `NEWCONN` → 双向 relay。

---

## 十一、已知陷阱清单

1. **lvbuffer CDATA**: atuserlist 值被 `<![CDATA[...]]>` 包裹，不剥离永远不匹配
2. **@ 空格 U+2005**: 四分之一空格，正则需 `[\s\u2005]`
3. **群聊 content 格式**: `wxid_xxx:@昵称 正文`，需两次剥离
4. **selfWxId**: `CoreAccount.getCurrentUserName()` 不可靠 → 读 SharedPreferences
5. **SparseArray wrapper**: 存 og 不存 pg → 单参 ctor 包装
6. **NativeHttp POST 403**: CSRF 校验，必须走 WebView XHR（重绑有 NativeHttp fallback 兜底）
7. **pullToken 双重转义**: callJs 返回 JSON.stringify → JSONTokener 剥一层
8. **同步 XHR 不可用**: X5 返回 status:0，已全部改用异步
9. **并发数据串扰**: lastApiResponse 全局单例 → webViewMutex 串行化
10. **DexKit 多进程崩溃**: libdexkit.so 限单进程加载
11. **BufferedReader 预读吞 POST body**: HTTP server 必须用 BufferedInputStream + 手动读
12. **移动 WebView Activity 用 moveTaskToBack 不用 finish**: finish 暂停渲染器 → pullToken 超时
13. **instanceFollowRedirects=false**: 防 token 为空时 302 重定向死循环
14. **forceRebuild 优先 reloadOAuthOnPool**: 不杀 H5，对现有 WebView 重走 OAuth
15. **PowerShell Set-Content 损坏 UTF-8 中文**: 含中文的 .kt 文件必须用 edit/write 工具修改

---

## 十二、文件地图

```
app/src/main/java/dev/example/autoreply/
├── hook/
│   ├── MainHook.kt              — Xposed 入口, 进程路由, 过滤链, 引擎启动
│   ├── WeChatHook.kt            — WCDB hook, sendText/sendImage, selfWxId
│   ├── IWeChatHook.kt           — 接口 + IncomingMessage 数据类
│   ├── PopupMenuHook.kt         — "+"菜单注入 (DexKit → HomeUI → SparseArray)
│   ├── WeDatabaseApi.kt         — rconversation rawQuery, isAtMe 兜底
│   ├── AtParser.kt              — lvbuffer → msgSource → atuserlist
│   ├── AtMentionHook.kt         — 真实 @ 通知：atuserlist 注入
│   ├── WhitelistLauncher.kt     — ComposeView 弹窗 + XposedLifecycleOwner
│   └── TinkerGuard.kt           — 热更新三层防护
│
├── ctwing/
│   ├── CtwingKeywordRouter.kt   — 关键词路由 + 格式化（含 executeRebind/nativeGetWithRetry）
│   ├── CtwingFacade.kt          — API 门面: pullToken/forceRebuild/mutex (约 500 行)
│   ├── NativeHttp.kt            — 原生 HTTP (GET/POST, 30/60s 超时)
│   ├── CtwingWebViewHook.kt     — WebView 劫持 + evaluateJs
│   ├── CtwingJsInjector.kt      — JS 注入: window.__ctwing.*
│   ├── CtwingJsBridge.kt        — addJavascriptInterface bridge
│   ├── CtwingCrypto.kt          — CTWing 加解密 (CTROBF1)
│   ├── CtwingIpcBridge.kt       — 跨进程文件 IPC（未使用）
│   ├── CtwingNetworkHook.kt     — okhttp/Cronet 网络 hook
│   ├── CtwingBundleCapture.kt   — SPA bundle 捕获
│   ├── TokenKeepAlive.kt        — 30s tick + 30min 刷新
│   └── WebViewPool.kt           — WebView 偷取 + overlay window
│
├── web/
│   ├── WebAdminServer.kt        — 内置 HTTP Server (端口 60080, 约 900 行)
│   ├── AccountStore.kt          — 账号 CRUD (SHA-256)
│   └── AgnesAiClient.kt         — AI OCR ICCID 识别 (agnes-3.0-flash)
│
├── tunnel/
│   ├── TunnelClient.kt          — 自研 TCP 反向隧道客户端
│   ├── TunnelManager.kt         — 生命周期管理
│   └── TunnelConfig.kt          — JSON 配置 (含 selfCheckCard)
│
├── ui/
│   ├── WhitelistScreen.kt       — 联系人勾选 Compose UI（仅微信弹窗）
│   ├── WhitelistStore.kt        — JSON 文件存储
│   ├── StatusScreen.kt          — 服务状态面板
│   ├── TunnelScreen.kt          — 内网穿透配置
│   └── TunnelLauncher.kt        — 穿透启动入口
│
└── trigger/
    ├── MessageTrigger.kt        — 正则过滤 + cooldown
    └── BufferedMessageTrigger.kt — Debounce 聚合
```

---

## 十三、硬性规则

### 版本号

编译时 Unix 时间戳自动生成：versionCode=Unix 秒，versionName=`yyyyMMddHHmmss`（东八区）。

### 锁顺序

`webViewMutex` → `rebuildMutex` 单向。禁止反向获取。

### 文件修改

含中文的 .kt 文件禁止用 PowerShell Set-Content/字符串替换，必须用 edit/write 工具。

### Git 推送

```bash
git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 push
```

### 业务复用

Web 端所有 CTWing 操作必须复用聊天端同一套函数，禁止复制粘贴。