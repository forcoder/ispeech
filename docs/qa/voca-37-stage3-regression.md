# VOCA-37 · Stage 3 键盘交互回归报告（模拟器口径）

- **被测构建（两个口径，别混）**：
  - **A 包 = `c524351`**（PR #18 head，base `fa57a07`）——本 issue 原定被测面；§3～§8 的判据数字全部出自 A 包当场实测。
  - **B 包 = 集成候选 `cffc0cd`**（QA 本地三方合并：`c524351` + VOCA-170 `d3b6f84` + VOCA-184 `d283706` + VOCA-207 `4d6363c`）——14:50 追加指令要求本轮覆盖 VOCA-170 / VOCA-203 / VOCA-184 的改动，而这三条 PR 都还没进 main（见下条），所以由 QA 自行合并出包复跑。**B 包不是合并窗口产物**：冲突由我按「后合入者适配先合入者」解决（清单见 §13.1），真实合并窗口的解法若不同，§13 的结论必须复跑。
- **⚠ 与派工指令不符的一条事实（需人类确认落地位置）**：指令说「PR #18 已合并、基于 `c524351`」。本轮当场核过的位置里 **PR #18 没有出现在任何主线**：
  - `gh pr view 18` → `state=OPEN`、`mergedAt=null`、`headRefOid=c524351`、`baseRefName=main`；
  - `git ls-remote origin refs/heads/main` → `fa57a07`（GitHub 与本地共享仓 `/Users/cookie/workspace/ispeech` 的 main 同一个 SHA）；
  - `git branch -r --contains c524351` → 只有 `origin/feat/voca-46-ux-46dp-space-period` 与 `refs/pull/18/head`；
  - 远端两条 release 分支 `release/voca-186-v1.6`（`eba7a61`）、`release/voca-r1`（`76d178c`）**都不包含** `c524351`/`d3b6f84`/`d283706`/`4d6363c`（四条 `merge-base --is-ancestor` 全部 rc=1）。
  → 结论：「已合并」在当前可见仓库里无法核实，本文按「未合并」写；若合并发生在另一个远端（Codeup 侧），请给出该远端与分支名，§13 需要在那上面复跑。
- 两个 PR 别混：**PR #18 = A 包的变更集**（产品代码 16 个文件，本文「变更面内 / 拦路项」所说的「本 PR」都指它）；**PR #25 = 本 issue 的交付 PR**（branch `test/voca-37-stage3-regression`，只含 `docs/` 报告 + `tools/qa/` 工装与证据，不含产品代码）。
- 交付物范围（计数口径，别和单个提交混）：报告 = 本文件；工装 = `tools/qa/voca-37/*.py` + `README.md`；证据 = `tools/qa/voca-37/evidence/`（54 份当场 stdout 原文）+ `shots/`（7 张截图）。三个口径分开写：单个提交的口径（`git show --stat` / `git diff --stat` 当场可核）：返修提交 `57f5f5b` 13 files +597/−35；第二轮返修提交 `4009fb4` 11 files +609/−39。**PR #25 的累计口径不在本文写死数字**——它每推一次就漂一点，写进报告就会立刻变成假数（上一版把「PR 累计 61 files +2029」和「单提交 13 files +597」混在同一行就是这个坑）。现算命令：`gh pr view 25 --json files --jq '{n:(.files|length),add:([.files[].additions]|add),del:([.files[].deletions]|add)}'`；本轮最后一次推送的实测值写在 VOCA-47 的交付评论里（带当时的 head SHA），以那条为准。
- 规格权威：`agent/ux/voca-45-spec` @ `28c1cac`（main 上的 602 行翻页版与 `agent/ux/voca-45` @ `fc564aa` 均已过期，本报告不引用）
- 执行环境：本地 AVD `ispeech_test`（1080×2340 / 440dpi / arm64 / 2G），Android 14 模拟器；对照基准为同机搜狗 `com.sohu.inputmethod.sogou/.SogouIME`
- 执行人：测试工程师（QA）· 2026-11-17 03:40–05:20 首测，06:55–07:45 按质检 6 条返修，12:25–12:45 第二轮 4 条，14:03–14:09 第三轮 5 条，**15:05–16:20 第五轮（裁决 6 条打回项：重测 AC-1/截字/spec-AC-10、补 spec-AC-9 1500ms 实测档、开关值披露、作废自查），16:50–17:25 集成候选 B 包回归**（本地时区）
- 结论：**条件性 no-go**。**PR #18 变更面拦路 3 条**，任一成立即拦：① 真机口径缺失——「占屏比 ≤1pp」在按真机参数（520dpi / font_scale 1.15）复现时 A 包 **+7.09pp**、B 包 **+5.39pp**，模拟器无权定稿；② AC-2 硬件/注入键路径 4 组全阴性（开关当场读到 `true`、间隔 0ms 仍不出句号）→ **P1 D1**，B 包复跑仍阴性（§13.3）；③ **新增 `spec-AC-10a` FAIL**——520dpi / fs1.15 下完整可见候选只有 3 条（硬门限 ≥4），A、B 两包同形（§3、§13.2），按裁决 §5 记 PR #18 变更面缺陷、交 PM 定级。另有 `v1.6-AC9`（=VOCA-170，**P0**，PR #9 未合入 main）一条：**在 B 包上已验证修复**（§13.3），但它今天仍在 main 上，v1.6 合并窗口门禁按裁决 §2-2② 计 D2。门禁：P0/P1 未清零不判 go。

---

## 1. 结论先行

| 层 | 判定 | 说明 |
|---|---|---|
| 模拟器可判面（spec AC-3/4/5/6/7、G1–G7、面板路径） | **全绿**（1 项 P2 观察、2 项规格/实现差异记录） | 见 §3–§6；AC-4~AC-7 的键名↔方向按代码订正（步长/钳制结论不变），`ev_ac47b.txt` |
| spec AC-1 恒高（空态 vs **有候选**态） | **PASS**（A 包 4 组 Δ=0；B 包 4 组 Δ=0） | 旧三组 Δ 因「chip-only 伪装态」**作废后重测**，闸门见 §2.5、数据见 §3；`ev_r5_440.txt` / `ev_r5_520_fs115.txt` / `ev_r5u_440.txt` / `ev_r5u_520.txt` |
| **spec AC-10a 完整可见候选 ≥4（硬门限）** | **440dpi PASS / 520dpi·fs1.15 FAIL（3 条）** | A、B 两包同形 → 记 PR #18 变更面缺陷，交 PM 定级（裁决 §5）；`ev_r5_520_fs115.txt`、`ev_r5u_520.txt` |
| spec AC-10b 混合词长 | **只入账，不判**；520/fs1.15 连闸门都过不了（`GATE reason=cand_low rc=13`） | 达不到 4 条属规格 §2.1 与实现的真实冲突 → 回 UX/UI + PM，不由测试自处 |
| AC-2 双击空格句号（按路径拆开） | **软键 PASS / 硬件·注入键 FAIL** | 开关态当场从 DataStore 读出 `true`；硬件键 4 组全阴性 → P1 D1；B 包复跑仍阴性（§13.3） |
| spec AC-9 退格长按（`>3` 字 @1.5s） | **PASS（1500ms 实测档）** | A 包 1500ms→删 13、B 包→删 9，均 >3；旧「1.5s≈10 字」是两点内插值，已标注非实测（§3） |
| `FAST=45ms` 快档 | **维持「本轮未观测」** | 与 AC 判定分开写，指向 §9-7（注入式长按上限落在 90ms 慢档，口径要换） |
| PR #18 变更面内撞出的产品缺陷 | **2 条：D1（P1，硬件键路径）+ D3（spec-AC-10a FAIL，交 PM 定级）** | 拦路，见 §3.4、§3.2 |
| `v1.6-AC9` = VOCA-170（D2 设置页崩溃） | **P0（继承 PM 11-15 裁定）**；A 包第三次复现坐实，**B 包已验证修复** | PR #9 `d3b6f84` 仍未合入 main；两个口径分开：PR #18 变更面拦路**不计 D2**，v1.6 合并窗口门禁**计 D2**。见 §3.3 |
| 真机可判面（绝对占屏 pp、520dpi 键高观感、真机手指触摸） | **未取证** | 见 §5、§9；按 issue 约束不得写「通过」 |
| 发布建议 | **条件性 no-go** | 门禁：P0（`v1.6-AC9`，PR #9 未合）+ P1（D1）未清零 + spec-AC-10a FAIL 待定级 + 真机 pp 未定稿 |

## 2. GATE 0（前置门禁）

```
$ adb -s emulator-5554 shell ime list -a -s
com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
com.ispeech.ime/com.ispeech.input.IspeechImeService
com.sohu.inputmethod.sogou/.SogouIME

$ adb -s emulator-5554 shell pm list packages | grep -i sogou
package:com.sohu.inputmethod.sogou
```

搜狗可用 → GATE 0 通过，允许做占屏对照。规格 11-16 的 978px 只作为历史参照，本轮所有对照值均为当场重测（见 §5）。

## 2.5 作废清单与重测闸门（裁决 §1.2 / §6-打回项 1）

**作废范围扩到两批读数**，同一根因：`CandidateView.kt:76-77` 在候选列表为空时把 `scrollView` 置 `GONE`，于是「敲了码但一条候选都没出」的帧里，候选带上**只剩左侧 chip 的墨迹**；旧工装把 `ink_extent` 打在整个带宽（x 16..1064）上，等于一直在量 chip。

| 作废项 | 旧记载 | 作废原因 |
|---|---|---|
| 旧 §4 三行截字（440/fs1.0、440/fs1.15、520/fs1.15 的 `ink_top/ink_bot/top_pad/bot_pad`） | `ev_fs115_440_fs10.txt` 1400–1441、`ev_fs115_440_final.txt` 1397–1445、`ev_fs115_520_ink.txt` 1185–1284 | 采样窗口含 chip，`cand_count` 未知 → 不能证明量的是候选字身 |
| 旧 §3 的 **AC-1 三组 Δ**（988→988 ×3、988→988、1168→1168） | §3 AC-1 行 | 「空态 → 组合态」的组合态可能是 chip-only 态，恒高契约的另外半边（有候选时仍不涨高）没被证明 |
| 旧 §3 AC-9 行「1.5s≈10 字」 | `ev_ac9.txt` 只有 200/1000/2000 三档 | 两点内插值，非实测（裁决 §6-打回项 6）→ 本轮补 1500ms 实测档 |

