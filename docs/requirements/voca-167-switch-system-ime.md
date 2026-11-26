# VOCA-167 需求澄清：切换到系统其他输入法

> 归属 issue：VOCA-168（父：VOCA-167）。角色：洞察BA。
> **R4（按质检专员评论 `01a1e4d6` 的打回项 1/2/3 返工，建议项 4/5 一并采纳）**。判定不变（A + D），本轮只改「取证挂在哪棵树上、出处是什么」：
> ① 用户装机包对应的那棵树更正为 **`eba7a61` = `origin/release/voca-186-v1.6` tip**（R3 用的 `a9d1dae` 上没有用户那个包）——两组行号并列、标明归属，见 §2.2；
> ② **撤回** R3 表里「本机模拟器 v1.6/6 = 8 键」那一行：我给不出该包的 SHA256 与来源 commit，而本轮扫全部 refs 可达的 383 个 commit，`versionCode=6` 的 13 个无一例外是 9 键、压根不存在「vc=6 + 8 键」的树；
> ③ 把「`72bb547` 不是任何设备上的安装包」这句我无法证伪的否定式断言，换成有出处的表述：**上一代 v1.5 的键面确实以 `dist/ispeech-release-v1.5.apk` 发行过**（8 键、第 1 键「⌨输入法」），README 那句话对这一包成立、对用户装的 v1.6 包不成立——这条**补强 A**，并改写 §6 两问的判读。
> 取证基线：`fa57a07` = `origin/main` tip（8 键 / v1.5·5）与 `eba7a61` = `origin/release/voca-186-v1.6` tip（9 键 / v1.6·6，用户机上那一包的字节就提交在这棵树）。R3/R4 机检与逐行对读实际跑在本地基线 commit `4995892` 上——它与 `fa57a07` **只差 `dist/ispeech-release-1.5-20261115-1004.apk` 一个二进制**（`git diff --stat 4995892 fa57a07` → `1 file changed`），源码与文档逐字节相同，所以引用时以 `fa57a07` 为准、`4995892` 只是我的跑数载体。

## 1. 一句话目标

让用户在智语键盘上**找得到入口、在列表里看到自己点名的那个输入法、并且切得过去**，
而不是以为「智语没有切换输入法这个功能」。

## 2. 需求判定：A（文档指位错）+ D（面板漏列，新缺陷）；B 未在 EMUI 排除；C 前提被证伪

### 2.0 先钉死「用户手上装的是哪个包」——R1/R2/R3 反复的根因

前提撤回记录：R2 的「装机版 = `72bb547`」（R3 撤）、R3 表里「本机模拟器 v1.6/6 = 8 键」那一行与「`72bb547` 不是任何设备上的安装包」这句否定式断言（本轮 R4 撤）。
**判据只有一个：以安装包字节为准。** 下表前两行是「有字节的」，第三行是有字节但来源不明、不进真值表的。

| 安装包（按字节定位） | 仓库里的位置 | `versionCode` / `versionName` | 实际工具栏 | `SWITCH_IME` 位置与键面 | 证据（本轮我自己跑的） |
|---|---|---|---|---|---|
| **① 用户手机现在那一包**（华为 ALN-AL00 `FMR0224913042917`，`lastUpdateTime=2026-11-15 10:29:20`） | `eba7a61:dist/ispeech-internal-v1.6.apk`（blob `6607aaff`，SHA256 `9cddcec5…43f42`；`git log --diff-filter=A` → 该 blob 的入库点是 `199b972`，tip 未改动它） | **6 / 1.6** | **9 键** | **第 3 键「输」** | 本轮从 release tip 导出后 `shasum -a 256` = `9cddcec5a88d53017c5680eb07ba52e643d523d273f5d3649180df44f8d43f42`（与 tip 的 `dist/SHA256SUMS` 第一条一致）；`aapt dump badging` → `versionCode='6' versionName='1.6'`；`dexdump -d classes3.dex` 的 `toolbarKeys` 常量序 = `短语 AI 输 中/英 符 ☺ 剪贴 语音 ⚙`，`SWITCH_IME` 是第 3 个、键面「输」。**设备侧那一行 SHA256 由质检专员在真机取证（评论 `01a1e352`）记录为同一串**——包字节已经替真机作了证，R3 那句「本轮没连到该真机，只能按引用记录复核」的免责可以去掉：仓库侧这半边我本轮独立复现了，剩下「设备上装的就是这一包」仍引用质检记录。 |
| **② 用户手机上一代那一包**（VOCA-159 记录 2026-11-14 12:28 覆盖安装 v1.5 重签版） | `fa57a07:dist/ispeech-release-v1.5.apk` == `eba7a61:dist/_archive/ispeech-release-v1.5.apk`（**同一 blob `a8a95926`**，SHA256 `490b152a…`） | **5 / 1.5** | **8 键** | **第 1 键「⌨输入法」** | 本轮实跑：`git rev-parse fa57a07:dist/ispeech-release-v1.5.apk` 与 release tip 的归档副本同 id；`aapt` → `versionCode='5' versionName='1.5'`；`dexdump -d classes3.dex` 常量序 = `⌨输入法 中/英 符 ☺ 短语 剪贴 AI ⚙`，`SWITCH_IME` 第 1 个、键面就是「⌨输入法」。源码侧一致：`72bb547:…SoftKeyboardView.kt` 的 `toolbarKeys()` = 8 键、`KeyDef.fn(KeyAction.SWITCH_IME, "⌨输入法", weight = 1.6f, …)` 在第 1 项。 |
| ③ 本机 AOSP 模拟器 `emulator-5554`（R3 把它记成「v1.6/6 + 8 键」） | **给不出来源** | 我 R3 读到的 `dumpsys` 是 1.6/6 | 截图 8 键 | 第 1 键（键盘图标） | **R4 从真值表删除，只作警示保留**：本轮 `adb devices` 为空，我无法导出当次那个 APK 算 SHA256、也指不出它属于哪棵树。可核查的反证是：本轮扫全部 refs 可达的 383 个 commit，`versionCode=6` 的 13 个**无一例外 9 键**，8 键的 141 个树全是 `versionCode=5 / 1.5`（含 main tip `fa57a07`）——不存在「6 号 + 8 键」的树。所以那一行只能是一次**未署名的本地构建**（改了 gradle 版本号没提交），或我把 `dumpsys` 里别的 version 行读进了 grep。 |

**结论（写进验收口径）**

