# csBaby 快捷短语增强：收藏 / 最近使用 / 使用计数 / 分类搜索

> 本文件由并行开发 Agent C 产出。Agent C 独占并已改动 `input/QuickPhrasePanel.kt`
> （收藏 / 最近胶囊 + 星标按钮），其余接线（`AppContainer` / `IspeechImeService` / `ClipboardPanel`）
> 由主 Agent 完成。本文档给出存储格式、id / 去重 / 迁移策略、搜索评分规则、
> **精确接线片段**与风险点。

## 0. 交付物

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/ispeech/input/QuickPhrasePanel.kt` | **已接线**：新增「★ 收藏 / 最近」胶囊、行内 ★/☆ 星标、`submitUsage(starredIds, recentIds)` 与 `onToggleStar` |
| `app/src/main/java/com/ispeech/data/PhraseUsageStore.kt` | 使用记录持久化：收藏、最近使用（倒序、上限 50）、使用计数、最后使用时间 |
| `app/src/main/java/com/ispeech/data/PhraseSearch.kt` | 分类 / 关键词搜索（触发码 + 名称 + 正文 + 分类），含拼音首字母缩写匹配与评分排序 |
| `tools/verify/check_phrase_usage.py` | 纯 Python 复刻上述两个类的核心逻辑并断言（31 项检查，可直接运行） |
| `docs/quick-phrase-enhancement.md` | 本文档 |

存储风格沿用现有 `UserDictStore` / `ClipboardHistory`：`filesDir` + TAB 分隔文本、
`#` 注释、`@Synchronized`、懒加载、无第三方依赖；差异点是**原子写入**（tmp + rename）与
损坏文件备份。

## 1. 存储：`filesDir/phrase_usage.txt`

```
# 快捷短语使用记录（自动生成：收藏 / 最近使用 / 使用次数）
# 字段：id\tcode\ttext\tstar(0/1)\tcount\tlastUsedMs\ttick
p1-3f8a1c0d5e6b7a90	hi	你好	1	12	1760000000000	37
p1-9b2c4d6e8f0a1b3c	thx	感谢语	0	4	1759999990000	36
```

| 字段 | 含义 |
|---|---|
| `id` | 短语稳定标识（见第 2 节），文件内唯一 |
| `code` | 触发码快照（归一化后的小写、去首尾空白、截断 64 字符） |
| `text` | 正文快照（`\r\n`/`\r` 统一为 `\n`、去首尾空白、截断 2000 字符） |
| `star` | 收藏标记 `0` / `1` |
| `count` | 使用次数（上限 999999） |
| `lastUsedMs` | 最后一次使用的 wall clock 毫秒 |
| `tick` | 单调序号，同毫秒多次使用也能定序（`recent()` 的二级排序键） |

行为约定：

