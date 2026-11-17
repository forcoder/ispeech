# VOCA-37 · Stage 3 回归工具与证据

报告：`docs/qa/voca-37-stage3-regression.md`。第七轮起**只有一个被测面**：PR #18 head `78343a4`（用 `gh pr view 18 --json headRefOid` 现算，不抄写），规格锚 PR #17 head `0e4eeb0`。第六轮及以前的两个口径都降为**历史对照**：旧 head 的产物（硬件空格路径还是阴性的那一版）、以及 B 包 `cffc0cd`（见文末）。正文数字的采信口径自第八轮起是「**§4／§5／§7 的设备读数引 `evidence/ev_r8_*`，其余腿仍引 `ev_r7_*`**」——同一个数两份逐位一致这件事本身就是复现证据，逐文件是采信还是作废见报告 §11.1。

## 常量在哪

`drive.py` 顶部集中了本机常量：`ADB`（platform-tools 路径，可用 `QA_ADB` 覆盖）、`S`（serial，读 `QA_SERIAL`）、`IME`、`DEN=2.75`（440dpi 的 px/dp）。换机器只改这里；其余脚本 `import drive as D` 复用。
**环境变量（第七轮加）**：`QA_SERIAL`（**默认 `emulator-5554`，所有脚本统一从这里取，不许各自写死**——同机多开的 5556 会串台）、`QA_ADB`、`QA_HEAD`（默认 `78343a4`）、`QA_SPEC`（默认 `0e4eeb0`）、`QA_APK`（被测包）、`QA_BASE_APK`（档① 修复前对照包）。脚本的 `IDENT` 行会把这些值打出来，装错包直接 `rc=19`。

`drive.log()` 从第七轮（入库 `aaa07f6`）起给**每一行**补 `ts=`（调用方显式传了就不覆盖）。之前一份证据只有文件头一个时刻，读者只能把读数追到入库提交，这在第六轮裁决里被定为不合格证据。
**但「工具备了能力」不等于「每份证据都有时刻」**（第八轮打回项 3），**而第八轮对自己这句话的归因也订正过一次**：`ev_r7_*` 里数据行不带 `ts=` 的成因分两类，不能一概说成「工具没能力」——
- **抓在改动之前**（链路本来就走 `drive.log`，本轮脚本 0 改动）：`ev_r7_ac10_1080.txt`（文件头 `16:40:19Z`＝+08:00 的 00:40:19）、`ev_r7_ac10_target_geom.txt`（00:42:38）、`ev_r7_d2_base.txt`（`# ts=…01:59:27+0800`）都早于同一链路改过 `drive.log` 后重跑的 `ev_r7_fs115.txt`（首条 `ts=…02:34:21+0800`）；`ppwin.py` 自己的 `log()` 是第七轮 `6882830` 才补的时刻，而 `ev_r7_pp_window.txt` 抓在 00:35:03 → 29 条里 17 条缺。
- **整条链路不经 `drive.log`**：旧 `g1_g3.sh` 是 shell 直出（`aapt2` dump + `grep`，7 条数据行全无 `ts=`、`grep -c IDENT` = 0）；`ppsum.py`、`spec_ux_anchors.py` 原来是 `print` 直出。

所以第八轮**动工具的只有四处**：`ppsum.py` 与 `spec_ux_anchors.py` 加逐行 `ts=`（后者的 `P()`），旧 shell 换成 Python 工装 `g1g3.py`（逐行 `ts=` + `IDENT` + `SRC_TREE` + `RES_HIT … DEAD／REFERENCED`），再加成对复核用的 `rerun_diff.py`（把「复跑逐位一致」落到字段级，见 `ev_r8_rerun_diff.txt`）；`fs115.py`／`ac10_offline.py`／`ppwin.py`／`drive.py` 本轮 `git diff 1a2727a HEAD` **0 行**，只是按原口径**重跑成 `ev_r8_*`**。逐文件覆盖情况写进报告 §11.1 的表，**不再写整轮 blanket 断言**。核验口径：`grep -vE '^#|^[[:space:]]*$' F | grep -vc 'ts=[0-9]\{4\}-'`（`#` 头/空行豁免；adb 的 `INSTALL out=Performing Streamed Install` 这类透传行要在文件里点名）。