1. **键位只认包字节**：判「用户手上那个包的键位」有两条可信路——① 拿到那个 APK，`aapt dump badging` + `dexdump` 读 `toolbarKeys`；② 记下该 APK 的 SHA256，回查它是哪棵树产出的。`versionName` / `versionCode` **不能**用来定位键位：本轮扫全部 refs 可达的 **383 个 commit**（逐个读 `build.gradle.kts` 的 `versionCode` 与 `toolbarKeys()` 的键数），分布是 `(vc=5, 8 键) 141` / `(vc=5, 9 键) 228` / `(vc=5, 7 键) 1` / `(vc=6, 9 键) 13`——**同一个号对应两种键面，且不存在「vc=6 + 8 键」的树**。号相同、键盘可以不同，号不同也不能反推键位（② 就是 5 号 8 键、① 是 6 号 9 键）。应用内自述更不可信（§2.6 那条真 FAIL：包 1.6、设置页写 v1.5）。
2. **用户手机上的两代包各自可定位，README 描述的是上一代**：v1.5 那一包 = 8 键、第 1 键「⌨输入法」；v1.6 那一包 = 9 键、第 3 键「输」。**README/dist-README 的「工具栏第 1 键『⌨输入法』」对 ① 是错的、对 ② 是对的**——这不是「文档凭空写错」，而是文档停在上一代包、装机包跨了代。这条**补强 A**，并直接改变 §6 两问的判读：用户回忆的「第 1 键」可能来自 11-14 那版，不能据此判他「点错键」。
3. **两条线自 `76d178c`（11-10）分叉**：`git rev-list --left-right --count origin/main...origin/release/voca-186-v1.6` = `10 7`；release 侧 7 个提交里只有 `a9d1dae` 动过 `app/`（`versionCode` 升 6），`…/SoftKeyboardView.kt` 一次没动（`git log origin/main..origin/release/voca-186-v1.6 -- app/src/main/java/com/ispeech/input/SoftKeyboardView.kt` 为空）。项目经理评论 `01a1e4b6` 已拍「验收真值 = `origin/main`」——所以 AC-1 必须**按被验收的那个安装包分别出结论**（§2.2 两张表 + 行号归属表），而 M5 出包必须用当前 main 重新打，否则「以 main 为真值的验收」全绿而用户照样点错。

### 2.1 判定表（R3）

| 判定 | 结论 | 依据（逐条给出处） |
|---|---|---|
| **A 入口不可发现（文档 / 交互）** | **成立**（在被安装的那个包上有硬证据） | 用户装机包对应的树 `eba7a61`（release tip）上：`README.md:23`「工具栏**第 1 键**弹出输入法列表」、`README.md:184` 自测第 7 步「工具栏**第 1 键**『⌨输入法』」、`dist/README.md:61` 同句（随包「取包说明」，装机人第一眼读它）、`README.md:19/20` 键序把 `⌨` 写在第 1 位；而**同一棵树**的代码 `SoftKeyboardView.kt:315` 第 1 键是「短语」（`PANEL_QUICK_PHRASE`）、输入法切换在**第 3 键「输」**。照 README 自测的人第 1 下按下去开的是**快捷短语面板** → 「这功能不存在」。更深一层：这套指位对**上一代 v1.5 包**（`dist/ispeech-release-v1.5.apk`，8 键、第 1 键「⌨输入法」）是对的——**文档停在上一代、装机包跨了代**，所以 A 不是「有人写错一句话」而是「出包分支没回追主干、文档没随包校正」。详见 §2.0 结论 2 与 §2.2 |
| **D 面板漏列已启用输入法（新缺陷）** | **成立**（本轮 AOSP 复现，见 §2.3） | 4 个输入法处于 `enabled_input_methods`，面板只渲染 **2 行**并显示「共 2 个」；同一时刻系统自带选择器列出 **4 项**（含 Gboard、搜狗输入法）。也就是说：**用户即使找对了键，也看不到搜狗五笔**——这与「没启用」无关，与「切换失败」也无关 |
| **B 真机切换失败（EMUI 拦截）** | **未排除** | 本轮在 AOSP 上切走成功（§2.5），但 AOSP 不能外推 EMUI；`setInputMethod` 在华为 ROM 有拦截先例（代码自己就为此写了兜底与「系统不允许切换输入法」文案，`IspeechImeService.kt:1435/1444/1450` @`fa57a07`、`:1528/1537/1543` @`eba7a61`）。取证仍挂 VOCA-228 第 5 项（该 issue 当前 `blocked`） |
| **C 想要新行为（把未启用的也列出来并引导启用）** | **前提被证伪，降级为无证据** | R2 主判 C 的唯一依据是「搜狗 / 千问可能没在系统里启用，所以列表里没有」。真机 `settings get secure enabled_input_methods` **两项都在**（质检记录），所以「未启用」不成立；而本轮发现的 D 给出了**同一个症状的另一种解释**，且不需要假设用户诉求变了。**父单原文点名搜狗五笔 / 千问语音，样本 = 1 条需求描述，属假设、无数据支撑**——C 不再作为主判定，也不为它开实现单 |

> 判定口径：**A 解释「找不到 / 找不到入口」，D 解释「找到了但列表里没有我要的」。**
> 两者都成立，所以本件不再在 A/B/C 里三选一——原 issue 给的三分法本身不完备，缺的正是质检专员打回项 3 要求补的那一支。

### 2.2 A 的证据：发行包 README vs 该包代码（按包分别出结论，打回项 2）

**结论 1 — 在 `fa57a07`（= `origin/main` tip；我跑数用的本地基线 `4995892` 与它只差一个二进制、文档与源码逐字节相同，8 键 / v1.5·5）上：文档与代码一致，无硬错。**

| 位置 | 文档写的 | 该树代码 | 判定 |
|---|---|---|---|
| `README.md:14` | 工具栏（8 键）：输入法切换 · 符号 · 表情 · 快捷短语 · 剪贴板 · AI · 语音 · 设置 | `SoftKeyboardView.kt:316` 同序 | 一致 |
| `README.md:15` | 键序 `⌨ 符 ☺ 短语 剪贴 AI 🎤 ⚙` | 同序 | 一致 |
| `README.md:18` / `:190`、`dist/README.md:24` | 第 1 键 = 输入法切换 / 「⌨输入法」 | 第 1 键 = `SWITCH_IME`（键面「⌨」+ `ic_ime_switch`） | 指位一致（「⌨输入法」是说明性称呼，键面实为「⌨」） |
| `docs/t9-and-symbols.md:74` / `:120` | 第 1 键 `SWITCH_IME`（键盘图标） | 同 | 一致 |
| `docs/t9-and-symbols.md:149-153` | 内嵌摘录：`SoftKeyboardView.kt:262-285` 8 键、`SWITCH_IME` 第 1 键、键面「⌨输入法」、`weight = 1.6f` | 实际 `:316`、键面「⌨」、`weight = 1f`、`iconRes = ic_ime_switch`；`check_t9.py` 断言 8 键权重 `[1.0,1.0,1.0,1.3,1.2,1.1,1.1,1.0]` | **不符**（行号 / 键面 / 权重三处过期） |

**结论 2 — 在 `eba7a61`（= `origin/release/voca-186-v1.6` tip，用户装机包那一棵树，9 键 / v1.6·6）上：1 条一致、5 处指位与键序硬错、1 处内嵌摘录过期、3 处软差异。**

