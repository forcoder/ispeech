# VOCA-37 · Stage 3 键盘交互回归报告（模拟器口径）

- 被测构建：`c524351e2e509ed294f49dd3d2f7a1bf29e07a4d`（PR #18 head，base `fa57a07`；PR #18 仍为 OPEN，未合并）
- 规格权威：`agent/ux/voca-45-spec` @ `28c1cac`（main 上的 602 行翻页版与 `agent/ux/voca-45` @ `fc564aa` 均已过期，本报告不引用）
- 执行环境：本地 AVD `ispeech_test`（1080×2340 / 440dpi / arm64 / 2G），Android 14 模拟器；对照基准为同机搜狗 `com.sohu.inputmethod.sogou/.SogouIME`
- 执行人：测试工程师（QA）· 2026-11-17 03:40–05:20（本地时区）
- 结论：**条件性 no-go（真机口径缺失）**。模拟器可判的判据全部执行并有证据，未发现 P0/P1；但「占屏比 ≤1pp」在按真机参数（520dpi / font_scale 1.15）复现时变成 **+7.09pp**，模拟器无法定稿，必须真机复核后才能给 go。

---

## 1. 结论先行

| 层 | 判定 | 说明 |
|---|---|---|
| 模拟器可判面（AC-1/2/3/4/5/6/7/9、G1–G7、恒高×1.15、面板路径） | **全绿**（1 项 P2 观察、2 项规格/实现差异记录） | 见 §3–§6 |
| 真机可判面（绝对占屏 pp、520dpi 键高观感、真机手指触摸） | **未取证** | 见 §5、§9；按 issue 约束不得写「通过」 |
| 发布建议 | **no-go（本轮）** → 真机口径补测后可转「有条件 go」 | 门禁：真机 pp 未定稿 |

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

测量口径（全部按规格 §8.1 / §8.4）：`dumpsys window displays` 的 `InsetsSource type=ime` frame 取键盘高度；滚动位移用候选带列剖面互相关（step=2px，±600，取最小平均绝对误差，residual>5 视为不可判）；文本真值取宿主输入框。测前一律 `settings put secure autofill_service null`（否则 GMS 自动填充会多报一个 `type=ime` source，把 inset 从 988 抬到 1260）。

| 判据 | 期望 | 实测 | 判定 |
|---|---|---|---|
| AC-1 恒高（空态→组合态 Δ） | Δ=0 | 440dpi/fs1.0：988→988 ×3 轮（04:51:56–04:53:13 身份双端校验）；440dpi/fs1.15：988→988；520dpi/fs1.15：1168→1168 | **PASS** |
| AC-2 双击空格句号（开关开） | 窗口内第二次空格出「。」 | 软键盘连点两次：宿主=`。`（wall 179ms）；间隔 500ms 连点：`␣␣`；三连点：`。␣` | **PASS** |
| AC-2 反例（开关关） | 只出空格 | `␣␣`（开关色值实测 195,196,197=关；开=0,100,136） | **PASS** |
| AC-3 组合非空时首格上屏首选 | 不出「。」 | 打 `shi`→首格=`是`；再按一次=`是疑是地上霜`（继续上屏联想/候选，未插句号） | **PASS** |
| AC-4 `-` 单档位移 | 198±6px（72dp） | 连按 6 次：198/198/198/198/198/198，residual ≤2.8 | **PASS** |
| AC-5 反向 `=` | −198px | −198，residual 2.52；左端 clamp 在第 25 次，尾部 3 组完全相同 | **PASS** |
| AC-6 边界不绕回 | 到端不循环 | 右端第 26 次 cum=4892px 后 5 次采样全部 4892；尾部像素差 bbox 仅 (242,430,248,492)=光标，之后 None | **PASS** |
| AC-7 端点起步可动 | 左端按 `-` 立即 +198 | +198，residual 2.8 | **PASS** |
| AC-6b 宿主文本不受滚动键污染 | 不变 | 全程宿主=`shi` | **PASS** |
| AC-9 退格长按阶梯 | 380/90/45ms 未被新事件路径打断 | 常量仍在 `SoftKeyboardView.kt:991-994`；200ms→删 1、1000ms→删 5、2000ms→删 15（单调、有快档加速）；模拟器 `input swipe` 注入下未观测到 45ms 快档理论值 | **PASS（带说明）**：与基线同码，非本轮回归 |
| G1 无 `N/M` 页码文案 | 不出现 | 候选条无页码绘制路径；`aapt2 dump resources` 无 `candidate_page_*` | **PASS** |
| G3 资源表无翻页控件 | 不出现 | 全量 dump 仅命中 `drawable/ic_settings_gear`，用于工具栏「设置」键（`SoftKeyboardView.kt:370`），与候选条无关 | **PASS** |
| G4 带外手势不驱动滚动 | 位移=0 | 键区横滑 shift=0；工具栏横滑 shift=0 | **PASS** |
| G6 编辑后偏移复位 | 归零 | 滚动态 shift=762 → 编辑宿主后 shift=0 | **PASS** |
| G2 / G7 | 只记用户可见影响 | 见 §8 | 记录 |

