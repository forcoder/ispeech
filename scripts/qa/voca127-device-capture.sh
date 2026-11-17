#!/usr/bin/env bash
# VOCA-127 真机取证脚本：条目 3 词组自学习（VOCA-124）
#
# 用途：接上真机后一键采集 VOCA-127 必验项所需的设备信息、logcat、词库快照。
# 本脚本不伪造任何结果：没有设备就直接退出 2，输出 blocked 原因。
#
# 用法：
#   scripts/qa/voca127-device-capture.sh pre             # 验证前：设备信息 + 包准入自证 + 词库快照 + 起 logcat
#   scripts/qa/voca127-device-capture.sh dict            # 任意时刻：打印当前词库
#   scripts/qa/voca127-device-capture.sh cand <label>    # 候选条快照（before / after / removed / cleared）
#   scripts/qa/voca127-device-capture.sh gate            # AC-3.7 + AC-3.12 ①②③ 门禁判定
#   scripts/qa/voca127-device-capture.sh anr             # AC-3.11：ANR 检查
#   scripts/qa/voca127-device-capture.sh post            # 验证后：词库快照 + 停 logcat + 汇总
#   scripts/qa/voca127-device-capture.sh all             # pre → 人工操作 → post
#
# 产物统一落在 ./qa-evidence/voca127-<时间戳>/，不写入仓库其它目录。
# 口径来源：docs/voice-phrase-learning-verify.md@8ab0a88 §0/§1/§2.1（旧版 @7ed1230 的 §1 ②③
#           两条期望在代码里没有发射点，照它跑会得到假 fail）、
#           VOCA-65-acceptance.md v1.1.2@b8e8d40（页码类断言已作废，只用 index/角标/高亮/项数）。

set -uo pipefail

PKG="com.ispeech.ime"
OUT_ROOT="./qa-evidence"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$OUT_ROOT/voca127-$TS"
LOG="$OUT/logcat-phrase.txt"
ADB="${ADB:-adb}"
# pre/post/cand/gate 分开跑时必须落同一个会话目录，否则学习前/后快照对不上
PTR="$OUT_ROOT/voca127.latest"
EXPECTED_WORD="喂猫"
EXPECTED_CODE="klqt"
# 已知的旧包版本：装的是这个版本说明包里没有条目 3 的实现，验证对象不成立
BAD_VERSION_NAME="1.5"

die() { echo "ERROR: $*" >&2; exit 2; }
say() { echo "==> $*"; }

session_new() { mkdir -p "$OUT" || die "无法创建产物目录 $OUT"; LOG="$OUT/logcat-phrase.txt"; echo "$OUT" > "$PTR"; }
session_resume() {
  [ -f "$PTR" ] && OUT="$(cat "$PTR")"
  [ -d "${OUT:-}" ] || die "找不到会话目录（先跑：$0 pre）"
  LOG="$OUT/logcat-phrase.txt"
}

require_device() {
  [ -x "$(command -v "$ADB" 2>/dev/null)" ] || die "找不到 adb（PATH 里没有，也未设置 \$ADB）"
  local state
  state="$("$ADB" get-state 2>/dev/null || true)"
  if [ "$state" != "device" ]; then
    cat >&2 <<'EOF'
BLOCKED: 没有可用的 Android 真机。

  adb get-state 未返回 "device"。

按 VOCA-127 要求，模拟器结果不能冒充真机，本脚本不会继续执行。
解除阻塞需要：一台已开启 USB 调试（或无线调试）的 Android 真机，`adb devices` 显示 device 状态
（期望 serial 23F0224224000950，见 VOCA-127 用例集 §7）。
EOF
    exit 2
  fi
}

adb_shell_path() {
  "$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed 's/^package://' | head -n 1
}

