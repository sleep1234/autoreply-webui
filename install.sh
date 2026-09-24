#!/usr/bin/env bash
# One-shot: build, install, relaunch WeChat, tail logs.
set -euo pipefail
cd "$(dirname "$0")"

echo "=== Build ==="
./gradlew assembleDebug

echo "=== Install ==="
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "=== Relaunch WeChat ==="
adb shell am force-stop com.tencent.mm
adb shell am start -n com.tencent.mm/.ui.LauncherUI

echo "=== Logcat ==="
adb logcat -s Xposed:AutoReply-I Xposed:AutoReply-E
