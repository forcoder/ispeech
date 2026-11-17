#!/bin/bash
# RUN4：抓「宿主缓冲有残留」的字面形状（残留串跟在预热串后面），而不是 RUN2 那种
# 键没进 IME 的占位文案。做法是在脚本预热（clear→type_str）期间并发补注入若干个 z，
# 让读到的宿主文本变成 'shi' + 一串 z。构造是明写着的，不当新缺陷、只当闸门分支的取证。
cd "$(dirname "$0")" || exit 1
A=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
APK=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-d3c8a7824003/voca47-c524351/app/build/outputs/apk/debug/app-debug.apk
OUT=./gate_r4
python3 ac47.py "$APK" 440 > "$OUT/r4_host_residue.txt" 2>&1 &
SPID=$!
for i in $(seq 1 150); do grep -q '^HOST' "$OUT/r4_host_residue.txt" && break; sleep 0.5; done
sleep 2.0                       # 等 clear(22) 跑完，落进 type_str 之后的窗口
for i in $(seq 1 22); do $A -s emulator-5554 shell input text z; done
wait $SPID; R4=$?
echo "R4_RC=$R4" | tee -a "$OUT/rc.txt"
grep -E '^(PREP|HOST|GATE)\b' "$OUT/r4_host_residue.txt" | tee -a "$OUT/rc.txt"
