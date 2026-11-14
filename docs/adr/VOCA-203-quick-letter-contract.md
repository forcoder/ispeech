# ADR-0002：快捷字母长度、本地-远端同 trigger 所有权、同步回写安全线（VOCA-203/S1）

- **状态**：Proposed（待人类评审；阶段 2 实现 VOCA-207 以本文唯一输入为准）
- **决策日期**：2026-11-14
- **决策人**：架构师（agent `e7ea905a-48f9-4dc9-86d5-32346dc237da`，代 VOCA-203 用户 4 张标注截图）
- **关联 issue**：VOCA-206（本 ADR）→ VOCA-203（父）；下游 VOCA-207（实现）、VOCA-208（回归与 go/no-go）
- **关联既有文档**：`docs/quick-phrase-enhancement.md` 12.3 / 12.4 / 12.5 / 12.6、`docs/adr/VOCA-65-phrase-learning.md`（格式与编号前例）
- **本文的基线口径**：所有行号与代码结论以 **`origin/main`（`f728231`）** 为准；与本分支基线 `ac4e4ce` 不一致处在 §0 修正 3 逐条列出。
- **范围**：只做架构决策与契约。不写实现、不出补丁、不改 main、不做视觉稿。
- **复审条件**（任一满足即重审）：
  ① csBaby 后端提供 `updated_at` / 规则版本号或可用的 `PUT /api/rules/{id}`；
  ② 五笔态 `MAX_CODE_LENGTH` 因任何原因改动；
  ③ 上线 30 天内出现任何一次「云端正文被本地过期条目覆盖」的实际案例；
  ④ 快捷规则总数进入千级（面板与候选去重的成本口径需重算）。

---

## 0. 先读这一节：对 issue 现状表与既有文档的五处修正

这五处直接改变决策方向与工作量。实现方按本节口径，不要按 issue 原文推导。

### 修正 1（关键）：事实 7 只对「快捷短语面板」成立，候选链路两态同挂

issue 事实 7 与 `docs/quick-phrase-enhancement.md` 12.6 都断言「五笔态 `includeLocal = false` → 内置规则整体不挂载」。**这句只对输入法内的快捷短语面板成立，候选查询链路从来不是这样。**

代码证据（两条链路是两套参数，候选链路没有五笔态分支）：

| 链路 | 调用点（main） | 实际参数 |
|---|---|---|
| 候选查询（打字出候选） | `di/AppContainer.kt:117-127`（`buildEngineFromSettings`）、`:200-208`（`pinyinLookupEngine`） | `includeLocal = enableQuick`、`includeRemote = enableRemote`，**只看用户开关**（两项默认均 `true`：`settings/store/SettingsStore.kt:84`/`:91`） |
| 快捷短语面板（列表展示） | `input/IspeechImeService.kt:1266-1288`（main） | 同样只看用户开关 —— 见修正 2 |

`input/IspeechImeService.kt:1066`（`lookupSource()`）五笔态走 `engine` = `AppContainer.compositeEngine`；
`tools/verify/check_quick_trigger.py:488`（K 节）已经在断言「`buildQuickEngines` 同时被五笔态 `buildComposite` 复用（两态规则一致）」。
**项目自己的校验脚本与「五笔态整体不挂载」的文档口径互相矛盾**，本 ADR 以代码 + 脚本断言为准。

推论（决定第 4 项怎么答）：

- 内置 `dtwsl`（`exact`）在五笔态**是挂载的**。它打不出来只因为组合缓冲上限 4 位（事实 3），不是因为没挂。
- csBaby 远端 `dtwsl` 落盘后 `match_type = CONTAINS` → `QuickMatchMode.CONTAINS`（`data/CsBabyClient.kt:199-204`），
  `engine/QuickPhraseEngine.kt:63` 的 `trigger.contains(needle) || needle.contains(trigger)` 使它在五笔态**打满 4 位 `dtws` 就已命中**。
  所以「五笔态完全够不到云端 `dtwsl`」这个前提不成立；够不到的是「精确 5 位命中」和「面板里看得到」。

### 修正 2：面板的五笔态硬编码，main 上已经删掉了

`07214d6`（2026-11-14，VOCA-voice-offline-1114）把 `loadQuickPhrases()` 里的

```kotlin
val includeLocal = if (chineseInputMethod == ChineseInputMethod.WUBI) false else enableQuick
val includeRemote = if (chineseInputMethod == ChineseInputMethod.WUBI) false else enableRemote
```

整体删除，main 现状（`IspeechImeService.kt:1270-1279`）已是 `includeLocal = enableQuick` / `includeRemote = enableRemote`。
引入方是 VOCA-29 `2a1883e`，**当时没有 ADR、没有对应断言、没有文档条目**，属顺带改动。

后果：

- 决策 4 的「改挂载」在 main 上**已经完成**，VOCA-207 不需要再改这行代码；
- 但 `docs/quick-phrase-enhancement.md:660`（12.6，写于 11-10）仍在说「五笔态 `includeLocal = false`，内置规则整体不挂载」—— **文档滞后于代码 4 天**，必须改（§7.2）；
- 本分支基线 `ac4e4ce` 里这段硬编码**还在**（`IspeechImeService.kt:1308-1309`）。若 VOCA-207 从基线起分支再「修复」它，等于把 main 已修复的代码重新改一遍，且在合并窗口里可能与其它分支冲突。

### 修正 3：本 worktree 基线落后 main 20 个 commit，且不含 VOCA-75

`ac4e4ce`（本分支起点）不是 `origin/main` 的祖先；`HEAD..origin/main` 有 20 条，`origin/main..HEAD` 只有基线快照 1 条。
基线与 main 的差异中，与本 issue 直接相关的三条：

| 项 | 基线 `ac4e4ce` | main `f728231` |
|---|---|---|
| `dtwsl` 内置规则 | **不存在**（全仓库 `grep dtwsl` 0 命中） | `assets/quick_phrases.txt` 有 `dtwsl \t 泡池须知 \t <281 字> \t exact \t 110 \t a`（`483a4c8`） |
| 面板五笔态硬编码 | 存在（`:1308-1309`） | 已删除（`07214d6`） |
| `check_quick_trigger.py` 的 trigger 长度判定 | 只有「>4 位 warn」 | **>4 位且非 exact/regex → warn；>12 位 → fail**（`:159-170`，见修正 4） |
| `docs/adr/` 目录 | 不存在 | 存在（`VOCA-65-phrase-learning.md`），本文编号顺延为 ADR-0002 |

