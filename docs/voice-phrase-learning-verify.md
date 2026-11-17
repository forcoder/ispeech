# 五笔双字词组自学习 — 真机验证步骤（VOCA-124）

> 关联：VOCA-65 条目 3、ADR `docs/adr/VOCA-65-phrase-learning.md`、验收标准
> `docs/requirements/VOCA-65-acceptance.md` **v1.1.2**（`agent/ba/voca-122 @ b8e8d40`）的
> AC-3.4 / AC-3.6 / AC-3.7 / AC-3.10 / AC-3.11 / **AC-3.12**。
> AC-3.7 与 AC-3.12 的判定以该版本 §3.5 的子节「AC-3.7 / AC-3.12 判定口径（v1.1.2 修订）」为唯一依据：
> AC-3.12 **未作废**，作废的只是其中「页码 +1」半句（VOCA-185 `fa57a07` 删了页码控件），
> 其余改写为「学习前 / 学习后两次快照」的 ①②③ 三条门禁，见本文 §2。
> **引用版本未过期**：权威文件已升到 v1.1.3（`agent/ba/voca-122 @ 36403e6`），v1.1.3 只动条目 1/4 与
> §7/§13，**§3.5 的判定文本与 v1.1.2 逐字相同**（`git diff b8e8d40 36403e6` 已核实）；本文继续引
> `b8e8d40` 是为了让行号与本文其它处的「v1.1.2」一致，读的人不必担心口径过期。
> JVM 段（AC-3.1~3.6、3.9、AC-3.4 的纯函数段）已由 `./gradlew testDebugUnitTest` 覆盖；本文只列**本机不可自动执行**的真机项。
> 开发者在无 `adb` 连接设备时不得声称已验，必须按本文交付给测试执行。

## 0. 准备

```bash
# 安装最新 debug 包（本机构建）
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk

# 清空既有用户词库，从干净状态开始（设置 → 用户词库 → 清空；或卸装重装）
adb shell pm clear com.ispeech.ime
```

打开任一可输入的 App（短信 / 备忘录），把输入法切到智语，并**切到五笔态**：

- 设置 → 键盘与输入 → 中文输入法 → 选「五笔」；
- 左下角「中/英」键只在「中文态 ⇄ 英文态」之间切换，不再循环三种状态（VOCA-30 后的行为）；
  回到中文态时固定使用上面选定的输入法。

> 换输入态会让词组学习状态作废（`enterMode` 里 `phraseTracker.reset()`），所以两组验证之间不要切换中/英。

打日志（三组全程保持）：

```bash
adb logcat -c
adb logcat -s IspeechIme:D IspeechIme:I IspeechIme:W UserDictStore:I | grep -E "phrase_learn|phrase_remove"
```

> `-s` 是白名单，没列的 tag 会被整条滤掉，`grep` 只能在它已经放行的行里挑。本文涉及两类日志、两个 tag，
> **两个都必须列**：`phrase_learn` 走 `TAG="IspeechIme"`、`phrase_remove` 走 `UserDictStore`；
> 逐条发射点与触发条件见下方清单（唯一权威表，正文各处只指它、不各自复述）。
> 少写 `UserDictStore:I` 会让 §5 的 AC-3.10 永远是 0 命中，那是文档的错，不是功能的错。
>
> 忘了挂前台管道（或中途断线重连）时，用同一份 buffer 事后补抓，判据不变：
>
> ```bash
> adb logcat -d | grep -E "phrase_learn|phrase_remove"
> ```

**日志发射点清单**（`git grep -n "phrase_learn\|phrase_remove" app/src/main` 的全部 6 处）。本文任何一条
「期望出现」的判据都必须能在下表里指到一行；指不到的，是文档写错，不是功能 fail：

| 日志文本 | tag / 级别 | 发射点 | 触发条件 |
|---|---|---|---|
| `phrase_learn commit code=… text=…` | `IspeechIme` / I | `IspeechImeService.kt:1143` | tracker 判成词，**且**「用户词频学习」开关开着 |
| `phrase_learn skipped reason=disabled` | `IspeechIme` / D | `:1140` | 判成词但开关关闭（见 §4） |
| `phrase_learn skipped reason=interrupt` | `IspeechIme` / D | `:1178` | **只有** `onUpdateSelection` 判出「他因光标移动」这一条路径会打，且受下方 300 ms 宽限窗约束 |
| `phrase_learn skipped reason=window / existing / repeat / clock_backwards count=…` | `IspeechIme` / D | `:1126` | 第二字上屏时 tracker 给出跳过原因（需要那时仍有上一跳可凑；来源非单字，或码点长度 ≠ 1） |
| `phrase_learn undecidable first=… second=… count=…` | `IspeechIme` / W | `:1124` | 同上，原因为编码推导失败 |
| `phrase_remove code=… text=…` | `UserDictStore` / I | `KeyboardViewModel.kt:137` | 设置页单条删除（见 §5） |

