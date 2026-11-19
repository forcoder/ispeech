#!/usr/bin/env bash
# v1.6 最终包出包门禁（SRE）。
#
# 两道门分别回答两个不同的问题：
#   门一（源码）：这个包是从「窗口合并后的 main」来的，还是从一个过期基线来的？
#   门二（字节）：修复真的在 APK 的 dex 里，而不只是在分支上？
# 门一过了门二也可能不过（历史上 1.6 试包就是这样），门二过了门一不过 = 整个基线回退，
# 单靠 dex 符号表看不出来（版本号仍是 6/1.6，只是包里没有窗口之后合进 main 的东西）。
#
# 用法（必须在仓库工作树内执行）：
#   bash tools/deploy/assert_apk_fix.sh <apk> <出包commit> <窗口HEAD>
#   APK_MODE=probe404 bash tools/deploy/assert_apk_fix.sh <apk> <出包commit> <窗口HEAD>
#   SHIP_README_REF=<ref>  …… 覆盖「随包分发那份 README」的锚 ref，默认为出包 commit（见交付文档一节）
#
# 参数不给默认值：默认值本身就是过期基线能被再次出包的通道（见 a9d1dae 事故）。
# APK_MODE=probe404 = AC-6 的下载失败变体包，它按设计永远不可能通过交付门禁。
#
# ── 侧别：本脚本只判「修复在不在包里」，不判「功能好不好用」（台账 §13.3）────────
#   进 exit 条件 = C1 C2 C3 C4 C5 C9（出包侧：拿到构建 commit / apk 就能给 exit 码）
#   明确不进 exit = C6（真机四件套，装机专员 11-20 出证）
#                   C7（设备接入 MBP，人类侧，VOCA-419 第 7 项）
#                   C8（AC-5 真人 10 句时段，人类侧观测基线）
#   理由：脚本对这三条既不能证真也不能证伪，写成 exit 条件只会得到恒 FAIL（然后被人删掉）
#   或恒 PASS（然后被当成已核）。出包侧对 C6 的唯一义务是把最终包 sha256 写进
#   dist/SHA256SUMS 的交付行 —— 那是动作，不是判据，别拿 echo 冒充它。
#
# ── 为什么是「在树断言」而不是「PR head 祖先断言」（台账 §13.1，作废 §12.3 那版）──
# 本项目开窗方式是 squash（main 的提交形态例：`e0dddb2 docs(adr): … (#8)`），squash 生成的新提交
# 不以 PR head 为祖先。`git merge-base --is-ancestor <PR head> <构建 commit>` 在 11-19 之后对
# C2/C3/C4/C5 每一条都是 NO —— 包里有修复也判不在；它对 11-15 那份试包成立（走 bc81c30 的 merge
# commit），对 squash 后的最终包不成立。窗口里跑一次看到全红，第一反应就是去松门禁，判据反而失效。
set -uo pipefail

APK="${1:?用法: bash tools/deploy/assert_apk_fix.sh <apk> <出包commit> <窗口HEAD>}"
BUILD_COMMIT="${2:?缺少出包 commit（窗口合并后 main 之上的版本提交）}"
WINDOW_HEAD="${3:?缺少窗口 HEAD（11-19 各 squash 合并完成后的 origin/main）}"
APK_MODE="${APK_MODE:-final}"
AC6_PROBE_URL_MARK="v1.6-ac6-404-probe-does-not-exist"
DEFAULT_MODEL_HOST="delivery-service-smzk"
# probe404 模式判「正式默认源已被覆盖」必须用默认 object key，不能用 host：
# 测试侧采纳的探针 URL 与正式源同域（保留真实网络路径），host 子串在探针 URL 里必然命中，
# 用 host 判会把覆盖生效的正确变体恒判 FAIL（11-19 实测）。final 模式仍用 host。
DEFAULT_MODEL_OBJECT="sherpa-onnx-paraformer-zh-small-2024-03-09"
DEAD_MODEL_HOST="cvm.agentai0.com"