| 位置（tip 树） | 文档写的 | 该树代码 `SoftKeyboardView.kt:315` = `短语 AI 输 中/英 符 ☺ 剪贴 语音 ⚙` | 判定 |
|---|---|---|---|
| `README.md:19` | 工具栏（9 键）：**输入法切换** · 中/英 · 符号 · 表情 · 快捷短语 · 剪贴板 · AI · 语音 · 设置 | 键数对、**顺序错**，切换键被写到第 1 位 | **不符（键序错）** |
| `README.md:20` | 键序 `⌨ 中/英 符 ☺ 短语 剪贴 AI 语音 ⚙` | 同上 | **不符**：这是**上一代 v1.5 包**的顺序（`⌨输入法 中/英 符 ☺ 短语 剪贴 AI ⚙`）后面补插一个「语音」，两代代码都对不上——README 是跨代没校正的补丁 |
| `README.md:23` | 工具栏**第 1 键**弹出输入法列表 | 第 1 键 = `PANEL_QUICK_PHRASE`（快捷短语）；`SWITCH_IME` 在**第 3 键** | **不符（指位错）** |
| `README.md:184`（自测第 7 步） | 工具栏**第 1 键**「⌨输入法」→ 列表… | 同上 | **不符（指位错）**——照着自测必然走进短语面板 |
| `dist/README.md:61` | 工具栏**第 1 键**「⌨输入法」→ 输入法列表面板 + 系统选择器兜底 | 同上 | **不符（指位错）**，且这条在随包「取包说明」里，是装机人第一眼读的那句 |
| `docs/t9-and-symbols.md:75` | **第 3 键** `SWITCH_IME`，键面固定「输」 | 同 | **一致** |
| `docs/t9-and-symbols.md:117` | 工具栏第 3 键（**键面显示 EN / 拼 / 五**）；「键面文字跟随当前输入态」 | 指位对；键面固定「输」，不随输入态变 | 软差异，且与同文 `:75`「键面固定『输』」**自相矛盾** |
| `README.md:204`（v1.5 真机实测记录） | 「工具栏『⌨输入法』」→ 面板列出 iSpeech + Google Voice Typing，点后者切走成功 | 键面「输」 | 软差异（历史记录，描述的是 v1.5 那一代；不能当当前包的自测凭据） |
| `dist/README.md:72` | 「…或直接在键盘工具栏点『⌨输入法』」（不指位） | 键面「输」 | 软差异（说明性称呼与键面不符） |
| `docs/t9-and-symbols.md:145-149` | 内嵌摘录「`SoftKeyboardView.kt:262-285` —— 8 键工具栏」、`SWITCH_IME` 第 1 键、「⌨输入法」、`weight = 1.6f` | 实际 `:315`、9 键、第 3 键、「输」、`1f` | **不符（摘录过期：行号 / 键数 / 指位 / 键面 / 权重 五处）** |

**两组行号属于两棵不同的树（打回项 1 要求收敛，这里成对给出并标明归属）**

| 被引用的内容 | **用户装机包那棵树 `eba7a61`（tip）** ← 前端 / 测试照这组改 | 「版本号升 1.6」提交 `a9d1dae`（其上没有用户的包） | R3 文档当时用的 |
|---|---|---|---|
| README 键序行 | `:20` | `:15` | `:15` |
| README「第 1 键弹出输入法列表」 | `:23` | `:18` | `:18` |
| README 自测第 7 步 | `:184` | `:179` | `:179` |
| dist/README「第 1 键『⌨输入法』」 | `:61` | `:24` | `:24` |
| `dist/ispeech-internal-v1.6.apk`（= 手机上那一包） | **在**（blob `6607aaff`，98416118 B） | **不在这棵树上** | 未核对 |

行号整体 +5 的原因：tip 比 `a9d1dae` 多 4 个提交——`199b972` 动了 `README.md` 7 行（净 +5，正文因此整体后移 5）、把内测包与 `_archive/` 归档一起入库、并重写 `dist/README.md`（66 行）；`c05f9a9` / `1aa4a7d` / `eba7a61` 再连续往 `dist/README.md` 追加取包说明与门禁（+14 / +4 / +2，所以那句从 `:24` 移到 `:61`）。
**两棵树的 `app/` 逐字节相同**（`git diff a9d1dae eba7a61 -- app` → 空），所以代码侧结论（键序、`SoftKeyboardView.kt:315`、`enabledInputMethods()`、`<queries>`）在两棵树上通用；**只有文档行号与 `dist/` 内容必须按 tip 引**，这就是项目经理评论 `01a1e4b6` 与本单要收敛成同一组数的原因。
> 与质检记录的一处出入（不影响判定）：打回项 1 写该 APK「只在 `c05f9a9` 之后才存在」。按 `git log --diff-filter=A -- dist/ispeech-internal-v1.6.apk` → `199b972`，`git show --stat 199b972` → `dist/ispeech-internal-v1.6.apk | Bin 0 -> 98416118 bytes`；`c05f9a9` 动的是 `dist/README.md`（+14）与 `.claude/worktrees/voca-74` 删除。blob 在 `199b972` / `c05f9a9` / tip 三点完全相同（`6607aaff`），所以「用户包在 `a9d1dae` 上不存在」这一条成立，入库点提前一个提交。

> 同一棵发行树里 `docs/t9-and-symbols.md:75` 说第 3 键、`README.md:23/184` 说第 1 键——**两份文档互相矛盾，代码与 README 矛盾、与 t9 文档一致**，
> 加上 `check_t9.py` 在该树断言「第 1 键 = `PANEL_QUICK_PHRASE`」（`:725`）与「第 3 键 = 切换系统输入法，键面固定『输』」（`:730`），三方指向同一结论：**错的是 README 与 dist/README，不是代码。**
> 分类口径再更正一次（R3 已作废 R2 的「6 处文字描述可接受」，本轮按 tip 重述）：`README.md:184`、`dist/README.md:61` 明确写了「第 1 键」，在被安装的包上是**指位错误**，不是软差异。

### 2.3 D 的证据：面板只列 2 个、系统选择器列 4 个（本轮 AOSP 实测，附件截图）

设备 `emulator-5554`（Android 14 / API 34，1080×2340），包 `com.ispeech.ime`，当次 `dumpsys` 读到 1.6 / versionCode 6、键盘截图是 8 键。**那个包我署不了名**（§2.0 第 ③ 行已从真值表删除），但**这不影响 D 成立**，理由在下一条。

**D 与「装的是哪棵树 / 点的哪个键」无关**（R4 按打回项 2/4 补的口径）：

- 取数函数在两棵真值树上**逐字节相同**：`enabledInputMethods()` 函数体 md5 = `c8d6de7b8594541ffe242a480b278ca7`（`4995892`/main-line 与 `eba7a61`/release tip 两边一致）；`app/src/main/AndroidManifest.xml:11-15` 的 `<queries>` 两树也相同。所以无论当次那个模拟器包出自哪棵树，走的是同一份取数代码。
- 面板的行数与「共 N 个」标签**同源**：`InputMethodPanel.kt:200`（两树同一行号）
  `countLabel.text = if (items.isEmpty()) "" else "共 ${items.size} 个"`，紧接着 `:201 listContainer.removeAllViews()` 再按同一个 `items` 逐行填充。
  ⇒ 标签显示 2 就意味着**数据侧只有 2 项**，「列表没渲染全 / 滚动裁切 / 屏幕放不下」这几种解释在结构上不成立（若 `items.size = 4`，标签必先写「共 4 个」）。这条把 D 与「显示问题」分开，是打回项 4 要的补引。

```
$ adb -s emulator-5554 shell settings get secure enabled_input_methods
com.google.android.tts/…VoiceInputMethodService : com.sohu.inputmethod.sogou/.SogouIME :
com.google.android.inputmethod.latin/…LatinIME : com.ispeech.ime/com.ispeech.input.IspeechImeService
→ 4 项

$ adb -s emulator-5554 shell ime list -s          # 系统侧「已启用」同一份口径
（同上 4 项）
```