**对 VOCA-207 的硬要求：分支必须从 `origin/main` 起，不能从 `ac4e4ce` 起。**
否则：看不到 `dtwsl` 内置规则与 L 节断言 → 改出来的东西与 VOCA-208 的回归用例对不上；
还会「重复修复」面板挂载，把 main 已删掉的五笔态硬编码又写回去。

### 修正 4：项目自己的数据侧校验早已把 12 位定为硬上限，编辑器的 2–4 是唯一离群点

main `tools/verify/check_quick_trigger.py:70` 有 `UNGUARDED_MODES = {"exact", "regex"}`，`:159-170` 对规则文件里每条 trigger 判定：

- `len(trigger) > 4` 且 mode ∉ {exact, regex} → **warn**，理由原文：「五笔态组合缓冲区上限 `MAX_CODE_LENGTH=4`，
  完整码只能在拼音态敲出，拿不到『精确码优先』的首位加成」；
- `len(trigger) > 12` → **fail**，理由原文：「超过拼音态组合串上限 `MAX_PINYIN_LENGTH=12` —— 完整码永远打不出来，该条静默失效」。

即：**数据侧（资产 + 导入 + 远端落盘）早就接受 5–12 位，并且已经把「12 = 功能可达天花板」和「>4 位不该用 prefix/contains」写成断言。**
只有设置页编辑器（`settings/QuickPhrasesScreen.kt:82-83` 的 `in 2..4`、`settings/quick/QuickPhrasesViewModel.kt:309` 的 `in 2..4`）
比项目自己的校验器更严，导致「数据里允许的东西，UI 不让写」。

这条把决策 1 从「放宽限制」重定义为「**消除编辑器与数据校验器的口径分裂**」，也让 12 这个数字不是拍脑袋：
它是仓库里已有断言的值，不是我新发明的常数。

### 修正 5：用户抱怨 ②「文字内容写多了无法保存」与抱怨 ① 同一个根因

本地规则弹窗的确认按钮是 `enabled = triggerValid && editor.content.isNotBlank()`（main `QuickPhrasesScreen.kt:609`），
`triggerValid` 来自 `isValidShortcutTrigger`（`:82`，硬编码 `in 2..4`）。全仓库对**正文**没有任何长度上限
（`QuickPhrasesScreen` / `QuickPhrasesViewModel` 内无 `maxLength`、无正文长度判断；
`data/PhraseUsageStore.kt:99` 的 `MAX_TEXT_LENGTH = 2000` 只用于使用记录的 id 归一，不参与保存校验）。

用户看到的现象：正文写得很长 → 点「保存」没反应 → 归因到「内容写多了」。
真实原因：快捷字母 `dtwsl` 5 位非法把按钮禁掉了，而错误提示挂在最上方字段下方、离确认按钮很远，且按钮禁用时没有任何「还差什么」的说明。

**结论：抱怨 ② 不需要单独修「正文长度」，它随决策 1 一起消失；但必须额外修「禁用原因可见性」**（C1-7），
否则用户下次遇到别的禁用理由，还会归因错位。

---

## 1. 决策一：快捷字母长度上限

### 结论

1. **编辑器上限 4 → 12**，下限保持 2，字符集保持 `a-z`（先 trim + lowercase 再判定）。与 main 数据侧判定（修正 4）同口径。
2. **不因为长度改写用户选的匹配方式**（事实 6 的「>4 自动降级 prefix」不作为编辑器行为引入）；长度与 mode 正交。
3. **不提高 `MAX_CODE_LENGTH`**（保持 4），**不引入独立于编码缓冲的「快捷码缓冲」**。
4. >4 位时给**可达性提示**（非阻断）；>4 位且 mode ∈ {prefix, contains} 时给**误弹警示**（非阻断）。
5. 校验实现**收敛为单一来源**（消除事实 2 的两份重复校验）。

### 备选方案与否决理由

| 方案 | 结论 | 理由 |
|---|---|---|
| A 保持 2–4 | 否决 | 用户资产里已存在 5 位码（csBaby `dtwsl`、`sogou_phrases.txt` 18 条 5 位、main 内置 `dtwsl`），编辑器写不进自己的数据；且与项目自己的 `check_quick_trigger.py:159-170` 口径分裂 |
| B 放宽到任意位（如 32） | 否决 | 32 是 `engine/PinyinEngine` 的内部上限，不是 IME 缓冲上限。>12 位**任何态都敲不出来**（`IspeechImeService.kt:95` `MAX_PINYIN_LENGTH = 12` 是组合串物理天花板），main 脚本已把 >12 判 fail。放到 32 只是制造打不出来的数据 |
| C 放宽到 2–12 | **采用** | 12 = 仓库既有断言值 = 最大缓冲天花板，是「功能可达性」的自然边界；覆盖 `sogou_phrases.txt` 最长 5 位与 csBaby 现实简写 |
| D >4 强制 `prefix`（沿用导入脚本约定） | 否决 | `QuickPhraseEngine.kt:62` 的 PREFIX 是 `trigger.startsWith(needle)`，五笔态打满 4 位 `dtws` 就命中 5 位码；`LookupKind.QUICKAPPLY.order = 0` 排在 `WUBI = 2` 之前（`engine/LookupCandidate.kt:24-29`）→ **281 字长文压掉用户正要输入的五笔词，空格即整段上屏**。main 脚本对这件事的判定是 warn + 理由，说明项目已经承认它是风险，只是过去靠「正文都是 4 字诗句」侥幸。导入脚本那条约定不能推广到长正文 |
| E 提高 `MAX_CODE_LENGTH` 到 5+ | 否决 | 五笔连续输入依赖「第 5 个字母到来时先上屏当前首选」（`IspeechImeService.kt:671-675` + `:909-918`）。抬到 5 等于每个 4 码完整字/词都不再自动上屏，用户必须逐字按空格 —— 五笔重度用户肌肉记忆全变。为一条 5 位简写付出整个输入态的回归，代价与收益严重不对称 |
| F 独立的快捷码缓冲 | 否决（本 issue 范围外） | 需要「这段字母是五笔码还是简写」的判定规则，等价于在输入态里再开一个命名空间，牵动 `handleChar` / `commitTop` / T9 / 学习链路 / `check_input_logic.py`。这是新特性，要有自己的 issue 与 ADR |

### 五笔态 5 位码怎么命中（用户可见口径）