> **面板开合不打日志**：`openPanel:1316` / `closePanel:1356` 只调 `phraseTracker.onInterrupt()` 清状态，
> 后面没有日志语句；`onInterrupt()` 之后下一次单字上屏会被当成**新的第一跳**（`lastSkip` 为 null，
> `onPhraseCommit:1120` 直接 `?: return`），整条路径一行都不打。所以 §1 ③ 组**不会**出现
> `reason=interrupt`，它的判据只能是「词库无新增 + 没有 commit 日志」。
>
> **300 ms 自因宽限窗（操作节奏是判据的一部分）**：本服务写完 InputConnection 后有
> `SELF_WRITE_GRACE_MILLIS = 300L` 的宽限窗（`IspeechImeService.kt:128`），窗内的光标回调一律
> **不判中断、也不打日志**（`onUpdateSelection:1172-1174` 的 `withinSelfWrite`）。这是 ADR `F1`
> 的保护、不是 bug，但它意味着：
>
> - §1 ② 组必须在「喂」上屏 **≥ 500 ms 之后**再移动宿主光标。若在 300 ms 内连打（脚本化步骤几乎必然如此），
>   ② 组会直接学出 `phrase_learn commit code=klqt` —— 那是**步骤踩进了宽限窗**，不是功能的 fail，
>   按 §1 ② 的归因段重跑，不要退回开发。
> - §1 ① 组的反向用例同样靠这个窗保护，**不要**为了「看起来更快」把它改成脚本连打：窗替 `F1` 兜底之后，
>   这一组就测不到它唯一要测的东西（正常上屏被自己误判成中断）。① 组必须手动、按人眼节奏点选。

词库落盘检查（每次验证前后各取一次）：

```bash
adb shell run-as com.ispeech.ime cat files/user_dict.txt
```

## 1. AC-3.4 真机段：光标与面板中断（三组）

ADR §1 的 `F1` 风险点是「本服务自己造成的上屏把上一跳误判成中断」，所以第 ① 组是**反向用例**，必须最先跑。

> **② 与 ③ 之间清一次词库**（`adb shell pm clear com.ispeech.ime` + 重挂日志）。② / ③ 的语义是「上一跳被中断
> 作废」，但最后一步的「猫」仍会成为**新的第一跳**留在状态机里；不清词库直接跑下一组，本组的「喂」会和
> 上一组留下的「猫」凑成另一个词，打出 `phrase_learn commit code=qtkl text=猫喂` 并写库（① 组不会，它的两跳
> 已被正常消费）。这属预期行为，但会污染计数，所以三组的判定都只看 `klqt 喂猫` 与 `commit code=klqt`，
> 别把 `qtkl` 之类的旁生条目算进 fail。

### ① 连续拼两个单字不移动光标（正向 + 反向合并）

1. 输入 `klge`，候选第 1 条应为「喂」，点选它上屏；
2. 1~3 s 内输入 `qtal`，选「猫」上屏；
3. 重复 10 次（每次换一对不同的字，例如 `lkg`+`gytg`→加班 会被「系统已有」挡掉，换 `wqiy`+`klge` 之类）。

**节奏要求（不得脚本连打）**：全部手动点选，每次上屏后停 ≥ 500 ms 再做下一个动作。本服务上屏后有
300 ms 自因宽限窗（见 §0），脚本在窗内连打等于让宽限窗替 `F1` 兜底，这一组就测不到它要测的东西。

**期望**：

- 日志出现 `phrase_learn commit code=klqt text=喂猫`（每次成功的拼接各一条）；
- `phrase_learn skipped reason=interrupt` 出现 **0 次**（这是 `F1` 的判据：只要 ≥1 次就说明自因过滤失效，每次上屏都被自己中断）；
- `user_dict.txt` 实际新增对应条目，`count=1`。

判据（可量化口径，与裁决 §3.5 真机段「① 组连续 10 次无一次误中断」一致）：**10 次拼接 → `commit` 10 条、`reason=interrupt` 0 条**；两项任一不达即 `F1` 成立，按 ADR §1 降级方案处理（调大 `selectionTolerance` 或改回只比位置），并把计数写进报告。

