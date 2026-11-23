#!/usr/bin/env bash
# VOCA-47 第七轮 · 补跑最后两份：paths.py（§6 路径）与 ac2.py soft（软键路径）。
# 这两份在 drive.log 逐行打 ts= 之前跑过，本串把它们换成带时刻的版本，正文口径统一。
set -u
export QA_HEAD=78343a4 QA_SPEC=0e4eeb0
SER="${QA_SERIAL:-emulator-5554}"   # 第七轮修：证据头部必须写展开后的串口，不写字面量
WS=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3
FIX="$WS/build-78343a4/app/build/outputs/apk/debug/app-debug.apk"
export QA_APK="$FIX"
cd "$(dirname "$0")" || exit 1
run() { n=$1; shift
  { printf '# %s · 被测=78343a4（PR #18 head）· 规格锚=0e4eeb0（PR #17 head）\n# cmd: %s\n# ts=%s  serial=%s  device=%s\n' "$n" "$*" "$(date +%Y-%m-%dT%H:%M:%S%z)" "$SER" "$(${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb} -s "$SER" shell getprop ro.product.model | tr -d '\r')"; } > "/tmp/r7d_$n.txt"
  "$@" >> "/tmp/r7d_$n.txt" 2>&1; printf '# rc=%s\n' "$?" >> "/tmp/r7d_$n.txt"; echo "=== $n rc=$? ==="; }
run paths python3 paths.py "$FIX"
run ac2_soft python3 ac2.py "$FIX" 440 soft
echo R7D_ALLDONE