- **转义**：`code` / `text` 复用 `UserDictStore` 同包内的 `escapeLineText` / `unescapeLineText`
  （`\` `\n` `\r` `\t` 写为字面量），因此正文里的换行 / 制表符不会破坏「一行一条」。
- **原子写入**：先写 `phrase_usage.txt.tmp` 再 `renameTo`；`renameTo` 失败（部分文件系统不允许覆盖）
  时退化为直接覆盖写。`open()` 时会清理上一次残留的 `.tmp`。
- **容错读取**：文件缺失 → 当空；字段数 < 7、数字非法、id 为空的行**跳过并计数**；
  存在坏行时把原文另存一份 `phrase_usage.txt.corrupt` 并打 `Log.w`；文件 > 512 KiB 直接判定损坏。
  任何情况下都不抛异常（`runCatching` + `Log.w`，与既有 Store 一致）。
- **落盘顺序**：收藏组在前、最近使用在前（`all()` / `ranked()` 的顺序），文件人工可读。
- **线程模型**：所有公开方法 `@Synchronized`，读写串行；文件 IO 在调用线程完成 ——
  **请在 IO 线程调用**（`withContext(Dispatchers.IO)`），不要在主线程批量写。
- 记录只保存「id + 快照」，**不参与渲染**：上屏内容始终以 `QuickEntry.content` 为准。

## 2. 稳定 id：去重与迁移策略

```
id = "p1-" + hex16(FNV-1a64( normalizeCode(trigger) + "\u0001" + normalizeText(content) ))
```

- `normalizeCode`：`trim()` + `lowercase()` + 截断 64 字符；
- `normalizeText`：`\r\n` / `\r` → `\n`，`trim()`，截断 2000 字符；
- 分隔符 `ID_SEP = "\u0001"`（不会出现在正常编码 / 文本里），保证 `code` / `text` 任意组合唯一；
- 哈希 = **FNV-1a 64**（对 UTF-8 字节流，64 位回绕；常量 `FNV_OFFSET_BASIS = 0xcbf29ce484222325`、
  `FNV_PRIME = 0x100000001b3`），前缀 `p1` 是**版本号**，用于未来的迁移。

**去重策略（语义边界，已在校验脚本中断言）**

| 情况 | 结果 |
|---|---|
| `TRIGGER` / `trigger` / ` trigger ` | 同一 id（触发码大小写与首尾空白不敏感） |
| 正文仅换行风格 / 首尾空白不同 | 同一 id |
| 同正文 + **不同触发码** | **两条**（多入口短语各自独立计数、各自可收藏） |
| 同触发码 + 不同正文 | 两条 |
| 文件里同 id 出现多行 | 合并为一条：`star` 取或、`count` 取**最大**（不叠加）、`lastUsedMs`/`tick` 取最新、`code`/`text` 优先取较新的一条 |

**迁移策略**

1. 读盘时若 `code` / `text` 非空，**重算 id**；重算值与文件中的 id 不一致 → 用重算值作为键，
   并按上面的合并规则与已有记录合并，同时把 `migratedIds` 计数 +1；
2. 只要发生过迁移，加载结束会**立即重写文件**（幂等，第二次启动不再迁移）；
3. 因此以下情况都能自愈：哈希算法 / 归一化规则变更、手工编辑过 `code`/`text`、旧版本写入的
   id。若将来更换哈希或归一化规则，把 `ID_PREFIX` 从 `p1` 升到 `p2` 即可（前缀不同不影响迁移，
   迁移是靠「重算 + 合并」完成的，前缀只是可读性与排障标记）；
4. `code` / `text` 为空的记录（只按 id 收藏、还没上屏过）无法重算，按原 id 保留，
   后续 `recordUse(id, code, text)` 会补齐快照。

**已知局限**

- 64 位哈希理论上可碰撞（概率极低；真发生时两条短语会被合并计数，不会崩溃）；
- 正文截断到 2000 字符后参与哈希 → 超长正文的不同尾巴会被视作同一条；
- id 只覆盖 `trigger + content`，**不含 label / source**：远端规则改名或换来源分组不影响收藏；
  若同一触发码 + 同一正文同时来自远端与内置，它们会共享收藏与计数（见第 7 节风险 4）。

## 3. 最近使用与上限

- `RECENT_LIMIT = 50`：**未收藏**记录只保留最近 50 条（按 `lastUsedMs` 倒序，淘汰最旧的）；
  **收藏记录永不淘汰**；
- 同毫秒多次使用由 `tick` 保证顺序确定；
- `toggleStar` 取消收藏时，若该短语**从未使用过**（`count == 0`）则整条记录删除，
  避免留下空壳；用过的短语取消收藏后计数保留。

主要 API（都在 `PhraseUsageStore`）：

| API | 说明 |
|---|---|
| `PhraseUsageStore.open(file)` | 打开（懒加载，不抛异常） |
| `PhraseUsageStore.idOf(code, text)` / `idOf(entry)` | 稳定 id（伴生对象静态函数，无需 Context） |
| `recordUse(entry)` / `recordUse(id, code, text)` / `recordUse(id)` | 计数 +1、刷新时间、进入最近使用首位 |
| `toggleStar(entry)` / `toggleStar(id, code, text)` / `toggleStar(id)` | 切换收藏，返回新状态；新收藏会把记录顶到收藏组首位 |
| `isStarred(id)` / `starCount()` / `countOf(id)` / `usage(id)` | 查询 |
| `recent(limit = 50)` / `recentIds(limit = 50)` | 按时间倒序（含收藏） |
| `topOrRecent(limit = 12, knownIds = null)` / `topOrRecentIds(...)` | **收藏优先**（组内最近使用倒序）+ 未收藏最近使用，无重复 id |
| `all()` | 收藏优先 + 最近使用靠前的全量列表 |
| `forget(id)` / `prune(knownIds)` / `clear()` | 删除 / 清理孤儿 / 清空 |
| `clockForTest`（internal） | 测试注入时钟，生产为 `System.currentTimeMillis()` |

## 4. 搜索：`PhraseSearch`

```
PhraseSearch.PURE                                  // 纯搜索（无使用数据）
PhraseSearch.of(store)                             // 带使用次数 / 收藏加成
PhraseSearch(usageCount = {...}, starredFlag = {...}) // 自定义数据源
```

搜索字段：**触发码、名称、正文、分类（来源分组「内置/远端/用户」+类型「短语/应用」）**。

评分 = 档位分 + 字段加成 + 使用加成 + 收藏加成：

| 档位 | 分数 | 说明 |
|---|---|---|
| `EXACT` | 900 | 任一字段整串等于关键词 |
| `PREFIX` | 600 | 任一字段以关键词开头 |
| `CONTAINS` | 300 | 任一字段包含关键词 |
| `INITIALS` | 100 | 拼音首字母缩写命中（仅 ASCII 关键词、长度 ≥ 2） |

| 加成 | 值 |
|---|---|
| 命中触发码 / 名称 / 正文 / 分类 | +100 / +60 / +20 / 0 |
| 使用次数 | 每次 +2，最多计 20 次（+40） |
| 已收藏 | +30 |

附加分上限 `100 + 40 + 30 = 170` < 最小档位间距 200，因此
**完全匹配 > 前缀 > 包含 > 首字母** 不会被使用次数或收藏翻转（校验脚本对不变量有断言）。
最终排序键：`score ↓ → count ↓ → weight ↓ → label ↑ → trigger ↑ → id ↑`（结果完全确定）。

过滤参数（`search(entries, keyword, category, limit, starredOnly, allowedIds)`）：

- `category`：来源分组或类型（`内置`/`远端`/`用户`/`短语`/`应用`），`全部`/`all`/空 = 不过滤；
- `starredOnly = true`：「★ 收藏」胶囊；
- `allowedIds`：「最近」胶囊传 `store.recentIds(50).toSet()`；
- 去重：同一 id 只保留分数最高的一条（与 Store 的去重规则完全一致）；
- `keyword` 为空：不过滤，按使用次数 / 收藏 / weight 排序（「全部」列表）。

高亮：`PhraseMatch.spans` 是**闭区间**列表，区间指向 `PhraseMatch.field` 对应的字段文本：

```kotlin
// match.field: TRIGGER/LABEL/CONTENT/CATEGORY/INITIALS -> 用哪个字符串做下标
val source = when (match.field) {
    PhraseHitField.TRIGGER -> match.entry.trigger
    PhraseHitField.LABEL   -> match.entry.label
    PhraseHitField.CATEGORY -> match.entry.source   // 或 entry.kind.label
    else -> match.entry.content
}
val spannable = SpannableString(source)
match.spans.forEach { range ->
    if (range.first in source.indices && range.last in source.indices) {
        spannable.setSpan(BackgroundColorSpan(0x335B6CFF), range.first, range.last + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
previewText.setText(spannable, TextView.BufferType.SPANNABLE)
```

### 4.1 拼音首字母匹配（取舍说明）

- **不做全量拼音库**（约定无第三方依赖、表太大只会增大 APK），改为内嵌**高频字表**
  `PhrasePinyin.GROUPS`：`声母:汉字...` 分组、`|` 分隔，当前 **787 个汉字**（校验脚本会打印数量）；
- **未收录汉字按通配处理**：关键词的对应位可以匹配任意字符 —— 这是「命中即高亮」的降级策略，
  不会漏报，代价是可能多报；
- 只对**纯 ASCII 关键词（≥ 2 字符）**启用，且要求对齐窗口内**至少有一个汉字**，
  避免邮箱 / 网址这类纯 ASCII 文本被刷成命中；
- 首字母命中永远排在字面「包含匹配」之后（100 < 300）；
- 扩展方式：往对应声母分组追加汉字即可（`"f:发费付分服方法返房非复负份丰放否封繁反防访飞废风福附副|"`），
  改完跑一次校验脚本（已内置 39 组已知用例 + 内置规则文案覆盖率断言，覆盖率低于 90% 会 FAIL）；
- 局限：不含全拼（`fapiao` 匹配不到「发票」）；多音字只按最常用读音收录（如 `长 → c`、`重 → z`）；
  BMP 之外的汉字（如「𠀀」）按非中文处理，不参与首字母匹配。

## 5. 接线片段（给主 Agent）

> 以下片段是**参考实现，未编译验证**（本机没有 JDK / Android SDK）。已尽量贴合现有代码风格，
> 落地时请按当前文件内容微调行位置。

### 5.1 `di/AppContainer.kt`：加一个全局缓存（与 `userDict` / `clipboardHistory` 同款）

```kotlin
/** 短语使用记录文件名（位于 filesDir 下） */
const val PHRASE_USAGE_FILE = "phrase_usage.txt"

@Volatile private var phraseUsageCache: PhraseUsageStore? = null

/** 短语使用记录（收藏 / 最近使用 / 计数）：filesDir/phrase_usage.txt，全局懒加载缓存 */
fun phraseUsage(context: Context): PhraseUsageStore {
    val cached = phraseUsageCache
    if (cached != null) return cached
    synchronized(this) {
        val existing = phraseUsageCache
        if (existing != null) return existing
        val created = PhraseUsageStore.open(File(context.filesDir, PHRASE_USAGE_FILE))
        phraseUsageCache = created
        return created
    }
}

/** 清空使用记录后调用：丢弃缓存实例，下次 phraseUsage() 重新读盘。 */
fun invalidatePhraseUsage() {
    synchronized(this) { phraseUsageCache = null }
}
```

（`import com.ispeech.data.PhraseUsageStore`）

### 5.2 `input/IspeechImeService.kt`：上屏即 `recordUse`

**方案 A（推荐，面板加一个回调，最准确）**

```kotlin
// onCreateInputView() 里，紧邻现有 phrases.onInsert = ...
phrases.onInsertEntry = { entry ->
    val store = AppContainer.phraseUsage(this)
    serviceScope.launch { withContext(Dispatchers.IO) { store.recordUse(entry) } }
    insertFromPanel(entry.content)
}
```

**方案 B（完全不动面板）**：在 `loadQuickPhrases()` 里顺手建「正文 → 条目」索引，然后在
`insertFromPanel()` 里补一次计数（注意该函数同时服务剪贴板面板，索引里没有的正文自然跳过）：

```kotlin
/** 正文 -> 条目索引（loadQuickPhrases 在 IO 线程写，insertFromPanel 在主线程读） */
@Volatile private var quickByContent: Map<String, QuickEntry> = emptyMap()

// loadQuickPhrases() 的 withContext(Dispatchers.IO) 块末尾：
quickByContent = entries.associateBy({ it.content }, { it })

private fun insertFromPanel(text: String) {
    if (text.isEmpty()) return
    quickByContent[text]?.let { entry ->
        val store = AppContainer.phraseUsage(this)
        serviceScope.launch { withContext(Dispatchers.IO) { store.recordUse(entry) } }
    }
    currentInputConnection?.commitText(text, 1)
    lastCommit = text
    closePanel()
}
```

方案 B 的取舍：同一正文来自不同触发码时只会给「后加载的那条」计数（id 若相同则无差别）；
「空格上屏第一条」（`insertPanelFirstMatch` → `insertFromPanel`）与候选条上屏不需要额外处理。

### 5.3 `input/QuickPhrasePanel.kt`：★ 收藏 / 最近 胶囊 + 星标点击（**已实现**）

Agent C 已在面板内落地，改动点与行号（`app/src/main/java/com/ispeech/input/QuickPhrasePanel.kt`）：

| 行 | 内容 |
|---|---|
| `:52` | `var onToggleStar: ((QuickEntry) -> Unit)? = null` |
| `:63` / `:67` | `@Volatile private var starredIds: Set<String>` / `recentIds: Set<String>`（空集合 = 未接线） |
| `:97` / `:100` | `private enum class UsageFilter { NONE, STARRED, RECENT }` + `Category` 新增 `usage` 字段（默认 `NONE`） |
| `:111` | 胶囊顺序：`全部` → `★ 收藏` → `最近` → 原有六项（内置 / 远端 / 用户 / 快速应用 / 短语，顺序不变） |
| `:137` | `fun submitUsage(starredIds: Set<String>, recentIds: Set<String>)` |
| `:165` / `:166` | `filtered()` 内按 `isStarred` / `isRecent` 过滤 |
| `:179` | `private fun idOf(entry) = PhraseUsageStore.idOf(entry)`（纯计算，无 IO） |
| `:191` | `render()` 只算一次 `filtered()`；选中收藏 / 最近时标题行显示 `★ 收藏 N 条` / `最近 N 条` |
| `:279` / `:314` / `:353` | `createRowView(entry, id)` 在角标列加 `createStarView(entry, id)`（★/☆，36dp 触摸目标） |

新增 API 最终签名（原文，可直接按此接线）：

```kotlin
/** 点击行内 ★/☆：只切换收藏（星标是独立子 View，不会触发 [onInsert] 上屏）。 */
var onToggleStar: ((QuickEntry) -> Unit)? = null

fun submitUsage(starredIds: Set<String>, recentIds: Set<String>)
```

行为约束（已满足）：

- `submitUsage` 未调用 → 两个集合为空 → 「★ 收藏 / 最近」胶囊列表为空（显示现有空状态文案
  「没有匹配的快捷短语」），原有六项胶囊、关键词过滤、点击上屏行为与增强前完全一致；
- 过滤判断用 `PhraseUsageStore.idOf(entry)`，面板**不做任何磁盘 IO**（无 `File` / 无 `runBlocking`）；
- 空状态文案沿用 `createEmptyView()`，未改动。

**主 Agent 仍需要做的服务侧接线**（面板已就绪）：

```kotlin
// ① onCreateInputView()：把收藏点击接到 store
phrases.onToggleStar = { entry ->
    val store = AppContainer.phraseUsage(this)
    serviceScope.launch {
        val starred = withContext(Dispatchers.IO) { store.toggleStar(entry) }
        statusText = if (starred) "已收藏：${entry.label}" else "已取消收藏：${entry.label}"
        renderCandidateView()
        refreshPhraseUsage()      // 见 ②
    }
}

// ② 把收藏 / 最近数据推给面板（IO 线程读盘，主线程刷新）
private fun refreshPhraseUsage() {
    serviceScope.launch {
        val store = AppContainer.phraseUsage(this@IspeechImeService)
        val (starred, recent) = withContext(Dispatchers.IO) {
            store.all().filter { it.starred }.map { it.id } to store.recentIds(PhraseUsageStore.RECENT_LIMIT)
        }
        quickPhrasePanel?.submitUsage(starred.toSet(), recent.toSet())
    }
}
```

在 `loadQuickPhrases()` 成功灌入 `entries` 之后调用一次 `refreshPhraseUsage()`；
`toggleStar` 之后也要再调一次（上面的 ① 已包含），否则星标不会变色。

如果想让「最近」胶囊按使用顺序排列、而不是按原有过滤顺序，可改用
`PhraseSearch.of(store)` 取排序结果（`search` 已内置同一套去重与档位评分），
面板侧只需把 `filtered()` 换成搜索器返回的列表。

### 5.4 可选：用 `PhraseSearch` 增强面板现有过滤

`QuickPhrasePanel.matchesKeyword()` 现在是「trigger / label / content 三处 contains」，不能命中
分类、不排序、不支持首字母。若想升级为评分排序，把 `filtered()` 换成：

```kotlin
private fun filtered(): List<QuickEntry> {
    val store = usageStore                       // 面板持有 store 引用（或由服务注入搜索器）
    val searcher = if (store == null) PhraseSearch.PURE else PhraseSearch.of(store)
    val category = categories.getOrNull(selectedCategory) ?: categories[0]
    return searcher.searchEntries(
        entries = allEntries,
        keyword = filterKeyword,
        category = category.source ?: category.kind?.label,
        limit = maxRows,
        starredOnly = category.usage == UsageFilter.STARRED,
        allowedIds = if (category.usage == UsageFilter.RECENT) recentIds else null
    )
}
```

（若不想让面板依赖 `data` 层，可由服务预先算好 `List<QuickEntry>` 再 `submit(...)`，面板保持纯渲染。）

### 5.5 从剪贴板保存为短语（建议改法）

现状：`ClipboardPanel` 只有 `onInsert` / `onRefresh` / `onUndo` / `onClear`，剪贴板内容进不了
短语库。建议加一个「存为短语」入口（长按行，或每行右侧一个「＋」小按钮）：

```kotlin
// ClipboardPanel：新增回调 + 行内按钮（与 onInsert 同款）
var onSaveAsPhrase: ((String) -> Unit)? = null

// IspeechImeService.onCreateInputView()
clipboard.onSaveAsPhrase = { text -> saveClipboardAsPhrase(text) }

/** 剪贴板内容 -> 用户短语（写 quick_phrases_user.txt，自动收藏） */
private fun saveClipboardAsPhrase(raw: String) {
    val snippet = raw.trim()
    if (snippet.isEmpty()) return
    val store = AppContainer.phraseUsage(this)
    serviceScope.launch {
        val label = withContext(Dispatchers.IO) {
            runCatching {
                val file = AppContainer.quickPhrasesUserFile(this@IspeechImeService)
                val existing = if (file.exists()) file.readText(Charsets.UTF_8) else ""
                val trigger = nextClipTrigger(existing)                 // cl1 / cl2 / ...
                val label = snippet.replace('\n', ' ').take(12)
                val rule = QuickEntry(
                    trigger = trigger,
                    label = label.ifEmpty { snippet.take(12) },
                    content = snippet,
                    weight = 120,                                       // 用户短语默认略靠前
                    mode = QuickMatchMode.PREFIX,
                    kind = LookupKind.PHRASE,
                    source = QuickSource.USER
                )
                file.parentFile?.mkdirs()
                // 与 QuickPhraseCodec.format 完全兼容：追加一行（无表头时可以照抄格式加表头）
                val line = buildString {
                    append(trigger).append('\t').append(label).append('\t')
                    append(snippet.replace("\r", "").replace("\n", "\\n").replace("\t", "\\t"))
                    append("\tprefix\t120\tp\n")
                }
                file.appendText(line, Charsets.UTF_8)
                store.toggleStar(rule)                                   // 存为短语 = 顺手收藏
                label
            }.getOrNull()
        }
        AppContainer.invalidateEngine()          // 用户规则变了，让引擎重建
        if (label == null) {
            statusText = "保存短语失败"
        } else {
            statusText = "已保存并收藏：$label"
            loadQuickPhrases()                   // 重新灌入面板
            refreshPhraseUsage()
        }
        renderCandidateView()
    }
}

/** 生成不与现有规则冲突的触发码：cl1 / cl2 / ...（简单扫描，规则量级很小） */
private fun nextClipTrigger(existing: String): String {
    val used = existing.lineSequence()
        .map { it.substringBefore('\t').trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toHashSet()
    var index = 1
    while ("cl$index" in used) index++
    return "cl$index"
}
```

注意点：

- 必须先 `AppContainer.invalidateEngine()`，否则 `CompositeEngine` 缓存里没有新短语；
- 触发码 `cl1` 只有 3 个字符，`minTriggerLength` 默认 2 时可用（前缀模式输入 2 个字符即出现）；
  若担心误触，可用 `cl` + 双位数字（`cl01`）或让用户补一个更顺手的触发码；
- 「存为短语」建议**默认收藏**（`toggleStar(rule)`），这样它会立刻出现在「★ 收藏」胶囊里；
- 若同时给 `QuickPhrasesScreen` 加「从剪贴板导入」，请走同一套 `QuickPhraseCodec` 编解码，
  避免两处格式漂移。

### 5.6 可选：设置页「清空使用记录」

```kotlin
Button(onClick = {
    scope.launch {
        withContext(Dispatchers.IO) { AppContainer.phraseUsage(context).clear() }
        AppContainer.invalidatePhraseUsage()
    }
}) { Text("清空使用记录（收藏 / 最近 / 计数）") }
```

## 6. 离线校验

```bash
python3 tools/verify/check_phrase_usage.py     # 31 项：源码漂移 + Store 行为 + 搜索行为
python3 tools/verify/check_static.py           # 括号配平 / 资源引用（会顺带覆盖两个新文件）
```

输出约定与 `tools/verify` 目录下其它脚本一致：每行 `PASS` / `FAIL` / `WARN`，
末尾 `SUMMARY: passed=N failed=M warned=K`，**任一 FAIL 退出码为 1**。

脚本会从 Kotlin 源码解析常量（`RECENT_LIMIT` / `ID_PREFIX` / `ID_SEP` / FNV 常量 /
`PhraseMatchTier` 档位 / 各项加成 / `PhrasePinyin.GROUPS` 首字母表）并与 Python 复刻值比对，
**改了 Kotlin 常量却不同步脚本会直接 FAIL**。

> 注：`tools/verify/README.md` 未同步更新（Agent C 的约束是「只允许新建文件」）。
> 建议主 Agent 在该 README 的表格里补一行：
> `python3 tools/verify/check_phrase_usage.py` —— 短语使用记录（收藏 / 最近 / 计数）与搜索评分校验。

面板（`QuickPhrasePanel.kt`）的改动**不在** `check_phrase_usage.py` 的断言范围内：该脚本只 pin
`PhraseUsageStore` / `PhraseSearch` 的常量、API 与行为；本次面板改动不影响任何一条断言，
因此按「本任务禁止修改其它文件」的约束**没有改动该脚本**（保持 31 项全绿）。
面板这一轮的离线自检 = `check_static.py`（括号配平 / R 资源引用 / 自定义 View）+ 源码级 grep：

| 自检点 | 结果 |
|---|---|
| `submitUsage(starredIds: Set<String>, recentIds: Set<String>)` 签名一致 | ✅ `QuickPhrasePanel.kt:137` |
| `var onToggleStar: ((QuickEntry) -> Unit)? = null` | ✅ `:52` |
| 过滤用 `PhraseUsageStore.idOf(entry)`，面板无磁盘 IO | ✅ `:179`（全文无 `java.io.File` / `runBlocking` / `filesDir`） |
| 胶囊顺序：全部 / ★ 收藏 / 最近 / 内置 / 远端 / 用户 / 快速应用 / 短语 | ✅ `:111` |
| 星标是独立可点击子 View（36dp 触摸目标） | ✅ `:314` / `:353` / `:362` |
| 空状态文案沿用「没有匹配的快捷短语」 | ✅ `createEmptyView()` 未改动 |

若后续想给面板也加断言，建议在该脚本新增一节 D（源码级）：断言上述 6 条 + `renderList` 只接收
已算好的 `results`（避免重复计算 id 哈希）。

## 7. 风险点

1. **id 稳定性依赖 `trigger + content`**：改触发码或改正文 = 新 id，老的收藏 / 计数会成为孤儿
   （不会崩溃，也不算丢数据）。设置页「编辑短语」若允许改这两个字段，建议编辑时顺带
   `forget(旧 id)` + `toggleStar(新 id)`，或读盘时统计孤儿后提示用户。
2. **哈希碰撞**：FNV-1a 64 非加密哈希，理论上不同短语可能同 id → 会被合并计数。
   概率极低（≈ n²/2⁶⁵），但**不要把 id 当作安全标识**；需要绝对唯一时应改成持久化 id 表。
3. **并发写入**：单个 `PhraseUsageStore` 实例的所有读写都在对象锁内串行，是安全的；
   但**多个实例指向同一文件**（例如 `AppContainer` 缓存被绕过、或测试里各开一份）会互相覆盖 ——
   请统一走 `AppContainer.phraseUsage(context)`。写盘是「整文件重写」，
   因此进程被杀时最坏情况是丢最后一次计数（原子 rename 保证不会写出半截文件）。
4. **主线程 IO**：文件读写发生在调用线程，`recordUse` / `toggleStar` / `clear` **必须**放到
   `Dispatchers.IO`（片段里都是 `withContext(Dispatchers.IO)`）。面板 `submitUsage` / `submit`
   只做内存赋值 + 主线程重绘，可以在主线程调用。
5. **`prune(knownIds)` 会删收藏**：它按白名单删除，**必须传「内置 + 远端 + 用户」全量规则的 id 集合**。
   若在关闭远程规则时调用（`loadAll(includeRemote = false)`），远端短语的收藏会被一并清掉。
   建议只在「同步完成后的全量刷新」时调用，或干脆不调用（未收藏记录本来就有 50 条 LRU 上限兜底）。
6. **热路径开销**：`recent` / `topOrRecent` 每次调用都做一次全量排序，`search` 对每个条目调用
   `idOf` + 两次 store 查询（哈希 + 加锁）。当前规则量级（≤ 千条）没问题；
   若要塞进「每次按键」的热路径，建议先缓存 `idOf` 结果或把使用数据快照一次性读出来。
7. **首字母表质量**：787 个高频字是手工收录，写错一个声母会导致该字相关的缩写搜不准
   （校验脚本对 39 组已知词与内置规则文案覆盖率有断言，但覆盖不到全部字）。未收录字走通配，
   所以「不命中」比「命中错误」概率低；发现漏字时只需追加到对应声母分组。
8. **文件手工编辑**：格式是纯文本，用户 / 后续 Agent 可能手改。解析对坏行容错并备份 `.corrupt`，
   但**不要**把 `id` 与 `code`/`text` 改得不一致又期望保留收藏 —— 这种情况下会按 `code`/`text`
   重算 id（这是有意为之的迁移行为）。
9. **存储清理**：`.corrupt` 备份目前只在再次写入时被覆盖，不会被自动删除；
   若担心占用空间，可在设置页「清空使用记录」时一并删除 `phrase_usage.txt.corrupt`。
10. **触摸冲突（行点击 vs 星标点击）**：星标是行内独立的 `isClickable = true` 子 View，
    ViewGroup 默认不拦截命中子 View 的触摸序列，星标自己消费 DOWN/UP 并触发 `onToggleStar`，
    父行的 `OnClickListener` 不会执行 —— 因此**点星标不会上屏**（这是 Android 事件分发的标准行为，
    不是「靠顺序兜底」）。残留风险：
    - 星标触摸目标只有 36dp（Material 建议 48dp），手指偏一点会落到行内容上 → **误上屏**。
      已用 36dp 最小尺寸 + `keySelector` 按压反馈降低概率；若要更保险，可把整行改成
      「右侧 48dp 星标热区 + 左侧正文区上屏」的两段式（正文区挂 `onInsert`，星标区挂 `onToggleStar`）；
    - 父行有 `state_pressed` 选择器：长按星标时行背景也可能显示按下态（视觉小瑕疵，不影响行为）；
    - 硬件键盘 / 无障碍焦点：星标设了 `isFocusable = false`（不抢焦点框），
      若后续要支持方向键导航收藏，需要改为可聚焦并补 `nextFocus*` 关系。
11. **面板与服务的刷新时序**：`submitUsage` 只改内存，服务每次 `toggleStar` 之后都必须再调一次
    刷新（片段 ① 已包含），否则星标图标与「★ 收藏」列表会停在旧状态；`recordUse` 之后若希望
    「最近」实时变化，也要补一次刷新（或只在面板重新打开时刷新，成本更低）。

## 8. 未验证部分（诚实声明）

- 本机**没有 JDK / Android SDK，未编译、未跑 gradle、未跑单测**：Kotlin 类型推断、
  方法重载解析、`@Synchronized` 实际语义等只有真机 / CI 编译才能确认；
- `tools/verify/check_phrase_usage.py` 是**逻辑复刻**（含常量与 API 的源码漂移断言），
  它验证语义与不变量，**不能替代编译**；
- 本文件第 5 节的**服务侧**片段未编译、未运行；面板侧改动（`QuickPhrasePanel.kt`）已落地但同样
  **未编译**（本机无 JDK）；
- 面板 UI（胶囊换行 / 星标按压反馈 / 36dp 触摸目标是否够用）与「点星标会不会误上屏」只能在
  真机上确认：事件分发层面已确认不会（子 View 消费触摸，父行 OnClickListener 不执行），
  但**手指落点误差导致误点行内容**属于人体工学问题，需要真机手感验证；
- `submitUsage` / `refreshPhraseUsage` 的调用时序由主 Agent 接线，本轮未做端到端验证；
- 拼音首字母表未与权威拼音表逐字比对（仅对已知用例与内置规则文案做断言）；
- `renameTo` 的原子性依赖文件系统（Android 上 `filesDir` 位于 ext4/f2fs，同目录 rename 是原子的），
  已实现「失败则退化覆盖写」的兜底，但未做掉电注入测试。

## 12. 搜索栏与云端增删改同步（2026-10-17 追加）

### 12.1 设置页搜索栏 + 智能联想

`settings/QuickPhrasesScreen.kt` 列表顶部新增搜索栏（`QuickSearchBar`），`settings/quick/QuickPhrasesViewModel.kt`
在 `load()` 里一次性读入使用记录（`PhraseUsageStore.countOf` / `isStarred`）后，关键词过滤完全由
`data/PhraseSearch.kt` 承担：

- 档位：完全 > 前缀 > 包含 > 拼音首字母（`fp` → 「发票」），同档按 使用次数（+2/次，上限 20 次）→ 收藏（+30）→ weight 排序；
- 空关键词时列表按同一评分排序（用频次高的自然靠前）；
- 命中区间以 `secondaryContainer` 底色高亮，只对「正在渲染的那个字段」生效；拼音首字母命中额外标「拼音首字母」档位角标。

输入时只做纯计算：使用记录在 `load()` 时读盘一次，之后 `setKeyword` 不在主线程碰任何文件。

### 12.2 IME 面板的智能联想

`input/QuickPhrasePanel.kt` 的关键词过滤从「三字段 `contains`」改为 `PhraseSearch`（由服务经
`submitUsage(starredIds, recentIds, usageCounts)` 灌入使用次数），因此面板内打字同样支持拼音首字母联想与
使用频率加权。**无关键词时保持规则文件原始顺序**，避免「开面板条序变了」。面板仍零磁盘 IO、零 `AppContainer`
（`tools/verify/check_crosscut.py` 有断言）。

### 12.3 云端增删改同步

后端（`api.agentai0.com`）实测**只有** `POST api/rules`（创建）与 `POST sync/push`（批量），
`PUT / DELETE /api/rules/{id}` 全部 404（curl 复核 2026-10-17）。因此：

| 操作 | 实现 |
|---|---|
| 新增 | `POST api/rules`，取返回的规则 id 记入映射表 |
| 修改 / 删除 | 汇总成一次 `POST sync/push`：`{rules:[{id,keyword,reply_template,match_type,priority,enabled}], deletedIds:[id]}` |

删除同步的前提是「本地删掉的那条对应远端哪个 id」，本地文件里没有这个信息，故新增映射文件
`filesDir/quick_phrases_remote_map.txt`：`trigger \t id \t fingerprint`，`fingerprint` 是远端可见字段
（触发词 / 正文 / match_type / weight / 类型）的哈希 —— 指纹变了才重推，避免每次保存都全量上传。

**不做「假装成功」**：`sync/push` 的生效范围未经确认，推送后重新拉取一次远端规则列表复核，
只有远端确实变了才计入 `applied` 并更新映射表；未生效的条数作为 `pending` 上报到设置页与同步页文案
（`quick_synced_partial` / `sync_upload_partial`）。

`match_type` 归一化：`PREFIX` 与 `CONTAINS` 都写 `CONTAINS`（后端只认 EXACT / CONTAINS / REGEX，
否则会「推上去又变回来」导致每次保存反复重推）。

### 12.4 快捷字母（自定义简写，命中即候选首位）

搜狗「常用语」支持给短语配一串自定义简写，敲简写就直接出这条短语。本实现的对应能力：

| 关注点 | 位置 / 规则 |
| --- | --- |
| 数据 | `QuickEntry.trigger` 就是快捷字母；用户规则文件 `filesDir/quick_phrases_user.txt` 第一列 |
| 录入 | 设置页「快捷短语」新增 / 编辑弹窗（本地规则与云端简写两处同一套校验）：只保留小写字母，2–4 位 |
| 为什么限 4 位 | 五笔态组合缓冲上限 `MAX_CODE_LENGTH = 4`，第 5 个字母会先把当前首选上屏再重新起码，更长的简写永远打不全 |
| 排序 | `CandidateRanking`：精确码优先 → `LookupKind.order`（应用 0 < 短语 1 < 词库 2 …）；`QuickPhraseEngine.lookup` 保证**精确命中不受扫描上限截断**，规则表再大也排第一 |
| 生效时机 | 保存即 `AppContainer.invalidateEngine()`，输入法无需重启 |
| 可见性 | 输入法内「短语」面板每行右侧以角标显示快捷字母，用户不必回设置页查 |

验收：五笔态敲 `ycsf` → 候选第 1 位是「游泳池换水要收费的」→ 空格整段上屏。
`tools/verify/check_quick_trigger.py` 对上面每一条都有断言（含「精确命中不被截断」与 `ycsf` 排序模拟）。

### 12.5 远端规则的查看与快捷字母覆盖

设置页「快捷短语」列表现在同时展示两个来源：

| 来源 | 文件 | 可做的操作 |
| --- | --- | --- |
| 用户 | `filesDir/quick_phrases_user.txt` | 查看 / 新增 / 编辑 / 删除 / 设置快捷字母 |
| 远端 | `filesDir/quick_phrases_remote.txt`（`SyncRepository.pullAndWriteRemoteRules` 的拉取产物） | 查看 / 设置快捷字母 / 恢复默认；内容与远端文件只读 |

数据落点与识别规则：

- 给远端规则设置快捷字母时**不改远端文件**，而是往用户文件追加一条覆盖条目：
  `trigger = 快捷字母`、`label / content / kind` 复制远端规则、`mode = prefix`、
  `weight = 远端 weight + 1000`（`QuickPhrasesViewModel.REMOTE_OVERRIDE_WEIGHT_BONUS`）。
- 用户文件里 `label + content` 与某条远端规则相同的条目，即视为该规则的覆盖条目
  （`QuickOverride.matches`）；设置页把它折叠到远端卡片上显示「已覆盖为 xxx」，
  不再单独显示成一条用户规则。普通用户规则仍显示来源「用户」。
- 远端条目先按原文件展示；覆盖后卡片上的快捷字母、匹配方式切换为覆盖条目的值，
  原本的 `label / content / 权重` 保持远端口径。

排序为什么能胜出：

- `CandidateRanking` 的顺序是「精确码 → `LookupKind.order` → weight 降序 → display」；
  覆盖条目是用户选择的完整快捷字母，输入完整字母时命中精确码，权重又比原远端高 1000。
- `CompositeEngine` 先把所有引擎的候选合并、全局排序，再按 `display` 去重；覆盖条目排得更前，
  先入者胜，原远端条目被去重。因此**不需要**调整 `QuickLocator.buildComposite` 的引擎顺序。

恢复默认：

- 卡片上的「恢复默认」调用 `QuickPhrasesViewModel.removeRemoteOverride`，从用户文件删除该覆盖条目；
  下次渲染时远端卡片回到原快捷字母。点击「保存并同步」后写盘并 `invalidateEngine()`，输入法立即生效。
- 远端文件下次同步被整体覆盖也不会影响用户设置：覆盖条目始终只存在用户文件中。

同步边界：

- 覆盖条目是本地别名，`SyncRepository.pushLocalRules` 会读取远端文件并跳过
  `label + content` 能匹配上的用户条目（`QuickOverride.matchesAny`），避免在 csBaby 上
  生成同内容的第二条远端规则。

## 13. 导入「通用短语.csv」（2026-11-09 追加）

来源：产品给的搜狗 / csBaby 导出文件 `通用短语.csv`（235 行、GBK、61 KB）。目标是「导进常用语，
敲快捷字母就出对应候选」，不再一条条手录。仓库内留了这份文件作基线（`tools/verify/fixtures/general_phrases.csv`），
`tools/verify/check_phrase_csv_import.py` 直接读它做回归。

### 13.1 入口

设置 → 快捷短语 → 「导入 CSV」→ 系统文件选择器（`ActivityResultContracts.OpenDocument`，
不申请存储权限）。MIME 白名单枚举了 `text/plain`、`text/csv`、`text/comma-separated-values`、`text/x-csv`、
`application/csv`、`application/vnd.ms-excel`、`application/octet-stream`——网盘 / 微信 / Excel 报的 MIME 各不相同，漏一个用户就选不到刚下载的文件。

选完只进内存列表并提示「已导入 N 条」，点「保存并同步」才落盘 + 上传：沿用 `upsert` 的同一套
契约（`dirty = true` → `save()`），中途退出不会留下半份数据。

### 13.2 文件格式

GBK（Excel 另存）或 UTF-8（含 BOM 也行），首行表头，逗号分隔，RFC 4180 引号字段
（字段内可含换行、逗号、`""` 转义）：

| 列 | 用途 |
| --- | --- |
| `CONTENT` | 上屏文本，可含换行（真实文件 224 条中 108 条是多行） |
| `INPUT_CODE` | 快捷字母，落成 `trigger` |
| `GROUP_NAME` | 分组名，仅当内容全为空时兜底当短名 |
| `IS_DELETE` | 非 0 视为已删除，跳过 |
| `UUID` / 时间戳 / `SYNC_STATUS` | 忽略 |

表头按别名识别、顺序无关（`input_code` / `code` / `简写` / `编码`，`content` / `内容`……），
换一家导出的文件不用改代码。

### 13.3 映射口径

| 字段 | 规则 | 为什么 |
| --- | --- | --- |
| `trigger` | 去空白 → 转小写，须为 1–12 位 `[a-z]` | 引擎按小写比对；`MAX_CODE_LENGTH = 4` 只是五笔输入上限 |
| `mode` | 1 位 → `exact`，其余 → `prefix` | `QuickPhraseEngine` 的 prefix / contains 在输入短于 2 位时被 `minTriggerLength` 拦下，1 位码用 prefix 等于永远打不出来 |
| 长码 > 4 位 | 照常导入为 prefix | 五笔态最多打 4 码，prefix 让「打前 4 位」也能命中；本文件 60 条 |
| `label` | 内容首个非空行，码点截断 20 位 + `…`；重名追加 `·2` / `·3` | `CompositeEngine` 按 display 去重，短名重复会吞掉候选；本文件 13 条重名 |
| `content` | CRLF / 孤立 CR → `\n`，逐行去行尾空白，连续空行压成一行，首行去前导空白 | 源文件有 `\r\r\n` 混排；这些空白在落盘 `trim` 后会丢，先规范化才能保证回读逐字一致 |
| `weight` | 100 | 与手录规则一致 |
| `kind` | `PHRASE`（落盘 `p`） | 走短语档位，`CandidateRanking` 里排在五笔 / 拼音候选之前 |

### 13.4 跳过规则与提示

提示里逐项报数，不静默吞行：

| 情况 | 条数 | 处理 |
| --- | --- | --- |
| 无 `INPUT_CODE` | 1 | 跳过 |
| 同码重复 | 10 | 保留先出现的，其余计入「同码合并」 |
| 1 位字母 | 1（`j`） | 照常导入，`exact`；提示说明它会抢五笔一级简码 |
| 超 4 位 | 60 | 照常导入，提示「五笔态打前 4 位即可命中」 |
| 已删除 / 空内容 | 0 | — |
| **合计导入** | **224** | 235 行 − 1 无码 − 10 重复 |

同 `trigger` 的已有用户规则被导入条目覆盖，其余规则原样保留，所以重复导入同一份文件是幂等的；
`replaced > 0` 时会提示覆盖了几条。

### 13.5 验证

```bash
python3 tools/verify/check_phrase_csv_import.py   # 44 项：解析 / 映射 / 候选 / 落盘回读 / 接线
```

`app/src/test/java/com/ispeech/data/PhraseCsvImportTest.kt` 覆盖同样边界（有 JDK 的环境跑 JUnit）。

### 13.6 真机验收（2026-11-09，华为 ALN-AL00）

设备：`ALN-AL00`（`4.2.0.223(C00E215R10P2)`，HarmonyOS 4.2 / EMUI，1260×2720，density 520），
USB 直连 serial `FMR0224913042917`，输入法为 `com.ispeech.ime/com.ispeech.input.IspeechImeService`（默认）。

构建与安装口径：源码 worktree 副本 + JDK 17 + Android SDK（`:app:assembleDebug`）→
`app-debug.apk` 104,937,729 B、`md5 33bea1a619c5a1aa17569eead74db17b`，
与设备上 `base.apk` 的 md5 逐字节一致——下面所有结论都是在**这份构建**上跑出来的。
`adb install -r -d` 覆盖安装（原为 1.5 / versionCode 5，签名一致，无需卸载，csBaby 登录态因此失效，见末行）。

| 用例 | 操作 | 真机结果 |
| --- | --- | --- |
| 导入 | 设置 → 快捷短语 → 导入 CSV → 系统文件选择器 → 下载内容 → `通用短语.csv`（61.5 KB） | 列表头「共 224 条」 |
| 幂等 | 同一文件再导一次 | 仍 224 条；提示「已导入 224 条…（同码合并 10 条；缺快捷字母 1 条；超 4 位 60 条…覆盖已有 224 条）」 |
| 落盘 | 保存并同步（csBaby 未登录） | 提示「已保存 224 条到本地；未登录 csBaby，未同步云端」；`files/quick_phrases_user.txt` 72,198 B = 2 行头注释 + 224 条 |
| 2 位码 | 键盘敲 `ly` | 候选第 1 位 **鹭影**，其后才是词典词 旅游 / 利用；点第 2 位「旅游」照常上屏（词典路径未受影响） |
| 2 位码 | `ls` | 第 1 位 **揽墅**，其后 历史 / 来说 |
| 4 位码命中长文 | `hxww` | 唯一候选 **湖山鹭韵五街5号**；点选后整段多行房源文案（含换行）落到输入框 |
| 超 4 位码打前 4 位 | `hxws`（原码 8 位） | 第 1 位 **湖山鹭韵五街3号·2**，300+ 字长文完整上屏 |
| 1 位码 | `j` | 第 1 位「亲，距离很近，走路大概1~2分钟吧」 |
| 三方宿主 | 系统「设置」搜索框敲 `hxww` | 候选命中，点选后完整多行文案进设置搜索框（uiautomator 读出全文、换行保留） |
| 五笔回归 | `ggd` | 仍出 高高地 / 感光度，未被短语挤掉 |
| 稳定性 | 全程 logcat | 无 ispeech 的 crash / ANR |

未覆盖：csBaby 登录后的云端上传（覆盖安装后未登录，提示语如实说明本地已存、云端未同步）；
224 条未逐条点选，抽测 6 个码（含 1 / 2 / 4 / 8 位码与多行文本）。