**自证（不是叙述，是当场重扫）**：`tools/qa/voca-37/voidcheck.py` 用旧报告的同一窗口（x 16..1064、行=带高 ±1px）重扫交付帧，`old_ink_rows` 与旧记载**逐位相同**（1400..1441 / 1397..1445 / 1185..1284）→ 复算口径对得上；再用新口径（只在 chip 右沿 +1 之后量字身）扫同一批帧，`cand_counts = 440/fs1.0: 0、440/fs1.15: 0、520/fs1.15: 1`，`ink_entirely_inside_chip = 440/fs1.0, 440/fs1.15` → 三帧全部低于「可见候选 ≥4」闸门，**作废成立**（`ev_r5_voidcheck.txt`，rc=0）。裁决 §6-3 要求的「`x>240` 命中列必须 > 0」在旧帧上是 0 —— 也就是旧数据确实还在量 chip。

**重测闸门（先于读数，任一不成立就拒绝出数）**，`fs115.py` 第五轮版：

1. `drive.key_map()` 从当场截图反推键位（不写死像素），失败 → `GATE reason=key_map_fail rc=16`；
2. 敲码后**回读宿主文本 == 所敲串**，不符 → `rc=10`；
3. `chip 文本 == 所敲串` 且 chip 盒存在且在候选区左边 → 否则 `rc=12`；
4. **可见候选数 ≥ 4**（`cand_count`，chip 右沿 +1 之后的字身连通块数）→ 否则 `GATE reason=cand_low rc=13`；
5. 候选池 ≥ 8（`=` 键滚动 + 互相关去重数出）→ 否则 `rc=14`；滚动后未回原点 `rc=15`；IME 不是本包 `rc=17`；宿主没起来 `rc=18`；
6. 闸门全过才打一行 `GATES`（把裁决 §5 的五个 Given 逐条点名），再打 `RESULT` / `INK` / `CAND` / `AC10`；拒绝出数时 **stdout 不出现 `RESULT`/`INK`/`delta`**。

**520dpi 的 `dashu` 那一组就是被闸门 4 拦下的**：`GATE reason=cand_low rc=13 cand_count=3 need=4 x0=227`（`ev_r5_520_fs115.txt`、`ev_r5u_520.txt`）——这不是「测出 FAIL」，是「这一档不给数」，两包一致。

**工装共因缺陷（裁决 §6-6，本轮修在工装层，不动判据）**：`drive.tap_keys` 原来既没回读校验、又写死 440dpi 像素，会污染一切「先打字再量」的用例。现在 `tap_keys` 敲完必须回读宿主文本比对，不一致直接 GATE；`fs115.py`/`ac9.py`/`ac2.py` 的键位一律 `key_map()` 当场反推。**第五轮新增一条同类修复**：VOCA-184（`d283706`）把字母键行内留白从 4dp 收到 1dp 后，行间隙只剩 ~5px，`key_map` 原来的 `merge_gap=8` 会把 qwerty 行和 asdf 行粘成一个带 → 段数不再是 10/9/7 → 整套键位反推失效（B 包首跑 `GATE reason=key_map_fail rc=16`）。已加「合并后高度不得超过 52dp 单行上限」的约束（`drive.py:219-229`），A/B 两包都能标定成功。**这条是合并窗口会踩的坑，不是产品缺陷**，但必须写进 §13。

## 3. 恒高与判据逐条结果（按规格 §4 编号重排）

编号与行号一律锚规格权威 `docs/keyboard-ux-spec.md` @ `28c1cac`：AC-1 `:396`｜AC-2 `:410`｜AC-3 `:423`｜AC-4 `:432`｜AC-5 `:446`｜AC-6 `:459`｜AC-7 `:469`｜AC-8 `:482`｜AC-9 `:486`｜AC-10 由裁决 §5 升格（规格正文 `:215` 那句的副作用约束）。G1–G7 是质检门禁项、不是规格 AC，另表（§3.2）。

测量口径（全部按规格 §8.1 / §8.4）：`dumpsys window displays` 的 `InsetsSource type=ime` frame 取键盘高度；滚动位移用候选带列剖面互相关（step=2px，±600，取最小平均绝对误差，residual>5 视为不可判）；文本真值取宿主输入框 = Settings 搜索框 `open_search_view_edit_text`（由 `drive.host_ready()` 每轮 force-stop 两个 Settings 包后重起，空态读到的是它的占位文案「搜索设置」而不是输入内容）。测前一律 `settings put secure autofill_service null`（否则 GMS 自动填充会多报一个 `type=ime` source，把 inset 从 988 抬到 1260）。

**键名↔方向的口径（本轮返修项 2 的根因）**：不靠人记，由代码定义——`IspeechImeService.kt:612-614`（行号锚 **c524351**；main `fa57a07` 上同一段在 `:601-603`。偏移已核：PR #18 在该文件 +90/−20，前 200 行内插了 11 行（`:11`/`:20` 两个 import + `:107` 3 行 + `:195` 6 行），601+11=612 对得上——引行号不写构建版本就会核空）里 `KEYCODE_MINUS` → `scrollCandidates(-1)`、`KEYCODE_EQUALS` → `scrollCandidates(+1)`；`CandidateView.kt:90-92,199` 里 `scrollCandidates(steps)` → `smoothScrollBy(steps * dp(72), 0)`，注释写明「steps 为正向右（看后面的候选）」。互相关返回值 `shift` 的符号约定是「正 = 候选内容左移 = 视口向右」，所以 `=` 应为 +198、`-` 应为 −198，实测与代码同向。`ac47.py` 把 keycode、代码出处、期望档位（`KEYMAP` 行）和每一档的实际符号一起打进 stdout，杜绝再次出现「数值对、键名反」。
   **口径不靠叙述，靠闸门**（本轮实测补上，见 `ev_ac47_probe.txt` / `ev_ac47_falsify.txt`）：`ac47.py` 在 sweep 前先做可滚性探针——停在左端按一次 fwd 方向的键，位移必须是 +198；把参数对调成 `--fwd 69 --rev 70` 再跑，同一台机同一数据态下探针读到 `shift=0 err=0.0`，脚本直接 `GATE reason=probe_zero rc=4 blocked=…` 退出，**不再产出那种「看着像到端钳制、其实是恒 0」的 sweep**。闸门拒绝出数时**每种前提失效给一个 `reason=` 和一个退出码**（4=probe_zero / 5=host_buffer / 6=bg_low / 7=frame_none / 8=host_not_ready）——上一版三种前提全退 4，而「宿主缓冲有残留」那种还**不打 `PRE_PROBE` 行**，第三人照 §10 复跑就会把「工装没跑起来」读成「证伪成立」；本轮已把两种失效各复跑一份（`ev_ac47_gate_probe_zero.txt` / `ev_ac47_gate_host_buffer.txt`），§10-4b 也补了跑前复位（13:06 我第一次跑对调档时闸门还没上，拿到的就是一份全 0 的假钳制输出，已删）。同时补底色闸门：`ime_frame()` 报 988 不代表键盘画在 inset 上（别的 run 收掉键盘后 inset 会残留），候选带底色对本包 `#F4F5F7` 的命中率低于 50% 即拒绝出数。

### 3.1 规格 §4 判据逐条（编号 = 规格编号，行号 = `28c1cac` 规格行号）

