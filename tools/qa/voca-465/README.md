# VOCA-465 · 双击空格句号在硬件 / 注入键路径的修复取证

对应 PR #18 新 head（base main `fa57a07`，上一 head `c524351`）。**被测代码 = `78a7981` `fix(ime): 硬件 / 注入键的空格进 handleSpace…`**，下面所有设备取证与门禁输出都在该 head 的工作树上跑出来的，`app-debug.apk` 同源。
判据原文见 issue VOCA-465；父单 VOCA-37，裁决依据 VOCA-47 报告 §3.3 + 裁决 §9.3。

## 环境

| 项 | 值 |
|---|---|
| 设备 | 模拟器 `emulator-5554`（AVD `ispeech_test`，API 34 google_apis arm64-v8a，`hw.lcd.density=440`） |
| 被测包 | 本 head 的 `:app:assembleDebug` → `app-debug.apk`，`adb install -r -d -g` |
| IME | `com.ispeech.ime/com.ispeech.input.IspeechImeService`（`ime set` + `host_ready` 自检 `type=ime frame` top=1352 h=988） |
| 宿主 | Settings 搜索框 `open_search_view_edit_text`，文本经 `uiautomator dump` 的 `focused="true"` 节点读取 |
| 开关置位 | 走输入法内置配置面板（齿轮 → 滚到底 → 逐行试点，以 DataStore 键差异命中 `double_space_period`），落盘原始字节 `12020801`=ON / `12020800`=OFF |

真机 `FMR0224913042917` 本轮离线，**未取证**：真机 INP、慢宿主、厂商字体余量、TalkBack 全部只是模拟器口径。

## 目录

| 文件 | 内容 |
|---|---|
| `evidence/ev_465_keyevent.txt` | 判据 1 / 2 / 3 / 4：`ac2.py <apk> 440 keyevent` 原样输出，4 组开关组合 |
| `evidence/ev_465_soft.txt` | 同脚本 `soft` 路径（回归面：软键不能被改坏） |
| `evidence/ev_465_ac3.txt` | AC-3：组合中连按两次空格，软硬件两入口逐字对照 |
| `evidence/ev_465_press.txt` | 单击 / 长按 / duration 700 / 两连发 × 开关两态的上屏长度（隔离 `--longpress` 工具假象） |
| `evidence/ev_465_extra.txt` | 判据 5 的 `logcat` 原文：`space via=key action=…` + `spaceProbe` 时刻与 `roundTripMs` |
| `evidence/ev_465_gates.txt` | 静态门禁 9 个脚本的 SUMMARY + `check_crosscut` 第 13 组逐行（含新增的 D1 结构检查） |
| `evidence/ev_465_longpress.txt` | 软键空格长按 800ms 的探测输出（见下「未取证」） |
| `test-results/` | `--tests` 只跑 `SpaceKeyPolicyTest` + `InputModePolicyTest`，`--rerun` 后的 JUnit XML 原文 |
| `test-results-full/` | 全量 `testDebugUnitTest --rerun` 的 7 份 XML 原文（含 main 既有缺陷 `AgentDispatcherTest > initializationError`） |
| `scripts/` | 本目录取证用的四个补充脚本 |

## 复现

`scripts/*.py` 依赖 PR #25 的工装，先把 `tools/qa/voca-37/{drive,ac2,fs115}.py` 拷到同一目录（需要 Pillow），再：

```bash
adb start-server && <sdk>/emulator/emulator -avd ispeech_test -no-window -no-audio -no-boot-anim &
python3 ac2.py <app-debug.apk> 440 keyevent   > ev_465_keyevent.txt
python3 ac2.py <app-debug.apk> 440 soft       > ev_465_soft.txt
python3 voca465_extra.py                       > ev_465_extra.txt
python3 voca465_press.py                       > ev_465_press.txt
python3 voca465_ac3.py                         > ev_465_ac3.txt
./gradlew :app:testDebugUnitTest --tests "com.ispeech.input.SpaceKeyPolicyTest" \
  --tests "com.ispeech.input.InputModePolicyTest" --rerun
for s in static input_logic t9 stats phrase_usage pinyin crosscut quick_trigger ai_suggest; do
  python3 tools/verify/check_$s.py; done       > ev_465_gates.txt
```

## 实测结论（模拟器口径）