# 在树断言的文件路径（C2/C3/C4/C5 的判据对象，逐个对应窗口步）
KVM=app/src/main/java/com/ispeech/settings/keyboard/KeyboardViewModel.kt
KVM_GUARD_TEST=app/src/test/java/com/ispeech/settings/keyboard/KeyboardViewModelInitOrderTest.kt
ASSOC=app/src/main/java/com/ispeech/engine/AssociationCommit.kt
MIC_GRANT=app/src/main/java/com/ispeech/input/MicGrantAction.kt
SHIP_README=dist/README.md
# 「实际发给内测用户的那份说明」所在的 ref。默认 = 出包 commit 本身：11-19 的出包提交落在发布分支上，
# 它改的 README 与被登记 sha256 的包是同一个 commit（PM 11-17 裁定）。显式覆盖留给出包 commit
# 不携带 README 的那种走法（例：SHIP_README_REF=origin/release/voca-186-v1.6）。
SHIP_README_REF="${SHIP_README_REF:-$BUILD_COMMIT}"

# 交付文档三句（锚 ref 见「交付文档」一节的注释，不是 $BUILD）
RECIPE_STALE='git checkout a9d1dae'
BAD_VERSION_CLAIM='凭版本号区分'
# G6 口径句的可识别形态：runbook §3 那句可直接粘贴的原文里，`versionName=1.6` 与「为准」之间隔着
# `/ versionCode=6` 和反引号，所以按字面 grep "versionName=1.6 为准" 恒 0 命中（PM 11-17 三处实测）。
# 三条任选其一即算口径写出来了。
G6_DOC_RE='应用内[^。]*v1\.5|versionName=1\.6[^。]*为准|VOCA-233（PR #14）'

ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$ANDROID_HOME/build-tools/34.0.0"
AAPT2="$BT/aapt2"; [ -x "$AAPT2" ] || AAPT2="$(command -v aapt2 || true)"
DEXDUMP="$BT/dexdump"; [ -x "$DEXDUMP" ] || DEXDUMP="$(command -v dexdump || true)"

FAIL=0
ok()   { printf '  [PASS] %s\n' "$1"; }
bad()  { printf '  [FAIL] %s\n' "$1"; FAIL=1; }
info() { printf '         %s\n' "$1"; }

git rev-parse --verify -q "$BUILD_COMMIT^{commit}" >/dev/null || { echo "出包 commit 不存在：$BUILD_COMMIT"; exit 2; }
git rev-parse --verify -q "$WINDOW_HEAD^{commit}"  >/dev/null || { echo "窗口 HEAD 不存在：$WINDOW_HEAD"; exit 2; }

echo "== v1.6 出包门禁 ==  模式=$APK_MODE  包=$APK"
echo "  出包 commit: $(git rev-parse --short=9 "$BUILD_COMMIT")   窗口 HEAD: $(git rev-parse --short=9 "$WINDOW_HEAD")"
[ -f "$APK" ] || { echo "APK 不存在：$APK"; exit 2; }

# ── 门一：源码基线（全部在树断言，除 C9）────────────────────────
echo "· 门一 源码基线（C9 + C1 C2 C3 C4 C5代码半，共 6 项）"

# C9 —— 本脚本唯一保留的祖先判定，方向是「窗口 HEAD ⊆ 出包 commit」：squash 落在 main 上、出包提交是
# 它的真子，所以这条成立。别照 §12.3 的老写法反着写成「PR head ⊆ 构建 commit」—— squash 提交不以
# PR head 为祖先，那种形式 11-19 之后恒 NO（§13.1）。
# C9 与 C1 不能合并成一道门，反例就是 a9d1dae：它的 AsrEngine.FUNASR 接线还在（8 处）⇒ 单看 C1 照样
# PASS，但它比窗口 HEAD 少 main 原有 10 个提交 + 窗口 squash 的 4 条修复，而 versionCode/Name 一个字节
# 都不变，装机侧与 aapt2 侧都看不出来。
if git merge-base --is-ancestor "$WINDOW_HEAD" "$BUILD_COMMIT"; then
  ok "C9 窗口 HEAD 是出包 commit 的祖先（①…④ 的 squash 提交与 main 原有提交都在包里）"