`commit` 不足 10 条时**先归因再判 `F1`**：状态机还会给出 `window` / `existing` / `repeat` /
`clock_backwards`（`phrase_learn skipped reason=…`）与 `undecidable`
（`phrase_learn undecidable first=… second=…`，WARN 级）这几类，见
`PhraseLearningTracker.kt` 的 `PhraseSkipReason`。这几类是**字对没选好**（超出时间窗 / 系统已有 /
同字连打 / 全码推不出），不是学习失效——换一个不含系统已有词、码可推导的字对重跑，并把实际出现的
reason 原文记进报告；只有换字对之后 `commit` 仍不足 10 条，才计入上面 `F1` 的判据。

### ② 拼完第一字后移动宿主光标

1. 输入 `klge`，选「喂」上屏；
2. **等 ≥ 500 ms**（越过 §0 的 `SELF_WRITE_GRACE_MILLIS = 300 ms` 宽限窗），再用系统手势或方向键把宿主光标
   移到别的位置（例如点一下输入框开头的文字）。光标基线容差 `selectionTolerance` 默认 0
   （`IspeechImeService.kt:277`），所以只要移开就是「他因」，不需要移很远；
3. 1~3 s 内输入 `qtal`，选「猫」上屏。

**期望**：日志出现 `phrase_learn skipped reason=interrupt`（发射点 `:1178`，见 §0 清单），且 `user_dict.txt` **没有** `klqt 喂猫` 新增。

**先归因再判 fail**：若第 3 步打出的是 `phrase_learn commit code=klqt`，说明第 2 步的光标操作落在了
300 ms 宽限窗里（窗内的回调一律不判中断），**这是步骤问题、不是功能 fail** —— 把第 2 步的等待加到
≥ 500 ms 重跑一次；只有加了等待之后仍然学出词，才计入 AC-3.4 的 fail。

### ③ 拼第一字后切短语面板再切回

1. 输入 `klge`，选「喂」上屏；
2. 点工具栏打开「快捷短语」面板，再关掉；
3. 1~3 s 内输入 `qtal`，选「猫」上屏。

**期望**（口径来源：验收文件 §3.5 真机表 ③ 行「期望同上，**无新增**」，同表 JVM 行断言的是「写入次数 0、
状态回 `idle`」——两层都在断言「没学成」，没有一层断言面板路径会打日志）：

1. `user_dict.txt` **没有** `klqt 喂猫` 新增（与验证前那次 dump 逐行比）；
2. 日志里**没有** `phrase_learn commit code=klqt`。这是本组的**正控**：面板中断若失效，「猫」会顺上「喂」
   凑成 `klqt` 并打出这条 commit —— 所以「没有 commit」恰恰证明了中断生效；
3. 本组**不要求**任何 `skipped` 日志。

> **为什么 ③ 组不会有 `reason=interrupt`**：面板开合路径（`IspeechImeService.kt:1316` / `:1356`）
> 只调 `phraseTracker.onInterrupt()` 清状态、**不打日志**，③ 组出现该日志属 ② 组（唯一发射点 `:1178`
> 在光标回调里）。若在 ③ 的同一次会话里看到 `reason=interrupt`，它来自 ② 的光标操作、或从面板里
> 点了候选（`:1126` 的「来源非单字」分支）——不能当 ③ 的通过证据，也**不能因为它没出现而判 ③ fail**。
> 本期约定不动实现（只在面板路径补一条 `source=panel` 日志属实现变更，需另立改动并回来同步本节与 §0）。

**取证要求**：机型 / ROM 版本 / 三组的 logcat 关键行原文随报告留档；不得只写「已验」。

## 2. AC-3.7 + AC-3.12：「喂猫」是否在 index 0，同码系统词是否一条不少

本节门禁逐字对齐验收文件 v1.1.2 §3.5 的子节「AC-3.7 / AC-3.12 判定口径」。**AC-3.7 的门禁是候选条序列
index 0**（不是「组内第 1」）；「词组组内第 1 与全局 index 0 是同一个结果」是裁决给出的**推论**，
用来说明为什么保住强断言不需要改 VOCA-185 的分组顺序，**不是**门禁的替代或削弱。

