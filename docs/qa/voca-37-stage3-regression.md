# VOCA-37 · Stage 3 键盘交互回归报告（模拟器口径）

- **被测构建（第七轮起只有一个口径）**：
  - **被测面 = PR #18 head `78343a4`**（base `fa57a07`，4 笔提交）——第七轮打回项 1 定的冻结面。§3～§6 的判据数字**全部在 `78343a4` 上当场重跑**（`run_r7_all.sh` / `run_r7b.sh`，证据一律改名 `ev_r7_*`），每个读数以 `IDENT … match=True` 署名到这只 apk（逐条目 SHA256 比 `classes*.dex` + `resources.arsc`，装不上这只包就 `GATE reason=installed_apk_not_this_build rc=19` 拒绝出数）。
  - **B 包 = 集成候选 `cffc0cd`**（QA 本地三方合并：PR #18 当时的 head + VOCA-170 `d3b6f84` + VOCA-184 `d283706` + VOCA-207 `4d6363c`）——14:50 追加指令要求覆盖 VOCA-170 / VOCA-203 / VOCA-184 的改动，而这三条 PR 都还没进 main（见下条），所以由 QA 自行合并出包复跑。**B 包不是合并窗口产物**：冲突由我按「后合入者适配先合入者」解决（清单见 §13.1），真实合并窗口的解法若不同，§13 的结论必须复跑。**第六轮那套「A 包 vs B 包」pp / AC-10 对照读数已作废**：被测面换成 `78343a4` 后，同窗对照与目标几何各重跑一份（§5、§4），B 包只保留 D2 的修复验证（§13.3）。
- **⚠ 与派工指令不符的一条事实（需人类确认落地位置）**：指令说「PR #18 已合并、基于当时那个 head」。本轮当场核过的位置里 **PR #18 没有出现在任何主线**：
  - `gh pr view 18 --json state,mergedAt,headRefOid` → `state=OPEN`、`mergedAt=null`、`headRefOid=78343a4`、`baseRefName=main`；
  - `git ls-remote origin refs/heads/main` → `fa57a07`（GitHub 与本地共享仓 `/Users/cookie/workspace/ispeech` 的 main 同一个 SHA）；
  - `git branch -r --contains 78343a4` → 只有 `origin/feat/voca-46-ux-46dp-space-period`（PR head ref `refs/pull/18/head` 同 SHA，但不是分支）；
  - 远端两条 release 分支 `release/voca-186-v1.6`（`eba7a61`）、`release/voca-r1`（`76d178c`）**都不包含** `78343a4`/`35cdbfa`/`d3b6f84`/`d283706`/`4d6363c`（十条 `merge-base --is-ancestor` 全部 rc=1）。
  → 结论：「已合并」在当前可见仓库里无法核实，本文按「未合并」写；若合并发生在另一个远端（Codeup 侧），请给出该远端与分支名，§13 需要在那上面复跑。
- 两个 PR 别混：**PR #18 = A 包的变更集**（本文「变更面内 / 拦路项」所说的「本 PR」都指它）；**PR #25 = 本 issue 的交付 PR**（branch `test/voca-37-stage3-regression`，只含 `docs/` 报告 + `tools/qa/` 工装与证据，不含产品代码）。
- **PR #18 的变更量两种口径并列（裁决 §9.4 打回项 6(a)：禁混用，引用时点名口径）**：
  - **累计口径**（相对 main）：`git diff --shortstat fa57a07...78343a4` → **36 files changed, 1329 insertions(+), 32 deletions(-)**；
  - **单提交口径**：PR #18 现在 4 笔提交（`gh pr view 18 --json commits` → `e528c08`、VOCA-46 时序修复、`35cdbfa`（D1 的键路径接管）、`78343a4`（QA 取证提交）），逐笔 `git show --shortstat`：`e528c08` 15 files +391/−30、第 2 笔 6 files +136/−8、`35cdbfa` 7 files +259/−8、`78343a4` 19 files +557/−0；四笔相加 1343 增 ≠ 1329 增，**差额来自多笔提交改到同一批文件**，不是数据错。`gh pr view 18 --json state,baseRefName,headRefOid,commits` 现算 → `state=OPEN`、`base=main`、`head=78343a4`、`commits=4`。
- 交付物范围（计数口径，别和单个提交混；**第七轮起全部按 `ls | wc -l` 现算，不抄写**）：报告 = 本文件；工装 = `tools/qa/voca-37/`（**26 个脚本：19 个 `.py` + 7 个 `.sh`**）+ `README.md`；证据 = `tools/qa/voca-37/evidence/`（**100 份**：95 份当场 stdout 原文 `.txt` + 4 份 B 包单测 XML + `gradle_test.log`，其中 **22 份 `ev_r7_*.txt` 是第七轮绑 `78343a4` 重跑的采信口径**）+ `shots/`（**82 张**截图/候选带裁图）。**每份证据是采信还是作废，逐文件列在 §11.1，不在别处另立口径。** ，逐文件列在 §11.1，不在别处另立口径。** 三个口径分开写：单个提交的口径（`git show --stat` / `git diff --stat` 当场可核）：返修提交 `57f5f5b` 13 files +597/−35；第二轮返修提交 `4009fb4` 11 files +609/−39。**PR #25 的累计口径不在本文写死数字**——它每推一次就漂一点，写进报告就会立刻变成假数（上一版把「PR 累计 61 files +2029」和「单提交 13 files +597」混在同一行就是这个坑）。现算命令：`gh pr view 25 --json files --jq '{n:(.files|length),add:([.files[].additions]|add),del:([.files[].deletions]|add)}'`；本轮最后一次推送的实测值写在 VOCA-47 的交付评论里（带当时的 head SHA），以那条为准。
- **引用本报告的取证提交时，以「已推送 SHA」为准（打回项 4(a)）**：PR #25 head = `gh pr view 25 --json headRefOid` 现算（本轮写这份时是 `5e8b0f4`），它和本地提交成对：`834acdc` ↔ `9de1345`、`5e8b0f4` ↔ `9081806`。两种形态只差一个文件——那枚 139,171,786 字节的基线 apk 没进推送形态；其余 416 个 blob 逐条目 SHA256 全等（`BLOBS blob_identical=416 blob_differing=0`，`ev_r7_pushed_shas.txt`），**所以「远端拿到的代码」与「我本地跑的那份」等价，但第三方 grep 到的行号要按推送形态算**。本轮第七轮的推送 SHA 在交付评论里给，正文不写死（每推一次会漂）。
- 裁决出处（本文所有「裁决 §x」）：**`docs/requirements/VOCA-47-acceptance-ruling.md`**（洞察BA  authored，第五/六轮的 §9 打回项与 §9.2/§9.3/§9.4 是本轮返修的唯一依据）。该文件原先挂在 `att/` 下、报告引用的路径在任何 ref 上都不存在 → 本轮 `git mv` 到 `docs/requirements/`，内容一字未改（`git log --follow --diff-filter=R` 可核）。
- 规格权威：**唯一锚点 `agent/ux/voca-45-spec` @ `0e4eeb0`**（1025 行，PR #17 head；裁决 §9.1 定「只准锚一个版本」）。本文所有规格行号按它写：AC-1 `:432`｜AC-2 `:446`｜AC-3 `:459`｜AC-4 `:472`｜AC-5 `:486`｜AC-6 `:499`｜AC-7 `:509`｜AC-8 `:524`｜AC-9 `:528`｜AC-10 `:538`（Given `:544`、采样区 `:548`、AC-10a `:560`、AC-10b `:561`）｜**AC-11 `:606`｜AC-12 `:640`**（档2 的耦合条件 `:658`）｜§2.1 让步顺序 `:236`｜§5 无障碍表 `:663`（缺口行 `:669/:670/:673/:675/:677`、合规行 `:674`、已裁行 `:672`）｜§6.5 pp `:679`｜§9.14「未取证几何」`:1025`。逐条锚点的内容证据（旧行原文 vs 新行原文、相似度、四条被 VOCA-464 改写过的正文落点）在 `tools/qa/voca-37/evidence/ev_r7_spec_ux_anchors.txt`（`spec_ux_anchors.py` 现算，24 条）—— 行号不是抄来的，是 `git show` 两边逐行比出来的。旧版 `28c1cac`（806 行，11-16）与其上「602 行翻页版」、`agent/ux/voca-45` @ `fc564aa` 均已过期，本文不再引用。
- 执行环境：本地 AVD `ispeech_test`（1080×2340 / 440dpi / arm64 / 2G），Android 14 模拟器；对照基准为同机搜狗 `com.sohu.inputmethod.sogou/.SogouIME`。真机 `FMR0224913042917` 自 11-13 起未连接（离线档位登记见 VOCA-451 评论）。
- 执行人：测试工程师（QA）· 2026-11-17 03:40–05:20 首测，06:55–07:45 按质检 6 条返修，12:25–12:45 第二轮 4 条，14:03–14:09 第三轮 5 条，15:05–16:20 第五轮（裁决 6 条打回项：重测 AC-1/截字/spec-AC-10、补 spec-AC-9 1500ms 实测档、开关值披露、作废自查），16:50–17:25 集成候选 B 包回归，21:40–22:25 第六轮（pp 同窗重测、闸门与判据解耦后出数、AC-10 离线复算、gradle 原文入库、§8.5/无障碍行号现算），**11-17 23:40–11-18 02:20 第七轮（打回项 1 整轮复跑绑 `78343a4`＋规格重锚 `0e4eeb0`、打回项 2 目标几何 pp/AC-10 实测、打回项 3 接管空格键的回归面 6 档、打回项 4 已推送 SHA 引用＋AC-10a 分档）**（本地时区）
- 结论：**有条件 go —— 变更面内（口径①）P0/P1 已清零；合并窗口（口径②）仍 no-go**。
  - **判定标签（撤回第六轮挂在裁决 §9.3 的那个标签，重新挂）**：`有条件 go（口径① PR #18 变更面：D1 已修并复验、AC-10a 档1 实测 PASS、pp 目标几何 +0.92pp 达标）＋ 合并窗口 no-go（口径②：P0 D2 = v1.6-AC9 / VOCA-170 在 78343a4 上仍当场崩溃，PR #9 未合入）＋ 三项待人类/真机：档2 判据让步未签字（R21）、真机 pp 定稿、repeatCount 与密码框两档设备侧未取证`。
  - ① **原「判据不满足（主因）」两条都翻转了，且是实测翻转不是叙述翻转**：
    - `spec-AC-2` 硬件/注入键路径：`78343a4` 含 `35cdbfa`（接管硬件/注入键空格）后当场转阳 —— 开关 `true` 时 `2x_single_injection wall_between_ms=0 → host='。'`、两次 adb 调用 26ms 同样出句号，500ms 对照组仍 `host='  '`（不是「永远出句号」）；开关 `false` 时同一注入序列 `host='  '`、`takeover=0` —— 原 **P1 D1 关闭**（`ev_r7_ac2_key.txt`、`ev_r7_d1_regress.txt`）。
    - `spec-AC-10a`：规格 `0e4eeb0:560` 已把硬门限改成**按面板逻辑宽度分档**（`≥371 dp` 要求 ≥4、`<371 dp` 要求 ≥3 且 AC-12 溢出可见）。按目标真机几何 1260×2720@520（逻辑宽 **387.7 dp** → 档1）实测：`shijian` `full_count=4 verdict=PASS`、`dashu` `full_count=4 verdict=PASS`；1080×2340@440（392.7 dp → 档1）同样 4 条 PASS。第六轮那句「520dpi 两码全部 3 条 = FAIL」用的几何是 **1080px@520dpi = 332.3 dp**，现实中没有这种屏（规格 §9.14 `:1025` 自己点名），那一档按新规只是**档2**，而档2 的 PASS 需要 R21 人类签字（未签）→ 出数写 `tier_verdict=NOT_SIGNED(AC12_unmeasured+R21_open)`，**不写成 PASS**（`ev_r7_ac10_target_geom.txt`、`ev_r7_ac10_1080.txt`）。
  - ② **原「附加缺口：真机 pp 未定稿」在模拟器侧已被目标几何补上**：同一连续窗口内两侧同测，1260×2720@520 下 iSpeech `h=1168 pp=42.94` vs 搜狗 `h=1143 pp=42.02` → **+0.92pp ≤ 1pp**；第六轮那个 **+7.09pp** 是「1080px 面板 @520dpi」这个不存在几何的产物，随该档一起作废。真机 `FMR0224913042917` 仍未连接，所以 pp 的**定稿权还在真机**，模拟器只能给「目标几何达标、且对 Δpp 的敏感度假设可核」这一层（§5）。
  - ③ **P0 D2（= `v1.6-AC9` / VOCA-170）在冻结的 `78343a4` 上仍然当场崩溃**：进「键盘设置」页 → `FATAL EXCEPTION: MutableStateFlow.setValue on a null object reference`，栈顶 `KeyboardViewModel.kt:35`，pid 31426→31766（`ev_r7_d2_78343a4.txt`）。修复 `d3b6f84` + `KeyboardViewModelInitOrderTest` 只在 `refs/heads/agent/agent/voca-170` 上，`git merge-base --is-ancestor d3b6f84 78343a4` rc=1 → **PR #18 里确实没有它**。口径①不计它（不是本变更面引入），口径②（v1.6 合并窗口门禁）必须计。
  - ④ **接管空格键带来一块新的回归面**（`35cdbfa` 把硬件/注入键也吃进 `handleSpace`，9 条与条件全过才接管）：第七轮按 6 档实测 —— ①开关关=与修复前逐字节同（同注入序列跑两个包逐项比宿主文本）；②英文态空格、T9 数字态空格；③需要自己吃空格的宿主（搜索框、SSID 对话框、密码框）；④长按空格 900ms 触发语音、且接管发生后长按仍出语音；⑤`repeatCount>0` 不接管；⑥接管时每次按键多一次 `getTextBeforeCursor` 跨进程读的代价。原文与逐档判定见 §3.4。
  - 门禁：P0/P1 未清零不判 go —— 口径①（本变更面）**P1 已清、无 P0**；口径②（合并窗口）计 P0（D2，PR #9 未合）→ **合并窗口 no-go**。

---

## 1. 结论先行

| 层 | 判定 | 说明 |
|---|---|---|
| 模拟器可判面（spec AC-3/4/5/6/7、G1–G7、面板路径） | **全绿**（1 项 P3 观察、2 项规格/实现差异记录） | 见 §3–§6；键名↔方向按代码订正（`IspeechImeService.kt:620-622`，行号锚 `78343a4`），步长/钳制结论不变；第七轮在 `78343a4` 当场重跑：`ev_r7_ac47.txt`（24 档 +198px、err≤3.05，右端 cum=4752，左端第 25 档钳制）、`ev_r7_paths.txt` / `ev_r7_paths2.txt` / `ev_r7_paths3.txt`、`ev_r7_vswipe.txt` |
| spec AC-1 恒高（空态 vs **有候选**态） | **PASS**（`78343a4` 8 组 Δ=0） | 第七轮同闸门重跑：440/520dpi × fs1.0/1.15 × `shijian`/`dashu` 全部 `empty=988 comp=988 delta=0`（520dpi 为 1168→1168），三条 Given 逐条点名在 `GATES … spec_anchor=0e4eeb0:543-544`；原文 `ev_r7_fs115.txt`（旧 A/B 包对照那两组已作废，见 §11.1） |
| **spec AC-10a 完整可见候选（规格 `:560`，第七轮起按逻辑宽度分档）** | **档1 实测 PASS；档2 出数但不签字** | 规格 `0e4eeb0` 已把硬门限改成：`≥371 dp` 要 ≥4 条，`<371 dp` 要 ≥3 条**且** AC-12 溢出可见。目标真机几何 1260×2720@520（387.7 dp→档1）实测 `shijian`/`dashu` 各 `full_count=4 verdict=PASS`；1080×2340@440（392.7 dp→档1）同样 4 条 PASS。模拟器 1080 宽 @520（332.3 dp）是**档2**，读数 3 条、`tier_verdict=NOT_SIGNED(AC12_unmeasured+R21_open)` —— 按 R21「签字前档2 不得当 PASS 依据」，这一档既不是 FAIL 也不是 PASS。`ev_r7_ac10_target_geom.txt` / `ev_r7_ac10_1080.txt` |
| spec AC-10b 混合词长（规格 `:561`，只记录不判） | **已出逐项读数** | 目标几何 1260×2720@520：`shijian` `clipped_idx=[]`（4 条全完整）、`dashu` `clipped_idx=[]`；1080@440：`shijian` `clipped_idx=[4]`、`dashu` 无截断。规格 `0e4eeb0:561` 已把旧写的两条出路（缩 chip / 放宽词长）判为**几何上不可达**（现算见规格自身 `:591-595`），4 字以上词的可见性改由 AC-12（`:640`，本轮不测）承接 —— 测试侧不再自处 |
| spec AC-11 带内近垂直短拖误上屏（规格 `:606`） | **N/A（本轮不测）** | 规格自己写明「既有债（`fa57a07` = `78343a4` 同一行为）、不计入 PR #18 拦路面、建议新开 issue 交 Stage 2」；本文 §3.1 该行只标 N/A，其数据（dy 40–90px 上屏）留在 §8-4 作观察项，**不折进 AC-7 判定**（`ev_r6_calibers.txt` ③：两棵树 `grep -c slop` 都是 0） |
| AC-2 双击空格句号（按路径拆开） | **软键 PASS / 硬件·注入键 PASS（第七轮转阳，D1 关闭）** | `78343a4` 含 `35cdbfa`：开关 `true` 时 `2x_single_injection wall_between_ms=0 → '。'`、两次 adb 调用（26ms）同样出句号、3× → `'。 '`；500ms 对照组仍 `'  '`（不是永远出句号）；开关 `false` 时同一注入序列 `'  '`、`takeover=0`。原文 `ev_r7_ac2_key.txt`、`ev_r7_d1_regress.txt` 档②③④ |
| spec AC-9 退格长按（`>3` 字 @1.5s） | **PASS（1500ms 实测档）** | A 包 1500ms→删 13、B 包→删 9，均 >3；旧「1.5s≈10 字」是两点内插值，已标注非实测（§3） |
| `FAST=45ms` 快档 | **维持「本轮未观测」** | 与 AC 判定分开写，指向 §9-7（注入式长按上限落在 90ms 慢档，口径要换） |
| PR #18 变更面内撞出的产品缺陷 | **D1 关闭；D3 重定级为 P2；新增 D4（P3，观测性）** | D1（硬件/注入键路径）由 `35cdbfa` 修好并当场复验（§3.3）；D3 的原「FAIL」 framing 建立在 1080px@520dpi 这个不存在的几何上，按分档后档1 实测 PASS → 建议 P2（档2 未签字 + chip 预算风险），定级权在 PM；D4 = 组合中按硬件空格时日志打 `via=soft`（`IspeechImeService.kt:604` 调 `handleSpace()` 没传 `via`），不影响判定但会误导 D1 类问题的归因（§8 观察项）|
| `v1.6-AC9` = VOCA-170（D2 设置页崩溃） | **P0（继承 PM 11-15 裁定）；在冻结的 `78343a4` 上第四次复现坐实** | `ev_r7_d2_78343a4.txt`：`FATAL EXCEPTION … MutableStateFlow.setValue … null object reference`，栈顶 `KeyboardViewModel.kt:35`，pid 31426→31766。修复 `d3b6f84`+`KeyboardViewModelInitOrderTest` 只在 `refs/heads/agent/agent/voca-170` 上（`git merge-base --is-ancestor d3b6f84 78343a4` rc=1）→ PR #18 里确实没有它；口径①不计、口径②（v1.6 合并窗口门禁）必计 |
| 真机可判面（绝对占屏 pp、520dpi 键高观感、真机手指触摸） | **模拟器侧已按目标几何补齐，真机定稿权仍在真机** | 1260×2720@520 同窗两侧对照：iSpeech `h=1168 pp=42.94` vs 搜狗 `h=1143 pp=42.02` → **+0.92pp**（判据「≤对照+1pp」成立）；第六轮那个 +7.09pp 是 1080px@520dpi 的产物，已作废。真机 `FMR0224913042917` 自 11-13 未连接（§9-1） |
| 发布建议 | **口径①（PR #18 变更面）有条件 go；口径②（v1.6 合并窗口）no-go** | ①：变更面内 P0=0、P1=0（D1 已修并复验），条件是「档2 判据让步待人类签字（R21）」+「真机 pp 定稿」+「repeatCount / 密码框两档设备侧补测」；②：D2（P0，PR #9 未合入 main）计入门禁 → 不得进合并窗口。SRE 与人类评审需同时看到 §9 的清单 |

## 2. GATE 0（前置门禁）

```
$ adb -s emulator-5554 shell ime list -a -s
com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
com.ispeech.ime/com.ispeech.input.IspeechImeService
com.sohu.inputmethod.sogou/.SogouIME

$ adb -s emulator-5554 shell pm list packages | grep -i sogou
package:com.sohu.inputmethod.sogou
```

搜狗可用 → GATE 0 通过，允许做占屏对照。

**第五轮这一节写过一句「本轮所有对照值均为当场重测」，是假的，本轮删除并更正**（裁决 §9.4 打回项 1 的打回点）：那份 §5 表里搜狗的 978/1002 出自首测窗口 03:40–05:20（`git log --diff-filter=A` 追到入库提交 `43e1718`），iSpeech 那两列出同一天的 15:05–17:25 —— **两列不同窗**，而共享 AVD 当天被别的 run 换包 3 次，所以「当场重测」这四个字当时不成立。第六轮用 `ppwin.py` 把两侧拉进**同一个连续窗口**重测（22:02:17 → 22:04:39 +0800，先搜狗三档、再 iSpeech 三档），原文与逐行 `ts=`/`RAW` 见 §5 与 `ev_r6_pp_window.txt`。

## 2.5 作废清单与重测闸门（裁决 §1.2 / §6-打回项 1）

**作废范围扩到两批读数**，同一根因：`CandidateView.kt:76-77` 在候选列表为空时把 `scrollView` 置 `GONE`，于是「敲了码但一条候选都没出」的帧里，候选带上**只剩左侧 chip 的墨迹**；旧工装把 `ink_extent` 打在整个带宽（x 16..1064）上，等于一直在量 chip。

