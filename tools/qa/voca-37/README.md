# VOCA-37 · Stage 3 回归工具与证据

报告：`docs/qa/voca-37-stage3-regression.md`。被测两个口径：**A 包 `c524351`**（PR #18 head）与 **B 包 `cffc0cd`**（QA 本地集成候选 = A 包 + VOCA-170 `d3b6f84` + VOCA-184 `d283706` + VOCA-207 `4d6363c`，见报告 §13）。

## 常量在哪

`drive.py` 顶部集中了本机常量：`ADB`（platform-tools 路径）、`S`（serial，默认 `emulator-5554`）、`IME`、`DEN=2.75`（440dpi 的 px/dp）。换机器只改这里；其余脚本 `import drive as D` 复用。

## 脚本

| 文件 | 用途 |
|---|---|
| `drive.py` | 测量底座：ime inset frame、键帽行检测、候选带列剖面互相关位移、宿主文本真值、软键坐标表、联系人/设置宿主拉起 |
| `fs115.py` | **spec AC-1 恒高 + 截字 + spec-AC-10**（第五轮重写版）。出数前过五道闸门：键位反推成功、宿主回读==所敲串、`chip 文本==所敲串` 且 chip 在候选区左、**chip 右侧可见候选 ≥4**、池内 ≥8；全过才打 `GATES`→`RESULT`→`INK`→`CAND`→`AC10`，否则只打一行 `GATE reason=… rc=…` 且 **stdout 不出现 RESULT/INK/delta**。第 5 位 `label`（默认 `a`）进截图名，防止两个包同名帧互相覆盖 |
| `band.py` | 候选带像素取证底座：底色/chip/item/主题色四组常量 + 连通块切候选项 + **角标圆片按外形剔除** + `first_item_left = chip 右沿 + 1`（字身只在它右边量）。为什么必须这样：`CandidateView.kt:76-77` 在候选列表为空时把 `scrollView` 置 `GONE`，整带宽里就只剩 chip 的墨迹——旧口径把「量 chip」当成了「量候选」 |
| `voidcheck.py` | 作废自查（裁决 §6-3 的自证）：用旧报告的窗口重扫交付帧，先逐位复现旧墨迹行，再用新口径数候选 → `x>240` 命中列=0 就证明旧数据确实还在量 chip。rc=0 = 三帧全部低于闸门（作废成立） |
| `ac9.py` | spec-AC-9 退格长按阶梯（200/1000/**1500**/2000ms）。两条硬规矩：退格键位从截图反推（不写死像素）；先做一次 200ms 短按**必须恰好删 1 字**，删不动说明点到的不是退格键 → `GATE reason=del_key_miss rc=20`，后面所有档位都是废数。长度一律 `hold()` 连读稳定值（批量敲键后紧接着 `uiautomator dump` 拿到的是旧文本，会把「删掉了」量成「没删」）。宿主文本一律用**已上屏中文**——组合缓冲里的退格走删 composing 的另一条路径，不是 AC-9 说的「删除字符」 |
| `dsdump.py` | AC-2/AC-3 披露用的开关运行时取值：`chinese_punctuation` + `double_space_period`。DataStore 只在**首次写入**时建文件，所以「文件不存在」是有效结论（`source=code_default(never_written)`），不是失败；脚本先 `ls -R files` 自证 run-as/路径没错（看不到 `cell_dicts` 才是真失败 → `rc=21`） |
| `d2check.py` | `v1.6-AC9` / D2 崩溃时序（脚本化，含阳性对照）。三道闸门：入口读 `default_input_method`（rc=17）、预热后必须「键盘真弹出 + IME 进程在」（rc=19，否则是假 FIXED）、崩溃只数 `Process: com.ispeech.ime` 的 FATAL 块（否则会把别人家的崩溃算进来） |
| `ac2.py` | 双击空格句号：按「开关态（读 DataStore 原始字节）× 键路径（`keyevent` / 软键点击）」二维取证 |
| `ac47.py` | AC-4~AC-7 候选条横滚：按 keycode 连按并把**键名、代码出处、期望档位**一起打进 stdout（`KEYMAP` 行），杜绝「数值对、键名反」；`--fwd 70 --rev 69` 可反向证伪 |
| `vswipe.py` | §8.4 候选带竖滑/斜滑：起点 y、`dy`、`dx`、`dur`、`mode`（inband/above/abs）全参数化，把**「有没有上屏」和「有没有横滚」分成两件事**各打一个判据 |
| `paths.py` | §9.8 路径 ①④⑤ + 双击空格 + 快捷短语字母进搜索框 |
| `paths2.py` | 键帽行/列检测辅助（被 `paths3.py` 复用） |
| `paths3.py` | 带宿主守卫（`-`/`=` 必须进 IME）与联系人弹窗自关的重试版 |
| `qa_rows.py` | 单图工具：`python3 qa_rows.py <png> <y0> <y1> <dpi>` 出行带与间距 |
| `qa_kb.py` | 早期手工探针集合（frame/host/text/bands/shift/ink） |

