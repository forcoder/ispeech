# VOCA-37 · Stage 3 键盘交互回归报告（模拟器口径）

- 被测构建：`c524351e2e509ed294f49dd3d2f7a1bf29e07a4d`（PR #18 head，base `fa57a07`；PR #18 仍为 OPEN，未合并）
- 两个 PR 别混：**PR #18 = 被测变更集**（产品代码，16 个文件，本文凡是「变更面内 / 拦路项」所说的「本 PR」都指它，本轮统一改写为 `PR #18`）；**PR #25 = 本 issue 的交付 PR**（branch `test/voca-37-stage3-regression`，只含 `docs/` 报告 + `tools/qa/` 工装与证据，**不含任何产品代码**：`git diff --name-only fa57a07...57f5f5b` 里 `app/src` 命中 0 个）。
- 交付物范围（计数口径，别和单个提交混）：报告 = 本文件；工装 = `tools/qa/voca-37/*.py` + `README.md`；证据 = `tools/qa/voca-37/evidence/`（50 份当场 stdout 原文）+ `shots/`（7 张截图）。三个口径分开写：返修提交 `57f5f5b` 单独 13 files +597/−35；第二轮返修提交 `4009fb4` 单独 11 files +609/−39；**PR #25 累计到 head `d5dac79`** 是 67 files +2599/−0（其中 evidence 48、shots 7、`tools/` 66、`docs/` 1）。复算命令：`gh pr view 25 --json files --jq '{n:(.files|length),add:([.files[].additions]|add),del:([.files[].deletions]|add)}'`——本句所在行的这次措辞修改本身还会让 head 往前挪一格、行数差两三行，所以以 head SHA 为准，不要拿本行的数字当最新值。
- 规格权威：`agent/ux/voca-45-spec` @ `28c1cac`（main 上的 602 行翻页版与 `agent/ux/voca-45` @ `fc564aa` 均已过期，本报告不引用）
- 执行环境：本地 AVD `ispeech_test`（1080×2340 / 440dpi / arm64 / 2G），Android 14 模拟器；对照基准为同机搜狗 `com.sohu.inputmethod.sogou/.SogouIME`
- 执行人：测试工程师（QA）· 2026-11-17 03:40–05:20 首测，06:55–07:45 按质检 6 条返修，12:25–12:45 按质检第二轮 4 条返修（本地时区）
- 结论：**no-go**。**PR #18 的拦路项 2 条**，任一成立即拦：① 真机口径缺失——「占屏比 ≤1pp」在按真机参数（520dpi / font_scale 1.15）复现时变成 **+7.09pp**，模拟器无权定稿；② AC-2 按「开关态 × 键路径」拆开重测后，**硬件/注入键路径 4 组全阴性**（开关当场读到 `true`、间隔 0ms 仍不出句号），规格 AC-2 未把判据限定在软键 → 记 **P1**（D1）。此外还有一条 **`fa57a07`（main）既有的 P1** D2：进入设置页「键盘与输入」即 NPE 崩掉输入法进程。缺陷真实且用户可见，但**不是 PR #18 引入、PR #18 也未触碰其代码**，是否卡住统一合并窗口属 PM/人类的范围裁决，本报告不替它判，也不计入 PR #18 的拦路项。门禁：P1 未清零不判 go。

---

## 1. 结论先行

| 层 | 判定 | 说明 |
|---|---|---|
| 模拟器可判面（AC-1/3/4/5/6/7、G1–G7、恒高×1.15、面板路径） | **全绿**（1 项 P2 观察、2 项规格/实现差异记录） | 见 §3–§6；AC-4~AC-7 的键名↔方向在本轮按代码订正（步长/钳制结论不变），见 §3 与 `ev_ac47b.txt` |
| AC-2 双击空格句号（返修轮按路径拆开） | **软键路径 PASS / 硬件·注入键路径 FAIL** | 开关态当场从 DataStore 读出 `true`；硬件键 4 组全阴性 → P1，见 §3.1 D1 |
| AC-9 退格长按阶梯 | **代码级确认，行为级未证** | 原判 `PASS（带说明）` 按质检意见下调：只观测到 90ms 慢档，45ms 快档无证据（§3） |
| PR #18 变更面内撞出的产品缺陷 | **1 条 P1（D1 · 硬件键路径）** | 拦路，见 §3.1 |
| `main`（`fa57a07`）既有缺陷，PR #18 未触碰 | **1 条 P1（D2 · 设置页崩溃）** | 质检在 `fa57a07` 构建上复现到同一 FATAL；不记在 PR #18 头上，是否卡合并窗口交 PM/人类，见 §3.1 D2 |
| 真机可判面（绝对占屏 pp、520dpi 键高观感、真机手指触摸） | **未取证** | 见 §5、§9；按 issue 约束不得写「通过」 |
| 发布建议 | **no-go** | 门禁：PR #18 拦路 2 条 = P1 D1 未清零 + 真机 pp 未定稿；D2 另列 |

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

## 3. 恒高与判据逐条结果

测量口径（全部按规格 §8.1 / §8.4）：`dumpsys window displays` 的 `InsetsSource type=ime` frame 取键盘高度；滚动位移用候选带列剖面互相关（step=2px，±600，取最小平均绝对误差，residual>5 视为不可判）；文本真值取宿主输入框 = Settings 搜索框 `open_search_view_edit_text`（由 `drive.host_ready()` 每轮 force-stop 两个 Settings 包后重起，空态读到的是它的占位文案「搜索设置」而不是输入内容）。测前一律 `settings put secure autofill_service null`（否则 GMS 自动填充会多报一个 `type=ime` source，把 inset 从 988 抬到 1260）。