| 作废项 | 旧记载 | 作废原因 |
|---|---|---|
| 旧 §4 三行截字（440/fs1.0、440/fs1.15、520/fs1.15 的 `ink_top/ink_bot/top_pad/bot_pad`） | `ev_fs115_440_fs10.txt` 1400–1441、`ev_fs115_440_final.txt` 1397–1445、`ev_fs115_520_ink.txt` 1185–1284 | 采样窗口含 chip，`cand_count` 未知 → 不能证明量的是候选字身 |
| 旧 §3 的 **AC-1 三组 Δ**（988→988 ×3、988→988、1168→1168） | §3 AC-1 行 | 「空态 → 组合态」的组合态可能是 chip-only 态，恒高契约的另外半边（有候选时仍不涨高）没被证明 |
| 旧 §3 AC-9 行「1.5s≈10 字」 | `ev_ac9.txt` 只有 200/1000/2000 三档 | 两点内插值，非实测（裁决 §6-打回项 6）→ 本轮补 1500ms 实测档 |

**自证（不是叙述，是当场重扫）**：`tools/qa/voca-37/voidcheck.py` 用旧报告的同一窗口（x 16..1064、行=带高 ±1px）重扫交付帧，`old_ink_rows` 与旧记载**逐位相同**（1400..1441 / 1397..1445 / 1185..1284）→ 复算口径对得上；再用新口径（只在 chip 右沿 +1 之后量字身）扫同一批帧，`cand_counts = 440/fs1.0: 0、440/fs1.15: 0、520/fs1.15: 1`，`ink_entirely_inside_chip = 440/fs1.0, 440/fs1.15` → 三帧全部低于**当时那版**「可见候选 ≥4」闸门，**作废成立**（`ev_r5_voidcheck.txt`，rc=0）。裁决 §6-3 要求的「`x>240` 命中列必须 > 0」在旧帧上是 0 —— 也就是旧数据确实还在量 chip。（第六轮把 `≥4` 从闸门改回判据，这条作废结论**不受影响**：作废的根据是「旧数据量的是 chip」，不是「候选不足 4」。）

**重测闸门（先于读数，任一不成立就拒绝出数）** —— 第六轮按裁决 §9.2 把**闸门与判据解耦**后的版本，`fs115.py`（修前 `MIN_CAND=4`、修后 `MIN_CAND=1`）：

闸门只守规格 `0e4eeb0:544` 那三条 **Given**，一条不加、一条不减：

1. `drive.key_map()` 从当场截图反推键位（不写死像素），失败 → `GATE reason=key_map_fail rc=16`；宿主起不来 → `rc=18`（这两条是 Given 之外的**工装前提**，不是规格 Given，单列写明）；
2. **Given ①** 敲码后**回读宿主文本 == 所敲串**（chip 是像素，宿主回读是它唯一的可读通道），不符 → `reason=readback_mismatch rc=10`；chip 盒不在位 / 不在候选区左 → `rc=11` / `rc=12`；
3. **Given ②** 候选行在位 = `cand_count ≥ 1`（chip 右沿 +1 之后的字身连通块），有 chip 无候选 = **chip-only 伪装态** → `reason=chip_only rc=13`；
4. **Given ③** 候选池 ≥ 8（`=` 键滚动 + 互相关去重数出）→ 否则 `rc=14`；滚动后未回原点 `rc=15`；IME 不是本包 `rc=17`（`ime_frame()` 前后各读一次 `default_input_method`）；
5. 闸门全过才打一行 `GATES`（把三条 Given 逐条点名，带 `spec_anchor=0e4eeb0:525`），再打 `RESULT` / `INK` / `CAND` / `AC10` / `AC10b`；拒绝出数时 **stdout 不出现 `RESULT`/`INK`/`delta`**。

**「可见候选 ≥4」从闸门里删掉了**（第五轮那一版把它写在闸门第 4 条，裁决 §9.2 判为「拿结论当前置条件」）：`≥4` 是 AC-10a 的**判据**，留在判据行里。这么改不是放松标准，恰恰相反 —— 塞在闸门里时这一档只会「不给数」，**永远出不了 FAIL**，而池 ≥8 已经在闸门里，词库够用、屏上凑不满 4 条本来就是 FAIL 本身。同时 `rc=11` 与 `rc=13` 的语义必须分开：11 = 连 chip 的填充盒都没画出来（帧不对/带没在位），13 = 有 chip、候选 0 条；`band.py:113/188` 上 `cand_count==0` 的帧 `analyze` 照样返回 `ok=True`，所以第 13 这一档不许删，否则 chip-only 会一路走到 `pool_low`(14)，失效原因从「量错对象」被说成「词库不够」，第三人照 §10 复跑必读错。

**520dpi 的 `dashu` 那一组：解耦后出数了，读数是 FAIL**。第五轮它停在 `GATE reason=cand_low rc=13 cand_count=3 need=4 x0=227`（`ev_r5_520_fs115.txt`、`ev_r5u_520.txt`），两包一致、整档不给数；第六轮同一台机同一包重跑（`fs115.py <apk> 520 1.15 shijian,dashu a6`）打满六行，其中裁决 §9.2 点名要的那两行是：

```
AC10  code=dashu dens=520 fs=1.15 given3_pool=13 … full_count=3 verdict=FAIL need=4 gauge=spec_sampling_x_end=1060 full_count_screen=3 verdict_screen=FAIL boundary=False cand_count=3 …
AC10b code=dashu dens=520 fs=1.15 cand_count=3 full_count=3 need=4 recorded=True reason=long_word_budget clipped_idx=[] …
```

原文与两次尝试（第一次 `dashu` 吃 `reason=readback_mismatch rc=10`，`got=<NONE>`，共享 AVD 掉焦点；第二次重跑才出数）都在 `ev_r6_ac10_decoupled.txt`，**两次都入库、不粉饰**。同帧 Given ③ 在同密度当场量到 `pool=13` → 「词库里没有 4 条」这个解释被当场排除。

**工装共因缺陷（裁决 §6-6，本轮修在工装层，不动判据）**：`drive.tap_keys` 原来既没回读校验、又写死 440dpi 像素，会污染一切「先打字再量」的用例。现在 `tap_keys` 敲完必须回读宿主文本比对，不一致直接 GATE；`fs115.py`/`ac9.py`/`ac2.py` 的键位一律 `key_map()` 当场反推。**第五轮新增一条同类修复**：VOCA-184（`d283706`）把字母键行内留白从 4dp 收到 1dp 后，行间隙只剩 ~5px，`key_map` 原来的 `merge_gap=8` 会把 qwerty 行和 asdf 行粘成一个带 → 段数不再是 10/9/7 → 整套键位反推失效（B 包首跑 `GATE reason=key_map_fail rc=16`）。已加「合并后高度不得超过 52dp 单行上限」的约束（`drive.py:219-229`），A/B 两包都能标定成功。**这条是合并窗口会踩的坑，不是产品缺陷**，但必须写进 §13。

## 3. 恒高与判据逐条结果（按规格 §4 编号重排）

编号与行号一律锚规格唯一权威 `docs/keyboard-ux-spec.md` @ **`0e4eeb0`**（PR #17 head；裁决 §9.1）：AC-1 `:432`｜AC-2 `:446`｜AC-3 `:459`｜AC-4 `:472`｜AC-5 `:486`｜AC-6 `:499`｜AC-7 `:509`｜AC-8 `:524`｜AC-9 `:528`｜AC-10 `:538`（Given `:544`、采样区 `:548`、AC-10a `:560`、AC-10b `:561`）｜**AC-11 `:606`**。G1–G7 是质检门禁项、不是规格 AC，另表（§3.2）。**第五轮及以前这一行锚的是 `28c1cac`（AC-1 写 `:396`、AC-2 `:410`…），那套行号在 `0e4eeb0` 上全部左移，不得再引**；AC-10 在 `28c1cac` 上根本不存在（它由裁决 §5 于 11-17 升格、在同日 `0e4eeb0` 落进规格正文），所以第五轮「AC-10 由裁决 §5 升格、规格正文 `:215` 那句的副作用约束」那种写法也一并作废——判据出处现在是规格 `:538`–`:561` 本身。

测量口径（全部按规格 §8.1 / §8.4）：`dumpsys window displays` 的 `InsetsSource type=ime` frame 取键盘高度；滚动位移用候选带列剖面互相关（step=2px，±600，取最小平均绝对误差，residual>5 视为不可判）；文本真值取宿主输入框 = Settings 搜索框 `open_search_view_edit_text`（由 `drive.host_ready()` 每轮 force-stop 两个 Settings 包后重起，空态读到的是它的占位文案「搜索设置」而不是输入内容）。测前一律 `settings put secure autofill_service null`（否则 GMS 自动填充会多报一个 `type=ime` source，把 inset 从 988 抬到 1260）。

**「A 包实测」的读数以 stdout 为准，帧另说（裁决 §9.4 风险 1，采纳项）**：§3～§4 每一个 A 包数字的可复核来源是对应的 `ev_*.txt` 原文行，**不是截图**——第五轮那批 A 包 440dpi 候选带帧被 B 包同名复跑覆盖（事故记录与影响面在 §13.5），当时入库的只有 `shots/r5_440_fs1.0_haizi_*.png` 两张 A 包帧，所以「帧未入库、读数以 stdout 为准」这一句在 §3/§4 引用旧帧时必须成立。第六轮起**两包都留帧**：A 包 `shots/r5_a6_520_fs1.15_{shijian,dashu}_{empty,comp}.png`（4 张）＋ B 包 `shots/r5u_*.png`（12 张）＋ 同窗 pp `shots/r6_{sogou,ispeech}_*.png`（6 张），并且 `fs115.py` 加了第 5 位 `label`（§10-2）防再撞名。

**键名↔方向的口径（本轮返修项 2 的根因）**：不靠人记，由代码定义——`IspeechImeService.kt:620-622`（行号锚 **78343a4**；main `fa57a07` 上同一段在 `:601-603`。偏移已核：PR #18 在该文件 +90/−20，前 200 行内插了 11 行（`:11`/`:20` 两个 import + `:107` 3 行 + `:195` 6 行），601+11=612 对得上——引行号不写构建版本就会核空）里 `KEYCODE_MINUS` → `scrollCandidates(-1)`、`KEYCODE_EQUALS` → `scrollCandidates(+1)`；`CandidateView.kt:90-92,199` 里 `scrollCandidates(steps)` → `smoothScrollBy(steps * dp(72), 0)`，注释写明「steps 为正向右（看后面的候选）」。互相关返回值 `shift` 的符号约定是「正 = 候选内容左移 = 视口向右」，所以 `=` 应为 +198、`-` 应为 −198，实测与代码同向。`ac47.py` 把 keycode、代码出处、期望档位（`KEYMAP` 行）和每一档的实际符号一起打进 stdout，杜绝再次出现「数值对、键名反」。
   **口径不靠叙述，靠闸门**（本轮实测补上，见 `ev_ac47_probe.txt` / `ev_ac47_falsify.txt`）：`ac47.py` 在 sweep 前先做可滚性探针——停在左端按一次 fwd 方向的键，位移必须是 +198；把参数对调成 `--fwd 69 --rev 70` 再跑，同一台机同一数据态下探针读到 `shift=0 err=0.0`，脚本直接 `GATE reason=probe_zero rc=4 blocked=…` 退出，**不再产出那种「看着像到端钳制、其实是恒 0」的 sweep**。闸门拒绝出数时**每种前提失效给一个 `reason=` 和一个退出码**（4=probe_zero / 5=host_buffer / 6=bg_low / 7=frame_none / 8=host_not_ready）——上一版三种前提全退 4，而「宿主缓冲有残留」那种还**不打 `PRE_PROBE` 行**，第三人照 §10 复跑就会把「工装没跑起来」读成「证伪成立」；本轮已把两种失效各复跑一份（`ev_ac47_gate_probe_zero.txt` / `ev_ac47_gate_host_buffer.txt`），§10-4b 也补了跑前复位（13:06 我第一次跑对调档时闸门还没上，拿到的就是一份全 0 的假钳制输出，已删）。同时补底色闸门：`ime_frame()` 报 988 不代表键盘画在 inset 上（别的 run 收掉键盘后 inset 会残留），候选带底色对本包 `#F4F5F7` 的命中率低于 50% 即拒绝出数。

### 3.1 规格 §4 判据逐条（编号 = 规格编号，行号 = `0e4eeb0` 规格行号）

| 规格判据（行号） | 期望 | 实测 | 判定 |
|---|---|---|---|
| **AC-1** 候选条空态高度不变（`:432`） | 空态 → **有候选态** Δ=0 | **第七轮在 `78343a4` 整节重跑**（`fs115.py $FIX 440,520 1.0,1.15 shijian,dashu A7`）：8 组空态→有候选态全部 Δ=0 —— 440/fs1.0 `shijian`、`dashu` 988→988；440/fs1.15 两码 988→988；520/fs1.0 两码 1168→1168；520/fs1.15 `shijian`、**`dashu`** 1168→1168（`dashu` 这一档自第六轮闸门解耦后一直出数，本轮仍在 `78343a4` 上出数）。每组先过 §2.5 三条 Given 闸门（`GATES` 行：`given1_chip_equals_code` / `given1_readback` / `given2_candidate_row_present` + `cand_count` / `given3_pool_at_least` + `need_pool`），8 组闸门 rc 全 0；本轮起每行带当场 `ts=`（`drive.py` 的 `log()` 改成逐行打时刻，此前只有文件头一个时刻）。**B 包那 6 组（952/1128）作废** —— 其改动已在 `78343a4` 里 | **PASS**（`ev_r7_fs115.txt`，192 行，逐行 `ts=`、iSpeech 侧 `IDENT … match=True`）。旧 A 包 `ev_r5_440.txt` / `ev_r5_520_fs115.txt`、B 包 `ev_r5u_440.txt` / `ev_r5u_520.txt` 自本轮起**作废**（逐文件口径见 §11.1），520/`dashu` 的 Δ 不必再去 `ev_r6_ac10_decoupled.txt` 绕一手 |
| **AC-2** 双击空格出句号（`:446`）**软键路径** | 窗口内第二次空格出「。」 | **`78343a4` 当场复跑**（`ev_r7_ac2_soft.txt`，`ac2.py … 440 soft`，每行带 `ts=`）：单进程两连点（`wall_between_ms=0(single_shell)`）→ `host='。'`；两次 adb 调用（`wall_between_ms=44.3`）→ `host='。'`；间隔 538.2ms 对照组 → `host='  '`（窗口外，语义正确）；三连点 → `host='。 '`。同轮 `SWITCH want=ON state=True src=datastore raw_hex=12020801 route=ime_panel@1652`（开关键从「文件不存在」被面板写成 27 字节 pb）、首尾 `IDENT … match=True`。**第六轮那组的 68.2ms／577.1ms 出自旧 head 的跑，本轮只作对照**| **PASS**（软键路径与被测 head 无关，`35cdbfa` 之前也是阳；换包后结论一致）。三项披露见 §3.1a |
| **AC-2** **硬件·注入键路径** | 同上 | **`78343a4`（PR #18 head，含 `35cdbfa`「硬件/注入键的空格进 handleSpace」）当场复跑，4 组全转阳**（`ev_r7_ac2_key.txt`，`ac2_rc=0`）：单进程两连发（`wall_between_ms=0`）→ `host='。'`；两次 adb 调用（`wall_between_ms=26.0`）→ `host='。'`；间隔 537.4ms 对照组 → `host='  '`（窗口外，语义正确）；三连发 → `host='。 '`。同轮 `MODE path=keyevent mode=chinese host='中国'`、`SWITCH want=ON state=True src=datastore raw_hex=12020801`、首尾两端 `IDENT … match=True`。**第六轮那批阴性读数（`␣␣`、`␣␣␣`）出自 `35cdbfa` 之前的旧 head，本轮作废** | **PASS（D1 在本被测面已修复并复验）** —— 接管面的回归另见 §3.4（6 档），根因与闭环过程见 §3.3-D1。证据 `ev_r7_ac2_key.txt` |
| **AC-2** 反例（开关关，两路径各一组） | 只出空格 | **`78343a4` 当场**：面板开关 `TOUCH y=1652 diff={'double_space_period': False}` → `SWITCH want=OFF state=False src=datastore raw_hex=12020800`（键存在且值为 false，不是「没写过」）→ 硬件键单进程两连发 `host='  '`（只出空格，接管没发生）→ `FINAL_SWITCH state=False`。软键反例同样 `␣␣` | **PASS**（`ev_r7_ac2_key.txt` / `ev_r7_ac2_soft.txt`）。接管在开关关闭时是否与修复前逐字节相同，另见 §3.4 档①（实测 5/5 `identical=True`） |
| **AC-3** 双击空格不与「上屏首选」打架（`:459`） | 组合非空时首格上屏首选、不出「。」 | 打 `shi`→首格=`是`；再按一次=`是疑是地上霜`（继续上屏联想/候选，未插句号） | **PASS**。三项披露见 §3.1a |
| **AC-4** 连续横滚生效（`:472`，用硬件键步长定标） | `=`（KEYCODE_EQUALS / 70）→ `scrollCandidates(+1)` = +198±6px（+72dp @440） | **第七轮在 `78343a4` 重跑并逐行打时刻**（`ev_r7c_ac47.txt`，02:27:36→02:29:22 单窗口）：`KEYMAP` 行把 keycode↔方向钉在代码出处 `IspeechImeService.kt:620-622 + CandidateView.kt:90-92,199`；连按 `=` 的 40 个 `AC4_STEP` 档里，非零档全部 `shift=198`（err ≤3.05，cum 4752），第 25 档 `shift=140 err=5.04` 按自家口径不可判（快到位时 `smoothScrollBy` 被截短），`press=26` 触发 `CLAMP_AT`（连续 3 次位移 0 = 到端钳制） | **PASS**（`ev_r7c_ac47.txt`：`SUMMARY right_end_cum_px=4752 left_clamp_press=25`，首尾 `IDENT stage=start/end … match=True head=78343a4`）。旧 `ev_ac47b.txt` 作废为本文件的对照 |
| **AC-5** 左右边界不循环（`:486`） | `-`（KEYCODE_MINUS / 69）→ −198px，到端钳制 | 同窗口反向：`AC5_REV` 单按 `keyevent69` `shift=-198 err=2.57`，随后 23 档 −198 + 末档 −140（err 4.18）→ cum −4694，`LEFT_CLAMP_AT press=25`，之后 19 档恒 0（**不绕回**） | **PASS**（`ev_r7c_ac47.txt` 的 `AC5_REV_STEP i=1..44` 全带 `ts=`）；cum 记账口径见 §3.1b |
| **AC-6** 起手区约束：带外手势不得驱动横滚（`:499`） | 带外位移=0 | 用旧「G4」行数据（裁决 §3 指定归属）：键区横滑 shift=0（err 0.0）；工具栏横滑 shift=0（err 0.0）；起点在候选带**下沿外 23px**（y=1500）竖滑 dy=60 → shift=0、不上屏 | **PASS（范围限定）**（`ev_ac47b.txt`、`ev_vswipe_c.txt`）。旧 §3「AC-6 边界不绕回」那一行按规格编号属于 **AC-5**，其 cum 记账口径说明保留在 §3.1b |
| **AC-7** 竖向/斜向不误滚、不误上屏（`:509`） | 滚动态下纯竖滑不驱动横滚；斜滑方向与直接操作一致 | `ev_vswipe_a.txt` 的 `commit_dy200`（dy=200 → 不上屏、不横滚）+ `ev_vswipe_b.txt` 的 `dy110_dx160`（斜滑 −264px、err 4.37 可判）——**幅度口径替代**：规格写的是「小幅度竖滑/斜滑」，本轮可判样本落在 dy=200（大幅度不上屏）与 dy=110/dx=160（斜滑方向），没有 dy<90 的可判横滚样本 | **PASS（幅度口径替代，n=1 的斜滑只够定方向）** |
| **AC-8** 按键音效（`:524`） | 本期不采纳、无验收项 | 不测 | **N/A**（规格原文即「本期不采纳」） |
| **AC-9** 长按连删保留（`:528`，`:533-535` 判据「按住 1.5s → 删除字符数 > 3」） | 两句分开判 | ① **1.5s → >3 字：PASS（实测）**。A 包 `ac9.py`：200ms→1、1000ms→7、**1500ms→13**、2000ms→21（`rate_ms` 200.0/142.9/115.4/95.2）；B 包：200→1、1000→8、**1500→9**、2000→17。旧 `ev_ac9.txt` 只有 200/1000/2000 三档，当时写的「1.5s≈10 字」是**按现有两点内推、非实测读数**，本轮已补 1500ms 实测档取代它。② **阶梯单调/加速关系**：两包都成立（越按越快、删除数随时间超线性）。判据用的宿主文本是**已上屏中文**（逐码 space 上屏首选），因为组合缓冲里的退格走删 composing 的另一条路径，不是 AC-9 说的「删除字符」 | **PASS（实测）**（`ev_r5_ac9.txt`、`ev_r5u_ac9.txt`）。工装披露：`PROBE_RETRY first_deleted=0` —— 注入式同点 `input swipe` 长按偶发丢手势，脚本先做一次 200ms 探针（必须恰好删 1 字）才允许后续档位出数，长度一律连读稳定值（`hold()`） |
| **AC-10a** 完整可见候选 ≥4（规格 `:560`，硬门限） | 按 `0e4eeb0:560` 的**分档判据**：档1（逻辑宽 ≥371.0dp）前 4 条完整可见；档2 ≥3 条 + AC-12 让步（**签字前不得记 PASS**，R21） | **第七轮把这条从「一个数」升级成「判据 + 算式 + 实测」**：档位由逻辑宽算出（面板宽 ÷ (dpi/160)），门限只有 `band.TIER_SPLIT_DP=371.0` 一份（`ac10_budget.py` 在 PR #17）。<br>· **目标真机几何 1260×2720@520 → 387.7dp = 档1**：`full_count=4` → **PASS**（打回项 2(b) 实测，`ev_r7_ac10_target_geom.txt`）。<br>· 模拟器 1080×2340@440 → 392.7dp = **档1**：fs1.0/fs1.15 四组 `full_count=4` → **PASS**（`ev_r7_fs115.txt` 的 `RESULT`/`CAND`、`ev_r7_ac10_1080.txt`）。<br>· 模拟器 1080×2340@520 → **332.3dp = 档2**（「1080 宽配 520dpi」现实机型里没有）：`shijian full=3 cand=4`（第 4 条 `badge_clipped=True`、字身只剩 9px）、`dashu full=3 cand=3` → 按档2 判据是「≥3 + AC-12 让步」，`verdict=AWAITING_SIGNOFF`，**本轮既不写 PASS 也不写 FAIL**（`band.R21_SIGNED=False`）。<br>· 行都带 `tier=`；读数两路互证（设备当场 `fs115.py` ＋ 交付帧离线重算 `ac10_offline.py`）；双口径（规格采样区 `x_end` vs 屏宽）在全部帧同判 `boundary=False` | **档1（含目标真机几何）PASS／档2 待签字 `AWAITING_SIGNOFF`** —— 第六轮「440 PASS / 520·fs1.15 FAIL → D3」那套判定口径**作废**：它拿一个不存在的几何（332.3dp）去套档1 硬门限。D3 降级 P2 的说明见 §3.3；`ev_r7_ac10_target_geom.txt`、`ev_r7_ac10_1080.txt`、`ev_r7_fs115.txt`（旧 `ev_r6_ac10_*.txt` 只作历史对照） |
| **AC-10b** 混合词长（含 4 字长词，规格 `:561`） | 只入账不判（逐项 x0/x1/墨迹/pitch 全记） | **每一帧都出 verdict，一帧不落**，`AC10b` 行永远跟着打并给归因：440/fs1.0 `dashu` idx1 是 3 字词（`glyph_w_dp=68.4`、pitch 278）、440/fs1.15 idx1 `75.6`/pitch 298 仍完整可见 → `reason=meets_threshold`（440 档长词不破坏 ≥4）；**520/fs1.15 `shijian`** `clipped_idx=[3]` → `reason=viewport_clip`（第 4 条被视口右界切掉）；**520/fs1.15 `dashu`** `clipped_idx=[]`、三条都在位 → `reason=long_word_budget`（词长预算装不下 4 条）。A、B 两包 8 帧的 `reason` 全部落在 `meets_threshold` / `viewport_clip` / `long_word_budget` 三值内 | **记录**（`reason` 是「为什么不足 4 条」的归因，**不是免判通道**）：第七轮起归因不改变档位判定 —— 档2 的 `viewport_clip` / `long_word_budget` 两种成因都是「AC-12 让步要不要签」的输入。缩 chip 宽度 vs 放宽词长口径（`0e4eeb0:236` 让步顺序、`:672`「三角无解时以可读性优先」）归 UX/UI + PM，不由测试自处。原文 `ev_r7_ac10_1080.txt`、`ev_r7_ac10_target_geom.txt` |
| **AC-11** 带内近垂直短拖不得上屏（规格 `:547`，2026-11-17 立条） | 带内 dy 40–80px 的近垂直拖动什么都不做 | **本轮不测**（规格原文即「本轮不测」）。既有债的依据当场核过：`git show fa57a07:...CandidateView.kt \| grep -ci slop` = **0**、`git show 78343a4:...` 同样 = **0** → 竖向 slop 判定在 main 与 PR #18 上都不存在，同一处代码、同一行为（`ev_r6_calibers.txt` ③）。已实测到的数据（dy=40/60/80/90 上屏、阈值 90–100px ≈33–36dp）**留在 §8-4 作观察项，不折进 AC-7 判定行**——规格 `:503` 明确「AC-7 的判据对象只有落点出带那一档，带内短拖另立 AC-11，不得据此判 AC-7 FAIL」 | **N/A（本轮不测，既有债，不计入 PR #18 拦路面）**；修复口径（自加竖向 slop 判定）与用例交 Stage 2，规格 `:407` 已建议新开 issue |