| 规格判据（行号） | 期望 | 实测 | 判定 |
|---|---|---|---|
| **AC-1** 候选条空态高度不变（`:396`） | 空态 → **有候选态** Δ=0 | **A 包（c524351）**：440/fs1.0 `shijian` 988→988、`dashu` 988→988；440/fs1.15 988→988 ×2；520/fs1.15 `shijian` 1168→1168（`dashu` 被闸门拦下不给数）。**B 包（集成候选）**：440/fs1.0 952→952 ×2、440/fs1.15 952→952 ×2、520/fs1.15 1128→1128。每组都先过 §2.5 五道闸门（`GATES` 行：`readback_equals_code=True`、`chip_text_equals_code=True`、`scrollview_visible=True`、`cand_visible≥4`、`pool_at_least≥8`） | **PASS**（`ev_r5_440.txt`、`ev_r5_520_fs115.txt`、`ev_r5u_440.txt`、`ev_r5u_520.txt`）。旧三组 Δ 已作废重测，见 §2.5 |
| **AC-2** 双击空格出句号（`:410`）**软键路径** | 窗口内第二次空格出「。」 | `ac2.py … soft`：连点两次（wall≈0）→`。`；两次 adb 调用（68.2ms）→`。`；间隔 577.1ms →`␣␣`（窗口外，语义正确）；三连点 →`。␣`。同轮 `SWITCH state=True src=datastore raw_hex=12020801` | **PASS**（`ev_ac2_soft.txt`）。三项披露见 §3.1a |
| **AC-2** **硬件·注入键路径** | 同上 | A 包：`input keyevent 62 62`（单进程，间隔≈0）→`␣␣`；两次 adb（39.1ms）→`␣␣`；569.6ms →`␣␣`；三连发 →`␣␣␣`。**B 包复跑（17:20）**：单进程两连发（`wall_between_ms=0`）→`␣␣`；两次 adb（53.4ms）→`␣␣`；549.6ms →`␣␣`；三连发 →`␣␣␣`，同轮 `SWITCH state=True raw_hex=12020801`、`IDENT match=True` 双端 | **FAIL（P1 · D1）** — 两包一致，间隔已排除工装因素；根因见 §3.3 D1（`ev_ac2_hw.txt`、`ev_r5u_ac2_hw.txt`） |
| **AC-2** 反例（开关关，两路径各一组） | 只出空格 | 软键 `␣␣`、硬件键 `␣␣`，同轮 `SWITCH state=False raw_hex=12020800`（键存在且值为 false，不是「没写过」）；B 包复跑同样 `state=False` → `␣␣` | **PASS**（`ev_ac2_soft.txt` / `ev_ac2_hw.txt` / `ev_r5u_ac2_hw.txt`） |
| **AC-3** 双击空格不与「上屏首选」打架（`:423`） | 组合非空时首格上屏首选、不出「。」 | 打 `shi`→首格=`是`；再按一次=`是疑是地上霜`（继续上屏联想/候选，未插句号） | **PASS**。三项披露见 §3.1a |
| **AC-4** 连续横滚生效（`:432`，用硬件键步长定标） | `=`（KEYCODE_EQUALS / 70）→ `scrollCandidates(+1)` = +198±6px（+72dp @440） | `ac47.py`：连按 24 次每档 +198（err ≤3.05，cum 4752）；第 25 次 +140（err 5.05 → 按自家口径不可判，正是快到位时 `smoothScrollBy` 被截短）；第 26 次起恒 0 | **PASS**（`ev_ac47b.txt`） |
| **AC-5** 左右边界不循环（`:446`） | `-`（KEYCODE_MINUS / 69）→ −198px，到端钳制 | 单档 −198 err 2.56；反向连按 23 档 −198 + 末档 −140（err 4.18）后归零，左端钳制在第 25 次，cum −4694 | **PASS**（`ev_ac47b.txt`） |
| **AC-6** 起手区约束：带外手势不得驱动横滚（`:459`） | 带外位移=0 | 用旧「G4」行数据（裁决 §3 指定归属）：键区横滑 shift=0（err 0.0）；工具栏横滑 shift=0（err 0.0）；起点在候选带**下沿外 23px**（y=1500）竖滑 dy=60 → shift=0、不上屏 | **PASS（范围限定）**（`ev_ac47b.txt`、`ev_vswipe_c.txt`）。旧 §3「AC-6 边界不绕回」那一行按规格编号属于 **AC-5**，其 cum 记账口径说明保留在 §3.1b |
| **AC-7** 竖向/斜向不误滚、不误上屏（`:469`） | 滚动态下纯竖滑不驱动横滚；斜滑方向与直接操作一致 | `ev_vswipe_a.txt` 的 `commit_dy200`（dy=200 → 不上屏、不横滚）+ `ev_vswipe_b.txt` 的 `dy110_dx160`（斜滑 −264px、err 4.37 可判）——**幅度口径替代**：规格写的是「小幅度竖滑/斜滑」，本轮可判样本落在 dy=200（大幅度不上屏）与 dy=110/dx=160（斜滑方向），没有 dy<90 的可判横滚样本 | **PASS（幅度口径替代，n=1 的斜滑只够定方向）** |
| **AC-8** 按键音效（`:482`） | 本期不采纳、无验收项 | 不测 | **N/A**（规格原文即「本期不采纳」） |
| **AC-9** 长按连删保留（`:486`，`:488-491` 判据「按住 1.5s → 删除字符数 > 3」） | 两句分开判 | ① **1.5s → >3 字：PASS（实测）**。A 包 `ac9.py`：200ms→1、1000ms→7、**1500ms→13**、2000ms→21（`rate_ms` 200.0/142.9/115.4/95.2）；B 包：200→1、1000→8、**1500→9**、2000→17。旧 `ev_ac9.txt` 只有 200/1000/2000 三档，当时写的「1.5s≈10 字」是**按现有两点内推、非实测读数**，本轮已补 1500ms 实测档取代它。② **阶梯单调/加速关系**：两包都成立（越按越快、删除数随时间超线性）。判据用的宿主文本是**已上屏中文**（逐码 space 上屏首选），因为组合缓冲里的退格走删 composing 的另一条路径，不是 AC-9 说的「删除字符」 | **PASS（实测）**（`ev_r5_ac9.txt`、`ev_r5u_ac9.txt`）。工装披露：`PROBE_RETRY first_deleted=0` —— 注入式同点 `input swipe` 长按偶发丢手势，脚本先做一次 200ms 探针（必须恰好删 1 字）才允许后续档位出数，长度一律连读稳定值（`hold()`） |
| **AC-10a** 完整可见候选 ≥4（裁决 §5 升格，硬门限） | 实际渲染前 4 条完整可见 | 440dpi/fs1.0：`shijian` full=4（cand=5）、`dashu` full=4；440/fs1.15：`shijian` full=4、`dashu` full=4（cand=4）。**520dpi/fs1.15：`shijian` full=3 < 4 → FAIL**（第 4 条 x=961..1059 被屏幕右边界切掉，`badge_clipped=True`）；`dashu` 该档 `GATE reason=cand_low rc=13` 不给数。**B 包同形**：440 全 4、520 `shijian` full=3 FAIL、`dashu` GATE | **440 PASS / 520·fs1.15 FAIL** → 记 PR #18 变更面缺陷 **D3**，交 PM 定级（§3.3） |
| **AC-10b** 混合词长（含 4 字长词） | 只入账不判 | 440/fs1.0 `dashu` 的 idx1 是 3 字词（glyph_w_dp=68.4、pitch 278），440/fs1.15 `dashu` idx1 glyph_w_dp=75.6/pitch 298 仍完整可见 → 440 档长词不破坏 ≥4；520/fs1.15 长词档连闸门都过不了（可见候选 3 < 4） | **记录**：达不到 4 条 → 规格 §2.1 与实现的真实冲突，**回 UX/UI + PM 裁**（缩 chip 宽度 vs 放宽词长口径），不由测试自处 |

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
| G1 无 `N/M` 页码文案 | 不出现 | 候选条无页码绘制路径；`aapt2 dump resources` 无 `candidate_page_*` | **PASS** |
| G3 资源表无翻页控件 | 不出现 | 全量 dump 仅命中 `drawable/ic_settings_gear`，用于工具栏「设置」键，与候选条无关 | **PASS** |
| G4 带外手势不驱动滚动 | 位移=0 | 数据已按规格编号归入 **AC-6**（§3.1）；旧「候选条竖滑/斜滑 shift=−870」按 residual>5 口径作废 | **PASS（范围限定）** |
| G6 编辑后偏移复位 | 归零 | 滚动态 shift=762 → 编辑宿主后 shift=0 | **PASS** |
| G2 / G7 | 只记用户可见影响 | 见 §8 | 记录 |

**键名↔方向的口径**：不靠人记，由代码定义——`IspeechImeService.kt:612-614`（行号锚 **c524351**；main `fa57a07` 上同一段在 `:601-603`。偏移已核：PR #18 在该文件 +90/−20，前 200 行内插了 11 行（`:11`/`:20` 两个 import + `:107` 3 行 + `:195` 6 行），601+11=612 对得上——引行号不写构建版本就会核空）里 `KEYCODE_MINUS` → `scrollCandidates(-1)`、`KEYCODE_EQUALS` → `scrollCandidates(+1)`；`CandidateView.kt:90-92,199` 里 `scrollCandidates(steps)` → `smoothScrollBy(steps * dp(72), 0)`，注释写明「steps 为正向右（看后面的候选）」。互相关 `shift` 的符号约定是「正 = 候选内容左移 = 视口向右」，所以 `=` 应为 +198、`-` 应为 −198，实测与代码同向。`ac47.py` 把 keycode、代码出处、期望档位（`KEYMAP` 行）和每档实际符号一起打进 stdout，杜绝「数值对、键名反」。
   **口径不靠叙述，靠闸门**（`ev_ac47_probe.txt` / `ev_ac47_falsify.txt`）：`ac47.py` 在 sweep 前先做可滚性探针——停在左端按一次 fwd 方向的键，位移必须是 +198；把参数对调成 `--fwd 69 --rev 70` 再跑，同一台机同一数据态下探针读到 `shift=0 err=0.0`，脚本直接 `GATE reason=probe_zero rc=4` 退出，**不再产出那种「看着像到端钳制、其实是恒 0」的 sweep**。每种前提失效给一个 `reason=` 和一个退出码（4=probe_zero / 5=host_buffer / 6=bg_low / 7=frame_none / 8=host_not_ready）——上一版三种前提全退 4，而「宿主缓冲有残留」那种还**不打 `PRE_PROBE` 行**，第三人照 §10 复跑会把「工装没跑起来」读成「证伪成立」；两种失效各复跑一份（`ev_ac47_gate_probe_zero.txt` / `ev_ac47_gate_host_buffer.txt`）。底色闸门：`ime_frame()` 报 988 不代表键盘画在 inset 上（别的 run 收掉键盘后 inset 会残留），候选带底色对本包 `#F4F5F7` 命中率低于 50% 即拒绝出数。

### 3.3 缺陷单（D1 P1 · D2 P0=`v1.6-AC9`/VOCA-170 · D3 待 PM 定级）

**D1 · P1 · AC-2 双击空格句号在硬件/注入键路径完全不生效**

- 环境：`emulator-5554` / `c524351` / 440dpi / fs1.0 / 中文拼音态（`MODE mode=chinese`）/ 开关 `double_space_period=true`（DataStore 原始字节 `12020801`，同一份 stdout 的 `SWITCH` 行）。
- 复现：`adb shell input keyevent 62 62`（一次 adb 调用内两连发，注入间隔≈0）→ 宿主 `␣␣`；`input keyevent 62` ×2（实测间隔 39.1ms）→ `␣␣`；三连发 → `␣␣␣`。
- 期望（规格 AC-2）：`。`，长度 1。实际：`␣␣`，长度 2。
- 根因（代码级）：`IspeechImeService.kt:591-597` 对 `KEYCODE_SPACE` 先判 `buffer.isEmpty() && !aiReplyActive && !associationActive` 就 `return false` —— 空缓冲时事件根本不进 `handleSpace()`，空格由宿主 EditText 自己吞掉，`lastSpaceCommitAt` 永远是 null，判定链入口都不到。软键走 `KeyAction.SPACE`（`:458`）直接调 `handleSpace()`，所以只有软键生效。
- 为什么判 P1 而不是「差异记录」：规格 AC-2 的 When 只写「在 300ms 内连按两次空格键」，没限定软键；同一份规格把 KeyEvent 路径当唯一可复现取证口径（AC-4），并在无障碍条款里要求「外接键盘与物理键盘同样走这条」。硬件/蓝牙键盘用户把开关打开也拿不到句号，属验收条款不成立。
- 附带收口：规格开放问题 #10（「keyevent 与触摸等价性未验证」）本轮给出答案——**不等价**。
- 归属：前端（`IspeechImeService` 空格键路径）修复；UX 需裁定硬件键是否纳入 AC-2 范围（若明确不纳入，要把规格 When 改成「软键盘空格键」并回改本报告判定）。

**D2 · P0（继承 PM 裁定，非本报告新判）· 进入设置页「键盘与输入」即 NPE 崩掉整个输入法进程 = 已有 issue [VOCA-170]（`v1.6-AC9`）**

> 别名先说清（裁决 §2-4）：本链路上「AC-9」指两个东西——**`v1.6-AC9`** = v1.6 内测门禁第 9 条 = VOCA-170 的修复（PR #9）；**`spec-AC9`** = 键盘规格 §4 AC-9 = 长按连删（本文 §3.1 那一行）。本轮起报告与工装一律带前缀写，不再裸写「AC-9」。

