# VOCA-127 真机验证用例集：条目 3 词组自学习（VOCA-124）

> 关联：VOCA-127（本 issue）、VOCA-124（实现，`origin/feat/voca-124-phrase-learning@8015e80`）、VOCA-65 条目 3
> 被测构建：`app-debug.apk`（`com.ispeech.ime`，versionCode=5 / versionName=1.5），源码 `8015e80`
> 验收依据：`docs/requirements/VOCA-65-acceptance.md` v1.1（AC-3.4/3.7/3.8/3.10/3.11/3.12）
> 手册依据：`docs/voice-phrase-learning-verify.md@8015e80`

## 1. 当前状态：blocked（无真机）

`adb devices -l` 返回空列表；`system_profiler SPUSBDataType` 无 Android 设备；`adb mdns services` 无无线调试设备。
本机仅存在 AVD `ispeech_test`（pixel_5 / android-34 / google_apis / arm64-v8a）。

**按 VOCA-127 明文要求，不接受模拟器结果冒充真机。** 因此本用例集已就绪但**未执行**，
6 项必验项状态见 §6。解除阻塞需要：一台已开启 USB 调试（或无线调试）的 Android 真机，
`adb devices` 出现 `device` 状态。提供方待确认（见 §7）。

## 2. 测试范围

| 在范围 | 不在范围 |
|---|---|
| AC-3.4 真机三组（含反向用例与 F1 专项探测） | AC-3.1~3.3、3.5、3.6、3.9 的 JVM 段（已由 `testDebugUnitTest` 覆盖） |
| AC-3.7 学后候选置顶 | 拼音态学习、3 字及以上词组（二期，ADR 已声明） |
| AC-3.8 开关关闭不写库 | `com.ispeech.agent.AgentDispatcherTest`（main 上既存失败，非本 PR 回归） |
| AC-3.10 设置页词库治理 | VOICE/ASR、快捷短语、键盘样式等条目 1/2/4 功能 |
| AC-3.11 连续学习无 ANR | 性能压测、多机型兼容矩阵（本轮单机） |
| AC-3.12 同码候选共存 | 线上真实用户数据 |

**风险排序**（先打高风险）：① AC-3.4 反向用例（F1 若成立则词组永远学不到，功能整体不可用）→
② AC-3.11 ANR / 主线程写盘（崩溃类）→ ③ AC-3.8 开关失效（隐私/信任）→ ④ AC-3.7/3.12 候选正确性 → ⑤ AC-3.10 治理。

## 3. 环境与数据准备

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# 0) 确认设备（必须是 device，不能是 offline/unauthorized）
adb devices -l

# 1) 从 8015e80 构建（本 issue 实测可通过；沙箱下需把 gradle home / tmpdir / android home 指向可写目录）
./gradlew --offline assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2) 干净起点：清空用户词库（设置 → 用户词库 → 清空），或重装
adb shell pm clear com.ispeech.ime