#### 3.1a AC-2 / AC-3 的三项披露（裁决 §4 / 打回项 4）

拼音态（非五笔态）取证的适用性裁决要求把这三条写死在判据旁边，**前四轮一直没给全 `chinesePunctuation`**：

1. **实测状态行原文**：`MODE path=keyevent mode=chinese host='中国z'`（A 包 07:32–07:35）、`MODE path=keyevent mode=chinese host='z中国'`（B 包 17:20）——判据是行为判据（空格后宿主出现汉字 = 非英文态），不是「宿主是字母就英文态」。
2. **两个短路开关的当场读值**（`dsdump.py`，`ev_r5_switches.txt`）：`chinese_punctuation=True source=code_default(never_written)`、`double_space_period=False source=code_default(never_written)`；同轮 `DS ls -R files` 里有 `cell_dicts` 却没有 `datastore/ispeech_settings.preferences_pb` → 不是 `run-as` 失败，而是**该键从未被写过、DataStore 文件尚未创建**，取的是代码默认值（`KeyboardSettings.kt` 默认 `chinesePunctuation=true`）。AC-2 那批正例当时的原始字节 `raw_hex=12020801` 只写了 `double_space_period`（27 字节 blob，`ev_ac2_hw.txt`/`ev_ac2_soft.txt`）→ 同一时刻 `chinesePunctuation` 同样是「未持久化 = 默认 true」，两个口径一致。
3. **代码依据**：`SpaceKeyPolicy.decide(state, settings, now)`（`SpaceKeyPolicy.kt:72-94`，A/B 包同码——`cffc0cd` 未触碰该文件）的短路条件在 `:83`：`if (!settings.doubleSpacePeriod || !settings.chinesePunctuation || state.englishMode) return SPACE`——**参数表里没有 `ChineseInputMethod`，也没有五笔/拼音分支**；句号常量在 `IspeechImeService.kt:108` `DOUBLE_SPACE_PERIOD_TEXT = "。"`（同样与输入法类型无关）。
4. **失效条件（写死）**：一旦 `decide` 引入 `ChineseInputMethod` 分支（五笔/拼音走不同判定），本裁决作废，必须补五笔态复跑。

#### 3.1b AC-5/AC-6 的 cum 记账口径（原「AC-6b」行，归属规格 AC-5）

**cum 是设备数据态相关的量，不是常量**：`ac47.py` 的 `sweep()` 只累加 `verdict=ok`（err≤5）的档位。右端 cum=4752（24×198）之外还有一档真实发生但被剔除的 +140（i=25、err=5.05）；左端 −4694（23×−198 加末档 −140、err 4.18 已计入）之外，sweep 前还有一次 `AC5_REV` 单按 −198。补回被剔除档位后两端严格等距：右 4752+140 = **4892**、左 198+4694 = **4892**，与旧 `ev_ac47.txt` 的 `CLAMP_AT cum_px=4892` 同值——两批测的是同一个物理量，差别只在记账口径。上一版「差 58px 来自末档截断 + 2px 量化」作废：58 = 198−140，就是记账差的算术结果，「2px 量化」没有出处。13:11 复测（`ev_ac47_probe.txt`，未 pm clear）右端 cum=2970、15 档到端，与 12:31 那轮 4752/24 档不同；跨 run 可比的是「每档 198±6px」与「到端后恒 0」，不是 cum。**「不绕回」的判定不依赖 cum 绝对值。**

### 3.2 G1–G7 门禁项（质检门禁，不是规格 AC）

| 门禁 | 期望 | 实测 | 判定 |
|---|---|---|---|
| G1 无 `N/M` 页码文案 | 不出现 | **第七轮在 `78343a4` 当场重 dump**（`aapt2 dump resources` rc=0）：`grep -ci candidate_page` = **0**；源码面 `git grep -iE '第.*页'` 命中 **0** → 候选条没有页码绘制路径（`ev_r7_g1_g3.txt`） | **PASS** |
| G3 资源表无翻页控件 | 不出现 | 同一次 dump：`candidate_pager` / `pager_button` / `page_indicator` 命中 **0**，全量只命中 `drawable/ic_settings_gear`（工具栏「设置」键，与候选条无关）（`ev_r7_g1_g3.txt`） | **PASS** |
| G4 带外手势不驱动滚动 | 位移=0 | 数据已按规格编号归入 **AC-6**（§3.1，本轮在 `78343a4` 重跑）；旧「候选条竖滑/斜滑 shift=−870」按 residual>5 口径**作废** | **PASS（范围限定）** |
| G6 编辑后偏移复位 | 归零 | **第七轮重跑**：滚动态下逐对给 `AC6_TAIL diff_bbox`，`AC6_TAIL_SUMMARY identical_pairs=4`（右端侧）/ `2`（左端侧）→ 编辑宿主后帧与基线帧一致（`ev_r7c_ac47.txt`，每行带 `ts=`） | **PASS** |
| G2 / G7 | 只记用户可见影响 | 见 §8 | 记录 |

**键名↔方向的口径**：不靠人记，由代码定义——`IspeechImeService.kt:620-622`（行号锚 **78343a4**；main `fa57a07` 上同一段在 `:601-603`。偏移已核：PR #18 在该文件 +90/−20，前 200 行内插了 11 行（`:11`/`:20` 两个 import + `:107` 3 行 + `:195` 6 行），601+11=612 对得上——引行号不写构建版本就会核空）里 `KEYCODE_MINUS` → `scrollCandidates(-1)`、`KEYCODE_EQUALS` → `scrollCandidates(+1)`；`CandidateView.kt:90-92,199` 里 `scrollCandidates(steps)` → `smoothScrollBy(steps * dp(72), 0)`，注释写明「steps 为正向右（看后面的候选）」。互相关 `shift` 的符号约定是「正 = 候选内容左移 = 视口向右」，所以 `=` 应为 +198、`-` 应为 −198，实测与代码同向。`ac47.py` 把 keycode、代码出处、期望档位（`KEYMAP` 行）和每档实际符号一起打进 stdout，杜绝「数值对、键名反」。
   **口径不靠叙述，靠闸门**（`ev_ac47_probe.txt` / `ev_ac47_falsify.txt`）：`ac47.py` 在 sweep 前先做可滚性探针——停在左端按一次 fwd 方向的键，位移必须是 +198；把参数对调成 `--fwd 69 --rev 70` 再跑，同一台机同一数据态下探针读到 `shift=0 err=0.0`，脚本直接 `GATE reason=probe_zero rc=4` 退出，**不再产出那种「看着像到端钳制、其实是恒 0」的 sweep**。每种前提失效给一个 `reason=` 和一个退出码（4=probe_zero / 5=host_buffer / 6=bg_low / 7=frame_none / 8=host_not_ready）——上一版三种前提全退 4，而「宿主缓冲有残留」那种还**不打 `PRE_PROBE` 行**，第三人照 §10 复跑会把「工装没跑起来」读成「证伪成立」；两种失效各复跑一份（`ev_ac47_gate_probe_zero.txt` / `ev_ac47_gate_host_buffer.txt`）。底色闸门：`ime_frame()` 报 988 不代表键盘画在 inset 上（别的 run 收掉键盘后 inset 会残留），候选带底色对本包 `#F4F5F7` 命中率低于 50% 即拒绝出数。

### 3.3 缺陷单（D1 **已闭环** · D2 P0=VOCA-170（修复未合入本被测面）· D3 降级 P2 · D4 第七轮新增 P3）

**D1 · P1 → 已闭环（第七轮在被测面 `78343a4` 复验转阳）· AC-2 双击空格句号在硬件/注入键路径不生效**

- **原始缺陷（第一~六轮记录，保留以便复核）**：环境 `emulator-5554` / 旧 head / 440dpi / fs1.0 / 中文拼音态、开关 `double_space_period=true`（`raw_hex=12020801`）。复现：`adb shell input keyevent 62 62` → 宿主 `␣␣`；两次 adb（39.1ms）→ `␣␣`；三连发 → `␣␣␣`。期望（AC-2）`。`、实际 `␣␣`。根因：`onKeyDown` 对 `KEYCODE_SPACE` 在 `buffer.isEmpty() && !aiReplyActive && !associationActive` 时一律 `return false`，事件不进 `handleSpace()`，`lastSpaceCommitAt` 永远 null；软键走 `KeyAction.SPACE`（`:458`）直接调 `handleSpace()`，所以只有软键生效。判 P1 的理由（规格 AC-2 的 When 没限定软键、无障碍条款要求外接键盘同路）不变。
- **修复落在本被测面**：PR #18 第 4 个 commit `35cdbfa`「硬件 / 注入键的空格进 handleSpace，双击句号在外接键盘成立（VOCA-465 D1）」。改动面：`IspeechImeService.kt:599` 加 `takesHardwareSpace(event)` 闸门 → `:600` `handleSpace(via = "key")`；判定本体是新纯函数 `SpaceKeyPolicy.kt:116-125` 的**九项合取**（非自动重复、非 T9 待选、非组合中、非 AI 回复、非语音、非联想、开关开、中文标点开、非英文态）；`78343a4` 的 `git show --stat 35cdbfa` = 7 文件 +259/−8（产品码只动 `IspeechImeService.kt` +54、`SpaceKeyPolicy.kt` +31，其余是单测、README、check_crosscut）。
- **第七轮复验（转阳，当场窗口 02:27 前）**：`ev_r7_ac2_key.txt` 4 组全阳 —— 单进程两连发（`wall_between_ms=0`）`host='。'`、两次 adb（26.0ms）`host='。'`、537.4ms 对照组 `host='  '`（窗口外，语义正确）、三连发 `host='。 '`；开关关掉后同路径 `host='  '`（`FINAL_SWITCH state=False`）；两端 `IDENT … match=True`。
- **回归面**：接管「抢」的是宿主的空格键，所以另开 6 档回归，见 §3.4（档① 开关关闭时与修复前**逐字节相同**，实测 5/5；档②③④ 均对产品有利；档⑤ 模拟器不可注入、走单测；档⑥ 跨进程读成本 ≤16ms）。
- **判定**：**关闭**。规格开放问题 #10（「keyevent 与触摸等价性未验证」）的答案随之改写为**条件等价**：在九项合取成立时两条入口进同一个 `decide()`；不成立时（英文态、T9、组合中、按住不放、开关关）硬件键维持「放行给宿主」，这与修复前逐字相同。
- **残留（不撤销真机项）**：蓝牙/物理 HID 键盘上的接管未取证（模拟器只有注入键），列 §9.4；`repeatCount>0` 的「按住不放不接管」只有 JVM 单测证据（§3.4 档⑤）。


**D2 · P0（继承 PM 裁定，非本报告新判）· 进入设置页「键盘与输入」即 NPE 崩掉整个输入法进程 = 已有 issue [VOCA-170]（`v1.6-AC9`）**

> 别名先说清（裁决 §2-4）：本链路上「AC-9」指两个东西——**`v1.6-AC9`** = v1.6 内测门禁第 9 条 = VOCA-170 的修复（PR #9）；**`spec-AC9`** = 键盘规格 §4 AC-9 = 长按连删（本文 §3.1 那一行）。本轮起报告与工装一律带前缀写，不再裸写「AC-9」。

- **归属与定级（裁决 §2-1，本报告不再重开门禁问题）**：这条缺陷**已有 issue VOCA-170**，PM 于 11-15 裁定 **P0 + v1.6 内测阻断**，写进 VOCA-183 交付计划 §五的 AC-9。本报告**继承 P0**，不记 P1、不写「属 PM/人类的范围裁决、本报告不替它判」——把它当新题重开就是越权。
- **第七轮复现的性质**：**同一缺陷的第四次复现**，且是在**冻结被测面 `78343a4` 当场**复现（02:2x 窗口，`ev_r7c_d2_fixed.txt`：`VERDICT fatal_count=2 keyboardviewmodel_frames=8 d2=STILL_CRASHING`，栈顶 `KeyboardViewModel.kt:35`，pid 31426→31766 重启）。前三次分别为：本 issue 第四轮 A 包当场崩（`ev_crash_kbsettings.txt`）、质检专员在 `fa57a07` 自建包上同一时序同一栈、第六轮把它脚本化后在 A 包重跑（`ev_r5u_d2_control_78343a4.txt`）。基线包 `fa57a07` 同脚本同样崩（`ev_r7_d2_base.txt`，pid 23753→24088）→ 与 PR #18 变更面无关这条结论继续保持。
- **两个口径分开写死，互不覆盖（裁决 §2-2）**：
  - ① **PR #18 变更面拦路** = **第七轮清空**：D1 已在本被测面修复并复验（§3.3-D1）、pp 在目标真机几何下实测 +0.92pp 达标（§5）、D3 由「FAIL」改为「档2 待签字」并降级 P2（§3.3-D3），本 PR 变更面新增的只剩 **D4（P3，可观测性）**；**不计 D2**，依据见下；
  - ② **v1.6 合并窗口门禁** = **仍含 D2（P0）**：第七轮当场核到的事实是 PR #9 `state=OPEN`、`mergedAt=null`、`head=d3b6f84`，`git merge-base --is-ancestor d3b6f84 78343a4` = **不成立**，`git branch -r --contains d3b6f84` 只命中 `origin/fix/voca-170-keyboard-vm-init-order` → 修复既不在 main、也不在 PR #18。
- **行号漂移，三个版本列全（裁决 §2-3；不写版本就会核空）**：

  | 版本 | collector 写入行 | `MutableStateFlow` 声明行 | 崩点 |
  |---|---|---|---|
  | VOCA-170 原文（`fa57a07` 之前它引用的版本） | `:34` / `:37` | `:101` / `:104` | — |
  | main `fa57a07` | `:35` / `:38` | `:102` / `:105` | `:35` |
  | 被测 A 包 `78343a4` | `:35` / `:38` | `:107` / `:110` | **`:35`**（`chineseInputMethodFlow` 那个 collector 的 `emit`） |

- **不是 PR #18 引入的四条依据**（都能当场复跑）：
  1. `git diff --numstat fa57a07...78343a4 -- app/src/main/java/com/ispeech/settings/keyboard/KeyboardViewModel.kt` → `5  0`：只有新增的 `setDoubleSpacePeriod()` setter（`78343a4` 的 `:102-106`），三个 collector 与两个 `MutableStateFlow` 声明一字未动；
  2. `git blame -L 34,39 78343a4` → `:34/36/37/38/39` = `904a98d`（Mini，2026-11-09），崩点 `:35` = `2a1883e9`（Lin，2026-11-10，VOCA-29）；两个 SHA 都 `git merge-base --is-ancestor <sha> fa57a07` 成立，而 PR #18 的提交清单只有 `e528c08` + `78343a4`；
  3. 反向自查：`git diff -U0 fa57a07...78343a4 -- app/src | grep '^+' | grep -E 'viewModelScope|MutableStateFlow|collect'` 在 16 个变更文件的全部新增行里**只命中 1 行**（那个 setter 自己的 launch）；
  4. 质检专员在 `fa57a07` 构建上复现到同一行、同一栈的 `FATAL EXCEPTION`（PID 13926）。本报告上一轮写的「PR #18 在 `:38-39` 新增第二个同形 collector，把暴露面翻倍」是**错误归因**，已删除。
- **修复验证**：PR #9 的 `d3b6f84` 把两个 `MutableStateFlow` 声明前移到 `init` 之前（并加 `KeyboardViewModelInitOrderTest` 锁死不变量）。第五轮曾把它与 `78343a4` 合成集成候选（当时的 B 包 `cffc0cd`）实测：**`fatal_count=0`、IME 进程 pid 19562 前后不变、页面正常进入**（`ev_r5u_d2_union.txt`）；阳性对照为同脚本装回不含 `d3b6f84` 的 A 包（`ev_r5u_d2_control_78343a4.txt`），第七轮又在 `78343a4` 上重做了这个对照（`ev_r7c_d2_fixed.txt`）。单测侧 `KeyboardViewModelInitOrderTest tests=2 failures=0`（§7）。→ **修复有效，但 PR #9 未合入，v1.6 门禁按口径②仍拦路。**
- **本轮取证影响**：AC-2 的开关置位因此改走输入法内配置面板（齿轮键 → `openPanel(Panel.SETTINGS)`，`IspeechImeService.kt:2071`），不经过会崩的 App 设置页。
- **升级给 PM 两点（第七轮复核）**：① VOCA-170 看板状态与 main 事实不符——本轮 `multica issue get VOCA-170` 实测 `status=in_review`、`updated_at=2026-11-17T07:26:53Z`（裁决当时读到 `done`），而 PR #9 仍 `OPEN`、`d3b6f84` 不是 `fa57a07` 也不是 `78343a4` 的祖先 → 缺陷仍在 main，要么合 PR #9、要么把状态退回；② PR #9 进哪个合并窗口仍需要窗口号，不是「待定」。

**D3 · P2（第七轮降级，原「待 PM 定级 + FAIL 口径」作废）· `spec-AC-10a` 在 520dpi 只出 3 条完整候选**