- **拼音态 / 英文态**：缓冲上限 12，`d t w s l` 完整累积，第 5 位落下即精确命中 → 候选首位、空格整段上屏。这是 12.6 已验收的路径，**保持不变**。
- **五笔态**：不承诺「敲键盘精确命中 >4 位简写」。承诺两条替代路径：
  1. 打前 4 位 `dtws`，**面板筛选**出该条 → 点行上屏（main 已删面板硬编码，见修正 2；`PhraseSearch` 已把 trigger 作为搜索字段，main L10 断言）；
  2. 中英切换键切到拼音态敲全码。
- 这条口径必须写进 12.6（§7.2），不能只留在口头。

### 回滚方式

长度上限是单一常量 + 一处文案，回滚 = 把 `MAX_TRIGGER_LENGTH` 改回 4 并还原 `quick_trigger_error`。
**已保存的 >4 位条目不会被回滚删除**（读取侧从不校验长度，C1-5），回滚只影响新建/编辑入口，无数据风险。

---

## 2. 决策二：本地-远端同 trigger 的所有权契约

### 现状为什么错

覆盖条目的身份判定是 `QuickOverride.matches(user, remote) = user.label == remote.label && user.content == remote.content`
（`engine/QuickEntry.kt:37-38`，main 同行）。远端落盘时 `label = keyword`（`data/CsBabyClient.kt:205-208`），
于是这条判定实际是「**正文必须一模一样才算覆盖**」。后果：

- 设备上那条正文缺了开头一段的过期 `dtwsl` 用户条目，`label`/`content` 都不等于远端条目 → **不被识别为覆盖**；
- `mergeForDisplay`（main `settings/quick/QuickPhrasesViewModel.kt`，以 `label to content` 为键折叠）
  把过期条目留在用户卡片里单独展示（截图 4 的 `dtwsl 用户 · 应用 · 前缀匹配`）；
- `pushLocalRules` 的跳过集合同样用 `matchesAny`（`data/SyncRepository.kt:90-91`），过期条目**进了待推送列表** → 直接接上决策 3 的数据丢失路径。

根因是**身份判定用错了键**。trigger 才是本地快捷规则命名空间里唯一的标识：
`QuickPhraseCodec.parse` 按 trigger 建 `LinkedHashMap` 后者覆盖前者（`QuickPhrasesViewModel.kt:426-453`）、
`upsert` 按 `it.trigger == userEntry.trigger` 覆盖（`:189-190`）、`remove` 按 trigger 删（`:203`）、
`QuickPhraseEngine.fromText` 也按 trigger 落库。写入侧、解析侧、删除侧全都把 trigger 当主键，
只有「覆盖判定」与「同步跳过」两处用 label+content —— 这是不一致，不是设计。

### 结论

1. **`QuickOverride` 的身份判定改为 trigger 全等**（归一化：trim + lowercase）。`label`/`content` 退出身份判定。
2. **所有权：同 trigger 时本地（用户）为准**，且**在引擎输入侧去重**，不再靠 weight 压过。
3. **展示：同 trigger 折叠成一张卡片**，来源标注 `远端 · 已覆盖`，正文取**本地 content**（不是远端 content）。
4. **过期本地条目不自动改写任何一侧**，转为「冲突」显式状态 + 两个用户动作。

### 契约细则

- **候选去重位置**：`QuickLocator.loadAll` / `buildQuickEngines`（`engine/QuickLocator.kt:137-198`）拼接内置 → 远端 → 用户后，
  按归一化 trigger 去重，优先级 **用户 > 远端 > 内置**。取代原先「`REMOTE_OVERRIDE_WEIGHT_BONUS = 1000` +
  `CompositeEngine` 按 display 去重、先入者胜」的机制。
  原机制为什么不够：`display` 取 `label`，用户条目 label 是中文名（`泡池须知`）、远端条目 label 是 keyword 本身（`dtwsl`），
  两者不同 → **不去重，两条候选并存**，谁在首位取决于 weight 与 `display.compareTo`（`engine/LookupEngine.kt:28-37`）。
  用户无法预测，测试也无法断言「空格上屏的应该是哪一条」。
- **权重加成保留但降级**：`REMOTE_OVERRIDE_WEIGHT_BONUS` 继续存在（同档内相对顺序），
  但**不再是正确性的唯一保证**；正确性由「同 trigger 只有一条进候选池」保证。
- **冲突的定义**（决策 3 与迁移共用）：`normalize(user.trigger) == normalize(remote.trigger)`
  且 `user.content != remote.content`（比较口径 = 落盘转义后的字符串全等，与 `SyncRepository.escapeForFile` 一致）。
- **迁移（设备上已有的过期 `dtwsl`）**：升级后首次加载跑一次一致性检查，把冲突条目列入冲突清单，
  卡片显示冲突徽标与两个显式动作，**默认不写任何一侧**：
  - 「采用云端正文」：本地正文替换为远端正文，冲突消除。云端不动 → 无远端数据风险；
    替换前必须把本地旧正文在冲突详情里可读（不要求做可撤销栈，见 §10）。
  - 「以本地为准并更新云端」：走 C3-4 的显式确认推送路径（一次性，推成功即登记映射表）。
  - **为什么不自动拉平**：本地正文可能是用户故意改的（离线编辑还没上传），自动以云端覆盖 = 丢本地未上传资产；
    自动以本地覆盖 = 丢云端正文。两个方向都是数据丢失，必须由人选。
- **使用记录不丢**：`PhraseUsageStore.idOf(entry) = hash(trigger + content)`（`data/PhraseUsageStore.kt:136`）。
  「采用云端正文」会让 content 变、id 变 → 收藏与次数漂到旧 id 上。契约：`mergeForDisplay` 的 `originalIdById`
  迁移从「覆盖前后」扩展到「同 trigger 的旧 content id → 新 content id」。

### 回滚方式

身份判定与去重是行为变更、不改文件格式，回滚 = 还原 `QuickOverride.matches` 与 `loadAll` 去重。
**已按新规则折叠的条目在回滚后会重新显示成两条**（数据没动），属可见行为退化，不属数据损失。
回滚窗口应早于冲突清单落盘；一旦设备上写了 `quick_phrases_conflicts.txt`，回滚需同时删掉该文件（§9）。

---

## 3. 决策三：同步回写安全线（**P0，数据丢失**）

### 缺陷链路（逐行核对过，main `data/SyncRepository.kt:107-129`）

```
ref    = known[entry.trigger]                                   // 映射表里没有 dtwsl → null
target = ref?.let { remoteById[it.id] } ?: remoteByKeyword[...]  // :112 按 keyword 兜底命中远端那条
when {
  target == null -> create
  ref != null && ref.fingerprint == fp && target.matches(entry) -> Unit      // :120
  ref == null && target.matches(entry) -> 补映射                              // :122
  else -> updates.add(entry.toPushRule(target.id))                            // :124-125 ← 本地过期正文按远端 id 覆盖云端
}
```

