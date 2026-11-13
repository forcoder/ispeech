# 五笔双字词组自学习 — 真机验证步骤（VOCA-124）

> 关联：VOCA-65 条目 3、ADR `docs/adr/VOCA-65-phrase-learning.md`、验收标准 `docs/requirements/VOCA-65-acceptance.md` v1.1 AC-3.4/3.7/3.8/3.10/3.11/3.12
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

打开任一可输入的 App（短信 / 备忘录），把输入法切到智语，并**切到五笔态**（左下角「中/英」键循环：五笔 → 拼音 → 英文）。

打日志（三组全程保持）：

```bash
adb logcat -c
adb logcat -s IspeechIme:D IspeechIme:I IspeechIme:W | grep -E "phrase_learn|phrase_remove"
```

词库落盘检查（每次验证前后各取一次）：

```bash
adb shell run-as com.ispeech.ime cat files/user_dict.txt
```

## 1. AC-3.4 真机段：光标与面板中断（三组）

ADR §1 的 `F1` 风险点是「本服务自己造成的上屏把上一跳误判成中断」，所以第 ① 组是**反向用例**，必须最先跑。

### ① 连续拼两个单字不移动光标（正向 + 反向合并）

1. 输入 `klge`，候选第 1 条应为「喂」，点选它上屏；
2. 1~3 s 内输入 `qtal`，选「猫」上屏；
3. 重复 10 次（每次换一对不同的字，例如 `lkg`+`gytg`→加班 会被「系统已有」挡掉，换 `wqiy`+`klge` 之类）。

**期望**：

- 日志出现 `phrase_learn commit code=klqt text=喂猫`（每次成功的拼接各一条）；
- **不出现** `phrase_learn skipped reason=interrupt`（这是 `F1` 的判据：出现即说明自因过滤失效，每次上屏都被自己中断）；
- `user_dict.txt` 实际新增对应条目，`count=1`。

判据：连续 10 次无一次误中断。若出现，按 ADR §1 降级方案处理（调大 `selectionTolerance` 或改回只比位置），并在此记录。

### ② 拼完第一字后移动宿主光标

1. 输入 `klge`，选「喂」上屏；
2. 用系统手势或方向键把宿主光标移到别的位置（例如点一下输入框开头的文字）；
3. 1~3 s 内输入 `qtal`，选「猫」上屏。

**期望**：日志出现 `phrase_learn skipped reason=interrupt`，且 `user_dict.txt` **没有** `klqt 喂猫` 新增。

### ③ 拼第一字后切短语面板再切回

1. 输入 `klge`，选「喂」上屏；
2. 点工具栏打开「快捷短语」面板，再关掉；
3. 1~3 s 内输入 `qtal`，选「猫」上屏。

**期望**：日志出现 `phrase_learn skipped reason=interrupt`，词库无新增。

**取证要求**：机型 / ROM 版本 / 三组的 logcat 关键行原文随报告留档；不得只写「已验」。

## 2. AC-3.7 / 3.12：学完之后候选里有没有 + 同码共存

1. 按 §1 ① 的方式学到「喂猫」（`klqt`）；
2. 打开一个**新的输入框**，输入 `klqt`。

**期望**：

- 候选第 1 条是「喂猫」，可直接上屏（AC-3.7）；
- 同码原有的「中国银行」「喂猪」仍在候选内，页码 / 总数相应 +1，与「喂猫」同屏或翻页可见（AC-3.12）。

## 3. AC-3.6：再次拼一次 → count=2

1. 已学「喂猫」（`count=1`）；
2. 再按 §1 ① 的方式拼一次「喂」「猫」。

**期望**：`user_dict.txt` 中该行 `count` 变为 `2`（权重 `120 + 10×2 = 140`），`klqt` 下「喂猫」仍是第 1 条。

## 4. AC-3.8：开关关闭不写库

1. 设置 → 键盘 → 关掉「用户词频学习」；
2. 按 §1 ① 方式拼「喂」「猫」。

**期望**：日志出现 `phrase_learn skipped reason=disabled`，`user_dict.txt` 无新增。
打开开关后再拼一次，应能学到（证明只是不落盘，不是判定失效）。

## 5. AC-3.10：设置页治理

1. 设置 → 用户词库：能看到学到的「喂猫」条目；
2. 单条删除它，回到输入框输入 `klqt`：候选不再置顶（该词消失）；
3. 整体「清空用户词库」后再输入 `klqt`：只剩系统词库候选。

## 6. AC-3.11：连续 10 次学习无 ANR

1. 按 §1 ① 方式连续学习 10 对不同的词；
2. 观察：

```bash
adb logcat -d | grep -E "ANR in com.ispeech.ime|Input dispatching timed out"
```

**期望**：无任何命中；键盘无卡顿。（写盘在 `Dispatchers.IO`，主线程不接触 `File.writeText`。）

## 7. 判定口径与已知限制

| 项 | 说明 |
| --- | --- |
| 时间窗 W | 默认 5 s（`PhraseLearningTracker.LEARN_WINDOW_MILLIS`），ADR 允许按 UX 真机节奏复核后调整，调整只改这一个参数 |
| 只学五笔 2 字 | 拼音态、3 字及以上词组不参与（二期） |
| 生僻字档位变化 | 非 BMP 单字（如 `𧝣`）的候选档位由「词组档」修正为「单字档」，候选顺序可能变化，属预期修正 |
| 误学率 | 当前只打 `phrase_learn` / 删除日志，未落盘统计；需要量化时另立 issue |