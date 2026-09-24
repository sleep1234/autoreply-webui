#!/usr/bin/env python3
"""Robust scan: search raw bytes in each dex for feature strings (UTF-8)."""
import zipfile, sys, os

FEATURE_STRINGS = {
    "classNetSceneSendMsg": [
        b"MicroMsg.NetSceneSendMsg",
        b"markMsgFailed for id",
    ],
    "classNetSceneQueue": [
        b"worker thread has not been se",
        b"MicroMsg.NetSceneQueue",
    ],
    "classNetSceneBase": [
        b"scene security verification not passed",
    ],
    "classNetSceneObserverOwner": [
        b"MicroMsg.Mvvm.NetSceneObserverOwner",
    ],
}

def scan(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        dexes = sorted(n for n in z.namelist() if n.endswith(".dex"))
        print(f"{len(dexes)} dex files")
        all_data = b""
        for n in dexes:
            all_data += z.read(n)
    print(f"Total dex size: {len(all_data)//1024//1024} MB")
    all_ok = True
    for feat, needles in FEATURE_STRINGS.items():
        hits = sum(1 for n in needles if n in all_data)
        ok = hits == len(needles)
        if not ok:
            all_ok = False
        print(f"[{'OK' if ok else 'MISS'}] {feat}: {hits}/{len(needles)}")
        for n in needles:
            if n in all_data:
                idx = all_data.index(n)
                ctx = all_data[max(0,idx-20):idx+len(n)+20]
                print(f"    ok {n!r}  ctx={ctx!r}")
            else:
                print(f"    MISS {n!r}")
    print("\n" + ("ALL OK" if all_ok else "SOME MISSING"))
    return all_ok

if __name__ == "__main__":
    apk = sys.argv[1] if len(sys.argv) > 1 else "wechat_8076/base.apk"
    ok = scan(apk)
    sys.exit(0 if ok else 1)