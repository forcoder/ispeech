# Design

## Context

见 `proposal.md` 的 Why。设计只需处理现有约束：

- **面板分支是提前 return 的**。`IspeechImeService.handleSoftKey()` 里
  `if (panel == Panel.QUICK_PHRASE)` 分支对 `CHAR` / `BACKSPACE` / `SPACE` 处理后都直接
  `return`，不进入 `handleChar()` / `handleBackspace()` / `handleSpace()` 主链路。因此改造
  面板内的字符语义**不会**影响五笔 / 拼音 / 英文上屏。
- **组合状态是服务级单例字段**：`buffer`（拼音/五笔编码）、`candidates`、`statusText`、
  `codeOverride`。`renderCandidateView()` 用 `statusText ?: codeOverride ?: buffer` 作为
  候选条编码。面板打开时这些字段当前完全没被使用。
- **`PinyinEngine` 已是完整引擎**：`lookup(input, limit)` 返回 `LookupCandidate`，
  支持全拼、简拼、前缀、模糊音，索引在构造时建好。`displayName = "pinyin86"`，
  `available` 反映码表是否加载。
- **面板约束**：`QuickPhrasePanel` 只做内存过滤与渲染，**零磁盘 IO**（文件头注释明确约定）。
  搜索逻辑集中在 `PhraseSearch`，构造参数只有两个纯函数。
- **`PhraseSearch` 已支持中文**：完全 / 前缀 / 包含三档用 `regionMatches(ignoreCase = true)`
  等长比较，中文关键词本来就命中。缺的只是产生中文关键词的通道。

## Goals / Non-Goals

**Goals:**

- 面板打开 + 拼音模式时，字母键进入拼音组合，候选条显示汉字候选，选字后进搜索框。
- 面板内组合态与已提交态在搜索框上可见、可分别退格。
- 面板内同时保留「直接打 ASCII 筛拼音首字母」的既有习惯。
- 恢复设置页搜索栏，与面板共用同一匹配规则。

**Non-Goals:**

- 不做完整拼音输入法的智能纠错、云候选、长句联想——面板内只需要「选中一个词/短语_display」。
- 不改 `PhraseSearch` 的评分公式与档位常量。
- 不改快捷短语文件格式、csBaby 同步协议、`PhraseUsageStore` 存储格式。
- 不引入第三方拼音库；复用现有 `pinyin86.dict` 与 `PinyinEngine`。
- 不为设置页单独实现搜索逻辑（它只是把同一个 `PhraseSearch` 接到 Compose 输入框）。

## Decisions

### D1. 复用 `PinyinEngine.lookup()`，而不是再写一套组词

**选择**：服务在面板打开时持有一个 `PinyinEngine` 引用（来自 `AppContainer` 已有的引擎实例），
字母键追加到面板私有的拼音组合串后调用 `lookup()` 取候选。

**理由**：`PinyinEngine` 已覆盖全拼 / 简拼 / 前缀 / 模糊音，索引已建好，零额外成本。
自建一套「短语专用」拼音匹配会与主输入法的行为漂移，且重复实现 `pinyin86.dict` 解析。

**备选**：把 `PhraseSearch` 扩展成直接接受拼音并按音节匹配短语正文。
否决——那是「用拼音搜短语」，仍不解决「输入框里要出现中文」这一体验目标。

### D2. 面板私有的组合状态，复用 `buffer` / `candidates`，但不复用 `statusText`

**选择**：面板打开时把 `buffer` 作为**拼音组合串**使用（进入面板前已 `commitPending()`，
所以是干净的），`candidates` 存 `PinyinEngine` 返回的候选。搜索框的**已提交关键词**
继续放在 `panelKeyword` 里，两者互不覆盖。候选条编码用 `buffer` 直接渲染。

**理由**：`buffer` / `candidates` / 分页（`pageIndex` / `pageCount`）已有完整的渲染与翻页
通路，复用它等于「面板内免费得到候选条 + 翻页 + 点选」。而 `statusText` 在面板分支里
已被用来显示「快捷短语 / 筛选：xxx」这类状态文案（见 `setPanelKeyword()`），复用它会
把两件事混在一起。

**代价与规避**：`buffer` 的语义被面板临时重载，因此**必须在关闭面板时清空**，
并在打开面板时确认 `buffer` 为空。`openPanel()` 已有 `commitPending()`，需补一条清空断言。

### D3. 空格键的语义优先级：候选 > 上屏第一条短语

**选择**：面板内按空格时，若拼音组合非空且存在候选 → **选中当前高亮候选**（与主输入法
一致）；若组合为空 → 维持现有行为「上屏第一条匹配的短语」。