## fs115 / ac9 / d2check 的退出码（拒绝出数时看这个，别看有没有数字）

| 脚本 | `reason=` | rc | 含义 |
|---|---|---|---|
| `fs115.py` | `readback_mismatch` | 10 | 宿主回读 != 所敲串（键漏了/打错位置） |
| `fs115.py` | `no_candidates` | 11 | chip 右侧一个候选字身都没有 |
| `fs115.py` | `chip_not_left` | 12 | chip 盒不在候选区左侧（帧不对/带没画出来） |
| `fs115.py` | `cand_low` | 13 | **可见候选 < 4**（chip-only 伪装态的兜底，520dpi `dashu` 两包都吃这条） |
| `fs115.py` | `pool_low` / `not_at_origin` | 14 / 15 | 池内 < 8 条 / 滚动后没回到原点 |
| `fs115.py` | `key_map_fail` / `ime_not_ours` / `host_not_ready` | 16 / 17 / 18 | 键位反推失败（VOCA-184 后曾把两行粘成一行）/ 当前 IME 不是本包 / 宿主起不来 |
| `ac9.py` | `del_key_miss` / `fill_short` | 20 / 21 | 200ms 探针删不掉 1 字（点错键）/ 填字没填满目标长度 |
| `dsdump.py` | `run_as_unavailable` | 21 | `run-as` 或路径真的不对（与「文件不存在=默认值」严格区分） |
| `voidcheck.py` | — | 22 | 有帧未低于旧口径闸门 → 作废结论需复核（0 = 作废成立） |
| `d2check.py` | `card_not_found` / `ime_not_ours` / `keyboard_not_shown` | 2 / 17 / 19 | 卡片没找到 / IME 被抢 / 键盘没弹出（后两条 = 不许报 FIXED） |
| `d2check.py` | — | 0 / 1 | 0 = 未崩（修复成立）；1 = 崩了并附 `STACK` 行 |

## 共享 AVD 的四个坑（本轮踩过，脚本已内置防护）

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
4. **`am force-stop com.ispeech.ime` 会让系统把默认输入法回落到 Gboard**（第五轮实测：`d2check.py` 连续三次
   `rc=17`，`IME_START` 还是本包、两秒后 `FRAME_1` 读到 833px 的 Gboard 几何 + `pidof` 空）。
   要冷数据态请用 `pm clear` + 紧接着 `ime set` + 重新 `host_ready()`；**别 force-stop 自己选中的输入法**。
   同理：数 FATAL 时必须按 `Process: com.ispeech.ime` 过滤，否则别的 run 的崩溃会被算成你的结论。

## 测量纪律（违反就不可比）

