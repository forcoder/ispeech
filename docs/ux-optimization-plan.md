# 输入法使用体验优化 — 交付计划（VOCA-37）

> 状态：`in_progress`（已派发 Stage 1，Stage 2/3 待晋升）
> 责任：项目经理（本文件 Owner）；实现不在此文件范围。
> 数据采集：2026-11-09，真机 `FMR0224913042917`（ALN-AL00 / 1260×2720 / density 520 → 3.25x）。

## 一句话目标

在不改需求、不扩功能的前提下，把「键盘占屏、候选条空态、标点快捷、按键反馈、候选翻页手势」这五处与搜狗五笔的体验差距补齐，且每一项都有真机可复现的验收证据。

## 范围边界

**做（本 issue）**

| # | 项 | 为什么算体验而非视觉 |
|---|---|---|
| 1 | 键盘占屏高度（空态） | 直接决定内容可视面积；实测比搜狗高 107px |
| 2 | 候选条空态收起策略 | 空候选仍占一条常驻高度 |
| 3 | 双击空格输出句号 | 中文输入高频动作，省一次切标点页 |
| 4 | 候选条滑动翻页 | 单手不必去点右上角翻页器 |
| 5 | 按键音效（可开关） | 现有仅震动，无声音反馈 |

**不做（另有归属，勿在本 issue 重复）**

- 键盘键面视觉重排（字号 / 圆角 / 换行键）→ VOCA-30，已 `in_review`。
- 默认中文输入法（五笔 / 拼音）设置 → VOCA-29，已 `in_review`。
- 「通用短语.csv」导入 → VOCA-28，已 `in_review`。
- 语音输入与 ASR 模型源 → VOCA-33 / 34 / 35。
- 皮肤包、云词库同步、双拼、手写、五笔 98 → 未立项，需人类先决定是否投入。

## 实测差距（本机测量，非推测）

### 1. 键盘占屏高度

同一台机器、同为「空候选 + 字母页」状态，从 `dumpsys window displays` 的 `ITYPE_IME` inset 直接读窗口上下沿：

| | iSpeech（设备实装） | 搜狗五笔 | 差 |
|---|---|---|---|
| IME 窗口 | `[0,1628][1260,2720]` | `[0,1735][1260,2720]` | — |
| 高度 | **1092 px / 336.0 dp** | **985 px / 303.1 dp** | **+107 px / +32.9 dp** |
| 占屏比 | **40.1 %** | **36.2 %** | +3.9 个百分点（相对搜狗 +10.9 %） |

复现：

```bash
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
D=FMR0224913042917
$ADB -s $D shell ime set com.ispeech.ime/com.ispeech.input.IspeechImeService
$ADB -s $D shell dumpsys window displays | grep "ITYPE_IME frame"
$ADB -s $D shell ime set com.sohu.inputmethod.sogou/.SogouIME
$ADB -s $D shell dumpsys window displays | grep "ITYPE_IME frame"
```

**归属待确认**：这 107px 里各项组件的占比本轮没有拆开测（候选条底色 `#F2F3F5` 与键盘底色同色，截图分不出边界）。已知的代码事实是：候选条在空态**从不收起**（见下），键盘默认键高 `52dp`（另有 46 / 58 两档）。拆解与取舍交 UX Stage 1 出结论，涉及改默认观感的要人类确认。

**测量口径的已知偏差**：上面的 1092px 测自设备当前实装包（`versionCode 5`，`lastUpdateTime 2026-11-09 16:26`），该包**不含 VOCA-30 的键面改动**。VOCA-30 把 `keyMargin` 由 4dp 改成 3dp，会影响每行上下留白，因此合并后 iSpeech 的高度预计会小幅下降（4 行键 × 2 个边距 × 1dp ≈ 8dp 量级，未实测）。结论「iSpeech 比搜狗高」不会因此改变，但**具体差值必须在 VOCA-29/30 合并后重测一次**再定验收基线 —— 这条已写进 VOCA-45 的交付要求。

### 2. 代码层现状（`c753b47` 基线）

| 项 | 现状 | 证据位置 |
|---|---|---|
| 候选条空态 | **常驻**：`update()` 只在无候选时隐藏内部 `scrollView`，候选条本身从未 `GONE`；`candidate_view.xml` `minHeight=44dp` | `CandidateView.kt:96-120`、`res/layout/candidate_view.xml`、`IspeechImeService.kt:299` |
| 双击空格出句号 | **未实现**（`grep lastSpaceAt|doubleSpace|spaceCount` = 0 命中） | `IspeechImeService.kt:713 handleSpace()` 无时间窗判断 |
| 候选条滑动翻页 | **未实现**（`CandidateView` 无 `GestureDetector` / `onFling`；只有点翻页器与硬件 `-` `=`） | `CandidateView.kt` 全文 |
| 按键音效 | **未实现**（`grep playSoundEffect|AudioManager` = 0 命中；只有 `performHapticFeedback`） | `SoftKeyboardView.kt:770` |
| 键盘手势删除 | **未实现**（仅语音键上滑取消有 `onTouchEvent`） | `SoftKeyboardView.kt:739-760` |

`docs/sogou-feature-matrix.md` 的 Roadmap 第 1 条已登记「手势翻页 / 键盘左滑删除」，与本表第 3 行一致。

### 3. 本轮**未**验证的搜狗行为（不得当结论引用）

