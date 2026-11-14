# 九宫格 T9 + 颜文字 / 更多符号 + 输入法切换

本文件说明 **Agent B 交付/接线的部分**：

| 文件 | 状态 | 内容 |
|---|---|---|
| `app/src/main/java/com/ispeech/input/keyboard/T9Layouts.kt` | 新增 | 九宫格 T9 键位 + 数字串到字母串映射 |
| `app/src/main/java/com/ispeech/input/keyboard/KaomojiData.kt` | 新增 | 颜文字 7 类（103 条）+ 更多符号 6 类（150 个） |
| `app/src/main/java/com/ispeech/input/keyboard/KeyDef.kt` | 修改 | `KeyboardLayoutKind.T9` + `KeyAction.SWITCH_IME`（枚举末尾） |
| `app/src/main/java/com/ispeech/input/keyboard/KeyboardLayouts.kt` | 修改 | `pages()` 加 T9 分支；符号页/表情页末尾追加；数字页加「9键」 |
| `app/src/main/java/com/ispeech/input/SoftKeyboardView.kt` | 修改 | 工具栏 8 键 + `HorizontalScrollView` 兜底最小宽度 |

校验：`python3 tools/verify/check_t9.py` → 87 PASS / 0 FAIL / 1 WARN（退出码 0）；
`python3 tools/verify/check_static.py` → 5/5（退出码 0）。
**服务侧行为（pageOffset 透传、T9 缓冲、SWITCH_IME 面板）由主 Agent 实现，见 4.2（已落地）**。

---

## 1. 键位设计

### 1.1 九宫格数字页 `T9Layouts.digitPage()`（title = 九宫格）

```
|--------|--------|--------|--------|
|   1    | 2ABC   | 3DEF   |   ⌫    |   1 = 标点页入口（PAGE_NEXT）
|--------|--------|--------|--------|
| 4GHI   | 5JKL   | 6MNO   |   ↵    |   ↵ = 回车 / 发送（KeyStyle.PRIMARY）
|--------|--------|--------|--------|
| 7PQRS  | 8TUV   | 9WXYZ  |   ☺    |   ☺ = 颜文字首页（EMOJI + pageOffset）
|--------|--------|--------|--------|
|   *    |   0    |   #    |  中/英 |   * = 符号1；# = 更多符号；0 = 空格
|--------|--------|--------|--------|
```

`2`-`9` 的 `KeyDef.char` 输出数字本身（服务侧用 `KeyAction.CHAR` + `T9Layouts.isT9CodeKey` 判断进 T9 缓冲）；
`1` 用 `KeyAction.PAGE_NEXT` 在「九宫格 / 标点」两页间循环 —— 这样不依赖服务侧支持 `pageOffset` 也能翻页。

### 1.2 标点页 `T9Layouts.punctuationPage()`（title = 标点）

3 行 x 8 个中文标点 + 底行 `1`（`PAGE_NEXT` 回数字页）/ `⌫` / `空格` / `↵`，共 24 个字符，页内无重复。
与「符号1」内容有意重合（`1` 键的语义就是常用标点）。

### 1.3 颜文字 / 更多符号页（追加式）

- `T9Layouts.kaomojiPages()`：每个 `KaomojiData.categories` 分类一页，每行 3 条，页标题 `颜·分类名`（避开表情页已有的「动物」）；
  键宽权重 2f 让 `SoftKeyboardView.keyTextSize` 对 `weight > 1.4f` 的键降一档字号（九宫格布局 18sp，
  26 键布局 15sp），长颜文字才放得下。字号 / 键面规格见 `docs/keyboard-ui.md`。
- `T9Layouts.moreSymbolPages()`：每个 `KaomojiData.symbolCategories` 分类一页，每行 8 个单字符。
- 两者都追加在**各自布局的末尾**：`KeyboardLayouts.symbols()` 追加更多符号、`KeyboardLayouts.emoji()` 追加颜文字，
  页码条（`SoftKeyboardView.buildPageBar`）会自动显示「颜·开心 9/15 点此翻页」。

### 1.4 九宫格入口：数字页（123）底行

九宫格入口**不在工具栏**，在数字页第 4 行：

```
123 页第 4 行： [ABC] [9键] [空格] [↵]        ← 新增的只有「9键」，其余三键原样保留
权重：           1.5    1.5     4     1.5
```