1. 测前 `settings put secure autofill_service null`，否则自动填充会多报一个 `type=ime` InsetsSource。
2. 位移一律用硬件 `-`/`=`（198px=72dp 一档，可复现）；触摸 fling 位移不可复现，禁止作为判据。
   **键名↔方向不靠人记**：`IspeechImeService.kt:612-614`（行号锚 c524351；main `fa57a07` 上是 `:601-603`）定死 `KEYCODE_MINUS`(69) → `scrollCandidates(-1)`、
   `KEYCODE_EQUALS`(70) → `scrollCandidates(+1)`，`CandidateView.kt:90-92,199` 是 `smoothScrollBy(steps*72dp,0)`，
   「steps 为正向右」。互相关 `shift` 的符号约定是「正 = 内容左移 = 视口向右」，所以 **`=` 读 +198、`-` 读 −198**，
   末端钳制在**右端**且靠 `=` 到达（左端靠 `-` 钳住）。往左端之外滚是 no-op：
   `ev_paths4/5` 里 `minus4_shift=0` 就是「先按 `-` 再上屏」根本没滚起来的现场，那条路径的脚本已改成按 `=`。
3. residual > 5 的相关结果视为不可判（多半是两帧候选内容不同，不是滚动）——**横滚结论只在 err ≤ 5 时给出**，
   否则写「不可判」，不得把 `err=5.05 / 8.19 / 24.73` 那几行的数值当位移引用。
   尤其：手势一旦把候选上屏，`CandidateView.kt:81` 的 `scrollTo(0,0)` 会整体重置候选池，
   before/after 两帧比的已是不同内容，这种「位移」天然是相关假象（上一轮 `-870` 就是这么来的，已作废）。
4. **inset 在位 ≠ 键盘画在那儿**：别的 run 收掉键盘后 `InsetsSource type=ime` 会残留（实测 frame 仍报 988，截屏那块区域却是宿主 Settings 的文字）。`ac47.py` 因此加了三道前提闸门，跑之前不用肉眼核对，而且**每种失效一个 `reason=` + 一个退出码**——上一版三种前提全退 rc=4，第三人照 §10 复跑时把「工装没跑起来」读成「证伪成立」：

   | `reason=` | rc | 触发条件 | 该怎么处理 |
   |---|---|---|---|
   | `probe_zero` | 4 | 左端按 fwd 方向的键位移 0 且 err≤5 = 键↔方向对调 / 候选池 ≤1 条（`IspeechImeService.kt:613`） | 对调档（`--fwd 69 --rev 70`）出这个 = **预期行为**，就是反向证伪档 |
   | `host_buffer` | 5 | 预热后宿主文本 ≠ `shi`：上一次 run 的残留、或键漏进了宿主而不是 IME | 按下面第 6 条复位后重跑，**不是**测出不符 |
   | `bg_low` | 6 | 候选带底色命中率 < 50%（正常在位实测 77.5~98.3） | 键盘没画在 inset 上，复位重跑 |
   | `frame_none` | 7 | 读不到 `type=ime` frame | 键盘没弹出，复位重跑 |
   | `host_not_ready` | 8 | 宿主输入框起不来 / 拿不到焦点 | 复位或直接换宿主，复位重跑 |

   `GATE` 行同时打该前提的实测字段（`host=` / `probe_shift=` / `bg_match_pct=` / `frame=`），所以只看 stdout 就能分清是哪种。可滚性探针本身仍是**反向证伪档**：`--fwd 69 --rev 70` 跑出来就是 `reason=probe_zero`（`ev_ac47_falsify.txt`（没打 reason 的那次）、`ev_ac47_gate_probe_zero.txt`（补 reason 后的复跑）），说明方向判据长在数据上而不是长在我写的注释上。没有这道闸门时，13:06 那次对调跑产出的是一份 40 档全 0 的「假到端钳制」sweep，看着像 PASS，其实是前提不成立。

5. **跑 `ac47.py` 前把宿主状态复位**（共享 AVD 上必做，否则大概率吃 `reason=host_buffer`）：
   `adb shell input keyevent KEYCODE_MOVE_END` + 30× `adb shell input keyevent 67`，或直接 `adb shell pm clear com.ispeech.ime`（后者会连开关态一起清掉，判据依赖开关态的用例要重新置位）。