## 脚本

| 文件 | 用途 |
|---|---|
| `drive.py` | 测量底座：ime inset frame、键帽行检测、候选带列剖面互相关位移、宿主文本真值、软键坐标表、联系人/设置宿主拉起 |
| `fs115.py` | **spec AC-1 恒高 + 截字 + spec-AC-10**（第五轮重写版，第六轮解耦闸门）。出数前只守规格 `0e4eeb0:543-54425` AC-10 的三条 **Given**：键位反推成功、宿主回读==所敲串（=Given ① 的唯一通道）、chip 盒在位且在候选区左、**候选行在位（`cand_count ≥ 1`，不是 chip-only）**、池内 ≥8、滚动后回原点；全过才打 `GATES`→`RESULT`→`INK`→`CAND`→`AC10`→`AC10b`，否则只打一行 `GATE reason=… rc=…` 且 **stdout 不出现 RESULT/INK/delta**。**「可见候选 ≥4」不是闸门**（裁决 §9.2）：它是 AC-10a 的判据，塞进闸门就等于让 AC-10 只会「不给数」、永远出不了 FAIL。第 5 位 `label`（默认 `a`）进截图名，防止两个包同名帧互相覆盖 |
| `ac10_offline.py` | 对**已入库的帧**离线重算 AC-10（无 adb）。判定式与 `fs115.py` 共用同一份 `band.ac10_rows`，所以设备当场读数与交付帧重算必须逐字段相同 —— A 包 `a6_520_fs1.15_*` 两帧已核过（`ev_r6_ac10_offline.txt` ↔ `ev_r6_ac10_decoupled.txt`）。离线读不出的 Given ①③ 逐行打 `carried_from_device` + 出处文件，不冒称当场重测 |
| `ppwin.py` | 键盘占比 pp 的**同窗对照**：一个连续窗口内先把搜狗各档量完、再量 iSpeech（两侧都在敲完 `shijian` 的组合态读 `type=ime` inset）。`--panel 1260x2720` 覆盖面板几何（**打回项 2 的目标真机形状**）、`--tiers 520:1.0,520:1.15` 选档、`--round` 防跨轮覆盖帧名、`--apk` 触发身份校验；退出时把 `font_scale` / `wm size` / `wm density` 还原成**入口读到的原值**，`TAIL` 行给 `font_scale_now` / `panel_now` 供复核 |
| `spec85_anchors.sh` | 规格 §8.5 那份「文档断言 → 代码行」清单（54 条）跑在任意 BASE 上：`bash spec85_anchors.sh fa57a07`。§12 引用的每一个代码行号都必须由它现算，不许手抄（裁决 §9.4 打回项 6） |

