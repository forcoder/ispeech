# VOCA-127 真机验证用例集：条目 3 词组自学习（VOCA-124）

> 关联：VOCA-127（本 issue）、VOCA-124（实现）、VOCA-65 条目 3
> 被测代码：PR #4 head **`8ab0a88`**（`OPEN` / `MERGEABLE` / `CLEAN`，base `fa57a07`；VOCA-124 已 `done`，**但尚未合入 `main`**）。`8ab0a88` 相对 `7ed1230` 只动两个文档，`app/`、`tools/` 两树字节相同（PM 与质检专员 11-17 双向复核）——判定 SHA 一律用 `8ab0a88`，`48f91ea` / `8015e80` / `7ed1230` 全部作废。
> 验收依据：`docs/requirements/VOCA-65-acceptance.md` **v1.1.2** @ `b8e8d40`（分支 `agent/ba/voca-122`，AC-3.7 / AC-3.10 / AC-3.12 已按 VOCA-185 候选条形态回写；**尚未落 `main`**）
> 手册依据：`docs/voice-phrase-learning-verify.md` @ **`8ab0a88`**（§0 新增「日志发射点清单」6 行、§1 ② 加 ≥500 ms 宽限窗等待、③ 降为弱判据；**旧版 `7ed1230` 的 §1 两条期望在代码里没有发射点，照它跑会得到假 fail**）
> 本文件口径 = 上述三份的并集；与 v1.1 旧文本冲突时，**以 v1.1.2 为准**，旧措辞（「候选第 1 条」「页码 +1」「不再置顶」）一律不再作为门禁。

## 1. 当前状态：blocked（等上游，非等本单）

本单不再自己推进任何验证，等的是三段上游链路，全部可自证：

| # | 等待项 |  owner | 到期 | 自证信号（成立即推进本单） |
|---|---|---|---|---|
| ① | VOCA-124 解冲突 + rebase 到 VOCA-185 形态并收口 | 前端工程师 | ~~2026-11-18 12:00 CST~~ **11-17 已闭环** | `gh pr view 4` = `OPEN`/`MERGEABLE`/`CLEAN`，head `8ab0a88`；第四轮质检 **PASS-WITH-CAVEAT**，VOCA-124 状态 `done`（11-17 04:39Z）。C-1 已并入本文件 §4.1 |
| ② | PR #4 合入 `main` + 重新出官方包 | 项目经理出合并清单 → **人类批准**（项目规程第五、六条） | 合并窗口 11-18 12:00 CST | **11-18 已把「PR #4（head `8ab0a88`）是否进 11-19 窗口」这个 ask 补登到 VOCA-203 与 VOCA-419（带真 `mention://member` 链接）**——此前该 ask 无任何载体，是本单被巡检判为「假阻塞」的那一段。`git merge-base --is-ancestor 8ab0a88 origin/main` 返回 `0`（11-17 12:40 CST 实测 **exit=1**，`origin/main` 仍 `fa57a07`、`versionName="1.5"`） |
| ③ | 包自证 + 真机接入 | 人类（qiaozhou）提供设备 | 2026-11-19 | 装机包 `versionName` ≠ `1.5` **且** `adb shell dumpsys package com.ispeech.ime` 可见；APK 内 `strings`/`dexdump` 能捞到日志 tag `phrase_learn`；`adb devices -l` 出现目标真机 serial `23F0224224000950` 为 `device` |

②与③**必须同时成立**：只有②而拿旧包装机，验的是空对象；只有③而包里没有条目 3 的代码，同样验空。①已闭环，不再等。

本单 2026-11-17 12:40 CST（wakeup 定时复核）实测（Mac Studio，adb 1.0.41 / 37.0.1）：

```text
$ adb devices -l
List of devices attached
emulator-5554   device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
（★ 目标真机 serial 23F0224224000950 不在列表；这条 emulator 是 12:40 前后新出现的，
   上一次本单实测是空列表 —— 它是模拟器，按本 issue 明文不能冒充真机，也不满足③）

$ timeout 5 adb mdns services
List of discovered mdns services
（空 —— 无无线调试设备）

$ system_profiler SPUSBDataType | grep -iE "android|23F02|samsung|xiaomi|huawei|oneplus|oppo|vivo"
（空 —— USB 上无 Android 设备）
```