面板（工具栏输入法切换键 → 「切换输入法」；当次那台 8 键构建上第 1 键即此键）实际渲染：**「共 2 个」/「输入法 2 个」**，两行 = `智语`（带「当前」标签）、`Google 语音输入`。
**缺 `Gboard`（`com.google.android.inputmethod.latin`）与 `搜狗输入法`（`com.sohu.inputmethod.sogou`）** —— 附件 `v167-emu-panel-2of4.png`。
点面板里的「系统选择器」，系统自己的「选择输入法」对话框列出 **4 项**：`Gboard`、`Google 语音输入`、`搜狗输入法`、`智语` —— 附件 `v167-emu-syspicker-4.png`。

代码侧几乎没有任何过滤：`enabledInputMethods()`（`IspeechImeService.kt:1390-1407` @`fa57a07`/`4995892`；`1483-1500` @`eba7a61`）把 `manager.enabledInputMethodList` 全量收进来，
只有 `info.id ?: continue`（id 为空才跳过）一处守卫，没有按包名 / 类型 / 可见性做任何筛选。
**所以差异发生在框架返回给应用的那一步，不在智语自己的排序 / 过滤逻辑里。**

候选解释（**属假设，需架构师核实，不是本件给的实现方案**）：Android 11+ 的**包可见性**会按调用方可见范围裁剪 `getEnabledInputMethodList()`。
本机的清单里可见的两项恰好 = **智语自身** + **`com.google.android.tts`**，而 manifest 里唯一的 `<queries>` 声明的是 `android.speech.RecognitionService`（`app/src/main/AndroidManifest.xml:11-15`），
`cmd package query-services -a android.speech.RecognitionService` 在本机返回 `com.google.android.as` 与 `com.google.android.tts` 两个提供者、**不含 latin 与 sogou**（本轮实跑）。
即「可见集合 == 自身 ∪ RecognitionService 提供者」在本机上完全吻合，指向「缺 `android.view.InputMethod` 的可见性声明」这一类原因。
判别实验（谁做都行，只读）：在同一台模拟器上给 `<queries>` 加 `android.view.InputMethod` intent 后重跑面板，若行数从 2 变 4 即确认；不加就换一台真机对拍。**本件不改代码。**

> 为什么这条最重要：它是**唯一一个不需要问用户、也不需要 EMUI 真机就能解释「列表里没有搜狗五笔」的原因**，
> 而且在 AOSP 上已经复现。R1/R2 都漏了它，是因为两轮都只在「文档对不对」上取证，没有真的把面板打开、数一遍行数。

### 2.4 现状能力清单（逐条给出处，本轮逐条核过）

| 能力 | 在不在 | 出处（`fa57a07` main 树 / `eba7a61` release tip 双列，行号不同；代码在两树相同或等价） | 本轮实测 |
|---|---|---|---|
| 工具栏有输入法切换键 | 在 | `SoftKeyboardView.kt:316`（main，第 1 键「⌨」）/ `:315`（release，第 3 键「输」） | VOCA-167 取证截图：按输入法切换键即出面板（键位随包而异，见 §2.0） |
| 面板列出输入法 | 在，但**列不全** | `IspeechImeService.kt:1390-1407` / `:1483-1500` | 4 启用 → 2 行（§2.3） |
| 当前项置顶 + 「当前」标签 | 在 | `IspeechImeService.kt:1403-1405`（`compareByDescending{current}.thenBy{label}`）/ `:1496-1498`；`InputMethodPanel.kt:254`（两树同行号） | 截图可见 |
| 点击直接切走 | 在 | `IspeechImeService.kt:1435`（`setInputMethod(token,id)`）/ `:1528` | 切走成功（§2.5） |
| 失败回落系统选择器 | 在 | `IspeechImeService.kt:1444`（`showInputMethodPicker`）/ `:1537` | 选择器可弹出并列出 4 项 |
| 选择器也不可用时的提示 | 在 | `IspeechImeService.kt:1450`「系统不允许切换输入法」/ `:1543` | 未构造失败，未测 |
| 空态 / 只有一个输入法 | 在 | `IspeechImeService.kt:1413-1415`（「读不到输入法列表」/「没有其他输入法」）/ `:1506-1508`；`InputMethodPanel.kt:274` | 未测（要改系统启用项，本轮不动） |
| 面板另有「系统选择器」按钮 | 在 | `InputMethodPanel.kt`（onSystemPicker） | 截图可见并可点 |

### 2.5 切换路径本身可用（AOSP，本轮复跑，不再依赖 R1 的旧记录）

```
DEFAULT 切换前 = com.ispeech.ime/com.ispeech.input.IspeechImeService
（点面板第 2 行「Google 语音输入」）
DEFAULT 切换后 = com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService
```
→ `setInputMethod` 这条路径在 AOSP 14 上工作正常，**B 在 AOSP 上不成立**；EMUI 仍不能排除。
测试结束后设备状态已还原为 `DEFAULT=…latin/LatinIME`、`ENABLED` 不含 `com.ispeech.ime`（与本轮开始前的首次读取一致）。

### 2.6 机检脚本本身也是「按分支分裂」的（打回项 1/2 的连带后果）

| 树 | `check_t9.py` | `check_crosscut.py` | 说明 |
|---|---|---|---|
| `fa57a07` = `origin/main` tip（8 键 / v1.5·5；我跑数的载体是内容相同的 `4995892`） | `passed=87 failed=0 warned=1` | `passed=151 failed=0 warned=0` | `:69 EXPECTED_TOOLBAR_LABELS = ["⌨","符","☺","短语","剪贴","AI","🎤","⚙"]`、`:722` 断言第 1 键 = `SWITCH_IME` |
| `eba7a61` = `origin/release/voca-186-v1.6` tip（9 键 / v1.6·6，**用户装机包那棵树**） | `passed=91 failed=0 warned=1` | **`passed=150 failed=1 warned=0`** | `:69` 期望 9 键 `["短语","AI","输","中/英","符","☺","剪贴","语音","⚙"]`、`:725` 断言第 1 键 = `PANEL_QUICK_PHRASE`、`:730` 断言「第 3 键 = 切换系统输入法，键面固定『输』」 |

R4 按打回项 1 的重跑说明：这组数字 R3 是在 `a9d1dae` 上跑的，本轮改在 **tip `eba7a61`** 上重跑（`git archive eba7a61 | tar -x -C <tmp>` 只读导出后执行），**四个数字与 FAIL 文案一字未变**——`git diff --stat a9d1dae eba7a61` 只有 5 个文件（`README.md`、`dist/README.md`、`dist/SHA256SUMS`、新增内测包、`.claude/worktrees/voca-74` 删除），`tools/` 与 `app/` 两树逐字节相同。换树不换数，引用的树名以本表为准。

两条都要记进文档：
1. **同一个「绿」在两条线上含义相反**——main 的绿说第 1 键是输入法切换，release 的绿说第 1 键是短语。所以 AC-1 只能按「包」比对，不能按「仓库当前 HEAD」比对。
2. 发行线 `check_crosscut.py` 那条 **FAIL 是真的**（tip 原文）：`版本号不一致：app/build.gradle.kts versionName=1.6 但 app/src/main/java/com/ispeech/settings/MainActivity.kt ISPEECH_VERSION=1.5（设置页「关于 / 首页」会显示旧版本号）`。
   后果由机检直接钉死（就是上面那行 FAIL 原文）：装 1.6 的机器打开设置页，首页写「版本 v1.5」（`HomeScreen.kt:86` 用 `ISPEECH_VERSION`，`MainActivity.kt:16` 硬编码 `"1.5"`）——R3 在模拟器上肉眼见过同一现象，但那一轮的模拟器包我这次署不出来源（§2.0 行 ③），所以这条**只靠机检成立，不靠肉眼**。
   **用户自证「我装的是哪版 / 哪一个包」时，应用内版本号不可信**——这正是 R1/R2/R3 判定反复能持续这么久的原因之一；本轮进一步发现随包的校验清单本身也不可信，见 §8 第 4 项。收口见 §8。