| `ppsum.py` | **pp 判据离线计算器**（第七轮打回项 2 补）：读 `ppwin.py` 那份 stdout，按**当档 dpi** 现算逻辑宽与档位，出 `PPSUM … delta_pp verdict=` ＋ `PPSUM_SENSITIVITY … 25/H`；缺任一侧读数 `GATE reason=one_side_missing rc=40`。为什么要它：`SUMMARY` 原来是人在文件头写的加法，那不算工具输出。`logic_w_dp` 不能用 `PANEL` 行那个值（它在 `wm density` 切换**之前**打，1260×2720 上会读成 458.2dp 而不是 387.7dp） |
| `band.py` | 候选带像素取证底座：底色/chip/item/主题色四组常量 + 连通块切候选项 + **角标圆片按外形剔除** + `first_item_left = chip 右沿 + 1`（字身只在它右边量）。为什么必须这样：`CandidateView.kt:76-77` 在候选列表为空时把 `scrollView` 置 `GONE`，整带宽里就只剩 chip 的墨迹——旧口径把「量 chip」当成了「量候选」 |
| `voidcheck.py` | 作废自查（裁决 §6-3 的自证）：用旧报告的窗口重扫交付帧，先逐位复现旧墨迹行，再用新口径数候选 → `x>240` 命中列=0 就证明旧数据确实还在量 chip。rc=0 = 三帧全部低于闸门（作废成立） |
| `ac9.py` | spec-AC-9 退格长按阶梯（200/1000/**1500**/2000ms）——被测面唯一读数是 `ev_r7_ac9.txt` **B 段**（200/1000/1500/2000 → 删 1/10/20/31，`rate_ms=200.0/100.0/75.0/64.5`）。两条硬规矩：退格键位从截图反推（不写死像素）；先做一次 200ms 短按**必须恰好删 1 字**，删不动说明点到的不是退格键 → `GATE reason=del_key_miss rc=20`，后面所有档位都是废数。长度一律 `hold()` 连读稳定值（批量敲键后紧接着 `uiautomator dump` 拿到的是旧文本，会把「删掉了」量成「没删」）。宿主文本一律用**已上屏中文**——组合缓冲里的退格走删 composing 的另一条路径，不是 AC-9 说的「删除字符」 |
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
| `d1regress.py` | **接管空格键（`35cdbfa`）的 6 档回归面**：档① 开关关=与修复前逐字节同（`--diff` 比两份 stdout 的宿主文本）、档② 英文态/T9 数字态、档③ 需要自己吃空格的宿主、档④ 长按语音（截图带差分，无日志可用）、档⑤ `repeatCount`、档⑥ `spaceProbe` 跨进程读成本。装错包 `rc=19`；基线包无开关键时 `SWITCH route=absent_means_default_off`，**不走设置页试点**（会误伤别的开关） |
| `spec_ux_anchors.py` | 规格行号重锚：报告引用的每条锚点在 `git show OLD` / `git show NEW` 两侧逐行比内容，出 `match=True` / `DRIFT` / `MISS`；**第八轮打回项 5 补了两件事**——① 被 VOCA-464 **改写**过的行（旧行文字在新 head 已不存在）不再走相似度匹配，改成 `RETIRED` 表里按锚点句正则现算新行号，输出 `verdict=REPLACED` ＋ 命中模式 ＋ 新旧原文 ＋ 作废理由；② 每行输出补 `ts=`（`P()` 统一打）。为什么必须有 ①：旧版把 pp 门限登记成 `("§6.5-pp", 599)`，而 `8324b03:599` 是 §5 无障碍表的「门禁结论」行，相似度 0.983 让匹配器**放行**了无障碍段里措辞相近的一句 → 报告 §5 与 `ppsum.py` 的 `criterion=` 一起被带偏（第七轮正文那个错行号锚就是这么生成的，订正过程与真锚见报告 §5.6）。跑法 `python3 spec_ux_anchors.py 8324b03 0e4eeb0 docs/keyboard-ux-spec.md` → `evidence/ev_r8_spec_ux_anchors.txt`（`SUMMARY 共 21 条 · 原样命中 21 · DRIFT 0 · MISS 0 retired_replaced=5`）|
| `g1g3.py` | **G1/G3 的资源面自查**（第八轮新增，替代旧 `g1_g3.sh`）：`aapt2 dump resources` 全量 31870 行 → 按 `page|pager|pagination|candidate_pager` 圈定面板自有资源 → 逐条回源码 grep 引用，出 `SCOPE` / `RES_HIT … status=DEAD|REFERENCED` / `G3_LAYOUT` / `VERDICT`。绑身份：`IDENT stage=0/1 … match=True`（比设备上的 `base.apk` 与本地构建产物）＋ `SRC_TREE head=78343a4 match=True`（代码 grep 跑在被测检出上，不跑在报告所在分支）；装错包 `rc=19`、apk 不存在 `rc=22`、dump 失败 `rc=21`。旧那份 `ev_r7_g1_g3.sh` 只 grep 源码、既不绑包也不查资源表引用，且整份无 `IDENT`、无 `ts=` → 降级为对照 |
| `pppanel.py` | **「搜狗键区固定 px」这条前提的可证伪检验**（第八轮打回项 1）：吃两份 `ppwin.py` stdout（同 dpi、不同面板），出 `PANEL_READ` ×2 → `GEOMETRY_STEP … sogou_change_pct=` → `HYPOTHESIS hyp=H1_spec_fixed_px / H2_proportional`（各自 predicted / err_pct / delta_pp）→ `SPEC9_VERDICT … verdict=`（固定 px 的误差 > 2% 门限即 `FALSE`）。离线纯算，不碰设备；某一侧在该 dpi 没读数 `GATE rc=40`、整份无帧 `rc=41` |
| `rerun_diff.py` | **成对复核同一链路的两份证据**（第八轮新增，打回项 3 要的「复跑逐位一致」落到字段级）：读两份 `ev_*.txt`，剥掉 `ts=` 后以 `(tag, code/dens/fs/idx/panel/step)` 为键对齐，把同名字段的差异分成三档 —— `JUDGE_DIFF`（判定量，出现即说明复跑翻了结论，`rc=42`）／`NOISE_DIFF`（`chip_box.n`、`SCROLL.err`、`shot` 这类抖动与命名）／`SNAPSHOT_DIFF`（`PANEL.requested`、`orig_font_scale` 这类入口快照，跑序不同就不同，不进判据但要单独点名，不许并到噪声里吞掉）；四对的当场输出在 `evidence/ev_r8_rerun_diff.txt`（`judge_diff_fields=NONE` ×4）|
| `pushform.py` | **推送形态自证**（第八轮新增，打回项 4(a) 的引用口径）：`git ls-tree -r --full-tree` 两边逐路径比 blob（刻意排除 `dist/…1.5-20261115-1004.apk` 与 `tools/verify/__pycache__/*.pyc`）、`git ls-remote` 现算远端 head、`git diff --name-only` 比两侧改名清单是否一致，再吐 `PUSHFORM`／`CUMULATIVE`／`REMOTE_HEAD`／`MAX_BLOB`／`SUMMARY`（任一条不等 → `rc=41`）；每数据行带 `ts=`，末尾附第三人可逐字复制的 `diff <(git ls-tree …)` 现算命令 —— 一份证据不可能自证携带它的那次提交，所以这里给的是尺子不是结论。当场输出在 `evidence/ev_r8_pushed_shas.txt` |
| `run_r7_all.sh` / `run_r7b.sh` / `run_r7c.sh` / `run_r7d.sh` / `run_r7e.sh` / `run_r7f.sh` / **`run_r8_all.sh`** | 第七轮的分串编排：每档单独 stdout + `# rc=`，一档跑挂不拦后面。`r7c` 是「`drive.log` 打上逐行 `ts=`」之后的重跑串，正文 AC-4~7 / AC-1 / D2 的数字以它为准；`r7d`/`r7e` 补 `paths.py`、`ac2`（两条路径）与目标几何 pp ＋ 空格 6 档；**`r7f` 是 AC-9 的唯一采信串**（`r7c` 里那一档吃 `key_map_fail rc=16`，见上面「套件之间会污染键盘态」那条）。**`run_r8_all.sh` 是第八轮的小轮串**：不重测产品面，只把第七轮被判「数据行无 `ts=` / 整份无 `IDENT`」的链路重跑成 `ev_r8_*`（g1_g3 → pp_window 1080×2340 三档两侧 → ac10_1080 → ac10_target_geom → fs115_matrix 8 组），截图换 label（`A8`/`T8`/`R8FS`）不覆盖第七轮帧；`ppsum.py`／`pppanel.py`／`spec_ux_anchors.py` 三条是离线现算，写在报告 §10|