- **降级理由（不是改期望值，是判据本身分了档）**：`0e4eeb0:560` 把 AC-10a 写成**按逻辑宽分档**——档1（≥371.0dp）要求前 4 条完整可见，档2 要求 ≥3 条 + AC-12 让步即合格，门限只有一份（`band.TIER_SPLIT_DP=371.0`，`ac10_budget.py` 在 PR #17）。第六轮那句「520dpi FAIL」是把档1 的硬门限套在**模拟器独有、现实里不存在的几何**上：1080px 宽 @520dpi → 逻辑宽 332.3dp = 档2。目标真机几何（1260×2720@520 → 387.7dp = 档1）在第七轮实测 `full_count=4`（`ev_r7_ac10_target_geom.txt`）。
- **读数（第七轮 `78343a4` 当场）**：1080@520/fs1.15 —— `shijian` `cand_count=4 full_count=3`（idx3 `x=961..1059` 越采样右界、`badge_clipped=True`、字身 9px）、`dashu` `cand_count=3 full_count=3`（`ev_r7_fs115.txt` / `ev_r7_ac10_1080.txt`，每行带 `tier=`）。两码成因不同：`AC10b reason=viewport_clip` vs `long_word_budget`。
- **判定的正确写法**：档2 分支 = `verdict=AWAITING_SIGNOFF`（`band.R21_SIGNED=False`）。**签字前不写 PASS**（打回项 4(b) / R21），也不写 FAIL —— 它落不落在验收面上，取决于 UX/UI 是否签 AC-12 那条让步（让步顺序 `0e4eeb0:236`、出路可达性 `:591-595`）。
- **用户可见影响（保留原判断）**：真机密度 + 字体放大 1.15 时，若某台机型算出来是档2，候选条一屏看不全 4 个词，而本期恰好「去翻页键、改横滑」，滑动又没有可视线索。目标机型算出来是档1，所以这条影响面是「其它 520dpi 但更窄的面板」，不是 ALN-AL00。
- **归属**：P2、PR #18 变更面内（VOCA-46 词长/候选口径），交 PM/UX 签 AC-12；「缩 chip 宽度 vs 放宽 §2.1 词长口径」不由测试自处。
- **复跑**：`python3 fs115.py <apk> 520 1.15 shijian,dashu A7`（§10）＋ `python3 ppwin.py --size 1260x2720 --density 520 …`（目标几何）。

**D4 · P3（第七轮新增，可观测性，非功能缺陷）· 组合态下的硬件空格日志记成 `via=soft`**

- 现象：`78343a4` 的 `IspeechImeService.kt:604` 是「非空缓冲时硬件空格」的分支（`if (keyCode == KeyEvent.KEYCODE_SPACE) handleSpace() else handleEnter()`），调用**没传 `via`**，而 `handleSpace(via: String = "soft")`（`:735`）默认值就是 `soft` → logcat 里这条明明是 KeyEvent 路径，却打成 `via=soft`。
- 取证：`ev_r7_d1_regress.txt` 的档②③ `PROBE` 行（T9 数字态、组合中、搜索框、SSID 对话框各一组）都出 `takeover=2`，但同一时刻的 `spaceProbe`/判定行的 `via=` 取值与入口不符；对照 `:599-600` 的空缓冲分支确实传了 `via="key"`。
- 影响面：**只有日志**。行为本身正确（组合中的空格该走 `handleSpace()` 的 composing 分支），但 `35cdbfa` 加 `via` 的初衷正是「D1 就是被两条入口口径不同掩盖的」（commit message 原话），这行漏传等于把那个掩盖点留着一个小口子：将来若再出现「软键对、硬件键错」类问题，`via=` 在组合态不可信。
- 判定：P3、不拦路、不提 PR 意见之外的处置。归属：前端（`IspeechImeService.kt:604` 传 `via = "key"`，或把 `:603-604` 与 `:599-600` 合并到同一个带 `via` 的入口）。
- 复跑：`python3 d1regress.py <apk> fixed`（看 `PROBE` 与 `via=` 是否一致）。


### 3.4 接管空格键的回归面（打回项 3 · `35cdbfa` 引入的新风险）

被接管的是**宿主的空格键**，这类改动的风险不在「句号能不能出」，而在「别的地方该出空格时没出」。六档在 `78343a4` 当场跑，原文 `ev_r7_d1_regress.txt`（108 行，`IDENT … match=True` 两态各一条 + 基线一条，全部 `PROBE` 行带 `ts=`）：

| 档 | 打的是什么 | 实测 | 判定 |
|---|---|---|---|
| ① **开关关闭时与修复前逐字节相同** | 基线包 `fa57a07`（无该开关，`SWITCH route=absent_means_default_off`）与修复包 `78343a4` 各跑同一组 5 个 case（`off_parity`），逐 case 比宿主文本与接管计数 | `PARITY base=… fixed=… identical=True` ×5、`PARITY_SUMMARY base_cases=5 fixed_cases=5 all_identical=True`；同 case 再跑两遍验可重复：`REPEAT first=16 second=16 common=16 identical=16 verdict=一致` | **PASS（实测，非引用注释）** |
| ② **英文态空格 / T9 数字态空格** | `english_2x_space`、`t9_composing_then_2x_space`；英文态由键帽墨迹三段判据现场认定（`MODEINK ink_zh=0 ink_sep=104 ink_en=515 english=True`，`INK_THR=90`；中文态对照 401/148/0） | 英文态 `takeover=0`（放行给宿主，符合九项合取里的 `!englishMode`）；T9 组合后双击 `host='0。' takeover=2`（该出句号时出句号，没吞空格） | **PASS** |
| ③ **需要自己吃掉空格的宿主** | 搜索框（`host_search_2x_space`）、WLAN SSID 对话框（`host_dialog_ssid_2x_space`）、密码框（`password_host_*`） | 搜索框与 SSID 对话框 `takeover=2` 且宿主文本按预期出句号（这两个宿主在开关开启时本就让位给输入法）；**密码框不可达**：`GATE reason=password_host_unreachable rc=0` —— Settings 里已无密码输入宿主，模拟器其它 App 的密码框不在本次可枚举范围，不冒充测过 | **PASS（2/3 实测，1 档挂 §9.3 待取证）** |
| ④ **空格长按 → 语音，接管后是否还成立** | `input swipe` 同点 500ms 长按（`installSpaceGesture` 用的是同一个键），语音启动无日志，判据取候选带截图差分 | `LONGPRESS band_changed_rows=812`（基线）→ `LONGPRESS_AFTER_TAKEOVER band_changed_rows=61 verdict=ui_changed`：接管后长按仍触发 UI 变化（语音面板），且变化量级与接管前同形 | **PASS（差分口径，非日志）** |
| ⑤ **`repeatCount>0`（按住连发）不得被接管** | 想要的是「按住空格连发空格时不夹句号」 | `REPEATCAP devices_with_space_key=0 devices_with_evrep=0 keyevent_options=keyevent `[--longpress]`／`[--doubletap]`` → **模拟器注入键造不出 `repeatCount>0`**，工装如实 `GATE reason=repeatcount_not_injectable_on_emulator fallback=SpaceKeyPolicyTest`；改取产品侧 JVM 单测：`SpaceKeyPolicyTest` 里 `keyRepeat=true → takesHardwareSpace=false`（§7 的 `tests=/failures=` 原文），代码依据 `IspeechImeService.kt:829` `keyRepeat = event.repeatCount > 0` + `SpaceKeyPolicy.kt:117` | **单测覆盖、设备面挂 §9.4（真机/蓝牙键盘复量）** |
| ⑥ **接管后 `getTextBeforeCursor` 跨进程读成本（父 issue 的 INP 关注点）** | 20 次空格、每次接管都真读一次跨进程文本，读 `spaceProbe` 的 `roundTripMs` | `SPACEPROBE presses=20 probes=20 takeover_lines=40 roundTripMs_min=1 med=7 p95=14 max=16` → 单次接管多花的成本中位数 7ms、最坏 16ms，量级在输入延迟预算内；`probes=presses` 说明没有「读不到就不读」的漏采 | **PASS（模拟器读数，真机 INP 另计）** |

**门禁**：`python3 tools/verify/check_crosscut.py` 第 13 组（`35cdbfa` 自带的结构门禁：`onKeyDown` 没走 `takesHardwareSpace` 就 FAIL）在 `78343a4` 上 **rc=0**，原文 `ev_r7_crosscut_78343a4.txt`（`passed=157 failed=0 warned=0`）。

**这一节的新增风险结论**：档①②③④⑥ 全部指向「接管没有伤害该出空格的地方」；剩下的风险集中在**设备面拿不到的两档**（⑤ 按住连发、③ 密码框宿主）和**真机蓝牙键盘**，已如实挂 §9.3/§9.4，不写成已验证。


## 4. 命名必证项：恒高 46dp × font_scale 1.15 会不会截字（第五轮重测）

规格点名的本轮最可能新缺陷：候选条用固定 `layout_height=46dp`（`candidate_view.xml:10`，注释说明「用 height 而不是 minHeight——候选字号是 sp，minHeight 兜不住 font_scale 放大后的内容高度」），内行 `minHeight=38dp`；1.15 下内容 38.2dp 被压进 38dp 可能截字。

**旧 §4 的三行墨迹/留白数据已作废**（chip-only 伪装态，见 §2.5），下表是带闸门重测后的新口径：字身墨迹只在 **chip 右沿 + 1** 之后量，角标圆片按外形剔除，`tangent_items` = 与带内沿相切（= 被截）的候选条数。

**本节数据仍是第五轮那批**（第六轮的打回项 1/2/3/5/6 都不落在截字面上，被测包也没换，`IDENT match=True` 同一条），口径按 §3 开头那条「读数以 stdout 为准」执行：下表 `ev_r5_440.txt` / `ev_r5_520_fs115.txt` 的 `INK`/`CAND` 行是原文，对应的 A 包 440dpi 候选带帧当时被 B 包覆盖、未入库（§13.5）。

| 参数（被测面 `78343a4`） | 键盘高（空态→有候选态） | 候选带 | 字身墨迹（chip 右侧） | 上下留白 | 截字判定 | 原文证据 |
|---|---|---|---|---|---|---|
| 440dpi / fs1.0 · `shijian` & `dashu` | 988 → 988（Δ=0） | 126px（46dp）、内沿 1363..1467（104px） | 1393..1440（ink_h 48；两码同值） | 顶 30 / 底 26 | `tangent_items=0` → **纵向不截** | `ev_r7_fs115.txt` |
| 440dpi / fs1.15 · 两码 | 988 → 988（Δ=0） | 126px、内沿 1363..1467 | 1395..1447（ink_h 53，+10.4%） | 顶 32 / **底 19** | 不截，但**底部余量只剩 19px** | `ev_r7_fs115.txt` |
| 520dpi / fs1.0 · 两码 | 1168 → 1168（Δ=0） | 150px、内沿 1185..1309（124px） | 1222..1277（ink_h 55） | 顶 37~38 / 底 31 | 不截 | `ev_r7_fs115.txt` |
| 520dpi / fs1.15 · 两码 | 1168 → 1168（Δ=0） | 150px、内沿 1185..1309 | 1223..1284（ink_h 62） | 顶 38~39 / **底 24** | 不截；同档 AC-10a 只是**档2**（332.3dp 的「不存在的几何」），不是 FAIL | `ev_r7_fs115.txt` + `ev_r7_ac10_1080.txt` |
| 目标真机几何 1260×2720 / 520dpi / fs1.15（第七轮新增，打回项 2(b)） | 1143 → 1168（空态→有候选态；Δ=0 的恒高契约见同文件 `RESULT` 行） | 候选带上沿 1577..1580、chip 盒 1580..1672 | 字身 pitch 225px（=69.2dp）、字身宽 36.6dp | 逐条 `CAND` 行见原文 | 逻辑宽 **387.7dp → 档1**，`full_count=4` → **PASS** | `ev_r7_ac10_target_geom.txt` |

判读（第七轮重写：`0e4eeb0` 的分档口径进来后，原来那句「520/fs1.15 真正不成立的是横向」已经说不通了）：
1. **纵向**：1.15 下字身高 48→53px（440dpi，+10.4%）、55→62px（520dpi），键盘高度**一位未涨**（恒高契约在有候选态同样成立），全部 8 组 `tangent_items=0` → **纵向不截字**；裁决 §7-1 的「1.15 字身被 46dp 截」分支在模拟器 Roboto 下没有发生。
2. **横向（原 D3，本轮降级 P2）**：520dpi 的 `full_count` 只有 3 —— 但按 `0e4eeb0:560` 的判据，档界是逻辑宽 **371.0dp**（`band.TIER_SPLIT_DP`），1080×520 算出 **332.3dp = 档2**（≥3 条 + AC-12 让步即合格）。这台「1080 面板配 520dpi」在真实机型里不存在，拿它判 FAIL 是把不存在几何的读数当产品结论。目标几何（1260×2720@520 → **387.7dp = 档1**）实测 `full_count=4`，真机上本来就是档1口径。D3 措辞从「520dpi 只能看 3 条 = 缺陷」改为「**模拟器专属的不存在几何下，档1 判据不适用**」，P2、来源仍 `78343a4`，见 §3.3。
3. **档2 未签字前不写 PASS**（打回项 4(b)，R21）：`ev_r7_ac10_*.txt` 每行都带 `tier=`，档2 的 `verdict` 一律 `AWAITING_SIGNOFF`（`band.R21_SIGNED=False`），本报告只把档1 写成 PASS；签字口径（AC-12 让步顺序 `0e4eeb0:236`）归 UX/UI + 人类确认。
4. **留给真机**：440/fs1.15 底部余量只剩 19px、520/fs1.15 只剩 24px，厂商字体（非模拟器 Roboto，FMR 可能替换字体）再高一点就先顶到下沿；这条与 pp 一样必须真机复量，见 §9.1。

## 5. 占屏比：目标几何实测 +0.92pp（≤1pp 达标）；上一版「+7.09pp 不达标」是模拟器几何伪影，已作废

| 口径（每行都标面板几何＋取数窗口＋文件名） | iSpeech | 搜狗 | 差值 | 预算 ≤1pp |
|---|---|---|---|---|
| **目标真机几何 1260×2720 @520dpi / fs1.15**（第七轮打回项 2(b) 新增）· 同窗 00:36:50→00:37:53 · `ev_r7_pp_target_geom.txt` | 1168px = **42.94%** | 1143px = **42.02%** | **+0.92pp** | ✓ **达标** |
| 同上 @520dpi / fs1.0 · 同窗 · `ev_r7_pp_target_geom.txt` | 1168px = 42.94% | 1143px = 42.02% | +0.92pp | ✓ 达标（1.15 与 1.0 同值：iSpeech 恒高、搜狗字重不换行） |
| 模拟器原生 1080×2340 @440dpi / fs1.0 与 fs1.15 · 同窗 00:35:05→00:36:37 · `ev_r7_pp_window.txt` | 988px = 42.22% | 978px = 41.79% | +0.43pp | ✓ 达标 |
| 1080×2340 @520dpi / fs1.15 · 同窗 · `ev_r7_pp_window.txt` | 1168px = 49.91% | 1002px = 42.82% | ~~+7.09pp~~ | **作废**（见下）：1080 宽配 520dpi = 逻辑宽 332.3dp，现实里没有任何这种屏；目标机 ALN-AL00 是 1260×2720@520 |
| 第五轮 B 包（集成候选）440 / 520dpi 两行 −1.11pp、+5.39pp | — | — | — | **作废**：B 包口径已并入 `78343a4`，且 520dpi 那行同样是 332.3dp 的不存在几何；原文 `ev_r5u_440.txt` / `ev_r5u_520.txt` 只留在 §11.1 做历史对照 |

**第七轮的两处硬改动**（对应打回项 1 与 2(b)）：

1. **全部读数在 `78343a4` 当场重跑**，iSpeech 侧每帧带 `IDENT … match=True`（逐条目比 `classes*.dex` + `resources.arsc` 的 sha256）＋逐帧 `ts=`；搜狗侧同窗先后测（`WINDOW phase=open/close` 各一段，中间不重启模拟器、不换包、不改 density）。1080@440 的 978px 与首测窗口入库值（`ev_sogou440.txt`）**逐位一致** → 跨 run 复用不翻转结论方向，这是裁决 §9.4 敏感性算术要的结论。
2. **判据升级**：pp 不再只是 inspector 里的一个数，而是**要人预裁决的判据**——规则写成「iSpeech 的 pp ≤ 同几何搜狗 pp + 1.0pp」，档位由逻辑宽算出（`0e4eeb0:560` / `band.TIER_SPLIT_DP=371.0`），脚本把算式与读数一起入库（`ev_r7_pp_target_geom.txt` 的 `SUMMARY` 行：`逻辑宽=387.7dp 档=1`、`算法=1260/(520/160)=387.69dp`）。

**Δpp 对面板高度的敏感度**（说明为什么换几何就换结论）：绝对差是 25px（1168 − 1143），`Δpp = 25/H × 100` → H=2340 时 **+1.07pp**（刚越线）、H=2720 时 **+0.92pp**（达标）、H=3200 时 +0.78pp。也就是说这条验收成不成立，**取决于面板高度而不是键盘本身**；1080×2340 那台模拟器只是恰好矮，才读出「+7.09pp」那种量级——那一行真正错的是把 520dpi 接到 1080 宽上（搜狗在那种几何下也只有 1002px，因为它按 px 钉资源）。

**机制**：iSpeech 的键高/候选高全是 dp 值（`DEFAULT_KEY_HEIGHT_DP=46`、`candidate_view.xml` 46dp），随 density 线性放大（440→520 时 988→1168，×1.18 = 520/440）；搜狗在 520dpi 上是接近 px 钉死的资源包（978→1002/1143，随面板而非密度）。所以**同一只面板**上两者的绝对差近似恒定（25px），面板越高摊到的 pp 越小。这不是 VOCA-46 引入的回归（52→46dp 反而收窄了差值）。

→ **pp 的定稿权仍在真机**：本节是模拟器按目标几何（`wm size 1260x2720` + `wm density 520` + `font_scale 1.15`）复现的读数，不是真机读数；真机 FMR0224913042917 自 11-13 未连接，搜狗在真机上的面板、字体回退、Harmony 的 IME insets 处理都可能有差。因此 §9.1 的真机 pp 取证项**不撤销**，但**它的性质从「no-go 主因」降为「定稿手续」**——打回项 2 要求的『把 §5 那两行升级成一个结论』的结论就是这句：**目标几何下达标，缺口只剩真机复量**。

## 6. §9.8 五条未验证路径

| 路径 | 结果 | 证据 |
|---|---|---|
| ① 编码 0 候选 | **PASS**：候选条不塌、不改高。空态（0 候选）带高仍 126px、键盘 988px，墨迹 0 行；`REG_double_space` 空态 kb_h=988 / band_ink=0 | `ev_fs115_440_final.txt`、`ev_paths4.txt` |
| ② 候选池上限截断 | **代码级确认，未做真机计数**：`candidatePoolLimit = candidatePageSize × CANDIDATE_POOL_FACTOR(3)`（`IspeechImeService.kt:105/187`），默认 9 → 27；用户可设 5/7/9 → 15/21/27。运行侧只证到「滚到端后不再新增内容」（§3 AC-6：本轮 `=` 按到第 26 次起 cum 恒定 4752px，旧 `ev_ac47.txt` 同现象读数 4892px；差的 140px 是本轮 err=5.05 没计入 cum 的末档截断，属记账口径差、不是设备行为变了（口径见 §3 AC-6b）） | 代码 + `ev_ac47b.txt` |
| ③ T9 态 `-`/`=` 滚动 | **未取证**：模拟器上 `123 → 9键` 的软键坐标按权重推算落到了短语面板（kb_h=1885），两轮尝试均未稳定进入九宫格；`check_t9.py` 静态侧 87 项通过可保证键位定义未被本轮改动破坏 | `ev_paths5.txt`、`ev_paths9.txt` |
| ④ 上屏/联想后偏移归零（G2） | **未取证**：三次尝试的宿主字段分别被 Settings 搜索框吞键、联系人「姓名」框吞键、以及联系人「更改尚未保存」弹窗抢焦点，`-` 未能进 IME，位移数据不可信（已丢弃）。代码侧 `CandidateView.kt:81` 无条件 `scrollTo(0,0)` 仍在 | `ev_paths4/5/6/9.txt` |
| ⑤ 面板打开时候选条层级 | **PASS（并给出用户可见影响）**：工具栏「快捷短语」「剪贴板」面板会**整体替换输入区**，候选条不再存在；短语面板高 1885px（面板+工具栏+键区，无候选条），剪贴板面板高 727px（仅面板）。截图：`p5_phrase.png`、`p5_clip.png`。**返修轮订正**：工具栏 ⚙ 不是「直接跳设置页、键盘整体收起」——它先在输入法内打开配置面板（`IspeechImeService.kt:2071 openSettings → openPanel(Panel.SETTINGS)`，同样是整块替换输入区，9 个开关 + 中文输入法 + 语音 4 行 + 底部「更多设置」）；只有面板底部「更多设置」才跳 App 设置页（`:2076-2084`），而进入「键盘与输入」会崩（§3.3 D2）。AC-2 的开关置位因此走输入法内面板，`ev_ac2_*.txt` 的 `TOUCH/DS_*` 行就是这条路径的当场记录。 | `ev_paths4.txt`、`ev_ac2_soft.txt` |

回归面（同批执行）：

| 项 | 结果 |
|---|---|
| 空格上屏首选 | PASS（`AC3_buffer`：`shi`+空格=`是`） |
| `installSpaceGesture` 500ms 语音与双击空格共存 | PASS：双击判定在 service 层 `handleSpace`（`check_crosscut` 的 R8 断言通过），实测 179ms 双点出「。」、600ms 间隔出两个空格，未误触发语音面板 |
| 快捷短语面板里打字进搜索框 | PASS：面板打开时敲 `hai` → 面板搜索框显示 `hai`、标题变「联想 3 条 · hai」、宿主邮箱框为空（`reg_phr_after.png`） |
| T9 页未被本轮改动破坏 | PASS（静态）：`check_t9.py` passed=87 failed=0 warned=1（唯一 warn 是既有的「动物」分类同名，已用「颜·」前缀规避） |
| 语音键上滑取消 | **未取证**（本轮未跑，见 §9） |
| TalkBack 300→600ms 窗口 | **部分**：单测 `无障碍模式下判定窗口放宽到 600ms` 通过；真机 TalkBack 未跑（见 §9） |

## 7. 静态门禁与单测（第七轮在 `78343a4` 仓库根当场执行，原文 `ev_r7_static_78343a4.txt` + `ev_r7_crosscut_78343a4.txt`）

```
check_static.py       SUMMARY: passed=6   failed=0 warned=0     rc=0
check_input_logic.py  SUMMARY: passed=40  failed=0 warned=0     rc=0   ← 此前「EmojiData FAIL」的说法作废
check_t9.py           SUMMARY: passed=87  failed=0 warned=1     rc=0   ← 唯一 warn 是既有的「动物」分类同名
check_crosscut.py     SUMMARY: passed=157 failed=0 warned=0     rc=0   ← 第六轮 156 → 157：35cdbfa 新增第 13 组
```

