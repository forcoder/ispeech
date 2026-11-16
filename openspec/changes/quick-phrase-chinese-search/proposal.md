# Proposal

## Why

快捷短语搜索目前**无法输入中文关键词**。`IspeechImeService.handleSoftKey()` 在面板打开时把
`KeyAction.CHAR` 直接追加进 `panelKeyword`，拼音模式下也不例外，因此输入法只能产生 ASCII
字母；用户只能靠「拼音首字母」（`fp` → 发票）间接命中中文条目。中文母语用户的第一直觉是直接
打「发票」两个字，这条路现在是断的。

同时发现 `QuickPhrasesScreen.kt` 在工作区被删到只剩 47 行（HEAD 为 408 行），主 Composable
`QuickPhrasesScreen()` 缺失但 `IspeechNavGraph.kt:67` 仍在引用，工作区当前无法编译；设置页
的搜索栏（`docs/quick-phrase-enhancement.md` §12.1 记载的 `QuickSearchBar`）也随之丢失。

## What Changes

- **面板内支持拼音组词搜索**：快捷短语面板打开时，拼音模式下的字母键不再被直接当作 ASCII
  搜索词，而是进入拼音组合；候选条显示组词候选，选字后把中文词追加进面板搜索框，
  由 `PhraseSearch` 做中文完全 / 前缀 / 包含匹配。
- **面板搜索框状态可视化**：组合中显示拼音原文与候选，上屏后显示已选中文词；退格优先删
  拼音组合，组合为空时再删已上屏中文。
- **`PhraseSearch` 匹配能力对齐**：确认并保持中文关键词走「完全 > 前缀 > 包含」三档，
  拼音首字母档仅在 ASCII 关键词时启用（现有行为，不改排序不变量）。
- **恢复设置页搜索栏**：从 HEAD 还原 `QuickPhrasesScreen.kt`，接回已有的
  `QuickPhrasesViewModel.setKeyword()` / `withMatches()`。Compose 输入框天然支持系统输入法
  输入中文，无需额外改动。
- **不改变**：`PhraseSearch` 的评分公式与档位间距、`PhraseUsageStore` 的 id / 去重规则、
  面板的零磁盘 IO 约束、快捷短语文件格式。

**BREAKING**：无。

## Capabilities

### New Capabilities

- `quick-phrase-search`: 快捷短语的关键词搜索行为，覆盖输入法面板内的中文组词输入与
  设置页搜索栏，以及跨「触发码 / 名称 / 正文 / 分类」的匹配档位与排序规则。

### Modified Capabilities

（无。项目此前没有 OpenSpec 规格，本变更是首个 capability。）

## Impact

**受影响代码**

| 文件 | 影响 |
| --- | --- |
| `app/src/main/java/com/ispeech/input/IspeechImeService.kt` | `handleSoftKey()` 的 `Panel.QUICK_PHRASE` 分支：字母键改走拼音组合而非直接拼进 `panelKeyword`；`onKeyDown()` 硬件键盘路径同步 |
| `app/src/main/java/com/ispeech/input/QuickPhrasePanel.kt` | 搜索框展示组合态（拼音原文 + 已选词），候选 / 选中态渲染 |
| `app/src/main/java/com/ispeech/engine/PinyinEngine.kt` | 仅复用其 `lookup()`，不改接口；需确认 `available` 为 false 时的降级路径 |
| `app/src/main/java/com/ispeech/data/PhraseSearch.kt` | 预期不改评分逻辑；若发现中文 `regionMatches` 大小写处理有偏差则修正 |
| `app/src/main/java/com/ispeech/settings/QuickPhrasesScreen.kt` | 从 HEAD 还原 371 行，接回搜索栏 |
| `app/src/main/res/values/strings.xml` | 新增组合态 / 候选态提示文案 |
| `tools/verify/check_phrase_usage.py` | 新增中文关键词匹配与面板组词链路的静态断言 |

**不受影响**

- 五笔 / 拼音主输入链路（`handleChar()`、`buffer`、`commitTop()`）——面板分支在此之前就
  `return`，不进入主链路。
- 快捷短语文件格式与 csBaby 远端同步协议。
- `PhraseUsageStore` 的存储格式。

**风险**

- 面板打开时字母键语义改变，用户已形成的「直接打字母筛首字母」习惯需要保留：方案是在
  组合有候选时优先走中文候选，**无候选或用户按空格且无选中项时**仍按 ASCII 关键词过滤。
  具体取舍在 design.md 决定。