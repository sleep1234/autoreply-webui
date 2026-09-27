# 自建 X5 WebView 可行性研究 — 最终结论

> 2026-09-27 | 微信 8.0.76 | Xiaomi 14 Pro

## 目标

验证能否在 LSPosed 模块中**自建一个 `com.tencent.xweb.WebView`**（不依赖微信的 MMWebViewUI），挂到透明 overlay，实现 CTWing H5 的全程后台静默加载。

期望效果：模块自己 `new` 一个 WebView → loadUrl(CTWing) → OAuth 登录 → JS 注入 → 查询/诊断/重绑全部在此 WebView 上完成，全程不抢前台、不影响微信使用。

## 验证过程

### 尝试 1: 裸 new `com.tencent.xweb.WebView(ctx, null)`

```
❌ InvocationTargetException
   cause: "create webview instance failed.
          ExpectedKind:WV_KIND_NONE, PreferredKind:WV_KIND_PINUS,
          ModuleKindBeforeInit:WV_KIND_PINUS"
```

根因：`com.tencent.xweb.WebView` 的构造器检测到自己没走 Pinus 内核工厂初始化路径，主动抛异常拒绝。`WV_KIND_PINUS` 是 X5 内核代号。

### 尝试 2: 用 Pinus SDK 类 `com.tencent.xweb.pinus.sdk.WebView`

**构造成功** ✅：
```
WebViewPool: ✅ com.tencent.xweb.pinus.sdk.WebView created (1-arg ctor: Context)
```
- 单参 `(Context)` 和双参 `(Context, AttributeSet)` 构造器均能成功 new 出实例
- `loadUrl` 可以调用，XWeb.Core 日志显示页面加载走通
- OAuth 登录、JS payload 注入、pullToken 提取 token 全部正常

**但有一个致命条件**：Pinus 内核必须先被微信预热。

### 尝试 3: 冷战启动（删除 preInitH5 预热步骤）

只保留自建 WebView，不做任何预热：

```
✅ pinus.sdk.WebView created (构造成功)
❌ 页面加载后 NPE 崩溃:
   java.lang.NullPointerException:
   Attempt to invoke interface method
   'com.tencent.xweb.pinus.sdk.WebViewInterface.onCheckIsTextEditor()'
   on a null object reference
   at com.tencent.xweb.pinus.sdk.WebView.onCheckIsTextEditor
   → 微信闪退
```

根因：`pinus.sdk.WebView` 是一个**包装器**，内部有一个 `WebViewInterface` 委托对象。这个委托对象不会被裸构造初始化——它由 Pinus 内核在渲染器启动时赋值。没有内核预热，委托就是 null，WebView 一旦参与 View 树布局（`sizeChange → onCheckIsTextEditor`）就 NPE。

## 最终结论

| 类名 | 裸 new | 需要内核预热 | 可行？ |
|------|--------|-------------|--------|
| `com.tencent.xweb.WebView` | ❌ 拒绝 | - | ❌ |
| `com.tencent.xweb.pinus.sdk.WebView` | ✅ 构造成功 | ✅ 必须 | ❌（冷启动不可行） |
| `com.tencent.xweb.pinus.PSWebview` | 未验证 | 未知 | ❓ |

**核心结论：在微信 X5/Pinus 生态中，无法绕过内核预热自建一个完整可用的 WebView。** 内核初始化由微信的 MMWebViewUI 或 XWeb 初始化流程在私有 API 中完成，裸 `new` 出来的 WebView 委托接口为 null，参与 View 树即崩溃。

WeKit 参考代码也印证了这一点——WeKit 的 `WeWebViewApi` 只追踪微信自己创建的 WebView（通过 hook `onPageFinished`），从不自建。

## 对比：WeKit 的做法 vs 我们的尝试

| | WeKit | 我们现在的偷取方案 | 自建 WebView（本次尝试） |
|---|---|---|---|
| WebView 来源 | hook 微信现有实例 | 打开 MMWebViewUI → steal | `new pinus.sdk.WebView` |
| 依赖微信创建 Activity | ✅ | ✅ | ❌（冷启动失败） |
| 抢前台 | 不抢（被动追踪） | ✅ 启动时一次 | ❌ 不需要（但内核冷的） |
| 内核预热 | 微信自然创建 | 微信自然创建 | ❌ 无 |

## 后续方向

### 方向 A：优化偷取方案（低风险）

保留 `preInitH5` 让微信自然预热内核 + 创建 WebView，但改进"抢前台"问题：
- 利用已有的 `silentMoveBack` 机制，让 `MMWebViewUI.onResume` 时立刻 `moveTaskToBack`
- 用户看到微信首页，H5 在后台完成 OAuth → 被偷取

### 方向 B：逆向内核预热入口（高风险、本次继续探索）

逆向微信的 XWeb/Pinus 内核初始化流程：
- 搜索 `XWebSdk`、`WebViewFactory`、`Pinus` 相关初始化类
- 找到能独立预热内核的静态方法
- 如果成功：模块可以完全自建 WebView，彻底不依赖微信创建 MMWebViewUI
- 如果失败：回到方向 A