设备在测量中途掉出 USB（`adb devices` 空列表，重启 adb server 未恢复），以下三项**没测成功**，交给 Stage 1 用真机补测：搜狗「双击空格是否出句号」、候选条滑动翻页的实际手感阈值、按键音效是否存在及默认开关状态。任何按「搜狗就是这样」写的规格都必须附实测截图。

## 依赖：三个 `in_review` 分支都还没进 main

```
origin/main = 904a98d      ← 不含 voca-28 / 29 / 30 的任何改动
设备实装 APK lastUpdateTime 2026-11-09 16:26
  → 不含 ic_enter（VOCA-30）、不含「中文输入法」文案（VOCA-29）、不含「导入 CSV」文案（VOCA-28）
```

核对方式：`unzip -l` 查 `res/drawable/ic_enter`，`aapt2 dump resources` + dex 字符串查文案，三者均 0 命中。

**因此 Stage 2 必须在 VOCA-29 与 VOCA-30 合并之后开工**，否则实现的候选条 / 键盘改动会与 VOCA-30 的键面重排在同一批文件上撞车（`SoftKeyboardView.kt`、`IspeechImeService.kt` 双方都改）。已用 `git merge-tree --write-tree` 验证：`HEAD + voca-29`、`HEAD + voca-30`、`voca-29 + voca-30` 当前**无文本冲突**，但语义冲突仍需按合并后的代码重排。

## 里程碑

| Stage | Owner | 交付物 | 完成判据 | 状态 |
|---|---|---|---|---|
| 1 | UX设计师 | `docs/keyboard-ux-spec.md`：5 项交互规格 + 状态矩阵 + Given/When/Then 验收标准，每项标注采纳/不采纳与真机证据 | 每条有可测验收标准、异常态覆盖齐全 | `todo`（本次派发） |
| 2 | 前端工程师 | 按规格实现 + 单测 + 真机截图 | 编译通过、单测绿、`check_static` / `check_crosscut` 全绿、真机截图逐项对照 | `backlog`（等 Stage 1 + VOCA-29/30 合并） |
| 3 | 测试工程师 | 真机回归报告 + go/no-go | 5 项逐条通过、无 P0/P1 | `backlog` |

## DoR（进 Stage 1 前已满足的部分）

- 目标与用户价值清楚：对齐搜狗五笔的键面占屏与高频操作效率。
- 差距可测：已有实测数字（见上表），不是主观判断。
- 依赖已识别：VOCA-29/30 合并顺序、真机可用性。
- **缺口（由 Stage 1 补齐）**：状态覆盖矩阵与逐条可测验收标准。
  说明：本 issue 的「需求」层由人类在 VOCA-37 直接给定（对齐搜狗），不需要洞察BA 重新定义；缺口在交互层，按 squad 分级评审表归 UX。若 Stage 1 发现存在产品取舍（例如「候选条是否允许常驻」涉及默认观感），必须升级人类，不自行拍板。

## DoD（可交付标准）

- 5 项逐条有真机证据（截图 + `dumpsys` 数值），不是「已实现」。
- `python3 tools/verify/check_static.py`、`check_crosscut.py`、`check_input_logic.py` 相对基线无新增失败。
- 键盘占屏相对搜狗差距收敛到 ≤ 1 个百分点，或给出人类确认的取舍理由。
- 已合并 PR + 人类评审签收。

## 验收口径（Stage 2 起可逐条验，Stage 1 会细化成 Given/When/Then）

| # | 验收点 | 判据 |
|---|---|---|
| 1 | 空候选时键盘高度 | `ITYPE_IME` 高度相对搜狗差值 ≤ 1 % 屏高 |
| 2 | 有候选时不再二次跳动 | 从空态到出候选，窗口高度变化 ≤ 8 dp |
| 3 | 双击空格 | 600 ms 内连按两次空格，输出一个中文句号「。」且不吞掉前一个字；单按仍上屏首选/空格 |
| 4 | 候选滑动翻页 | 候选区左滑翻下一页、右滑上一页；滑动不触发候选上屏 |
| 5 | 按键音效 | 设置页可开/关，默认值经人类确认；关闭后无声，开启后每个按键有音且无叠音 |

## 风险登记册

| 风险 | 概率 | 影响 | 应对 | Owner |
|---|---|---|---|---|
| 三个 `in_review` 分支长期不合并，Stage 2 反复返工 | 高 | 高 | Stage 2 保持 `backlog`，合并后才晋升；PM 在 VOCA-28/29/30 合并后手动提升 | 项目经理 |
| 真机中途掉线导致无法验收 | 已发生 1 次 | 高 | 每个 Stage 的交付必须含真机证据；掉线时以「未验证」如实交付，不用截图代替 | 各 Stage owner |
| 降低键盘高度影响键面可点面积 / VOCA-30 已量的字形基准 | 中 | 中 | 只在空态收起候选条而非压键高；若必须压键高，重跑 VOCA-30 的字形测量 | UX + 前端 |
| 双击空格与「空格上屏首选」语义冲突 | 中 | 中 | 明确优先级：有候选时空格上屏，不参与双击判定 | UX |
| 按键音效在部分 ROM 上被系统静音策略忽略 | 中 | 低 | 不做硬性承诺，验收只验 App 侧开关与调用 | 前端 |

## 非目标

- 不为了「像搜狗」而引入搜狗的商业功能（云词库、词库商店、账号体系扩展）。
- 不改输入引擎（五笔 / 拼音 / T9）的查询与排序逻辑。
- 不新增第三方依赖；音效用 `AudioManager.playSoundEffect` + 系统音，不引入音频库。