## fs115 / ac9 / d2check 的退出码（拒绝出数时看这个，别看有没有数字）

| 脚本 | `reason=` | rc | 含义 |
|---|---|---|---|
| `fs115.py` | `readback_mismatch` | 10 | 宿主回读 != 所敲串（键漏了/打错位置）。第六轮 520 档 `dashu` 首跑就吃这条（`got=<NONE>`，共享 AVD 掉焦点），重跑才出数 —— 两次尝试都入库 |
| `fs115.py` | `no_chip_box` | 11 | **连 chip 的填充盒都没画出来**（`band.analyze` 的 `no_chip_fill`）= 帧不对/带没在位，跟「候选为空」是两件事 |
| `fs115.py` | `chip_not_left` | 12 | chip 盒不在候选区左侧（帧不对/带没画出来） |
| `fs115.py` | `chip_only` | 13 | **有 chip、候选 0 条 = chip-only 伪装态**（Given ② 不成立）。`MIN_CAND` 第六轮从 4 降到 1，但这一档**不许删**：`band.py:113/188` —— `cand_count==0` 的帧照样 `ok=True`，删了它 chip-only 会一路走到 `pool_low`（rc=14），失效原因从「量错对象」变成「词库不够」，第三人照 §10 复跑必读错（裁决 §9.2-1）。11 与 13 的语义必须分开：11=连 chip 都没有，13=有 chip 无候选 |
| `fs115.py` | `pool_low` / `not_at_origin` | 14 / 15 | 池内 < 8 条 / 滚动后没回到原点 |
| `fs115.py` | `ime_not_ours` | 17 | 当前 IME 不是本包（前后各读一次，任一端不符即拒绝） |
| `fs115.py` | `key_map_fail` / `host_not_ready` | 16 / 18 | 键位反推失败（VOCA-184 后曾把两行粘成一行）/ 宿主起不来 |
| `ac10_offline.py` | `frame_not_in_pr` / `no_chip_box` | 20 / 11 | 帧文件不在 `shots/`（离线没有第二次机会）/ 连 chip 盒都没找到 |
| `ac9.py` | `del_key_miss` / `fill_short` | 20 / 21 | 200ms 探针删不掉 1 字（点错键）/ 填字没填满目标长度 |
| `dsdump.py` | `run_as_unavailable` | 21 | `run-as` 或路径真的不对（与「文件不存在=默认值」严格区分） |
| `voidcheck.py` | — | 22 | 有帧未低于旧口径闸门 → 作废结论需复核（0 = 作废成立） |
| `ppsum.py` | `one_side_missing` | 40 | 那份 ppwin stdout 里 sogou 或 iSpeech 一侧的 `FRAME` 读数缺失 → 不出 Δpp 判据（0 = 两侧齐、`PPSUM` 已出） |
| `d2check.py` | `card_not_found` / `ime_not_ours` / `keyboard_not_shown` | 2 / 17 / 19 | 卡片没找到 / IME 被抢 / 键盘没弹出（后两条 = 不许报 FIXED） |
| `d2check.py` | — | 0 / 1 | 0 = 未崩（修复成立）；1 = 崩了并附 `STACK` 行 |
| `d1regress.py` | `installed_apk_not_this_build` | 19 | 装着的包不是 `QA_APK` 那一个（逐条目 dex/arsc sha256 不等）→ 整档作废 |
| `d1regress.py` | `switch_stuck` / `password_host_unreachable` / `repeatcount_not_injectable_on_emulator` | 33 / 0 / 0 | 开关点了不生效 / 没有可用密码宿主（如实拒绝出数）/ 注入键造不出 `repeatCount>0`（转 JVM 单测，见报告 §7） |

