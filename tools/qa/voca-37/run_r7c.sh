#!/usr/bin/env bash
# VOCA-47 第七轮 · 打回项 1 的「逐行时间戳」补跑。
# drive.log 之前只在文件头给一个 ts=，读数组本身没有时刻，读者只能追到入库提交；
# 这一轮把 drive.py 的 log() 改成每行带 ts=，所以受影响的套件（AC-1/截字、AC-4~7、AC-7 竖向、
# 路径、AC-9、D2）必须在同一被测面上重跑一遍，读数才有时刻可查。
# 被测面恒定：78343a4（PR #18 head）/ 规格 0e4eeb0（PR #17 head）。
set -u
export QA_HEAD=78343a4 QA_SPEC=0e4eeb0
SER="${QA_SERIAL:-emulator-5554}"   # 第七轮修：证据头部必须写展开后的串口，不写字面量
WS=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3
FIX="$WS/build-78343a4/app/build/outputs/apk/debug/app-debug.apk"
export QA_APK="$FIX"
cd "$(dirname "$0")" || exit 1
run() {
  n=$1; shift
  {
    printf '# %s · 被测=78343a4（PR #18 head）· 规格锚=0e4eeb0（PR #17 head）\n' "$n"
    printf '# cmd: %s\n' "$*"
    printf '# ts=%s  serial=%s  device=%s\n' "$(date +%Y-%m-%dT%H:%M:%S%z)" "$SER" \
      "$("${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}" -s "$SER" shell getprop ro.product.model | tr -d '\r')"
  } > "/tmp/r7c_$n.txt"
  "$@" >> "/tmp/r7c_$n.txt" 2>&1
  printf '# rc=%s\n' "$?" >> "/tmp/r7c_$n.txt"
  echo "=== $n rc=$? ==="
}
run ac47     python3 ac47.py "$FIX" 440
run vswipe   python3 vswipe.py "$FIX"
run paths3   python3 paths3.py "$FIX"
run paths2   python3 paths2.py "$FIX"
run ac9      python3 ac9.py 60 200,1000,1500,2000
run d2_fixed python3 d2check.py "$FIX"
run fs115    python3 fs115.py "$FIX" 440,520 1.0,1.15 shijian,dashu A7
echo R7C_ALLDONE