**键名↔方向的口径（本轮返修项 2 的根因）**：不靠人记，由代码定义——`IspeechImeService.kt:612-614` 里 `KEYCODE_MINUS` → `scrollCandidates(-1)`、`KEYCODE_EQUALS` → `scrollCandidates(+1)`；`CandidateView.kt:90-92,199` 里 `scrollCandidates(steps)` → `smoothScrollBy(steps * dp(72), 0)`，注释写明「steps 为正向右（看后面的候选）」。互相关返回值 `shift` 的符号约定是「正 = 候选内容左移 = 视口向右」，所以 `=` 应为 +198、`-` 应为 −198，实测与代码同向。`ac47.py` 把 keycode、代码出处、期望档位（`KEYMAP` 行）和每一档的实际符号一起打进 stdout，杜绝再次出现「数值对、键名反」。
   **口径不靠叙述，靠闸门**（本轮实测补上，见 `ev_ac47_probe.txt` / `ev_ac47_falsify.txt`）：`ac47.py` 在 sweep 前先做可滚性探针——停在左端按一次 fwd 方向的键，位移必须是 +198；把参数对调成 `--fwd 69 --rev 70` 再跑，同一台机同一数据态下探针读到 `shift=0 err=0.0`，脚本直接 `GATE blocked=前提不成立…` 退出 rc=4，**不再产出那种「看着像到端钳制、其实是恒 0」的 sweep**（13:06 我第一次跑对调档时闸门还没上，拿到的就是一份全 0 的假钳制输出，已删）。同时补底色闸门：`ime_frame()` 报 988 不代表键盘画在 inset 上（别的 run 收掉键盘后 inset 会残留），候选带底色对本包 `#F4F5F7` 的命中率低于 50% 即拒绝出数。

| 判据 | 期望 | 实测 | 判定 |
|---|---|---|---|
| AC-1 恒高（空态→组合态 Δ） | Δ=0 | 440dpi/fs1.0：988→988 ×3 轮（04:51:56–04:53:13 身份双端校验）；440dpi/fs1.15：988→988；520dpi/fs1.15：1168→1168 | **PASS** |
| AC-2 双击空格句号（开关开）**软键路径** | 窗口内第二次空格出「。」 | `ac2.py … soft`：连点两次（同一次 shell，wall≈0）→`。`；两次 adb 调用（68.2ms）→`。`；间隔 577.1ms →`␣␣`（窗口外，语义正确）；三连点 →`。␣`（不出第二个句号）；同轮 `SWITCH state=True src=datastore raw_hex=12020801` | **PASS**（`ev_ac2_soft.txt`） |
| AC-2 双击空格句号（开关开）**硬件·注入键路径** | 同上 | `ac2.py … keyevent`：`input keyevent 62 62`（单进程，间隔≈0）→`␣␣`；两次 adb 调用（39.1ms）→`␣␣`；569.6ms →`␣␣`；三连发 →`␣␣␣`。同轮 `SWITCH state=True src=datastore raw_hex=12020801`，宿主中文态（`MODE path=keyevent mode=chinese host='中国z'`），身份双端 `IDENT match=True`（07:32:22–07:35:03） | **FAIL（P1）** — 间隔已排除工装因素，根因见 §3.1 D1（`ev_ac2_hw.txt`） |
| AC-2 反例（开关关，两条路径各一组） | 只出空格 | 软键 `␣␣`、硬件键 `␣␣`，同轮 `SWITCH state=False raw_hex=12020800`（键存在且值为 false，不是「没写过」） | **PASS**（`ev_ac2_soft.txt` / `ev_ac2_hw.txt`） |
| AC-3 组合非空时首格上屏首选 | 不出「。」 | 打 `shi`→首格=`是`；再按一次=`是疑是地上霜`（继续上屏联想/候选，未插句号） | **PASS** |
| AC-4 **`=`（KEYCODE_EQUALS / keyevent 70）单档位移** → `scrollCandidates(+1)` | +198±6px（+72dp，向右看后面的候选） | `ac47.py`：连按 24 次每档 +198（err ≤3.05，cum 4752）；第 25 次只剩 +140（err 5.05 → 按自家口径记不可判，正是快到位时 `smoothScrollBy` 被截短的形状）；第 26 次起恒 0 | **PASS**（`ev_ac47b.txt`） |
| AC-5 **`-`（KEYCODE_MINUS / keyevent 69）反向** → `scrollCandidates(-1)` | −198px（向左回退） | 单档 −198，err 2.56；反向连按 23 档 −198 + 末档 −140（err 4.18）后归零，左端钳制在第 25 次，cum −4694 | **PASS**（`ev_ac47b.txt`） |
| AC-6 边界不绕回 | 到端不循环 | **右端**由 `=` 到达：第 26 次起 cum 恒 4752，其后 15 次采样（i=26..40）位移全 0；静置 5 组像素差只有光标 (253,196,259,252) 与 None（`identical_pairs=3`） | **PASS** |
| AC-7 端点起步可动 | 停在**左端**（`-` 按到钳制）后改按 `=` 一次 | +198，err 2.85 | **PASS** |
| AC-6b 宿主文本不受滚动键污染 | 不变 | 全程宿主=`shi`；左右两端累计位移 4752 / 4694，差 58px 来自末档截断 + 2px 量化，不影响「不绕回」判定。**cum 是设备数据态相关的量，不是常量**：13:11 复测（`ev_ac47_probe.txt`，未 pm clear）右端 cum=2970、15 档到端，与 12:31 那轮的 4752/24 档不同；跨 run 可比的是「每档 198±6px」与「到端后恒 0」，不是 cum | **PASS** |
| AC-9 退格长按阶梯 | 380/90/45ms 未被新事件路径打断 | 常量仍在 `SoftKeyboardView.kt:991-994`；200ms→删 1、1000ms→删 5、2000ms→删 15（单调、有加速）；`ev_ac9d.txt` 的 `rate_ms=101.2 / 92.8` **只落在 90ms 慢档，45ms 快档自始至终没被观测到** | **代码级确认，行为级未证**（原判 `PASS（带说明）` 按质检意见下调）：常量与基线同码、非本轮回归，但「45ms 快档可达」这条行为判据本轮无证据 |
| G1 无 `N/M` 页码文案 | 不出现 | 候选条无页码绘制路径；`aapt2 dump resources` 无 `candidate_page_*` | **PASS** |
| G3 资源表无翻页控件 | 不出现 | 全量 dump 仅命中 `drawable/ic_settings_gear`，用于工具栏「设置」键（`SoftKeyboardView.kt:370`），与候选条无关 | **PASS** |
| G4 带外手势不驱动滚动 | 位移=0 | **仅覆盖键区/工具栏横滑**：键区横滑 shift=0（err 0.0）；工具栏横滑 shift=0（err 0.0）；起点在候选带下沿外 23px（y=1500）的竖滑 dy=60 → shift=0、不上屏（`ev_vswipe_c.txt`） | **PASS（范围限定）**。上一轮在这里写的「候选条竖滑/斜滑 shift=−870」按本报告自己定的 residual>5 不可判口径作废：那是**上屏前后两帧**做互相关的产物（候选池被 `scrollTo(0,0)` 重置，内容不同 → err 11.4），本轮在滚动态重测的结论见 §8.4 |
| G6 编辑后偏移复位 | 归零 | 滚动态 shift=762 → 编辑宿主后 shift=0 | **PASS** |
| G2 / G7 | 只记用户可见影响 | 见 §8 | 记录 |