## 共享 AVD 的四个坑（本轮踩过，脚本已内置防护）

1. **设备里可能留着别人更高 versionCode 的包**：`adb install -r -g` 会 `INSTALL_FAILED_VERSION_DOWNGRADE`。
   一律用 `install -r -d -g`（`-d` 允许降级），降级后用 `classes*.dex` + `resources.arsc` 逐条目 SHA256
   证明装的就是当轮被测 head 的产物（第七轮 = `78343a4`） —— 整包哈希不可比，所以身份校验不能省。
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

5. **套件之间会污染键盘态：跑 AC-9 之前必须先看一眼 `key_map()`**（第七轮实测踩到，报告 §9-20）。
   `d1regress.py` 的档②③ 会把输入法切进英文态 / T9 数字态并留在原地，紧接着跑 `ac9.py` 就是
   `GATE reason=key_map_fail rc=16`（`runs` 变成 `[8,8,8,7,1,1]` 或 `[10,9,5,1]`，反推不到 10 键行）。
   复位：`adb shell pm clear com.ispeech.ime` + `adb shell ime set com.ispeech.ime/com.ispeech.input.IspeechImeService`
   + 重新起宿主；或按 `run_r7f.sh` 的做法：先 `pm clear` + `ime set`，再用 `drive.log` 打 `HOST`/`PRECHECK` 证键位，才跑阶梯。**别照抄 `run_r7c.sh` 的跑序**——
   那一版的 AC-9 就是这么吃 rc=16 的，`ev_r7_ac9.txt` 的 A 段是失败原文（无读数），B 段才是复位后的采信读数。