`:145-156` 推送后复核远端确实变了 → `updatedApplied++` → `quick_synced` 文案报「修改 1」**成功**。

即：**用户从未在本地编辑过这条云端规则，仅因设备上残留一条同简写的过期条目，云端正文就被覆盖，并且界面报告成功。**
`docs/quick-phrase-enhancement.md` 12.3 明确承诺过「不做『假装成功』」，这条路径违背的正是该承诺。

### 定级：P0

三条理由，缺一则降为 P1：

1. **不可逆**：`sync/push` 带 id 即按 id 覆盖，iSpeech 侧没有任何版本/撤销能力可调用
   （12.3 实测 `PUT / DELETE /api/rules/{id}` 全部 404）；云端是真实营业话术（泡池/泳池收费与预约规则），丢了只能人工回忆重写。
2. **静默**：界面报成功，用户没有机会阻止，也没有事后发现的信号。
3. **触发门槛极低**：不需要用户做错任何操作 —— 一次「从远程同步」+ 一次「保存并同步」即可，
   而这两个按钮就在同一个页面上（截图 4 的「从远程同步」与右上角「保存并同步」）。

### 结论：映射表是唯一同步身份

| 编号 | 规则 |
|---|---|
| C3-1 | 远端规则的身份**只有** `filesDir/quick_phrases_remote_map.txt` 里的 `trigger → id` 记录。无映射记录时，**禁止**把本地内容写进任何 keyword 相同的远端规则 |
| C3-2 | 无映射 + keyword 命中 + 正文一致 → 只补映射，不推送（现状 `:122-123` 保留） |
| C3-3 | 无映射 + keyword 命中 + 正文不一致 → 判为 **conflict**：不推送、不补映射、不计 `applied`；`RuleSyncReport`（`:30-42`）新增 `conflicts: Int`；冲突条数必须出现在同步文案里，且**不能复用** `quick_synced_partial`（那条语义是「后端不支持修改/删除」，不是「有冲突」） |
| C3-4 | 有映射 + 指纹变化 → 允许推送更新（这才是「用户改了本地自己拥有的规则」） |
| C3-5 | 有映射 + 远端已无该 id → 走 create 分支（现状 `remoteById[it.id]` 为 null → `target == null` 已覆盖，保持） |
| C3-6 | 新建云端规则仍走 `POST api/rules`，拿到非空 id 才登记映射（现状 `:116` 保持） |
| C3-7 | 「以本地为准并更新云端」只能由**用户显式动作**触发（决策 2 的冲突动作，或本地编辑器保存自己拥有的规则）；`pushLocalRules` 不得自行进入该分支 |
| C3-8 | `toRuleRequest` / `toPushRule` 不再硬编码 `enabled = true`（`:202`/`:212`），改传 `null`。本地规则文件里没有 enabled 语义，**本地不知道就别写** —— 同一原则的延伸。现状会把远端已禁用的规则悄悄启用 |
| C3-9 | 冲突清单落盘 `filesDir/quick_phrases_conflicts.txt`（`trigger \t remoteId \t remoteFingerprint \t detectedAt`），使冲突跨多次同步可追踪；正文一致后该条自动移出 |

**能否不依赖后端改动闭环**：能。C3-1…C3-9 全部只用现有 `listRules` / `sync/push` / `api/rules` 与本地映射表，
不需要 csBaby 提供新字段或新路由。后端能改进的两点（`updated_at`/版本号、可用的 PUT/DELETE）列为**建议但不阻塞**，见 §11 项 3。

### 回滚方式

安全线是「少做事」的变更：回滚 = 恢复 `else -> updates.add(...)` 分支，回滚后风险立刻回来。
**不允许单独回滚 C3**；若必须回滚整个 PR，需同时回滚 C1（长度）与 C2（身份判定），
并在发布说明里写明「该版本会把本地过期正文覆盖到云端同简写规则」。

---

## 4. 决策四：五笔态是否挂载内置 / 远端快捷规则

### 结论

**采用「改挂载」，不采用「内置快捷码只承诺拼音态」的说明口径 —— 但代码修复已在 main 完成（`07214d6`），
本 issue 交付的是「口径 + 断言 + 说明」三件事，不是一行代码。**

三项交付：

1. **文档口径修正**：删掉 12.6 里「五笔态 `includeLocal = false`，内置规则整体不挂载」这句已经与代码相反的话，
   换成正确的可达性说明（§7.2）。
2. **回归断言**：`check_quick_trigger.py` 新增断言 —— `loadQuickPhrases()` 内 `includeLocal` / `includeRemote`
   **不得**引用输入方式（`InputMethodMode` / `chineseInputMethod`），面板与候选链路同源。
   这条断言是防止 VOCA-29 那种「顺带改动」再次把挂载删回去的唯一手段（修正 2 的教训：改行为不留断言，4 天后文档与代码就互相矛盾）。
3. **用户可见说明**：五笔态 >4 位简写的可达路径 = 面板筛选点选 / 切拼音态敲全（§1）。

### 依据

1. 候选链路本来就两态同挂（修正 1），且 `check_quick_trigger.py:488` 已把它当不变量断言。
   面板单独在五笔态隐藏内置/远端条目，是**同一份数据的两个口径**：用户会遇到
   「敲 `dtws` 候选里冒出来的东西，打开短语面板却搜不到」—— 这种分裂无法向用户解释，也无法向测试描述验收。
2. 面板挂载是**本次唯一能让五笔默认用户在 UI 里看见 `dtwsl` 这条云端规则**的路径（键盘敲不出 5 位，见决策 1 方案 E）。
3. VOCA-29 引入硬编码时（`2a1883e`）没有 ADR、没有断言、没有文档条目，提交信息讲的是中英切换 —— 属顺带改动，不是被论证过的设计。

### 代价与必须配套的约束

| 代价 | 约束 |
|---|---|
| 面板条目数上升：内置 8（含 `dtwsl`）+ 搜狗 25 + 远端 N + 用户 M | 无关键词时保持规则文件原始顺序（12.2 既有约定）；`QuickPhrasePanel.submit` 全量灌入 + 过滤，**VOCA-208 必须真机测面板滚动**，条目 > 200 时记录是否掉帧 |
| 五笔用户在面板里看到大量不认识的内置诗句 | 面板已有来源分类胶囊（`engine/QuickEntry.kt:24` `QuickSource.all` = 内置/远端/用户）；默认筛选口径不变，**不新增开关**（避免扩大范围） |
| 「恢复默认」路径 | 不变：`removeRemoteOverride` 仍只删用户文件里的覆盖条目 |