## 3. 验收标准（5 条核心 + 2 条附加；每条先给一句话判据）

术语：`DEFAULT` = `adb shell settings get secure default_input_method`；
`ENABLED` = `adb shell settings get secure enabled_input_methods`（冒号分隔，条数记 `|ENABLED|`）。

### AC-1（核心）文档键位 == 用户那个安装包的实际键序

**一句话**：对**目标安装包对应的那棵树**跑一次键序比对，`README.md` / `dist/README.md` / `docs/*.md` 里凡是说「第 N 键」的指位必须与 `toolbarKeys()` 解析结果一致；main 与 release 两个结论都要写出来。

- **Given** 一份文档 + 一个**署了名的**被验对象：要么「安装包 SHA256 + 产出它的 commit/树」这条链闭合（当前两棵真值树 = `fa57a07`（main tip，对应 `dist/ispeech-release-v1.5.apk`=SHA `490b152a…` 那一代）与 `eba7a61`（release tip，对应 `dist/ispeech-internal-v1.6.apk`=SHA `9cddcec5…` = 用户机上那一包）），要么真机 APK 反编译出的键序。**不接受**「仓库当前 HEAD」，也**不接受**只给 `versionCode`/`versionName`（§2.0 结论 1）；`a9d1dae` 只是分叉后的版本号提交、其上没有用户那个包，不能当被验对象
- **When** 以**该树**的 `toolbarKeys()` 解析结果为唯一真值，逐条比对文档中的「第 N 键」「键序」「键面」与内嵌源码摘录
- **Then** 全部指位一致；出现「文档说第 1 键、代码第 3 键」这类指位错即 FAIL（本轮已知不符项见 §2.2 两张表；main 与 release **两个结论都要写出来**，行号按 §2.2 的归属表各引各的）
- 覆盖范围：① 文字描述的键序 / 键数 / 指位；② 文档内嵌的 `KeyDef.fn(...)` / `weight` / `iconRes` / `SoftKeyboardView.kt:NNN-MMN` 摘录（做不到同构就删摘录、只留引用）；③ 校验脚本自己的断言文案（现有一处过期：`check_crosscut.py:353` @`fa57a07` / `:355` @`eba7a61` 的 PASS 文案写「⌨输入法」，而当次键面分别是「⌨」（main）与「输」（release））；④ **随包文档里引用上一代包的「实测记录」段落**（`README.md:196-204` @`eba7a61` 标的是 v1.5 记录）要么标清所属包版本，要么删掉——否则它就是把人指回第 1 键的那句话
- **判据出处**：`tools/verify/check_t9.py`（`:69` 期望键序、`:720`/`:722` 键序与第 1 键断言 @main、`:722`/`:725`/`:730` @release tip、权重断言）；本轮两树实跑数字见 §2.6
- **键面文案**允许写「⌨输入法」这类说明性称呼，**指位与键数不允许**——这就是 AC-1 写成不变量而不是写死位置的理由（项目经理打回项 1）
- **今天还不能纯机检**：`check_t9.py` 一条断言都不读 `.md`（`grep -n '\.md' tools/verify/check_t9.py` 仅 1 处引用文案）。要落地需在工具栏段新增「文档指位 == 解析结果」断言；断言归测试工程师、文档归前端，洞察BA 只定判据

### AC-2（核心，新增 = 缺陷 D 的可测形式）面板行数 == 系统已启用输入法条数

**一句话**：打开「切换输入法」面板，列表行数必须等于 `|ENABLED|`，且 `ENABLED` 里每一个 id 都能在列表里找到对应行。

- **Given** 设备上 `ENABLED` 含 N 项（N ≥ 1），其中至少 1 项不是智语
- **When** 打开切换输入法面板
- **Then** 行数 = N、每个 id 都有行、面板「共 N 个」与 `|ENABLED|` 相等；**未启用**的输入法不出现（这是设计）
- **当前判 FAIL**：本轮 AOSP 实测 `|ENABLED|=4` 而面板 2 行（§2.3），系统选择器 4 项 → 缺陷 D 成立，转架构师 + 前端
- **取证口径（打回项 4 要求，缺一判「无法核实」）**：面板**同一张截图**里必须同时拍到 ① 右上角「共 N 个」标签 ② 全部列表行（列表可滚动时补一张滚到底的）③ 输入法标题栏「输入法 N 个」。因为 `InputMethodPanel.kt:200` 的标签与行同源（都取 `items.size`），标签里的 N 就是数据侧条数——这样「渲染裁切 / 没滚出来」无法被拿来当辩解。
- 取证命令（只读）：`adb shell settings get secure enabled_input_methods` + `adb shell ime list -s` + 上面那张面板截图；三个 N（`|ENABLED|`、标签、行数）必须同框可比

### AC-3（核心）点一行就切走，以 `DEFAULT` 前后变化判定

**一句话**：点列表中非当前行 T，3 秒内 `DEFAULT` 变成 T 的 id、面板关闭、键盘由 T 接管。

- **Given** 面板已按 AC-2 列出目标输入法 T（T ≠ 当前）
- **When** 点击 T 所在行
- **Then** `DEFAULT` 变为 T 的 id（字符串相等），且再次拉起键盘时是 T 的界面
- 本轮已实测通过（§2.5）；AOSP 上 1/1

### AC-4（核心）切不走必须有可见反馈，不得静默

**一句话**：`setInputMethod` 失败或未被系统接受时，必须弹出系统输入法选择器；选择器也弹不出时，候选条必须显示「系统不允许切换输入法」——两者都没有即 FAIL。

- **Given** 构造失败（测试桩让 `setInputMethod` 抛异常 / 返回后未生效）
- **When** 点击某行
- **Then** `showInputMethodPicker()` 被调用一次；若其也失败，`statusText` = 「系统不允许切换输入法」
- 代码支撑：`IspeechImeService.kt:1435/1444/1450`（main）/ `:1528/1537/1543`（release）

### AC-5（核心）只有一个输入法时的空态

**一句话**：`ENABLED` 只含智语时，面板显示「没有其他输入法 / 到系统设置 → 语言与输入法 里启用后，这里就能一键切换」，且「系统选择器」按钮仍可点。

- **Given** `|ENABLED| = 1` 且为智语
- **When** 打开面板
- **Then** 列表 0 行 + 上述空态文案可见 + 系统选择器可点；`statusText` = 「没有其他输入法」
- 代码支撑：`IspeechImeService.kt:1413-1415` / `:1506-1508`，`InputMethodPanel.kt:274`
- **未测**（需要改设备启用项，本轮只做只读取证），基线如实标「未测」

### AC-6（附加）ROM 拦截机型必须落到 AC-3 或 AC-4 之一

**一句话**：在 EMUI / MIUI 等会拦 `setInputMethod` 的 ROM 上，点非当前行后要么直接切走（AC-3），要么弹系统选择器并能从选择器切走（AC-4）；两者都不成立即判 B 缺陷，并附 `adb logcat -d` 原文。