## 测量纪律（违反就不可比）

1. 测前 `settings put secure autofill_service null`，否则自动填充会多报一个 `type=ime` InsetsSource。
2. 位移一律用硬件 `-`/`=`（198px=72dp 一档，可复现）；触摸 fling 位移不可复现，禁止作为判据。
   **键名↔方向不靠人记**：`IspeechImeService.kt:620-622`（行号锚当轮被测 head `78343a4`；旧 head 上是 `:612-614`，main `fa57a07` 上是 `:601-603`）定死 `KEYCODE_MINUS`(69) → `scrollCandidates(-1)`、
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

## AC-10 的四条读数规矩（第六轮立三条，第七轮加第四条；违反其中任一条 = 该轮 AC-10 结论不作数）

裁决 §9.2 / §9.4 风险 3(a) 把这三条钉死，实现都在 `band.ac10_rows()` 里（设备与离线共用）：

1. **`≥4` 是 `full_count` 的门限，`≥8` 是池的门限，不可互换。** 池 ≥8 是 AC-10a 的**可判性来源**：
   词库里明明有 ≥8 条、屏上凑不满 4 条完整可见 = 横向空间不够 = **FAIL 本身**，不是「不给数」的理由。
2. **每一帧都要出 verdict**（两档 × 两码 = 4 帧，一帧不落），而且**不许看完结果再挑样本**：
   同一批码在 440 档当 AC-10a 样本判过 PASS，到 520 档就得同样判，不能以「这码是长词、属 AC-10b」摘出去。
3. **双口径并列，分歧不许测试自挑。** `AC10` 行同时给规格采样区口径（`x_end = 1080 − paddingEnd`，
   规格 `:529` 写死、内缩不放开）与屏宽口径（右界 1080）：`verdict=` / `verdict_screen=`，
   两者不一致时 `boundary=True` → 那一档单列交洞察BA 复核。第六轮 8 帧全部 `boundary=False`。
   `AC10b` 行永远跟着出（`recorded=True reason=…`，reason ∈ `meets_threshold` / `viewport_clip` / `long_word_budget`），
   它是「为什么不足 4 条」的归因，**不是免判通道**。