### 3.1 缺陷单（2 条 P1：D1 在 PR #18 变更面内，D2 在 main 上就有）

**D1 · P1 · AC-2 双击空格句号在硬件/注入键路径完全不生效**

- 环境：`emulator-5554` / `c524351` / 440dpi / fs1.0 / 中文拼音态（`MODE mode=chinese`）/ 开关 `double_space_period=true`（DataStore 原始字节 `12020801`，同一份 stdout 的 `SWITCH` 行）。
- 复现：`adb shell input keyevent 62 62`（一次 adb 调用内两连发，注入间隔≈0）→ 宿主 `␣␣`；`input keyevent 62` ×2（实测间隔 39.1ms）→ `␣␣`；三连发 → `␣␣␣`。
- 期望（规格 AC-2）：`。`，长度 1。实际：`␣␣`，长度 2。
- 根因（代码级）：`IspeechImeService.kt:591-597` 对 `KEYCODE_SPACE` 先判 `buffer.isEmpty() && !aiReplyActive && !associationActive` 就 `return false` —— 空缓冲时事件根本不进 `handleSpace()`，空格由宿主 EditText 自己吞掉，`lastSpaceCommitAt` 永远是 null，判定链入口都不到。软键走 `KeyAction.SPACE`（`:458`）直接调 `handleSpace()`，所以只有软键生效。
- 为什么判 P1 而不是「差异记录」：规格 AC-2 的 When 只写「在 300ms 内连按两次空格键」，没限定软键；同一份规格把 KeyEvent 路径当唯一可复现取证口径（AC-4），并在无障碍条款里要求「外接键盘与物理键盘同样走这条」。硬件/蓝牙键盘用户把开关打开也拿不到句号，属验收条款不成立。
- 附带收口：规格开放问题 #10（「keyevent 与触摸等价性未验证」）本轮给出答案——**不等价**。
- 归属：前端（`IspeechImeService` 空格键路径）修复；UX 需裁定硬件键是否纳入 AC-2 范围（若明确不纳入，要把规格 When 改成「软键盘空格键」并回改本报告判定）。

**D2 · P1（main 既有，非 PR #18 引入）· 进入设置页「键盘与输入」即 NPE 崩掉整个输入法进程**