> AC-3.7 判定列原文（v1.1.2）：候选条**序列第 1 项**为「喂猫」——可观察锚点：右上角数字角标「1」+ 首选高亮底色；该项必在首屏最左（每次刷新 `scrollTo(0,0)`），无需滑动即可一次点选；点选后输入框以「喂猫」结尾、编码缓冲清空
>
> AC-3.12 判定列原文（v1.1.2）：① 不丢失：学习前序列里的 2 条，学习后**全部仍在**序列内且相对顺序不变（中国银行 → 喂猪）；② 数量：`candidate_items` 直接子 View 数 = **学习前快照 + 1**（干净机预期 2 → 3）；③ 优先级：「喂猫」在 index 0。原「页码与总数各 +1」的**页码部分作废**（控件已删）；「总数 +1」只在本受控向量成立，不得推广——见 §2.4

**前置（不满足则判「用例不适用」，不判 fail）**

1. 测试机没有命中 `klqt` 的快捷短语 / 快速应用（验收文件 AC-3.7 的 Given 已挡）；
2. 设置 → 词库里**没有启用中的五笔码词库含 `code` 以 `klqt` 开头的单字条目**。这条来自 VOCA-122 质检
   的 N-1 caveat（评论 `01a1e5ef`，尚未回写进验收文件 L238）：用户自装的分类 / 细胞词库经
   `CellDictEngine` 的 `code.startsWith(needle)` 分支可在**满码**下引入单字候选（`LookupKind.WUBI` → tier 1），
   排在「喂猫」（tier 2）之前，此时上面那个推论不成立、index 0 会变成单字。捆绑词库是拼音码、不命中。

**候选条上可观察的四个锚点（全部来自 main 的代码，本口径只用这四个）**

| 锚点 | 来源 | 读法 |
|---|---|---|
| 序列顺序 | `candidate_view.xml` 的 `@+id/candidate_items`（`LinearLayout`），`CandidateView.update` 按列表顺序 `addView` | `uiautomator dump` 取直接子 View 序列，是「第几条」的**唯一权威观察量** |
| 数字角标 | `CandidateView.createItemView`：`badge.text = (index + 1).toString()` | 角标「1」⟺ index 0，测试不必数 View |
| 首选高亮 | 同函数 `val highlighted = index == 0`（主题色底色） | 与角标互为交叉校验 |
| 首屏位置 | `update()` 末尾 `scrollView.post { scrollTo(0, 0) }` | 每次刷新回到最左 ⇒ index 0 必首屏可见，「无需二次滑动」是**可证的**，不是假设 |

页码、翻页箭头、来源标签、设置齿轮在 `fa57a07`（VOCA-185）已删除，**任何页码类断言一律不写**。

### 2.1 学习前快照（AC-3.12 的对照基准）

先准备一个把候选条读成序列的小工具（学习前 / 学习后各用一次，同一份脚本）：

```bash
cat > /tmp/cand_seq.py <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
group = [n for n in root.iter() if n.get('resource-id', '').endswith('candidate_items')]
assert group, '没抓到 candidate_items：确认键盘已弹出、输入框里是 klqt，再重跑一次 dump'
items = list(group[0])
print('项数 =', len(items))
for i, it in enumerate(items):
    texts = [c.get('text') for c in it.iter() if c.get('text')]
    print('index %d  候选=%s  角标=%s' % (i, it.get('content-desc'), texts[-1] if texts else '?'))
PY
```

清空用户词库（`adb shell pm clear com.ispeech.ime`，或设置 → 用户词库 → 清空），新输入框输入 `klqt`，
候选条刷出来后立刻抓第一张快照：

```bash
adb shell uiautomator dump /sdcard/cand.xml >/dev/null
adb pull /sdcard/cand.xml ./cand_before.xml >/dev/null
python3 /tmp/cand_seq.py ./cand_before.xml
```

干净机预期：项数 **2**，序列 `[中国银行, 喂猪]`（`wubi86.dict` 里 `klqt` 全仓只有这两条整码词，
62857 / 54507，无单字）。记下这个序列，它是 ①② 的比较基准。

> 部分 ROM 的 IME 窗口不进 `uiautomator dump`：拿不到 `candidate_items` 时不要改判据，
> 用 `adb exec-out screencap -p > cand_after.png` 留图，按「数字角标 1 + 首选高亮 + 从左往右第 1 项」
> 目视判定并在报告里附该图（角标与高亮本身就是给这种情况留的交叉校验）。

### 2.2 学到「喂猫」

按 §1 ① 拼 `klge`→「喂」、1~3 s 内 `qtal`→「猫」，确认 `user_dict.txt` 新增一行四列（TAB 分隔）：
`klqt`、`喂猫`、`1`、`wb`。

