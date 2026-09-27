# 天翼物联一站式服务工具 — 开发复盘与经验总结

> 2026-09-27 | 本次会话全程复盘

---

## 一、本次会话解决的问题

1. **诊断/重绑一直返回空或超时** → 根因是同步 XHR 在 X5 内核被禁用（status:0），改为 NativeHttp GET + WebView XHR POST 分流
2. **并发查询/重绑数据串扰** → `CtwingJsBridge.lastApiResponse` 全局单例 → `webViewMutex` 串行化
3. **H5 抢前台影响微信使用** → 方案A：NEW_DOCUMENT+MULTIPLE_TASK 独立任务栈 + moveTaskToBack
4. **尝试自建 WebView 绕开微信** → 方向B验证失败，pinus.sdk.WebView 冷启动 NPE，结论写入 SELF_CREATE_WEBVIEW_RESEARCH.md
5. **非台州卡查询返回 raw JSON** → extractQueryError 友好提示
6. **非命令消息回复太随意** → 统一欢迎帮助信息

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
所以拿到的是 `"\"{\\"token\\":\\"...}\""` 这种双重转义格式。
修复：先用 `JSONObject(raw)` 解析，失败则 `JSONTokener(raw).nextValue()` 剥一层再解。
JEP：**所有 evaluateJavascript 的返回结果都可能双重转义，用 JSONTokener 做容错解析。**

### 2.6 Activity finish vs moveTaskToBack

finish MMWebViewUI 会暂停 WebView 渲染器 → pullToken 超时（evaluateJavascript 无响应）。
moveTaskToBack 保持 Activity 存活、WebView 正常渲染。
JEP：**偷取后必须用 moveTaskToBack，不能 finish。WebView 的生命周期依赖宿主 Activity。**

---

## 三、方案 A 静默后台的核心原理

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

1. **不要反复 force-stop 微信**：多次 force-stop 可能触发微信安全模式或双用户进程异常。出现问题先检查设备状态而不是盲目重启。
2. **先读日志再动手**：每次部署后等 20s → 读完整链路日志 → 确认状态再让人测试。
3. **不要猜，要验证**：自建 WebView 的方案做了 4 次尝试才确认不可行，但每次都有具体的错误信息推进方向。
4. **Git 代理问题**：本机 git 配了 `http.proxy=127.0.0.1:7890`，push GitHub 时 TLS 不通。绕过方式：`git -c http.proxy= -c https.proxy= push`。
5. **adb pm install 只能装设备路径**：不能 `pm install <PC路径>`，必须 `adb push` → `/data/local/tmp/` → `pm install`。
6. **APK 卸载后再装**：改了 manifest（去掉 LAUNCHER）后旧 shortcut 残留，必须 `pm uninstall` 清干净。

---

## 五、后续迭代的安全区

以下区域可以安全修改，不影响核心链路：
- `CtwingKeywordRouter.kt`：格式化输出（formatCardInfo/formatDiagnosis）、extractQueryError 错误文案
- `MainHook.kt`：欢迎回复文案、白名单逻辑
- `AndroidManifest.xml`：模块名、描述
- 可以加新的关键词命令（如"刷新token"），只需在 CtwingKeywordRouter 里加一条路由

以下区域修改需谨慎，建议先打备份：
- `CtwingFacade.kt`：pullToken、rebuildH5、操作互斥锁
- `CtwingWebViewHook.kt`：WebView 定位、加载拦截、keep-alive
- `WebViewPool.kt`：overlay 创建、WebView 偷取
- `NativeHttp.kt`：HTTP 请求/缓存逻辑