---

## 5. 契约表（每条可直接转成断言）

### 5.1 长度规则

| 编号 | 契约 | 可转成的断言 |
|---|---|---|
| C1-1 | 编辑器合法快捷字母 = `^[a-z]{2,12}$`（先 trim + lowercase） | `QuickTrigger.isValid("dtwsl") == true`；`isValid("d") == false`；`isValid("abcdefghijklm") == false`；`isValid("dtws1") == false` |
| C1-2 | 校验只有**一个**实现，Screen / ViewModel / SyncRepository 都委托它 | 全仓库 `in 2..4` 字面量出现次数 == 0；`isValidShortcutTrigger` 定义处 == 1 |
| C1-3 | `setRemoteShortcut` 非法输入不静默 return | main `:309` 的 `return` 改为可呈现的错误（或调用方先校验），有对应单测 |
| C1-4 | 长度不改变 mode：编辑器不因 >4 位改写用户选的匹配方式 | 输入 5 位 trigger 后 mode 字段值不变（单测） |
| C1-5 | 读取侧从不校验长度、从不截断 | 13 位 trigger 的规则行仍完整解析、完整参与匹配（`QuickPhraseEngine.fromText` 单测） |
| C1-6 | 覆盖条目（`setRemoteShortcut` 新建）mode 规则：`len ≤ 4 → PREFIX`，`len > 4 → EXACT` | 5 位简写生成的覆盖条目 `mode == EXACT`；4 位的仍 `== PREFIX`。与 main `UNGUARDED_MODES = {exact, regex}` 同口径 |
| C1-7 | >4 位时 UI 给可达性提示（非阻断）；确认按钮禁用时必须显示禁用原因 | 存在 `quick_trigger_reachable_hint` 中英文案；`enabled == false` 时弹窗内可见「还差什么」（修 §0 修正 5 的归因错位） |
| C1-8 | `MAX_CODE_LENGTH = 4`、`MAX_PINYIN_LENGTH = 12` 不变 | `check_quick_trigger.py` L8 / L9 缓冲区断言原样通过 |
| C1-9 | 用户为 >4 位 trigger 显式选 PREFIX/CONTAINS 时允许保存，但显示误弹警示 | 存在 `quick_trigger_prefix_long_content_warn` 文案；`len(trigger) > 4 && mode ∈ {PREFIX, CONTAINS}` 时可见；与脚本 `:159` 的 warn 同级 |
| C1-10 | 导入脚本的「>4 → prefix」保留为**已交付数据的封闭例外**（`sogou_phrases.txt` 18 条，正文均 4 字） | 脚本不改；18 条 5 位 prefix 条目数不变；新增数据不再享受该例外（C1-6 覆盖新路径） |

### 5.2 冲突裁决规则（所有权）

| 编号 | 契约 | 可转成的断言 |
|---|---|---|
| C2-1 | 归一化 trigger 是本地快捷规则唯一主键 | `QuickPhraseCodec.parse` 同 trigger 两行 → 1 条（现状保持）；`upsert` 同 trigger 覆盖（现状保持） |
| C2-2 | 覆盖判定 = trigger 全等，不看 label/content | `QuickOverride.matches(user(label="泡池须知", content="A"), remote(label="dtwsl", content="B")) == true`（同 trigger）；不同 trigger 且同 label/content → `false` |
| C2-3 | 同 trigger 折叠为一张卡片，来源 `远端 · 已覆盖`，正文取本地 content | `mergeForDisplay` 输入同 trigger 的用户+远端 → 返回 `size == 1`、`content == user.content`、`RemoteRuleInfo.overrideTrigger != null` |
| C2-4 | 候选池按 trigger 去重，优先级 用户 > 远端 > 内置 | `QuickLocator.loadAll` 同 trigger 跨源 → 该 trigger 恰好 1 条且来自用户；`buildQuickEngines` 同口径 |
| C2-5 | 冲突 = 同 trigger 且正文不等（转义后全等口径） | 单测：同 trigger 同正文 → 非冲突；同 trigger 差一个空格 → 冲突 |
| C2-6 | 迁移不自动改写任何一侧 | 升级后首次加载：`quick_phrases_user.txt` 与 `quick_phrases_remote.txt` 字节内容均不变 |
| C2-7 | 冲突解决后使用记录不丢 | 「采用云端正文」后 `isStarred(newId) == true`（旧 id 收藏已并入） |

### 5.3 同步回写规则

| 编号 | 契约 | 可转成的断言 |
|---|---|---|
| C3-1 | 无映射记录 ⇒ 永不按 keyword 覆盖远端正文 | 单测：`known` 空、`remoteByKeyword` 命中、正文不同 → `updates.isEmpty()` 且 `conflicts == 1` |
| C3-2 | 无映射 + 正文一致 → 只补映射 | 单测：`created == 0`、`updates.isEmpty()`、映射表新增该 trigger |
| C3-3 | 冲突计入 `conflicts`，不计 `applied`，不复用 partial 文案 | `RuleSyncReport` 有 `conflicts`；`quick_synced_conflict` 新文案存在（中英）；`syncTip` 冲突分支优先于 partial 分支 |
| C3-4 | 有映射 + 指纹变化 → 允许更新 | 单测：`known[trigger] = (id, oldFp)`、正文已改 → `updates.size == 1` |
| C3-5 | 有映射 + 远端无该 id → create | 单测：`known` 有 id 但 `remoteById` 无 → 走 create |
| C3-6 | 只有 `rule.id` 非空才登记映射 | 现状 `:116` 的 `if (rule.id.isNotBlank())` 保持 |
| C3-7 | 「以本地为准覆盖云端」只能由显式用户动作触发 | `pushLocalRules` 内不存在「冲突未解决即推送」的可达路径；显式动作有独立入口与单测 |
| C3-8 | 不硬编码 `enabled = true` | `toRuleRequest` / `toPushRule` 的 `enabled` 传 `null`；`SyncRepository.kt` 内不再出现 `enabled = true` |
| C3-9 | 冲突清单落盘且跨同步可追踪 | `quick_phrases_conflicts.txt` 格式为 `trigger \t remoteId \t remoteFingerprint \t detectedAt`；正文一致后该 trigger 移出 |
| C3-10 | 覆盖条目绝不被当作新规则推送 | 现状 `filterNot { QuickOverride.matchesAny(...) }`（`:90-91`）保持，判定按 C2-2（trigger） |