| 判据 | 组 | 开关 | 宿主文本 | 判定 |
|---|---|---|---|---|
| 1 | `input keyevent 62 62`（单次调用两连发，间隔 0ms） | ON | `'。'` len=1 | 通过（修复前 `'  '`） |
| 2 | `input keyevent 62` ×2（实测 29.6 / 39.1ms） | ON | `'。'` len=1 | 通过（修复前 `'  '`） |
| — | `2x_gap500ms_control`（538.7ms > 窗口） | ON | `'  '` len=2 | 通过（窗口外不误转） |
| 3 | `input keyevent 62 62 62` | ON | `'。 '` len=2 | 见下「三连发口径」 |
| 4 | `input keyevent 62 62` | OFF | `'  '` len=2 | 通过 |
| 5 | 软键 `soft` 全 4 组 | ON/OFF | 与 keyevent 逐字相同 | 通过（两入口同链） |
| 5 | AC-3 组合中两连发 | ON | key 与 soft 均 `'中国国之利刃'`，无 `。` | 通过（首选上屏不被抢） |
| 6 | 单测 23 项 / 静态门禁 9 脚本 | — | `test-results/` + `ev_465_gates.txt` | 全绿 |

### 三连发口径（判据 3 要求「按规格期望定义写死」）

规格 AC-2 只定义两连发（`8324b03:431`），三连发没有 Then。本实现把它钉成：
**`。` + 一个普通空格**（净长度 2）—— 出句号时时间戳已清零，第三下按「单击」处理。
理由：既符合 AC-3 对「窗口内后一次空格 = 一个普通空格」的同类期望，也满足「不吞字」（第三下不被丢弃）、
「不出现 `。` 之外的多余上屏」（不会连着出两个 `。`）。单测 `三连发出句号加一个空格 第三下不吞也不多出句号`
与设备组 3 同判。若 UX / BA 期望「三连只出一个 `。`」，那是规格改文，改判后再调本条。

### `getTextBeforeCursor` 跨进程读取（判据 5 附带的必须取证）

接管后每次硬件空格最多触发 **1 次**读取：`lastSpaceCommitAt == null` 时短路，第一下一律不读
（只有上一记留了时间戳的按法才会读）。`ev_465_extra.txt` 的 epoch 时间戳 / `agoMs` / `roundTripMs`：

| 组 | `spaceProbe` 次数 | `agoMs` | 单次往返 `roundTripMs` |
|---|---|---|---|
| `2x_single_injection` | 2（813.647 / 813.656，相隔 9ms） | 4940 → matched=false；2 → matched=true | 0 / 7 |
| `2x_two_adb_calls` | 1（818.284） | 23 → matched=true | 0 |
| `3x_single_injection` | 1（822.906；第三下时间戳已清零，不读） | 2 → matched=true | 4 |
| `2x_gap500ms` | 2（828.018 / 828.549，相隔 531ms） | 5110 → false；532 → true | 1 / 0 |

即：**连按空格的跨进程读取频率 ≤ 每键 1 次、只在窗口候选态发生，模拟器单次往返 0–7ms**。

模拟器 host 是 Settings 同机进程，往返 0–7ms 只是**下界参考**，不能当真实 INP 用。
慢宿主 / 真机数值未取证，第 7 轮若要卡 INP 需要人类供真机。

## 已知未覆盖

1. **自动重复（按住硬件空格连发）没有设备取证**：`adb input keyevent` 不产生 `repeatCount > 0` 的事件，
   `--longpress` 也不（它是「一次 DOWN + 带 LONG_PRESS 标记的 UP」）。这条只有单测
   `硬件键长按的自动重复不算双击` 覆盖，判定口径是「repeat 一律放行给宿主 = 修复前行为」，
   所以即便真机产生重复事件，也只是回到改动前的宿主连发，不会出 `。`。
2. **`--longpress 62` 上屏 2 个空格**（开关 ON / OFF 完全一致，见 `ev_465_press.txt`）是注入工装假象，
   不是本改动引入的：同一命令在开关关闭时也给 2 个空格，说明第二下由宿主自己吞的。
3. **软键空格长按 500ms 进语音**：本轮只确认代码层未被触碰（`installSpaceGesture` 在 View 层，
   `spaceLongPressFired` 抑制随后的 SPACE 回调，KeyEvent 接管看不到触摸事件）。
   `ev_465_longpress.txt` 空手而归 —— 模拟器语音链路不可用，状态文案 `voice_unavailable` 是自绘 View，
   `uiautomator` 读不到节点，所以「进语音」这条既没证成也没证伪，第 7 轮要卡它得换手段。
4. **AC-3 的 Then 只写了一半**：联想（`association` 默认开）开着时，第二次空格走
   `PICK_FIRST_CANDIDATE` 提交联想首选（实测 `'中国国之利刃'`），不是规格写的「输出一个普通空格」。
   两条键路径与修复前同口径，本单不动；这是规格与实现的差异，归 UX（PR #17）判定。
