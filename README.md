# Minimal Text Auto-Reply for WeChat

A distilled, self-contained Xposed module that implements **text-only auto-reply**
for WeChat (`com.tencent.mm`), derived from the architecture of
[WeKit](https://github.com/Ujhhgtg/WeKit)'s WeAgent subsystem (GPL-3.0).

This is **not** a copy-paste of WeKit — it is a clean-room reimplementation of the
same three-stage pipeline (capture → LLM → send) that is small enough to read in one
sitting, with an explicit extension point for image handling later.

---

## Architecture

```
┌───────────────────────────────────────────────────────────────┐
│ MainHook (Xposed entry, package com.tencent.mm)              │
│   └─ WeChatHook : IWeChatHook   — capture + send             │
│        │  (hook WCDB insertWithOnConflict → message table)   │
│        ▼                                                      │
│   BufferedMessageTrigger — filter + debounce + batch         │
│        │  (MessageTrigger: regex filter, cooldown, anti-loop)│
│        ▼                                                      │
│   AutoReplyEngine.onFlush                                     │
│        │  ├─ mediaHandler?.handleImage()  → image path       │
│        │  └─ llm.reply(content)            → reply text       │
│        ▼                                                      │
│   IWeChatHook.sendText(talker, reply)                        │
│        │  (NetSceneSendMsg → NetSceneQueue → WeChat network) │
│        ▼                                                      │
│   WeChat sends the reply                                     │
└───────────────────────────────────────────────────────────────┘
```

## File Map

```
minimal_text_autoreply/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml          — xposedmodule metadata
│       ├── assets/xposed_init           — legacy entry declaration
│       └── java/dev/example/autoreply/
│           ├── hook/
│           │   ├── IWeChatHook.kt       — capture+send abstraction
│           │   ├── WeChatHook.kt        — DexKit+reflection impl (text only)
│           │   └── MainHook.kt          — Xposed entry, config, wiring
│           ├── trigger/
│           │   ├── MessageTrigger.kt    — filter + cooldown config
│           │   └── BufferedMessageTrigger.kt — debounce/batch buffer
│           ├── llm/
│           │   └── OpenAiClient.kt      — OpenAI chat completions
│           ├── engine/
│           │   └── AutoReplyEngine.kt   — wires it all together
│           └── media/
│               └── MediaHandler.kt      — image-reply extension point
```

## What Is Implemented

| Layer | Status |
|-------|--------|
| Message capture (WCDB `insertWithOnConflict` hook) | ✅ code present |
| Text filter (regex on content/talker, type==1) | ✅ code present |
| Anti-loop (`filterOwnEvents` drops `isSend==1`) | ✅ code present |
| Debounce/batch/max-wait buffering | ✅ code present |
| Cooldown (rate-limit between replies) | ✅ code present |
| LLM reply (OpenAI-compatible, configurable base URL) | ✅ code present |
| Send reply via `NetSceneSendMsg` | ✅ code present (stub resolver) |
| Image reply | ⬜ interface + stub only |

## What You Still Must Do (Honest Gaps)

This is **scaffolding with the full logic in place, not a ready-to-install APK.**
Three things are deliberately left for you because they are WeChat-version-specific:

### 1. DexKit bridge wiring (WeChatHook.resolveSendPath)

The `WeChatHook` documents the exact DexKit matchers (copied from WeKit's
`WeMessageApi`) but does not inline the `DexKitBridge` construction. You must:

1. Obtain the WeChat APK path (`lpparam.appInfo.sourceDir`).
2. Build a `DexKitBridge` over it (see WeKit's `DexCacheManager.kt`).
3. Run `findClass` / `findMethod` with the documented matchers to resolve:
   - `classNetSceneSendMsg`, `classNetSceneQueue`, `classNetSceneBase`
   - `methodPostToQueue`, `ctorNetSceneSendMsg` (5-arg and 6-arg)

### 2. WeChat version

The DexKit matchers target **8.0.65–8.0.78**. For any other version, decompile
that APK and re-derive the matcher strings (class/method markers).

### 3. Image reply

`WeChatHook.sendImage` is a stub. When you need it, implement the DexKit targets
documented in that method's comment (from WeKit's `WeMessageApi.sendImage`):
`classImageServiceImpl`, `classImageTask`, the 5-param task constructor, and the
`flow`-returning send method.

---

## Build

```bash
# Requires JDK 17+, Android SDK (compileSdk 35)
./gradlew assembleDebug
# Install the APK, enable it in LSPosed/LSPatch for com.tencent.mm
```

## Configure

Edit `MainHook.kt`:
- `apiKey` — your OpenAI (or compatible) key
- `baseUrl` — endpoint (works with any OpenAI-compatible API: DeepSeek, Moonshot, etc.)
- `model` — model name
- `contentRegex` — set to e.g. `^@机器人` to only reply when @mentioned

## Legal

Derived from WeKit (GPL-3.0) — this project must remain GPL-3.0.
WeChat's terms prohibit automated messaging; use responsibly and only
where permitted.