### 5.4 挂载与可见性

| 编号 | 契约 | 可转成的断言 |
|---|---|---|
| C4-1 | 面板与候选链路同源，输入方式不参与挂载 | `IspeechImeService.loadQuickPhrases` 内不出现 `InputMethodMode` / `chineseInputMethod` 参与 `includeLocal`/`includeRemote` 计算（main 现状已满足，需断言锁住） |
| C4-2 | 面板条目顺序 = 内置 → 远端 → 用户，无关键词时不按评分重排 | 12.2 既有约定保持；`check_crosscut.py` 面板零磁盘 IO 断言不回退 |
| C4-3 | 五笔态 >4 位码的可达口径 = 面板筛选点选 / 拼音态敲全 | 12.6 出现该口径；`check_quick_trigger.py` L9 仍断言「五笔态打不全」（不变） |

---

## 6. 影响面（文件与不变量）

### 6.1 代码（阶段 2 落点，行号取 main `f728231`；本 ADR 不写实现）

| 文件 | 改什么 | 牵动 |
|---|---|---|
| `engine/QuickEntry.kt:34-43` | `QuickOverride.matches` 改 trigger 全等 + 归一化 | C2-2/3/4、C3-10；脚本 `:410`/`:416`/`:436` 断言 |
| **新增** `engine/QuickTrigger.kt` | `MIN = 2` / `MAX = 12` / `isValid()` / `normalize()` 单一来源 | C1-1/C1-2；被 Screen / ViewModel / SyncRepository 共用 |
| `settings/QuickPhrasesScreen.kt:82-83` | `isValidShortcutTrigger` 委托 `QuickTrigger` | C1-1/C1-2 |
| `settings/QuickPhrasesScreen.kt:527 / 636 / 718`（三处 `isValidShortcutTrigger` 调用）+ `:609 / :691 / :766`（三处确认按钮 enabled） | 统一走同一函数 + 禁用原因可见 + >4 提示 + 误弹警示 | C1-6/C1-7/C1-9 |
| `settings/quick/QuickPhrasesViewModel.kt:309` | 删重复校验；非法不静默 return | C1-3 |
| `settings/quick/QuickPhrasesViewModel.kt:306-331`（`setRemoteShortcut`） | 覆盖条目 mode 按 C1-6；不再靠复制远端 content 建立身份 | C1-6/C2-2 |
| `settings/quick/QuickPhrasesViewModel.kt:141-172`（`mergeForDisplay`） | 按 trigger 折叠 + 冲突标记 + 使用记录 id 迁移 | C2-3/C2-5/C2-7 |
| `engine/QuickLocator.kt:137-198` | `buildQuickEngines` / `loadAll` 按 trigger 跨源去重（用户>远端>内置） | C2-4；脚本 `:488` 断言 |
| `data/SyncRepository.kt:107-129` | `else` 分支拆成「有映射 → 更新」/「无映射且不一致 → 冲突」 | C3-1/C3-3/C3-4/C3-7 |
| `data/SyncRepository.kt:197-213` | `enabled` 传 `null` | C3-8 |
| `data/SyncRepository.kt:30-42` | `RuleSyncReport` 加 `conflicts` | C3-3 |
| **新增** `filesDir/quick_phrases_conflicts.txt` 读写 | 冲突清单 | C3-9 |
| `res/values/strings.xml:70` + `values-en/strings.xml:70` | 错误文案改 2–12；新增可达性提示 / 误弹警示 / 冲突文案 | C1-6/C1-7/C1-9/C3-3 |
| `input/IspeechImeService.kt:1266-1288` | **不改代码**（main 已正确）；只加断言锁住 | C4-1 |

### 6.2 数据与资产

- `app/src/main/assets/quick_phrases.txt` 头注释（main 上为「小写 a-z，1-5 位；…5 位码只在拼音态能打全」）→ 改为
  `小写 a-z；编辑器允许 2–12 位；≤4 位两态可敲全，>4 位仅拼音态/英文态可敲全（五笔态走面板筛选）`。
- `sogou_phrases.txt`、导入脚本、`dtwsl` 规则行：**都不改**（C1-10、决策 1 结论 3、C1-8）。
- 设备侧三个规则文件与映射表格式**不变**（无新增列、无版本号）→ 不需要迁移，回滚不需要数据转换。
  新增的只有 `quick_phrases_conflicts.txt`（C3-9）。

### 6.3 必须保持的既有不变量（阶段 2 不得破坏）

1. 五笔态 `MAX_CODE_LENGTH = 4`、拼音态 `MAX_PINYIN_LENGTH = 12`（C1-8）。
2. `minTriggerLength` 单字母防误触守卫与「单字母不触发」默认开（`QuickPhraseEngine.kt:58-59`、`SettingsStore.kt:98`；脚本 `:255-265` 断言）。
3. 精确命中不受扫描上限截断（`QuickPhraseEngine.kt:38-39`；脚本 H 节 `:329-335`）。
4. 面板零磁盘 IO、零 `AppContainer` 依赖（`check_crosscut.py`）。
5. 正文逐字保真：`dtwsl` 281 字 / 11 行 / 空行在第 2、9 行的结构（main 脚本 L4-L7）。
6. 五笔码推导与学习链路（`docs/adr/VOCA-65-phrase-learning.md` 取码口径）不受快捷字母长度影响 ——
   快捷字母长度是**规则表字段**约束，与 `wubi86.dict` 取码长度无关，两者不得共用同一个常量
   （注意 `PhraseUsageStore.MAX_CODE_LENGTH = 64` 是 id 归一用的，与 `IspeechImeService.MAX_CODE_LENGTH = 4` 同名不同义，
   实现时**不要**误改前者，`check_phrase_usage.py:189` 会读它）。

---

## 7. 校验脚本与文档口径的同步调整（点名到行，行号取 main）

### 7.1 `tools/verify/check_quick_trigger.py`