`KeyboardLayouts.t9SwitchKey()` = `KeyDef.fn(KeyAction.LAYOUT, "9键", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.T9)`。
选择理由：数字页第 1 行的 10 个数字键和第 2、3 行的 `- / : ; ( ) ¥ @ "` 与 `#+= , . ? ! ' ⌫` 一个都没动
（`check_t9.py` 断言 2-4 行键数仍是 `[9, 7, 4]`、`DIGIT_ORDER` 仍是 10 个）；
底行本来就是布局切换区（`ABC` 在左），把 `9键` 插在 `ABC` 右侧，切换入口相邻、手指不用换位置。
代价是底行从 3 键变 4 键，空格占比从 4/7 降到 4/8.5 ≈ 47%（仍然最宽）。

### 1.5 工具栏：8 键

```
[⌨] [符] [☺] [短语] [剪贴] [AI] [🎤] [⚙]
 1.0  1.0  1.0  1.3    1.2   1.1  1.1  1.0   ← 权重和 8.8
```

- 第 1 键 `KeyAction.SWITCH_IME`：切系统其它输入法（服务侧实现），键面是键盘图标（`ic_ime_switch`）。
- 第 2 键「符」：符号页（搜狗侧没有对等的符号键图标，故保持文字）。
- 第 3 键「☺」：表情页（颜文字追加在其末尾）。
- 第 4~6 键：快捷短语 / 剪贴板 / AI。
- 第 7 键「🎤」：语音输入。
- 第 8 键「⚙」：设置。
- **没有「中/英」键**：中英切换由**字母页左下角的 Shift 键**承担（中文态下改造成「中/英」，
  点击上报 `KeyAction.MODE_SWITCH`，见 `docs/keyboard-ui.md`）。
- 工具栏键背景统一交给 `toolbarKeySelector()`：**默认透明，只有当前激活项（`state_selected`）显示主题色底**
  （`primaryPressed`），便于一眼看出正在使用哪个功能。
- 没有独立的「颜」键：颜文字跟在表情页末尾，点 `☺` 后靠页码条翻到。
- 没有「9键」键：见 1.4。
- 8 键在窄屏仍会挤到 40dp 以下，所以外层是 `HorizontalScrollView(isFillViewport = true)`（**不换行、不加第二行**）：
  放得下时整行铺满（视觉与旧版一致），放不下时横向滚动，每键靠 `TOOLBAR_KEY_MIN_WIDTH_DP = 40` 兜底 ≥ 40dp。
- 字号 `TOOLBAR_TEXT_SIZE = 12f`。

**宽度估算（393dp 屏）**：视口 387dp 扣掉工具栏左右 padding 6dp，8 键各带左右各 4dp margin（共 64dp），
净宽约 317dp / 8 ≈ **39.6dp**，仍在 40dp 兜底下沿 → 视具体 ROM 字体会轻微横滑；320dp 窄屏必然横滑。

---

## 2. 数字串到字母串映射

```kotlin
T9Layouts.t9Letters('2')                  // "ABC"（2=ABC 3=DEF 4=GHI 5=JKL 6=MNO 7=PQRS 8=TUV 9=WXYZ）
T9Layouts.t9DigitOf('q')                  // '7'
T9Layouts.t9ToLetterPatterns("23")        // [ad, ae, af, bd, be, bf, cd, ce, cf]（小写、笛卡尔积）
T9Layouts.t9ToLetterPatterns("79").size   // 16 = PQRS x WXYZ
T9Layouts.t9ToLetterPatterns("2", lookup) // 展开 + 查词库，返回命中的候选字母串
```

- 只取 `2`-`9`，`0`/`1`/`*`/`#` 与其它字符忽略；展开上限 `MAX_LETTER_PATTERNS = 2048`；
- 第二个重载参数是 `fun interface T9LetterLookup`，服务侧可写 `T9LetterLookup { pattern -> engine.lookup(pattern, 9).map { it.insertText } }`；
- `t9DigitOf(letter)` 是反查，用于把一个五笔码转成对应数字串。

---

## 3. 与搜狗九宫格五笔 / 拼音的差异