# 3) 切到五笔态：设置 → 键盘 → 中文输入法 → 五笔
#    注意：换输入态会 phraseTracker.reset()，两组验证之间不要切中/英
```

测试数据（词库无污染，全部自造，不依赖生产数据）：

| 词组 | 首字码 | 次字码 | 合并码 | 说明 |
|---|---|---|---|---|
| 喂猫 | `klge` | `qtal` | `klqt` | 主用词，`klqt` 同码已有「中国银行」「喂猪」→ AC-3.12 |
| 加班 | `lkg` | `gytg` | `lkgytg` | 4 码限制外的对照组，用于确认超长码不误学 |
| 其余 8 对 | 见 §4.1 | | | 每次换不同字，避免 `existing`/`repeat` 跳过 |

## 4. 用例集

### 4.1 AC-3.4 ① 反向用例：连续拼两字不动光标（最高优先级）

- **前置**：干净词库，五笔态，`user_dict.txt` 无 `klqt` 行
- **步骤**：`klge` → 选「喂」上屏 → 1~3 s 内 `qtal` → 选「猫」上屏。重复 10 次，每次换不同的字对
- **期望**：每次出现 `phrase_learn commit code=… text=…`；**全程不出现** `phrase_learn skipped reason=interrupt`；`user_dict.txt` 实际 +1 条 `klqt 喂猫 1 wubi`
- **判据**：10/10 成功且 0 次误中断。**出现任一 `reason=interrupt` 即 F1 成立 → fail**
- **对应验收**：AC-3.4（反向）、AC-3.3

### 4.2 AC-3.4 ② 拼完首字后移动宿主光标

- **步骤**：`klge` → 选「喂」上屏 → 用系统手势/方向键把光标移到别处（如点输入框开头）→ 1~3 s 内 `qtal` → 选「猫」
- **期望**：出现 `phrase_learn skipped reason=interrupt`；`user_dict.txt` **无** `klqt 喂猫` 新增
- **对应验收**：AC-3.4（光标分支，ADR 标注「仅真机可覆盖」）

### 4.3 AC-3.4 ③ 拼首字后切短语面板再切回

- **步骤**：`klge` → 选「喂」→ 打开「快捷短语」面板 → 关闭 → 1~3 s 内 `qtal` → 选「猫」
- **期望**：出现 `skipped reason=interrupt`；词库无新增
- **对应验收**：AC-3.4（面板分支）

### 4.4 F1 专项探测（记录行为，不计 fail）

已知残余风险：`SELF_WRITE_GRACE_MILLIS = 300`（`IspeechImeService.kt:130`）。上屏后 300 ms 内立刻移动光标，
可能落在自因宽限窗内而被当成「自己写的那一笔」，从而**漏判**真实中断。

- **A 组（300 ms 内移动）5 次**：`klge` → 选「喂」→ **立即**（<300 ms）移动光标 → `qtal` → 选「猫」
- **B 组（300 ms 后移动）5 次**：同上，但移动光标放在上屏后 500 ms~1 s
- **记录**：每组 5 次各自「是否出现 `skipped reason=interrupt`」「是否写了库」
- **判定**：A 组漏判属**已知行为**，只记录不判 fail；B 组若漏判则说明 `selectionTolerance` 或自因判定有缺陷 → 升级为缺陷

### 4.5 AC-3.7 学后候选置顶

- **步骤**：已学「喂猫」→ 打开**新的输入框**（重要：学习后当前候选不即时刷新是设计口径）→ 输入 `klqt`
- **期望**：候选第 1 条为「喂猫」，可直接上屏
- **对应验收**：AC-3.7

### 4.6 AC-3.8 开关关闭不写库

- **步骤**：设置 → 键盘 → 关闭「用户词频学习」→ 按 4.1 方式拼「喂」「猫」
- **期望**：出现 `phrase_learn skipped reason=disabled`；`user_dict.txt` 无新增
- **补充**：重新打开开关后再拼一次应能学到（证明只是不落盘，不是判定失效）
- **对应验收**：AC-3.8

### 4.7 AC-3.10 设置页词库治理

- **步骤**：设置 → 用户词库 → 可见「喂猫」→ 单条删除 → 回输入框输入 `klqt`；再整体清空 → 输入 `klqt`
- **期望**：可见 / 可单条删除 / 可整体清空；删除后 `klqt` 候选不再置顶
- **取证**：删除时 logcat 出现 `phrase_remove code=klqt text=喂猫`
- **对应验收**：AC-3.10

### 4.8 AC-3.11 连续 10 次学习无 ANR

- **步骤**：按 4.1 连续学习 10 对不同的词，期间保持 `adb logcat` 抓取
- **期望**：`adb logcat -d | grep -E "ANR in com.ispeech.ime|Input dispatching timed out"` 无命中；键盘无卡顿；写盘在 `Dispatchers.IO`（主线程无 `File.writeText` 调用栈）
- **对应验收**：AC-3.11

### 4.9 AC-3.12 同码候选共存

- **步骤**：已学「喂猫」→ 输入 `klqt`
- **期望**：新词排第 1，原有「中国银行」「喂猪」2 条仍在候选内，页码与总数各 +1
- **对应验收**：AC-3.12

## 5. 取证要求（每项必附）

| 项 | 内容 |
|---|---|
| 机型 / ROM / 构建号 | `adb shell getprop ro.product.model`、`ro.build.display.id`、`ro.build.version.release` |
| APK 来源 | `./gradlew --offline assembleDebug` 产物；sha256 = `92f05fe78c3e98d8ddb52ae877da37f5f738a0873a312665a65de82bea00bb5c` |
| logcat 原文 | `phrase_learn commit` / `phrase_learn skipped reason=…` / `phrase_remove`，说明过滤命令 |
| 截图 | 候选条（学之前 / 学之后 / 删除之后）、设置页用户词库 |
| 词库落盘 | `adb shell run-as com.ispeech.ime cat files/user_dict.txt` 前后对照 |

一键取证脚本：`scripts/qa/voca127-device-capture.sh`（本 issue 交付，需真机执行）。

## 6. 当前判定

| # | 必验项 | 状态 |
|---|---|---|
| 1 | AC-3.4 真机三组（含反向 + F1 探测） | **blocked** — 无真机 |
| 2 | AC-3.7 | **blocked** — 无真机 |
| 3 | AC-3.8 | **blocked** — 无真机 |
| 4 | AC-3.10 | **blocked** — 无真机 |
| 5 | AC-3.11 | **blocked** — 无真机 |
| 6 | AC-3.12 | **blocked** — 无真机 |

**go / no-go：no-go（blocked，非实现失败）。** 真机层 0/6 有证据，不具备发布条件。
**不得**据此判定 VOCA-124 实现有问题——本 issue 没有产生任何 fail 证据。

## 7. 待确认

1. **谁提供真机**：本轮环境无任何 adb 设备。需人类（@qiaozhou）确认可用机型与接入方式（USB / 无线调试），或指定由谁执行本用例集。
2. 是否需要**多机型**覆盖：本用例集按单机设计，若要求跨 ROM 兼容需追加机型矩阵与排期。

## 8. 附：本轮已完成的旁证（不等同真机验证）

- `assembleDebug` 从 `8015e80` 构建**成功**，产物可安装（`aapt2 dump badging` 确认 `com.ispeech.ime` v1.5）。
- `testDebugUnitTest`：**86 用例 / 1 失败**，唯一失败为既存 `AgentDispatcherTest`（`AndroidJUnit4` delegate runner 缺失，main 上即失败，不属本 PR 回归）；词组相关 39 条 **全部通过**（`PhraseLearningTrackerTest` 17、`PhraseCodeDeriverTest` 10、`PhraseDictCompatibilityTest` 4、`PhraseRankingTest` 3、`SelectionSelfCausedTest` 5）。
- 上述仅覆盖 JVM 层，**真机层 AC 一条都未验**。