- **归属与定级（裁决 §2-1，本报告不再重开门禁问题）**：这条缺陷**已有 issue VOCA-170**，PM 于 11-15 裁定 **P0 + v1.6 内测阻断**，写进 VOCA-183 交付计划 §五的 AC-9。本报告**继承 P0**，不记 P1、不写「属 PM/人类的范围裁决、本报告不替它判」——把它当新题重开就是越权。
- **本轮复现的性质**：**同一缺陷的第三次复现 + 追加证据**（第一次 = 本 issue 第四轮 A 包当场崩，`ev_crash_kbsettings.txt`；第二次 = 质检专员在 `fa57a07` 自己的构建上同一时序同一栈；第三次 = 本轮把它脚本化成 `d2check.py` 后在 A 包重跑，`ev_r5u_d2_control_c524351.txt`）。
- **两个口径分开写死，互不覆盖（裁决 §2-2）**：
  - ① **PR #18 变更面拦路** = D1 + 真机 pp（+ 本轮新增 D3）——**不计 D2**，因为 PR #18 没碰它的代码（依据见下）；
  - ② **v1.6 合并窗口门禁** = **含 D2（P0，PR #9 未合入 main）**。
- **行号漂移，三个版本列全（裁决 §2-3；不写版本就会核空）**：

  | 版本 | collector 写入行 | `MutableStateFlow` 声明行 | 崩点 |
  |---|---|---|---|
  | VOCA-170 原文（`fa57a07` 之前它引用的版本） | `:34` / `:37` | `:101` / `:104` | — |
  | main `fa57a07` | `:35` / `:38` | `:102` / `:105` | `:35` |
  | 被测 A 包 `c524351` | `:35` / `:38` | `:107` / `:110` | **`:35`**（`chineseInputMethodFlow` 那个 collector 的 `emit`） |

- **不是 PR #18 引入的四条依据**（都能当场复跑）：
  1. `git diff --numstat fa57a07...c524351 -- app/src/main/java/com/ispeech/settings/keyboard/KeyboardViewModel.kt` → `5  0`：只有新增的 `setDoubleSpacePeriod()` setter（`c524351` 的 `:102-106`），三个 collector 与两个 `MutableStateFlow` 声明一字未动；
  2. `git blame -L 34,39 c524351` → `:34/36/37/38/39` = `904a98d`（Mini，2026-11-09），崩点 `:35` = `2a1883e9`（Lin，2026-11-10，VOCA-29）；两个 SHA 都 `git merge-base --is-ancestor <sha> fa57a07` 成立，而 PR #18 的提交清单只有 `e528c08` + `c524351`；
  3. 反向自查：`git diff -U0 fa57a07...c524351 -- app/src | grep '^+' | grep -E 'viewModelScope|MutableStateFlow|collect'` 在 16 个变更文件的全部新增行里**只命中 1 行**（那个 setter 自己的 launch）；
  4. 质检专员在 `fa57a07` 构建上复现到同一行、同一栈的 `FATAL EXCEPTION`（PID 13926）。本报告上一轮写的「PR #18 在 `:38-39` 新增第二个同形 collector，把暴露面翻倍」是**错误归因**，已删除。
- **修复验证（本轮新增，B 包）**：PR #9 的 `d3b6f84` 把两个 `MutableStateFlow` 声明前移到 `init` 之前（并加 `KeyboardViewModelInitOrderTest` 锁死不变量）。集成候选 B 包 = `c524351` + `d3b6f84` 合并后，同一脚本、同一时序：**`fatal_count=0`、IME 进程 pid 19562 前后不变、页面正常进入**（`ev_r5u_d2_union.txt`）；**阳性对照**：同一脚本装回 A 包（不含 `d3b6f84`）当场复现 `FATAL EXCEPTION` ×2、栈顶 `KeyboardViewModel.kt:35`、pid 18755→19089 重启（`ev_r5u_d2_control_c524351.txt`）。单测侧 `KeyboardViewModelInitOrderTest tests=2 failures=0`（§7）。→ **修复有效，但 PR #9 仍未合入 main，v1.6 门禁按口径② 仍然拦路**。
- **本轮取证影响**：AC-2 的开关置位因此改走输入法内配置面板（齿轮键 → `openPanel(Panel.SETTINGS)`，`IspeechImeService.kt:2071`），不经过会崩的 App 设置页。
- **升级给 PM 两点（裁决 §2 末尾，本轮复核后更新）**：① VOCA-170 看板状态与 main 事实不符——裁决当时读到 `status=done`，本轮 `multica issue get VOCA-170` 实测 **`status=in_review`**（`updated_at=2026-11-17T07:26:53Z`），PR #9 仍 `OPEN`、`d3b6f84` 不是 `fa57a07` 的祖先 → 缺陷仍在 main，要么合 PR #9、要么把状态退回；② PR #9 进哪个合并窗口仍需要窗口号，不是「待定」。

**D3 · 待 PM 定级（本轮新增，PR #18 变更面内）· `spec-AC-10a` 在 520dpi / font_scale 1.15 不成立**

- 环境：A 包与 B 包各测一轮，闸门全过（`GATES … cand_visible=4 pool_at_least=17`）、`IDENT match=True` 双端、宿主回读 == 所敲串。
- 实测：520dpi / fs1.15 敲 `shijian` → 可见候选 4 条，但**完整可见只有 3 条**：idx0 x=241..510（字身 282..465）、idx1/2 pitch 225 完整、**idx3 x=961..1059 被屏幕右边界切掉**（`x_end=1060`、`badge_clipped=True`）→ `AC10 verdict=FAIL full_count=3 need=4`。同档敲 `dashu`（含 3 字长词）→ 可见候选只剩 3 条，闸门 `GATE reason=cand_low rc=13` 直接拒绝出数。
- 期望（裁决 §5 AC-10a 硬门限）：实际渲染的前 4 条 ≥4 完整可见。**实际：3 条。**
- 用户可见影响：真机密度 + 系统字体放大 1.15 时，候选条一屏看不全 4 个词，而本期恰好「去掉翻页键、改横向滑动」——翻页补偿已经没了，只能靠滑动，滑动又没有可视线索。
- 两包同形（B 包 520/fs1.15 `shijian` full=3、`dashu` GATE）→ **不是 VOCA-170/184/207 带来的，也不是被它们掩盖的**。
- 归属：按裁决 §5 末句「若测出 FAIL，记 PR #18 变更面缺陷、交 PM 定级」——本报告不定级、不替 PM 决定能否接受；AC-10b（混合词长）那部分按裁决回 **UX/UI + PM** 裁「缩 chip 宽度 vs 放宽 §2.1 词长口径」，不由测试自处。
- 复跑：`python3 tools/qa/voca-37/fs115.py <apk> 520 1.15 shijian,dashu`（§10-2）。

## 4. 命名必证项：恒高 46dp × font_scale 1.15 会不会截字（第五轮重测）

规格点名的本轮最可能新缺陷：候选条用固定 `layout_height=46dp`（`candidate_view.xml:10`，注释说明「用 height 而不是 minHeight——候选字号是 sp，minHeight 兜不住 font_scale 放大后的内容高度」），内行 `minHeight=38dp`；1.15 下内容 38.2dp 被压进 38dp 可能截字。

**旧 §4 的三行墨迹/留白数据已作废**（chip-only 伪装态，见 §2.5），下表是带闸门重测后的新口径：字身墨迹只在 **chip 右沿 + 1** 之后量，角标圆片按外形剔除，`tangent_items` = 与带内沿相切（= 被截）的候选条数。

| 参数 / 包 | 键盘高（空态→有候选态） | 候选带 | 字身墨迹（chip 右侧） | 上下留白 | 截字判定 | 原文证据 |
|---|---|---|---|---|---|---|
| 440/fs1.0 · A | 988 → 988（Δ=0） | 126px（46dp）、内沿 1363..1467 | `shijian` 1393..1440、`dashu` 1393..1440 | 顶 30 / 底 26 | **`tangent_items=0` → 不截** | `ev_r5_440.txt` |
| 440/fs1.15 · A | 988 → 988（Δ=0） | 126px、内沿 1363..1467 | `shijian` 1395..1447、`dashu` 1395..1447 | 顶 32 / **底 19** | 不截，但**底部余量只剩 19px** | `ev_r5_440.txt` |
| 520/fs1.15 · A | 1168 → 1168（Δ=0） | 150px、内沿 1185..1309 | `shijian` 1223..1284（ink_h 62） | 顶 38 / **底 24** | 不截；同档 AC-10a FAIL（D3，横向问题不是纵向） | `ev_r5_520_fs115.txt` |
| 440/fs1.0 · B | 952 → 952（Δ=0） | 126px、内沿 1399..1503 | 1429..1476（ink_h 48） | 顶 30 / 底 26 | 不截 | `ev_r5u_440.txt` |
| 440/fs1.15 · B | 952 → 952（Δ=0） | 126px、内沿 1399..1503 | 1431..1483（ink_h 53） | 顶 32 / **底 19** | 不截 | `ev_r5u_440.txt` |
| 520/fs1.15 · B | 1128 → 1128（Δ=0） | 150px、内沿 1225..1349 | 1263..1324（ink_h 62） | 顶 38 / **底 24** | 不截；AC-10a 仍 FAIL（3 条） | `ev_r5u_520.txt` |

判读：1.15 下字身高 48→53px（440dpi，+10.4%）、62px（520dpi），键盘高度**一位未涨**（恒高契约在有候选态同样成立），`tangent_items` 全 0 → **纵向不截字**。但两件事必须留给真机与 UX：① 440/fs1.15 的**底部余量只剩 19px**、520/fs1.15 只剩 24px，真机厂商字体（非模拟器 Roboto，FMR 可能带字体替换）再高一点就先顶到下沿；② 520/fs1.15 真正不成立的是**横向**（只能完整看到 3 条 = D3），不是纵向。裁决 §7-1 说的「重测若显示 1.15 下字身真被 46dp 截 → 恒高与 ≥4 候选互斥」这一分支：**纵向没截、横向没够 4 条，所以互斥确实存在，但表现形是「宽度」不是「高度」**——谁让步属 UX/UI + PM 的范围裁决。

## 5. 占屏比：440dpi 达标，按真机参数复现时不达标（本轮 no-go 主因）

