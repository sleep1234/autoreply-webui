#!/usr/bin/env python3
"""
Auto-reply ADB install script.
Usage: python install.py
"""

import subprocess, sys, os

APK = "app/build/outputs/apk/debug/app-debug.apk"
PKG = "com.tencent.mm"

def run(cmd, **kw):
    print(f"  $ {' '.join(cmd)}")
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.stdout: print(r.stdout.strip())
    if r.stderr and r.returncode != 0: print(r.stderr.strip(), file=sys.stderr)
    return r

def main():
    print("=== 1. Build APK ===")
    if sys.platform == "win32":
        r = subprocess.run(["gradlew.bat", "assembleDebug"], cwd=os.path.dirname(__file__))
    else:
        r = subprocess.run(["./gradlew", "assembleDebug"], cwd=os.path.dirname(__file__))
    if r.returncode != 0:
        print("BUILD FAILED", file=sys.stderr)
        sys.exit(1)

    if not os.path.exists(APK):
        print(f"APK not found: {APK}", file=sys.stderr)
        sys.exit(1)

    print("\n=== 2. Install APK ===")
    run(["adb", "install", "-r", APK])

    print(f"\n=== 3. Kick {PKG} to reload hooks ===")
    run(["adb", "shell", "am", "force-stop", PKG])
    run(["adb", "shell", "am", "start", "-n", f"{PKG}/.ui.LauncherUI"])

    print(f"\n=== 4. Logcat (Ctrl+C to stop) ===")
    run(["adb", "logcat", "-s", "Xposed:AutoReply-I", "Xposed:AutoReply-E"])

if __name__ == "__main__":
    main()