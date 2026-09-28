# 天翼物联一站式服务工具 — 开发复盘与经验总结

> 2026-09-27 | 全会话复盘（两轮对话合并）

---

## 一、解决的问题总览

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 1 | 诊断/重绑返回空或超时 | 同步 XHR 在 X5 内核被禁用（status:0） | NativeHttp GET + WebView XHR POST 分流 |
| 2 | 并发查询/重绑数据串扰 | CtwingJsBridge.lastApiResponse 全局单例 | webViewMutex 串行化 |
| 3 | H5 抢前台影响微信使用 | MMWebViewUI 默认进入前台任务栈 | 方案A：NEW_DOCUMENT+MULTIPLE_TASK + moveTaskToBack |
| 4 | 自建 WebView 绕开微信 | pinus.sdk.WebView 冷启动 NPE | 验证不可行，写入 SELF_CREATE_WEBVIEW_RESEARCH.md |
| 5 | 非台州卡查询返回 raw JSON | 无业务错误码友好提示 | extractQueryError 友好提示 |
| 6 | 非命令消息回复太随意 | 旧的在吗/帮助/兜底回复 | 统一欢迎帮助信息 |
| 7 | **诊断 401 账号未登录** | 诊断分支漏 pullToken，直接用过期 token | 补 pullToken（与查询一致） |
| 8 | **白名单双开无法保存** | createPackageContext 跨包无写权限 | 改用微信 lpparam.appInfo.dataDir |
| 9 | **白名单空列表语义** | 旧逻辑空列表=回复所有人 | 改为空列表=不回复任何人 |
| 10 | **白名单保存按钮残留** | 清空只清内存，保存时预勾选复写 | 清空立即写文件 |
| 11 | 模块更名/去图标/静态scope | 产品化需求 | 天翼物联一站式服务工具 |
| 12 | **白名单保存后重开不显示勾选** | 排查旧数据时把 selected 改成永远空集合，破坏回显 | selected 恢复从 list() 初始化 |
| 13 | **群聊回复 @ 发起者 + 真实 @ 通知** | 普通 sendText 无 @；纯文本 @ 无服务器提醒 | AtMentionHook 入库前注入 atuserlist + wrapReply 加 @昵称 |
| 14 | **重绑结果回显卡号** | 结果只显示工单号，群聊难对应 | 结果末尾追加 ICCID/接入号/IMSI 标签 + 号码 |
| 15 | **手动 @（普通空格）不被识别** | 微信 @ 靠 atuserlist，手动打 @ 无此数据 | 兜底检测 content 含 @自身昵称 |
| 16 | **WebView 被杀后无自动恢复** | pullToken/fireJs 失败时只抛异常，不重建 | pullTokenOrRebuild 兜底 + fireJs 兜底重建 + TokenKeepAlive 重建 |
| 17 | **死代码清理** | queryCardSync/diagnoseCardSync/operationCommitSync 同步 XHR 不可用 | 删除三个 Sync 方法（~90 行） |
| 18 | **重绑 XHR 回调不触发（双开）** | X5 后台 JS 事件循环被挂起，onreadystatechange 不执行 | 6 秒轮询超时后 fallback NativeHttp POST（带 cachedCookie） |
| 19 | **并发重建导致多窗口叠加** | pullTokenOrRebuild 与 TokenKeepAlive 同时 rebuildAndWait，无锁保护 | rebuildAndWait 加 rebuildMutex 互斥锁 + 二次检查 |
| 20 | **旧 MMWebViewUI 空壳堆积** | NEW_DOCUMENT 每次建新窗口，旧壳不自动关 | 偷取后 finish 多余旧壳（dropLast(1)），只保留最新一个 moveTaskToBack |
| 21 | **命令容错** | startsWith 匹配，要求精确格式 | contains 匹配 + 支持号码/命令词任意位置 + 新增"解绑"别名 |
| 22 | **重绑缺少幂等保护** | 无防重复机制，手抖/群聊重复推送会建重复工单 | 30s 幂等窗口（ConcurrentHashMap 记录卡号→时间戳） |
| 23 | **版本号手动维护** | 手写 versionCode/versionName，容易忘改 | 编译时 Unix 时间戳自动生成（`SimpleDateFormat("yyyyMMddHHmmss")`） |
| 24 | **重绑失败分类错误** | 所有失败都同等对待（清除幂等） | isPermanentBusinessError 区分永久/临时错误；永久保留幂等防刷 |
| 25 | **401 token 过期无自动刷新** | pullTokenOrRebuild 只在 WebView null 时重建，token 在旧不过期不走重建 | 查询/诊断收到 401 → forceRebuild 走完整 OAuth → 自动重试 |
| 26 | **查询/诊断无命令空格的容错** | startsWith 要求精确格式 | contains 匹配 + 支持号码/命令词任意位置

