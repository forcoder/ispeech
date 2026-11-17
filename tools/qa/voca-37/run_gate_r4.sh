#!/bin/bash
# VOCA-47 第四轮返修：证明 GATE 的 reason= 能把两种前提失效分开。
# 同一台设备不能并发，三次 run 串行；RUN2 的「宿主缓冲有残留」用后台注入复现——
# 那正是共享 AVD 下别的 run 抢键的真实形状（脚本自己会 force-stop 宿主重建输入框，
# 所以开局前预置的残留一定会被它擦掉，只能在它预热的那一刻把键漏进去）。
cd "$(dirname "$0")" || exit 1
A=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
APK=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-d3c8a7824003/voca47-c524351/app/build/outputs/apk/debug/app-debug.apk
OUT=./gate_r4
mkdir -p "$OUT"
: > "$OUT/rc.txt"

echo "=== RUN1 对调档（期望 reason=probe_zero rc=4）" | tee -a "$OUT/rc.txt"
python3 ac47.py "$APK" 440 --fwd 69 --rev 70 > "$OUT/r1_probe_zero.txt" 2>&1
echo "R1_RC=$?" | tee -a "$OUT/rc.txt"
grep -E '^(PRE_PROBE|GATE)\b' "$OUT/r1_probe_zero.txt" | tee -a "$OUT/rc.txt"

echo "=== RUN2 预热期间并发注入（期望 reason=host_buffer rc=5）" | tee -a "$OUT/rc.txt"
python3 ac47.py "$APK" 440 > "$OUT/r2_host_buffer.txt" 2>&1 &
SPID=$!
sleep 22          # 让 install + ident + host_ready 先跑完，赶上 clear/type_str 这一步
for i in $(seq 1 60); do
  $A -s emulator-5554 shell input text zz >> "$OUT/r2_inject.log" 2>&1
  sleep 0.35
done
wait $SPID; R2=$?
echo "R2_RC=$R2" | tee -a "$OUT/rc.txt"
grep -E '^(PREP|HOST|GATE)\b' "$OUT/r2_host_buffer.txt" | tee -a "$OUT/rc.txt"

echo "=== RUN3 按 §10-4b 复位后跑正向（期望 rc=0、PRE_PROBE shift≈+198）" | tee -a "$OUT/rc.txt"
$A -s emulator-5554 shell input keyevent KEYCODE_MOVE_END
for i in $(seq 1 30); do $A -s emulator-5554 shell input keyevent 67; done
python3 ac47.py "$APK" 440 --n 2 > "$OUT/r3_forward.txt" 2>&1
echo "R3_RC=$?" | tee -a "$OUT/rc.txt"
grep -E '^(PRE_PROBE|GATE|HOSTTEXT|SUMMARY)\b' "$OUT/r3_forward.txt" | tee -a "$OUT/rc.txt"
echo "=== DONE" | tee -a "$OUT/rc.txt"