- 取证归属：VOCA-228 第 5 项（当前 `blocked`）；**注意该 issue 标题写「已装 1.5/5」与真机 1.6/6 不符，且其设备 serial `23F0224224000950`（ALN-AL10 / Android 12）与质检取证的 `FMR0224913042917`（ALN-AL00）不是同一台**——需人类确认哪台是用户手机，否则 B 的取证对象是错的

### AC-7（附加）人工可发现性

**一句话**：只读 README 自测章节的用户，按第 7 步一次点中输入法切换键、无需试错其他键。

- 判据：qiaozhou 本人 1 次成功
- 现状：**FAIL（未测即已知会失败）**——发行包 README 第 7 步写「第 1 键」而该包第 1 键是短语（§2.2 结论 2）；AC-1 修完才有测的意义

## 4. 范围边界（明确不做）

- **不做**输入法管理页（启用 / 禁用 / 排序系统输入法）——引导到系统设置，不自建管理界面
- **不改**切换机制本身（`setInputMethod` + `showInputMethodPicker` 兜底在 AOSP 已实测可用）
- **不改**「默认输入法」之外的系统设置（不写 `enabled_input_methods`）
- **不申请新权限**：不引入 `QUERY_ALL_PACKAGES`。**注意口径**：AC-2 若确认是包可见性导致，修法是在 manifest 的 `<queries>` 里声明 `android.view.InputMethod` intent——这是**清单可见性声明，不是运行时权限**，与「不申请新权限」不冲突；但它属技术方案，**需架构师确认后再由人类拍**，本件不代拍
- **不做** C 类新行为的实现（列出未启用输入法 + 引导启用、单键在最近两个输入法间来回切）——R3 已把 C 降为无证据，**不为它开实现单**
- **不在本件改代码 / 文档**：BA 分支基线早于 8 键还原，在此改 `README.md` / `docs/t9-and-symbols.md` 会与 main 撞；改动归前端在收口分支上做
- **不做**签名 / 版本号策略调整（VOCA-159 §2 待人类拍板）

## 5. 成功度量

| # | 指标 | 基线（本轮实测） | 目标 | 观察窗口 | 采集方式 |
|---|---|---|---|---|---|
| M1 | 发行包文档键位一致率（AC-1） | main `fa57a07`：4/5 一致、1 处摘录过期（`t9-and-symbols.md:149-153`）；**用户装机包那棵树 `eba7a61`：1/10 一致 = 5 处指位与键序硬错（`README.md:19/20/23/184`、`dist/README.md:61`）+ 1 处摘录过期（`t9:145-149`）+ 3 处软差异（`t9:117`、`README.md:204`、`dist/README.md:72`）**（§2.2 两张表） | 两个包各自 100% 指位一致，且由 `check_t9.py` 机检 | 交付即验 | 对两树分别跑 `check_t9.py` + 按 §2.2 表逐行比对；报告必须写明每条属于哪个 SHA |
| M2 | 面板列表完整率（AC-2） | **2/4**（AOSP 实测：4 启用、面板 2 行） | `行数 / \|ENABLED\| = 1`，含搜狗、千问 | 下一次出包 | `settings get secure enabled_input_methods` 条数 vs 面板截图行数 |
| M3 | 真机外部输入法切换成功率（AC-3/AC-6） | AOSP 1/1；真机 0 次尝试 | 真机切到 ≥1 个外部输入法成功 | VOCA-228 取证 | `DEFAULT` 前后对比 + `logcat` |
| M4 | 用户自行找到入口（AC-7） | 未测 | 1/1 一次成功 | §6 问题 1 回执 | qiaozhou 回答 + 面板截图 |

## 6. 待人类确认（qiaozhou，2 个封闭问题）