---

## 硬性规则

### 版本号

**每次编译自动更新，使用编译时的 Linux 时间戳**：

| 字段 | 计算方式 | 示例 |
|------|----------|------|
| versionCode | `System.currentTimeMillis() / 1000L`（Unix 秒） | `1790575646` |
| versionName | `SimpleDateFormat("yyyyMMddHHmmss")`（东八区） | `20260928140726` |

规则写入 `app/build.gradle.kts` 顶部注释，自动执行，无需手动改版本号。versionCode 天然递增，可排序追溯。

### 锁顺序

| 情况 | 说明 |
|------|------|
| webViewMutex → rebuildMutex | 有 webViewMutex 的操作可调用 rebuildAndWait（获取 rebuildMutex） |
| rebuildMutex 单独 | TokenKeepAlive 等外部可单独获取 rebuildMutex |

**禁止反向获取**（持有 rebuildMutex 时获取 webViewMutex），否则死锁。

---

## 二、核心技术洞察

### 2.1 进程模型真相

最初以为 CTWing H5 的 WebView 在 `:tools` 进程，实现了完整的文件 IPC 桥（CtwingIpcBridge）。
**真相**：WebView 的 Java 对象（evaluateJavascript、addJavascriptInterface）都在主进程。
X5 渲染进程只负责 JS 执行和页面渲染，binder 透明转发。
IPC 桥从未被使用——`isInjected()` 在主进程恒为 true。
教训：**先验证进程模型再设计跨进程方案，不要基于假设写代码。**

### 2.2 同步 XHR 在 X5 中不可用

X5/Chromium WebView 默认禁用主线程同步 XHR（`xhr.open('GET', url, false)`）。
请求能发出但返回 `status:0, body:""`。
JEP：**WebView 内 JS 只能用异步 fetch/XHR + 结果回传机制。**

### 2.3 NativeHttp vs WebView JS 分流原则

| 接口 | 方法 | 方案 | 原因 |
|------|------|------|------|
| /querySimBaseInfo | GET | NativeHttp | GET 无 CSRF 校验 |
| /intelligentDiagnosis | GET | NativeHttp | 同上 |
| /operationCommit | POST | WebView XHR | POST 有 CSRF 校验，必须浏览器内核上下文 |

CSRF 校验不靠 Origin/Referer header——加了也 403。浏览器内核自带页面级 CSRF token。
JEP：**GET 走纯 HTTP，POST 走 WebView。不要试图用 HttpURLConnection 伪装浏览器发 POST。**

### 2.4 自建 X5 WebView 不可行

`com.tencent.xweb.pinus.sdk.WebView` 裸 new 可以构造，但内部 `reflectInterface`（WebViewInterface 委托）为 null。
参与 View 树布局（sizeChange → onCheckIsTextEditor）时 NPE 崩溃。
Pinus 内核初始化是微信私有流程（通过 MMWebViewUI 的生命周期完成），无法独立复现。
WeKit 也只 hook 微信现有实例、从不自建，侧面印证。
JEP：**不要试图自建 X5 WebView。老老实实让微信创建，然后偷取。**

### 2.5 pullToken 的 JSON 双重转义

`callJs` 返回 JS `JSON.stringify({token, cookie})` 的结果。
evaluateJavascript 的 ValueCallback 再把这个字符串 JSON-encode 一层传回来。
修复：先用 `JSONObject(raw)` 解析，失败则 `JSONTokener(raw).nextValue()` 剥一层再解。
JEP：**所有 evaluateJavascript 的返回结果都可能双重转义，用 JSONTokener 做容错解析。**

### 2.6 Activity finish vs moveTaskToBack

finish MMWebViewUI 会暂停 WebView 渲染器 → pullToken 超时（evaluateJavascript 无响应）。
moveTaskToBack 保持 Activity 存活、WebView 正常渲染。
JEP：**偷取后必须用 moveTaskToBack，不能 finish。**

### 2.7 每次 NativeHttp 请求前必须 pullToken

查询每次先 pullToken 再请求，正常工作。诊断漏了 pullToken，直接用 `NativeHttp.cachedToken`（可能是几十分钟前的旧值）→ 401"账号未登录"。
JEP：**所有需要登录态的 NativeHttp GET 请求，必须先 `pullToken(cookie)` 刷新 token。不要假设 cachedToken 有效。**

### 2.8 白名单存储路径跨包权限

