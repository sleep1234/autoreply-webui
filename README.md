# WeChat Auto-Reply Xposed Module (LSPosed)

Target: WeChat 8.0.76 (`com.tencent.mm`), Xiaomi 14 Pro, KernelSU root.

## Architecture

```
Message INSERT (WCDB hook)
  │  lvbuffer → msgSource → atuserlist
  ▼
BufferedMessageTrigger (1.5s debounce, dedup by talker+content)
  │
  ▼
onFlush:
  ├─ Whitelist gate  (/data/data/com.tencent.mm/files/autoreply_whitelist.json)
  ├─ Group @-mention gate  (atuserlist → isAtMe)
  ├─ CTWing keyword router (查询/诊断/重绑/续期/状态)
  │    ├── query   → nativeGetWithRetry → formatCardInfo
  │    ├── diagnose → nativeGetWithRetry → formatDiagnosis
  │    ├── rebind   → executeRebind (XHR → fallback NativeHttp)
  │    ├── renew    → forceRebuild + pullToken + 新旧对比
  │    └── status   → basicInfo 真实验证
  └─ Fallback reply (欢迎帮助信息)
```

### Web Admin (端口 60080)

内置 HTTP Server（零依赖 ServerSocket），提供 Web 管理后台：
- 账号系统（SHA-256，默认 admin/admin123）
- AI OCR 识别 ICCID（AgnesAiClient，agnes-3.0-flash）
- 诊断 / 重绑 / 续期 / 自检 — 全部复用窗口端同一套业务函数
- 统一队列：`webViewMutex` 串行化聊天 + Web 所有操作

### 内网穿透 (自研 TCP 反向隧道)

纯 Socket 实现，替代 frpc：`tunnel/TunnelClient.kt` + `tunnel/TunnelManager.kt` + `tunnel/TunnelConfig.kt`。
服务端 `tunnel_server.py` 部署在 NAS，systemd 开机自启。

## CTWing Operations

| Operation | Method | Transport | Implementation |
|-----------|--------|-----------|----------------|
| query     | GET `/querySimBaseInfo` | NativeHttp | nativeGetWithRetry |
| diagnose  | GET `/intelligentDiagnosis` | NativeHttp | nativeGetWithRetry |
| rebind    | POST `/operationCommit` | WebView XHR + NativeHttp fallback | executeRebind (shared) |
| renew     | OAuth reload | WebView | forceRebuild + pullToken |
| selfCheck | GET basicInfo | NativeHttp | nativeGetWithRetry |

Token acquired via `evaluateJavascript` reading `document.cookie` from the CTWing H5 WebView.
10-min cache TTL (`lastTokenPullAt`), forceRebuild resets to 0.

## File Map

```
app/src/main/java/dev/example/autoreply/
├── hook/
│   ├── MainHook.kt              — Xposed entry, process routing, filter chain
│   ├── WeChatHook.kt            — WCDB hook, sendText, selfWxId
│   ├── IWeChatHook.kt           — IncomingMessage data class, interface
│   ├── PopupMenuHook.kt         — "+" menu whitelist/status/tunnel injection
│   ├── WeDatabaseApi.kt         — rconversation rawQuery
│   ├── AtParser.kt              — lvbuffer → msgSource → atuserlist
│   ├── AtMentionHook.kt         — 真实 @ 通知：atuserlist 注入
│   ├── WhitelistLauncher.kt     — ComposeView contact picker
│   └── TinkerGuard.kt           — 热更新三层防护
├── ctwing/
│   ├── CtwingKeywordRouter.kt   — 查询/诊断/重绑/续期 routing + formatting
│   ├── CtwingFacade.kt          — pullToken, pollDshResult, forceRebuild, mutex
│   ├── NativeHttp.kt            — HTTP client (GET/POST, cachedToken/cachedCookie)
│   ├── CtwingWebViewHook.kt     — WebView hijack + evaluateJs
│   ├── CtwingJsInjector.kt      — window.__ctwing.* JS injection
│   ├── CtwingJsBridge.kt        — addJavascriptInterface bridge
│   ├── CtwingCrypto.kt          — CTWing encryption (CTROBF1)
│   ├── CtwingIpcBridge.kt       — cross-process file IPC (unused)
│   ├── CtwingNetworkHook.kt     — okhttp/Cronet network hook
│   ├── CtwingBundleCapture.kt   — SPA bundle capture
│   ├── TokenKeepAlive.kt        — 30s tick + 30min refresh
│   └── WebViewPool.kt           — WebView steal + overlay window
├── web/
│   ├── WebAdminServer.kt        — Built-in HTTP server (port 60080)
│   ├── AccountStore.kt          — Account CRUD (SHA-256)
│   └── AgnesAiClient.kt         — AI OCR for ICCID extraction
├── tunnel/
│   ├── TunnelClient.kt          — Reverse TCP tunnel
│   ├── TunnelManager.kt         — Lifecycle management
│   └── TunnelConfig.kt          — JSON config (incl. selfCheckCard)
├── ui/
│   ├── WhitelistScreen.kt       — Compose contact picker UI
│   ├── WhitelistStore.kt        — JSON file storage
│   ├── StatusScreen.kt          — 服务状态面板
│   ├── TunnelScreen.kt          — 内网穿透配置
│   └── TunnelLauncher.kt        — 穿透启动入口
└── trigger/
    ├── MessageTrigger.kt        — Regex filter + cooldown
    └── BufferedMessageTrigger.kt — Debounce aggregation
```

