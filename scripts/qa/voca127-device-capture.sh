#!/usr/bin/env bash
# VOCA-127 真机取证脚本：条目 3 词组自学习（VOCA-124）
#
# 用途：接上真机后一键采集 VOCA-127 必验项所需的设备信息、logcat、词库快照。
# 本脚本不伪造任何结果：没有设备就直接退出 2，输出 blocked 原因。
#
# 用法：
#   scripts/qa/voca127-device-capture.sh pre        # 验证前：设备信息 + 词库快照 + 起 logcat
#   scripts/qa/voca127-device-capture.sh dict       # 任意时刻：打印当前词库
#   scripts/qa/voca127-device-capture.sh anr        # 验证后：ANR 检查
#   scripts/qa/voca127-device-capture.sh post       # 验证后：词库快照 + 停 logcat + 汇总
#   scripts/qa/voca127-device-capture.sh all        # 上述全跑（人工在 pre/post 之间操作键盘）
#
# 产物统一落在 ./qa-evidence/voca127-<时间戳>/，不写入仓库其它目录。

set -uo pipefail

PKG="com.ispeech.ime"
OUT_ROOT="./qa-evidence"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_ROOT/voca127-$TS"
LOG="$OUT/logcat-phrase.txt"
ADB="${ADB:-adb}"

die() { echo "ERROR: $*" >&2; exit 2; }
say() { echo "==> $*"; }

require_device() {
  [ -x "$(command -v "$ADB" 2>/dev/null)" ] || die "找不到 adb（PATH 里没有，也未设置 \$ADB）"
  local state
  state="$("$ADB" get-state 2>/dev/null || true)"
  if [ "$state" != "device" ]; then
    cat >&2 <<'EOF'
BLOCKED: 没有可用的 Android 真机。

  adb get-state 未返回 "device"。

按 VOCA-127 要求，模拟器结果不能冒充真机，本脚本不会继续执行。
解除阻塞需要：一台已开启 USB 调试（或无线调试）的 Android 真机，`adb devices` 显示 device 状态。
EOF
    exit 2
  fi
}

cmd_pre() {
  require_device
  mkdir -p "$OUT" || die "无法创建产物目录 $OUT"

  say "采集设备信息 → $OUT/device.txt"
  {
    echo "# VOCA-127 真机取证 — $TS"
    for p in ro.product.manufacturer ro.product.model ro.product.device \
             ro.build.display.id ro.build.version.release ro.build.version.sdk \
             ro.build.fingerprint ro.product.cpu.abi; do
      printf '%-32s %s\n' "$p" "$("$ADB" shell getprop "$p" 2>/dev/null | tr -d '\r')"
    done
    echo "adb version: $("$ADB" version 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
  } | tee "$OUT/device.txt"

  say "采集 APK 信息"
  "$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime" \
    > "$OUT/apk-info.txt" || true

  say "清空 logcat 缓冲"
  "$ADB" logcat -c 2>/dev/null

  say "词库快照（验证前）→ $OUT/user_dict-before.txt"
  "$ADB" shell run-as "$PKG" cat files/user_dict.txt 2>/dev/null \
    | tr -d '\r' > "$OUT/user_dict-before.txt" || true
  if [ -s "$OUT/user_dict-before.txt" ]; then
    echo "    当前词库 $(wc -l < "$OUT/user_dict-before.txt" | tr -d ' ') 行"
  else
    echo "    词库为空或不可读（首次安装属正常）"
  fi

  say "起 logcat 后台抓取 → $LOG"
  say "  之后的键盘操作都会被记录。完成操作后跑：$0 post"
  "$ADB" logcat -v time > "$LOG" 2>/dev/null &
  echo $! > "$OUT/logcat.pid"
  echo "    logcat pid=$(cat "$OUT/logcat.pid")"
  echo
  echo "产物目录：$OUT"
}

cmd_dict() {
  require_device
  echo "== $PKG files/user_dict.txt =="
  "$ADB" shell run-as "$PKG" cat files/user_dict.txt 2>/dev/null | tr -d '\r'
}

cmd_anr() {
  require_device
  local hits
  hits="$("$ADB" logcat -d 2>/dev/null | tr -d '\r' \
    | grep -E "ANR in $PKG|Input dispatching timed out" || true)"
  if [ -z "$hits" ]; then
    echo "AC-3.11 PASS: 无 ANR 记录（grep -E \"ANR in $PKG|Input dispatching timed out\" 命中 0）"
    return 0
  fi
  echo "AC-3.11 FAIL: 检测到 ANR / 输入超时"
  echo "$hits"
  return 1
}

cmd_post() {
  require_device
  [ -d "$OUT" ] || die "找不到 pre 产物目录，请先跑：$0 pre"

  say "词库快照（验证后）→ $OUT/user_dict-after.txt"
  "$ADB" shell run-as "$PKG" cat files/user_dict.txt 2>/dev/null \
    | tr -d '\r' > "$OUT/user_dict-after.txt" || true

  say "停 logcat"
  if [ -f "$OUT/logcat.pid" ]; then
    kill "$(cat "$OUT/logcat.pid")" 2>/dev/null || true
    rm -f "$OUT/logcat.pid"
  fi

  say "抽取词组学习关键行 → $OUT/phrase-key-lines.txt"
  grep -E "phrase_learn|phrase_remove" "$LOG" 2>/dev/null > "$OUT/phrase-key-lines.txt" || true

  say "AC-3.11 ANR 检查"
  cmd_anr | tee "$OUT/anr-check.txt" || true

  say "判定摘要"
  python3 - "$OUT" <<'PY' | tee "$OUT/summary.txt"
import sys, os, re
out = sys.argv[1]
def read(p):
    try: return open(os.path.join(out, p), encoding='utf-8', errors='replace').read()
    except FileNotFoundError: return ''
key = read('phrase-key-lines.txt')
commits = re.findall(r'phrase_learn commit code=(\S+) text=(\S+)', key)
skipped = re.findall(r'phrase_learn skipped reason=(\w+)', key)
removes = re.findall(r'phrase_remove code=(\S+) text=(\S+)', key)
interrupt = skipped.count('interrupt')

before = {l.strip() for l in read('user_dict-before.txt').splitlines() if l.strip()}
after  = {l.strip() for l in read('user_dict-after.txt').splitlines() if l.strip()}

print(f"phrase_learn commit : {len(commits)} 条 {commits}")
print(f"skipped reason 统计 : { {r: skipped.count(r) for r in set(skipped)} }")
print(f"phrase_remove      : {len(removes)} 条")
print(f"词库新增行          : {len(after - before)} 条")
for l in sorted(after - before): print(f"  + {l}")
for l in sorted(before - after): print(f"  - {l}")
print()
print("F1 判据（AC-3.4 ① 反向用例）：")
if interrupt:
    print(f"  FAIL — 出现 {interrupt} 次 skipped reason=interrupt，且 commit {len(commits)} 次；")
    print("         若能排除『用户确实移动了光标』，即为 F1 成立，本项记 fail。")
else:
    print("  未见 reason=interrupt，符合 AC-3.4 ① 期望（需与人工操作记录交叉核对）。")
PY

  echo
  echo "全部产物：$OUT"
  ls -1 "$OUT"
}

case "${1:-}" in
  pre)  cmd_pre ;;
  dict) cmd_dict ;;
  anr)  cmd_anr ;;
  post) cmd_post ;;
  all)  cmd_pre; echo; echo "现在请按用例集人工操作键盘，完成后按回车…"; read -r _; cmd_post ;;
  *)    sed -n '2,20p' "$0"; exit 1 ;;
esac