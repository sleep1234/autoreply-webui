# CTWing 微信自动回复 — 架构记忆 (v7)

> 2026-09-27 | 微信 8.0.76 | 小米14 Pro KernelSU

## 当前状态：全部跑通 ✅

| 操作 | 方案 | 状态 |
|------|------|------|
| 查询 | NativeHttp GET | ✅ |
| 诊断 | NativeHttp GET | ✅ |
| 重绑 | WebView XHR POST | ✅ |
| 启动静默 | NEW_DOCUMENT+MULTIPLE_TASK + moveTaskToBack | ✅ |
| 锁屏可用 | WebViewPool overlay + KeepAlive | ✅ |

## 方案 A（静默后台）核心机制

MMWebViewUI 在独立任务栈打开（NEW_DOCUMENT+MULTIPLE_TASK）→
偷取 WebView 到透明 overlay → moveTaskToBack 推回空壳 →
用户全程只看到微信首页。

## 方向 B 验证失败

自建 pinus.sdk.WebView 裸 new + addView → NPE（reflectInterface 为 null），
Pinus 内核初始化不可脱离微信 MMWebViewUI。详见 SELF_CREATE_WEBVIEW_RESEARCH.md。

## 环境

微信 8.0.76 | Xiaomi 14 Pro HyperOS | KernelSU
WiFi ADB 192.168.31.61:35193
GitHub: sleep1234/minimal_text_autoreply