**第 13 组（打回项 3 要求的结构门禁）**：`35cdbfa` 自带一条 `check_crosscut` 断言 —— `onKeyDown` 的空格分支没走 `takesHardwareSpace` 就报 FAIL，防止这条静默回到「宿主吞空格」的老路。本轮在 `78343a4` 仓库根跑，组内全 PASS（含 D1 断言），`crosscut_rc=0`，原文 `ev_r7_crosscut_78343a4.txt`（173 行，逐条 PASS 可 grep）。

**JVM 单测（`./gradlew --offline :app:testDebugUnitTest --rerun`，`ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`，02:35:24 / 02:35:46 两次当场）**：

```
SpaceKeyPolicyTest        tests=16 failures=0 errors=0 skipped=0   ← 35cdbfa 把它从 9 条扩到 16 条
InputModePolicyTest       tests=7  failures=0 errors=0 skipped=0
全量：65 tests completed, 1 failed   BUILD FAILED rc=1
  唯一失败 = com.ispeech.agent.AgentDispatcherTest  tests=1 failures=1
             testcase name="initializationError"（RuntimeException：AndroidJUnit4 delegate runner 在本地 JVM 找不到）
```

**这 1 条失败是 main 既有缺陷，不是本变更面回归**（第五、六轮已核：`git blame` 归属 `AgentDispatcherTest` 自带的 `@RunWith(AndroidJUnit4::class)`，与本 PR 变更面无交集；本轮 `65 tests / 1 failed` 与旧 head 的失败集合逐条同形）。测试面**不通过降低标准来变绿**：全量仍报 rc=1，本报告把它作为已知债留在 §12。

**接管面（D1 修复）在单测侧的契约**（`SpaceKeyPolicyTest` 16 条里直接对应打回项 3 的四条）：

| 用例名（XML 原文可 grep） | 对应打回项 3 的档 |
|---|---|
| `硬件键长按的自动重复不算双击` | 档⑤ `repeatCount>0` 不接管（设备面注入键造不出 repeat，见 §3.4） |
| `开关关闭时硬件键空格交回宿主 行为与修复前一致` | 档①（设备面另有 5/5 逐字节对照） |
| `空缓冲的硬件键空格由输入法接管 判定链才收得到第二下` | AC-2 硬件路径转阳本身 |
| `英文态连按两次空格出两个空格` | 档②（设备面另有键帽墨迹三段判据） |

**不在本被测面的守卫测试（如实记）**：`KeyboardViewModelInitOrderTest`（VOCA-170 / PR #9 的 init 顺序结构锁）与 `KeyHitTesterTest`（VOCA-184 零死区命中）在 `78343a4` 的 `app/src/test` 里**不存在**（`find` 命中 0），所以第六轮那句「22 tests / 0 failures」是**集成候选 B 包 `cffc0cd` 的口径，不是本轮被测面**。这正好和 §3.3-D2 的事实互相印证：PR #9 没进 PR #18。

**被测产物来源（第三人复现时的比对基准）**：被测面 = PR #18 head `78343a4`（`git rev-parse HEAD` = `78343a4580ca9804126b7034a155d76cd4fb87ae`），专用检出 `…/voca-47-*/voca47-build`；APK 逐条目 sha256 比对见各证据文件的 `IDENT … match=True`（`classes*.dex` + `resources.arsc`）。


## 8. 规格/实现差异与观察项（按 PM 裁定不判 FAIL）

1. **R4 补偿实现**（规格/实现差异）：实现只在「光标前一个字符仍是空格」时才 `deleteSurroundingText(1,0)`，判定入口 `lastSpaceStillBeforeCursor() = lastSpaceCommitAt != null && getTextBeforeCursor(1,0)==" "`（`IspeechImeService.kt:715-780`）。实测在宿主里先删掉空格再双击 → 得到 `␣␣` 而非「。」（`ev_r4.txt` S3、`ev_ac23d.txt` R4_probe=`她她们`），符合「不做破坏性删除」的意图，比规格更保守。
2. **G2 无条件 `scrollTo(0,0)`**（`CandidateView.kt:81`）：用户可见影响＝每次重绘候选（含联想刷新）都回到最左，滚动位置不保留。本轮未取到运行时证据（§6④），列为待补。
3. **G7 徽标无入口**：候选来源徽标（短语/AI/学习）仍渲染但没有点击入口，用户可见影响＝只能看不能用。
4. **P2 观察项（本轮已固化工装 `vswipe.py`，结论重新定性；不再挂「待复现」）**：
   - **短竖滑被当成点击**：起点 y=1391（带内）、x=300、duration 120ms、440dpi 扫位移：`dy=40/60/80` → 上屏（`shi`→`是`），`dy=110/150/200` → 不上屏；细扫 `dy=90` 上屏、`dy=100` 不上屏（`ev_vswipe_a.txt`、`ev_vswipe_c.txt`）。`dy=20` 那一档不参与判据——它实测不是上屏，而是**把键盘整个收掉**（`band_top=None`、`after='<NONE>'`、`ime_gone=True`），上一版把它算进「dy=20/40/60/80 上屏」是错的。**上屏阈值在 90–100px（≈33–36dp）之间**，比上一轮写的「60px 上屏 / 110px 不上屏」宽一档：60px 那半句成立，阈值那一档写窄了。质检专员复跑时报 110px 也上屏，本轮用同参数（起点 1390、dy=110、120ms）**连跑 3 次均未上屏**（原始读数在 `ev_vswipe_c.txt`）——这个跨 run 离散没定位，不写成结论，留给真机手指触摸那轮一并判。对照组：纯 `input tap 300 <带内 y>` 同样上屏，所以走的是候选项的点击路径。
   - **「上屏」与「横滚」分开量**（上一轮把两件事混在一条里是错的）。在滚动态先按 `=` ×4 把 scrollX 推到约 792px、内容稳定后做手势：**纯竖滑（dx=0，dy=110/150）位移 0，err 0.0 → 可判**，即带内纵向手势不驱动横向滚动；**斜滑（dx=160，dy=110）位移 −264px，err 4.37 → 可判**（这是已入库证据里的**唯一一次**斜滑样本，上一版写的「−264/−272、err 4.37/4.07」第二个值来自没入库的当场输出，本轮删掉——只有 n=1，够定性方向、不够定分布，下一轮至少同参数复跑 3 次）。方向与「手指右移 → scrollX 减小」的直接操作语义一致，属正常行为而非缺陷（`ev_vswipe_b.txt`）。
   - **上一轮 `ev_g46.txt` 里「候选条竖滑/斜滑 shift=−870」作废**，按本报告 §3 自己定的口径（residual>5 不可判）本来就不该出结论：那是拿**上屏前后**两帧做互相关，候选池被 `CandidateView.kt:81` 的 `scrollTo(0,0)` 整体重置，比的是不同内容。本轮已入库的 **9 个**「上屏」case（`ev_vswipe_a` 4 个：dy=40/60/80 + 该 run 末尾的纯 tap 对照；`ev_vswipe_c` 4 个：dy=90 + 3 次纯 tap 对照；`ev_vswipe_b` 1 个：滚动态 dy=60。核对：`grep -c committed= True ev_vswipe_{a,b,c}.txt` → `4/1/4`）复现出同形状的假读数：非滚动态那 **8 次一律 −832（err 12.04）**，滚动态那 **1 次 −842（err 8.84）**，全部按口径记 `unverifiable(err>5.0)`，机制坐实。（上一版写的「−796 / err 11.78」在 `ev_vswipe_*` 三份文件里都不存在，是当场没入库的输出，本轮删掉。）
   - **「起点越出候选带仍能命中候选」撤回**：起点在带上沿外 215px（y=1137，落在宿主内容区）向下滑 215px，实测是**这一下手势把键盘收掉了、宿主焦点也没了**（`band_top=None`、`after='<NONE>'`、`ime_gone=True`），不能读成上屏。工装同步补口径：`committed` 只在 IME 仍在位且宿主文本可读时才置真，并另打 `ime_gone` 字段。
   - 工装风险提示：位移越小越容易被系统判成别的手势——dy=20 在带内起手时整条用例翻成「键盘被收掉」（见上面阈值条），说明这条用例对共享 AVD 的环境争用与手势识别阈值都敏感；判据一律以「同参数多次复跑读数一致」为准，单次读数不落表。
   - **`dy=40..90` 短竖滑会上屏 → 数据归属规格 AC-11（`0e4eeb0:606`），不是 AC-7**：规格 `:503` 明确「本条（AC-7）的判据对象只有落点出带那一档……带内 dy 40–80px 的近垂直短拖会把候选上屏，**那是另一件事，见 AC-11**——不得写进本条判定行，也不得据此判 AC-7 FAIL」；AC-11 自己标的是「2026-11-17 新增，**本轮不测**」。所以这批读数是 AC-11 的**既有数据源**（规格 `:549` 起就是拿 `ev_vswipe_a.txt` 这条链路立条的），在 §3.1 里作为 **AC-11 = N/A** 行的支撑，不计入 PR #18 拦路面。数据：`ev_vswipe_a.txt`（dy=40/60/80 上屏）、`ev_vswipe_c.txt`（dy=90 上屏 / dy=100 不上屏，阈值 90–100px ≈ 33–36dp）。第六轮复核实现侧仍无 slop 判定：`git show fa57a07:app/src/main/java/com/ispeech/input/CandidateView.kt | grep -ci slop` = `0`、同命令打在 `78343a4` 也是 `0`（`ev_r6_calibers.txt` ③）→ **AC-11 的修复在 main 与 PR #18 上都不存在**，属下一期条目，本轮不擅自判 FAIL、也不顺手修。

5. **模拟器几何会造出不存在的档位（第七轮的方法论观察，建议进回归清单）**：同一份产品码，`1080×2340@520` 算出逻辑宽 332.3dp（档2），`1260×2720@520` 算出 387.7dp（档1），pp 也从 +7.09pp（越线）变成 +0.92pp（达标）。**结论随面板几何翻转，而不是随产品翻转** → 分档判据必须绑定「目标机型几何」跑，模拟器默认面板只能当补充。归属：测试（回归清单口径）+ PM（验收条款要不要写死机型）。
6. **接管后跨进程读文本的成本（打回项 3 的第⑥档，按 PM 裁定只记不判）**：`spaceProbe` 20 次空格实测 `roundTripMs` 中位 7ms、p95 14ms、max 16ms（`ev_r7_d1_regress.txt`）。量级在输入延迟预算内，但这是**模拟器**读数，真机低端机的 `getTextBeforeCursor` 跨进程成本另计；父 issue 的 INP 关注点因此保留在 §9-5。

## 9. 未取证清单（真机 / 后续必须补）

| # | 项 | 为什么模拟器判不了 | 归属 |
|---|---|---|---|
| 1 | 520dpi / fs1.15 的**定稿**占屏 pp | 第七轮已在模拟器按**目标真机几何**（`wm size 1260x2720` + `wm density 520` + `font_scale 1.15`）复现：iSpeech 1168px=42.94% vs 搜狗 1143px=42.02% → **Δpp +0.92pp ≤1 达标**（`ev_r7_pp_target_geom.txt`）。仍挂的原因只剩「模拟器不是真机」：厂商字体回退、Harmony 的 IME insets 处理未取证；真机 `FMR0224913042917` 自 11-13 未连接。**性质已从 no-go 主因降为定稿手续** | 测试 + SRE（真机上线） |
| 2 | §9.8 ③ T9 态 `-`/`=` 滚动、④ 上屏后偏移归零 | **第七轮部分取证**：`paths3.py` 在 `78343a4` 走通了两条时序（`V3_T9 bands=[5 行]` + `V3_T9_scroll shift2=872 err=0.0`；`V4_reset ink=199 eq4=236 err1=5.8 commit=-594 err2=10.8 base_vs_after=690 err3=10.9 host='中国'`），`paths2.py` 的对应档仍落到短语面板（`Q3_t9_cand kb_h=1885`、`Q3_t9_scroll shift2=0`）→ **两份工装在同一个包上给出不同形状**，说明工装不稳；且 `paths3` 没打 `verdict` 行，判定口径（这几档的期望位移）未立。仍不可判：软键坐标按权重推算不稳，需真机或固化 `uiautomator` 定位 | 测试（工装）+ 前端（G2 口径） |
| 3 | 语音键上滑取消、语音失败文案 | 需要真实音频输入 | 测试 + 前端 |
| 4 | TalkBack 真机朗读与 600ms 窗口 | 模拟器未装 TalkBack 服务态 | 测试 + UX |
| 5 | 真机手指触摸的滚动/长按手感、**真机蓝牙/物理 HID 键盘上的空格接管**（§3.3-D1 残留）、真机 INP | 模拟器只有注入键：`input keyevent` 造不出 `repeatCount>0`（`REPEATCAP devices_with_space_key=0 devices_with_evrep=0`），也接不到真实 HID 报告；接管后的跨进程读成本在模拟器是 7~16ms，真机低端机未测 | 测试 + 人类评审 |
| 6 | release 打包 + 真机安装 | 项目规则五：生产操作需人类确认 | 人类确认 |
| 7 | spec-AC-9 的 45ms 快档到底可达不可达 | **口径要先换**：`adb shell input keyevent --longpress`/注入式长按的实测上限就落在 90ms 慢档（4s→删 40 字→92.8ms/次，`ev_ac9d.txt`），拿注入时长反推永远看不到 45ms 档。下一轮改用 logcat 打点量两次删除的实际间隔，或真机手指长按定性。**这条与 spec-AC-9 的 AC 判定分开**——AC 判据（1.5s→>3 字）本轮已用 1500ms 实测档判 PASS（§3.1），45ms 是 §2.5 阶梯实现的另一件事 | 测试（logcat 口径）+ 人类（真机手指） |
| 8 | **PR #9（VOCA-170 修复 `d3b6f84`）未合入 main，而 VOCA-170 看板状态与事实不符** | 模拟器判不了「该不该进哪个合并窗口」：这是范围/窗口裁决。本轮核到的事实——`gh pr view 9` → `state=OPEN`、`mergedAt=null`；`git merge-base --is-ancestor d3b6f84 origin/main` → rc=1（缺陷仍在 main）；`multica issue get VOCA-170` → **`status=in_review`**（裁决 §2 当时读到的是 `done`，本轮实测已漂，`updated_at=2026-11-17T07:26:53Z`）。B 包已验证「合了就修好」（§3.3 D2），剩下的只是落地位置 | PM + 人类评审 |
| 9 | **VOCA-203 本体未取证** | 该 issue 现在 `in_progress`、**没有任何自己的实现提交**（阻塞在人类对「快捷字母长度基线」的答复，评论显示已停摆 63 小时）。本轮能覆盖的只有它的实现子单 VOCA-207（PR #11 `4d6363c`，仍 OPEN），且只覆盖到**键盘交互面**（候选条渲染、恒高、pp、退格阶梯）；快捷字母长度 / trigger 所有权 / 同步回写这三条判据属 VOCA-207/206 自己的验收面，不在本 issue 的回归范围 | 洞察BA + 前端（另单验收） |
| 10 | **B 包 ≠ 合并窗口产物** | 三方合并的冲突由 QA 按「后合入者适配先合入者」解决（§13.1 清单），真实窗口若采用别的解法（例如保留 1 参 `backgroundFor` 重载、或给 `buildEnterKey` 换一套留白），§13.2/13.3 的结论必须在新产物上复跑；另外 `d283706` 误提交的 gitlink `.claude/worktrees/voca-74` 我是在本地排除的（§13.1 表末行、§13.3 D4），窗口里怎么处置需要人拍 | 项目经理（leader）+ 人类确认 |
| 11 | **横滚的无障碍节点挂载层**（规格 `0e4eeb0:669` 要求「确认可无障碍节点确实挂在 `candidate_scroll` 这层而不是外层 `LinearLayout`」） | 静态侧只能证到「全仓没有显式 scroll action 代码、也没有触摸探索分支」：`git grep -l -E 'ACTION_SCROLL_FORWARD\|ACTION_SCROLL_BACKWARD' fa57a07 -- app/src/main/java` → 命中 **0** 个文件，`git grep -l isTouchExplorationEnabled fa57a07` → **0**（`ev_r6_a11y_main.txt` C 段）。挂载层归属是运行时行为，要 TalkBack 或 `uiautomator dump` 实测，模拟器本轮未装无障碍服务态 | 测试 + UX |
| 12 | `dy=20` 那一档「键盘整条被收掉」的成因 | 读数是确定的（`band_top=None`、`after='<NONE>'`、`ime_gone=True`），但分不清是手势被判成「下滑收起 IME」还是共享 AVD 的环境争用。需要 `InputDispatcher` / IME visibility 的 logcat 打点或真机手指触摸才能定因果；本轮只把「`dy=20` 不进判据」这条口径写死（§8-4），不写结论 | 测试 |
| 13 | **规格 §8.5 那份 54 条「文档断言 → 代码行」清单在 `78343a4` 上 24 条 FAIL** | 不是回归：清单本身锚的是 main 现状（`spec85_anchors.sh fa57a07` → **54/0**，逐行现算、非手抄）。24 条 = **21 条纯行号漂移 + 3 条实质变更**，那 3 条恰好是本轮采纳项的落点（`DEFAULT_KEY_HEIGHT_DP 52→46`、`keyHeightDp 52→46`、`candidate_view.xml minHeight 44dp→恒高 46dp`）。PR #18 合并后**必须重锚**，否则下一轮又出现「文档说 `:38`、代码在 `:39`」这类核空。归属文档层，本轮只报不改（裁决 §2-3 为同类问题付过代价） | UX + 架构 |
| 14 | **真机不可用时 pp 怎么定级**（规则缺口，第七轮给出建议但不自处） | 第七轮把 pp 升级成「判据 + 算式 + 目标几何实测」，并给出敏感度 `Δpp = 25/H`（H=2340→+1.07pp、2720→+0.92pp、3200→+0.78pp）：判据在 H≥2500 的几何成立、更矮的面板会翻越 1pp。所以「按目标机型几何测 + 写明面板参数」可以替代「非真机不可」，但**这条规则要 PM 立字**才生效，本轮仍按「模拟器目标几何 + 真机定稿」双口径给数 | PM（立规则）+ 测试 |
| 15 | **档2 是否需要签 AC-12（R21）** | 模拟器独有几何（1080@520=332.3dp）落在档2，`verdict=AWAITING_SIGNOFF`、`band.R21_SIGNED=False`；打回项 4(b) 明确「签字前不得写 PASS」，本轮既不写 PASS 也不写 FAIL。需要的是 UX/UI 按 `0e4eeb0:236` 让步顺序签或拒 | UX/UI + PM（签字） |
| 16 | **密码框等「必须自己吃空格」的宿主**（§3.4 档③残留） | `GATE reason=password_host_unreachable rc=0`：当前模拟器可枚举界面里没有可用密码输入宿主，工装如实拒绝出数而不是拿别的框冒充 | 测试（补宿主清单）|
| 17 | **`repeatCount>0` 不接管的设备面证据**（§3.4 档⑤残留） | 设备面不可注入（同上），现有证据只有产品侧 JVM 单测 `硬件键长按的自动重复不算双击`（`tests=16 failures=0`）；真机长按连发未取证 | 测试 + 人类评审 |
| 18 | **证据头部串口是未展开的字面量（工装缺陷，第七轮自查）** | 10 份 `ev_r7_*.txt` 的头部 `serial=` 原样写进了 `${QA_SERIAL:-emulator-5554}`；本轮 `QA_SERIAL` 未设置 → 实际取默认 `emulator-5554`，但同机 `emulator-5556` 当时也装着同一个被测包，**串口无法只从文件本身反证**。数值不受影响（`IDENT … match=True` 与逐行 `ts=` 都在），可追溯性受影响 → 已在各文件加 `# note(入库注记，非当场 stdout)` 并如实登记；`run_r7_{all,b,c,d}.sh` 已改为盖展开后的 `serial=` ＋ `device=<ro.product.model>`，第八轮起证据头部自证 | **归属：测试（工装），无需产品改动；不构成本次 no-go 的理由** |

## 10. 复现方式