### 2.3 学习后快照 + 门禁（**必须与 2.1 在同一次会话内**，中途不重启、不清词库）

新输入框再输入 `klqt`，用同一条命令抓 `cand_after.xml`（`adb pull` 的目标名换成 `cand_after.xml`）。逐条判：

| ID | 门禁 | 判定动作 |
|---|---|---|
| **AC-3.7** | 「喂猫」在候选条序列 **index 0** | 快照第 0 项文本 = 喂猫；数字角标 = 「1」；该项带首选高亮底色；刷新后不滑动即可点选（`scrollTo(0,0)` 保证它在最左）。点选后输入框以「喂猫」结尾、编码缓冲清空 |
| **AC-3.12 ①** | **不丢失**：学习前序列里的 2 条，学习后全部仍在且**相对顺序不变**（中国银行 → 喂猪） | 后快照减掉新词后与 2.1 序列逐项相等 |
| **AC-3.12 ②** | **数量**：`candidate_items` 直接子 View 数 = 学习前快照 **+ 1**（干净机 2 → 3） | 两次快照的「项数」相减为 1 |
| **AC-3.12 ③** | **优先级**：「喂猫」在 index 0 | 与 AC-3.7 同一观察量，交叉校验 |

期望序列：`[喂猫, 中国银行, 喂猪]`。

### 2.4 「+1」的适用边界（不得推广，写进测试用例注释）

- 候选条是**定长 top-N 池**，不是全集：`candidatePoolLimit = settings.candidatePageSize ×
  CANDIDATE_POOL_FACTOR = 9 × 3 = 27`。同码候选 ≥ 27 时学习一个新词只把末位挤出，**项数不变**，
  所以「总数 +1」只在 `klqt`（2 条系统词，远小于 27）这个受控向量上成立。
- 通用形式是集合包含关系：引擎层 JVM 佐证 `CompositeEngine.lookup("klqt", 27)` 学习前 S、学习后 S′，
  断言 `S ⊂ S′` 且 `|S′| = |S| + 1`（`PhraseRankingTest` 已覆盖）；真机门禁是上面 ①②③，其中 **①
  不受 27 上限影响**，任何同码冲突场景都必须成立。
- 不得写成断言的三件事：① 「页码 +1」「第 N 页」（控件已删）；② 「候选条项数 == 用户词库 + 系统词库
  同码条目总数」（被 27 截断且按 display 去重）；③ 「学习词必须首屏可见」**不推广为通用规则**——
  短码（<4 码）有单字候选在前、快捷短语组（tier 0）也在前，那些场景需要滑动属预期，不判 fail。

## 3. AC-3.6：再次拼一次 → count=2

1. 已学「喂猫」（`count=1`）；
2. 再按 §1 ① 的方式拼一次「喂」「猫」。

**期望**：`user_dict.txt` 中该行 `count` 变为 `2`（权重 `120 + 10×2 = 140`），且 `klqt` 候选序列的
**index 0 仍是「喂猫」**（AC-3.6 判定列：该码候选首位 = 引擎返回列表 index 0）。学第二次只让 `count`
递增、不新增条目，所以项数仍是 3、序列与 §2.3 的学习后快照一致。

## 4. AC-3.8：开关关闭不写库

1. 设置 → 键盘 → 关掉「用户词频学习」；
2. 按 §1 ① 方式拼「喂」「猫」。

**期望**：日志出现 `phrase_learn skipped reason=disabled`，`user_dict.txt` 无新增。
打开开关后再拼一次，应能学到（证明只是不落盘，不是判定失效）。

## 5. AC-3.10：设置页治理

1. 设置 → 用户词库：能看到学到的「喂猫」条目；
2. 单条删除它，回到输入框输入 `klqt`，用 §2.1 的命令再抓一张快照：序列中**不再出现「喂猫」**，
   其余同码候选一条不少、「中国银行」回到 **index 0**（AC-3.10 在 v1.1.2 里是序列断言，不写「不再置顶」）；
   日志出现 `phrase_remove code=klqt text=喂猫`（**该条 tag 是 `UserDictStore`**、级别 `I`，发射点
   `KeyboardViewModel.removeUserWord`，不是 `IspeechIme`——§0 的 `-s` 白名单已把它列上；抓不到先按
   §0 的事后补抓式 `adb logcat -d | grep phrase_remove` 复核，再判 fail）；
3. 整体「清空用户词库」后再输入 `klqt`：只剩系统词库候选，项数回到 §2.1 的 2。

