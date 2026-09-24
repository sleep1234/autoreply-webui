#!/usr/bin/env python3
"""Quick scan for ChattingContext matcher strings in WeChat APK."""
import zipfile, sys

STRINGS = {
    "classChattingContext": [
        b"MicroMsg.ChattingContext",
        b"[notifyDataSetChange]",
    ],
    "methodChattingContextGetTalker": [
        b"getTalker returns null.",
    ],
}

def scan(apk):
    with zipfile.ZipFile(apk) as z:
        data = b"".join(z.read(n) for n in sorted(z.namelist()) if n.endswith(".dex"))
    for feat, needles in STRINGS.items():
        hits = sum(1 for n in needles if n in data)
        ok = hits == len(needles)
        print(f"[{'OK' if ok else 'MISS'}] {feat}: {hits}/{len(needles)}")
        if not ok:
            for n in needles:
                print(f"  {'ok' if n in data else 'MISS'} {n!r}")

if __name__ == "__main__":
    scan(sys.argv[1] if len(sys.argv) > 1 else "wechat_8076/base.apk")