| 口径 | iSpeech | 搜狗 | 差值 | 预算 ≤1pp |
|---|---|---|---|---|
| A 包 440dpi / fs1.0（模拟器原生） | 988px = **42.22%** | 978px = 41.79% | **+0.43pp** | ✓ |
| A 包 520dpi / fs1.15（模拟器模拟真机参数，非真机定稿） | 1168px = **49.91%** | 1002px = 42.82% | **+7.09pp** | ✗ |
| **B 包（集成候选）440dpi / fs1.0** | **952px = 40.68%** | 978px = 41.79% | **−1.11pp** | ✓（本包比搜狗矮） |
| **B 包 520dpi / fs1.15** | **1128px = 48.21%** | 1002px = 42.82% | **+5.39pp** | ✗ |

机制：本包键高/候选条全部按 dp 布局，密度 440→520 时键盘像素高度同比放大（A 包 988→1168，×1.18 = 520/440）；搜狗用 px 资源（978→1002，×1.02）。所以 440dpi 下 +0.43pp 的余量在真机密度下会被放大成约 +7pp。**这不是 VOCA-46 引入的回归（52→46dp 反而收窄了差值），但它直接决定「占屏 ≤1pp」这条验收在真机上成不成立，模拟器无权定稿。**

**B 包带来的新事实（合并窗口要看的）**：VOCA-184 把字母键行内留白从 4dp 收到 1dp 之后，键盘总高从 988/1168 降到 **952/1128**（−36px / −40px），440dpi 由「比搜狗高 0.43pp」变成「比搜狗矮 1.11pp」，520dpi 的差值从 +7.09pp 收窄到 **+5.39pp**。也就是说 pp 这条拦路项的**具体数值取决于合并窗口最终收了哪些 PR**——本报告按 A、B 两个口径同时给数，真机定稿时必须写明当时的 main SHA，否则数字不可比。搜狗基准 978/1002 沿用同机当场重测值（`ev_sogou115.txt` / `ev_sogou520.txt`，本轮未重测搜狗，包体没变）。

→ 真机必做：520dpi / font_scale 1.15 下重测 iSpeech 与搜狗的 ime inset，给出定稿 pp 差。

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

## 7. 静态门禁与单测（c524351 当场执行）

```
check_static.py      SUMMARY: passed=6   failed=0 warned=0
check_crosscut.py    SUMMARY: passed=156 failed=0 warned=0
check_input_logic.py SUMMARY: passed=40  failed=0 warned=0     ← 此前「EmojiData FAIL」的说法作废
check_t9.py          SUMMARY: passed=87  failed=0 warned=1
./gradlew :app:testDebugUnitTest --tests "com.ispeech.input.*"  → BUILD SUCCESSFUL
  InputModePolicyTest  tests=5 failures=0
  SpaceKeyPolicyTest   tests=9 failures=0   （含 双击窗口/链条优先级/英文态/无障碍 600ms/中文标点关闭/光标前非空格不破坏性删除）
```

全量 `./gradlew :app:testDebugUnitTest` 有 1 条已知基线噪声 `AgentDispatcherTest > initializationError`（与本轮无关），故按 issue 口径用 `--tests "com.ispeech.input.*"` 过滤，不以此掩盖真实失败。

**B 包（集成候选 `cffc0cd`）当场执行**（16:55 构建、16:57 出结果）：

```
./gradlew :app:testDebugUnitTest --tests "com.ispeech.input.*" --tests "com.ispeech.settings.keyboard.*"  → EXIT=0
  input.InputModePolicyTest              tests=5 failures=0 errors=0 skipped=0
  input.SpaceKeyPolicyTest               tests=9 failures=0 errors=0 skipped=0
  input.KeyHitTesterTest                 tests=6 failures=0 errors=0 skipped=0   ← VOCA-184 新增（零死区命中）
  settings.keyboard.KeyboardViewModelInitOrderTest  tests=2 failures=0 errors=0 skipped=0  ← VOCA-170 新增（源码结构锁死 init 顺序）
```

合并没破坏任何一条守卫：**22 tests / 0 failures / 0 skipped**。原始出处 `voca47-union/app/build/test-results/testDebugUnitTest/TEST-*.xml`。B 包的静态门禁脚本（`check_*.py`）未重跑——三条 PR 里只有 VOCA-207 动了 `tools/verify/check_quick_trigger.py` / `check_phrase_csv_import.py`，属快捷字母域，不在本 issue 的键盘交互面上；要跑全量静态门禁应在真实合并窗口的产物上做（§13.4）。

**被测产物来源（第三人复现时的比对基准）**：A 包 = `c524351` 在专用检出 `…/voca-47-37061cb47d5d/voca47-build`（`git rev-parse HEAD` = `c524351e2e509ed294f49dd3d2f7a1bf29e07a4d`）里 `assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`，构建时间 11-17 15:25；B 包 = `cffc0cd` 在 `…/voca-47-37061cb47d5d/voca47-union` 里 `assembleDebug`，构建时间 11-17 16:55，整包 `sha256 = c302c89440a1e70db92017c11a45af1ebae2c77ad1b6f0156eb406c6866b72f1`（145,847,323 字节）。装到设备后 `ident()` 不按整包哈希比（签名/时间戳会漂），而是逐条目比 17 个 `classes*.dex` + `resources.arsc` 的 SHA256。

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
   - **`dy=40..90` 短竖滑会上屏（单列观察，交 UX 裁）**：按裁决 §3 的口径，这一族读数**不属于规格 AC-7 的判据对象**（AC-7 管的是「竖向/斜向不误滚、不误上屏」里的可判横滚与大幅度手势，本轮 AC-7 用 `ev_vswipe_a.txt` 的 `commit_dy200` + `ev_vswipe_b.txt` 的 `dy110_dx160` 两条，见 §3.1），所以**不重测、不改判**；但「带内 40–90px 的小竖滑被当成点击、直接上屏首选」是真实的用户可见行为（想横滑选词时误上屏），**单列交 UX 裁定是否算缺陷**。数据：`ev_vswipe_a.txt`（dy=40/60/80 上屏）、`ev_vswipe_c.txt`（dy=90 上屏 / dy=100 不上屏，阈值 90–100px ≈ 33–36dp）。

## 9. 未取证清单（真机 / 后续必须补）

| # | 项 | 为什么模拟器判不了 | 归属 |
|---|---|---|---|
| 1 | 520dpi / fs1.15 的定稿占屏 pp | 模拟器只是「模拟真机参数」，字体渲染与厂商 ROM 不同 | SRE + 人类评审（装真机跑） |
| 2 | §9.8 ③ T9 态 `-`/`=` 滚动、④ 上屏后偏移归零 | 模拟器软键坐标靠权重推算，面板态抢焦点；换真机或固化 uiautomator 定位后可测 | 测试（下轮补，非真机专属） |
| 3 | 语音键上滑取消、语音失败文案 | 需要真实音频输入 | 测试 + 前端 |
| 4 | TalkBack 真机朗读与 600ms 窗口 | 模拟器未装 TalkBack 服务态 | 测试 + UX |
| 5 | 真机手指触摸的滚动/长按手感（fling 不可复现，本轮一律用 `-`/`=` 与 `input swipe` 固定位移） | 触摸离散度 | 人类评审 |
| 6 | release 打包 + 真机安装 | 项目规则五：生产操作需人类确认 | 人类确认 |
| 7 | spec-AC-9 的 45ms 快档到底可达不可达 | **口径要先换**：`adb shell input keyevent --longpress`/注入式长按的实测上限就落在 90ms 慢档（4s→删 40 字→92.8ms/次，`ev_ac9d.txt`），拿注入时长反推永远看不到 45ms 档。下一轮改用 logcat 打点量两次删除的实际间隔，或真机手指长按定性。**这条与 spec-AC-9 的 AC 判定分开**——AC 判据（1.5s→>3 字）本轮已用 1500ms 实测档判 PASS（§3.1），45ms 是 §2.5 阶梯实现的另一件事 | 测试（logcat 口径）+ 人类（真机手指） |
| 8 | **PR #9（VOCA-170 修复 `d3b6f84`）未合入 main，而 VOCA-170 看板状态与事实不符** | 模拟器判不了「该不该进哪个合并窗口」：这是范围/窗口裁决。本轮核到的事实——`gh pr view 9` → `state=OPEN`、`mergedAt=null`；`git merge-base --is-ancestor d3b6f84 origin/main` → rc=1（缺陷仍在 main）；`multica issue get VOCA-170` → **`status=in_review`**（裁决 §2 当时读到的是 `done`，本轮实测已漂，`updated_at=2026-11-17T07:26:53Z`）。B 包已验证「合了就修好」（§3.3 D2），剩下的只是落地位置 | PM + 人类评审 |
| 9 | **VOCA-203 本体未取证** | 该 issue 现在 `in_progress`、**没有任何自己的实现提交**（阻塞在人类对「快捷字母长度基线」的答复，评论显示已停摆 63 小时）。本轮能覆盖的只有它的实现子单 VOCA-207（PR #11 `4d6363c`，仍 OPEN），且只覆盖到**键盘交互面**（候选条渲染、恒高、pp、退格阶梯）；快捷字母长度 / trigger 所有权 / 同步回写这三条判据属 VOCA-207/206 自己的验收面，不在本 issue 的回归范围 | 洞察BA + 前端（另单验收） |
| 10 | **B 包 ≠ 合并窗口产物** | 三方合并的冲突由 QA 按「后合入者适配先合入者」解决（§13.1 清单），真实窗口若采用别的解法（例如保留 1 参 `backgroundFor` 重载、或给 `buildEnterKey` 换一套留白），§13.2/13.3 的结论必须在新产物上复跑；另外 `d283706` 误提交的 gitlink `.claude/worktrees/voca-74` 我是在本地排除的（§13.5），窗口里怎么处置需要人拍 | 项目经理（leader）+ 人类确认 |

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
adb -s emulator-5554 install -r -d -g <app-debug.apk of c524351>
adb -s emulator-5554 shell ime enable com.ispeech.ime/com.ispeech.input.IspeechImeService
adb -s emulator-5554 shell ime set   com.ispeech.ime/com.ispeech.input.IspeechImeService

# 2) 恒高 / 字体缩放 / 截字 / spec-AC-10（§3.1、§4；第五轮带闸门版）
#    闸门不过就只打一行 GATE，stdout 不出现 RESULT/INK/delta（裁决 §6-1）
python3 tools/qa/voca-37/fs115.py <apk> 440 1.0,1.15 shijian,dashu
python3 tools/qa/voca-37/fs115.py <apk> 520 1.15 shijian,dashu    # dashu 这档实测是 GATE reason=cand_low rc=13

