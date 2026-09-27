# CTWing 物联网卡自动回复模块 — 记忆文件 (v6)

> 更新：2026-09-27

## 当前状态：三个操作全部跑通 ✅

| 操作 | 方案 | 结果 |
|------|------|------|
| 查询 | NativeHttp GET | ✅ |
| 诊断 | NativeHttp GET | ✅ |
| 重绑 | WebView XHR POST | ✅ |

## 架构

```
pullToken() ← 依赖 H5（evaluateJavascript 读 cookie）
  ├→ NativeHttp.cachedToken
  └→ NativeHttp.cachedCookie

查询/诊断 → NativeHttp.queryCard/diagnose → JSON → 回复
重绑    → fireJs(operationCommit) → pollDshResult → 回复
```

- NativeHttp：纯 Java HttpURLConnection，不依赖 WebView JS 执行
- 重绑 POST 走 WebView XHR（CSRF 校验需浏览器上下文）
- 无 H5 重建、无同步 XHR、webViewMutex 串行化

## 环境

微信 8.0.76 | 小米14Pro HyperOS | KernelSU
WiFi ADB 192.168.31.61:35193