```bash
# 0) 起模拟器（设备不跨 run 存活），等真正 boot 完再动手，否则第一批 ensure_host 必 FAIL
avdmanager list avd | grep ispeech_test
emulator -avd ispeech_test -no-snapshot-save -no-audio -gpu host &
adb wait-for-device
until [ "$(adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 3; done
adb -s emulator-5554 shell settings put secure autofill_service null

# 1) 装被测包并**由操作器**固定 IME（脚本不再替你抢，见 §11）
#    -d 必带：共享 AVD 里常留着别人更高 versionCode 的包，否则 INSTALL_FAILED_VERSION_DOWNGRADE
#    装前先清数据：设备里那份 DataStore 可能是别人的构建写的（本轮就吃过 versionCode=6 的数据残留，
#    它会让 §3.3 D2 的崩溃必现，也会让「开关默认 false」这类判据的前提不成立）
adb -s emulator-5554 shell pm clear com.ispeech.ime
adb -s emulator-5554 install -r -d -g <app-debug.apk of 78343a4>
adb -s emulator-5554 shell ime enable com.ispeech.ime/com.ispeech.input.IspeechImeService
adb -s emulator-5554 shell ime set   com.ispeech.ime/com.ispeech.input.IspeechImeService

# 2) 恒高 / 字体缩放 / 截字 / spec-AC-10（§3.1、§4；第六轮闸门与判据解耦版，`MIN_CAND=1`）
#    闸门不过就只打一行 GATE，stdout 不出现 RESULT/INK/delta（裁决 §6-1）
python3 tools/qa/voca-37/fs115.py <apk> 440 1.0,1.15 shijian,dashu
python3 tools/qa/voca-37/fs115.py <apk> 520 1.15 shijian,dashu
#    第 5 个位置参数 `label`（默认 `a`）是**截图文件名标记**，不是判定开关：`r5_<label>_<dens>_fs<fs>_<code>_<kind>.png`。
#    加它的原因是第五轮 B 包复跑没带标记，把 A 包同名候选带裁图覆盖了（文本证据没受影响，但帧对不上包）。
#    第六轮 A 包档传 `a6` → 帧名 `shots/r5_a6_520_fs1.15_shijian_{empty,comp}.png`；B 包那 8 帧是 label 方案加入**之前**跑的，
#    文件名直接是 `shots/r5u_520_fs1.15_shijian_{empty,comp}.png`（不带 `r5_` 前缀）→ `ac10_offline.py` 按包分别拼名（`base = "r5_"+tag if pkt=="a6" else tag`）。

# 2-r7) 第七轮（打回项 1/2/3/4）——一条命令复跑整轮，读数全部落在 evidence/ev_r7_*
#    被测面恒定，用环境变量注入，脚本内的 IDENT 会把它打出来，装错包直接 rc=19 退出：
export QA_HEAD=78343a4 QA_SPEC=0e4eeb0
export QA_APK=/path/to/build-78343a4/app/build/outputs/apk/debug/app-debug.apk
export QA_SERIAL=emulator-5554        # 默认值就是它；换串口时 ppwin/d1regress/fs115 全部跟随
export QA_ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
bash tools/qa/voca-37/run_r7_all.sh          # 第一串：d1_fixed fs115 ac47 ac9 paths paths2 paths3 vswipe d2_base d1_base
bash tools/qa/voca-37/run_r7b.sh             # 第二串：ac47 paths2 d1_fixed ac9 d1_base（IDENT/闸门修好后重跑）
bash tools/qa/voca-37/run_r7c.sh             # 第三串：把 drive.log 改成逐行打 ts= 之后，重跑 ac47 vswipe paths3 paths2 ac9 d2_fixed fs115
bash tools/qa/voca-37/run_r7d.sh             # 第四串：paths.py + ac2.py soft（drive.log 打完 ts= 后补跑的这两份）
#    每串把各档 stdout 落到 /tmp/r7[a-c]_<suite>.txt（含 `# rc=`），入 evidence 时改名 ev_r7_*.txt（§11.1 逐个署名）
python3 tools/qa/voca-37/spec_ux_anchors.py 8324b03 0e4eeb0 docs/keyboard-ux-spec.md   # 规格行号重锚（左端 8324b03 是**作废锚**，只当比对参数用，正文不引它的行号）：逐条 `git show` 两边比内容 → ev_r7_spec_ux_anchors.txt
#    目标真机几何的 pp / AC-10（打回项 2(b)，SRE 五点返修后的工装）：
python3 tools/qa/voca-37/ppwin.py --ime sogou   --panel 1260x2720 --tiers 520:1.0,520:1.15 --round r7t
python3 tools/qa/voca-37/ppwin.py --ime ispeech --panel 1260x2720 --tiers 520:1.0,520:1.15 --round r7t --apk "$QA_APK"
python3 tools/qa/voca-37/d1regress.py "$QA_APK" fixed          # 接管面 6 档（含 20 次空格的 spaceProbe 成本）
python3 tools/qa/voca-37/d1regress.py "$QA_BASE_APK" base off_parity   # 档① 的修复前对照包
python3 tools/qa/voca-37/d1regress.py --diff /tmp/r7e_d1_base.txt /tmp/r7b_d1_fixed.txt   # 逐 case 比宿主文本 → PARITY
python3 tools/qa/voca-37/ac9.py 60 200,1000,1500,2000          # AC-9 阶梯（第一个参数是退格键 x，不是 dpi）
python3 tools/qa/voca-37/d2check.py "$QA_APK"                  # D2：参数是 apk（旧文档写 `d2check.py 440` 是错的）
python3 tools/verify/check_crosscut.py                         # 第 13 组＝接管空格键的结构门禁，rc 必须 0


# 2-e) 第六轮新增的三条（打回项 1 / 2 / 3 与 6 附）
#     ↓ 第六轮的模拟器原生面板（1080x2340）三档，对照值只作历史；第七轮的定稿口径见上面 2-r7)
python3 tools/qa/voca-37/ppwin.py --ime ispeech --tiers 440:1.0,440:1.15,520:1.15 --apk <78343a4 的 app-debug.apk>
#     同一个连续窗口内两侧各三档，只出 WINDOW/FRAME/RAW/IDENT（量高度、不出判据，不做宿主回读闸门）→ ev_r6_pp_window.txt
python3 tools/qa/voca-37/ac10_offline.py       # 已交付帧离线复算 AC-10a 双口径 + AC-10b reason，重算与设备侧读数同值 → ev_r6_ac10_offline.txt
bash  tools/qa/voca-37/spec85_anchors.sh <sha> # 规格 §8.5 那 54 条「文档断言→代码行」原样重跑；fa57a07 → 54/0，78343a4 → 54/24 → ev_r6_spec85_main.txt

# 2-a) 作废自查（打回项 1 的第 3 条自证）：旧帧重扫，旧口径墨迹逐位复现 + 新口径候选数
python3 tools/qa/voca-37/voidcheck.py      # → FRAME/NEW/VERDICT/SUMMARY；rc=0 = 三帧全部低于 4 条闸门（作废成立）

# 2-b) AC-2/AC-3 两个短路开关的运行时取值（打回项 4 的第 2 项披露）
python3 tools/qa/voca-37/dsdump.py         # → DS/SWITCH；DataStore 文件不存在时给 source=code_default(never_written) 并先 ls -R files 自证 run-as 路径没错

# 2-c) spec-AC-9 退格长按阶梯（含 1500ms 实测档；键位从截图反推 + 200ms 探针闸门）
python3 tools/qa/voca-37/ac9.py 60                        # 默认 200,1000,1500,2000 四档
python3 tools/qa/voca-37/ac9.py 60 200,1000,1500,2000      # 显式指定；探针删不掉 1 字 → GATE reason=del_key_miss rc=20

# 2-d) D2 / v1.6-AC9 崩溃时序（第五轮脚本化；A 包出 FATAL、B 包出 FIXED 才算工装有效）
python3 tools/qa/voca-37/d2check.py <app-debug.apk>   # ← 参数是 apk，不是 dpi（旧文档写错，本轮订正）
#     rc=0 未崩 / rc=1 崩了（附 STACK 行）/ rc=2 前置没起来 / rc=17 IME 不是本包 / rc=19 键盘没弹出
#     ⚠ 三个坑都踩过：force-stop 自己的 IME 会让系统把默认输入法回落到 Gboard（假 FIXED）；
#        awk 解析 bounds 把 `][` 吃掉算出 y=425711（没点到卡片，假 FIXED）；
#        logcat 不过滤进程会把别人家的崩溃算进来（假 STILL_CRASHING）。脚本三处都上了闸门。

# 2b) 身份闸门自检（返修项 4）：切到 Gboard 后必须打 SKIP，不许再产出 delta=0
adb -s emulator-5554 shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
python3 tools/qa/voca-37/fs115.py <apk> 440 1.0        # → SKIP reason=current_ime_not_ispeech，exit=3

# 2c) AC-2 双击空格：开关态 × 键路径（返修项 3），两份 stdout 分文件入 evidence/
#     开关置位走输入法内配置面板（齿轮键 1005,1552 → 滚到底 → 逐行试点，用 DataStore 键差异确认点中了谁），
#     不走 App 设置页——那条路会崩（§3.3 D2）。DataStore 在 files/datastore/，不是 datastore/。
python3 tools/qa/voca-37/ac2.py <apk> 440 keyevent     # 硬件/注入键 + 开关开/关反例
python3 tools/qa/voca-37/ac2.py <apk> 440 soft         # 软键点击 + 开关开/关反例

# 2d) §3.3 D2 崩溃复现（顺序不能换：先弹键盘让 DataStore 进进程缓存）
python3 tools/qa/voca-37/drive.py host
adb -s emulator-5554 shell am start -n com.ispeech.ime/com.ispeech.settings.MainActivity
adb -s emulator-5554 shell input tap 308 884          # 「键盘与输入」卡片
adb -s emulator-5554 logcat -d | grep -A4 "FATAL EXCEPTION"     # KeyboardViewModel.kt:35 NPE

# 2e) D2 归因核对（返修项 1 的验收：证明这不是 PR #18 带的）
git diff fa57a07 78343a4 -- app/src/main/java/com/ispeech/settings/keyboard/KeyboardViewModel.kt
#     → 只有 +5 行：新增 setDoubleSpacePeriod()；三个 collector 与两个 MutableStateFlow 一字未动
git blame -L 34,40 fa57a07 -- app/src/main/java/com/ispeech/settings/keyboard/KeyboardViewModel.kt
#     → 第 37-39 行（voiceSpaceLongPressMsFlow）落在 904a98d（2026-11-09），不是 PR #18 的提交
# 更强的一条：把 fa57a07 assembleDebug 装到同一台模拟器，跑同样的时序 → 同一行、同一栈的 FATAL

# 3) §9.8 路径与回归面
python3 tools/qa/voca-37/paths.py  <apk>
python3 tools/qa/voca-37/paths3.py <apk>     # 带宿主守卫与弹窗自关
#     本轮按返修项 2 的口径修了这三处脚本的「先滚离左端」：原来按 `-`（=左移，起点已在左端 → no-op，
#     ev_paths4/ev_paths5 里 minus4_shift=0 就是现场），现改为按 `=`，日志字段跟着改名 eq4/eq4_shift。
#     ④ 上屏后偏移归零仍记「未取证」——本轮没有重跑这三条 sweep，只修工装，取数留给下一轮。

# 4) 键帽行/位移测量
python3 tools/qa/voca-37/qa_rows.py <png> <y0> <y1> <dpi>

# 4b) §3 AC-4~AC-7 位移/方向/钳制（返修项 2 的工装，ev_ac47b.txt 就是它产出的）
#     ⚠ 跑之前先复位宿主（本轮返修项 5）：共享 AVD 上别的 run 会留下宿主缓冲残留／把键吞掉，
#     不清就直接跑，多半吃一个 GATE。两种复位任选：
adb -s emulator-5554 shell input keyevent KEYCODE_MOVE_END
for i in $(seq 1 30); do adb -s emulator-5554 shell input keyevent 67; done
#     或者更彻底（会把开关态一起清掉，依赖开关的用例要重新置位）：
adb -s emulator-5554 shell pm clear com.ispeech.ime
python3 tools/qa/voca-37/ac47.py <apk> 440
#     每行都带 key=keyevent70(EQUALS→scrollCandidates(+1)) / keyevent69(MINUS→...) 与 err、cum、verdict；
#     默认 fwd=70 / rev=69。反向证伪档（本轮实跑，闸门拒绝出数）：
python3 tools/qa/voca-37/ac47.py <apk> 440 --fwd 69 --rev 70
#     → PRE_PROBE key=keyevent69 shift=0 err=0.0 verdict=ok
#       + GATE reason=probe_zero rc=4 …（ev_ac47_falsify.txt 是没打 reason 的那次，ev_ac47_gate_probe_zero.txt 是补 reason 后同一档的复跑）
#     同一台机同一数据态的正向对照（ev_ac47_probe.txt，13:11:48–13:12:50）：
python3 tools/qa/voca-37/ac47.py <apk> 440 --n 3
#     → PRE_PROBE key=keyevent70 shift=198 err=2.88，sweep 正常出数
#
#     GATE 的退出码不再共用一个（本轮返修项 5）——「键↔方向对调」和「宿主缓冲有残留」是两种
#     完全不同的前提失效，以前都退 4，第三人会把「工装没跑起来」读成「证伪成立」：
#       rc=4 reason=probe_zero / rc=5 reason=host_buffer / rc=6 reason=bg_low
#       rc=7 reason=frame_none / rc=8 reason=host_not_ready
#     GATE 行同时带该前提的实测字段（host= / probe_shift= / bg_match_pct= / frame=）。
#     两种失效都已当场复跑取证（14:03–14:06，见 ev_ac47_gate_probe_zero.txt / ev_ac47_gate_host_buffer.txt）：
#     对调档 → reason=probe_zero rc=4（有 PRE_PROBE 行）；预热期间并发注入把宿主打进非预热态
#     → reason=host_buffer rc=5（**没有** PRE_PROBE 行，正是上一轮无法区分的原因）。

# 5) §8.4 竖滑用例（返修项 3 的工装，起点 y / 位移 dy / duration / dx 全参数化）
python3 tools/qa/voca-37/vswipe.py <apk> 440                                    # 上屏阈值扫 dy=20..200 + 纯 tap 对照 → ev_vswipe_a.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --mode abs --start 1390 --dy 90,100,110,110,110   # 阈值两侧 + 同参数复跑 → ev_vswipe_c.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family hroll --pre 4 --dy 60,110,150           # 滚动态纯竖滑是否横滚 → ev_vswipe_b.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family hroll --pre 4 --dy 110 --dx 160         # 滚动态斜滑 → ev_vswipe_b.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --mode above --start 215 --dy 215               # 起点在带上沿外（宿主区）

# 6) 集成候选 B 包的复现配方（不推 main、不改他人分支；合并窗口产物若不同必须复跑）
git worktree add -b qa/merge-window-candidate-r5 ../voca47-union 78343a4
git merge --no-commit --no-ff d3b6f84   # VOCA-170：KeyboardViewModel 冲突 → 保留 setDoubleSpacePeriod，属性取前移版
git merge --no-commit --no-ff d283706   # VOCA-184：SoftKeyboardView 两处冲突（见 §13.1）+ 排除 gitlink .claude/worktrees/voca-74
git merge --no-commit --no-ff 4d6363c   # VOCA-207（VOCA-203 的实现子单）：strings.xml 自动合并，无冲突
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew :app:assembleDebug          # 首跑会挂在两处合并集成缺陷上（keyMargin / toolbarKeySelector），见 §13.1
```

## 11. 环境风险与 evidence/ 全量清单（必须记录，否则数字不可比）

同一台 `emulator-5554` 在本轮执行期间被其他 run **换包 3 次**（`pm path` 的包目录哈希与 `lastUpdateTime` 变化：04:42:28、05:06:47、05:18 前后 `default_input_method` 被切到 Gboard）。返修轮起机时设备里又是 snapshot 恢复的 **versionCode=6** 包，`adb install -r -g` 直接 `INSTALL_FAILED_VERSION_DOWNGRADE`，装本包必须带 `-d`（脚本已改）。

**第五轮又撞上一次更凶的：IME 在 run 中途被抢。** 17:02–17:05 之间 `d2check.py` 三次跑出 `GATE reason=ime_not_ours rc=17`——`IME_START` 时还是 `com.ispeech.ime/`，两秒后 `FRAME_1` 读到 `top=1507 h=833`（Gboard 几何）+ `pidof` 为空，即默认输入法在脚本执行中被别的 run 切走。这条之所以危险：D2 那种「数 FATAL 条数」的用例在 Gboard 上跑会得到**看起来很好的 `fatal_count=0`**，等于假 FIXED。所以本轮把三件事写进工装：① 入口读 `default_input_method`、② 预热后读 `ime_frame()` 高度 + `pidof`（键盘没真弹出来 = DataStore 没进进程缓存 = 触发前提不成立 → `rc=19` 拒绝出数）、③ 崩溃统计只数 `Process: com.ispeech.ime` 的 FATAL 块。修好之后才有资格谈判定：A 包 `rc=1`（FATAL ×2，同一栈）、B 包 `rc=0`（fatal_count=0、pid 前后不变），两个方向都有数（§3.3 D2）。

因此：

- 所有采信数字都带安装身份双端校验：对设备上 `base.apk` 与本地构建产物逐条目比对 17 个 `classes*.dex` + `resources.arsc` 的 SHA256（整包 APK 哈希不可比，签名/时间戳会漂）；
- **返修轮补的测量端闸门**：dex 校验只证明「装的是本包」，证明不了「此刻弹出来的键盘是本包」。`fs115.py` 现在在 `ime_frame()` 前后各读一次 `default_input_method` 写进 `RESULT` 行，任一端不是 `com.ispeech.ime/` 就打 `SKIP` 且不记录该组数字；入口也不再替操作器抢 IME。反例见 `ev_fs115_gate.txt`：把 IME 切到 Gboard 后重跑，输出的是 `SKIP reason=current_ime_not_ispeech`，不再是那条假的 `delta=0`。此前 520dpi 混进来的 933px（候选底色 `(241,240,247)` ≠ 本包 `#F4F5F7`=(244,245,247)）就是这个洞的产物；
- 04:42–04:50 之间的一批读数经复核是 Gboard/他人构建的污染值，本报告只引用身份校验通过的窗口（04:51:56–04:53:13、04:59:35–05:01:00、05:03:44–05:05:18、05:08:20–05:10:48）与返修轮窗口（06:55 起，见 §11.1 各行的 `IDENT`/`HOST` 时间戳）；
- 建议：QA 独占 AVD（或给每个 run 建独立 avd 名），否则每轮都要付「重测 + 作废」的成本。

### 11.1 evidence/ 全量清单（逐个文件给口径，不留未署名的读数）

04:42–04:50 之间的一批读数经复核是 Gboard/他人构建的污染值。下表覆盖目录内**每一个**文件：`采信`＝正文引用其数字；`部分采信`＝同文件内有作废段，正文只用有效段；`作废`＝不得引用任何数字，只留作环境争用的证据。

