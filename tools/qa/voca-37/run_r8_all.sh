#!/usr/bin/env bash
# VOCA-47 第八轮 · 打回项 3「逐行 ts= 与 IDENT 补齐」的设备侧复跑串。
# 只重跑第七轮被判「数据行无时刻 / 整份无 IDENT」的四份 ＋ §4 的 8 组矩阵（同一批读数的另一个切面）：
#   ev_r7_ac10_1080.txt(95/95 无 ts) · ev_r7_ac10_target_geom.txt(49/49 无 ts)
#   ev_r7_g1_g3.txt(7/7 无 ts + IDENT=0) · ev_r7_pp_window.txt(17/29 无 ts，全是 RAW 行)
# 被测面恒定 78343a4（PR #18 head）；规格读数口径仍按 0e4eeb0 取，PR #17 head 已前进到 ef14209
# （第八轮打回项 2 要求报告头点名，两份的差异逐条对账在报告 §0/§5）。
# 截图一律换新 label（A8/T8/r8_*），不覆盖第七轮已入库的帧。
set -u
export QA_HEAD=78343a4 QA_SPEC=0e4eeb0
SER="${QA_SERIAL:-emulator-5554}"
ADB_BIN="${QA_ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
WS=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3
FIX="${QA_APK:-$WS/build-78343a4/app/build/outputs/apk/debug/app-debug.apk}"
export QA_SRC="${QA_SRC:-$WS/build-78343a4}"   # 代码面 grep 跑在被测构建的检出上
OUT=${R8_OUT:-/tmp}
export QA_APK="$FIX" QA_SERIAL="$SER"
cd "$(dirname "$0")" || exit 1

hdr() { printf '# %s · 被测=78343a4（PR #18 head）· 规格读数口径=0e4eeb0（PR #17 head 第八轮现算已前进到 ef14209，差异对账见报告 §0/§5）\n# cmd: %s\n# ts=%s  serial=%s  device=%s\n' \
  "$1" "$2" "$(date +%Y-%m-%dT%H:%M:%S%z)" "$SER" "$("$ADB_BIN" -s "$SER" shell getprop ro.product.model | tr -d '\r')"; }

run() { name=$1; shift
  out="$OUT/r8_${name}.txt"
  hdr "$name" "$*" > "$out"
  "$@" >> "$out" 2>&1
  printf '# rc=%s  ts=%s\n' "$?" "$(date +%Y-%m-%dT%H:%M:%S%z)" >> "$out"
  echo "=== $name rc done: $out ==="
}

# 1) G1/G3 资源表面（新增 g1g3.py：逐行 ts + IDENT 绑包）
run g1_g3 python3 g1g3.py "$FIX"
# 2) pp 同窗对照（模拟器原生面板 1080x2340，三档两侧）—— 打回项 1 的「搜狗随面板涨」第一腿
hdr pp_window_sogou "python3 ppwin.py --ime sogou --panel 1080x2340 --tiers 440:1.0,440:1.15,520:1.15 --round r8" > "$OUT/r8_pp_window.txt"
python3 ppwin.py --ime sogou --panel 1080x2340 --tiers 440:1.0,440:1.15,520:1.15 --round r8 >> "$OUT/r8_pp_window.txt" 2>&1
R1=$?
printf '# sogou_rc=%s  ts=%s\n' "$R1" "$(date +%Y-%m-%dT%H:%M:%S%z)" >> "$OUT/r8_pp_window.txt"
printf '# cmd: python3 ppwin.py --ime ispeech --panel 1080x2340 --tiers 440:1.0,440:1.15,520:1.15 --round r8 --apk %s\n' "$FIX" >> "$OUT/r8_pp_window.txt"
python3 ppwin.py --ime ispeech --panel 1080x2340 --tiers 440:1.0,440:1.15,520:1.15 --round r8 --apk "$FIX" >> "$OUT/r8_pp_window.txt" 2>&1
R2=$?
printf '# ispeech_rc=%s\n# rc=%s  ts=%s\n' "$R2" "$((R1 + R2))" "$(date +%Y-%m-%dT%H:%M:%S%z)" >> "$OUT/r8_pp_window.txt"
echo "=== pp_window rc=$R1,$R2 ==="
# 3) AC-10 模拟器原生面板
run ac10_1080 python3 fs115.py "$FIX" 440,520 1.15 shijian,dashu A8 1080x2340
# 4) AC-10 目标真机几何
run ac10_target_geom python3 fs115.py "$FIX" 520 1.15 shijian,dashu T8 1260x2720
# 5) §4 截字主证据：fs115 完整 8 组矩阵（440/520 × fs1.0/1.15 × shijian/dashu，模拟器原生面板）
#    入 evidence 时改名 ev_r8_fs115.txt；截图 label=R8FS（不覆盖第七轮帧）
run fs115_matrix python3 fs115.py "$FIX" 440,520 1.0,1.15 shijian,dashu R8FS 1080x2340
echo R8_ALLDONE