# 2-a) 作废自查（打回项 1 的第 3 条自证）：旧帧重扫，旧口径墨迹逐位复现 + 新口径候选数
python3 tools/qa/voca-37/voidcheck.py      # → FRAME/NEW/VERDICT/SUMMARY；rc=0 = 三帧全部低于 4 条闸门（作废成立）

# 2-b) AC-2/AC-3 两个短路开关的运行时取值（打回项 4 的第 2 项披露）
python3 tools/qa/voca-37/dsdump.py         # → DS/SWITCH；DataStore 文件不存在时给 source=code_default(never_written) 并先 ls -R files 自证 run-as 路径没错

# 2-c) spec-AC-9 退格长按阶梯（含 1500ms 实测档；键位从截图反推 + 200ms 探针闸门）
python3 tools/qa/voca-37/ac9.py 60                        # 默认 200,1000,1500,2000 四档
python3 tools/qa/voca-37/ac9.py 60 200,1000,1500,2000      # 显式指定；探针删不掉 1 字 → GATE reason=del_key_miss rc=20

# 2-d) D2 / v1.6-AC9 崩溃时序（第五轮脚本化；A 包出 FATAL、B 包出 FIXED 才算工装有效）
python3 tools/qa/voca-37/d2check.py 440
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
git diff fa57a07 c524351 -- app/src/main/java/com/ispeech/settings/keyboard/KeyboardViewModel.kt
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
git worktree add -b qa/merge-window-candidate-r5 ../voca47-union c524351
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
| `ev_r5_voidcheck.txt` | 采信（作废自证） | 打回项 1 的第 3 条：旧口径墨迹逐位复现（1400..1441 / 1397..1445 / 1185..1284）+ 新口径 `cand_counts=440/fs1.0:0, 440/fs1.15:0, 520/fs1.15:1`、`ink_entirely_inside_chip=440/fs1.0, 440/fs1.15`，rc=0 → 三帧全部低于 4 条闸门，**旧 §4 三行与旧 §3 AC-1 三组 Δ 作废成立** |
| `ev_r5_440.txt` | 采信（第五轮重测） | A 包 440dpi fs1.0/1.15 × `shijian`/`dashu` 四组：`GATES` 行 + `RESULT`（Δ=0、x0、cand_count、full_count、pool）+ `INK`（chip 右侧字身）+ `CAND`（逐条 x0/x1/glyph/pitch/badge/tangent）+ `AC10 verdict=PASS` |
| `ev_r5_520_fs115.txt` | 采信（含一条闸门取证） | A 包 520dpi/fs1.15：`shijian` 过闸门后 `AC10 full_count=3 verdict=FAIL`（D3）；`dashu` → `GATE reason=cand_low rc=13`，**该组不出数**（stdout 无 RESULT/INK/delta） |
| `ev_r5_gates_440.txt` | 采信（闸门取证） | 裁决 §5 五个 Given 的逐条点名行（`readback_equals_code` / `chip_text_equals_code` / `scrollview_visible` / `cand_visible` / `pool_at_least`）；第四、五轮的 chip-only 帧在这一版会被直接拒 |
| `ev_r5_ac9.txt` | 采信 | spec-AC-9 阶梯实测：200→1、1000→7、**1500→13（`spec_ac9_gt3=PASS`，实测）**、2000→21；`PROBE_RETRY first_deleted=0` 如实入库（注入式同点 swipe 偶发丢手势，探针补按后才出数）；`NOTE` 行标注旧「1.5s≈10 字」是两点内插非实测 |
| `ev_r5_switches.txt` | 采信 | AC-2/AC-3 披露第 2 项：`chinese_punctuation=True`、`double_space_period=False`，两条都 `source=code_default(never_written)`；同轮 `DS ls -R files` 有 `cell_dicts`、无 `datastore/ispeech_settings.preferences_pb` → 不是 run-as 失败，是该键从未写过 |
| `ev_r5u_440.txt` | 采信（B 包） | 集成候选 440dpi fs1.0/1.15 四组：Δ=0（952→952）、`tangent_items=0`、`AC10` 全 PASS；`KEYMAP runs=[10,9,7,3]`（VOCA-184 新几何下重新标定成功） |
| `ev_r5u_520.txt` | 采信（B 包，含闸门取证） | 集成候选 520/fs1.15：Δ=0（1128→1128）、`shijian` `full_count=3 FAIL`（与 A 包同形 → D3 不是被 170/184/207 掩盖的）、`dashu` `GATE reason=cand_low rc=13` |
| `ev_r5u_ac9.txt` | 采信（B 包） | 集成候选退格阶梯：200→1、1000→8、**1500→9（>3 PASS）**、2000→17 |
| `ev_r5u_d2_union.txt` | 采信（B 包） | `d2check.py` 在 B 包：`fatal_count=0`、`keyboardviewmodel_frames=0`、pid 19562 前后不变 → **`v1.6-AC9` / PR #9 的修复在并集上有效** |
| `ev_r5u_d2_control_c524351.txt` | 采信（阳性对照） | 同一脚本装回 A 包：`fatal_count=2`、栈顶 `KeyboardViewModel.kt:35`、pid 18755→19089 重启 → 证明工装的「未崩」不是假阴性，同时是 D2 的**第三次复现** |
| `ev_r5u_ac2_hw.txt` | 采信（B 包） | 集成候选的 AC-2 硬件/注入键路径：`wall_between_ms=0` 与 53.4ms 两档仍 `host='  '`（开关 `state=True raw_hex=12020801`、`IDENT match=True` 双端）→ **D1 在并集上未修**（`SpaceKeyPolicy.kt` 与 `IspeechImeService.kt` 未被三条 PR 触碰：`git diff --name-only c524351 cffc0cd` 可核） |
| `ev_headline.txt` | 部分采信 | fs1.0 段（988→988 ×3、pp=42.22，IDENT 双端 match=True 04:51:56–04:53:13）采信；fs1.15 的 833px 段是 Gboard 抢占值，作废 |
| `ev_fs115_440_fs10.txt` | **降级：不得再用于 AC-1/截字** | 440dpi / fs1.0 候选带墨迹原文（旧 §4 第 1 行的 1400–1441）；带 `ime_before/ime_after` 闸门字段。**第五轮裁决：该帧没记 `cand_count`，墨迹含 chip（`ev_r5_voidcheck.txt` 复算 `x>240` 命中列=0）→ 恒高 Δ 与截字一律以 `ev_r5_440.txt` 为准**，本文件只留作身份/环境旁证 |
| `ev_fs115_440_final.txt` | **降级：INK 段不得再用于 AC-1/截字** | 旧 fs1.15 的 `INK comp ink_top=1397 ink_bot=1445 top_pad=44 bot_pad=31` 属 chip-only 伪装态（`ev_r5_voidcheck.txt` 复算 cand_count=0），由 `ev_r5_440.txt` 取代；同轮 `RESULT fs=1.0 host=FAIL` 本来就无数据；`REG_double_space` 空态那两条仍可作「0 候选不塌」的旁证（§6①） |
| `ev_fs115_520_ink.txt` | **降级：不得再用于 AC-1/截字** | 520dpi / fs1.15 旧墨迹原文（旧 §4 第 3 行的 1185–1284，其中 `top_pad=12` 是把角标圆片当成了字身顶沿）；新口径见 `ev_r5_520_fs115.txt`（字身 1223..1284、top_pad=38、bot_pad=24） |
| `ev_fs115_520.txt` | 部分采信 | `RESULT 1168→1168 delta=0` 与 `ev_520b.txt` 同值互证，保留；两行 `INK ... none=True` 为取证未成功，其截字数据作废 |
| `ev_fs115b.txt` | 部分采信 | 两档 `delta=0` 与 `ev_headline`/`ev_fs115_440_final` 互证，保留；`INK` 全 `none=True`（带内没取到墨迹）→ 留白数据作废 |
| `ev_fs115_gate.txt` | 采信（闸门自检） | IME 切到 Gboard 后重跑：`SKIP reason=current_ime_not_ispeech current=com.google.android.inputmethod.latin/...`，未产出任何 Δ |
| `ev_font115.txt` | **作废** | `AC1_440_115 empty=1050 comp=1062 delta=12` 与 AC-1 的 Δ=0 直接冲突；全文无 `IDENT` 身份校验行，几何 1050/1062 与校验通过窗口的 988 不同构 → 他人换包（恒高未生效的旧包）期间的污染值。**不得当作 AC-1 失败** |
| `ev_fs115.txt` | **作废** | `IDENT start ime=com.google.android.inputmethod.latin/... h=833` → Gboard 抢占；两档 `host=FAIL`，无有效测量 |
| `ev_fontscale.txt` / `ev_fontscale2.txt` | **作废** | `frame=833 top=1507` 是 Gboard 的几何（本包在 440dpi 是 `top=1352 h=988`），`host='搜索设置'` 是 Settings 搜索框的**占位文案**（说明键根本没落到本包）→ Gboard 抢占窗口内的污染值；`ev_fontscale2.txt` 当时已被旧版脚本自己打了 `SKIP` |
| `ev_sogou440.txt` | **作废** | 文件名写 `SOGOU_440`，行内实测 `ime=com.google.android.inputmethod.latin/...`（833px 是 Gboard）。标签与内容不符、最易误导第三人；440dpi 搜狗基准改用 `ev_sogou115.txt` / `ev_pp.txt` 的 978 |
| `ev_520.txt` | **作废** | `HOST ok=False` + `ISPEECH_520_115 h=933 pp=39.87` → 非本包几何（同值出现在 Gboard 抢占窗口）；520dpi 以 `ev_520b.txt` 的 1168 为准 |
| `ev_trunc.txt` | **作废** | `IME top=1099 h=1241` 与 440dpi(1352/988)、520dpi(1172/1168) 两种几何都不符 → 宿主/键盘错位；`INK rel_top=0` 的「贴边」是错位产物，不是截字证据 |
| `ev_sogou.txt` | **作废** | `TRY1/TRY2 top=None` → 搜狗键盘没弹出，取证未成功，无数据 |
| `ev_paths.txt` | **作废** | 运行中 `TypeError: unsupported operand ... NoneType + int` 中止；`CLEAN h=1050` 与本包几何不符 → 由 `ev_paths2..9` 取代 |
| `ev_ac23.txt` | **作废** | keyevent 三组阴性（`AC2_fast2x host='  '`、`AC2_gap250ms`、`AC2_triple`）**未记开关实际态**（默认 false），且 `wall` 间隔未标注 → 属「未取证」而非「测出不符」；由 `ev_ac2_hw.txt` 取代 |
| `ev_ac23b.txt` | **作废** | `SINGLE_INJECT_2x/3x host='  '` + `LOGCAT_KEYEVENT_LINES n=0` → 注入没落到 IME，同样无开关态；由 `ev_ac2_hw.txt` 取代 |
| `ev_ac23c.txt` | 部分采信 | 软键正例 `AC2_soft2x host='。' wall_ms=179` 采信；同轮开关态由 `ev_ac2_soft.txt` 用 DataStore 原始字节重新标注 |
| `ev_ac23d.txt` | 采信 | `R4_probe host='她她们'`（§8.1）+ 开关关闭反例 `AC2_toggleOFF host='  '`。其开关态用滑块色值标注（`(0,100,136)`=开 / `(195,196,197)`=关），**返修后以 `ev_ac2_{soft,hw}.txt` 的 DataStore 原始字节标注为准**，本文件的色值标注只作旁证 |
| `ev_ac2_hw.txt` | 采信 | 硬件/注入键路径（`input keyevent 62`）× 开关开/关，含 `SWITCH state=... src=datastore raw_hex=...`、`MODE`、`IDENT` 与 `wall_between_ms` → §3.3 D1 |
| `ev_ac2_soft.txt` | 采信 | 软键点击路径 × 开关开/关，同上口径（含间隔 577.1ms 的窗口外反例、三连点 `。␣`） |
| `ev_crash_kbsettings.txt` | 采信（缺陷单） | §3.3 D2 的崩溃原文：`FATAL EXCEPTION: main` / `Process: com.ispeech.ime, PID: 30743` / `NullPointerException ... MutableStateFlow.setValue` @ `KeyboardViewModel.kt:35`；含「先弹键盘再进页面」的复现时序自证（两次 `pid` 同为 30743） |
| `ev_ac47.txt` | 部分采信 | 位移阶梯与钳制的**数值**可用（±198、cum 4892、端点后归零）；**键名作废**——文件里没记 keycode，正文当时把 `-`/`=` 的方向标反了。方向/端点归属一律以 `ev_ac47b.txt` 为准；这批标签当时由一次性命令产出、脚本未入库，也是上一轮被打回的原因 |
| `ev_ac47_probe.txt` | 采信 | 闸门上线后的正向复测（13:11:48–13:12:50）：`PRE_PROBE keyevent70 → shift=198 err=2.88`、`bg_match_pct=77.5`；cum 2970/15 档到端，与 12:31 那轮 4752/24 档不同——记「档位稳、cum 随候选池规模变」这条口径的凭据 |
| `ev_ac47_falsify.txt` | 采信（证伪档） | `--fwd 69 --rev 70` 后 `PRE_PROBE keyevent69 → shift=0 err=0.0`、`GATE blocked=前提不成立…`、rc=4：方向判据不是叙述，参数对调就拒绝出数。13:06 那次对调跑（闸门还没上）产出的是全 0 的假钳制 sweep，已删除、不入库 |
| `ev_ac47b.txt` | 采信 | §3 AC-4~AC-7 的返修轮重测（12:31:03–12:32:51，`IDENT` 双端 match=True）：`=`=keyevent70→+198×24 档、cum 4752 后归零；`-`=keyevent69→−198、左端第 25 次归零；`AC7_FROM_LEFT` +198 err 2.85；宿主全程 `shi`。产出脚本 `ac47.py` 已入库，命令行见 §10-4b |
| `ev_ac47_gate_probe_zero.txt` | 采信（闸门取证） | 本轮返修项 5 的复跑：`--fwd 69 --rev 70` 对调档 → `PRE_PROBE keyevent69 shift=0 err=0.0` + `GATE reason=probe_zero rc=4`（14:05:16 起，同 `ev_ac47_falsify.txt` 那一档，只是现在带 `reason`）|
| `ev_ac47_gate_host_buffer.txt` | 采信（闸门取证） | 预热期间并发注入把宿主打进非预热态 → `PREP host='搜索设置'`、**无 `PRE_PROBE` 行**、`GATE reason=host_buffer rc=5`（14:05:47）。这条就是上一轮「退 4 但看不出为什么」的那一类 |
| `ev_ac47_gate_host_residue.txt` | 采信（闸门取证） | 同上第二种形状：宿主文本 = 预热串混进残留 `host='szzzhzzzizzzzzzzzzzzzzzzz'` → `reason=host_buffer rc=5`、同样无 `PRE_PROBE`（14:08:59）。注入是明写的构造（`run_gate_r4b.sh`），只为覆盖闸门分支，**不是新缺陷、不进任何判据** |
| `ev_ac47_gate_forward.txt` | 采信 | 按 §10-4b 复位后正向跑通（14:06:39–14:07:57，rc=0）：`PRE_PROBE keyevent70 shift=198 err=2.85`、`HOSTTEXT v='shi'`、`SUMMARY right_end_cum_px=1980 / left_clamp_press=10`（这轮 `--n 2` → 只按 10 档，没到右端，cum 与 4752 不可比，见 AC-6b 的「cum 非常量」口径）|
| `ev_ac9{,b,c,d}.txt` | 采信 | §3 AC-9 退格长按：200/1000/2000ms→删 1/5/15、英文态与 4s 续按；`ev_ac9d.txt` 的 `rate_ms=101.2/92.8` 只落 90ms 慢档 → AC-9 判据按「代码级确认，行为级未证 45ms 快档」收窄 |
| `ev_r4.txt` | 采信 | §8.1 R4 补偿实现 S0–S3（含 `inject_ms=131` 的间隔自证） |
| `ev_g46.txt` | 部分采信 | §3 G4（键区/工具栏横滑 shift=0，err 0.0）+ G6（762→0）采信；同文件「候选条竖滑/斜滑 `shift=-870`」两行**作废**——err 11.4 > 5 按本报告 §3 自己的口径本就不可判，且比的是上屏前后两帧（候选池被 `CandidateView.kt:81` 的 `scrollTo(0,0)` 重置，内容不同） |
| `ev_vswipe.txt` | 部分采信 | 「60px 上屏」成立；「110px 不上屏」本轮同参数 3 次复跑也未上屏（与质检专员的一次读数不一致，见 §8.4 与 `ev_vswipe_c.txt`），保留为观察；「带上沿外 215px 起点仍上屏」那行**作废**，由 `ev_vswipe_c.txt` 的 `ime_gone=True` 读数取代；该文件当时由一次性命令产出、脚本未入库 |
| `ev_vswipe_a.txt` | 采信 | §8.4 上屏阈值扫（dy=20/40/60/80/110/150/200，dur 120，起点 1391）+ 纯 tap 对照。每行都给 start_y/end_y/dy/dx/dur/committed/ime_gone/h_shift/h_err/h_verdict，脚本 `vswipe.py` 已入库 |
| `ev_vswipe_b.txt` | 采信 | §8.4 滚动态（先按 `=`×4 把 scrollX 推到 ~792px）下的横滚：纯竖滑 dx=0 → shift=0、err 0.0（可判）；斜滑 dx=160/dy=110 → −264px、err 4.37（可判，n=1）；`hroll_dy60` 那行 committed=True、err 8.84，按口径记不可判 |
| `ev_vswipe_c.txt` | 采信 | §8.4 阈值细扫（dy=90 上屏 / dy=100 不上屏）+ dy=110 同参数三连复跑（均未上屏）+ 起点带上沿外 215px（`ime_gone=True`、`after='<NONE>'`）+ 起点带下沿外 y=1500（shift=0、不上屏） |
| `ev_pp.txt` | 采信 | §5 440dpi 行：iSpeech 988 / 搜狗 978 → +0.43pp |
| `ev_520b.txt` | 采信 | §5 520dpi 左列：`ime=com.ispeech.ime top=1172 h=1168 pp=49.91` + 键帽行带 |
| `ev_sogou115.txt` | 采信 | §5 440dpi 右列：搜狗 978（`ime=com.sohu...` 已标注） |
| `ev_sogou520.txt` | 采信 | §5 520dpi 右列：搜狗 1002 |
| `ev_paths2.txt` | 采信（反面用途） | `IDENT match=False` —— 身份校验确实拦得住换包，是校验有效性的证据 |
| `ev_paths{3,4,5}.txt` | 采信 | §6 ①④⑤ 与 ③T9 的当场尝试（含 `HOST ok=False` 的失败原样） |
| `ev_paths{6,7,8,9}.txt` | 采信（未取证原样） | `GATE blocked=拿不到「-/= 进 IME」的宿主字段` —— ③④ 记「未取证」的凭据 |
| `gradle_test.log` | 采信 | §7 单测 14 条 0 失败 0 跳过 + 全量仅 1 条基线噪声 |