用 `createPackageContext("dev.example.autoreply")` 拿模块 filesDir → 代码跑在微信进程（UID 不同）→ 无写权限 → 保存静默失败（runCatching 吞异常）。
正确做法：用微信 `lpparam.appInfo.dataDir` 下的子目录（如 `files/autoreply/`），每个微信实例各自独立。
JEP：**Xposed 模块写文件只能用宿主进程有写权限的路径，不能跨包写。**

### 2.9 白名单语义

- 空列表 = **不回复任何人**（所有消息被 skip）
- 非空列表 = 只回复列表内的会话
- 打开选择器从已保存文件读取初始勾选（`selected = WhitelistStore.list().map{it.id}.toSet()`）
- "清空"按钮必须立即 `setList(emptyList())` 写文件，不能只清 Compose 内存状态
- **无敌模式（紧急关闭）**：用 su 删掉 `autoreply_whitelist.json` 文件 → 空白名单不回复任何人

### 2.10 群聊真实 @ 通知（参照 WeKit MentionMembers）

微信的 @ 提醒靠消息 msgSource 里的 `<atuserlist>` 节点，不是 content 文本里的 "@昵称"。

实现链路：
1. 回复前 `AtMentionHook.pend(talker, senderWxId)` 注册目标
2. Hook `MsgInfoStorage.insert`（锚点日志 `"protect:c2c msg should not here"`）
3. before 拦截 → 读 msgInfo 的 `field_talker`/`field_isSend`/`field_type` 校验
4. 调用 MsgSourceHelper 合并方法（锚点正则 `(?s)<alnode[^>]*>.*?</alnode>`，static 3 args void）
5. 注入 `<atuserlist><![CDATA[wxid]]></atuserlist>` → 微信服务器推送 "@我" 提醒

字段名：`field_talker` / `field_isSend` / `field_type` / `field_msgSvrId`（微信 MsgInfo 内部命名，WeKit 同款）。

关键坑：
- 手动输入的 "@昵称"（普通空格）不会写入 atuserlist，AtParser.isAtMeOrAll 检测不到 → 需要 content 兜底匹配 @自身昵称
- msgInfo 字段可能在父类，需要递归 getDeclaredField
- talker 不匹配时不能直接丢弃 pending（可能是其他会话先触发了入库），要 restore 重试

---

## 三、方案 A 静默后台核心原理

```
rebuildH5:
  Intent(MMWebViewUI).addFlags(NEW_TASK | NEW_DOCUMENT | MULTIPLE_TASK)
  → MMWebViewUI 在独立任务栈打开（用户当前任务栈不受影响）
  
rebuildAndWait:
  findForHost("tywlonestop.ctwing.cn") → steal WebView 到透明 overlay
  → findActivitiesByClassName("MMWebViewUI") → moveTaskToBack(true)
  → 用户看到微信首页，H5 在 overlay 中继续运行

关键约束：
- 必须用 NEW_DOCUMENT + MULTIPLE_TASK 创建独立任务栈
- 必须用 moveTaskToBack 而不是 finish（否则 WebView 渲染器暂停）
- 透明 overlay 用 alpha=0 + not_touchable + not_focusable
```

---

## 四、调试工作流经验

1. **不要反复 force-stop 微信**：多次 force-stop 可能触发微信安全模式。出现问题先检查设备状态。
2. **先读日志再动手**：每次部署后等 20s → 读完整链路日志 → 确认状态再让人测试。
3. **不要猜，要验证**：自建 WebView 做了 4 次尝试才确认不可行，但每次都有具体的错误信息推进方向。
4. **Git 代理问题**：本机 git 配了 `http.proxy=127.0.0.1:7890`，push GitHub 时 TLS 不通。绕过：`git -c http.proxy= -c https.proxy= push`。
5. **adb pm install 只能装设备路径**：必须 `adb push` → `/data/local/tmp/` → `pm install`。
6. **APK 卸载后再装**：改了 manifest（去掉 LAUNCHER）后旧 shortcut 残留，必须 `pm uninstall` 清干净。
7. **分析问题时逐层隔离**：白名单"不生效"其实是 UI 保存按钮逻辑问题，不是过滤逻辑。先删文件验证过滤逻辑本身正确，再排查 UI 写文件。

---

## 五、修改文件清单（本次会话）

| 文件 | 改动 |
|------|------|
| `MainHook.kt` | 白名单逻辑反转（空=不回复）+ WhitelistStore.initWithDataDir |
| `WhitelistStore.kt` | 存储路径改微信 dataDir + 双开兼容 + 诊断日志 |
| `WhitelistScreen.kt` | 打开默认全不勾选 + 清空立即保存 + 保存按钮日志 |
| `CtwingKeywordRouter.kt` | 诊断补 pullToken + 业务错误检测 |
| `AndroidManifest.xml` | 模块更名/去桌面图标/静态scope |
| `arrays.xml`（新增） | 静态作用域声明 |
| `ARCHITECTURE.md` | 方案A描述 + 模块元信息 |
| `开发总结.md` | 踩坑清单扩展（新增 5 条） |
| `MEMORY.md` | 复盘总结（合并两轮对话） |
| `AtMentionHook.kt`（新增） | 真实 @ 通知：DexKit 解析 + 消息入库 hook + atuserlist 注入 |
| `WeDatabaseApi.kt` | 新增 getNickname(wxid) 查 rcontact |