- 复现序列（关键是先让 DataStore 进进程缓存）：① 弹出键盘（任意宿主输入框）→ ② `am start -n com.ispeech.ime/com.ispeech.settings.MainActivity` → ③ 点「键盘与输入」卡片（bounds `[193,851][423,917]`，中心 308,884）→ ④ `FATAL EXCEPTION: main`，`Process: com.ispeech.ime, PID: 30743`，栈顶 `KeyboardViewModel$2$1.emit(KeyboardViewModel.kt:35)` ← `SettingsStore$chineseInputMethodFlow$$inlined$map$1$2.emit(Emitters.kt:219)` ← `KeyboardViewModel.<init>(KeyboardViewModel.kt:34)`。证据：`ev_crash_kbsettings.txt`。全新数据态（`pm clear` 后）可复现；冷进程（没弹过键盘直接进设置页）不复现——因为 DataStore 未缓存时首次 emit 走异步读盘，属性已初始化完。
- 根因：`KeyboardViewModel` 的 `init{}`（c524351 的 `:29-39`）里 launch 了三个 collector，而它们写的 `_chineseInputMethod`（`:107`）、`_spaceLongPressMs`（`:110`）**声明在 `init` 之后**。Kotlin 属性初始化按声明顺序排在 `init` 块之后执行，`viewModelScope.launch` 用 `Dispatchers.Main.immediate` 同步起跑，命中进程缓存的第一次 emit 就打在尚未赋值的 null 字段上（崩点 `:35` = `chineseInputMethodFlow` 那个 collector 的 `emit`）。
- 归属与紧迫性（**本轮按质检打回项 1 订正**）：这是 **`fa57a07`（main）既有的缺陷，PR #18 没有触碰它的代码**。四条依据，都能当场复跑：
  1. `git diff --numstat fa57a07...c524351 -- app/src/main/java/com/ispeech/settings/keyboard/KeyboardViewModel.kt` → `5  0`：PR #18 对这个文件只有 **+5 行**，是新增的 `setDoubleSpacePeriod()` setter（c524351 的 `:102-106`），三个 collector（`:34-39`）与两个 `MutableStateFlow` 声明一字未动；
  2. `git blame -L 34,39 c524351 -- .../KeyboardViewModel.kt` → `:34/36/37/38/39` = `904a98d`（Mini，2026-11-09），崩点 `:35`（`chineseInputMethodFlow` 那行）= `2a1883e9`（Lin，2026-11-10，VOCA-29）。两个 SHA 都 `git merge-base --is-ancestor <sha> fa57a07` 成立（即今天就在 main 上），而 PR #18 的提交清单只有 `e528c08` + `c524351`，两者都不在其中；
  3. 反向自查：`git diff -U0 fa57a07...c524351 -- app/src | grep '^+' | grep -E 'viewModelScope|MutableStateFlow|collect'` 在 PR #18 的 16 个变更文件、全部新增行里**只命中 1 行**，就是那个 setter 自己的 `viewModelScope.launch { SettingsStore.setDoubleSpacePeriod(...) }`——PR #18 没在任何文件加过第二个 collector；
  4. 质检专员在 `fa57a07` 自己的构建上、同一台模拟器、同一时序复现到**同一行、同一栈**的 `FATAL EXCEPTION`（PID 13926）。本报告上一轮写的「PR #18 在 `:38-39` 新增第二个同形 collector，把暴露面翻倍」是**错误归因**，已删除。
- 定级与去向：崩溃本身是真的、用户可见（「打字后改键盘设置 → 键盘整个消失，进程重启」），P1 定级不变；但它今天就在 main 上，**属 main 既有债**。是否因此卡住统一合并窗口，是 PM/人类的范围裁决，本报告只给事实与影响，不替它判，也不把它记进 PR #18 的拦路项（PR #18 拦路 2 条 = D1 + 真机 pp）。修法沿用上一轮的建议：把两个 `MutableStateFlow` 声明提到 `init` 之前，或让 collector 延后启动。
- 本轮取证影响：AC-2 的开关置位因此改走输入法内配置面板（齿轮键 → `openPanel(Panel.SETTINGS)`，`IspeechImeService.kt:2071`），不再经过会崩的 App 设置页。

## 4. 命名必证项：恒高 46dp × font_scale 1.15 会不会截字

规格点名的本轮最可能新缺陷：候选条用固定 `layout_height=46dp`（`candidate_view.xml:10`，注释说明「用 height 而不是 minHeight——候选字号是 sp，minHeight 兜不住 font_scale 放大后的内容高度」），内行 `minHeight=38dp`；1.15 下内容 38.2dp 被压进 38dp 可能截字。

实测（`tools/qa/voca-37/fs115.py`，安装身份双端校验 match=True）：

| 参数 | 键盘高（空态→组合态） | 候选带 | 带内文字墨迹范围 | 上下留白 | 结论 | 原文证据 |
|---|---|---|---|---|---|---|
| 440dpi / fs 1.00 | 988 → 988（Δ=0） | 126px（46dp） | `ink_top=1400 ink_bot=1441`（42px） | 顶 47 / 底 35 | — | `ev_fs115_440_fs10.txt`（07:37:10–07:37:41，`ime_before/ime_after` 双端都是 `com.ispeech.ime/`） |
| 440dpi / fs 1.15 | 988 → 988（Δ=0） | 126px（46dp） | 1397–1445（49px） | 顶 44 / 底 31 | **不涨高、不截字** | `ev_fs115_440_final.txt`（采信段） |
| 520dpi / fs 1.15 | 1168 → 1168（Δ=0） | 150px（46dp） | `ink_top=1185 ink_bot=1284`（100px） | 顶 12 / 底 36 | 同上（模拟器模拟真机参数，非真机定稿） | `ev_fs115_520_ink.txt`（07:37:50–07:38:22，同带闸门字段） |

判读：1.15 下候选字高 42→49px（+16.7%，与 sp 缩放一致），键盘高度**一位未涨**（恒高契约成立），墨迹未贴带边 → **无截字**。但要留个尾巴给真机：520dpi / 1.15 那组**顶部只剩 12px**（440dpi 同档位是 44px），字高 100px 已经吃掉带内 148px 的三分之二——真机字体（非模拟器 Roboto，且 FMR 可能带厂商字体替换）再高一点点就会先顶到上沿。这一组是本轮新测出来的，之前 §4 只写了「仍在带内」这种含糊话。截图证据：`shots/f440_1.0_c_cand.png`、`shots/f520_1.15_c_cand.png`（候选带裁图，当场由 `fs115.py` 落盘）。

## 5. 占屏比：440dpi 达标，按真机参数复现时不达标（本轮 no-go 主因）