6. 每个采信窗口都要有安装身份双端校验：逐条目比对设备上 `base.apk` 与本地构建的 `classes*.dex` + `resources.arsc` SHA256；整包 APK 哈希不可比。
7. 判定类用例（双击空格这类有开关的）必须把**开关实际态**打进同一份 stdout：
   开关态没标注的阴性结果不能算测过。`ac2.py` 直接读
   `run-as com.ispeech.ime cat files/datastore/ispeech_settings.preferences_pb` 的原始字节
   （**路径是 `files/datastore/`，不是 `datastore/`** —— 走错目录会永远读到「文件不存在」，
   于是把真正的 `true` 判成代码默认 `false`），文件/键不存在=从未写过=代码默认 `false`（`KeyboardSettings.kt:29`）；
   键名后面跟 `12 02 08 01`=true、`12 02 08 00`=false。
8. 注入键的间隔要用同一份 stdout 里的 `wall_between_ms` 自证：两次独立 `adb shell input keyevent`
   之间隔着 adb 往返，慢的时候超出 300ms 判定窗口；对照组用单进程多键码 `input keyevent 62 62`（几乎 0 间隔）。
9. 装包前先 `pm clear com.ispeech.ime`：设备里那份 DataStore 可能是别人更高版本构建写的，
   它会让「开关默认 false」这类判据的前提不成立，也会让报告 §3.3 D2 的崩溃必现。
10. 手势类用例必须同时打 `ime_gone`（这次手势后 `ime_frame()` 是否为 `None`）和宿主文本：
   起点落在候选带外的滑动会把键盘收掉，宿主焦点也没了（`after='<NONE>'`），
   这种帧**不能读成「上屏」**——上一轮「带外 215px 竖滑同样上屏」就是这么误判的，已撤回。
   `vswipe.py` 的 `committed` 判据要求 `ime_gone=False` 且宿主文本确实变了。

## 改开关：走输入法内面板，不要走 App 设置页

`ac2.py` 置位走的是键盘上的齿轮键，不是设置 App —— 进入「键盘与输入」会 NPE 崩掉整个
`com.ispeech.ime` 进程（报告 §3.3 D2），崩溃后键盘消失、后续用例全成噪声。

- 齿轮键 `(1005, 1552)`（`FRAME` 行会把它和空格键坐标一起打出来）→ 输入法内配置面板；
- 面板是 `ScrollView`，uiautomator **dump 不到 IME 窗口的节点**，只能靠带网格截图读坐标；
  滚到底：`input swipe 500 1900 500 1450 400` ×3；
- 「双击空格输出句号」开关行：`y=1652`，触点 x=955（滑块热区宽，别用行中心的 500）；
- **点完必须用 DataStore 键差异确认点中了谁**（`TOUCH y=... diff=... verdict=...`）：
  面板滚动位置一变，同一个坐标就会打到「模糊拼音」「五笔」「繁体输出」「按键震动」上，
  把这些无关开关顺手改掉 —— 算 diff 时要排除 `ds_snapshot()` 里的 `bytes` 伪键，
  否则文件长度变化会被当成「键集合变化」而误判。命中后 `KEYCODE_BACK` 关面板。

## 键位一律从当场截图反推（为什么不能写死像素）

`drive.key_map()` 用白底键帽的列段认行（段数 = 10/9/7），空格取第三行之后最宽白段，退格取第三行最后白段右沿到屏幕右边的中点。历史教训两条，都写进代码注释了：