## 4. 命名必证项：恒高 46dp × font_scale 1.15 会不会截字

规格点名的本轮最可能新缺陷：候选条用固定 `layout_height=46dp`（`candidate_view.xml:10`，注释说明「用 height 而不是 minHeight——候选字号是 sp，minHeight 兜不住 font_scale 放大后的内容高度」），内行 `minHeight=38dp`；1.15 下内容 38.2dp 被压进 38dp 可能截字。

实测（`tools/qa/voca-37/fs115.py`，安装身份双端校验 match=True）：

| 参数 | 键盘高（空态→组合态） | 候选带 | 带内文字墨迹范围 | 上下留白 | 结论 |
|---|---|---|---|---|---|
| 440dpi / fs 1.00 | 988 → 988（Δ=0） | 126px（46dp） | 1400–1441（42px） | 顶 47 / 底 37 | — |
| 440dpi / fs 1.15 | 988 → 988（Δ=0） | 126px（46dp） | 1397–1445（49px） | 顶 44 / 底 31 | **不涨高、不截字** |
| 520dpi / fs 1.15 | 1168 → 1168（Δ=0） | 150px（46dp） | 带内 1185–1284 | 仍在带内 | 同上（模拟器模拟真机参数，非真机定稿） |

判读：1.15 下候选字高 42→49px（+16.7%，与 sp 缩放一致），键盘高度**一位未涨**（恒高契约成立），墨迹上下仍各有 ≥31px 余量，未贴带边 → **无截字**。截图证据：`f440_1.15_c_cand.png`（候选带裁图）、`f440_1.15_c.png`（整屏）。

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
| ② 候选池上限截断 | **代码级确认，未做真机计数**：`candidatePoolLimit = candidatePageSize × CANDIDATE_POOL_FACTOR(3)`（`IspeechImeService.kt:105/187`），默认 9 → 27；用户可设 5/7/9 → 15/21/27。运行侧只证到「滚到端后不再新增内容」（§3 AC-6 cum 恒定 4892px） | 代码 + `ev_ac47.txt` |
| ③ T9 态 `-`/`=` 滚动 | **未取证**：模拟器上 `123 → 9键` 的软键坐标按权重推算落到了短语面板（kb_h=1885），两轮尝试均未稳定进入九宫格；`check_t9.py` 静态侧 87 项通过可保证键位定义未被本轮改动破坏 | `ev_paths5.txt`、`ev_paths9.txt` |
| ④ 上屏/联想后偏移归零（G2） | **未取证**：三次尝试的宿主字段分别被 Settings 搜索框吞键、联系人「姓名」框吞键、以及联系人「更改尚未保存」弹窗抢焦点，`-` 未能进 IME，位移数据不可信（已丢弃）。代码侧 `CandidateView.kt:81` 无条件 `scrollTo(0,0)` 仍在 | `ev_paths4/5/6/9.txt` |
| ⑤ 面板打开时候选条层级 | **PASS（并给出用户可见影响）**：工具栏「快捷短语」「剪贴板」面板会**整体替换输入区**，候选条不再存在；短语面板高 1885px（面板+工具栏+键区，无候选条），剪贴板面板高 727px（仅面板）。工具栏「设置」键直接跳设置页，键盘整体收起。截图：`p5_phrase.png`、`p5_clip.png` | `ev_paths4.txt` |

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

## 8. 规格/实现差异与观察项（按 PM 裁定不判 FAIL）

1. **R4 补偿实现**（规格/实现差异）：实现只在「光标前一个字符仍是空格」时才 `deleteSurroundingText(1,0)`，判定入口 `lastSpaceStillBeforeCursor() = lastSpaceCommitAt != null && getTextBeforeCursor(1,0)==" "`（`IspeechImeService.kt:715-780`）。实测在宿主里先删掉空格再双击 → 得到 `␣␣` 而非「。」（`ev_r4.txt` S3、`ev_ac23d.txt` R4_probe=`她她们`），符合「不做破坏性删除」的意图，比规格更保守。
2. **G2 无条件 `scrollTo(0,0)`**（`CandidateView.kt:81`）：用户可见影响＝每次重绘候选（含联想刷新）都回到最左，滚动位置不保留。本轮未取到运行时证据（§6④），列为待补。
3. **G7 徽标无入口**：候选来源徽标（短语/AI/学习）仍渲染但没有点击入口，用户可见影响＝只能看不能用。
4. **P2 观察项（待复现）**：候选带内的竖滑 60px 会把候选上屏（`shi`→`使`），110px 不上屏；起点在候选带外 215px 的竖滑同样上屏（`shi`→`时`）。即竖滑在短位移时被当成点击处理，且手势起点越出候选带仍能命中候选。该组用例当时用一次性命令执行、脚本未固化，端点语义需要复核后才能定性，**挂「待复现」**，复现路径见 §10。