| 口径 | iSpeech | 搜狗 | 差值 | 预算 ≤1pp |
|---|---|---|---|---|
| 440dpi / fs1.0（模拟器原生） | 988px = **42.22%** | 978px = 41.79% | **+0.43pp** | ✓ |
| 520dpi / fs1.15（模拟器模拟真机参数，非真机定稿） | 1168px = **49.91%** | 1002px = 42.82% | **+7.09pp** | ✗ |

机制：本包键高/候选条全部按 dp 布局，密度 440→520 时键盘像素高度同比放大（988→1168，×1.18 = 520/440）；搜狗用 px 资源（978→1002，×1.02）。所以 440dpi 下 +0.43pp 的余量在真机密度下会被放大成约 +7pp。**这不是 VOCA-46 引入的回归（52→46dp 反而收窄了差值），但它直接决定「占屏 ≤1pp」这条验收在真机上成不成立，模拟器无权定稿。**

→ 真机必做：520dpi / font_scale 1.15 下重测 iSpeech 与搜狗的 ime inset，给出定稿 pp 差。

## 6. §9.8 五条未验证路径

| 路径 | 结果 | 证据 |
|---|---|---|
| ① 编码 0 候选 | **PASS**：候选条不塌、不改高。空态（0 候选）带高仍 126px、键盘 988px，墨迹 0 行；`REG_double_space` 空态 kb_h=988 / band_ink=0 | `ev_fs115_440_final.txt`、`ev_paths4.txt` |
| ② 候选池上限截断 | **代码级确认，未做真机计数**：`candidatePoolLimit = candidatePageSize × CANDIDATE_POOL_FACTOR(3)`（`IspeechImeService.kt:105/187`），默认 9 → 27；用户可设 5/7/9 → 15/21/27。运行侧只证到「滚到端后不再新增内容」（§3 AC-6：本轮 `=` 按到第 26 次起 cum 恒定 4752px，上一轮同现象读数是 4892px，差一档 = 末档被钳制截断） | 代码 + `ev_ac47b.txt` |
| ③ T9 态 `-`/`=` 滚动 | **未取证**：模拟器上 `123 → 9键` 的软键坐标按权重推算落到了短语面板（kb_h=1885），两轮尝试均未稳定进入九宫格；`check_t9.py` 静态侧 87 项通过可保证键位定义未被本轮改动破坏 | `ev_paths5.txt`、`ev_paths9.txt` |
| ④ 上屏/联想后偏移归零（G2） | **未取证**：三次尝试的宿主字段分别被 Settings 搜索框吞键、联系人「姓名」框吞键、以及联系人「更改尚未保存」弹窗抢焦点，`-` 未能进 IME，位移数据不可信（已丢弃）。代码侧 `CandidateView.kt:81` 无条件 `scrollTo(0,0)` 仍在 | `ev_paths4/5/6/9.txt` |
| ⑤ 面板打开时候选条层级 | **PASS（并给出用户可见影响）**：工具栏「快捷短语」「剪贴板」面板会**整体替换输入区**，候选条不再存在；短语面板高 1885px（面板+工具栏+键区，无候选条），剪贴板面板高 727px（仅面板）。截图：`p5_phrase.png`、`p5_clip.png`。**返修轮订正**：工具栏 ⚙ 不是「直接跳设置页、键盘整体收起」——它先在输入法内打开配置面板（`IspeechImeService.kt:2071 openSettings → openPanel(Panel.SETTINGS)`，同样是整块替换输入区，9 个开关 + 中文输入法 + 语音 4 行 + 底部「更多设置」）；只有面板底部「更多设置」才跳 App 设置页（`:2076-2084`），而进入「键盘与输入」会崩（§3.1 D2）。AC-2 的开关置位因此走输入法内面板，`ev_ac2_*.txt` 的 `TOUCH/DS_*` 行就是这条路径的当场记录。 | `ev_paths4.txt`、`ev_ac2_soft.txt` |

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

**被测产物来源（第三人复现时的比对基准）**：`c524351` 在专用检出 `…/voca-47-d3c8a7824003/voca47-c524351`（`git rev-parse HEAD` = `c524351e2e509ed294f49dd3d2f7a1bf29e07a4d`）里 `assembleDebug` 产出 `app/build/outputs/apk/debug/app-debug.apk`，构建时间 11-17 03:43，整包 `sha256 = 52911cf22393b8292ec705a3dfef7fdf668fa37448bb8bc4d10d3fdb5afb1d5b`；装到设备后 `ident()` 不按整包哈希比（签名/时间戳会漂），而是逐条目比 17 个 `classes*.dex` + `resources.arsc` 的 SHA256。上表两条单测计数的原始出处是同一检出的 `app/build/test-results/testDebugUnitTest/TEST-com.ispeech.input.{InputModePolicyTest,SpaceKeyPolicyTest}.xml`（`tests=5/9`、`failures=0`、`errors=0`、`skipped=0`）。

## 8. 规格/实现差异与观察项（按 PM 裁定不判 FAIL）