第三人若按文件名读过去：`ev_font115.txt`（Δ=12）与 `ev_trunc.txt`（贴边）都不是本包在身份校验窗口内的行为，引用前先看本表状态列。

## 12. 范围外已知缺口（PM 裁定不计入本轮 FAIL）

`fa57a07` 既有债务，本轮未回归、也未恶化：§5 遗留 4 项无障碍（候选条朗读、滚动位置播报、键位 label 缺失、面板焦点顺序）+ 候选文字对比度 3.82:1（低于 4.5:1）。52→46dp 的 UI 批准已由人类于 11-09 给出，不再重开。

---

## 13. 集成候选回归：本轮覆盖 VOCA-170 / VOCA-184 / VOCA-203（=B 包 `cffc0cd`）

14:50 追加指令要求本轮回归覆盖「问题关联 issue 的改动」。核过仓库后（见文首那条事实核对），这三条 PR **都没有进 main**，所以覆盖方式只能是 QA 自己起一个并集出包。**B 包不是合并窗口产物**，它的价值是「提前把并集的集成风险打出来」，不能替代窗口后的全量回归（项目规则四）。

### 13.1 合并面与冲突处理（三处，逐条给依据）

| 合并 | 冲突 | 处理（原则=后合入者适配先合入者） |
|---|---|---|
| `d3b6f84`（VOCA-170）→ `c524351` | `KeyboardViewModel.kt` 1 处 | VOCA-46 在同一位置新增 `setDoubleSpacePeriod()`，VOCA-170 把 `_chineseInputMethod` / `_spaceLongPressMs` 两个声明前移到 `init` 之前 → **两者都保留**：setter 留在原位，声明取前移版（文件里只剩一处声明，`:31-35`） |
| `d283706`（VOCA-184）→ 上一步 | `SoftKeyboardView.kt` 2 处 | ① `buildKey` 开头：HEAD 侧是「换行键单独画」的 `enterView` 块，d283706 侧是 `hMarginDp/vMarginDp`（1dp）计算——**两个都要**（d283706 分支点早于 ↵ 图标恢复那笔提交，所以它那边根本没有 `enterView`）；② `backgroundFor`：取 d283706 的三参版（`style, action, layout`，工具栏键透明底）+ 新增的 `keyElevationFor` / `applyKeyElevation`，**删掉 1 参重载**并把 `:554`、`:599` 两处调用点补上 `action/layout` |
| `4d6363c`（VOCA-207）→ 上两步 | 无 | `strings.xml` / `strings-en.xml` 自动合并（两边各自追加，不重叠） |