| 维度 | 搜狗九宫格 | 本实现 | 说明 |
|---|---|---|---|
| 布局栅格 | 3x4 数字栅格 + 右侧「退格 / 回车」竖排 | 4x4（3 列数字 + 1 列功能） | `SoftKeyboardView` 只有按 weight 均分的一维行，没有跨行合并（no rowspan） |
| `1` 键 | 键面画「，。？」等标点图形 | `1` = 标点页入口（`PAGE_NEXT`） | 行为一致，键面文字从简（`TextView` 单行） |
| `0` 键 | 键面写「空格」/「0」 | 键面 `0`，行为 `KeyAction.SPACE` | 九宫格里 0 就是空格 |
| `*` / `#` | `*` 符号页、`#` 更多设置 | `*` 符号1、`#` 更多符号 | 不弹系统菜单，保持键位表驱动 |
| 九宫格 / 26 键切换 | 工具栏「9键/26键」按钮 | 数字页底行「9键」 | 工具栏 8 键已有归属，入口让给数字页 |
| 切其它输入法 | 工具栏地球 / 键盘图标 | 工具栏第 1 键（键盘图标） | 行为由服务侧实现（`KeyAction.SWITCH_IME`） |
| 拼音联想 | 自建拼音词库 + T9 索引 | 数字串展开成字母组合后查**九宫格专用拼音引擎** | `pinyin86.dict` + `PinyinEngine`（`AppContainer.pinyinEngine`，与「拼音混输」开关无关）；长串靠组合枚举，见第 6 节 |
| 候选条 / 翻页 | 九宫格候选条 | 复用现有 `CandidateView` | 候选条、页码器、1-9 角标都是现成的 |

---

## 4. 接线现状

### 4.1 已落地（Agent B）

```kotlin
// KeyDef.kt:188（枚举末尾，既有值 ordinal 不变）
T9("九宫格"),
// KeyDef.kt:78（枚举末尾）
SWITCH_IME,

// KeyboardLayouts.kt:48-50 —— 数字页底行的九宫格入口（第 137 行调用）
private fun t9SwitchKey(): KeyDef =
    KeyDef.fn(KeyAction.LAYOUT, "9键", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.T9)

// KeyboardLayouts.kt:230 —— 符号页末尾追加更多符号
fun symbols(): List<KeyboardPage> = listOf(/* 符号1、符号2 */) + T9Layouts.moreSymbolPages()

// KeyboardLayouts.kt:251 —— 表情页末尾追加颜文字
fun emoji(): List<KeyboardPage> = EmojiData.categories.map { /* 现有实现 */ } + T9Layouts.kaomojiPages()

// KeyboardLayouts.kt:256-262 —— 分派
KeyboardLayoutKind.T9 -> T9Layouts.pages()

// SoftKeyboardView.kt:262-285 —— 8 键工具栏
// （外层 HorizontalScrollView 见 :249-258，最小宽度常量见 :599）
private fun toolbarKeys(): List<KeyDef> = listOf(
    KeyDef.fn(KeyAction.SWITCH_IME, "⌨输入法", weight = 1.6f, style = KeyStyle.SPECIAL),
    /* 符、☺、短语、剪贴、AI、🎤、⚙ 原样保留（工具栏 8 键；中/英 由字母页 Shift 键承担） */
)

// SoftKeyboardView.kt:129 —— setLayout 支持 page（默认值不变，旧调用点不受影响）
fun setLayout(kind: KeyboardLayoutKind, page: Int = 0)
```

### 4.2 服务侧接线（主 Agent，已落地并真机验证）

`IspeechImeService.kt` 三处：

```kotlin
// 1) pageOffset 透传（九宫格 # / ☺ 直达页）
KeyAction.LAYOUT -> key.layout?.let { switchLayout(it, key.pageOffset) }
private fun switchLayout(kind: KeyboardLayoutKind, page: Int = 0) { /* … softKeyboard?.setLayout(kind, page) */ }

// 2) SWITCH_IME → 输入法列表面板（onPick 走 InputMethodManager.setInputMethod，
//    失败兜底 showInputMethodPicker；见 InputMethodPanel + ime_input_view.xml）
KeyAction.SWITCH_IME -> openPanel(Panel.SWITCH_IME)

// 3) T9：2-9 进 t9Digits 缓冲（不直接上屏数字）
if (softKeyboard?.layoutKind == KeyboardLayoutKind.T9) {
    T9Layouts.codeDigitOf(key)?.let { handleT9Digit(it); return }
}
// refreshT9Candidates()：展开字母组合 → 后台线程逐个查「九宫格专用拼音引擎」→ 按 kind.order/权重重排
// commitT9()：空格 / 回车 / 离开九宫格时上屏首选，无候选则数字串原样上屏
```

行为细节（避免和五笔缓冲打架）：