本地存在 AVD `ispeech_test`、`qa166_test`，**按本 issue 明文要求不用模拟器冒充真机**。
`$ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` 可用，构建链路不是阻塞项。

## 2. 测试范围

| 在范围 | 不在范围 |
|---|---|
| AC-3.4 真机三组（含反向用例与 F1 专项探测） | AC-3.1~3.3、3.5、3.6、3.9 的 JVM 段（`testDebugUnitTest` 已覆盖） |
| AC-3.7 index 0 门禁 | 拼音态学习、3 字及以上词组（二期，ADR 已声明） |
| AC-3.8 开关关闭不写库 | `com.ispeech.agent.AgentDispatcherTest`（main 上既存失败，非本 PR 回归） |
| AC-3.10 设置页治理（序列断言） | VOICE/ASR、快捷短语、键盘样式等条目 1/2/4 功能 |
| AC-3.11 连续学习无 ANR | 性能压测、多机型兼容矩阵（本轮单机） |
| AC-3.12 同码候选共存（前/后两次快照） | 线上真实用户数据 |
| — | 召回路径 `related()`、候选条分组顺序（裁决明确不改，需变更另开 issue） |

**风险排序**：① AC-3.4 反向用例（F1 成立则功能整体不可用）→ ② AC-3.11 ANR / 主线程写盘 → ③ AC-3.8 开关失效（隐私）→ ④ AC-3.7 / 3.12 候选正确性 → ⑤ AC-3.10 治理。

## 3. 环境与数据准备

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# 0) 设备（必须 device，不能 offline/unauthorized），期望 serial 23F0224224000950
adb devices -l

# 1) 装「合并后重新出的官方包」，过 §1 ③ 的两条自证再开始；不接受分支包、不接受现有 v1.6 包
adb install -r <官方包路径>

# 2) 干净起点：adb shell pm clear com.ispeech.ime（或 设置 → 用户词库 → 清空）