| 位置 | 现状 | 调整为 |
|---|---|---|
| `:17`（模块 docstring B 项） | 「> 12 位（拼音态组合串 `MAX_PINYIN_LENGTH`）直接判死」 | 保留；补一句「编辑器口径与本节同源：2–12，>4 位提示可达性」 |
| `:70` `UNGUARDED_MODES = {"exact","regex"}` | 已有 | **复用为 C1-6 的判定依据**，不新设常数 |
| `:159-166`（>4 且非 exact/regex → warn） | 已有 | 保留 warn；文案点名「这是编辑器允许 5–12 位的前提：>4 位必须 exact/regex」 |
| `:167-170`（>12 → fail） | 已有 | 保留（C1-1 的上限即此值） |
| `:296-299` | 字面量 `isValidShortcutTrigger` + `"in 2..4"` + `'a'..'z'` | 改为断言 ①`QuickTrigger.MAX_TRIGGER_LENGTH == 12` 常量存在、②源码内 `in 2..4` 出现次数 == 0、③`isValidShortcutTrigger` 定义处 == 1、④Screen 三处弹窗 + ViewModel 都调用同一函数 |
| `:410` | `"QuickPhraseCodec.format(entries)" in vm and "QuickOverride.matches(it, original)" in vm` | 保留，说明文字按 C2-2 改口径 |
| `:416` | 「QuickOverride 统一『**label + content** 相同即覆盖』的判定口径」 | → 「QuickOverride 统一『**归一化 trigger** 相同即覆盖』的判定口径」 |
| `:436` | `filterNot { QuickOverride.matches(it, original) }` in vm | 保留（语义随 C2-2 变） |
| `:443-444` | `matchesAny(entry, remoteEntries)` + `readRemoteEntries` | 保留（C3-10）；**新增**断言：`pushLocalRules` 中不存在「无映射 + 正文不一致 → 推送」的可达路径 |
| `:488` | `"QuickLocator.buildQuickEngines(" in chunk and "includeLocal = enableQuick" in chunk` | 保留；**新增**断言：`loadQuickPhrases` 内 `includeLocal`/`includeRemote` 不引用输入方式（C4-1） |
| L8 `:855-873` / L9 `:875-887` | 拼音态 5 位命中 / 五笔态打不全 | **不改**（C1-8、C4-3） |
| 新增 M 节 | — | 冲突与回写安全线：C3-1、C3-3（`conflicts` 字段 + 新文案）、C3-8（无 `enabled = true`）、C2-3（同 trigger 折叠为 1 条）、C2-6（迁移不改写任何一侧）各一条静态断言 |

`tools/verify/check_phrase_usage.py`、`check_input_logic.py`、`check_crosscut.py`：**预期零改动**；
若因 `MAX_CODE_LENGTH` 同名混淆被改动，视为回归，VOCA-208 需拦。

### 7.2 `docs/quick-phrase-enhancement.md`

| 段 | 现状口径 | 改哪一句 |
|---|---|---|
| 12.4 表格「录入」行 | 「只保留小写字母，2–4 位」 | → 「只保留小写字母，2–12 位（校验单一来源 `QuickTrigger`）」 |
| 12.4 表格「为什么限 4 位」行 | 「五笔态上限 `MAX_CODE_LENGTH = 4`，更长的简写永远打不全」 | → 拆两行：「为什么限 **12** 位」= 拼音串天花板（与脚本 `:167` 同源）；「为什么 **>4 位要提示**」= 五笔态敲不全，走面板或拼音态 |
| 12.5 数据落点第 2 条 | 「用户文件里 `label + content` 与某条远端规则相同的条目，即视为该规则的覆盖条目」 | → 改为「**归一化 trigger 相同**即视为覆盖条目」，并补「正文以本地为准，同 trigger 只出一张卡片」 |
| 12.5 数据落点第 3 条 | 「原本的 `label / content / 权重` 保持远端口径」 | → 改为「快捷字母与匹配方式取覆盖条目；**正文取本地条目**；权重仍为远端 + 加成」 |
| 12.5「排序为什么能胜出」 | 靠 `weight + 1000` 与 display 去重 | → 改为「同 trigger 在引擎输入侧按 trigger 去重（用户 > 远端 > 内置）；weight 加成只影响同档相对顺序，不再是正确性保证」 |
| 12.5「同步边界」 | 「跳过 `label + content` 能匹配上的用户条目」 | → 改为 trigger 口径，并新增一段「**回写安全线**」：映射表是唯一同步身份 + 冲突不写 + `conflicts` 文案 |
| 12.6「5 位码的两种输入态」第 2 条（`:658-662`） | 「且 `AppContainer.loadQuickPhrases()` 在五笔态 `includeLocal = false`，内置规则整体不挂载」 | → **删掉这半句**（修正 1 + 修正 2：候选链路两态同挂；面板挂载的五笔态硬编码已由 `07214d6` 删除）。改为「五笔态组合缓冲上限 4 位，5 位码敲不全；可达路径 = 面板筛选点选 / 切拼音态敲全」 |
| 12.6「用户自己的『快捷字母』仍限 2–4 位…与内置码这条 5 位的取舍不冲突」（`:664-665`） | 「不冲突」 | → **冲突了**，必须改：统一为 2–12；新口径下的理由是 C1-6（>4 位覆盖条目用 EXACT，不用 PREFIX，因此在五笔态不会误弹），而不是原来的「两态都能敲全」 |

`docs/sogou-feature-matrix.md`：main 上第 64 行已不含「2–4 位」表述，**无需改动**（本分支基线 `:64` 有，合并窗口以 main 为准）。

---

## 8. 非功能预算

| 指标 | 目标 | 依据 / 测法 |
|---|---|---|
| 候选查询延迟 P95（五笔态，规则总数 ≤ 400） | 不高于改动前 +5 ms | 去重是一次哈希集合扫描，不引入排序；VOCA-208 用 `adb shell dumpsys gfxinfo` 或现有 `recordQuery` 计数对照 |
| 面板首次渲染（8 + 25 + N + M 条） | 无关键词时不重排、不卡顿；> 200 条需实测 | C4-2；真机测（VOCA-208 用例 6） |
| 一次同步的网络往返 | 不增加（仍是 `listRules` + 必要时 `sync/push` + 复核 `listRules`） | C3 只改判定分支，不改调用次数 |
| 冲突可发现时延 | 每次「保存并同步」当场可见；跨启动可追踪 | C3-3、C3-9 |
| 数据丢失风险 | **0**（任何远端正文覆盖都必须由映射表或显式用户动作授权） | C3-1…C3-7 |

## 9. 失败模式清单与回滚预案