1. **R4 补偿实现**（规格/实现差异）：实现只在「光标前一个字符仍是空格」时才 `deleteSurroundingText(1,0)`，判定入口 `lastSpaceStillBeforeCursor() = lastSpaceCommitAt != null && getTextBeforeCursor(1,0)==" "`（`IspeechImeService.kt:715-780`）。实测在宿主里先删掉空格再双击 → 得到 `␣␣` 而非「。」（`ev_r4.txt` S3、`ev_ac23d.txt` R4_probe=`她她们`），符合「不做破坏性删除」的意图，比规格更保守。
2. **G2 无条件 `scrollTo(0,0)`**（`CandidateView.kt:81`）：用户可见影响＝每次重绘候选（含联想刷新）都回到最左，滚动位置不保留。本轮未取到运行时证据（§6④），列为待补。
3. **G7 徽标无入口**：候选来源徽标（短语/AI/学习）仍渲染但没有点击入口，用户可见影响＝只能看不能用。
4. **P2 观察项（本轮已固化工装 `vswipe.py`，结论重新定性；不再挂「待复现」）**：
   - **短竖滑被当成点击**：起点 y=1391（带内）、x=300、duration 120ms、440dpi 扫位移：`dy=40/60/80` → 上屏（`shi`→`是`），`dy=110/150/200` → 不上屏；细扫 `dy=90` 上屏、`dy=100` 不上屏（`ev_vswipe_a.txt`、`ev_vswipe_c.txt`）。`dy=20` 那一档不参与判据——它实测不是上屏，而是**把键盘整个收掉**（`band_top=None`、`after='<NONE>'`、`ime_gone=True`），上一版把它算进「dy=20/40/60/80 上屏」是错的。**上屏阈值在 90–100px（≈33–36dp）之间**，比上一轮写的「60px 上屏 / 110px 不上屏」宽一档：60px 那半句成立，阈值那一档写窄了。质检专员复跑时报 110px 也上屏，本轮用同参数（起点 1390、dy=110、120ms）**连跑 3 次均未上屏**（原始读数在 `ev_vswipe_c.txt`）——这个跨 run 离散没定位，不写成结论，留给真机手指触摸那轮一并判。对照组：纯 `input tap 300 <带内 y>` 同样上屏，所以走的是候选项的点击路径。
   - **「上屏」与「横滚」分开量**（上一轮把两件事混在一条里是错的）。在滚动态先按 `=` ×4 把 scrollX 推到约 792px、内容稳定后做手势：**纯竖滑（dx=0，dy=110/150）位移 0，err 0.0 → 可判**，即带内纵向手势不驱动横向滚动；**斜滑（dx=160，dy=110）位移 −264px，err 4.37 → 可判**（这是已入库证据里的**唯一一次**斜滑样本，上一版写的「−264/−272、err 4.37/4.07」第二个值来自没入库的当场输出，本轮删掉——只有 n=1，够定性方向、不够定分布，下一轮至少同参数复跑 3 次）。方向与「手指右移 → scrollX 减小」的直接操作语义一致，属正常行为而非缺陷（`ev_vswipe_b.txt`）。
   - **上一轮 `ev_g46.txt` 里「候选条竖滑/斜滑 shift=−870」作废**，按本报告 §3 自己定的口径（residual>5 不可判）本来就不该出结论：那是拿**上屏前后**两帧做互相关，候选池被 `CandidateView.kt:81` 的 `scrollTo(0,0)` 整体重置，比的是不同内容。本轮已入库的 6 个「上屏」case（`ev_vswipe_a` 的 dy=40/60/80、`ev_vswipe_c` 的 dy=90、`ev_vswipe_b` 的滚动态 dy=60，外加每次 run 末尾的纯 tap 对照）复现出同形状的假读数：非滚动态一律 **−832（err 12.04）**，滚动态那次 **−842（err 8.84）**，全部按口径记 `unverifiable(err>5.0)`，机制坐实。（上一版写的「−796 / err 11.78」在 `ev_vswipe_*` 三份文件里都不存在，是当场没入库的输出，本轮删掉。）
   - **「起点越出候选带仍能命中候选」撤回**：起点在带上沿外 215px（y=1137，落在宿主内容区）向下滑 215px，实测是**这一下手势把键盘收掉了、宿主焦点也没了**（`band_top=None`、`after='<NONE>'`、`ime_gone=True`），不能读成上屏。工装同步补口径：`committed` 只在 IME 仍在位且宿主文本可读时才置真，并另打 `ime_gone` 字段。
   - 工装风险提示：位移越小越容易被系统判成别的手势——dy=20 在带内起手时整条用例翻成「键盘被收掉」（见上面阈值条），说明这条用例对共享 AVD 的环境争用与手势识别阈值都敏感；判据一律以「同参数多次复跑读数一致」为准，单次读数不落表。

## 9. 未取证清单（真机 / 后续必须补）

| # | 项 | 为什么模拟器判不了 | 归属 |
|---|---|---|---|
| 1 | 520dpi / fs1.15 的定稿占屏 pp | 模拟器只是「模拟真机参数」，字体渲染与厂商 ROM 不同 | SRE + 人类评审（装真机跑） |
| 2 | §9.8 ③ T9 态 `-`/`=` 滚动、④ 上屏后偏移归零 | 模拟器软键坐标靠权重推算，面板态抢焦点；换真机或固化 uiautomator 定位后可测 | 测试（下轮补，非真机专属） |
| 3 | 语音键上滑取消、语音失败文案 | 需要真实音频输入 | 测试 + 前端 |
| 4 | TalkBack 真机朗读与 600ms 窗口 | 模拟器未装 TalkBack 服务态 | 测试 + UX |
| 5 | 真机手指触摸的滚动/长按手感（fling 不可复现，本轮一律用 `-`/`=` 与 `input swipe` 固定位移） | 触摸离散度 | 人类评审 |
| 6 | release 打包 + 真机安装 | 项目规则五：生产操作需人类确认 | 人类确认 |
| 7 | AC-9 的 45ms 快档到底可达不可达 | **口径要先换**：`adb shell input keyevent --longpress`/注入式长按的实测上限就落在 90ms 慢档（4s→删 40 字→92.8ms/次，`ev_ac9d.txt`），拿注入时长反推永远看不到 45ms 档。下一轮改用 logcat 打点量两次删除的实际间隔，或真机手指长按定性 | 测试（logcat 口径）+ 人类（真机手指） |

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
#    它会让 §3.1 D2 的崩溃必现，也会让「开关默认 false」这类判据的前提不成立）
adb -s emulator-5554 shell pm clear com.ispeech.ime
adb -s emulator-5554 install -r -d -g <app-debug.apk of c524351>
adb -s emulator-5554 shell ime enable com.ispeech.ime/com.ispeech.input.IspeechImeService
adb -s emulator-5554 shell ime set   com.ispeech.ime/com.ispeech.input.IspeechImeService

