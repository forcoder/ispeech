#!/bin/bash
# VOCA-47 第七轮 · 打回项 1/3 的设备侧复跑编排（被测面固定 78343a4）。
# 每档单独一个日志文件 + 退出码，跑挂一档不影响后面的档；所有读数都出自当场窗口，
# 报告里引的数字必须能在对应文件里 grep 到。
# 用法：bash run_r7_all.sh [suite ...]   （不传参数就按下面的顺序全跑）
cd "$(dirname "$0")" || exit 1
WS=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3
FIX="$WS/build-78343a4/app/build/outputs/apk/debug/app-debug.apk"
BASE="$WS/base-fa57a07/app/build/outputs/apk/debug/app-debug.apk"
OUT=${R7_OUT:-/tmp}
head7() {
  printf '# %s · 被测=78343a4（PR #18 head）· 规格锚=0e4eeb0（PR #17 head）\n' "$1"
  printf '# cmd: %s\n' "$2"
  printf '# ts=%s  serial=${QA_SERIAL:-emulator-5554}\n' "$(date +%Y-%m-%dT%H:%M:%S%z)"
}
run() {
  name=$1; shift
  out="$OUT/r7_${name}.txt"
  head7 "$name" "$*" > "$out"
  "$@" >> "$out" 2>&1
  printf '# rc=%s\n' "$?" >> "$out"
}

declare -A SUITE
SUITE[d1_fixed]='python3 d1regress.py $FIX fixed'
SUITE[fs115]='python3 fs115.py $FIX 440,520 1.0,1.15 shijian,dashu A7'
SUITE[ac47]='python3 ac47.py $FIX 440'
SUITE[ac9]='python3 ac9.py 60 200,1000,1500,2000'
SUITE[paths]='python3 paths.py $FIX'
SUITE[paths2]='python3 paths2.py $FIX'
SUITE[paths3]='python3 paths3.py $FIX'
SUITE[vswipe]='python3 vswipe.py $FIX'
SUITE[d2_base]="python3 d2check.py $BASE"
SUITE[d1_base]='python3 d1regress.py $BASE base off_parity'

LIST=${@:-d1_fixed fs115 ac47 ac9 paths paths2 paths3 vswipe d2_base d1_base}
for s in $LIST; do
  cmd=${SUITE[$s]}
  echo "=== $s : $cmd ===" >&2
  eval "run $s ${cmd}"
done