4. **档位绑定几何，不许拿模拟器默认面板去套档1 的硬门限。** 逻辑宽 = 面板宽 ÷ (dpi/160)，档界 `band.TIER_SPLIT_DP = 371.0`（规格 `0e4eeb0:560`，只有一份）：1260×2720@520 = **387.7dp → 档1**；1080×2340@440 = 392.7dp → 档1；1080×2340@520 = **332.3dp → 档2**——现实机型里没有这种面板/dpi 组合，它是模拟器强行改 dpi 造出来的档位。每行 `verdict` 必须带 `tier=`；**档2 在人类签字（R21）之前只能记「未签字」，不得写 PASS** —— `band.py:262-264` 的实现是：档2 且按 `tier_need` 判到 PASS 时改打 `tier_verdict=NOT_SIGNED(AC12_unmeasured+R21_open)`（同行 `r21_signed=False`），报告引用时**必须抄这个原文标签**（第八轮订正：以前写的 `AWAITING_SIGNOFF` 是报告自造的字样，脚本从未输出过）；`band.R21_SIGNED = False` 时脚本不会自己变绿。

## 第七轮按 SRE 五点修的工装（不修这五点，目标几何上的 pp / AC-10 根本拿不到数）

1. `drive.py` 的 serial 改成读 `QA_SERIAL`，不再写死 `emulator-5554`；
2. `ppwin.py` 顶部 `W,H = 1080,2340` 只是**默认值**，`--panel` 传入即覆盖，`PANEL` 行把 `requested / panel / logic_w_dp` 三个都打出来，方便第三人核对档位是不是算错了；
3. `band.py` 的采样右界原来写死 `W = 1080`，改为按当场面板读——1260 面板上还按 1080 算，`full_count` 直接失真；
4. `bring_up()` 拉起的是 Settings 搜索页，不再是厂商设置页入口（那个入口在华为 ROM 上不存在，旧写法到真机必失败）；
5. 退出还原：`font_scale` 还原成**入口读到的原值**（不是硬写 1.0），`wm size` / `wm density` 同理。

代价记在账上：换被测包要 `pm clear com.ispeech.ime` 才能回到「没有 DataStore 键 = 开关默认关」的数据态；清完必须马上 `ime set` 并等 `host_ready()`，否则后一档全部读空。

## 集成候选（B 包）复跑（第七轮起作废，只作历史）

B 包 `cffc0cd` = 旧 head + `d3b6f84`(VOCA-170) + `d283706`(VOCA-184) + `4d6363c`(VOCA-207) 的本地三方合并。`78343a4` 已包含 VOCA-184/207 的内容（**VOCA-170 的 `d3b6f84` 不在**：`git merge-base --is-ancestor d3b6f84 78343a4` 不成立，`branch -r --contains` 只列出 `origin/fix/voca-170-keyboard-vm-init-order`，见报告 §3.3-D2），所以第七轮不再维护 B 包口径。它的读数（`ev_r5u_*`、`ev_r6_gradle_b*`）全部降为历史对照，配方与冲突清单仍留在报告 §13，不删。用它跑任何脚本时记得带 `label`（`fs115.py <apk> 440 1.0 shijian b`），否则会把上一轮的候选带裁图覆盖掉——第五轮就覆盖过一次（12 张同名帧）。

## evidence/ 与 shots/