# 2) 恒高 / 字体缩放 / 截字（§3 §4）
python3 tools/qa/voca-37/fs115.py <apk> 440 1.0,1.15
python3 tools/qa/voca-37/fs115.py <apk> 520 1.0,1.15

# 2b) 身份闸门自检（返修项 4）：切到 Gboard 后必须打 SKIP，不许再产出 delta=0
adb -s emulator-5554 shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
python3 tools/qa/voca-37/fs115.py <apk> 440 1.0        # → SKIP reason=current_ime_not_ispeech，exit=3

# 2c) AC-2 双击空格：开关态 × 键路径（返修项 3），两份 stdout 分文件入 evidence/
#     开关置位走输入法内配置面板（齿轮键 1005,1552 → 滚到底 → 逐行试点，用 DataStore 键差异确认点中了谁），
#     不走 App 设置页——那条路会崩（§3.1 D2）。DataStore 在 files/datastore/，不是 datastore/。
python3 tools/qa/voca-37/ac2.py <apk> 440 keyevent     # 硬件/注入键 + 开关开/关反例
python3 tools/qa/voca-37/ac2.py <apk> 440 soft         # 软键点击 + 开关开/关反例

# 2d) §3.1 D2 崩溃复现（顺序不能换：先弹键盘让 DataStore 进进程缓存）
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
python3 tools/qa/voca-37/ac47.py <apk> 440
#     每行都带 key=keyevent70(EQUALS→scrollCandidates(+1)) / keyevent69(MINUS→...) 与 err、cum、verdict；
#     默认 fwd=70 / rev=69。反向证伪档（本轮实跑，rc=4 拒绝出数）：
python3 tools/qa/voca-37/ac47.py <apk> 440 --fwd 69 --rev 70
#     → PRE_PROBE key=keyevent69 shift=0 err=0.0 verdict=ok + GATE blocked=前提不成立…（ev_ac47_falsify.txt）
#     同一台机同一数据态的正向对照（ev_ac47_probe.txt，13:11:48–13:12:50）：
python3 tools/qa/voca-37/ac47.py <apk> 440 --n 3
#     → PRE_PROBE key=keyevent70 shift=198 err=2.88，sweep 正常出数

# 5) §8.4 竖滑用例（返修项 3 的工装，起点 y / 位移 dy / duration / dx 全参数化）
python3 tools/qa/voca-37/vswipe.py <apk> 440                                    # 上屏阈值扫 dy=20..200 + 纯 tap 对照 → ev_vswipe_a.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --mode abs --start 1390 --dy 90,100,110,110,110   # 阈值两侧 + 同参数复跑 → ev_vswipe_c.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family hroll --pre 4 --dy 60,110,150           # 滚动态纯竖滑是否横滚 → ev_vswipe_b.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family hroll --pre 4 --dy 110 --dx 160         # 滚动态斜滑 → ev_vswipe_b.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --mode above --start 215 --dy 215               # 起点在带上沿外（宿主区）
```

## 11. 环境风险与 evidence/ 全量清单（必须记录，否则数字不可比）

同一台 `emulator-5554` 在本轮执行期间被其他 run **换包 3 次**（`pm path` 的包目录哈希与 `lastUpdateTime` 变化：04:42:28、05:06:47、05:18 前后 `default_input_method` 被切到 Gboard）。返修轮起机时设备里又是 snapshot 恢复的 **versionCode=6** 包，`adb install -r -g` 直接 `INSTALL_FAILED_VERSION_DOWNGRADE`，装本包必须带 `-d`（脚本已改）。因此：

- 所有采信数字都带安装身份双端校验：对设备上 `base.apk` 与本地构建产物逐条目比对 17 个 `classes*.dex` + `resources.arsc` 的 SHA256（整包 APK 哈希不可比，签名/时间戳会漂）；
- **返修轮补的测量端闸门**：dex 校验只证明「装的是本包」，证明不了「此刻弹出来的键盘是本包」。`fs115.py` 现在在 `ime_frame()` 前后各读一次 `default_input_method` 写进 `RESULT` 行，任一端不是 `com.ispeech.ime/` 就打 `SKIP` 且不记录该组数字；入口也不再替操作器抢 IME。反例见 `ev_fs115_gate.txt`：把 IME 切到 Gboard 后重跑，输出的是 `SKIP reason=current_ime_not_ispeech`，不再是那条假的 `delta=0`。此前 520dpi 混进来的 933px（候选底色 `(241,240,247)` ≠ 本包 `#F4F5F7`=(244,245,247)）就是这个洞的产物；
- 04:42–04:50 之间的一批读数经复核是 Gboard/他人构建的污染值，本报告只引用身份校验通过的窗口（04:51:56–04:53:13、04:59:35–05:01:00、05:03:44–05:05:18、05:08:20–05:10:48）与返修轮窗口（06:55 起，见 §11.1 各行的 `IDENT`/`HOST` 时间戳）；
- 建议：QA 独占 AVD（或给每个 run 建独立 avd 名），否则每轮都要付「重测 + 作废」的成本。