## 9. 未取证清单（真机 / 后续必须补）

| # | 项 | 为什么模拟器判不了 | 归属 |
|---|---|---|---|
| 1 | 520dpi / fs1.15 的定稿占屏 pp | 模拟器只是「模拟真机参数」，字体渲染与厂商 ROM 不同 | SRE + 人类评审（装真机跑） |
| 2 | §9.8 ③ T9 态 `-`/`=` 滚动、④ 上屏后偏移归零 | 模拟器软键坐标靠权重推算，面板态抢焦点；换真机或固化 uiautomator 定位后可测 | 测试（下轮补，非真机专属） |
| 3 | 语音键上滑取消、语音失败文案 | 需要真实音频输入 | 测试 + 前端 |
| 4 | TalkBack 真机朗读与 600ms 窗口 | 模拟器未装 TalkBack 服务态 | 测试 + UX |
| 5 | 真机手指触摸的滚动/长按手感（fling 不可复现，本轮一律用 `-`/`=` 与 `input swipe` 固定位移） | 触摸离散度 | 人类评审 |
| 6 | release 打包 + 真机安装 | 项目规则五：生产操作需人类确认 | 人类确认 |

## 10. 复现方式

```bash
# 0) 起模拟器（设备不跨 run 存活）
avdmanager list avd | grep ispeech_test
emulator -avd ispeech_test -no-snapshot-save -gpu host &
adb wait-for-device; adb -s emulator-5554 shell settings put secure autofill_service null

# 1) 装被测包（必须校验身份，见 §11）
adb -s emulator-5554 install -r -g <app-debug.apk of c524351>
adb -s emulator-5554 shell ime enable com.ispeech.ime/com.ispeech.input.IspeechImeService
adb -s emulator-5554 shell ime set   com.ispeech.ime/com.ispeech.input.IspeechImeService

# 2) 恒高 / 字体缩放 / 截字（§3 §4）
python3 tools/qa/voca-37/fs115.py <apk> 440 1.0,1.15
python3 tools/qa/voca-37/fs115.py <apk> 520 1.0,1.15

# 3) §9.8 路径与回归面
python3 tools/qa/voca-37/paths.py  <apk>
python3 tools/qa/voca-37/paths3.py <apk>     # 带宿主守卫与弹窗自关

# 4) 键帽行/位移测量
python3 tools/qa/voca-37/qa_rows.py <png> <y0> <y1> <dpi>

# 5) 待复现的竖滑用例（§8.4）：起点分别在 带内+60px、带内+110px、带上沿-215px，
#    终点同 x、y 递增 215px，duration 120ms；每步前把宿主清空并打 shi，读宿主文本变化
```

## 11. 环境风险（必须记录，否则数字不可比）

同一台 `emulator-5554` 在本轮执行期间被其他 run **换包 3 次**（`pm path` 的包目录哈希与 `lastUpdateTime` 变化：04:42:28、05:06:47、05:18 前后 `default_input_method` 被切到 Gboard）。因此：

- 所有采信数字都带安装身份双端校验：对设备上 `base.apk` 与本地构建产物逐条目比对 17 个 `classes*.dex` + `resources.arsc` 的 SHA256（整包 APK 哈希不可比，签名/时间戳会漂）；
- 04:42–04:50 之间的一批读数（含 `ev_headline.txt` 里 fs1.15 的 833px、`ev_fontscale*.txt`、`ev_sogou440.txt`、`ev_520.txt`）经复核是 Gboard/他人构建的污染值，**已全部作废**，本报告只引用身份校验通过的窗口（04:51:56–04:53:13、04:59:35–05:01:00、05:03:44–05:05:18、05:08:20–05:10:48）；
- 建议：QA 独占 AVD（或给每个 run 建独立 avd 名），否则每轮都要付「重测 + 作废」的成本。

## 12. 范围外已知缺口（PM 裁定不计入本轮 FAIL）

`fa57a07` 既有债务，本轮未回归、也未恶化：§5 遗留 4 项无障碍（候选条朗读、滚动位置播报、键位 label 缺失、面板焦点顺序）+ 候选文字对比度 3.82:1（低于 4.5:1）。52→46dp 的 UI 批准已由人类于 11-09 给出，不再重开。

---

### 附：本报告引用的原始证据

`ev_headline.txt`（作废段已标注）、`ev_ac47.txt`、`ev_ac23{,b,c,d}.txt`、`ev_r4.txt`、`ev_ac9*.txt`、`ev_g46.txt`、`ev_vswipe.txt`、`ev_pp.txt`、`ev_sogou{115,520}.txt`、`ev_fs115b.txt`、`ev_fs115_440_final.txt`、`ev_paths{2,3,4,5,6,7,8,9}.txt`、`gradle_test.log`，截图 `f440_1.15_c.png`、`f440_1.15_c_cand.png`、`p5_phrase.png`、`p5_clip.png`、`reg_phr_after.png`。
