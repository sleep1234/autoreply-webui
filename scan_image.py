#!/usr/bin/env python3
"""Scan for image-send matcher strings in WeChat APK."""
import zipfile, sys

STRINGS = {
    "classMvvmBase": [b"MicroMsg.Mvvm.MvvmPlugin", b"onAccountInitialized start"],
    "classImageTask": [b"msg_raw_img_send"],
    "classImageServiceImpl": [b"MicroMsg.ImgUpload.MsgImgFeatureService"],
}

def scan(apk):
    with zipfile.ZipFile(apk) as z:
        data = b"".join(z.read(n) for n in sorted(z.namelist()) if n.endswith(".dex"))
    allok = True
    for feat, needles in STRINGS.items():
        hits = sum(1 for n in needles if n in data)
        ok = hits == len(needles)
        if not ok: allok = False
        print(f"[{'OK' if ok else 'MISS'}] {feat}: {hits}/{len(needles)}")
        for n in needles:
            print(f"  {'ok' if n in data else 'MISS'} {n!r}")
    print("\n" + ("ALL OK" if allok else "SOME MISSING"))
    return allok

if __name__ == "__main__":
    ok = scan(sys.argv[1] if len(sys.argv) > 1 else "wechat_8076/base.apk")
    sys.exit(0 if ok else 1)