---

## 六、安全修改区域

**可以安全修改**：`CtwingKeywordRouter.kt` 格式化/文案、`MainHook.kt` 欢迎回复、`AndroidManifest.xml` 元信息

**需要谨慎**：`CtwingFacade.kt` pullToken/rebuildH5、`CtwingWebViewHook.kt` WebView 定位、`WebViewPool.kt` overlay 创建、`NativeHttp.kt` HTTP 请求、`AtMentionHook.kt` DexKit 解析与 atuserlist 注入

---

## 七、当前功能全景与运行逻辑（2026-09-28 版）

### 7.1 功能清单

| 功能 | 状态 |
|------|------|
| 查询（ICCID/接入号） | ✅ NativeHttp GET，401 自动重建 OAuth 重试 |
| 诊断（ICCID/接入号） | ✅ NativeHttp GET，401 自动重建 OAuth 重试 |
| 重绑（ICCID/接入号/IMSI） | ✅ WebView XHR + NativeHttp fallback，30s 幂等防重复 |
| 群聊 @ 发起者（真实通知） | ✅ AtMentionHook 注入 atuserlist |
| 重绑回显卡号 | ✅ 结果末尾追加类型标签+号码 |
| 命令容错 | ✅ contains 匹配 + 号码/命令词任意位置 + "解绑"别名 |
| 重绑失败分类 | ✅ 永久业务错误保留幂等（不刷），临时/网络错误允许重试 |
| 白名单 | ✅ 空=不回复，非空=只回复列表内 |
| 后台静默 | ✅ NEW_DOCUMENT+MULTIPLE_TASK + moveTaskToBack |
| WebView 被杀自动恢复 | ✅ pullTokenOrRebuild + fireJs 兜底 + TokenKeepAlive 兜底 |
| 防并发重建 | ✅ rebuildMutex 互斥锁 |
| 防窗口堆积 | ✅ finish 多余旧壳，保留一个 |
| 版本号 | ✅ 编译时 Unix 时间戳自动生成 |
| 模块元信息 | ✅ 天翼物联一站式服务工具 / 无桌面图标 / 静态 scope |

### 7.2 正常运行逻辑

**启动阶段**（微信启动后 ~8 秒）：
1. 模块加载 → 消息捕获引擎 + 白名单 + AtMention 初始化
2. `preInitH5()`：静默打开 CTWing OAuth（独立任务栈）→ 偷取 WebView 到透明 overlay → moveTaskToBack
3. `pullToken()` 拉取 ACCESS_TOKEN 缓存
4. `TokenKeepAlive` 每 25 分钟保活 token（WebView 死亡自动重建）

**消息处理阶段**（收到消息）：
```
消息捕获 → 去重 → 白名单过滤 → 群聊 @ 检测
  → 关键词路由（contains 容错匹配）：
      查询/诊断：pullTokenOrRebuild → NativeHttp GET
                → 401 → forceRebuild(OAuth) → 换新 token → 自动重试
                → 格式化回复
      重绑：幂等检查（30s 窗口）→ pullTokenOrRebuild
            → WebView XHR（fallback NativeHttp）
            → 成功：记录幂等 | 永久错误：保留幂等防刷 | 临时错误：清除允许重试
            → 格式化回复 + 回显卡号
      其他：统一欢迎帮助信息
  → 群聊回复：@昵称 + 真实 @ 通知
```

**异常恢复阶段**（WebView 被杀 / token 过期）：
```
WebView 被杀：pullToken 失败 → ensureReady → rebuildAndWait（rebuildMutex 锁）
  → 静默重建 → finish 旧壳 + moveTaskToBack 新壳 → 重拉 token

Token 过期（401）：NativeHttp 返回 code=401 → forceRebuild 走完整 OAuth
  → pullToken 换新 token → 重试请求（用户无感）
```

### 7.3 双开/多用户支持

- 白名单存 `{微信dataDir}/files/autoreply/`，每个 user 独立（主空间 `/data/user/0/`，双开 `/data/user/999/`）
- Token 存 `{微信dataDir}/dsh_ctwing_bundles/`，同样按 user 隔离
- 需在 LSPosed 中对双开微信单独勾选作用域