1. 旧版写死 440dpi 的一排像素（`ROW1/ROW2/ROW3/YROW`），既没按密度换算、中心本身也和真实布局对不上（`o` 写 918、实测 804）→ 520dpi 下把 `zhongguo` 打成 `hogguo`/`khk`，候选条从头到尾是空的，第四轮的「组合态」其实是 chip-only。现在 `tap_keys` 敲完必须回读宿主文本，不一致就出不了数。
2. **VOCA-184（`d283706`）把字母键行内留白从 4dp 收到 1dp 后，行间隙只剩 ~5px**，`key_map` 原来的 `merge_gap=8` 会把 qwerty 行和 asdf 行粘成一个带 → 段数不再是 10/9/7 → B 包首跑直接 `GATE reason=key_map_fail rc=16`。已加「合并后带高不得超过 52dp（`MAX_KEY_HEIGHT_DP` 同口径）」的上限护栏（`drive.py`），A/B 两包都能标定成功。

换键盘几何（键高、留白、阴影）之后先跑一次自检，看 `diag` 里的 `runs` 是不是 `[10,9,7,3]`：

```bash
python3 -c "
import sys; sys.path.insert(0,'tools/qa/voca-37')
import drive as D; print(D.ime_frame()); print(D.key_map())"
```

## 候选项「没有被 ellipsize」这条怎么判

候选项是 `wrap_content` 且没有 `ellipsize`（`CandidateView.kt:101` 只设 `textSize=18f`、`:112` 设 `setPadding(dp(12),dp(6),dp(12),dp(6))`），所以**不存在「文字被省略号截掉」这种截字**：字要么整条画出来，要么被 `HorizontalScrollView` 的视口右边界切掉。因此 spec-AC-10 的「完整可见」只落在两条可测的东西上：① 该项 x 区间是否越出采样区右沿（`x_end` vs 屏幕右边界，越界即 `badge_clipped` / 不完整）；② 字身墨迹上下是否与带内沿相切（`tangent_items`，纵向截字）。报告里凡写「未截字」都必须同时给这两个字段，不能只写「仍在带内」。

## 集成候选（B 包）复跑

报告 §13 的 B 包 = `c524351` + `d3b6f84`(VOCA-170) + `d283706`(VOCA-184) + `4d6363c`(VOCA-207) 的本地三方合并，配方与冲突清单在报告 §10-6 / §13.1。**用它跑任何脚本时记得带 `label`**（`fs115.py <apk> 440 1.0 shijian b`），否则会把 A 包的候选带裁图覆盖掉——第五轮就覆盖过一次（12 张同名帧），文本证据没受影响，但帧与包对不上名就是废证据。

## evidence/ 与 shots/

`ev_*.txt` 为当场 stdout 原文（含时间戳与身份校验行），`gradle_test.log` 为单测运行日志，`shots/*.png` 为报告点名的截图（候选带裁图、面板态）。**每个文件是采信还是作废，逐条列在报告 §11.1 的「evidence/ 全量清单」**，不要在这里另立口径；作废值只留作环境争用的证据，不得引用其数字。

```bash
python3 tools/qa/voca-37/ac2.py <apk> 440 keyevent   # 硬件/注入键路径 + 开关开/关反例
python3 tools/qa/voca-37/ac2.py <apk> 440 soft       # 软键点击路径 + 同上反例
python3 tools/qa/voca-37/ac47.py <apk> 440           # AC-4~AC-7：= 右移 / - 左移 + 两端钳制 + 右端尾部判据
python3 tools/qa/voca-37/ac47.py <apk> 440 --n 3     # 正向复测（PRE_PROBE 必须 +198 才出数）→ ev_ac47_probe.txt
python3 tools/qa/voca-37/ac47.py <apk> 440 --fwd 69 --rev 70   # 反向证伪档：探针读 0 → GATE rc=4，不出数 → ev_ac47_falsify.txt
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family commit --dy 40,60,90,100,110,150,200   # 上屏阈值
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family hroll --pre 4 --dy 60,110 --dx 0        # 纯竖滑有无横滚
python3 tools/qa/voca-37/vswipe.py <apk> 440 --family hroll --pre 4 --dy 110 --dx 160         # 斜滑
python3 tools/qa/voca-37/vswipe.py <apk> 440 --mode above --start 215 --dy 215                # 起点在带外
```