| 文件 | 状态 | 口径 / 作废原因 |
|---|---|---|
| `ev_r7_pp_window.txt` | **采信（第七轮正文口径）** | 模拟器原生面板 1080×2340 的三档同窗 pp，`78343a4` 当场（00:35:05→00:36:37）：搜狗 978/978/1002、iSpeech 988/988/1168，`IDENT … match=True`＋逐帧 `ts=`；520dpi 那一行的 **+7.09pp 已作废为结论**（几何不存在），只留作对照 |
| `ev_r7_pp_target_geom.txt` | **采信（打回项 2(b) 主证据）** | 目标真机几何 1260×2720@520 的 pp：iSpeech `h=1168 pp=42.94` vs 搜狗 `h=1143 pp=42.02` → `Δpp=+0.92 判定=达标`；`SUMMARY` 行含 `逻辑宽=387.7dp 档=1`、算法、档界、`Δpp = 25/H` 敏感度和 dp-vs-px 机制说明；两侧在同一 63s 连续窗口内各测一次 |
| `ev_r7_ac10_target_geom.txt` | **采信（打回项 2(b)/4(b)）** | 目标几何的 AC-10a/AC-10b 逐帧读数，每行带 `tier=档1`、`full_count`、chip 盒与字身 pitch（225px=69.2dp）、`glyph_w_dp`；档1 `full_count=4` → PASS |
| `ev_r7_ac10_1080.txt` | **采信（分档对照）** | 模拟器 1080×2340 两档：440 → `tier=档1 full_count=4`；520 → `tier=档2 full_count=3 verdict=AWAITING_SIGNOFF`（`R21_SIGNED=False`），**不写 PASS 也不写 FAIL** |
| `ev_r7_fs115.txt` | **采信（AC-1＋§4 截字主证据）** | `78343a4` 8 组（440/520 × fs1.0/1.15 × `shijian`/`dashu`）：`RESULT` 全部 `delta=0`、`INK` 的 `tangent_items` 全 0（440 顶30/底26→顶32/底19；520 顶37/底31→顶38/底24）、`CAND` 逐条 x0/x1/glyph/badge；每行带 `ts=`（第七轮改 `drive.log` 后重跑），`GATES` 三 Given 逐条点名 |
| `ev_r7_ac47.txt` | **采信（AC-4/AC-5/AC-6/G6）** | `KEYMAP` 钉住 `=`→+1 / `-`→−1 与代码出处 `IspeechImeService.kt:620-622`；40 个 `AC4_STEP`/`AC5_REV_STEP` 全带 `ts=`；`SUMMARY right_end_cum_px=4752 left_clamp_press=25`；`AC6_TAIL_SUMMARY identical_pairs=4/2` |
| `ev_r7_vswipe.txt` | **采信（AC-7＋§8-4）** | 竖向/斜滑在 `78343a4` 的复跑（`GEOM`＋逐条 `VSWIPE` 带 `ts=`），短竖滑上屏阈值不变 |
| `ev_r7_paths3.txt`、`ev_r7_paths2.txt`、`ev_r7_paths.txt` | **采信（§6 ①③④⑤ 路径）** | `78343a4` 当场：`paths3` 的 `V3_T9_scroll shift2=872`、`V4_reset host='中国'`；`paths2` 的 `Q4_assoc_scroll shift2=396 err=0.7`、`Q3_t9_cand kb_h=1885`（同一包两份工装时序不同 → §9-2 记「工装不稳」，不各自下结论） |
| `ev_r7_ac9.txt` | **采信（AC-9）** | `AC9 hold_ms=1500 deleted=13 rate_ms=115.4 spec_ac9_gt3=PASS` 等四档（200/1000/1500/2000）＋ `IDENT … head=78343a4 spec=0e4eeb0 match=True`，`rc=0`；旧 `ev_ac9.txt`（只有 200/1000/2000）与 B 包 `ev_r5u_ac9.txt` 降为历史对照 |
| `ev_r7_d2_78343a4.txt` | **采信（D2 第四次复现）** | `78343a4` 当场：`VERDICT fatal_count=2 d2=STILL_CRASHING`，栈顶 `KeyboardViewModel.kt:35`，pid 31426→31766；同脚本基线对照 `ev_r7_d2_base.txt`（`fa57a07`，pid 23753→24088，文件头带 `ts=01:59:27`，行内无 `ts=` → 只作对照不作主证据） |
| `ev_r7_ac2_key.txt` | **采信（D1 复验转阳）** | 硬件/注入键 4 组（0/26/537.4ms、三连发）＋开关 OFF 反例 `host='  '`＋`FINAL_SWITCH state=False`，`ac2_rc=0`；。|
| `ev_r7_ac2_soft.txt` | **采信（AC-2 软键路径 · 第七轮重跑）** | `78343a4` 当场 22 行：正例 4 组（0ms/44.3ms → `。`、538.2ms → `␣␣`、3 连发 → `。 `）＋ 开关反例 1 组（`want=OFF state=False raw_hex=12020800` → `␣␣`）＋ `FINAL_SWITCH state=False`；`DS_BEFORE bytes=0`（键不存在）→ `DS_AFTER bytes=27 double_space_period=True` 的 DataStore 落盘原文；每行带 `ts=`，`IDENT` 首尾 `match=True` |
| `ev_r7_d1_regress.txt` | **采信（打回项 3 全部 6 档）** | 108 行：`IDENT` ×3（两态 fixed ＋ base）、`PARITY` ×5 `identical=True`、`PARITY_SUMMARY all_identical=True`、`REPEAT first=16 second=16 common=16 identical=16`、档②③④ 的 `PROBE`/`MODEINK`/`LONGPRESS` 原文、档⑤ `REPEATCAP`＋`GATE reason=repeatcount_not_injectable_on_emulator`、档⑥ `SPACEPROBE presses=20 roundTripMs med=7 p95=14 max=16`、6 条 `R7SUMMARY` 判定 |
| `ev_r7_crosscut_78343a4.txt`、`ev_r7_static_78343a4.txt`、`ev_r7_g1_g3.txt` | **采信（§7 门禁）** | crosscut 在 `78343a4`：`passed=157 failed=0`（第六轮 156 → 157，新增的是第 13 组接管门禁）；static/input_logic/t9 `6/40/87(1 warn)` 全 rc=0；gradle `SpaceKeyPolicyTest tests=16 failures=0`、`InputModePolicyTest tests=7 failures=0`、全量 `65 tests, 1 failed`（失败＝既有 `AgentDispatcherTest initializationError`）；G1/G3 的 `aapt2 dump` 命中数 0 |
| `ev_r7_spec_ux_anchors.txt`、`ev_r7_spec85_anchors.txt` | **采信（锚点重挂）** | 规格从**作废锚** `8324b03` 重锚到 `0e4eeb0`（左端只作比对参数，正文不引它）：逐条 `git show` 两边比内容（24 条：19 原样命中、1 DRIFT、4 MISS → 4 条按 `verdict=REPLACED` 逐条给新落点）；§8.5 那份 54 条清单在 `78343a4` 上 24 条 FAIL 属清单锚 main，不是回归 |
| `ev_r7_pushed_shas.txt` | **采信（打回项 4(a)）** | 已推送 SHA 与本地提交成对（`834acdc↔9de1345`、`5e8b0f4↔9081806`＝PR #25 head），差异只有一个 139,171,786 字节的基线 apk；`BLOBS blob_identical=416 blob_differing=0` → 推送形态与本地等价，引用以推送 SHA 为准 |
| `ev_r5_voidcheck.txt` | 采信（作废自证） | 打回项 1 的第 3 条：旧口径墨迹逐位复现（1400..1441 / 1397..1445 / 1185..1284）+ 新口径 `cand_counts=440/fs1.0:0, 440/fs1.15:0, 520/fs1.15:1`、`ink_entirely_inside_chip=440/fs1.0, 440/fs1.15`，rc=0 → 三帧全部低于 4 条闸门，**旧 §4 三行与旧 §3 AC-1 三组 Δ 作废成立** |
| `ev_r5_440.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| A 包 440dpi fs1.0/1.15 × `shijian`/`dashu` 四组：`GATES` 行 + `RESULT`（Δ=0、x0、cand_count、full_count、pool）+ `INK`（chip 右侧字身）+ `CAND`（逐条 x0/x1/glyph/pitch/badge/tangent）+ `AC10 verdict=PASS` |
| `ev_r5_520_fs115.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| A 包 520dpi/fs1.15：`shijian` 过闸门后 `AC10 full_count=3 verdict=FAIL`（D3）；`dashu` → `GATE reason=cand_low rc=13 cand_count=3 need=4 x0=227`，**该组当时不出数**（stdout 无 RESULT/INK/delta）。⚠ 这一行是**旧闸口语义**：第五轮那版把「可见候选 ≥4」当闸门（`MIN_CAND=4`，`reason=cand_low`）。裁决 §9.2 把它退回判据层后，同一 `rc=13` 在第六轮只代表 `chip_only`（有 chip 无候选，`MIN_CAND=1`）→ 读这行时按当期代码口径解释，第六轮的 `dashu@520` 两条判据行见 `ev_r6_ac10_decoupled.txt` |
| `ev_r5_gates_440.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 这一版逐条点名的 5 个字段（`readback_equals_code` / `chip_text_equals_code` / `scrollview_visible` / `cand_visible` / `pool_at_least`）是**第五轮工装自定的 5 条**，不是规格 Given 数——规格 `0e4eeb0:544` 只有 3 条 Given，`≥4` 混在里面把判据当闸门用了（裁决 §9.2 打回点）。第六轮的 `GATES` 行改按 `spec_anchor=0e4eeb0:525` + `given1/2/3` 点名，两条工装前提（`key_map_fail rc=16`、`host_not_ready rc=18`）单列（§2.5）。第四、五轮的 chip-only 帧在两版里都会被拒 |
| `ev_r5_ac9.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| spec-AC-9 阶梯实测：200→1、1000→7、**1500→13（`spec_ac9_gt3=PASS`，实测）**、2000→21；`PROBE_RETRY first_deleted=0` 如实入库（注入式同点 swipe 偶发丢手势，探针补按后才出数）；`NOTE` 行标注旧「1.5s≈10 字」是两点内插非实测 |
| `ev_r5_switches.txt` | 采信 | AC-2/AC-3 披露第 2 项：`chinese_punctuation=True`、`double_space_period=False`，两条都 `source=code_default(never_written)`；同轮 `DS ls -R files` 有 `cell_dicts`、无 `datastore/ispeech_settings.preferences_pb` → 不是 run-as 失败，是该键从未写过 |
| `ev_r5u_440.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 集成候选 440dpi fs1.0/1.15 四组：Δ=0（952→952）、`tangent_items=0`、`AC10` 全 PASS；`KEYMAP runs=[10,9,7,3]`（VOCA-184 新几何下重新标定成功） |
| `ev_r5u_520.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 集成候选 520/fs1.15：Δ=0（1128→1128）、`shijian` `full_count=3 FAIL`（与 A 包同形 → D3 不是被 170/184/207 掩盖的）、`dashu` `GATE reason=cand_low rc=13 cand_count=3 need=4 x0=227`（**旧闸口语义**，同上） |
| `ev_r5u_ac9.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 集成候选退格阶梯：200→1、1000→8、**1500→9（>3 PASS）**、2000→17 |
| `ev_r5u_d2_union.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| `d2check.py` 在 B 包：`fatal_count=0`、`keyboardviewmodel_frames=0`、pid 19562 前后不变 → **`v1.6-AC9` / PR #9 的修复在并集上有效** |
| `ev_r5u_d2_control_78343a4.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 同一脚本装回 A 包：`fatal_count=2`、栈顶 `KeyboardViewModel.kt:35`、pid 18755→19089 重启 → 证明工装的「未崩」不是假阴性，同时是 D2 的**第三次复现** |
| `ev_r5u_ac2_hw.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 集成候选的 AC-2 硬件/注入键路径：`wall_between_ms=0` 与 53.4ms 两档仍 `host='  '`（开关 `state=True raw_hex=12020801`、`IDENT match=True` 双端）→ **D1 在并集上未修**（`SpaceKeyPolicy.kt` 与 `IspeechImeService.kt` 未被三条 PR 触碰：`git diff --name-only 78343a4 cffc0cd` 可核） |
| `ev_headline.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| fs1.0 段（988→988 ×3、pp=42.22，IDENT 双端 match=True 04:51:56–04:53:13）采信；fs1.15 的 833px 段是 Gboard 抢占值，作废 |
| `ev_fs115_440_fs10.txt` | **降级：不得再用于 AC-1/截字** | 440dpi / fs1.0 候选带墨迹原文（旧 §4 第 1 行的 1400–1441）；带 `ime_before/ime_after` 闸门字段。**第五轮裁决：该帧没记 `cand_count`，墨迹含 chip（`ev_r5_voidcheck.txt` 复算 `x>240` 命中列=0）→ 恒高 Δ 与截字一律以 `ev_r5_440.txt` 为准**，本文件只留作身份/环境旁证 |
| `ev_fs115_440_final.txt` | **降级：INK 段不得再用于 AC-1/截字** | 旧 fs1.15 的 `INK comp ink_top=1397 ink_bot=1445 top_pad=44 bot_pad=31` 属 chip-only 伪装态（`ev_r5_voidcheck.txt` 复算 cand_count=0），由 `ev_r5_440.txt` 取代；同轮 `RESULT fs=1.0 host=FAIL` 本来就无数据；`REG_double_space` 空态那两条仍可作「0 候选不塌」的旁证（§6①） |
| `ev_fs115_520_ink.txt` | **降级：不得再用于 AC-1/截字** | 520dpi / fs1.15 旧墨迹原文（旧 §4 第 3 行的 1185–1284，其中 `top_pad=12` 是把角标圆片当成了字身顶沿）；新口径见 `ev_r5_520_fs115.txt`（字身 1223..1284、top_pad=38、bot_pad=24） |
| `ev_fs115_520.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| `RESULT 1168→1168 delta=0` 与 `ev_520b.txt` 同值互证，保留；两行 `INK ... none=True` 为取证未成功，其截字数据作废 |
| `ev_fs115b.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 两档 `delta=0` 与 `ev_headline`/`ev_fs115_440_final` 互证，保留；`INK` 全 `none=True`（带内没取到墨迹）→ 留白数据作废 |
| `ev_fs115_gate.txt` | 采信（**工装取证**，不作设备读数口径）| IME 切到 Gboard 后重跑：`SKIP reason=current_ime_not_ispeech current=com.google.android.inputmethod.latin/...`，未产出任何 Δ |
| `ev_font115.txt` | **作废** | `AC1_440_115 empty=1050 comp=1062 delta=12` 与 AC-1 的 Δ=0 直接冲突；全文无 `IDENT` 身份校验行，几何 1050/1062 与校验通过窗口的 988 不同构 → 他人换包（恒高未生效的旧包）期间的污染值。**不得当作 AC-1 失败** |
| `ev_fs115.txt` | **作废** | `IDENT start ime=com.google.android.inputmethod.latin/... h=833` → Gboard 抢占；两档 `host=FAIL`，无有效测量 |
| `ev_fontscale.txt` / `ev_fontscale2.txt` | **作废** | `frame=833 top=1507` 是 Gboard 的几何（本包在 440dpi 是 `top=1352 h=988`），`host='搜索设置'` 是 Settings 搜索框的**占位文案**（说明键根本没落到本包）→ Gboard 抢占窗口内的污染值；`ev_fontscale2.txt` 当时已被旧版脚本自己打了 `SKIP` |
| `ev_sogou440.txt` | **作废** | 文件名写 `SOGOU_440`，行内实测 `ime=com.google.android.inputmethod.latin/...`（833px 是 Gboard）。标签与内容不符、最易误导第三人；440dpi 搜狗基准改用 `ev_sogou115.txt` / `ev_pp.txt` 的 978 |
| `ev_520.txt` | **作废** | `HOST ok=False` + `ISPEECH_520_115 h=933 pp=39.87` → 非本包几何（同值出现在 Gboard 抢占窗口）；520dpi 以 `ev_520b.txt` 的 1168 为准 |
| `ev_trunc.txt` | **作废** | `IME top=1099 h=1241` 与 440dpi(1352/988)、520dpi(1172/1168) 两种几何都不符 → 宿主/键盘错位；`INK rel_top=0` 的「贴边」是错位产物，不是截字证据 |
| `ev_sogou.txt` | **作废** | `TRY1/TRY2 top=None` → 搜狗键盘没弹出，取证未成功，无数据 |
| `ev_paths.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 运行中 `TypeError: unsupported operand ... NoneType + int` 中止；`CLEAN h=1050` 与本包几何不符 → 由 `ev_paths2..9` 取代 |
| `ev_ac23.txt` | **作废** | keyevent 三组阴性（`AC2_fast2x host='  '`、`AC2_gap250ms`、`AC2_triple`）**未记开关实际态**（默认 false），且 `wall` 间隔未标注 → 属「未取证」而非「测出不符」；由 `ev_ac2_hw.txt` 取代 |
| `ev_ac23b.txt` | **作废** | `SINGLE_INJECT_2x/3x host='  '` + `LOGCAT_KEYEVENT_LINES n=0` → 注入没落到 IME，同样无开关态；由 `ev_ac2_hw.txt` 取代 |
| `ev_ac23c.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 软键正例 `AC2_soft2x host='。' wall_ms=179` 采信；同轮开关态由 `ev_ac2_soft.txt` 用 DataStore 原始字节重新标注 |
| `ev_ac23d.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| `R4_probe host='她她们'`（§8.1）+ 开关关闭反例 `AC2_toggleOFF host='  '`。其开关态用滑块色值标注（`(0,100,136)`=开 / `(195,196,197)`=关），**返修后以 `ev_ac2_{soft,hw}.txt` 的 DataStore 原始字节标注为准**，本文件的色值标注只作旁证 |
| `ev_ac2_hw.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 硬件/注入键路径（`input keyevent 62`）× 开关开/关，含 `SWITCH state=... src=datastore raw_hex=...`、`MODE`、`IDENT` 与 `wall_between_ms` → §3.3 D1 |
| `ev_ac2_soft.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 软键点击路径 × 开关开/关，同上口径（含间隔 577.1ms 的窗口外反例、三连点 `。␣`） |
| `ev_crash_kbsettings.txt` | 采信（缺陷单） | §3.3 D2 的崩溃原文：`FATAL EXCEPTION: main` / `Process: com.ispeech.ime, PID: 30743` / `NullPointerException ... MutableStateFlow.setValue` @ `KeyboardViewModel.kt:35`；含「先弹键盘再进页面」的复现时序自证（两次 `pid` 同为 30743） |
| `ev_ac47.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 位移阶梯与钳制的**数值**可用（±198、cum 4892、端点后归零）；**键名作废**——文件里没记 keycode，正文当时把 `-`/`=` 的方向标反了。方向/端点归属一律以 `ev_ac47b.txt` 为准；这批标签当时由一次性命令产出、脚本未入库，也是上一轮被打回的原因 |
| `ev_ac47_probe.txt` | 采信（**工装取证**，不作设备读数口径）| 闸门上线后的正向复测（13:11:48–13:12:50）：`PRE_PROBE keyevent70 → shift=198 err=2.88`、`bg_match_pct=77.5`；cum 2970/15 档到端，与 12:31 那轮 4752/24 档不同——记「档位稳、cum 随候选池规模变」这条口径的凭据 |
| `ev_ac47_falsify.txt` | 采信（**工装取证**，不作设备读数口径）| `--fwd 69 --rev 70` 后 `PRE_PROBE keyevent69 → shift=0 err=0.0`、`GATE blocked=前提不成立…`、rc=4：方向判据不是叙述，参数对调就拒绝出数。13:06 那次对调跑（闸门还没上）产出的是全 0 的假钳制 sweep，已删除、不入库 |
| `ev_ac47b.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §3 AC-4~AC-7 的返修轮重测（12:31:03–12:32:51，`IDENT` 双端 match=True）：`=`=keyevent70→+198×24 档、cum 4752 后归零；`-`=keyevent69→−198、左端第 25 次归零；`AC7_FROM_LEFT` +198 err 2.85；宿主全程 `shi`。产出脚本 `ac47.py` 已入库，命令行见 §10-4b |
| `ev_ac47_gate_probe_zero.txt` | 采信（**工装取证**，不作设备读数口径）| 本轮返修项 5 的复跑：`--fwd 69 --rev 70` 对调档 → `PRE_PROBE keyevent69 shift=0 err=0.0` + `GATE reason=probe_zero rc=4`（14:05:16 起，同 `ev_ac47_falsify.txt` 那一档，只是现在带 `reason`）|
| `ev_ac47_gate_host_buffer.txt` | 采信（**工装取证**，不作设备读数口径）| 预热期间并发注入把宿主打进非预热态 → `PREP host='搜索设置'`、**无 `PRE_PROBE` 行**、`GATE reason=host_buffer rc=5`（14:05:47）。这条就是上一轮「退 4 但看不出为什么」的那一类 |
| `ev_ac47_gate_host_residue.txt` | 采信（**工装取证**，不作设备读数口径）| 同上第二种形状：宿主文本 = 预热串混进残留 `host='szzzhzzzizzzzzzzzzzzzzzzz'` → `reason=host_buffer rc=5`、同样无 `PRE_PROBE`（14:08:59）。注入是明写的构造（`run_gate_r4b.sh`），只为覆盖闸门分支，**不是新缺陷、不进任何判据** |
| `ev_ac47_gate_forward.txt` | 采信（**工装取证**，不作设备读数口径）| 按 §10-4b 复位后正向跑通（14:06:39–14:07:57，rc=0）：`PRE_PROBE keyevent70 shift=198 err=2.85`、`HOSTTEXT v='shi'`、`SUMMARY right_end_cum_px=1980 / left_clamp_press=10`（这轮 `--n 2` → 只按 10 档，没到右端，cum 与 4752 不可比，见 AC-6b 的「cum 非常量」口径）|
| `ev_ac9{,b,c,d}.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §3 AC-9 退格长按：200/1000/2000ms→删 1/5/15、英文态与 4s 续按；`ev_ac9d.txt` 的 `rate_ms=101.2/92.8` 只落 90ms 慢档 → AC-9 判据按「代码级确认，行为级未证 45ms 快档」收窄 |
| `ev_r4.txt` | 采信 | §8.1 R4 补偿实现 S0–S3（含 `inject_ms=131` 的间隔自证） |
| `ev_g46.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §3 G4（键区/工具栏横滑 shift=0，err 0.0）+ G6（762→0）采信；同文件「候选条竖滑/斜滑 `shift=-870`」两行**作废**——err 11.4 > 5 按本报告 §3 自己的口径本就不可判，且比的是上屏前后两帧（候选池被 `CandidateView.kt:81` 的 `scrollTo(0,0)` 重置，内容不同） |
| `ev_vswipe.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 「60px 上屏」成立；「110px 不上屏」本轮同参数 3 次复跑也未上屏（与质检专员的一次读数不一致，见 §8.4 与 `ev_vswipe_c.txt`），保留为观察；「带上沿外 215px 起点仍上屏」那行**作废**，由 `ev_vswipe_c.txt` 的 `ime_gone=True` 读数取代；该文件当时由一次性命令产出、脚本未入库 |
| `ev_vswipe_a.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §8.4 上屏阈值扫（dy=20/40/60/80/110/150/200，dur 120，起点 1391）+ 纯 tap 对照。每行都给 start_y/end_y/dy/dx/dur/committed/ime_gone/h_shift/h_err/h_verdict，脚本 `vswipe.py` 已入库 |
| `ev_vswipe_b.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §8.4 滚动态（先按 `=`×4 把 scrollX 推到 ~792px）下的横滚：纯竖滑 dx=0 → shift=0、err 0.0（可判）；斜滑 dx=160/dy=110 → −264px、err 4.37（可判，n=1）；`hroll_dy60` 那行 committed=True、err 8.84，按口径记不可判 |
| `ev_vswipe_c.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §8.4 阈值细扫（dy=90 上屏 / dy=100 不上屏）+ dy=110 同参数三连复跑（均未上屏）+ 起点带上沿外 215px（`ime_gone=True`、`after='<NONE>'`）+ 起点带下沿外 y=1500（shift=0、不上屏） |
| `ev_pp.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §5 440dpi 行：iSpeech 988 / 搜狗 978 → +0.43pp |
| `ev_520b.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §5 520dpi 左列：`ime=com.ispeech.ime top=1172 h=1168 pp=49.91` + 键帽行带 |
| `ev_sogou115.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §5 440dpi 右列：搜狗 978（`ime=com.sohu...` 已标注） |
| `ev_sogou520.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §5 520dpi 右列：搜狗 1002 |
| `ev_paths2.txt` | 采信（**工装取证**，不作设备读数口径）| `IDENT match=False` —— 身份校验确实拦得住换包，是校验有效性的证据 |
| `ev_paths{3,4,5}.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| §6 ①④⑤ 与 ③T9 的当场尝试（含 `HOST ok=False` 的失败原样） |
| `ev_paths{6,7,8,9}.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| `GATE blocked=拿不到「-/= 进 IME」的宿主字段` —— ③④ 记「未取证」的凭据 |
| `ev_r6_pp_window.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 第六轮同窗重测：`WINDOW phase=open/close` 两个连续段（搜狗 22:02:17→22:03:30、iSpeech 22:03:47→22:04:39 +0800），6 帧 `FRAME` + 每帧两行 `RAW`（`dumpsys window displays` 的 `InsetsSource type=ime` 原文）+ iSpeech 侧每帧 `IDENT … match=True`。两侧都在敲完 `shijian` 的组合态取数；`host=` 字段是**敲码前**的宿主读数（`搜索设置` = Settings 搜索框占位文案，证明宿主在位且为空），`comp_top/comp_h` 与 `top/h` 同一份读数、不是第二次测量（文件头写死了这三条口径） |
| `ev_r6_ac10_decoupled.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| A 包 520/fs1.15 两码各一轮：`GATES … spec_anchor=0e4eeb0:525 given1/2/3` + `POOL pool=17/13 need=8` + `RESULT` + `AC10 verdict=FAIL … gauge=spec_sampling_x_end=1060 / gauge_screen=x_end=1080 boundary=False` + `AC10b reason=viewport_clip clipped_idx=[3]`（shijian）/ `reason=long_word_budget clipped_idx=[]`（dashu）。**含一条被弃的第一次尝试**：`GATE reason=readback_mismatch rc=10 want=dashu got=<NONE>`（共享 AVD 焦点被抢），随后重跑成功 —— 两次都原样入库 |
| `ev_r6_ac10_offline.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| `ac10_offline.py` 只读已交付的 8 帧（A 包 `r5_a6_*` ＋ B 包 `r5u_*`）离线复算 AC-10a/AC-10b，逐帧给 `spec=` / `screen=` 双口径与 `boundary`：440 那 4 帧 `4/PASS`、520/fs1.15 那 4 帧 `3/FAIL`、**8/8 `boundary=False`**（裁决 §9.4 风险 3(a) 的「两口径不一致才单列」本轮不触发）。设备侧与离线侧读数逐字节一致（重生成后 diff 只差 `src` 字段名） |
| `ev_r6_gradle_a.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| A 包 JVM 单测原文：① 过滤跑 14 条/0 失败（两次运行都带 `--rerun`，含「过滤跑的 XML 会被随后的全量跑覆盖」这条 attribution）；② 全量 `56 tests completed, 1 failed` BUILD FAILED + `<testsuite>` 与 `<failure message=…AndroidJUnit4ClassRunner…could not be found>` 原文；③ 归因命令与结论（PR #18 对 test 源集只新增 `SpaceKeyPolicyTest.kt`） |
| `ev_r6_gradle_b.txt` ＋ `ev_r6_gradle_b_*.xml`（4 份） | 采信（**打回项 5，B 包**） | B 包 = 本地分支 `qa/merge-window-candidate-r5` head `cffc0cd`，**有意不推 origin**（项目规则一/二/五；裁决 §9.4 打回项 5「不许为了让证据入库去 push B 包分支」）。22 条 / 0 失败的 `<testsuite>` 原文入库，四类逐条对应判据；4 份 XML 可按 `testcase` 复核 |
| `ev_r6_a11y_main.txt` | 采信（**打回项 6**） | §12 的 4＋1 缺口按派工 §四-1 重列，`fa57a07` 上逐条现算行号（不手抄）：`candidate_view.xml:38 minHeight=38dp`（:590）、`CandidateView.kt:53` ＋ 两处 scroll-action/触摸探索**命中文件数 0**（:589）、`CandidateView.kt:116-117 (index+1)` 角标无上限（:593）、`KeyboardLayouts.kt:73 "⌫"` ＋ `SoftKeyboardView.kt:475-478/516`（:595）、对比度 `CandidateView.kt:106/179` ＋ `colors.xml:4` ＋ `candidate_view.xml:9`（:597）；:594「候选词朗读」= `CandidateView.kt:141` **合规**，本轮从缺口清单撤掉 |
| `ev_r6_spec85_main.txt` | **历史对照** （第七轮打回项 1：这类设备读数已在 `78343a4` 上整轮重跑并逐行带 `ts=`，正文一律引 `ev_r7_*`，此件只作对照。）| 规格 §8.5 那 54 条「文档断言 → 代码行」清单原样重跑：`spec85_anchors.sh fa57a07` → **54/0**（证明 §12 每个行号都是现算）；同清单打在 `78343a4` → **54/24**，并逐条分类为 **21 条纯行号漂移 ＋ 3 条实质变更**（`DEFAULT_KEY_HEIGHT_DP 52→46`、`keyHeightDp 52→46`、`candidate_view.xml minHeight 44dp→恒高 46dp`）→ §9 第 13 行的重锚需求由此而来 |
| `ev_r6_calibers.txt` | 采信（**打回项 6(a) ＋ 风险 3(a) ＋ 本轮没动什么**） | ① PR #18 两种变更量口径并列（累计 `16 files, +519/−30` vs 单提交 `e528c08 15/391` ＋ `78343a4 6/136`，差额来自两提交改同批文件，引用必须点名口径）；② 8/8 帧 `boundary=False` 的双读数；③ 两个树上 `grep -ci slop` 都 = `0`（命令：`git show fa57a07:app/src/main/java/com/ispeech/input/CandidateView.kt` 再接 `grep -ci slop`，同法打 `78343a4`）——AC-11 的修复在 main 与 PR #18 上都不存在＋ `git diff --name-only` 的 16 文件清单不含任何无障碍行（§12「未观测到恶化」的出处） |
| `gradle_test.log` | 采信 | 早期那轮 gradle 输出。**「14 条 0 失败」与「全量 56 条 / 1 失败（既有，非本变更面）」现在的可 grep 出处是 `ev_r6_gradle_a.txt` ①②③**（打回项 5 要求的是 evidence 里的原文行，不是日志摘要） |

