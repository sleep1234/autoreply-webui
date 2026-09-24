#!/usr/bin/env python3
"""
Consolidated WeChat version-adapter checker.

Scans a WeChat APK's classes*.dex raw bytes for EVERY DexKit matcher
feature string used by WeChatHook.kt. If all print [OK], the matchers
will hit on that WeChat version — no code change needed.

Usage:
    adb pull <apk> wechat.apk
    python scan_all.py wechat.apk
"""
import zipfile, sys

# Every matcher string used in WeChatHook.kt, grouped by feature.
MATCHERS = {
    # --- sendText (NetSceneSendMsg path) ---
    "classNetSceneSendMsg": [b"MicroMsg.NetSceneSendMsg", b"markMsgFailed for id:%d"],
    "classNetSceneQueue":   [b"worker thread has not been se", b"MicroMsg.NetSceneQueue"],
    "classNetSceneBase":    [b"scene security verification not passed, type="],
    "classNetSceneObserverOwner": [b"MicroMsg.Mvvm.NetSceneObserverOwner"],

    # --- getCurrentTalker (ChattingContext) ---
    "classChattingContext": [b"MicroMsg.ChattingContext", b"[notifyDataSetChange]"],
    "methodChattingContextGetTalker": [b"getTalker returns null."],

    # --- sendImage (image service) ---
    "classMvvmBase":        [b"MicroMsg.Mvvm.MvvmPlugin", b"onAccountInitialized start"],
    "classImageTask":       [b"msg_raw_img_send"],
    "classImageServiceImpl":[b"MicroMsg.ImgUpload.MsgImgFeatureService"],
}

def scan(apk):
    with zipfile.ZipFile(apk) as z:
        data = b"".join(z.read(n) for n in sorted(z.namelist()) if n.endswith(".dex"))
    allok = True
    for feat, needles in MATCHERS.items():
        hits = sum(1 for n in needles if n in data)
        ok = hits == len(needles)
        if not ok: allok = False
        print(f"[{'OK ' if ok else 'MISS'}] {feat}: {hits}/{len(needles)}")
        for n in needles:
            if n not in data:
                print(f"        MISSING: {n!r}")
    print("\n" + ("ALL MATCHERS VALID — no code change needed." if allok
                  else "SOME MATCHERS MISSING — update WeChatHook.kt matchers."))
    return allok

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(0 if scan(sys.argv[1]) else 1)