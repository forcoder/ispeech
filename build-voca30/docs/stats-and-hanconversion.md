# 输入统计 + 简繁转换（Agent D 交付）

本文件说明两块旁路功能的**数据来源、许可、校验值、体积**，以及**给主 Agent 的精确接线片段**。
两块功能都只新增文件实现，**未改动任何已存在文件**（SettingsStore / AppContainer /
IspeechImeService / 导航图由主 Agent 接线）。

---

## 1. 交付物与体积

| 文件 | 类型 | 体积 |
|------|------|------|
| `app/src/main/java/com/ispeech/data/InputStats.kt` | Kotlin 源码 | 统计存储 |
| `app/src/main/java/com/ispeech/data/HanConversion.kt` | Kotlin 源码 | 简繁转换 API |
| `app/src/main/java/com/ispeech/settings/StatsScreen.kt` | Kotlin 源码 | 只读统计页（Compose） |
| `app/src/main/java/com/ispeech/settings/stats/StatsViewModel.kt` | Kotlin 源码 | 统计页 ViewModel |
| `app/src/main/assets/han_s2t.txt` | 简繁映射资产 | **19774 字节 / 2311 条** |
| `app/src/main/assets/han_t2s.txt` | 简繁映射资产 | **21627 字节 / 2582 条** |
| `tools/gen/build_han_assets.py` | 生成脚本（联网，可复现） | — |
| `tools/verify/check_stats.py` | 离线校验脚本（纯 Python） | — |

映射资产合计 **41401 字节（约 40 KB）**，随 APK 打包，运行时只读 assets，不联网。

---

## 2. 简繁映射数据来源与校验值

