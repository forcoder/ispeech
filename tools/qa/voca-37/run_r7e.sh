#!/usr/bin/env bash
# VOCA-47 第七轮 · 打回项 1 的 ts 补漏串。
# ev_r7_ac2_key.txt（00:44 跑，drive.log 打点之前）、ev_r7_pp_target_geom.txt（ppwin 自己的 RAW 行没时刻）、
# ev_r7_d1_regress.txt（部分行无 ts）三份都在「读数必须能追到当场时刻」这条上不合格，本串重跑这三份。
# 被测面恒定：78343a4（PR #18 head）/ 规格 0e4eeb0（PR #17 head）。
set -u
export QA_HEAD=78343a4 QA_SPEC=0e4eeb0
SER="${QA_SERIAL:-emulator-5554}"
ADB="${QA_ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
WS=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3
FIX="$WS/build-78343a4/app/build/outputs/apk/debug/app-debug.apk"
BASE="$WS/base-fa57a07/app/build/outputs/apk/debug/app-debug.apk"
export QA_APK="$FIX" QA_BASE_APK="$BASE"
cd "$(dirname "$0")" || exit 1

hdr() { printf '# %s · 被测=78343a4（PR #18 head）· 规格锚=0e4eeb0（PR #17 head）\n# cmd: %s\n# ts=%s  serial=%s  device=%s\n' \
  "$1" "$2" "$(date +%Y-%m-%dT%H:%M:%S%z)" "$SER" "$("$ADB" -s "$SER" shell getprop ro.product.model | tr -d '\r')"; }

# 1) AC-2 键路径（D1 复验主证据）
hdr ac2_key "python3 ac2.py $FIX 440 keyevent" > /tmp/r7e_ac2_key.txt
python3 ac2.py "$FIX" 440 keyevent >> /tmp/r7e_ac2_key.txt 2>&1
printf '# rc=%s\n' "$?" >> /tmp/r7e_ac2_key.txt
echo "=== ac2_key rc=$? ==="

# 2) 目标几何 pp（两侧同窗：先搜狗后 iSpeech）
hdr pp_target_geom "python3 ppwin.py --panel 1260x2720 --tiers 520:1.0,520:1.15 --round r7e" > /tmp/r7e_pp_target_geom.txt
python3 ppwin.py --ime sogou   --panel 1260x2720 --tiers 520:1.0,520:1.15 --round r7e >> /tmp/r7e_pp_target_geom.txt 2>&1
R1=$?
printf '# sogou_rc=%s\n' "$R1" >> /tmp/r7e_pp_target_geom.txt
python3 ppwin.py --ime ispeech --panel 1260x2720 --tiers 520:1.0,520:1.15 --round r7e --apk "$FIX" >> /tmp/r7e_pp_target_geom.txt 2>&1
R2=$?
printf '# ispeech_rc=%s\n# rc=%s\n' "$R2" "$((R1 + R2))" >> /tmp/r7e_pp_target_geom.txt
echo "=== pp rc=$R1,$R2 ==="

# 3) 接管空格键的 6 档：base 一遍 + fixed 一遍 + --diff 比
hdr d1_base "python3 d1regress.py $BASE base" > /tmp/r7e_d1_base.txt
python3 d1regress.py "$BASE" base >> /tmp/r7e_d1_base.txt 2>&1
printf '# rc=%s\n' "$?" >> /tmp/r7e_d1_base.txt
echo "=== d1_base rc=$? ==="
hdr d1_fixed "python3 d1regress.py $FIX fixed" > /tmp/r7e_d1_fixed.txt
python3 d1regress.py "$FIX" fixed >> /tmp/r7e_d1_fixed.txt 2>&1
printf '# rc=%s\n' "$?" >> /tmp/r7e_d1_fixed.txt
echo "=== d1_fixed rc=$? ==="
hdr d1_regress "python3 d1regress.py --diff /tmp/r7e_d1_base.txt /tmp/r7e_d1_fixed.txt" > /tmp/r7e_d1_regress.txt
python3 d1regress.py --diff /tmp/r7e_d1_base.txt /tmp/r7e_d1_fixed.txt >> /tmp/r7e_d1_regress.txt 2>&1
printf '# rc=%s\n' "$?" >> /tmp/r7e_d1_regress.txt
echo "=== d1_regress rc=$? ==="
echo R7E_ALLDONE
