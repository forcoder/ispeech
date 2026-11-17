# VOCA-37 · Stage 3 回归工具与证据

报告：`docs/qa/voca-37-stage3-regression.md`（被测构建 `c524351`，PR #18 head）。

## 常量在哪

`drive.py` 顶部集中了本机常量：`ADB`（platform-tools 路径）、`S`（serial，默认 `emulator-5554`）、`IME`、`DEN=2.75`（440dpi 的 px/dp）。换机器只改这里；其余脚本 `import drive as D` 复用。

## 脚本

| 文件 | 用途 |
|---|---|
| `drive.py` | 测量底座：ime inset frame、键帽行检测、候选带列剖面互相关位移、宿主文本真值、软键坐标表、联系人/设置宿主拉起 |
| `fs115.py` | 恒高 × font_scale 取证（含安装身份校验、**测量两端 IME 身份闸门**、候选带墨迹上下留白 → 判截字） |
| `ac2.py` | 双击空格句号：按「开关态（读 DataStore 原始字节）× 键路径（`keyevent` / 软键点击）」二维取证 |
| `ac47.py` | AC-4~AC-7 候选条横滚：按 keycode 连按并把**键名、代码出处、期望档位**一起打进 stdout（`KEYMAP` 行），杜绝「数值对、键名反」；`--fwd 70 --rev 69` 可反向证伪 |
| `vswipe.py` | §8.4 候选带竖滑/斜滑：起点 y、`dy`、`dx`、`dur`、`mode`（inband/above/abs）全参数化，把**「有没有上屏」和「有没有横滚」分成两件事**各打一个判据 |
| `paths.py` | §9.8 路径 ①④⑤ + 双击空格 + 快捷短语字母进搜索框 |
| `paths2.py` | 键帽行/列检测辅助（被 `paths3.py` 复用） |
| `paths3.py` | 带宿主守卫（`-`/`=` 必须进 IME）与联系人弹窗自关的重试版 |
| `qa_rows.py` | 单图工具：`python3 qa_rows.py <png> <y0> <y1> <dpi>` 出行带与间距 |
| `qa_kb.py` | 早期手工探针集合（frame/host/text/bands/shift/ink） |

## 共享 AVD 的三个坑（本轮踩过，脚本已内置防护）

1. **设备里可能留着别人更高 versionCode 的包**：`adb install -r -g` 会 `INSTALL_FAILED_VERSION_DOWNGRADE`。
   一律用 `install -r -d -g`（`-d` 允许降级），降级后用 `classes*.dex` + `resources.arsc` 逐条目 SHA256
   证明装的就是 `c524351` 的产物 —— 整包哈希不可比，所以身份校验不能省。
2. **dex 校验只证明「装的是本包」，不证明「此刻弹出的键盘是本包」**：别的 run 会在测量中途把
   `default_input_method` 切到 Gboard，`ime_frame()` 读到的就是别人的 inset（实测候选底色
   `(241,240,247)` ≠ 本包 `#F4F5F7`=(244,245,247)，933px 那组假读数就是这么来的）。
   现在 `fs115.py` 在 `ime_frame()` 前后各读一次 `default_input_method` 并写进 `RESULT` 行，
   任一端不是 `com.ispeech.ime/` 就打 `SKIP` 且**不记录**该组数字；入口同理，脚本不再替你 `ime set`。
   自检：`adb shell ime set com.google.android.inputmethod.latin/...` 后重跑必须输出
   `SKIP reason=current_ime_not_ispeech` 而不是 `delta=0`（`evidence/ev_fs115_gate.txt`）。
   要测之前先自己固定：`adb shell ime set com.ispeech.ime/com.ispeech.input.IspeechImeService`。
3. **装完包 IME 进程被杀 + density 变更后旧宿主是僵尸窗口**：直接 `ensure_host` 会 6 次全 FAIL。
   `fs115.py` 的 `host_ready()` 每轮先 `am force-stop com.android.settings` 再拉起，键盘已在位且是本包时走快速通道。

## 测量纪律（违反就不可比）

1. 测前 `settings put secure autofill_service null`，否则自动填充会多报一个 `type=ime` InsetsSource。
2. 位移一律用硬件 `-`/`=`（198px=72dp 一档，可复现）；触摸 fling 位移不可复现，禁止作为判据。
   **键名↔方向不靠人记**：`IspeechImeService.kt:612-614` 定死 `KEYCODE_MINUS`(69) → `scrollCandidates(-1)`、
   `KEYCODE_EQUALS`(70) → `scrollCandidates(+1)`，`CandidateView.kt:90-92,199` 是 `smoothScrollBy(steps*72dp,0)`，
   「steps 为正向右」。互相关 `shift` 的符号约定是「正 = 内容左移 = 视口向右」，所以 **`=` 读 +198、`-` 读 −198**，
   末端钳制在**右端**且靠 `=` 到达（左端靠 `-` 钳住）。往左端之外滚是 no-op：
   `ev_paths4/5` 里 `minus4_shift=0` 就是「先按 `-` 再上屏」根本没滚起来的现场，那条路径的脚本已改成按 `=`。