[@qiaozhou](mention://member/238f192e-3aa9-4de7-8e98-2d267716a9e1) 只需要选两项，不用写长文：

1. **你点的是工具栏哪个键、点完看到什么？**
   - (a) 没找到入口（不知道按哪个键）
   - (b) 按了某个键，面板没弹出
   - (c) 面板弹出了，但里面**没有**搜狗五笔 / 千问语音
   - (d) 面板里有，但点了切不过去
2. **面板右上角写着「共 N 个」，那个 N 是几？列表里有没有「搜狗输入法」这一行？**（能附一张面板截图最好；没找到这个界面就答「没找到」）

> 已知的一半答案（**不用你回答，先记在这**）：你手机上「设置 → 语言与输入」里 **搜狗五笔和千问语音都已经是启用状态**——
> 质检专员只读取证 `settings get secure enabled_input_methods` 含 `com.sohu.inputmethod.sogou/.SogouIME` 与 `com.qianwen.ime/…VoiceInputMethodService`。
> 所以「没启用所以不出现」这个解释已经被排除，R2 的主判 C 作废。
>
> 答案怎么定走向（R4 按打回项 3 改判读口径：**不能拿「第 1 键」这个回忆当「用户点错键」的证据**）：
> - **先修正一条 R3 的隐含假设**：你 11-14 12:28 装的是 v1.5（8 键、**第 1 键就是「⌨输入法」**，见 §2.0 行 ②），11-15 10:29 才换成现在的 v1.6（9 键、切换在第 3 键「输」）。
>   所以如果你回忆「我按的是第 1 键」，那**在上一代包上是正确的按法**，只是这一代键位变了、文档没跟着改 → 归 **A**（文档 + 出包分支跨代没回追），**不归用户操作失误**，也不能据此判「面板没问题」。
> - 答 **(c)**，或 **问题 2 的 N 小于手机上启用的输入法数** → 落 **D**：面板漏列已启用输入法。本轮在 AOSP 上已经复现同一形态（4 启用 → 面板 2 行，且标签与行同源，见 §2.3），**属新缺陷，转架构师 + 前端**，不再归 C、不为 C 开实现单。
> - 答 **(a)** → 落 **A**：README 把「第 1 键」写成输入法切换（现在这个包第 1 键是短语），文档 + 键面醒目度一起修（交互部分交 UX）。
> - 答 **(d)** → 落 **B**：EMUI 拦截 `setInputMethod`，需要该机 `adb logcat -d` 原文（VOCA-228 第 5 项口径，并先确认是哪台设备）。
> - 答 **(b)** → 面板渲染问题，新缺陷，转前端。
> - 若回答自相矛盾（例如「能看到搜狗但只有 2 行」），以截图里的「共 N 个」标签为准（§2.3 的同源口径），文字描述让位于标签与行数。
> - 两个问题都不答也不影响开工：**A 与 D 各自都有独立证据**，可以先修；问题 1 只决定「先修哪个的优先级」。

## 7. 风险与假设（最可能推翻方案的三条）

1. ~~「main 与 release 哪条是真相」没拍板~~ **已由项目经理评论 `01a1e4b6` 拍定：验收真值 = `origin/main`。** 残留的不是「谁是真的」而是「装到用户手上的是谁」：用户那个包出自与 main 双向分叉（`10 7`，落后 10 个提交）的出包分支，所以
   **只要出包分支不回追 main，按 main 验收就全绿而用户照样点错**（§2.0 结论 3）。这条已进 M5 硬门禁；BA 侧的应对是 AC-1 强制**两树分别出结论 + 每条证据带 SHA**，避免下一轮再把行号引到错的树上。
2. **真机侧的键位我不能自己复跑，但已不需要复跑。** 用户手机上那一包的字节 = `eba7a61:dist/ispeech-internal-v1.6.apk`（SHA256 `9cddcec5…43f42`），本轮我用 `aapt` + `dexdump` 直接从仓库里的那颗 blob 读出 9 键 / 第 3 键「输」——R3 那句「§2.0 第 1 行只能引用质检记录」的免责作废（打回项 4）。**仍然只有设备侧那一半是引用**：「手机上装的确实就是这一包」依据质检专员 `01a1e352` 的 `dumpsys` + APK SHA 取证。
   另外 VOCA-228 跟踪的 serial 是 `23F0224224000950`（ALN-AL10 / Android 12），与质检取证的 `FMR0224913042917`（ALN-AL00）**不是同一台**——哪台是用户手机需人类确认；确认前，B 的取证对象存疑。
3. **D 的机制解释（包可见性）仍是假设。** 吻合度高（可见集合 == 自身 ∪ RecognitionService 提供者，§2.3），但我没有直接读进程内的 `enabledInputMethodList` 返回值；
   判别实验写在 §2.3，需架构师做。若机制不是包可见性，AC-2 的判据不变（行数 == `|ENABLED|` 仍是验收口径），只是修法不同。

## 8. 顺带发现（都只列事实与归属，本件不动手、不重复开单）

1. **发行线机检已有一条真 FAIL**：`check_crosscut.py` @`eba7a61`（release tip）→ `passed=150 failed=1`，
   失败项 = 「`app/build.gradle.kts versionName=1.6` 但 `MainActivity.kt ISPEECH_VERSION=1.5`（设置页显示旧版本号）」。
   后果：装 1.6 的机器打开设置页，首页写「版本 v1.5」（`HomeScreen.kt:86` + `MainActivity.kt:16`）——这一条由机检直接判出，不依赖我对模拟器那次的肉眼记录（那个包本轮署不出来源，见 §2.0 行 ③）。
   影响：用户自证版本时应用内版本号不可信（§2.0 的取证必须走包字节 + `dumpsys package`）。归属：前端 + 测试，v1.6 出包门禁内解决。
2. **设置页崩溃**：收口在 **VOCA-170**（`KeyboardViewModel` 属性声明晚于 `init` 导致 NPE，v1.6 门禁 AC-9），真机取证在 **VOCA-228 第 5 项**；
   修复 `d3b6f84` 仍在 **PR #9（`gh pr view 9` → `state: OPEN`）**，本轮复核：`git merge-base --is-ancestor d3b6f84 origin/main` → NO、`… origin/release/voca-186-v1.6` → NO，所以 main 与用户装机那棵树**都没有这条修复**，装新包后设置页仍可能崩并污染「切不过去」的复现。
   口径不变：**以 VOCA-228 真机栈为准，不另开第三张单**；提醒合并窗口核对 PR #9 是否真进了最终包（项目经理已把它列为 M5 硬门禁），别让「done」跑在「已合入」前面。
3. **设备清单不一致**：见 §7 假设 2，需人类在 VOCA-228 上更正 serial 与「已装 1.5/5」的标题描述。
4. **随包的校验清单 `dist/SHA256SUMS` 在 main 上是过期的（本轮 R4 新查，只列事实）**：
   ```
   $ git show fa57a07:dist/SHA256SUMS > 清单 ; shasum -a 256 -c 清单   # 在 main tip 的 dist/ 里跑
   ispeech-debug-v1.0.apk: OK
   ispeech-debug.apk: FAILED                     # 清单写 f21072d5…，实际字节 b308f7ad…
   ispeech-release-v1.0.apk: OK
   ispeech-release-v1.3.apk: OK
   ispeech-release-v1.5.apk: FAILED              # 清单写 f6155fa0…，实际字节 490b152a…
   WARNING: 2 computed checksums did NOT match
   
   $ cd <release tip 导出>/dist && shasum -a 256 -c SHA256SUMS         # eba7a61 上一律 OK（含内测包 9cddcec5…）
   ```
   三点影响：① 装机人照 main 的清单校验那两个文件会直接得到「校验不通过」，`dumpsys` / 应用内版本号又不可信（§2.6 第 2 条）——**用户侧「我装的是哪一包」目前没有任何一条自助路径是可信的**，这正是本单三轮反复的取证根源；
   ② 对不上号的那一项恰好是 v1.5 那一包：VOCA-159 记录 11-14 12:28 装机是「v1.5 **重签版**」，清单里 `f6155fa0…` 很可能是重签前的旧校验和、提交进仓库的是重签后的字节（**属推断，未取证**，SRE 一条命令即可定：对 `dist/ispeech-release-v1.5.apk` 跑 `shasum -a 256` 与清单对读）；
   ③ **没有任何机检覆盖这份清单**：两棵树里 `tools/verify/` 与 `docs/` 都搜不到 `SHA256SUMS`（`grep -rl SHA256SUMS tools docs` 为空），只有 `dist/README.md` 用散文提了一句，而 release tip 的 `eba7a61` 提交信息正是「最终包门禁补 VOCA-121，四项缺一不可出包」。
   归属：**SRE + 测试**（出包门禁里加一条 `shasum -c`），不在本件范围；是否另开单由项目经理定，我不重复开单。

## 9. 变更记录

### R4（2026-11-16，按质检专员评论 `01a1e4d6` 的打回项 1/2/3 返工 + 建议项 4/5；判定不变）

1. **打回项 1（取证树错了）**：§2.0 与 §2.2 的用户装机包那棵树由 `a9d1dae` 更正为 **`eba7a61` = `origin/release/voca-186-v1.6` tip**——内测包 blob `6607aaff` 只存在于 `199b972` 之后的树上，`a9d1dae` 上没有用户手机上那一包。
   行号随之为 `README.md:20`（键序）/ `:23`（第 1 键）/ `:184`（自测第 7 步）/ `dist/README.md:61`，即项目经理评论 `01a1e4b6` 的那一组；§2.2 里**两组行号并列成表并标明归属**（哪一组属于哪棵树、为什么差 5 行），§2.6/§2.1/§5/§8/AC-1 全部按 tip 重述。
   代码侧无需迁移：`git diff a9d1dae eba7a61 -- app` 为空（本轮复跑确认），机检四个数字在 tip 上重跑一字未变。
2. **打回项 2（模拟器那一行无出处）**：从 §2.0 真值表**删除**该行，只作警示保留并写明我这轮补不出处的原因（`adb devices` 已空、拿不到当次 APK 的 SHA256 与来源 commit）。
   补的正证是本轮扫全部 refs 可达的 383 个 commit：`(vc=6, 9 键) 13`、`(vc=5, 8 键) 141`、`(vc=5, 9 键) 228`、`(vc=5, 7 键) 1`——「vc=6 + 8 键」这一格是空的。§2.0 结论改写为「两个**已发布**包各自可定位：v1.5 = 8 键 / 第 1 键『⌨输入法』；v1.6 = 9 键 / 第 3 键『输』」，AC-1 的「按安装包判」口径不变、并加了「被验对象必须署名（SHA + 树）」这一条。
3. **打回项 3（否定式断言换成有出处的表述）**：`72bb547` 那一行改为「v1.5 那一代的键面**确实以 `dist/ispeech-release-v1.5.apk` 发行过**」（blob `a8a95926` 在 main tip 与 release tip 的 `_archive/` 是同一颗，SHA256 `490b152a…`；本轮 `aapt` → `5 / 1.5`、`dexdump` → 8 键且 `SWITCH_IME` 第 1 项键面就是「⌨输入法」）。
   **这条补强 A**：README 那句「第 1 键『⌨输入法』」对上一代包成立、对用户装的 v1.6 不成立，A 的根因从「文档写错」升级为「出包分支跨代没回追、文档没随包校正」。§6 判读口径同步改掉：用户回忆「按的第 1 键」在 11-14 那版是正确按法，**不能据此判他点错键**，也不能据此判「面板没问题」。
4. **建议项 4（`共 N 个` 同源）**：§2.3 补引 `InputMethodPanel.kt:200`（两树同行号）`countLabel.text = … "共 ${items.size} 个"`，并补两条反证结构：标签与行同源 ⇒ `items.size` 只有 2，「渲染裁切 / 没滚出来」不成立；AC-2 的取证口径要求「同一张截图里同时拍到『共 N 个』标签 + 全部行 + 标题栏计数」，缺一判无法核实。§2.0 行 1 的「本轮没连到真机、只能引用记录」免责按建议项去掉（仓库侧那一半我已用包字节自己复现）。
5. **建议项 5（`4995892` 与 main tip 的关系加限定）**：文档抬头改为「以 `fa57a07` = `origin/main` tip 为准；`4995892` 与它只差 `dist/ispeech-release-1.5-20261115-1004.apk` 一个二进制，源码与文档逐字节相同，只是我的跑数载体」。
6. **本轮新增的两条事实（不改判定，进 §8）**：① main 的 `dist/SHA256SUMS` 两条过期（`shasum -c` FAILED，release tip 全 OK），且没有任何机检覆盖它；② `d3b6f84`（PR #9）经复核**既不在 main 也不在 release tip**，M5 出包门禁必须核对。
7. **与质检记录的一处出入**：打回项 1 说内测包「只在 `c05f9a9` 之后才存在」，按 `--diff-filter=A` 与 `git show --stat 199b972` 入库点是 `199b972`（同一颗 blob，判定与行号不受影响），已写进 §2.2 脚注。
8. **本轮跑数的载体**：§2.6 的四个机检数字这次分别跑在 `git archive fa57a07` 与 `git archive eba7a61` 的只读导出树上（不是本地工作树），这样报告里的每个数字都直接对应被引用的那棵树；`dist/SHA256SUMS` 的 `shasum -c` 同样在两份导出里跑（main tip 5 条里 2 条 FAILED，release tip 9 条全 OK）。

### R3（2026-11-16，按质检专员评论 `01a1e352` 的 5 条打回项返工）

1. **打回项 1（判定基线用错构建）**：撤回「用户装机版 = `72bb547`」，改为 §2.0「按安装包判」三行表（真机 1.6/9 键、模拟器 1.6/8 键、`72bb547` 只是历史源码）；
   主判定由 R2 的 C 改为 **A + D**（A 恢复并升级为「有发行包硬证据」，B 维持未排除，C 的「未启用」前提删除）；§5 M1 改为按两个包分别出数。
   **→ R4 更正**：那一轮用的 release 树 `a9d1dae` 不是用户装机包的树（已换成 `eba7a61`），表里的「模拟器 1.6/8 键」一行无出处（已删），「`72bb547` 不是任何设备上的安装包」这句否定式断言（已改写为 v1.5 归档包的发行事实）。A + D 的判定本身不变。
2. **打回项 2（AC-1 真值口径）**：AC-1 的真值从「当次 checkout」改为「**被验收的那个安装包对应的树**」，并按要求对 `4995892` 与 `a9d1dae` **两个 `toolbarKeys()` 分别出结论**（§2.2 两张表）；
   同时更正 R2「6 处文字描述可接受」的分类——`README.md:179`、`dist/README.md:24` 在发行包上是**指位错误**，不是软差异。
   **→ R4 更正**：口径保留，但那一组 `:15/:18/:179` + `dist/README.md:24` 属于 `a9d1dae`，前端/测试要照的是 tip 的 `:20/:23/:184` + `dist/README.md:61`；§2.2 已把两组并列标明归属。
3. **打回项 3（两问送达 + 补分支）**：§6 补 `[@qiaozhou](mention://member/238f192e-…)`（R2 全 issue 0 个 member mention，这是 45h 无人答的根因）；
   Q2 的已知答案写进正文（设备已启用搜狗与千问，附命令出处）；新增分支「Q2=是 + Q1=(c) → **D 面板未列出已启用输入法**，转前端 / 架构师，不归 C」——并且本轮已经在 AOSP 上把它复现成实测事实（§2.3）。
4. **打回项 4（陈旧行号）**：全篇 `.kt:NNN` 按 `4995892` / `a9d1dae` 双列重核——main 树：`enabledInputMethodList` `:1397`（R2 误写 `:1479`）、空态文案「没有其他输入法」`:1415`（R2 误写 `:1497`）、
   `setInputMethod` `:1435`、`showInputMethodPicker` `:1444`、「系统不允许切换输入法」`:1450`；release 树对应 `:1490 / :1508 / :1528 / :1537 / :1543`。
5. **打回项 5（小项更正）**：README 键序行号改 `:15`（R2 写 `:14`）；R2 评论里的改动量更正为 `git diff --numstat 28cc1f8 45e2c85` = **`123 47`**（我上条写 `+126 −52`）；
   补披露起点 commit `51e90e7` 除 139 MB release APK 外**还删除了 `.claude/worktrees/voca-74`**（`git show --stat 51e90e7` → 2 files changed）。
6. **本轮新增取证（R2 没有的）**：§2.3 缺陷 D（4 启用 → 面板 2 行、系统选择器 4 项，附 3 张截图）；§2.5 切换成功复跑（`DEFAULT` 前后原文）；
   §2.6 两树机检数字（main 87/0/1 + 151/0/0；release 91/0/1 + **150/1/0**，FAIL 项为版本号自述不一致）；§2.0 模拟器包版本与键序实测（**→ R4：这一项署不出包来源，已从真值表删除，键位改由两个发行包字节支撑**）。
7. **验收标准重排**：由 AC-1~AC-7 的长式 Given/When/Then 收敛为「**每条先给一句话判据**」+ 5 核心（AC-1 文档键位、AC-2 列表完整性、AC-3 切换成功、AC-4 失败兜底、AC-5 空态）+ 2 附加（AC-6 ROM 拦截、AC-7 人工可发现），覆盖 issue 交付物 3 要求的全部 6 项。
8. **状态口径**：本轮按项目经理评论 `01a1e29a` 与角色规程置 **`qa_ba`（待质检·洞察BA）**，不停 `in_review`（`in_review` 无 handler，会再次出圈）。

### R2（2026-11-16，按项目经理打回项返工，基线 `4995892`）

AC-1 改不变量、§2.1 漂移清单标基线（三张表）、崩溃项并单到 VOCA-170/228、外推口径收紧（versionCode 相同 ≠ 同一构建）。
其中「装机版 = `72bb547`」与「撤销 A、主判 C」两条已被 R3 撤回；「6 处文字描述可接受」的分类被 R3 更正。

### R1（2026-11-14，commit `28cc1f8`，基线 `945e8ad`）

初版：A/B/C 判定（主判定 A）、AC-1~AC-7、范围边界、M1~M3、2 个封闭问题、3 条风险假设、附带发现设置页崩溃。
测量基线用了工作树中间态 `945e8ad`，非装机构建，R2/R3 两轮更正。