# 3) 切五笔态：设置 → 键盘与输入 → 中文输入法 → 五笔
#    ★ 11-18 实测：该入口在 8ab0a88 / fa57a07 上都必崩（§6.3 P1），真机同样不可用；
#      模拟器改走持久化等价路径（§6.1 末段）：run-as 写
#      files/datastore/ispeech_settings.preferences_pb 内 input_method_mode="WUBI"
#      （androidx datastore-preferences 1.0.0：PreferenceMap.entries=1 / key=1 / value=2 /
#        Value.string=field 5 -> tag 0x2a；写成 0x12 会被忽略并退回拼音）
#    换输入态会 phraseTracker.reset()，两组验证之间不要切中/英
```

测试数据（自造，不依赖生产数据）：

| 词组 | 首字码 | 次字码 | 合并码 | 说明 |
|---|---|---|---|---|
| 喂猫 | `klge` | `qtal` | `klqt` | 主用向量。`klqt` 全库仅 中国银行(62857) / 喂猪(54507) 两条整码词、无单字 → AC-3.12 受控向量 |
| 加班 | `lkg` | `gytg` | `lkgytg` | 系统库已有 `lkgy` → AC-3.5 反例，只作对照不作门禁 |
| 其余字对 | 每次换不同字对 | | | 避免 `existing` / `repeat` 跳过导致样本不足 |

## 4. 用例集（门禁逐字对齐 v1.1.2）

**通用前置（不满足判「用例不适用」，不判 fail）**
1. 测试机无命中 `klqt` 的快捷短语 / 快速应用；
2. 设置里**没有启用中的五笔码词库含 `code` 以 `klqt` 开头的单字条目**（VOCA-122 质检 N-1 caveat，验收文件 L238 Given 尚未回写）——分类 / 细胞词库经 `CellDictEngine` 的 `startsWith` 分支可在满码引入单字候选（tier 1）压过「喂猫」（tier 2），此时 index 0 会变单字。命中即记「不适用 + 原因」，不判实现失败。

**可观察锚点（只用这四个，来自 main 的代码）**：`candidate_items` 直接子 View 序列（唯一权威「第几条」观察量）、数字角标 `(index+1)`、首选高亮 `index == 0`、刷新末尾 `scrollTo(0,0)`。
**页码、翻页箭头、来源标签、齿轮已在 `fa57a07` 删除 —— 任何页码类断言一律不写。**

### 4.1 AC-3.4 ① 反向用例：连续拼两字不动光标（最高优先级）

- **步骤**：`klge` → 选「喂」上屏 → 1~3 s 内 `qtal` → 选「猫」上屏。重复 10 次，每次换不同字对
- **期望**：每次 `phrase_learn commit code=… text=…`；全程**不出现** `skipped reason=interrupt`；词库 +1 条四列（TAB 分隔）`klqt`、`喂猫`、`1`、`wb`
- **判据**：10/10 且 0 次误中断。出现任一**光标路径**的 `reason=interrupt` 且能排除「用户确实移动了光标」→ **F1 成立，记 fail**
- **skip reason 分类（N-3，不得混进 F1）**：`window` / `existing` / `repeat` / `clock_backwards` / `undecidable` 属**字对选错**，换对字对重跑并把 reason 记进报告；换对后仍凑不满 10 条，才计入 F1
- **`reason=interrupt` 有两个发射点，必须分开计数**（VOCA-124 质检 C-1，`8ab0a88` 代码实测）：`:1178`（`onUpdateSelection` 判出他因光标移动，日志**不带 `count=`**）才是 F1 要看的那个；`:1126`（上屏来源非单字 / 码点长度 ≠ 1，日志**带 `count=`**）说明这一轮里混进了一次词组或联想上屏，**重跑该次拼接，不计 F1**。判别式：`grep 'reason=interrupt' | grep -vc 'count='`

### 4.2 AC-3.4 ② 拼完首字后移动宿主光标

- `klge` → 「喂」→ **等 ≥ 500 ms**（越过 §4.1 的 300 ms 自因宽限窗）→ 系统手势/方向键把光标移开 → 1~3 s 内 `qtal` → 「猫」
- **期望**：`phrase_learn skipped reason=interrupt`（**不带 `count=`** 的那条，即 `:1178` 光标路径）；词库无 `klqt 喂猫` 新增
- **归因**：若打出 `commit code=klqt` → 是移光标落在宽限窗里，**不是功能 fail**，加等到 ≥ 500 ms 重跑；只有加了等待仍学出词才计入 fail

### 4.3 AC-3.4 ③ 拼首字后切短语面板再切回

- 同 4.2，中间开「快捷短语」面板再关
- **期望（弱判据，`8ab0a88` 更正）**：只断两条 —— ① `user_dict.txt` 没有 `klqt 喂猫` 新增；② 日志里**没有** `phrase_learn commit code=klqt`（正控：没有 commit 恰恰证明中断生效）。
- **不要求任何 `skipped` 日志**：面板开合（`:1316`/`:1356`）只清状态、没有日志发射点，照旧版手册判「出现 `reason=interrupt`」是**必然 0 命中的假 fail**。
- **②③ 之间清一次词库**（`pm clear com.ispeech.ime` 或 设置→用户词库→清空）：② 的最后一步「猫」会留在状态机里当新的第一跳，与 ③ 的「喂」凑成 `qtkl 猫喂` 并打出 commit（预期行为，但污染计数）。本组判定只看 `klqt 喂猫` 与 `commit code=klqt`，不受该噪声影响。

### 4.4 F1 专项探测（记录行为，不计 fail）

残余风险 `SELF_WRITE_GRACE_MILLIS = 300`：上屏后 300 ms 内移动光标可能落在自因宽限窗内而**漏判**中断。

- **A 组**（上屏后 <300 ms 移动光标）5 次；**B 组**（500 ms~1 s 后移动）5 次
- **记录**：每组 5 次各自是否出现 `reason=interrupt`（**只数不带 `count=` 的 `:1178`**，同 §4.1）、是否写库
- **判定**：A 组漏判属已知行为，只记录；**B 组漏判 → 升级为缺陷**（`selectionTolerance` 或自因判定有缺陷）

### 4.5 AC-3.7 学后 index 0

- **步骤**：已学「喂猫」→ **新输入框**（五笔态）输入整码 `klqt`（学习后当前候选不即时刷新是设计口径）
- **期望（v1.1.2 判定列）**：候选条**序列 index 0** = 「喂猫」，锚点 = 数字角标「1」+ 首选高亮底色 + 刷新后必在首屏最左（无需滑动即可一次点选）；点选后输入框以「喂猫」结尾、编码缓冲清空
- **注意**：「词组组内第 1」与「全局 index 0」同一结果是裁决的**推论**，不是可替代的弱门禁；门禁仍按 index 0 判
- **取证**：`scripts/qa/voca127-device-capture.sh cand before` + `cand after` + `gate`

### 4.6 AC-3.8 开关关闭不写库

- 设置 → 键盘 → 关「用户词频学习」→ 按 4.1 拼「喂」「猫」
- **期望**：`phrase_learn skipped reason=disabled`；词库无新增
- **补充**：重开开关后再拼一次应学到（证明只是不落盘，不是判定失效）

### 4.7 AC-3.10 设置页治理（序列断言，不写「不再置顶」）

- **步骤**：设置 → 用户词库 → 可见「喂猫」→ 单条删除 → 输入 `klqt` 抓快照 → 再整体清空 → 输入 `klqt` 抓快照
- **期望**：可见 / 可单条删除 / 可整体清空；删除后序列中**不再出现「喂猫」**，其余同码候选一条不少，**「中国银行」回到 index 0**；清空后项数回到 §4.9 学习前快照的 2
- **取证**：日志 `phrase_remove code=klqt text=喂猫`，**tag 是 `UserDictStore`、级别 `I`**（发射点 `KeyboardViewModel.removeUserWord`，不是 `IspeechIme`）。§0 白名单已列 `UserDictStore:I`；抓不到先用事后补抓式 `adb logcat -d | grep -E "phrase_learn|phrase_remove"` 复核，**再**判 fail（漏列白名单造成的 0 命中是文档错，不是功能错——即质检打回项 R-1）

### 4.8 AC-3.11 连续 10 次学习无 ANR

- 按 4.1 连续学 10 对不同词，全程保持抓取
- **期望**：`adb logcat -d | grep -E "ANR in com.ispeech.ime|Input dispatching timed out"` 命中 0；键盘无卡顿
- **线程口径（不写绝对句）**：学习**写盘**在 `Dispatchers.IO`（`learnPhrase` 的 `store.record(...)`）。同函数取 `AppContainer.userDict(this)` 仍在调用线程，`user_dict.txt` 缺失时会同步 `writeText`，但该冷路径实际不可达（`onCreate` 已建好文件）——AC-3.11 按「学习写盘不在主线程」判，冷建文件属既有行为、非本 PR 引入

### 4.9 AC-3.12 同码候选共存（学习前 / 后两次快照，同一次会话内、不重启不清词库）

- **学习前**：干净词库 → 新输入框 `klqt` → `cand before`。干净机预期 项数 **2**、序列 `[中国银行, 喂猪]`（相对顺序即比较基准）
- **学到「喂猫」**（§4.1）后 → 新输入框 `klqt` → `cand after`
- **三条门禁**：
  - ① **不丢失**：学习前 2 条学习后全部仍在且相对顺序不变（后快照减掉新词后与前置序列逐项相等）
  - ② **数量**：`candidate_items` 直接子 View 数 = 学习前 + 1（干净机 2 → 3）
  - ③ **优先级**：「喂猫」在 index 0（与 AC-3.7 同一观察量交叉校验）
- **不得写成断言的三件事**：① 「页码 +1」「第 N 页」（控件已删）；② 「项数 == 用户词库 + 系统词库同码条目总数」（被 `candidatePoolLimit = 9 × 3 = 27` 截断且按 display 去重）；③ 「学习词必须首屏可见」**不推广**——短码（<4）有单字在前、快捷短语组 tier 0 也在前，那些场景需滑动属预期
- **适用边界（必须留在用例注释里）**：② 只在 `klqt` 这个受控向量成立；通用形式是集合包含 `S ⊂ S′` 且 `|S′| = |S| + 1`，引擎层由 `PhraseRankingTest` 佐证；**① 不受 27 上限影响**，任何同码冲突场景都必须成立
- 部分 ROM 的 IME 窗口不进 `uiautomator dump`：拿不到 `candidate_items` 时**不改判据**，改用 `adb exec-out screencap -p > cand_after.png`，按「角标 1 + 首选高亮 + 从左第 1 项」目视判定并附图

## 5. 取证要求（每项必附）

| 项 | 内容 |
|---|---|
| 机型 / ROM / 构建号 | `adb shell getprop ro.product.model` / `ro.build.display.id` / `ro.build.version.release` |
| **包准入自证** | `adb shell dumpsys package com.ispeech.ime` 的 `versionName`（必须 ≠ `1.5`）+ 从该 APK `strings`/`dexdump` 捞到 tag `phrase_learn`，两条并列才算「验的是有被测代码的包」 |
| logcat 原文 | 前台：`adb logcat -s IspeechIme:D IspeechIme:I IspeechIme:W UserDictStore:I \| grep -E "phrase_learn\|phrase_remove"`；事后补抓：`adb logcat -d \| grep -E "phrase_learn\|phrase_remove"`（两个 tag 都必须列，见 §4.7） |
| 候选条快照 | `cand before` / `cand after` 的 `uiautomator` XML + `cand_seq` 解析输出（AC-3.7 / 3.10 / 3.12 共用），拿不到 XML 时附 `screencap` 图 |
| 截图 | 学之前 / 学之后 / 删除之后 候选条、设置页用户词库 |
| 词库落盘 | `adb shell run-as com.ispeech.ime cat files/user_dict.txt` 前后对照 |
| 复现步骤 | 每条 fail 附机型 + 命令原文 + logcat 原文 + 操作序列 |

一键取证：`scripts/qa/voca127-device-capture.sh`（`pre` / `cand` / `gate` / `dict` / `anr` / `post`），产物落 `./qa-evidence/voca127-<时间戳>/`。

## 6. 当前判定（11-18 换口径：先拆「可模拟器化 / 必须真机」，再分别给读数）

### 6.1 拆分

| # | 必验项 | 可模拟器化？ | 依据 |
|---|---|---|---|
| 1 | AC-3.4 ①②③ + F1 探测 | **可** | 全链路都是「注入码 → 上屏 → 读 logcat / user_dict」，模拟器与真机等价；② 的光标移动用 `KEYCODE_MOVE_HOME`，③ 的面板开合用工具条第 4 键 + `ESCAPE`，都可在 emulator 触发 |
| 2 | AC-3.7 index 0 | **可（观察量降级）** | 本机 `uiautomator dump` 不含 IME 窗口 → 按 §4.9 末条走 `screencap` 目视 + 点选后宿主文本双证 |
| 3 | AC-3.8 开关关闭不写库 | **半** | 学习逻辑对 `learning` 值的响应可验；**开关本身的 UI 路径不可达**（见 6.3 P1） |
| 4 | AC-3.10 设置页治理 | **不可（被 P1 挡住）** | 「设置 → 键盘与输入」打开即崩，用户词库入口不可达；崩溃与设备无关，真机同样进不去 |
| 5 | AC-3.11 连续学习无 ANR | **可（弱证）** | ANR / `Input dispatching timed out` 日志判据可跑；但模拟器时序余量比真机宽，只算弱证 |
| 6 | AC-3.12 前/后快照 | **可（观察量降级）** | 同 #2，靠截图判序列 |

**进入五笔态的方式（重要口径）**：两条 UI 路径都不可用（#4 的崩溃 + 面板选择行对注入点击无响应），本单改用 `run-as` 往 `files/datastore/ispeech_settings.preferences_pb` 写产品自己那个 key（`input_method_mode = "WUBI"`，androidx datastore-preferences 1.0.0 的 `Value.string` 是 **field 5**，写成 field 2 会被静默忽略退回拼音）。**这只证明学习逻辑读的是持久化值，不证明「用户能在 UI 上切到五笔」**——后者正是 6.3 的 P1 影响面。

### 6.2 模拟器读数（11-18，emulator-5554，被测包 = `8ab0a88` 自构建 debug APK）

| # | 必验项 | 读数 | 关键证据 |
|---|---|---|---|
| 1 | AC-3.4 ① | **PASS** | 10 组不同字对连拼：**10/10 `phrase_learn commit`**，期望合并码 10/10 命中；`reason=interrupt`（不带 `count=`）**0 次**；`user_dict.txt` 10 行四列 `code\t词\t1\twb` |
| 1 | AC-3.4 ② | **PASS** | 首字上屏 +0.8 s 后 `MOVE_HOME` → `skipped reason=interrupt`（无 `count=`）、无 commit、词库无该条 |
| 1 | AC-3.4 ③ | **PASS** | 首字上屏后开「快捷短语 · csBaby」面板再 `ESCAPE` 关 → 该合并码**无 commit、无词库新增**（按 §4.3 弱判据两条） |
| 1 | F1 探测 | **无漏判** | A 组（<300 ms 移动光标）5/5 出中断；B 组（500 ms~1 s）5/5 出中断且 0 commit → **不升级为缺陷** |
| 2 | AC-3.7 | **PASS** | 学到「喂猫」后新输入框打 `klqt`，按数字键 1（index 0）上屏 **喂猫**、缓冲清空 |
| 3 | AC-3.8 | **PASS（逻辑段）** | `learning=false` → `skipped reason=disabled`、词库零新增；改回 `true` 后同一序列 `commit code=klqt text=喂猫` |
| 4 | AC-3.10 | **未执行** | 前置入口崩溃（6.3），非本单可绕过 |
| 5 | AC-3.11 | **PASS（弱证）** | 10 次连续学习全程 `ANR in com.ispeech.ime` / `Input dispatching timed out` **0 命中** |
| 6 | AC-3.12 | **PASS** | 干净词库前快照 **2 条**：`中国银行①, 喂猪②`；学「喂猫」后 **3 条**：`喂猫①, 中国银行②, 喂猪③` → ①不丢失（相对顺序不变）②数量 +1 ③index 0 = 喂猫，三条门禁同一次会话内成立 |

**模拟器层：6 项全部执行，5 项通过读数，1 项（AC-3.10）被 P1 挡住 → 5/6。**

### 6.3 缺陷单（P1，非 PR #4 引入）

- **标题**：设置 → 键盘与输入 打开即 NPE，`com.ispeech.ime` 整个进程被杀（含输入法）
- **严重级**：P1（核心入口不可用；同时挡住 AC-3.10 与「用户切五笔」）
- **环境**：emulator-5554（Android 14 / sdk_gphone64_arm64），`8ab0a88` 与 `fa57a07` 两个自构建 debug APK **都复现**
- **复现**：`adb shell am start -n com.ispeech.ime/com.ispeech.settings.MainActivity` → 点「键盘与输入」(308,964) → 进程 PID 5587 → 5652（被杀重启），弹「Application Error: com.ispeech.ime」
- **实际**：`java.lang.NullPointerException: Attempt to invoke interface method 'void kotlinx.coroutines.flow.MutableStateFlow.setValue(java.lang.Object)' on a null object reference` at `KeyboardViewModel.kt:36`（`fa57a07` 同一形状，行号 35）
- **根因**：`init { }`（`:30`）里 `viewModelScope.launch` 的第二个收集器在 `:36` 写 `_chineseInputMethod.value`，而该属性声明在 **`:103`**——`viewModelScope` 用 `Dispatchers.Main.immediate`，收集器在构造期就能拿到首个发射，此时属性尚未初始化。`fa57a07` 是 `_inputMethodMode` 声明在 `:101`、收集器在 `:34`，同一缺陷
- **期望**：设置页正常打开；本条与 PR #4 无关，属 main 既存问题 → 建议新开 issue 交前端，不挂在本单门禁里
- **影响面**：① AC-3.10 无法验收；② 真机上用户也无法把「中文输入法」切成五笔，条目 3 的入口功能对普通用户不可达

### 6.4 真机层判定与 go / no-go

| 层 | 读数 | 说明 |
|---|---|---|
| 模拟器（emulator-5554，`8ab0a88`） | **5/6** | 上表 6.2，可复现命令与截图随本单评论交付 |
| 真机（serial `23F0224224000950`） | **0/6** | 设备仍不在线；按 issue 明文与项目规程，模拟器读数不替代真机验收 |

**go / no-go：no-go。** 三条硬理由：① 真机 0/6，核心链路无真机回归记录；② AC-3.10 未执行且被 P1 挡住；③ PR #4 尚未合入 `main`（§1 ②），装到真机上的包里没有条目 3。
**不得**据 6.2 判定 VOCA-124 实现有问题——模拟器层 5 项全绿，未产生任何功能 fail 证据；6.3 的 P1 属 main 既存缺陷。


## 7. 待确认 / 已收敛

1. **真机**：期望 serial `23F0224224000950`（人类在 wakeup 指令里给出）。本主机 11-17 12:40 实测只有 `emulator-5554`，目标 serial 不在 `adb devices -l`、无 mdns、USB 无 Android 设备；需人类接入（USB 或无线调试）或在有该 serial 的机器上执行本用例集。
2. **出包**：PR #4 head `8ab0a88` 合入 `main` 后需重出官方包；现有 v1.6 内测包与 `origin/main`（`fa57a07`，`versionName="1.5"`）都不含条目 3，不可用于本单。`scripts/qa/voca127-device-capture.sh pre` 会在开工前当场拒绝这两类包。
3. **N-1 回写**：验收文件 v1.1.2 的 AC-3.7 Given（L238）仍缺「无命中 `klqt` 的单字词条」这半句，`agent/ba/voca-122` 上未补。本单按 §4 通用前置第 2 条自挡（判「不适用」），不卡 11-19，归属仍是 BA。
4. **C-1（11-17 第四轮质检的非阻塞项）已在本文件收敛**：`reason=interrupt` 的两个发射点（`:1178` 无 `count=` / `:1126` 带 `count=`）已在 §4.1、§4.2、§4.4 与取证脚本里分计。**同一处盲区在验收文件 §3.5 AC-3.4 真机行原文里**（写的是「无 `skipped{reason=interrupt}`」，未区分两类），归 BA 下次回写，本单不改需求口径。
5. **多机型**：本用例集按单机设计，跨 ROM 兼容矩阵需另立 issue 与排期。

## 8. 旁证（不等同真机验证）

- 本单 11-17 12:40 CST 实测：`gh pr view 4` → `state=OPEN` / `mergeable=MERGEABLE` / `mergeStateStatus=CLEAN` / `headRefOid=8ab0a88210546285…` / `mergedAt=null`；`git merge-base --is-ancestor 8ab0a88 origin/main` → **exit=1**（`48f91ea` / `8015e80` / `7ed1230` 同样 exit=1），即**仍未合入**；`origin/main` 仍 `fa57a07`，`app/build.gradle.kts` 仍 `versionName = "1.5"`，条目 3 一行未入库。
- C-1 的两个发射点本单自行核对（未照抄质检结论）：`git grep -n "phrase_learn\|phrase_remove" 8ab0a88 -- app/src/main` 得 `IspeechImeService.kt:1124/1126/1140/1143/1178` + `KeyboardViewModel.kt:137`，其中 `:1126` 的文本是 `phrase_learn skipped reason=${…} count=$count`、`:1178` 是 `phrase_learn skipped reason=interrupt`（无 `count=`）——`count=` 判别式成立。
- JVM 层 `84 tests / 1 failed`（唯一红项 `AgentDispatcherTest`，main 上同样红）、`check_crosscut` 161/0/0：**出自前端交付说明与前端质检的独立复现，本单本轮未重跑**，不计入本单证据。合入后本单会在官方包上重跑一次相关 JVM/静态脚本作为包一致性佐证。
- 本单本轮只更新用例集与取证脚本（测试资产），未改任何产品代码、未改验收口径、未动 issue 状态之外的平台配置。