3. residual > 5 的相关结果视为不可判（多半是两帧候选内容不同，不是滚动）——**横滚结论只在 err ≤ 5 时给出**，
   否则写「不可判」，不得把 `err=5.05 / 8.19 / 24.73` 那几行的数值当位移引用。
   尤其：手势一旦把候选上屏，`CandidateView.kt:81` 的 `scrollTo(0,0)` 会整体重置候选池，
   before/after 两帧比的已是不同内容，这种「位移」天然是相关假象（上一轮 `-870` 就是这么来的，已作废）。
4. 每个采信窗口都要有安装身份双端校验：逐条目比对设备上 `base.apk` 与本地构建的 `classes*.dex` + `resources.arsc` SHA256；整包 APK 哈希不可比。
5. 判定类用例（双击空格这类有开关的）必须把**开关实际态**打进同一份 stdout：
   开关态没标注的阴性结果不能算测过。`ac2.py` 直接读
   `run-as com.ispeech.ime cat files/datastore/ispeech_settings.preferences_pb` 的原始字节
   （**路径是 `files/datastore/`，不是 `datastore/`** —— 走错目录会永远读到「文件不存在」，
   于是把真正的 `true` 判成代码默认 `false`），文件/键不存在=从未写过=代码默认 `false`（`KeyboardSettings.kt:29`）；
   键名后面跟 `12 02 08 01`=true、`12 02 08 00`=false。
6. 注入键的间隔要用同一份 stdout 里的 `wall_between_ms` 自证：两次独立 `adb shell input keyevent`
   之间隔着 adb 往返，慢的时候超出 300ms 判定窗口；对照组用单进程多键码 `input keyevent 62 62`（几乎 0 间隔）。
7. 装包前先 `pm clear com.ispeech.ime`：设备里那份 DataStore 可能是别人更高版本构建写的，
   它会让「开关默认 false」这类判据的前提不成立，也会让报告 §3.1 D2 的崩溃必现。
8. 手势类用例必须同时打 `ime_gone`（这次手势后 `ime_frame()` 是否为 `None`）和宿主文本：
   起点落在候选带外的滑动会把键盘收掉，宿主焦点也没了（`after='<NONE>'`），
   这种帧**不能读成「上屏」**——上一轮「带外 215px 竖滑同样上屏」就是这么误判的，已撤回。
   `vswipe.py` 的 `committed` 判据要求 `ime_gone=False` 且宿主文本确实变了。

## 改开关：走输入法内面板，不要走 App 设置页

`ac2.py` 置位走的是键盘上的齿轮键，不是设置 App —— 进入「键盘与输入」会 NPE 崩掉整个
`com.ispeech.ime` 进程（报告 §3.1 D2），崩溃后键盘消失、后续用例全成噪声。

- 齿轮键 `(1005, 1552)`（`FRAME` 行会把它和空格键坐标一起打出来）→ 输入法内配置面板；
- 面板是 `ScrollView`，uiautomator **dump 不到 IME 窗口的节点**，只能靠带网格截图读坐标；
  滚到底：`input swipe 500 1900 500 1450 400` ×3；
- 「双击空格输出句号」开关行：`y=1652`，触点 x=955（滑块热区宽，别用行中心的 500）；
- **点完必须用 DataStore 键差异确认点中了谁**（`TOUCH y=... diff=... verdict=...`）：
  面板滚动位置一变，同一个坐标就会打到「模糊拼音」「五笔」「繁体输出」「按键震动」上，
  把这些无关开关顺手改掉 —— 算 diff 时要排除 `ds_snapshot()` 里的 `bytes` 伪键，
  否则文件长度变化会被当成「键集合变化」而误判。命中后 `KEYCODE_BACK` 关面板。

## evidence/ 与 shots/

`ev_*.txt` 为当场 stdout 原文（含时间戳与身份校验行），`gradle_test.log` 为单测运行日志，`shots/*.png` 为报告点名的截图（候选带裁图、面板态）。**每个文件是采信还是作废，逐条列在报告 §11.1 的「evidence/ 全量清单」**，不要在这里另立口径；作废值只留作环境争用的证据，不得引用其数字。

```bash
python3 tools/qa/voca-37/ac2.py <apk> 440 keyevent   # 硬件/注入键路径 + 开关开/关反例
python3 tools/qa/voca-37/ac2.py <apk> 440 soft       # 软键点击路径 + 同上反例
python3 tools/qa/voca-37/ac47.py <apk> 440           # AC-4~AC-7：= 右移 / - 左移 + 两端钳制 + 右端尾部判据
python3 tools/qa/voca-37/ac47.py <apk> 440 --fwd 69 --rev 70   # 反向证伪：若方向真反了，这一组符号会翻
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family commit --dy 40,60,90,100,110,150,200   # 上屏阈值
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family hroll --pre 4 --dy 60,110 --dx 0        # 纯竖滑有无横滚
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family hroll --pre 4 --dy 110 --dx 160         # 斜滑
python3 tools/qa/voca-37/vswipe.py <apk> 440 --mode above --start 215 --dy 215                # 起点在带外
```