- 查询源是 `AppContainer.pinyinEngine()`（数字 → 拼音，与搜狗九宫格一致，不跟随「拼音混输」开关）；
  拼音词表加载失败才退回复合引擎，这时命中的会是五笔码（有候选胜过没候选）。
- `t9Digits` 与 `buffer` 互斥；进入九宫格输入前若有残留五笔组合，先 `commitTop()`。
- ⌫ 优先删数字串；Esc 丢弃数字串；`中/英` 切换会先落定数字串（复用既有 `commitTop` 语义）。
- 九宫格候选不写用户词库（`learn()` 里 `t9Digits` 非空直接返回）：展开出来的字母串写进五笔词库会污染五笔编码。
- 数字串上限 9 位、单次最多展开 192 个字母组合（`T9_PATTERN_LIMIT`），查询在 `Dispatchers.Default` 上跑，结果用序号校验，防止旧结果覆盖新输入。
- 快捷短语 / 用户规则**不参与九宫格候选**（九宫格查的是拼音引擎）；要用短语走工具栏「短语」面板。

---

## 5. 校验

```bash
python3 tools/verify/check_t9.py     # 87 PASS / 0 FAIL / 1 WARN，退出码 0
python3 tools/verify/check_static.py # 5 PASS / 0 FAIL / 0 WARN，退出码 0
```

覆盖项：T9 字母集合与 26 字母唯一性、数字串展开语义（Python 复刻）、栅格 0-9 全覆盖、
数字页 4x4、标点页 24 字符、颜文字/更多符号分类数与全局去重（含与 `EmojiData`/`KeyboardLayouts` 交叉去重）、
声明名冲突、**KeyDef 字段对齐**（42 处键位构造的具名参数必须属于真实参数）、
枚举合法性（`KeyAction` 26 值与基线逐一比对，只有 `SWITCH_IME` 是新加的）、
`pages()` 5 分支、追加页位置、跳页下标算术、**工具栏 8 键的键序/权重/最小宽度/HorizontalScrollView**、
**数字页 2-4 行键数 [9, 7, 4] 与 10 个数字键未动**。

那 1 条 WARN 是已知并已规避的：颜文字分类名 `动物` 与 `EmojiData` 的「动物」页同名，
但页标题统一是 `颜·动物`，页码条不会出现两个「动物」页。

---

## 6. 未验证 / 缺口（诚实标注）

1. **没有编译验证**：本机无 JDK / Android SDK，三个被改文件只做了文本级校验（括号配平、字段对齐、枚举合法性），
   真实 `./gradlew :app:assembleDebug` 未跑。`HorizontalScrollView` / `fillViewport` / `minWidth` 的实际测量行为未验证。
2. **`SWITCH_IME` 服务侧未实现**：`handleSoftKey` 的 `when (key.action)`（`IspeechImeService.kt:249-273`）没有 `else` 分支，
   加了这个枚举值后它是非穷尽 `when`（Kotlin 1.9 下是警告，不是错误；若工程开了 `allWarningsAsErrors` 就会变红）。
   主 Agent 加上 `KeyAction.SWITCH_IME -> ...` 分支即可。
3. **九宫格页没有直达 `ABC` 的键**：T9 数字页第 4 行是 `* / 0 / # / 中/英`，回字母页要经 `☺` 或 `*`/`#` 再点 `ABC`
   （或页码条）。若要一键回字母，把该行 `中/英` 换成 `letterSwitchKey()` 即可（一行改动，本 Agent 未擅自改）。
4. **窄屏横滑未验证**：320dp 屏上工具栏会横向滚动（按 40dp 下限的必然结果），实际手感未在真机确认。
5. **T9 拼音只做了「展开」**：`pinyin86.dict` + `PinyinEngine` 可以接收展开出的拼音串（`ni` 来自 64，`nihao` = 3^5 = 243 组），
   但 6 位以上数字串会被 `MAX_LETTER_PATTERNS = 2048` 截断；稳妥做法是用音节表切分 + `t9DigitOf()` 反查建索引（未实现）。
   另外 `PinyinEngine` 目前还没被 `QuickLocator.buildComposite`（`QuickLocator.kt:60-101`）装进复合引擎。
6. **`pageOffset` 未接通服务侧**：在 4.2 的第 1 条落地前，九宫格的 `#` / `☺` 只会跳到符号 / 表情的第 1 页
   （`setLayout` 内部 `coerceIn`，不会崩）。
7. **未做真机渲染**：`0` 当空格的手感、`颜·动物` 等标题在页码条上的长度、颜文字 18sp 下的截断均未验证。
