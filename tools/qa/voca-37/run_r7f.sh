#!/usr/bin/env bash
# VOCA-47 第七轮 · 第六串：AC-9 单独跑，且**先复位输入法数据态**。
# 为什么要有这一串：run_r7c.sh 把 ac9 排在 d1regress（档②③ 会切进英文态 / T9 数字态）之后，
# 结果 ac9 的 key_map 反推不到 10 键行，只出 `GATE reason=key_map_fail rc=16`（ev_r7_ac9.txt 的 A 段）。
# 那一串没有读数，所以第七轮的 AC-9 结论只能来自本串。
set -u
export QA_HEAD=78343a4 QA_SPEC=0e4eeb0
SER="${QA_SERIAL:-emulator-5554}"
ADB_BIN="${QA_ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}"
WS=/Users/cookie/multica_workspaces_desktop-api.multica.ai/vocation-d18f8ab6510e/voca-47-1a7ac9fe79f3
FIX="$WS/build-78343a4/app/build/outputs/apk/debug/app-debug.apk"
export QA_APK="$FIX"
cd "$(dirname "$0")" || exit 1
export QA_SERIAL="$SER"

echo "# ac9 · 被测=78343a4（PR #18 head）· 规格锚=0e4eeb0（PR #17 head）"
echo "# cmd: pm clear + ime set + python3 ac9.py 60 200,1000,1500,2000"
printf '# ts=%s  serial=%s  device=%s\n' "$(date +%Y-%m-%dT%H:%M:%S%z)" "$SER" \
  "$("$ADB_BIN" -s "$SER" shell getprop ro.product.model | tr -d '\r')"
# 复位：T9/英文态是持久化在 DataStore 里的，不 clear 就会一路带到下一档
"$ADB_BIN" -s "$SER" shell pm clear com.ispeech.ime
"$ADB_BIN" -s "$SER" shell ime set com.ispeech.ime/com.ispeech.input.IspeechImeService
sleep 3
# 跑前先起宿主 + 证键位（runs 必须是 [10,9,7,3]），不然这一串又是 rc=16 的空跑。
# pm clear 之后旧宿主窗口是僵尸的（README 坑 3），必须先 host_ready() 才谈得上 key_map()。
python3 -c "
import sys; sys.path.insert(0,'.')
import drive as D
st=D.host_ready()
D.log('HOST', ok=st.get('ok'), reason=st.get('reason'), frame=D.ime_frame())
km=D.key_map(); d=km[1] if isinstance(km,tuple) else km
D.log('PRECHECK', runs=d.get('runs'), ok=d.get('ok'), keymap_diag=sorted(k for k in d if k not in ('bands','bands_raw','space_band')))
sys.exit(0 if d.get('ok') else 16)"
pre=$?
if [ "$pre" -ne 0 ]; then printf '# rc=%s (键位仍反推不到，未跑阶梯) ts=%s\n' "$pre" "$(date +%Y-%m-%dT%H:%M:%S%z)"; exit "$pre"; fi
python3 ac9.py 60 200,1000,1500,2000
printf '# rc=%s ts=%s\n' "$?" "$(date +%Y-%m-%dT%H:%M:%S%z)"
