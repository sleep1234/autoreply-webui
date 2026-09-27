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
  ├─ CTWing keyword router (查询/诊断/重绑)
  │    ├── query   → NativeHttp GET  → formatCardInfo
  │    ├── diagnose → NativeHttp GET  → formatDiagnosis
  │    └── rebind   → WebView XHR POST → parse result
  └─ Fallback reply
```

## CTWing Operations

| Operation | Method | Transport |
|-----------|--------|-----------|
| query     | GET `/querySimBaseInfo` | NativeHttp (HttpURLConnection) |
| diagnose  | GET `/intelligentDiagnosis` | NativeHttp (HttpURLConnection) |
| rebind    | POST `/operationCommit` | WebView XHR (needs browser CSRF context) |

Token acquired via `evaluateJavascript` reading `document.cookie` from the CTWing H5 WebView.

## File Map

```
app/src/main/java/dev/example/autoreply/
├── hook/
│   ├── MainHook.kt              — Xposed entry, process routing, filter chain
│   ├── WeChatHook.kt            — WCDB hook, sendText, selfWxId
│   ├── IWeChatHook.kt           — IncomingMessage data class, interface
│   ├── PopupMenuHook.kt         — "+" menu whitelist injection
│   ├── WeDatabaseApi.kt         — rconversation rawQuery
│   ├── AtParser.kt              — lvbuffer → msgSource → atuserlist
│   └── WhitelistLauncher.kt     — ComposeView contact picker
├── ctwing/
│   ├── CtwingKeywordRouter.kt   — 查询/诊断/重绑 routing
│   ├── CtwingFacade.kt          — pullToken, pollDshResult, mutex
│   ├── NativeHttp.kt            — HTTP client (GET/POST, cachedToken/cachedCookie)
│   ├── CtwingWebViewHook.kt     — WebView hijack + evaluateJs
│   ├── CtwingJsInjector.kt      — window.__ctwing.* JS injection
│   └── ...                      — Crypto, IPC, keep-alive, etc.
└── ui/
    ├── WhitelistScreen.kt       — Compose contact picker UI
    └── WhitelistStore.kt        — JSON file storage
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

# Install via adb
adb push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/autoreply.apk
adb shell pm install -r /data/local/tmp/autoreply.apk
adb shell am force-stop com.tencent.mm
adb shell am start -n com.tencent.mm/.ui.LauncherUI
```

## Known Pitfalls

1. **lvbuffer** captured during INSERT; no DB fallback needed.
2. **atuserlist** is CDATA-wrapped — strip `<![CDATA[` / `]]>`.
3. **Group content format**: `wxid_xxx:@昵称 正文`; @ separator is U+2005.
4. **selfWxId** must come from `com.tencent.mm_preferences` key `login_weixin_username`.
5. **SparseArray** stores wrapper (og), not data (pg) — use single-arg ctor.
6. **NativeHttp POST → 403**: CSRF on POST endpoints; must use WebView XHR.
7. **pullToken double-escaping**: `callJs` returns JSON.stringify result — use JSONTokener.

## License

GPL-3.0 (derived from WeKit).