`ev_*.txt` 为当场 stdout 原文（文件头带 `# 被测=` / `# cmd:` / `# rc=`；**逐行 `ts=` 的覆盖要按文件说**：设备侧 24 份 `ev_r7_*` 里 6 份成片缺（`ac10_1080`／`ac10_target_geom`／`d2_base`／`g1_g3`／`pp_window`／`ac9`）、7 份只缺 adb `INSTALL` 透传那 1 行，第八轮把其中 4 条链路重跑成 `ev_r8_*`（`d2_base` 是档② 基线侧那次中止、`ac9` 缺的是 `HOST`／`PRECHECK`／`chain_rc=` 三条工装状态行，两条都不在本轮重测面内，均已逐份点名），逐文件口径在报告 §11.1 的覆盖表，这里不另立断言），`gradle_test.log` 为单测运行日志，`shots/*.png` 为报告点名的截图（候选带裁图、面板态）。**当前规模（第八轮 `ls | wc -l` 现算）：`tools/qa/voca-37/` 下 24 个 `.py` + 10 个 `.sh`，`evidence/` 111 份（106 `.txt` + 4 `.xml` + 1 `.log`，其中 24 份 `ev_r7_*.txt` ＋ 9 份 `ev_r8_*.txt`），`shots/` 120 张（第八轮新增 34 张：`r8_*` 6 / `r5_A8_*` 8 / `r5_T8_*` 4 / `r5_R8FS_*` 16）**（以 `ls | wc -l` 现算为准，报告头部引用时同样现算，不抄写）。**每个文件是采信还是作废，逐条列在报告 §11.1 的「evidence/ 全量清单」**，不要在这里另立口径；作废值只留作环境争用的证据，不得引用其数字。

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

# 第六轮（打回项 1 / 2 / 3 / 6）三条，前两条要设备，第三条纯离线
python3 tools/qa/voca-37/ppwin.py --ime sogou --tiers 440:1.0,440:1.15,520:1.15              # → ev_r6_pp_window.txt 上半
python3 tools/qa/voca-37/ppwin.py --ime ispeech --tiers 440:1.0,440:1.15,520:1.15 --apk <构建产物 apk>   # 同窗下半，含 IDENT 自证
python3 tools/qa/voca-37/fs115.py <apk> 520 1.15 shijian,dashu a6                            # 解耦闸门后的当场出数
python3 tools/qa/voca-37/ac10_offline.py                                                      # 无 adb，对入库帧重算 → ev_r6_ac10_offline.txt
bash    tools/qa/voca-37/spec85_anchors.sh fa57a07                                            # §12 的代码行号现算 → ev_r6_spec85_main.txt

# 第七轮（打回项 1~4），被测面 78343a4、规格 0e4eeb0；先 export 再跑，脚本会把这两个值打进每份证据的头
export QA_HEAD=78343a4 QA_SPEC=0e4eeb0 QA_SERIAL=emulator-5554 QA_APK=<PR#18 构建产物 app-debug.apk>
bash    tools/qa/voca-37/run_r7c.sh                                    # AC-4~7 / vswipe / paths3 / paths2 / AC-9 / D2 / fs115 重跑（逐行 ts=）
bash    tools/qa/voca-37/run_r7d.sh                                    # paths.py + ac2 soft 路径
bash    tools/qa/voca-37/run_r7e.sh                                    # 补漏：ac2 keyevent + 目标几何 pp + 6 档（RAW/PER 行也要 ts=）
bash    tools/qa/voca-37/run_r7f.sh                                    # AC-9 专用：复位数据态 + 键位 PRECHECK + 200/1000/1500/2000 阶梯（→ ev_r7_ac9.txt C 段）
python3 tools/qa/voca-37/ppwin.py --panel 1260x2720 --tiers 520:1.0,520:1.15 --round r7t --apk "$QA_APK"   # 目标几何的 pp
python3 tools/qa/voca-37/ppsum.py /tmp/r7e_pp_target_geom.txt                          # 目标几何 pp 的判据由它算 → 落进 ev_r7_pp_target_geom.txt 的 B 段
python3 tools/qa/voca-37/d1regress.py "$QA_APK" --base "$QA_BASE_APK" --diff                # 接管空格的 6 档
python3 tools/qa/voca-37/spec_ux_anchors.py 8324b03 0e4eeb0            # 规格行号两侧逐行比 → ev_r7_spec_ux_anchors.txt
python3 tools/verify/check_crosscut.py                                 # 第 13 组（空格键回归面）rc=0
```