## 6. AC-3.11：连续 10 次学习无 ANR

1. 按 §1 ① 方式连续学习 10 对不同的词；
2. 观察：

```bash
adb logcat -d | grep -E "ANR in com.ispeech.ime|Input dispatching timed out"
```

**期望**：无任何命中；键盘无卡顿。

线程口径（只覆盖学习路径，不写成绝对句）：词组学习的**写盘**在 `Dispatchers.IO`
（`learnPhrase` 里 `store.record(...)` 包在 `launch(Dispatchers.IO)` 内）。同一函数取 store 的
`AppContainer.userDict(this)` 仍在调用线程，它在 `user_dict.txt` 缺失时会同步 `file.writeText`
（`UserDictStore.open`），该分支实际不可达——`onCreate` 同步建引擎时已把文件建好——所以 AC-3.11 的
「主线程无 `File.writeText` 调用栈」按**学习写盘**这一句判，冷建文件属既有行为、非本 PR 引入。

## 7. 判定口径与已知限制

| 项 | 说明 |
| --- | --- |
| 中断的可观测性 | `onInterrupt()` 有 5 个调用点，但**只有** `onUpdateSelection:1178` 那条会打日志：面板开合（`:1316`/`:1356`）、`commitDirect:997`（标点/空格/英文）、AI 回复上屏（`:2013`）三处都是「静默清状态」。这是**观测口径的限制**，不是功能缺陷——§1 ③ 组因此只能判「无新增 + 无 commit」，任何「期望出现 interrupt 日志」的判据都只对 ② 组成立 |
| 自因宽限窗 | `SELF_WRITE_GRACE_MILLIS = 300L`（`:128`）：上屏后 300 ms 内的光标回调不判中断、不打日志。它是 `F1` 的保护，同时要求**手动按人眼节奏操作**（② 组的移光标必须 ≥ 500 ms 后做，① 组不得脚本连打），见 §0 |
| 时间窗 W | 默认 5 s（`PhraseLearningTracker.LEARN_WINDOW_MILLIS`），ADR 允许按 UX 真机节奏复核后调整，调整只改这一个参数 |
| 只学五笔 2 字 | 拼音态、3 字及以上词组不参与（二期） |
| 「候选第 1 条」口径 | 验收文件 v1.1.2 §3.5（`agent/ba/voca-122 @ b8e8d40`，裁决评论 `01a1e5ef`）：AC-3.7 门禁 = 候选条**序列 index 0**，锚点为数字角标「1」+ 首选高亮 + 刷新后 `scrollTo(0,0)` 必在最左。「词组组内第 1 与 index 0 同一结果」是该裁决的**推论**（`USER_BOOST = 100_000` 大于词库最大权重 65535，加上满码不出单字候选），不是对门禁的替代。**其中「满码不出单字候选」只在本文的受控向量下成立**（捆绑 `wubi86.dict`、未启用含 `klqt*` 单字的分类词库）：分类 / 细胞词库经 `CellDictEngine` 的 `code.startsWith(needle)` 分支**可以**在满码下补出单字候选（`LookupKind.WUBI` → tier 1 压过 tier 2 的「喂猫」），此时按 §2 前置 2 判**用例不适用**，不判 fail（与 §2 前置 2 的 `CellDictEngine` caveat 同一口径，勿两处各说各话） |
| AC-3.12 | **未作废**，判定仍是「学习前 / 学习后两次快照」的 ①②③（见 §2.3）；作废的只有「页码 +1」半句（`fa57a07` 删了页码控件），「总数 +1」只在 `klqt` 这个受控向量成立、不得推广（§2.4）。本 issue 只学习、不做召回；召回路径 `related()` 在 main 上仍在被调用，与本文各条门禁无关，本文不对其下结论 |
| 档序（词组 vs 单字谁在前） | 裁决**明确不需要改**已合并的分组顺序就能保住 AC-3.7 的强断言，本期也不改。若产品后续要求「学习词永远压过单字排全局首位」，那是对 `fa57a07` 已合并行为的变更，按裁决走 UX + 架构师**新开 issue**，不在本 issue / 本文口径内（验收文件 §13.4 第 6 条） |
| 生僻字档位变化 | 非 BMP 单字（如 `𧝣`）的候选档位由「词组档」修正为「单字档」，候选顺序可能变化，属预期修正 |
| 误学率 | 当前只打 `phrase_learn` / 删除日志，未落盘统计；需要量化时另立 issue |