**上游**：[BYVoid/OpenCC](https://github.com/BYVoid/OpenCC)（Open Chinese Convert），
目录 `data/dictionary/`，**Apache-2.0** 许可（资产头注释里也逐行记录）。

| 资产 | 上游文件 | 上游 URL | 上游 sha256 | 条目数 | 资产 sha256 |
|------|----------|----------|-------------|--------|-------------|
| `han_s2t.txt` | `STCharacters.txt`（简体 → 繁体） | `https://github.com/BYVoid/OpenCC/blob/master/data/dictionary/STCharacters.txt` | `a0ca1601c70648cf48b33c3c6210ccbecc5c7eead4b4c3daf76587ba2c03582b` | 上游 4012 条 → 裁剪后 2311 条 | `68b25935d1a01fe5424f3e7828495a1a19c127e9414635927309dd24cc08f103` |
| `han_t2s.txt` | `TSCharacters.txt`（繁体 → 简体） | `https://github.com/BYVoid/OpenCC/blob/master/data/dictionary/TSCharacters.txt` | `9ff46a7d30e5765375eb13d33f2b03a34d298913caf2b120380679f33ae1642d` | 上游 4148 条 → 裁剪后 2582 条 | `13b4104863e26d3148f2c08d2759a67ebc437ab1236f7ee7416b9c6c2879fd49` |

**上游文件 sha256 的取得方式**：`raw.githubusercontent.com` 在本机网络被重置，改用
jsDelivr CDN 镜像（`https://cdn.jsdelivr.net/gh/BYVoid/OpenCC@master/data/dictionary/<文件>`），
并用第二个独立镜像（`ghproxy.net` 反代 raw.githubusercontent）下载同一文件做**交叉比对**，
两个镜像得到的 STCharacters.txt 字节 sha256 完全一致（`a0ca1601…`），TSCharacters.txt 同样
一致（`9ff46a7d…`）。生成脚本会重新校验该 sha256，不一致时打 `WARN`。

**裁剪口径**（脚本可复现，`python3 tools/gen/build_han_assets.py`）：

1. **只保留「简体侧字符属于 GB2312 汉字集」的条目**（GB2312 `0xB0A1–0xF7FE`，共 6763 个
   常用汉字 = 一级 3755 + 二级 3008）：
   - s2t：源字符（简体）必须属于该集合；
   - t2s：**任一目标字符**（简体）属于该集合即可（繁体源字符本身多半只存在于 GBK / Big5，
     用它过滤会让 t2s 从 4148 条掉到 9 条，等于不可用）。
2. **剔除首选项与源字符相同的条目**（转换后与原文一致的条目无意义），
   共剔掉 s2t 130 条、t2s 31 条。
3. 目标字符列表**保持上游原样**（顺序即上游优先级），转换时取**第一项**。

裁剪效果：上游 36 KB + 105 KB → 内置 19.3 KB + 21.1 KB；裁掉的主要是 CJK 扩展 A/B
区的生僻字（如 㐷 / 𠀀），这些字在 GB2312 输入场景中不会出现。

**已知限制（字符级转换，与上游字符表行为一致）**：

- **不做短语 / 分词消歧**：OpenCC 完整版靠 `STPhrases.txt` / `TSPhrases.txt` 解决
  「里→裏 / 里」「乾→干 / 乾」「后→後 / 后」这类歧义，本实现只用字符表，
  所以「万里」会转成「萬裏」、「乾隆」会转成「干隆」。要更准需再接短语表（体积更大）。
- t2s 有 5 条上游首选项是生僻异体（如 `勣 → 𪟝`），字符级只取首项，未做「选常用字」替换。

---

## 3. 输入统计设计（InputStats.kt）

存储：`filesDir/input_stats.txt`（纯文本、`\t` 分隔、`#` 注释、词文本转义），
行为对齐既有 `UserDictStore` / `ClipboardHistory`：懒加载、读写 `runCatching` + `Log.w` 兜底、
**原子写**（同目录 `input_stats.txt.tmp` 写完后 `renameTo` 覆盖，rename 失败回退直写）。

```
# 输入统计（自动生成，可在设置页查看 / 重置）
q	2026-10-17	120                      查询次数（命中率分母）
c	2026-10-17	CANDIDATE	30	62        某类型：上屏次数 / 上屏字数
w	你好	12                              最常用词：文本 / 频次
```

口径（与 `tools/verify/check_stats.py` 的断言一一对应）：

- **跨天分桶**：键是本地日期字符串 `LocalDate.now().toString()`（`yyyy-MM-dd`），
  字典序 = 时间序，因此裁剪旧分桶时可直接比较；最多保留 400 天。
- **字数**：按 **Unicode 码点**计数并忽略空白。emoji 记 1 个字；
  ZWJ 组合序列（👨‍👩‍👧）由多个码点组成，按码点数记（=5），这是码点口径的已知特征。
- **上屏次数**：`recordCommit(type, text)`，`type ∈ {CANDIDATE, ENGLISH, PUNCTUATION, PHRASE, EMOJI}`。
- **命中率** = 上屏次数 / 查询次数；**查询为 0 时返回 0.0**（除零保护），
  并对 > 1 的异常比值做 `min(1.0, …)` 上限保护。
- **最常用词 Top 50**：只统计 `CANDIDATE` / `PHRASE` 两类上屏文本（英文、标点、emoji 不计入），
  文本 `trim()` 后非空且 ≤ 32 码点才计入；排序 = 频次降序 + 文本升序（同频次顺序稳定，可复现）；
  内存最多保留 500 条例词。
- **累计值不单独落盘**，一律由分桶求和，避免出现两套真相。
- **reset()** 清空分桶与词频，落盘只剩文件头。

API：

```kotlin
val stats = InputStats.get(context)        // 进程内单例（输入法 + 设置页共用）
stats.snapshot()                           // StatsSnapshot：today / total / days / topWords
stats.recordCommit(CommitType.CANDIDATE, text)
stats.recordQuery()                        // 命中率分母
stats.reset()
```

线程模型：所有方法加锁且**在调用线程做文件 IO**，输入法侧请放到 IO 线程调用（见下方片段）。

---

## 4. 主 Agent 接线片段

### 4.1 设置导航加一项（3 处 + 1 个新路由）

`app/src/main/java/com/ispeech/settings/nav/IspeechNavGraph.kt`：

```kotlin
// 1) 路由常量
object IspeechRoutes {
    // …既有常量…
    const val STATS = "stats"
}

// 2) import
import com.ispeech.settings.StatsScreen

// 3) composable 分支（与 THEME 同级）
composable(IspeechRoutes.STATS) {
    StatsScreen(onBack = { navController.popBackStack() })
}

// 4) HomeScreen 回调
HomeScreen(
    // …既有参数…
    onOpenStats = { navController.navigate(IspeechRoutes.STATS) },
)
```

`app/src/main/java/com/ispeech/settings/HomeScreen.kt`：

```kotlin
@Composable
fun HomeScreen(
    onOpenDictionary: () -> Unit,
    onOpenKeyboard: () -> Unit,
    onOpenQuickPhrases: () -> Unit,
    onOpenAccount: (loggedIn: Boolean) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenStats: () -> Unit,          // ← 新增参数
) {
    // …在「主题外观」卡片前后插入：
    SettingsEntryCard(
        icon = Icons.Filled.BarChart,   // material-icons-extended 已引入
        title = "输入统计",
        subtitle = "输入字数、候选命中率与最常用词",
        onClick = onOpenStats,
    )
}
```

> 统计页文案目前直接写在 `StatsScreen.kt` 里（因为没有改动 `res/values/strings.xml` 的授权）；
> 若要国际化，把中文串搬到 strings.xml 即可，页面结构无需变化。

### 4.2 输入法里挂 recordQuery / recordCommit

`app/src/main/java/com/ispeech/input/IspeechImeService.kt`：

```kotlin
// —— 字段：进程内单例，与设置页共用 ——
private val stats by lazy { InputStats.get(this) }

// —— 记录封装：一律切到 IO 线程，绝不阻塞按键 ——
private fun recordCommit(type: CommitType, text: String) {
    if (text.isEmpty()) return
    serviceScope.launch(Dispatchers.IO) { runCatching { stats.recordCommit(type, text) } }
}

private fun recordQuery() {
    serviceScope.launch(Dispatchers.IO) { runCatching { stats.recordQuery() } }
}
```

**查询挂钩点（命中率分母）**：`refreshCandidates()` 里真正查询候选的地方，只有 1 处：

```kotlin
val limit = settings.candidatePageSize * MAX_PAGES
allCandidates = engine?.lookup(buffer, limit).orEmpty()
recordQuery()                      // ← 新增：每次五笔缓冲查询记 1 次（空结果也算，命中率才有意义）
```

> 要不要把 `lookupRelated()`（联想查询）也算进分母？默认**不算**：本口径是「五笔编码查询的
> 命中率」。若想统计含联想的整体命中率，在 `lookupRelated()` 里同样加 `recordQuery()` 即可。

**上屏挂钩点（`commit` 一处覆盖候选）**：

```kotlin
private fun commit(text: String, allowAssociation: Boolean) {
    // …原有逻辑…
    currentInputConnection?.commitText(text, 1)
    recordCommit(CommitType.CANDIDATE, text)      // ← 新增：commitTop / pickCandidate 都走这里
    // …原有逻辑继续…
}
```

`commitDirect()` 是英文 / 标点 / 符号 / emoji / 空格 / 换行的公共出口，给它加一个类型参数，
**5 个调用点**各自显式传类型（默认值设成 `PUNCTUATION` 以防漏改）：

```kotlin
private fun commitDirect(text: String, type: CommitType = CommitType.PUNCTUATION) {
    if (text.isEmpty()) return
    currentInputConnection?.commitText(text, 1)
    lastCommit = text
    recordCommit(type, text)                      // ← 新增
    renderCandidateView()
}

// ① handleChar() 里非字母分支（emoji / 数字 / 符号页字符）
commitDirect(applyFullWidth(raw), directType(raw))

// ② handleChar() 里英文态 / 大写分支
commitDirect(applyFullWidth(out), CommitType.ENGLISH)

// ③ handleSpace() 末尾
commitDirect(" ", CommitType.PUNCTUATION)

// ④ handleEnter() 末尾
commitDirect("\n", CommitType.PUNCTUATION)

// ⑤ commitPunctuation() 末尾（标点键）
commitDirect(text, CommitType.PUNCTUATION)

/** 直接上屏字符的类型判定：emoji → EMOJI，数字 / 符号 → PUNCTUATION，其余算英文。 */
private fun directType(raw: String): CommitType {
    val cp = raw.codePointAt(0)
    return when {
        cp >= 0x1F000 || (cp in 0x2600..0x27BF) -> CommitType.EMOJI
        cp in 0x30..0x39 -> CommitType.PUNCTUATION
        else -> CommitType.PUNCTUATION
    }
}
```

**长文本挂钩点（PHRASE）**：

```kotlin
private fun insertFromPanel(text: String) {      // 快捷短语 / 剪贴板面板插入
    if (text.isEmpty()) return
    currentInputConnection?.commitText(text, 1)
    lastCommit = text
    recordCommit(CommitType.PHRASE, text)         // ← 新增
    closePanel()
}

private fun commitAiReply() {                    // AI 回复上屏
    // …原有逻辑…
    ic?.commitText(candidate.insertText, 1)
    lastCommit = candidate.insertText
    recordCommit(CommitType.PHRASE, candidate.insertText)   // ← 新增
    // …原有逻辑继续…
}
```

需要的 import：

```kotlin
import com.ispeech.data.CommitType
import com.ispeech.data.InputStats
```

### 4.3 简繁转换按钮：放**候选条工具栏**（推荐），不放符号页

**结论：推荐放在候选条工具栏（`candidate_view.xml` 齿轮旁边），而不是符号页。**

理由：

1. 简繁转换的典型用法是「**整段文本转一下**」——用户希望随时点得到，而符号页要先切
   布局（`123 → #+=`）才能点到，切回来又要两次；
2. 工具栏本来就有设置入口（`candidate_settings` 齿轮 TextView，字符方案不引入新 drawable），
   照同样写法加一个「简/繁」开关成本最低；
3. 符号页第 1/2 页第三行虽然有空位，但那两页是「标点 / 数学符号」语义，放状态开关不协调。

**方案 A（推荐：常驻开关，持久化）**

```kotlin
// 1) KeyDef.kt：KeyAction 增加一个值（新增枚举值会要求所有 when 补分支，
//    至少 handleSoftKey()，编译器会强制你补全）
    /** 切换简繁输出（繁体上屏）。 */
    TOGGLE_HAN,

// 2) SettingsStore.kt：存一个开关（默认关）
private val KEY_HAN_CONVERSION = booleanPreferencesKey("han_conversion")

fun keyboardSettingsFlow(context: Context): Flow<KeyboardSettings> = context.safeData().map { prefs ->
    KeyboardSettings(
        // …既有字段…
        hanConversion = prefs[KEY_HAN_CONVERSION] ?: false,
    )
}

suspend fun setHanConversion(context: Context, enabled: Boolean) =
    context.editSettings { it[KEY_HAN_CONVERSION] = enabled }

// 3) KeyboardSettings.kt：加字段
data class KeyboardSettings(
    // …既有字段…
    val hanConversion: Boolean = false,   // 繁体输出
)

// 4) KeyboardScreen.kt：加一行开关（照抄「全角输出」那行）
HorizontalDivider()
KeyboardSwitchRow(
    title = "繁体输出",
    subtitle = "上屏前把候选与文字转成繁体（基于 OpenCC 字符表）",
    checked = settings.hanConversion,
) { viewModel.setHanConversion(it) }   // KeyboardViewModel 里同样转发一次 SettingsStore.setHanConversion

// 5) candidate_view.xml：齿轮左边加一个开关按钮
<TextView
    android:id="@+id/candidate_han"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:clickable="true"
    android:contentDescription="简繁切换"
    android:focusable="true"
    android:gravity="center"
    android:paddingStart="10dp"
    android:paddingTop="4dp"
    android:paddingEnd="4dp"
    android:paddingBottom="4dp"
    android:text="简"
    android:textColor="#666666"
    android:textSize="14sp" />

// 6) CandidateView.kt：照抄 onSettingsClick 的写法
var onHanClick: (() -> Unit)? = null
// 绑定 + 点击回调里调用 onHanClick?.invoke()
// 再加一个状态刷新方法，例如 fun setHanEnabled(enabled: Boolean) { hanView.text = if (enabled) "繁" else "简" }

// 7) IspeechImeService.kt：改设置 + 应用到上屏
KeyAction.TOGGLE_HAN -> toggleHanConversion()          // handleSoftKey 里的分支

private fun toggleHanConversion() {
    val enabled = !settings.hanConversion
    settings = settings.copy(hanConversion = enabled)
    serviceScope.launch { SettingsStore.setHanConversion(this@IspeechImeService, enabled) }
    statusText = if (enabled) "繁体输出" else null
    renderCandidateView()
}

/** 统一的简繁出口：只在开关打开时转换，转换本身不抛异常（失败原样返回）。 */
private fun applyHan(text: String): String =
    if (settings.hanConversion) toTraditional(text) else text

// 应用点（两处即可覆盖全部上屏路径）：
//   commit()        -> val out = applyHan(text)
//   commitDirect()  -> val out = applyHan(text)
// 标点 / 空格 / 换行不必转换（标点不在字符表里，转换等于原样）。
```

**方案 B（轻量：符号页放一个一次性按钮，不需要新设置项）**

```kotlin
// KeyboardLayouts.symbols() 第 1 页第 3 行末尾追加：
KeyDef.fn(KeyAction.TOGGLE_HAN, "简繁", 1.2f, KeyStyle.SPECIAL),
// 服务侧仍需 KeyAction.TOGGLE_HAN 分支；把 settings.hanConversion 换成服务内的
// private var hanMode: Boolean = false（仅当次会话生效，重启输入法复位）。
```

**工具函数调用方式**（两种写法等价）：

```kotlin
import com.ispeech.data.HanConversion
import com.ispeech.data.toTraditional     // 顶层函数
import com.ispeech.data.toSimplified

HanConversion.install(this)               // 可选：显式装载（幂等；不调用也会惰性装载）
val t = toTraditional("发展与国家")        // 發展與國家
val s = toSimplified("發展與國家")         // 发展与国家
HanConversion.isReady                     // 装载状态
HanConversion.entryCounts                 // (s2t 条数, t2s 条数)
```

惰性装载走 `AppContainer.applicationContext()`（`IspeechApp.onCreate` 已初始化），
因此**不需要**为了简繁转换改 `AppContainer`。读不到 assets 时只打 `Log.w`，
`toTraditional` / `toSimplified` **原样返回**，不会抛异常、不会打断输入。

---

## 5. 校验

```bash
python3 tools/verify/check_stats.py              # 离线校验（不联网、不改文件）
python3 tools/gen/build_han_assets.py --check    # 只校验资产自洽（不联网）
python3 tools/gen/build_han_assets.py            # 联网重新生成资产（会覆盖 assets）
```

`check_stats.py` 覆盖：源码契约（常量 / 落盘格式 / 原子写 / 降级路径）、Python 复刻的聚合逻辑
（跨天分桶、除零、>1 上限、Top 排序稳定与截断、字数口径、词长上限、往返一致、坏行容错、
400 天裁剪、reset）、映射数据（无坏行、无重复键、裁剪口径、体积、双向回环抽样、已知常用字、
代理对不拆分）、文档完整性。任一 `FAIL` 时退出码为 1。

---

## 6. 未验证 / 待主 Agent 确认的部分

- **未编译**：本机没有 JDK / Android SDK，`InputStats.kt` / `HanConversion.kt` /
  `StatsScreen.kt` 没有经过 `./gradlew :app:assembleDebug`；Compose 与 Kotlin 的
  编译期问题（类型推断、API 兼容性）只能由有 SDK 的环境暴露。
  已按现有页面（KeyboardScreen / ThemeScreen）的依赖与写法对齐，未引入任何新三方库。
- **Android 运行时行为**（assets 读取、DataStore 无关、文件 rename 原子性、
  真实命中率数值）未在设备上验证。
- **统计写入频率**：当前每次上屏都会重写一次统计文件（文件小、有界，但仍是每次 IO）。
  若担心耗电 / IO，可在 `InputStats` 上加节流（例如脏标记 + 1 秒防抖）或改用
  `serviceScope.launch(Dispatchers.IO)` 之外的批量提交。
- **命中率口径**默认只统计五笔编码查询，不含联想查询、不含快捷短语面板筛选。
- **简繁字符级歧义**（里→裏、乾→干、后→後）未做短语消歧，属于已知限制。
- 4.3 的方案 A 需要主 Agent 修改 `KeyDef.kt` / `SettingsStore.kt` / `KeyboardSettings.kt` /
  `KeyboardScreen.kt` / `KeyboardViewModel.kt` / `candidate_view.xml` / `CandidateView.kt` /
  `IspeechImeService.kt`；本 Agent 未改动其中任何一个。