**理由**：用户已经习惯「空格 = 选第一个候选」，面板内保持一致的肌肉记忆；而组合为空时
「空格 = 上屏第一条」是现有面板特性，不应破坏。

**备选**：空格永远上屏短语。否决——组词后按空格会跳过选字直接上屏短语，体验断裂。

### D4. 无候选时回落为 ASCII 关键词，而不是丢弃输入

**选择**：拼音模式下若 `PinyinEngine.lookup()` 返回空（码表未加载、或输入不是合法拼音），
该字母序列**不进入组合**，按现有路径作为 ASCII 关键词追加到 `panelKeyword`。
即：`available == false` 时面板退化为当前行为。

**理由**：`pinyin86.dict` 加载失败是可能的运行时状态；此时让用户仍然能用拼音首字母搜，
比让面板彻底不能输入要好。这也是规格里「无候选时回落字面匹配」场景的来源。

**权衡**：用户可能看到「打 `fapiao` 结果变成 ASCII 关键词」的意外行为。
缓解——只要 `lookup()` 有结果就走组合；无结果本身已经是异常路径。

### D5. 英文模式下不组词

**选择**：`mode == Mode.ENGLISH` 时面板字母键**不**进入拼音组合，直接作为 ASCII 关键词。

**理由**：面板搜索框右侧已有「中/英」徽标复用 `mode`（`setInputLanguage()`）；英文态
意味着用户想按 ASCII 搜，组词会与这个可见状态矛盾。

### D6. 退格分层：先组合、后已提交

**选择**：`backspacePanelKeyword()` 改为两级——`buffer` 非空时删组合末字符；
`buffer` 为空时删 `panelKeyword` 末字符。

**理由**：对应 D2 的双层状态，退格必须按「最近输入的先撤销」顺序，否则组合中的错字无法改。

### D7. 设置页从 HEAD 还原，不改搜索实现

**选择**：`git checkout HEAD -- app/src/main/java/com/ispeech/settings/QuickPhrasesScreen.kt`
恢复 408 行完整文件，其搜索栏接线走已有的 `viewModel.setKeyword()` →
`withMatches()` → `PhraseSearch`。

**理由**：`QuickPhrasesViewModel` 的搜索通路完好，只是 UI 被删。还原比重新实现风险低。
Compose 的 `OutlinedTextField` 天然支持系统输入法中文输入，满足设置页的中文搜索需求。

### D8. 规格里的中文匹配断言加进 `check_phrase_usage.py`

**选择**：在既有 `tools/verify/check_phrase_usage.py` 增加「中文关键词命中 / 档位不变量 /
面板组合链路」断言，不新建脚本。

**理由**：该脚本已经是「源码漂移 + Store 行为 + 搜索行为」的集中校验点
（`docs/quick-phrase-enhancement.md` §441 记录 31 项断言）。新增脚本会分裂校验入口。

## Risks / Trade-offs

- **[`buffer` 语义被重载，可能泄漏到主链路]** → 面板关闭路径统一清空 `buffer` 与
  `candidates`；`tools/verify/check_input_logic.py` 增加断言「`panel != NONE` 分支必
  在 `return` 前处理」。
- **[拼音码表未加载时用户观感异常]** → D4 的回落路径 + `statusText` 提示，不静默失败。
- **[候选条与短语列表同时占据屏幕高度]** → 面板已有 `maxPanelHeightDp = 200`，候选条
  是键盘区固定部件，不额外增高；实机确认在 `tasks` 里作为显式验证点。
- **[硬件键盘路径需要同步改]** → `onKeyDown()` 的 `KEYCODE_A..KEYCODE_Z` 分支与软键盘
  分支必须共用同一个入口方法，避免两套语义。设计上抽一个 `handlePanelLetter(Char)`。
- **[设置页还原可能与工作区其它未提交改动冲突]** → 还原前先确认该文件之外无引用变化；
  `IspeechNavGraph.kt:67` 的调用签名与 HEAD 一致（`QuickPhrasesScreen(onBack, onOpenSync)`）。

## Migration Plan

纯客户端改动，无数据迁移。

1. 先还原 `QuickPhrasesScreen.kt` 让工作区恢复可编译（独立可验证的一步）。
2. 再改面板输入链路。
3. 无回滚数据风险；如需回滚，两个改动可独立回退。

## Open Questions

- 面板内是否需要「长按空格直接上屏第一条短语」作为组词态下的逃生通道？可延后到实机
  试用后再定，不影响规格与任务拆分。
- 候选条在面板内的分页条数是否需要按面板高度调整？当前沿用键盘默认分页，实机观察后再定。