## WeChat 8.0.76 Obfuscation Map

| Role | Class |
|------|-------|
| PlusSubMenuHelper | `com.tencent.mm.ui.HomeUI` |
| Inner helper | `com.tencent.mm.ui.rg` |
| MenuItemData | `pg` (ctor: int,String,String,int,int) |
| MenuItemWrapper | `og` (wraps pg) |
| handleClick | `onItemClick` (in rg) |
| MMKernel | `hm0.j1` |
| getStorage | `u` |
| NetSceneSendMsg | `y11.r0` |
| NetSceneQueue | `com.tencent.mm.modelbase.r1` |

## Build & Deploy

```powershell
$env:JAVA_HOME="C:\JDK\jdk-17.0.2"
$env:ANDROID_HOME="C:\Android\Sdk"
.\gradlew assembleDebug

# Install via adb (dual-app user 999)
adb -s <serial> push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/autoreply.apk
adb -s <serial> shell pm install -r /data/local/tmp/autoreply.apk
adb -s <serial> shell am force-stop --user 0 com.tencent.mm
adb -s <serial> shell am force-stop --user 999 com.tencent.mm
adb -s <serial> shell am start --user 999 com.tencent.mm/.ui.LauncherUI
```

Version numbers auto-generated from build timestamp:
- `versionCode` = Unix timestamp (seconds)
- `versionName` = `yyyyMMddHHmmss` (Asia/Shanghai)

## Devices

| Serial | Model | User | Web Port | Tunnel |
|--------|-------|------|----------|--------|
| 2410DPN6CC | Xiaomi 14 Pro (haotian) | 999 | 60080 | 60080 |
| 23116PN5BC | Xiaomi 14 Pro (shennong) | 999 | 60081 | 60081 |

## Key Rules

1. **Lock order**: `webViewMutex` → `rebuildMutex` (one-way, no deadlock).
2. **No business logic duplication**: Web admin must reuse chat-side functions (nativeGetWithRetry, executeRebind, etc.).
3. **Never edit .kt files with PowerShell Set-Content** — destroys UTF-8 Chinese characters. Use edit/write tools only.
4. **Git push requires proxy**: `git -c http.proxy=http://127.0.0.1:7890 -c https.proxy=http://127.0.0.1:7890 push`.

## Known Pitfalls

1. **lvbuffer** captured during INSERT; no DB fallback needed.
2. **atuserlist** is CDATA-wrapped — strip `<![CDATA[` / `]]>`.
3. **Group content format**: `wxid_xxx:@昵称 正文`; @ separator is U+2005.
4. **selfWxId** must come from `com.tencent.mm_preferences` key `login_weixin_username`.
5. **SparseArray** stores wrapper (og), not data (pg) — use single-arg ctor.
6. **NativeHttp POST → 403**: CSRF on POST endpoints; must use WebView XHR.
7. **pullToken double-escaping**: `callJs` returns JSON.stringify result — use JSONTokener.
8. **instanceFollowRedirects=false**: prevent redirect loops when token is empty.
9. **BufferedReader pre-reads POST body bytes** — use BufferedInputStream + manual readLineBytes for HTTP server.

## License

GPL-3.0 (derived from WeKit).