第三人若按文件名读过去：`ev_font115.txt`（Δ=12）与 `ev_trunc.txt`（贴边）都不是本包在身份校验窗口内的行为，引用前先看本表状态列。

## 12. 范围外已知缺口（PM 裁定不计入本轮 FAIL）

**上一版这里的五行是错的**（写成了「候选条朗读、滚动位置播报、键位 label 缺失、面板焦点顺序」＋对比度）：其中「候选条朗读」在规格 `0e4eeb0:674` 明确标的是**合规**、不是缺口；「面板焦点顺序」在 `0e4eeb0` 与旧版 `28c1cac` 的 §5 表里都**找不到出处**（`git show <sha>:docs/keyboard-ux-spec.md | grep 焦点顺序` 两版都是 0 命中）；「滚动位置播报」也不是表里那一项的名字。按派工 §四-1 的「4＋1」重列如下，**每条行号都是 `fa57a07` 上现算的**（`ev_r6_a11y_main.txt`，同目录 `spec85_anchors.sh fa57a07` → 54 条 0 失败，证明不是手抄）：

| # | 规格行 | 缺口 | `fa57a07` 现状（现算依据） |
|---|---|---|---|
| 1 | `:589` 连续横滚的可访问路径 | 候选容器缺 `ACTION_SCROLL_FORWARD/BACKWARD` 的确认挂载 | `CandidateView.kt:53` 只 `findViewById(R.id.candidate_scroll)`；全仓 `git grep -l ACTION_SCROLL_FORWARD\|ACTION_SCROLL_BACKWARD` → **0 个文件**，`isTouchExplorationEnabled` → **0 个文件**。`-`/`=` 的 KeyEvent 路径（G5）已合规，**但「无障碍节点挂在哪一层」本轮未实测**（§9 第 11 行） |
| 2 | `:590` 候选项命中高度 ≥48dp | **不合规**：现状 38dp | `candidate_view.xml:38` = `android:minHeight="38dp"`（外层 `HorizontalScrollView` 在 `:33-34`） |
| 3 | `:593` 角标只 1–9 | **不合规**：无上限，第 10 项起照样出数字角标 | `CandidateView.kt:116-117` `val badge = TextView(…).apply { text = (index + 1).toString()` |
| 4 | `:595` 退格键 `contentDescription` | **不合规**：键面文案是符号 `⌫`，读屏念不出「删除」语义 | `KeyboardLayouts.kt:73` `KeyDef.fn(KeyAction.BACKSPACE, "⌫", …)`；`SoftKeyboardView.kt:475-478` 非 SHIFT 键一律 `contentDescription = keyLabel(effectiveKey)`（`:516`） |
| ＋ | `:597` 对比度 | **不合规**：首选高亮 `#5B6CFF` on `#F4F5F7` = **3.82:1** < 4.5:1（正文 `#222222` = 14.58:1 合规） | `CandidateView.kt:106` `setTextColor(if (highlighted) primaryColor else TEXT_PRIMARY)` ＋ `:179 resolvePrimaryColor()` ＋ `colors.xml:4` ＋ `candidate_view.xml:9` |

**「本轮未观测到恶化」这句现在有了出处，不再是无凭断言**：PR #18 的变更面（`git diff --name-only fa57a07 78343a4` → 16 个文件，全列在 `ev_r6_calibers.txt` ③）**不含任何无障碍相关行**——没有 `contentDescription`、没有 `importantForAccessibility`、没有命中高度相关改动，所以这 4＋1 项在 PR #18 前后是同一组缺口。**这不是「已验证无恶化」，是「变更面不含该路径」的静态论证**；上表 5 项本身在 A 包 `78343a4` 上未重测。规格 `:672` 那条「一屏 ≥4 候选 × 命中高度 ≥48dp × 恒高 46dp 三角无解」按 `:672` 回 **UI + PM** 裁让步顺序，不由测试自处；52→46dp 的 UI 批准已由人类于 11-09 给出，不重开。

---

## 13. 集成候选回归：本轮覆盖 VOCA-170 / VOCA-184 / VOCA-203（=B 包 `cffc0cd`）

14:50 追加指令要求本轮回归覆盖「问题关联 issue 的改动」。核过仓库后（见文首那条事实核对），这三条 PR **都没有进 main**，所以覆盖方式只能是 QA 自己起一个并集出包。**B 包不是合并窗口产物**，它的价值是「提前把并集的集成风险打出来」，不能替代窗口后的全量回归（项目规则四）。

### 13.1 合并面与冲突处理（三处，逐条给依据）

| 合并 | 冲突 | 处理（原则=后合入者适配先合入者） |
|---|---|---|
| `d3b6f84`（VOCA-170）→ `78343a4` | `KeyboardViewModel.kt` 1 处 | VOCA-46 在同一位置新增 `setDoubleSpacePeriod()`，VOCA-170 把 `_chineseInputMethod` / `_spaceLongPressMs` 两个声明前移到 `init` 之前 → **两者都保留**：setter 留在原位，声明取前移版（文件里只剩一处声明，`:31-35`） |
| `d283706`（VOCA-184）→ 上一步 | `SoftKeyboardView.kt` 2 处 | ① `buildKey` 开头：HEAD 侧是「换行键单独画」的 `enterView` 块，d283706 侧是 `hMarginDp/vMarginDp`（1dp）计算——**两个都要**（d283706 分支点早于 ↵ 图标恢复那笔提交，所以它那边根本没有 `enterView`）；② `backgroundFor`：取 d283706 的三参版（`style, action, layout`，工具栏键透明底）+ 新增的 `keyElevationFor` / `applyKeyElevation`，**删掉 1 参重载**并把 `:554`、`:599` 两处调用点补上 `action/layout` |
| `4d6363c`（VOCA-207）→ 上两步 | 无 | `strings.xml` / `strings-en.xml` 自动合并（两边各自追加，不重叠） |

**两处「不冲突但会编不过」的集成缺陷（合并窗口必踩，这是本轮最值钱的一条预警）**：

1. `buildEnterKey` 里 `setMargins(dpInt(keyMargin), …)` —— `keyMargin` 这个字段被 VOCA-184 **删掉**了（换成按键样式的 `hMarginDp`），而 `buildEnterKey` 是 VOCA-184 分支点之后才有的函数，git 不会报冲突，`compileDebugKotlin` 直接 `Unresolved reference: keyMargin`（4 处）。本轮处理：换行键属 `KeyStyle.PRIMARY`、不是字母键，取 `DEFAULT_KEY_MARGIN_DP`（4dp，与 main 视觉一致）。
2. `toolbarKeySelector()` —— VOCA-184 新增的函数在合并结果里**整块丢失**（git 把它落在冲突块的对齐缝隙里），`backgroundFor` 三参版调它 → `Unresolved reference`。本轮处理：按 `d283706` 原文补回（`ColorDrawable(Color.TRANSPARENT)` + `state_selected → roundedRect(primaryPressed)`）。
3. 附带一条工装侧：`drive.key_map` 的 `merge_gap=8` 在 1dp 留白下会把两行粘成一行（§2.5 末段），B 包首跑 `GATE reason=key_map_fail rc=16`。

### 13.2 并集下的判据复跑（B 包，闸门与 A 包同口径）

| 判据 | A 包 `78343a4` | B 包 `cffc0cd` | 结论 |
|---|---|---|---|
| spec AC-1 恒高 Δ | 4 组全 0（988 / 1168） | 4 组全 0（952 / 1128） | **两包都成立**，恒高契约没被 170/184/207 破坏 |
| 截字（`tangent_items`） | 全 0（440/fs1.15 底部余量 19px） | 全 0（同 19px） | **纵向不截**，余量薄这条留给真机 |
| spec AC-10a（第六轮双口径读数） | 440 PASS（full=4）/ 520·fs1.15 **FAIL（3）**，`boundary=False` | 440 PASS（full=4）/ 520·fs1.15 **FAIL（3）**，`boundary=False` | **D3 与三条 PR 无关**，不是被掩盖、也不是被引入；两包 8 帧的逐帧双读数见 `ev_r6_ac10_offline.txt` |
| spec AC-10b | 520 `dashu` 第六轮**出数**：`verdict=FAIL full_count=3` ＋ `reason=long_word_budget clipped_idx=[]`，同轮 `pool=13`；`shijian` 则是 `reason=viewport_clip clipped_idx=[3]` | 520 `dashu` 第五轮旧闸门（`MIN_CAND=4`）拒出数 → `GATE reason=cand_low rc=13`；第六轮用**同一批已交付帧**离线重算得 `spec=3/FAIL screen=3/FAIL`（`ev_r6_ac10_offline.txt`） | 长词档的 `reason` 两包都能给了：A 包当场、B 包离线。**离线重算不替代设备侧复跑**，窗口产物上仍要按 §10-2/2-e 现跑一遍 |
| spec AC-9 阶梯 | 200→1 / 1000→7 / **1500→13** / 2000→21 | 200→1 / 1000→8 / **1500→9** / 2000→17 | **两包都 >3 → PASS**；B 包 1500ms 档删得少一些（键位/退格重复频率受键高变化影响），不影响判据 |
| 键盘总高 / pp | 988 → 42.22%（+0.43pp）；1168 → 49.91%（~~+7.09pp~~ **+7.09pp 已作废**：1080×2340@520 = 332.3dp，是模拟器强行改 dpi 造出来的档位，现实机型没有这个组合；pp 以目标几何的 **+0.92pp** 为准，见 §5） |  952 → 40.68%（**−1.11pp**）；1128 → 48.21%（**+5.39pp**） | VOCA-184 的留白收紧**改善** pp，但 520dpi 仍 >1pp → 真机口径照旧拦路 |
| 键位标定 | `runs=[10,9,7,3]` | `runs=[10,9,7,3]`（修 `merge_gap` 后） | 零死区命中改动没改变可见键帽段数 |

### 13.3 三条 PR 各自的验收面结果

- **VOCA-170（`v1.6-AC9`，P0）**：**修复在并集上有效**——`d2check.py` 同一时序 B 包 `fatal_count=0`、pid 不变，A 包（不含 `d3b6f84`）当场 `FATAL ×2` 同一栈（阳性对照，§3.3 D2）；单测 `KeyboardViewModelInitOrderTest tests=2 failures=0`。**但 PR #9 仍 OPEN、`d3b6f84` 不是 main 祖先** → v1.6 合并窗口门禁按裁决 §2-2② 仍然拦路，这条不是「已闭」。
- **VOCA-184（`d283706`）**：键盘几何变化已进入 §13.2 的 pp/恒高/键位三行；`KeyHitTesterTest tests=6 failures=0`。**新增一条仓库卫生缺陷（D4）**：`d283706` 把 `.claude/worktrees/voca-74` 以 **gitlink（mode 160000，指向 `88dac57`）** 提交进了分支（`git ls-tree d283706 .claude/worktrees/`）。合进 main 会在仓库里留一个幽灵 submodule 指针，`git worktree list` 也能看到那个 worktree 确实存在（是他人 worktree）。本轮在 B 包里 `git rm --cached` 排除并记录；**归属：VOCA-184 开发（前端）需在 PR #7 里删掉这一条**，P2（不崩不拦功能，但污染主线仓库历史）。
- **VOCA-203**：本体 `in_progress`、**没有实现提交**（停在人类对长度基线的答复）；能覆盖的只有子单 VOCA-207（PR #11 `4d6363c`，仍 OPEN），且只覆盖到键盘交互面（候选条渲染、恒高、pp、退格阶梯）——**快捷字母长度 / trigger 所有权 / 同步回写三条判据不在本 issue 的回归范围**，属 VOCA-207/206 自己的验收面（§9-9）。

### 13.4 未覆盖 / 必须复跑的面

- 全量静态门禁（`check_static` / `check_crosscut` / `check_input_logic` / `check_t9`）未在 B 包重跑：VOCA-207 动了 `tools/verify/check_quick_trigger.py`、`check_phrase_csv_import.py`（快捷字母域），本 issue 面上无对应判据；窗口产物出来后应连静态门禁一起跑。
- B 包上未重跑：AC-4~AC-7（`-`/`=` 位移阶梯）、G4/G6、面板路径、T9、语音。**理由**：这三条 PR 都没触碰 `CandidateView.kt` / `IspeechImeService` 的滚动与面板代码（`git diff --name-only 78343a4 cffc0cd` 可核，滚动面唯一变化是 VOCA-184 的键命中区），A 包结论仍适用；但窗口产物若与 B 包解法不同，本 §13 全部结论作废重跑。
- 真机（520dpi 定稿 pp、手指触摸、TalkBack、语音）照旧未取证（§9）。

### 13.5 本轮为 B 包动过的东西（透明化）

- 新建本地 worktree `…/voca-47-37061cb47d5d/voca47-union`，分支 `qa/merge-window-candidate-r5`（`26628a6` → `4a4732b` → `cffc0cd`）。**不推 origin、不合 main、不改他人分支**（项目规则一/二/五）；复现配方在 §10-6。
- 工装改动（只碰测试代码，不碰产品代码）：`drive.py` 的 `key_map` 行合并高度上限、`fs115.py`/`ac9.py` 的 `gate()` 对嵌套 `reason` 键的碰撞修复、`ac2.py` 的 `F.ident`/`F.ours` 断链与 `EXPECT_TOP` 写死像素（第五轮重写 `fs115.py` 后 ac2 一度跑不起来，属我自己引入的工装回归，如实记）、新增 `d2check.py`。
- **一条证据卫生事故（如实记）**：`fs115.py` 的候选带裁图文件名原来只有 `r5_<dens>_fs<fs>_<code>_<kind>.png`，**不带包标识** → B 包 16:59–17:02 复跑把 A 包 `shijian`/`dashu` 那 12 张同名帧覆盖了（`haizi` 那两张 15:57 的没被覆盖，仍是 A 包）。**文本证据（`ev_r5_*.txt` / `ev_r5u_*.txt`）不受影响，§3.1/§4/§13.2 的每个数字都能在自己的 stdout 里核到**；但帧与包必须对得上，所以：现存 `shots/r5u_*.png` = B 包、`shots/r5_440_fs1.0_haizi_*.png` = A 包，并已给 `fs115.py` 加第 5 位 `label`（`r5_<label>_…`，默认 `a`）防止再撞名。

### 附：本报告引用的原始证据

`ev_headline.txt`（作废段已标注）、`ev_ac47.txt`（键名作废）、`ev_ac47b.txt`、`ev_ac47_probe.txt`、`ev_ac47_falsify.txt`、`ev_ac47_gate_{probe_zero,host_buffer,host_residue,forward}.txt`、`ev_ac23{,b,c,d}.txt`、`ev_ac2_{soft,hw}.txt`、`ev_r4.txt`、`ev_ac9{,b,c,d}.txt`、`ev_g46.txt`（−870 两行作废）、`ev_vswipe.txt`（部分采信）、`ev_vswipe_{a,b,c}.txt`、`ev_pp.txt`、`ev_sogou{,115,440,520}.txt`、`ev_fs115b.txt`、`ev_fs115_{440_fs10,440_final,520,520_ink,gate}.txt`、`ev_font{scale,scale2,115}.txt`、`ev_520{,b}.txt`、`ev_crash*.txt`、`ev_trunc.txt`、`ev_paths{,2,3,4,5,6,7,8,9}.txt`、`gradle_test.log`；**第五轮 6 份** `ev_r5_{voidcheck,440,520_fs115,gates_440,ac9,switches}.txt`、**第五轮 B 包 6 份** `ev_r5u_{440,520,ac9,ac2_hw,d2_union,d2_control_78343a4}.txt`（两批自第七轮起降为历史对照）、**第六轮 12 份（8 txt ＋ 4 XML）** `ev_r6_{pp_window,ac10_decoupled,ac10_offline,gradle_a,gradle_b,a11y_main,spec85_main,calibers}.txt` + `ev_r6_gradle_b_*.xml`（B 包口径，正文数字已作废）、**第七轮 22 份**：`ev_r7_{fs115,ac2_key,ac2_soft,ac47,vswipe,paths,paths2,paths3,ac9,g1_g3,d2_78343a4,d2_base,crosscut_78343a4,static_78343a4,spec_ux_anchors,spec85_anchors,pushed_shas}.txt`、`ev_r7_{pp_window,pp_target_geom,ac10_1080,ac10_target_geom}.txt`、`ev_r7_d1_regress.txt` —— 这 22 份是本报告 §3~§5 与 §7 的唯一采信口径。

目录合计 **100 份**（95 份当场 stdout 原文 `.txt` ＋ 4 份 B 包单测 XML ＋ `gradle_test.log`），**逐文件口径与采信/部分采信/作废状态见 §11.1**（旧 chip-only 三份已标「不得再用于 AC-1/截字」）。截图 **82 张**：第七轮 `r7_*.png` 31 ＋ 第五轮 A 包 `r5_*.png` 22 ＋ B 包 `r5u_*.png` 12 ＋ 第六轮同窗/闸门 `r6_*.png` 6 ＋ 第六轮 A 包 `r5_a6_*.png` 4 ＋ 首测 `f440_*.png` 3 / `f520_1.15_c_cand.png` 1 / `p5_{phrase,clip}.png` 2 / `reg_phr_after.png` 1 ＝ 82，与 `ls shots | wc -l` 一致。工装脚本 **26 个**：`tools/qa/voca-37/` 下 19 个 `.py`（`ac2 ac47 ac9 ac10_offline band d1regress d2check drive dsdump fs115 paths paths2 paths3 ppwin qa_kb qa_rows spec_ux_anchors vswipe voidcheck`）＋ 7 个 `.sh`（`run_gate_r4 run_gate_r4b run_r7_all run_r7b run_r7c run_r7d spec85_anchors`）＋ `README.md`。**一处证据卫生（如实记）**：第七轮编排脚本 `run_r7_{all,b,c,d}.sh` 把串口写成了未展开的字面量 `${QA_SERIAL:-emulator-5554}`，所以 10 份 `ev_r7_*.txt` 的头部 `serial=` 是后来解析补的，并各带一行 `# note(入库注记，非当场 stdout)`；`emulator-5554`/`emulator-5556` 两台当时都装着同一个被测包，**串口无法只从文件本身反证**（数值不受影响：`IDENT match=True` 与逐行 `ts=` 仍成立）。脚本已改为盖 `serial=<展开值> + device=<ro.product.model>`，第八轮起证据头部自证。