| 失败模式 | 现在的表现 | 契约后的表现 |
|---|---|---|
| 设备残留同 trigger 过期条目 + 用户点保存并同步 | 云端正文被覆盖并报成功 | 报「1 条冲突，未上传」，云端不动（C3-1/C3-3） |
| 远端正文被人在 csBaby 后台改了 | 下次 push 按本地指纹覆盖回去 | 有映射 → 按 C3-4 允许更新（本地是自己拥有的规则，语义正确）；无映射 → 冲突（C3-1） |
| 映射表损坏 / 换机恢复 | keyword 兜底 → 走覆盖分支 | keyword 兜底只用于「正文一致时补映射」（C3-2），不一致一律冲突 → 换机不再静默覆盖 |
| 同 trigger 用户条目与内置资产撞码 | 两条候选并存，首位不可预测 | 用户条目胜出，只有一条（C2-4） |
| >4 位简写在五笔态 | 编辑器拒绝，用户以为正文太长（修正 5） | 编辑器接受；EXACT 语义下五笔态不误弹；面板可点选（C1-6/C4-1） |
| 用户显式给长正文 + >4 位选 PREFIX | 无此路径（被禁） | 允许 + 误弹警示（C1-9），用户自担 |
| `sync/push` 失败 | `runCatching` 吞掉，复核后计 pending | pending 语义保持；冲突计数独立于 pending（C3-3） |
| 冲突文件写失败 | — | 清单丢失只导致下次重算，不影响正确性；**禁止**因写失败而降级为「直接推送」 |
| 有人再次顺带删掉面板挂载 | 已发生过一次（VOCA-29 → `07214d6` 才修回） | C4-1 断言拦住（决策 4 交付项 2） |

**回滚顺序**（最小代价路径）：
① C3 不可单独回滚 —— 若必须回滚则整个 PR 一起回滚，并在发布说明标注数据风险；
② C4 可单独回滚，只影响可见性（且 main 现状已是修复态，回滚等于退回 `2a1883e` 的缺陷）；
③ C1 可单独回滚为 4，>4 的已存条目继续有效（读取侧不校验），只是不能编辑；
④ C2 回滚需同时清掉冲突文件，否则冲突徽标会指向已不存在的判定口径。

## 10. 可观测性（新增，必须实现）

- `pushLocalRules` 返回的 `RuleSyncReport` 增加 `conflicts`，在同步页 / 快捷短语页文案可见（C3-3）。
- 冲突详情视图必须显示：本地正文与远端正文的**逐字对照**（含长度与首行）以及远端 id —— 用户判断「以谁为准」的唯一依据。
- 日志：冲突发生时 `Log.w`，字段只允许 `trigger` / `remoteId` / 两个正文的**长度**与 sha256 前 12 位。
  **禁止**把正文打进日志（281 字话术可能含客户信息）—— 与共享规则第 8 条脱敏要求一致。
- 失败如何被发现：同步页在 `conflicts > 0` 时以 error 色调显示计数，不依赖用户读文案。

## 11. 仍需人类拍板的项（含推荐）

| # | 问题 | 推荐 | 备注 |
|---|---|---|---|
| 1 | 五笔态 >4 位简写「只能面板点选 / 切拼音态」是否可接受 | **接受**（决策 1 方案 C + 决策 4） | 备选 E（提高 `MAX_CODE_LENGTH`）改五笔肌肉记忆；F（独立快捷码缓冲）是新特性需另立 issue |
| 2 | 冲突默认口径 | **默认不写任何一侧 + 显式二选一** | 备选「以云端为准拉平」实现最省事，但丢用户离线未上传的编辑，且与「本地为准」的所有权口径矛盾 |
| 3 | 是否向 csBaby 后端提 `updated_at` / 版本号 / 可用 PUT-DELETE | **提，但不阻塞** | 有 `updated_at` 才能把「谁更新」变成可判定，冲突可自动裁决。需人类提供：后端仓库权限或接口文档，并确认 12.3 里「`sync/push` 生效范围未经确认」这笔口头债的真实语义 |
| 4 | C3 是否作为发布硬门禁 | **是**，P0 未清零不得进合并窗口 / 不出包 | 已写进 VOCA-208 用例 1 红线，本 ADR 确认其定级 |
| 5 | VOCA-207 分支基线 | **从 `origin/main` 起**（修正 3） | 从 `ac4e4ce` 起会：看不到 `dtwsl` 与 L 节断言、把 main 已删的面板硬编码重新改一遍 |
| 6 | VOCA-29 那次「顺带删挂载」是否要追责/补 ADR | 建议**补一篇输入态 × 规则来源的挂载矩阵**进 `docs/adr/` | 不在本 issue 范围，建议另开 issue（§12） |

## 12. 顺带发现（不在本 issue 范围，建议另开 issue）

- 截图 4 的「找到 93 条 · 关键词 dtwsl」：`data/PhraseSearch.kt` 的拼音首字母档位（`INITIALS = 100` `:25`、
  `MIN_INITIALS_QUERY = 2` `:234`、`DEFAULT_LIMIT = 200` `:219`）会让 5 个字母的首字母查询命中大量无关条目。
  数字本身**待确认**（需设备上真实规则总数与正文首字母才能复算），但这个计数把用户引导成「云端同步下来 93 条」，
  与抱怨 ③ 直接相关。建议：搜索计数文案区分「字面命中」与「联想命中」，或首字母档位不计入标题计数。
- `IspeechImeService.MAX_CODE_LENGTH = 4` 与 `PhraseUsageStore.MAX_CODE_LENGTH = 64` 同名不同义，
  是本次分析中真实踩到的混淆点（`check_phrase_usage.py:189` 读的是后者）。建议改名（如 `MAX_COMPOSE_CODE_LENGTH` / `MAX_USAGE_CODE_LENGTH`），属纯改名 chore。

---

## 附：给阶段 2（VOCA-207）的「实现方还要问的问题」自查表

按本 ADR 落地时，以下问题必须已有答案；若发现 ADR 没答，先在本 issue 评论追问，不要自行决定：

1. `QuickTrigger.normalize` 是否也用于 `RemoteRuleMap` 的 key？（应统一，否则映射表与判定口径分叉）
2. 冲突文件的写入时机与 `save()` / `syncToCloud()` 的先后？（不得在写盘失败时降级推送）
3. `mergeForDisplay` 返回单卡片后，`remoteInfo` 的 key（展示 id）如何保持稳定，使「恢复默认」仍能定位原远端条目？
4. 「采用云端正文」后是否需要 `invalidateEngine()`？（需要，与 `save()` 同口径）
5. C1-7 的「禁用原因可见」是否覆盖三个弹窗 + 远端简写入口共 4 处？（是，见 6.1）
6. C1-9 的误弹警示是否在 mode 与 trigger 长度都变化时实时重算？（是，纯计算，不读盘）
7. `UNGUARDED_MODES`（脚本 `:70`）与 Kotlin 侧 C1-6 的判定，谁是权威？（Kotlin 侧 `QuickTrigger` 为权威，脚本断言与之对齐，不得反向让脚本定义语义）