### 11.1 evidence/ 全量清单（逐个文件给口径，不留未署名的读数）

04:42–04:50 之间的一批读数经复核是 Gboard/他人构建的污染值。下表覆盖目录内**每一个**文件：`采信`＝正文引用其数字；`部分采信`＝同文件内有作废段，正文只用有效段；`作废`＝不得引用任何数字，只留作环境争用的证据。

| 文件 | 状态 | 口径 / 作废原因 |
|---|---|---|
| `ev_headline.txt` | 部分采信 | fs1.0 段（988→988 ×3、pp=42.22，IDENT 双端 match=True 04:51:56–04:53:13）采信；fs1.15 的 833px 段是 Gboard 抢占值，作废 |
| `ev_fs115_440_fs10.txt` | 采信 | 440dpi / fs1.0 候选带墨迹原文（§4 第 1 行的 1400–1441）；带 `ime_before/ime_after` 闸门字段 |
| `ev_fs115_440_final.txt` | 部分采信 | fs1.15 的 `INK comp ink_top=1397 ink_bot=1445 top_pad=44 bot_pad=31` 采信；同轮 `RESULT fs=1.0 host=FAIL` 无数据 |
| `ev_fs115_520_ink.txt` | 采信 | 520dpi / fs1.15 候选带墨迹原文（§4 第 3 行的 1185–1284）；带闸门字段 |
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
| `ev_ac2_hw.txt` | 采信 | 硬件/注入键路径（`input keyevent 62`）× 开关开/关，含 `SWITCH state=... src=datastore raw_hex=...`、`MODE`、`IDENT` 与 `wall_between_ms` → §3.1 D1 |
| `ev_ac2_soft.txt` | 采信 | 软键点击路径 × 开关开/关，同上口径（含间隔 577.1ms 的窗口外反例、三连点 `。␣`） |
| `ev_crash_kbsettings.txt` | 采信（缺陷单） | §3.1 D2 的崩溃原文：`FATAL EXCEPTION: main` / `Process: com.ispeech.ime, PID: 30743` / `NullPointerException ... MutableStateFlow.setValue` @ `KeyboardViewModel.kt:35`；含「先弹键盘再进页面」的复现时序自证（两次 `pid` 同为 30743） |
| `ev_ac47.txt` | 部分采信 | 位移阶梯与钳制的**数值**可用（±198、cum 4892、端点后归零）；**键名作废**——文件里没记 keycode，正文当时把 `-`/`=` 的方向标反了。方向/端点归属一律以 `ev_ac47b.txt` 为准；这批标签当时由一次性命令产出、脚本未入库，也是上一轮被打回的原因 |
| `ev_ac47_probe.txt` | 采信 | 闸门上线后的正向复测（13:11:48–13:12:50）：`PRE_PROBE keyevent70 → shift=198 err=2.88`、`bg_match_pct=77.5`；cum 2970/15 档到端，与 12:31 那轮 4752/24 档不同——记「档位稳、cum 随候选池规模变」这条口径的凭据 |
| `ev_ac47_falsify.txt` | 采信（证伪档） | `--fwd 69 --rev 70` 后 `PRE_PROBE keyevent69 → shift=0 err=0.0`、`GATE blocked=前提不成立…`、rc=4：方向判据不是叙述，参数对调就拒绝出数。13:06 那次对调跑（闸门还没上）产出的是全 0 的假钳制 sweep，已删除、不入库 |
| `ev_ac47b.txt` | 采信 | §3 AC-4~AC-7 的返修轮重测（12:31:03–12:32:51，`IDENT` 双端 match=True）：`=`=keyevent70→+198×24 档、cum 4752 后归零；`-`=keyevent69→−198、左端第 25 次归零；`AC7_FROM_LEFT` +198 err 2.85；宿主全程 `shi`。产出脚本 `ac47.py` 已入库，命令行见 §10-4b |
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

### 附：本报告引用的原始证据

`ev_headline.txt`（作废段已标注）、`ev_ac47.txt`（键名作废）、`ev_ac47b.txt`、`ev_ac47_probe.txt`、`ev_ac47_falsify.txt`、`ev_ac23{,b,c,d}.txt`、`ev_ac2_{soft,hw}.txt`、`ev_r4.txt`、`ev_ac9*.txt`、`ev_g46.txt`（−870 两行作废）、`ev_vswipe.txt`（部分采信）、`ev_vswipe_{a,b,c}.txt`、`ev_pp.txt`、`ev_sogou{115,520}.txt`、`ev_fs115b.txt`、`ev_fs115_440_fs10.txt`、`ev_fs115_440_final.txt`、`ev_fs115_520.txt`、`ev_fs115_520_ink.txt`、`ev_fs115_gate.txt`、`ev_crash_kbsettings.txt`、`ev_paths{2,3,4,5,6,7,8,9}.txt`、`gradle_test.log`（全部在 `tools/qa/voca-37/evidence/`，共 48 份，逐文件口径见 §11.1），截图 `shots/f440_1.0_c_cand.png`、`f440_1.15_c.png`、`f440_1.15_c_cand.png`、`f520_1.15_c_cand.png`、`p5_phrase.png`、`p5_clip.png`、`reg_phr_after.png`。工装脚本：`tools/qa/voca-37/{fs115,ac2,ac47,vswipe,drive,paths,paths2,paths3,qa_kb,qa_rows}.py` + `tools/qa/voca-37/README.md`。