else
  bad "C9 出包基线不含窗口 HEAD → 本次合并的全部修复都在包外（a9d1dae 事故就是这个；版本号不会告诉你这件事）"
fi

funasr_hits=$(git grep -c "AsrEngine.FUNASR" "$BUILD_COMMIT" -- app 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')
if [ "${funasr_hits:-0}" -gt 0 ]; then
  ok "C1 ① VOCA-190：出包树里 AsrEngine.FUNASR 命中 $funasr_hits 处（默认引擎有离线链路）"
else
  bad "C1 ① VOCA-190：出包树里 AsrEngine.FUNASR 0 命中 → 语音功能面为零（默认 SYSTEM + 前置判定恒假），别占真机时段"
fi

KVM_SRC=$(git show "${BUILD_COMMIT}:${KVM}" 2>/dev/null || true)
init_line=$(printf '%s\n' "$KVM_SRC" | grep -n '^    init {' | head -1 | cut -d: -f1)
cim_line=$(printf '%s\n' "$KVM_SRC" | grep -n 'private val _chineseInputMethod' | head -1 | cut -d: -f1)
slp_line=$(printf '%s\n' "$KVM_SRC" | grep -n 'private val _spaceLongPressMs' | head -1 | cut -d: -f1)
max_decl=0
[ -n "${cim_line:-}" ] && max_decl="$cim_line"
[ -n "${slp_line:-}" ] && [ "$slp_line" -gt "$max_decl" ] && max_decl="$slp_line"
if git cat-file -e "${BUILD_COMMIT}:${KVM_GUARD_TEST}" 2>/dev/null; then guard_in_tree=1; else guard_in_tree=0; fi
if [ -n "${init_line:-}" ] && [ "$max_decl" -gt 0 ] && [ "$max_decl" -lt "$init_line" ] && [ "$guard_in_tree" -eq 1 ]; then
  ok "C2 ② VOCA-170/AC-9：两个声明最大行(:$max_decl) 早于 init{ }(:$init_line)，且护栏单测在树"
else
  bad "C2 ② VOCA-170/AC-9：init 顺序未修正或护栏单测不在树（声明最大 :${max_decl} / init :${init_line:-无} / 护栏单测=$guard_in_tree）→ 设置页确定性 NPE；命中数为 0 时先怀疑文件被移动，判据跟着改，别松门禁"
fi

if git cat-file -e "${BUILD_COMMIT}:${ASSOC}" 2>/dev/null; then
  ok "C3 ③ VOCA-121/AC-10：$ASSOC 在出包树里"
else
  bad "C3 ③ VOCA-121/AC-10：出包树里没有 AssociationCommit.kt → 联想去重没进包（dex 四个 token 见门二）"
fi
# e8dc6f1 不再当包准入条件（§13.1）：squash 后它不是最终包的祖先，留着就是恒 FAIL 的过期门禁。
# 它现在的用途只剩一条：证明 11-15 那份试包字节里不含 AC-10 修复。历史事实，不参与判定。
if git merge-base --is-ancestor e8dc6f1 "$BUILD_COMMIT" 2>/dev/null; then
  info "C3 附注：e8dc6f1（AC-10 的 Given 锚点）在本出包 commit 历史里 → merge 形态谱系；只作说明，不判定"
else
  info "C3 附注：e8dc6f1 不在本出包 commit 历史里 → 只说明本包字节不含那个提交（11-15 试包就是这样），squash 后的最终包同样显示这一行；不参与判定"
fi

if git cat-file -e "${BUILD_COMMIT}:${MIC_GRANT}" 2>/dev/null; then
  ok "C4 ④ VOCA-166：$MIC_GRANT 在出包树里"
else
  bad "C4 ④ VOCA-166：出包树里没有 MicGrantAction.kt → 语音入口/麦克风授权修复没进包"
fi

# C5 代码半：不在这里定 FAIL —— 它和 dex 那半、README 口径句是一个「二选一闭合」的关系，判决在门二末尾。
ispeech_version_hits=$(git grep -c "ISPEECH_VERSION" "$BUILD_COMMIT" -- app 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}')
info "C5 ④b VOCA-233：出包树里 ISPEECH_VERSION 命中 ${ispeech_version_hits:-0} 处（0 = PR #14 的代码进包）→ 与 dex/README 一起走 G6 异或"

# ── 门二：包身份与字节 ──────────────────────────────────────────
echo "· 门二 包身份"
if [ -n "${AAPT2:-}" ]; then
  badge=$("$AAPT2" dump badging "$APK" 2>/dev/null)
  vc=$(printf '%s' "$badge" | grep -o "versionCode='[^']*'" | head -1 | tr -dc "0-9")
  vn=$(printf '%s' "$badge" | grep -o "versionName='[^']*'" | cut -d"'" -f2)
  abi=$(printf '%s\n' "$badge" | grep "^native-code:" | head -1 | sed "s/^native-code: //" | tr -d "'")
  [ "${vc:-}" = "6" ] && [ "${vn:-}" = "1.6" ] \
    && ok "versionCode=$vc / versionName=$vn" \
    || bad "版本元数据不是 6/1.6（实际 ${vc:-?}/${vn:-?}）"
  nabi=$(printf '%s' "$abi" | wc -w | tr -d ' ')
  [ "${nabi:-0}" -ge 4 ] && ok "native-code ABI $nabi 个：$abi" || bad "ABI 不足 4 个：${abi:-无}（端侧识别运行时没打全）"
else
  bad "缺 aapt2（装 Android build-tools 34.0.0 或设 ANDROID_HOME）"
fi

echo "· 门二 dex 字节断言"
if [ -z "${DEXDUMP:-}" ]; then
  bad "缺 dexdump；兜底：unzip -p '$APK' 'classes*.dex' | grep -ac 'Lcom/ispeech/engine/AssociationCommit;'"
  exit 2
fi
D=$(mktemp -d); trap 'rm -rf "$D"' EXIT
unzip -o -q "$APK" 'classes*.dex' -d "$D"
: > "$D/dex.txt"
for f in "$D"/classes*.dex; do "$DEXDUMP" -d "$f"; done > "$D/dex.txt" 2>/dev/null
info "sha256 $(shasum -a 256 "$APK" | awk '{print $1}') / $(wc -c < "$APK" | tr -d ' ') 字节 / $(ls "$D"/classes*.dex | wc -l | tr -d ' ') 个 dex"

if [ "$APK_MODE" = "probe404" ]; then
  hits=$(grep -c -- "$AC6_PROBE_URL_MARK" "$D/dex.txt" || true)
  [ "${hits:-0}" -gt 0 ] \
    && ok "AC-6 变体：404 探针 URL 在 dex 里（$hits 处）" \
    || bad "AC-6 变体：dex 里没有 404 探针 URL —— 这个包跑不了 AC-6"
  dead=$(grep -c -- "$DEFAULT_MODEL_OBJECT" "$D/dex.txt" || true)
  [ "${dead:-0}" -eq 0 ] \
    && ok "AC-6 变体：正式默认源已被覆盖掉（默认 object key 0 命中；探针与正式源同域，host 子串不作判据）" \
    || bad "AC-6 变体：dex 里同时有正式模型源 —— 覆盖没生效，构建参数没传进去"
  echo
  echo "!! 这是 AC-6 下载失败取证包：模型永远下不下来。禁止进内测通道、禁止覆盖 dist/ispeech-internal-v1.6.apk、"
  echo "   禁止写进 dist/SHA256SUMS 的交付行；单独命名 + 单独登记 sha256。本脚本对它 exit 0 只代表「变体构建正确」。"
  echo "!! 交付文档门禁（配方句 / G6 文本半）在本模式不判：变体包按 runbook §1 第 8→9→11 步与交付包同一出包"
  echo "   commit 构建，那份 README 已在 final 模式那次跑里判过；只跑本模式 = 没跑交付门禁。"
  exit $FAIL
fi

for t in 'Lcom/ispeech/engine/AssociationCommit;' textToCommit suffixPrefixOverlap ASSOCIATION_ANCHOR_CHARS; do
  n=$(grep -c -- "$t" "$D/dex.txt" || true)
  [ "${n:-0}" -gt 0 ] && ok "$t → $n" || bad "$t → 0（VOCA-121 联想去重不在包里）"
done
n=$(grep -c "FUNASR" "$D/dex.txt" || true)
[ "${n:-0}" -gt 0 ] && ok "FUNASR → $n" || bad "FUNASR → 0（C1：语音功能面为零，别上真机，直接判构建不合规）"
n=$(grep -c -- "$DEFAULT_MODEL_HOST" "$D/dex.txt" || true)
[ "${n:-0}" -gt 0 ] && ok "$DEFAULT_MODEL_HOST → $n（默认模型源 = OSS）" || bad "默认模型源缺失"
n=$(grep -c -- "$DEAD_MODEL_HOST" "$D/dex.txt" || true)
[ "${n:-0}" -eq 0 ] && ok "$DEAD_MODEL_HOST → 0（旧源没回潮）" || bad "旧模型源 $DEAD_MODEL_HOST 仍在 dex（$n 处）→ -PasrModelUrl 用了不该用的值"
# G6 的字节那半：dex 只看得出「有没有写死 1.5 的字符串」。判定不在这儿下，与代码半 + README 口径句
# 组成「二选一闭合」（runbook §3），见本脚本末尾的 G6 判决。
v15_dex_hits=$(grep -c "版本 v1.5" "$D/dex.txt" || true)
info "G6 字节半：dex 里「版本 v1.5」${v15_dex_hits:-0} 处（const 内联产物；0 = 硬编码已从字节里消失）"

# ── 交付文档：C5 文本半 + 过期配方句 ────────────────────────────
# 锚 ref 用 SHIP_README_REF 而不是 $BUILD（§13.5 第 1 条）。原因是实测的：origin/main 那份
# dist/README.md 是 v1.5 时代的文档（标题「# 智语 iSpeech 1.5 — 测试安装包（Android）」，全文
# 「1.6」0 命中、「git checkout a9d1dae」0 命中）。出包 commit 若落在 main 谱系上，对它跑这几句
# grep 会同时给出「配方句 PASS」（它本来就不含配方）和「口径句 FAIL」（它本来就不是 v1.6 说明）——
# 「判据不成立」与「判据对象不对」两种语义混在一起，看着像门禁在起作用。所以先用「提不提 1.6」把
# 判据对象钉死，对象不对就直接 FAIL 并说明怎么换 ref，不让它冒充绿灯。
echo "· 交付文档（${SHIP_README} @ ${SHIP_README_REF}）"
g6_doc_hits=0
SHIP_DOC=$(git show "${SHIP_README_REF}:${SHIP_README}" 2>/dev/null || true)
ship_doc_ok=0
if [ -z "$SHIP_DOC" ]; then
  bad "C5 文本半：取不到 ${SHIP_README_REF}:${SHIP_README} → 随包分发那份说明不在这个 ref 上，先定文档再跑门禁（可用 SHIP_README_REF=<ref> 指过去）"
else
  v16_doc_hits=$(printf '%s\n' "$SHIP_DOC" | grep -c '1\.6' || true)
  if [ "${v16_doc_hits:-0}" -eq 0 ]; then
    bad "C5 文本半：锚 ref 指的是 v1.5 那份文档（标题「$(printf '%s\n' "$SHIP_DOC" | head -1)」，全文 0 次提 1.6）→ 判据对象不对，不等于「文档没问题」；用 SHIP_README_REF 指向实际发给内测用户那份"
  else
    ship_doc_ok=1
    info "判据对象已确认：标题「$(printf '%s\n' "$SHIP_DOC" | head -1)」/ 全文提 1.6 共 $v16_doc_hits 处"
  fi
fi
if [ "$ship_doc_ok" -eq 1 ]; then
  recipe_hits=$(printf '%s\n' "$SHIP_DOC" | grep -c -F "$RECIPE_STALE" || true)
  if [ "${recipe_hits:-0}" -eq 0 ]; then
    ok "配方句：说明里已无「$RECIPE_STALE」（0 命中）"
  else
    printf '%s\n' "$SHIP_DOC" | grep -n -F "$RECIPE_STALE" | cut -c1-160 | sed 's/^/           /'
    bad "配方句：说明里仍有 $recipe_hits 处「$RECIPE_STALE」→ 有人照它出包就是一次基线回退（C9 能在包里拦住的，文档这句仍会误导下一次出包）。「只把 \`$RECIPE_STALE\` 换成新基线」这类指令句同样算一处，一并改写成新基线"
  fi

  claim_hits=$(printf '%s\n' "$SHIP_DOC" | grep -c -F "$BAD_VERSION_CLAIM" || true)
  if [ "${claim_hits:-0}" -eq 0 ]; then
    ok "已证伪断言：说明里无「装机后可凭版本号区分」"
  else
    printf '%s\n' "$SHIP_DOC" | grep -n -F "$BAD_VERSION_CLAIM" | cut -c1-160 | sed 's/^/           /'
    bad "已证伪断言：说明里仍有 $claim_hits 处「$BAD_VERSION_CLAIM」→ 应用内显示 v1.5（D-2）已把它打破，与 G6 口径自相矛盾，删掉或按 runbook §3 替换"
  fi

  g6_doc_hits=$(printf '%s\n' "$SHIP_DOC" | grep -c -E "$G6_DOC_RE" || true)
  [ "${g6_doc_hits:-0}" -gt 0 ] \
    && info "G6 文档半：口径句已写（$g6_doc_hits 处命中）" \
    || info "G6 文档半：口径句没写（0 命中，匹配式 $G6_DOC_RE）"
fi

# ── G6 判决：二选一闭合，两条都没走才算 FAIL ────────────────────
# 写成单向「必须含那句」会在 PR #14 进包的那个分支上造一个新假失败 —— 和 a9d1dae 是同一类过期基线。
echo "· G6（VOCA-233 应用内版本显示）闭合判定"
n14_in_pkg=0
if [ "${ispeech_version_hits:-1}" -eq 0 ] && [ "${v15_dex_hits:-1}" -eq 0 ]; then n14_in_pkg=1; fi
if [ "$n14_in_pkg" -eq 1 ]; then
  ok "G6 闭合①：PR #14 生效（app 里 ISPEECH_VERSION 0 命中 + dex 无「版本 v1.5」）→ 应用内显示 = versionName=1.6，不要求 README 写口径句"
elif [ "${g6_doc_hits:-0}" -gt 0 ]; then
  ok "G6 闭合②：显示没修好（ISPEECH_VERSION ${ispeech_version_hits:-?} 处 / dex「版本 v1.5」${v15_dex_hits:-?} 处）但随包说明已写明口径 → 已知显示缺陷，显式接受，不是包不对"
else
  bad "G6 两条闭合路径都没走：显示没修好（ISPEECH_VERSION ${ispeech_version_hits:-?} 处 / dex「版本 v1.5」${v15_dex_hits:-?} 处），随包说明也没写口径句 → 静默通过，测试不判放行"
fi

echo "· 非门禁项（判了也不算，明确不进上面的 exit 码）"
info "C6 真机四件套 = 11-20 装机专员出证、QA 判读；出包侧唯一义务是把最终包 sha256 写进 dist/SHA256SUMS 交付行（上面 info 已给 sha256）"
info "C7 设备接入 / C8 AC-5 真人 10 句时段 = 人类侧场次安排，脚本既不能证真也不能证伪"
info "PR #10 由 draft 转 ready = 项目经理动作（runbook §1 第 0 步），不是包检查"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "门禁 PASS：可以登记 sha256 并交付内测。"
else
  echo "门禁 FAIL：包不合规 —— 不登记 sha256、不发内测、不进装机队列。"
fi
exit "$FAIL"
