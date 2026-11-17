# VOCA-127 模拟器执行件（emulator-5554）

配套用例集：`docs/qa/VOCA-127-phrase-learning-device-test-plan.md` §6。
**定位**：把 6 项里「可模拟器化」的那部分当场跑出读数；**不替代真机验收**（issue 明文 + 项目规程第四条）。

## 前置

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
$ANDROID_HOME/emulator/emulator -avd ispeech_test -no-snapshot-save &   # 或已在跑的 emulator-5554
adb -s emulator-5554 install -r -g <被测 APK>            # 8ab0a88 或 fa57a07 自构建 debug 包
adb -s emulator-5554 shell ime set com.ispeech.ime/com.ispeech.input.IspeechImeService
```

宿主输入框用系统「新建联系人」表单（`am start -a android.intent.action.INSERT`），脚本靠
`uiautomator dump` 定位 EditText，不打盲坐标。

## 文件

| 文件 | 作用 |
|---|---|
| `emu.py` / `kb.py` / `run.py` | adb 封装、截图、宿主文本读取、五笔编码注入（`input keyevent`） |
| `cases.py` | `reset()` = `pm clear` → 注入 `input_method_mode=WUBI` + `learning` → 重新选中输入法；词库回到只有表头 |
| `r1b.py` | AC-3.4 ①：10 组不同字对连拼，逐组隔离 logcat |
| `r2.py` | AC-3.4 ②（`MOVE_HOME` 移光标）/ ③（开合快捷短语面板）/ F1 A、B 各 5 次 |
| `r3.py` | AC-3.12 学习前后快照 + AC-3.7 index 0 点选 |
| `r4.py` | AC-3.8 `learning=false` 不写库 + 重开可学 |
| `P1_crash_8ab0a88.log` | 设置 → 键盘与输入 崩溃的 crash buffer 原文（§6.3） |

## 运行

```bash
cd scripts/qa/voca127-emulator
python3 -u r1b.py     # 期望：commits=10 / interrupt(no count)=0
python3 -u r2.py      # 期望：4.2/4.3 commit=False，F1 B 组 5/5 出中断
python3 -u r3.py      # 期望：前快照 2 条、后快照 3 条、pick(0) 上屏「喂猫」
python3 -u r4.py      # 期望：disabled 跳过 + 重开后 commit
```

## 已知的坑（照抄前先看）

1. **五笔态进不去**：设置页有 P1 崩溃（用例集 §6.3），键盘面板的选择行对注入点击无响应，
   所以 `cases.py` 直接写产品自己那个 DataStore key。androidx datastore-preferences 1.0.0 的
   `Value.string` 是 **field 5**（tag `0x2a`）；写成 `0x12` 会被静默忽略、退回拼音——这条曾让
   本单误判「模拟器无法进入五笔」。
2. **`uiautomator dump` 看不到候选条**（IME 窗口不进 dump）→ AC-3.7 / 3.12 用 `screencap` 目视，
   截图随评论交付。
3. **`pm clear` 会杀掉宿主 Activity 并把输入法选择重置** → `reset()` 之后必须 `fresh_field()` +
   `ensure_editor()`，不能沿用旧界面。
4. **两轮之间的清场 DEL 本身就是一次光标移动**，会把 `reason=interrupt` 打出来污染 F1 判读；
   `r1b.py` 的口径是「清场只做一次，之后不再动宿主」。
5. 上一跳的词会留在状态机里当新的第一跳（用例集 §4.3 已声明），跨轮会多出 `X喂` 这类词，属预期噪声。