cmd_pre() {
  require_device
  session_new

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

  say "采集 APK 信息 + 包准入自证（用例集 §1 ③）"
  {
    "$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime" | tr -d '\r'
  } > "$OUT/apk-info.txt" || true
  cat "$OUT/apk-info.txt"
  VN="$(grep -m1 versionName "$OUT/apk-info.txt" 2>/dev/null | sed 's/.*versionName=//' | tr -d ' \r')"
  if [ -z "$VN" ]; then
    say "  ⚠ 拿不到 versionName，包准入未自证 —— 手工核对后再开始，不要默认包是对的"
  elif [ "$VN" = "$BAD_VERSION_NAME" ]; then
    say "  ✗ 包准入不成立：装机包 versionName=$BAD_VERSION_NAME，即 PR #4 合入前的 main 构建，不含条目 3 的实现。"
    say "    本单应判 blocked（验空对象），改用合入后重出的官方包，不要继续跑用例。"
    exit 2
  fi
  APKDEV="$(adb_shell_path)"
  if [ -z "$APKDEV" ]; then
    say "  ⚠ 拿不到已装 APK 路径，tag 自证未做：手工核对 unzip -p <apk> 'classes*.dex' | strings | grep -c phrase_learn"
  elif ! command -v unzip >/dev/null 2>&1 || ! command -v strings >/dev/null 2>&1; then
    say "  ⚠ 本机缺 unzip/strings，无法自证 APK 内容（不代表包没问题）：请手工核对后再跑用例"
  elif "$ADB" pull "$APKDEV" "$OUT/base.apk" >/dev/null 2>&1; then
    if unzip -p "$OUT/base.apk" 'classes*.dex' > "$OUT/dex.raw" 2>/dev/null; then
      HITS="$(strings "$OUT/dex.raw" 2>/dev/null | grep -c "phrase_learn" || true)"
      echo "phrase_learn 字符串命中数=${HITS:-0} versionName=${VN:-?} apk=$APKDEV" > "$OUT/apk-admission.txt"
      rm -f "$OUT/dex.raw" "$OUT/base.apk"
      cat "$OUT/apk-admission.txt"
      if [ "${HITS:-0}" = "0" ]; then
        say "  ✗ 包准入不成立：装机 APK 里捞不到日志 tag phrase_learn，说明包里没有条目 3 的实现。"
        say "    本单判 blocked（继续跑等于验空对象），换合入后重出的官方包再来。"
        exit 2
      fi
      say "  ✓ 包准入成立：versionName=${VN:-?}，APK 内可见 phrase_learn"
    else
      rm -f "$OUT/base.apk"
      say "  ⚠ 解不出 classes*.dex（APK 结构异常或 split APK），tag 自证未做，请手工核对"
    fi
  else
    say "  ⚠ 拉取已装 APK 失败（部分 ROM 限制），tag 自证未做，请对本机 apk 文件手工核对"
  fi

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

cmd_cand() {
  local label="${1:-}"
  [ -n "$label" ] || die "用法：$0 cand <before|after|removed|cleared>"
  require_device
  session_resume
  local xml="$OUT/cand-$label.xml" seq="$OUT/cand-$label.seq"
  "$ADB" shell uiautomator dump /sdcard/cand.xml >/dev/null 2>&1 || true
  "$ADB" pull /sdcard/cand.xml "$xml" >/dev/null 2>&1 || true
  "$ADB" shell rm /sdcard/cand.xml >/dev/null 2>&1 || true
  [ -s "$xml" ] || die "uiautomator dump 没产出 XML：确认键盘已弹出、输入框里是 $EXPECTED_CODE"

  if python3 - "$xml" "$seq" <<'PY'
import sys, xml.etree.ElementTree as ET
xml, seq = sys.argv[1], sys.argv[2]
group = [n for n in ET.parse(xml).getroot().iter()
         if n.get('resource-id', '').endswith('candidate_items')]
if not group:
    sys.exit(3)
items = list(group[0])
rows = []
for i, it in enumerate(items):
    texts = [c.get('text') for c in it.iter() if c.get('text')]
    rows.append((i, it.get('content-desc') or '', texts[-1] if texts else ''))
with open(seq, 'w', encoding='utf-8') as f:
    for i, word, badge in rows:
        f.write('%d\t%s\t%s\n' % (i, word, badge))
print('项数 =', len(items))
for i, word, badge in rows:
    print('index %d  候选=%s  角标=%s' % (i, word, badge))
PY
  then
    say "候选条快照（$label）→ $xml / $seq"
  else
    rc=$?
    [ "$rc" = "3" ] || die "解析失败（exit=$rc）"
    rm -f "$xml"
    say "  这台 ROM 的 IME 窗口不进 uiautomator dump —— 判据不改，转目视留证"
    "$ADB" exec-out screencap -p > "$OUT/cand-$label.png" 2>/dev/null || true
    say "  已抓屏 → $OUT/cand-$label.png；按「数字角标 1 + 首选高亮 + 从左第 1 项」判定，报告里必须附该图"
    return 1
  fi
}

cmd_gate() {
  session_resume
  local b="$OUT/cand-before.seq" a="$OUT/cand-after.seq"
  [ -s "$b" ] && [ -s "$a" ] || die "需要 $b 与 $a 两次快照（同一会话内、不重启不清词库）：先跑 cand before / 学「喂猫」/ 再跑 cand after"
  say "AC-3.7 + AC-3.12 门禁判定 → $OUT/ac3-7-12-gate.txt"
  python3 - "$b" "$a" "$EXPECTED_WORD" <<'PY' | tee "$OUT/ac3-7-12-gate.txt"
import sys
before = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
after  = [l.rstrip('\n').split('\t') for l in open(sys.argv[2], encoding='utf-8') if l.strip()]
word = sys.argv[3]
bw = [w for _, w, _ in before]
aw = [w for _, w, _ in after]
ap = [w for w in aw if w != word]
ok1 = ap == bw
ok2 = len(after) == len(before) + 1
ok3 = bool(aw) and aw[0] == word
badge0 = after[0][2] if after else ''
# 角标 == 序号+1 逐行成立，才说明 uiautomator 的读序就是屏幕上的视觉序
badge_ok = all(len(r) > 2 and r[2] == str(i + 1) for i, r in enumerate(after))
print('学习前序列 :', bw, '项数', len(bw))
print('学习后序列 :', aw, '项数', len(after))
print()
print('AC-3.12 ① 不丢失（后快照去掉新词后与前快照逐项相等、相对顺序不变）: %s' % ('PASS' if ok1 else 'FAIL'))
print('AC-3.12 ② 数量（candidate_items 子 View 数 = 前 + 1，干净机 2→3） : %s' % ('PASS' if ok2 else 'FAIL'))
print('AC-3.12 ③ / AC-3.7 「%s」在 index 0                              : %s' % (word, 'PASS' if ok3 else 'FAIL'))
print('AC-3.7 交叉校验（逐行角标 = 序号+1，读序即视觉序）                : %s (index 0 角标实测 %r)'
      % ('PASS' if badge_ok else 'FAIL', badge0))
print()
print('注：② 只在 klqt 这个受控向量成立（同码候选 < candidatePoolLimit 27），不得推广为通用断言；')
print('    ① 不受 27 上限影响，任何同码冲突场景都必须成立；页码类断言已随 VOCA-185 作废，本脚本不判。')
sys.exit(0 if (ok1 and ok2 and ok3) else 1)
PY
}

cmd_post() {
  require_device
  session_resume

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
skipped = re.findall(r'phrase_learn skipped reason=(\w+)( count=\d+)?', key)
removes = re.findall(r'phrase_remove code=(\S+) text=(\S+)', key)
# interrupt 有两个发射点：:1178 光标路径（不带 count=，F1 要看的就是它）
# 与 :1126 非单字上屏（带 count=，属操作里混进词组/联想，重跑即可）。混计会出假 F1。
cursor_interrupt = sum(1 for r, c in skipped if r == 'interrupt' and c == '')
op_interrupt     = sum(1 for r, c in skipped if r == 'interrupt' and c)
reasons          = [r for r, _ in skipped]
# UNDECIDABLE 走 :1124 的 WARN，文本形状不同（phrase_learn undecidable first=… count=…），
# 上面的 skipped reason= 抓不到它，单独计一次，否则 N-3 的字对选错会漏一类。
reasons += ['undecidable'] * len(re.findall(r'phrase_learn undecidable ', key))

before = {l.strip() for l in read('user_dict-before.txt').splitlines() if l.strip()}
after  = {l.strip() for l in read('user_dict-after.txt').splitlines() if l.strip()}

print(f"phrase_learn commit : {len(commits)} 条 {commits}")
print(f"skipped reason 统计 : { {r: reasons.count(r) for r in set(reasons)} }")
print(f"  interrupt 光标路径(:1178, 无 count=) {cursor_interrupt} 次 / 非单字上屏(:1126, 带 count=) {op_interrupt} 次")
print(f"phrase_remove      : {len(removes)} 条")
print(f"词库新增行          : {len(after - before)} 条")
for l in sorted(after - before): print(f"  + {l}")
for l in sorted(before - after): print(f"  - {l}")
print()
print("F1 判据（AC-3.4 ① 反向用例）：")
if cursor_interrupt:
    print(f"  FAIL — 出现 {cursor_interrupt} 次 :1178 的 skipped reason=interrupt（不带 count=），且 commit {len(commits)} 次；")
    print("         若能排除『用户确实移动了光标』，即为 F1 成立，本项记 fail。")
else:
    print("  未见 :1178 的 reason=interrupt，符合 AC-3.4 ① 期望（需与人工操作记录交叉核对）。")
if op_interrupt:
    print(f"  ⚠ {op_interrupt} 次 interrupt 带 count=（来自 :1126 非单字上屏）：这一条不计 F1，")
    print("     说明该轮拼接里混进了一次词组/联想上屏，重跑那一次并把次数记进报告。")
other = sorted({r for r in reasons if r != 'interrupt'})
if other:
    print(f"  非 interrupt 的 skip（{other}）属字对选错（N-3）：换对字对重跑并把 reason 记进报告，")
    print("  换对后仍凑不满 10 条才计入 F1，不要混进本项 fail。")
PY

  if [ -s "$OUT/cand-before.seq" ] && [ -s "$OUT/cand-after.seq" ]; then
    say "候选条门禁（AC-3.7 / AC-3.12 ①②③）"
    cmd_gate || true
  else
    say "  未做候选条门禁：缺 cand before / cand after 快照（AC-3.7 / 3.10 / 3.12 必须两次都在同一次会话内抓）"
  fi

  echo
  echo "全部产物：$OUT"
  ls -1 "$OUT"
}

case "${1:-}" in
  pre)   cmd_pre ;;
  dict)  cmd_dict ;;
  cand)  shift; cmd_cand "${1:-}" ;;
  gate)  cmd_gate ;;
  anr)   cmd_anr ;;
  post)  cmd_post ;;
  all)   cmd_pre; echo; echo "现在按用例集操作：cand before → 学「喂猫」→ cand after → AC-3.10 的删词/清空各抓一张，完成后按回车…"; read -r _; cmd_post ;;
  *)     sed -n '2,18p' "$0"; exit 1 ;;
esac