**两处「不冲突但会编不过」的集成缺陷（合并窗口必踩，这是本轮最值钱的一条预警）**：

1. `buildEnterKey` 里 `setMargins(dpInt(keyMargin), …)` —— `keyMargin` 这个字段被 VOCA-184 **删掉**了（换成按键样式的 `hMarginDp`），而 `buildEnterKey` 是 VOCA-184 分支点之后才有的函数，git 不会报冲突，`compileDebugKotlin` 直接 `Unresolved reference: keyMargin`（4 处）。本轮处理：换行键属 `KeyStyle.PRIMARY`、不是字母键，取 `DEFAULT_KEY_MARGIN_DP`（4dp，与 main 视觉一致）。
2. `toolbarKeySelector()` —— VOCA-184 新增的函数在合并结果里**整块丢失**（git 把它落在冲突块的对齐缝隙里），`backgroundFor` 三参版调它 → `Unresolved reference`。本轮处理：按 `d283706` 原文补回（`ColorDrawable(Color.TRANSPARENT)` + `state_selected → roundedRect(primaryPressed)`）。
3. 附带一条工装侧：`drive.key_map` 的 `merge_gap=8` 在 1dp 留白下会把两行粘成一行（§2.5 末段），B 包首跑 `GATE reason=key_map_fail rc=16`。

### 13.2 并集下的判据复跑（B 包，闸门与 A 包同口径）

| 判据 | A 包 `c524351` | B 包 `cffc0cd` | 结论 |
|---|---|---|---|
| spec AC-1 恒高 Δ | 4 组全 0（988 / 1168） | 4 组全 0（952 / 1128） | **两包都成立**，恒高契约没被 170/184/207 破坏 |
| 截字（`tangent_items`） | 全 0（440/fs1.15 底部余量 19px） | 全 0（同 19px） | **纵向不截**，余量薄这条留给真机 |
| spec AC-10a | 440 PASS（full=4）/ 520·fs1.15 **FAIL（3）** | 440 PASS（full=4）/ 520·fs1.15 **FAIL（3）** | **D3 与三条 PR 无关**，不是被掩盖、也不是被引入 |
| spec AC-10b | 520 `dashu` `GATE cand_low` | 同 | 长词档两包都不给数 → 回 UX/UI + PM |
| spec AC-9 阶梯 | 200→1 / 1000→7 / **1500→13** / 2000→21 | 200→1 / 1000→8 / **1500→9** / 2000→17 | **两包都 >3 → PASS**；B 包 1500ms 档删得少一些（键位/退格重复频率受键高变化影响），不影响判据 |
| 键盘总高 / pp | 988 → 42.22%（+0.43pp）；1168 → 49.91%（+7.09pp） | 952 → 40.68%（**−1.11pp**）；1128 → 48.21%（**+5.39pp**） | VOCA-184 的留白收紧**改善** pp，但 520dpi 仍 >1pp → 真机口径照旧拦路 |
| 键位标定 | `runs=[10,9,7,3]` | `runs=[10,9,7,3]`（修 `merge_gap` 后） | 零死区命中改动没改变可见键帽段数 |

### 13.3 三条 PR 各自的验收面结果

- **VOCA-170（`v1.6-AC9`，P0）**：**修复在并集上有效**——`d2check.py` 同一时序 B 包 `fatal_count=0`、pid 不变，A 包（不含 `d3b6f84`）当场 `FATAL ×2` 同一栈（阳性对照，§3.3 D2）；单测 `KeyboardViewModelInitOrderTest tests=2 failures=0`。**但 PR #9 仍 OPEN、`d3b6f84` 不是 main 祖先** → v1.6 合并窗口门禁按裁决 §2-2② 仍然拦路，这条不是「已闭」。
- **VOCA-184（`d283706`）**：键盘几何变化已进入 §13.2 的 pp/恒高/键位三行；`KeyHitTesterTest tests=6 failures=0`。**新增一条仓库卫生缺陷（D4）**：`d283706` 把 `.claude/worktrees/voca-74` 以 **gitlink（mode 160000，指向 `88dac57`）** 提交进了分支（`git ls-tree d283706 .claude/worktrees/`）。合进 main 会在仓库里留一个幽灵 submodule 指针，`git worktree list` 也能看到那个 worktree 确实存在（是他人 worktree）。本轮在 B 包里 `git rm --cached` 排除并记录；**归属：VOCA-184 开发（前端）需在 PR #7 里删掉这一条**，P2（不崩不拦功能，但污染主线仓库历史）。
- **VOCA-203**：本体 `in_progress`、**没有实现提交**（停在人类对长度基线的答复）；能覆盖的只有子单 VOCA-207（PR #11 `4d6363c`，仍 OPEN），且只覆盖到键盘交互面（候选条渲染、恒高、pp、退格阶梯）——**快捷字母长度 / trigger 所有权 / 同步回写三条判据不在本 issue 的回归范围**，属 VOCA-207/206 自己的验收面（§9-9）。

### 13.4 未覆盖 / 必须复跑的面

- 全量静态门禁（`check_static` / `check_crosscut` / `check_input_logic` / `check_t9`）未在 B 包重跑：VOCA-207 动了 `tools/verify/check_quick_trigger.py`、`check_phrase_csv_import.py`（快捷字母域），本 issue 面上无对应判据；窗口产物出来后应连静态门禁一起跑。
- B 包上未重跑：AC-4~AC-7（`-`/`=` 位移阶梯）、G4/G6、面板路径、T9、语音。**理由**：这三条 PR 都没触碰 `CandidateView.kt` / `IspeechImeService` 的滚动与面板代码（`git diff --name-only c524351 cffc0cd` 可核，滚动面唯一变化是 VOCA-184 的键命中区），A 包结论仍适用；但窗口产物若与 B 包解法不同，本 §13 全部结论作废重跑。
- 真机（520dpi 定稿 pp、手指触摸、TalkBack、语音）照旧未取证（§9）。

### 13.5 本轮为 B 包动过的东西（透明化）

- 新建本地 worktree `…/voca-47-37061cb47d5d/voca47-union`，分支 `qa/merge-window-candidate-r5`（`26628a6` → `4a4732b` → `cffc0cd`）。**不推 origin、不合 main、不改他人分支**（项目规则一/二/五）；复现配方在 §10-6。
- 工装改动（只碰测试代码，不碰产品代码）：`drive.py` 的 `key_map` 行合并高度上限、`fs115.py`/`ac9.py` 的 `gate()` 对嵌套 `reason` 键的碰撞修复、`ac2.py` 的 `F.ident`/`F.ours` 断链与 `EXPECT_TOP` 写死像素（第五轮重写 `fs115.py` 后 ac2 一度跑不起来，属我自己引入的工装回归，如实记）、新增 `d2check.py`。
- **一条证据卫生事故（如实记）**：`fs115.py` 的候选带裁图文件名原来只有 `r5_<dens>_fs<fs>_<code>_<kind>.png`，**不带包标识** → B 包 16:59–17:02 复跑把 A 包 `shijian`/`dashu` 那 12 张同名帧覆盖了（`haizi` 那两张 15:57 的没被覆盖，仍是 A 包）。**文本证据（`ev_r5_*.txt` / `ev_r5u_*.txt`）不受影响，§3.1/§4/§13.2 的每个数字都能在自己的 stdout 里核到**；但帧与包必须对得上，所以：现存 `shots/r5u_*.png` = B 包、`shots/r5_440_fs1.0_haizi_*.png` = A 包，并已给 `fs115.py` 加第 5 位 `label`（`r5_<label>_…`，默认 `a`）防止再撞名。

### 附：本报告引用的原始证据

`ev_headline.txt`（作废段已标注）、`ev_ac47.txt`（键名作废）、`ev_ac47b.txt`、`ev_ac47_probe.txt`、`ev_ac47_falsify.txt`、`ev_ac47_gate_{probe_zero,host_buffer,host_residue,forward}.txt`、`ev_ac23{,b,c,d}.txt`、`ev_ac2_{soft,hw}.txt`、`ev_r4.txt`、`ev_ac9*.txt`、`ev_g46.txt`（−870 两行作废）、`ev_vswipe.txt`（部分采信）、`ev_vswipe_{a,b,c}.txt`、`ev_pp.txt`、`ev_sogou{115,520}.txt`、`ev_fs115b.txt`、`ev_fs115_440_fs10.txt`、`ev_fs115_440_final.txt`、`ev_fs115_520.txt`、`ev_fs115_520_ink.txt`、`ev_fs115_gate.txt`、`ev_crash_kbsettings.txt`、`ev_paths{2,3,4,5,6,7,8,9}.txt`、`gradle_test.log`、**第五轮新增 12 份**：`ev_r5_{voidcheck,440,520_fs115,gates_440,ac9,switches}.txt` + `ev_r5u_{440,520,ac9,ac2_hw,d2_union,d2_control_c524351}.txt`（全部在 `tools/qa/voca-37/evidence/`，共 66 份，逐文件口径见 §11.1——旧 chip-only 三份已标「不得再用于 AC-1/截字」），截图 `shots/f440_1.0_c_cand.png`、`f440_1.15_c.png`、`f440_1.15_c_cand.png`、`f520_1.15_c_cand.png`、`p5_phrase.png`、`p5_clip.png`、`reg_phr_after.png`。工装脚本：`tools/qa/voca-37/{fs115,ac2,ac47,vswipe,drive,paths,paths2,paths3,qa_kb,qa_rows,band,ac9,dsdump,voidcheck,d2check}.py` + `tools/qa/voca-37/{run_gate_r4,run_gate_r4b}.sh` + `tools/qa/voca-37/README.md`。截图新增 `shots/r5u_*.png`（B 包 440/520 候选带裁图）。
