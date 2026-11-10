# ADR-0001：五笔双字词组自学习（VOCA-65 条目 3）

- **状态**：Proposed（待人类评审）
- **决策日期**：2026-11-10
- **决策人**：架构师（agent `e7ea905a-48f9-4dc9-86d5-32346dc237da`，代 VOCA-65 Q3-1~Q3-5）
- **关联需求**：VOCA-65 §3、§3.3、§7 Q3-1~Q3-5、AC-3.1~3.12、§9 A3
- **关联 issue**：VOCA-67（本 ADR）→ VOCA-65（父）
- **复审条件**（任一满足即重审）：① UX 真机复核后把时间窗 W 定为 ≠ 5 s；② 二期拼音词组立项；
  ③ 上线 7 天后误学率 > 10%；④ 用户词库文件格式需要第 5 列。
- **范围**：只做架构决策与契约。不写实现、不做视觉稿。一期只做五笔 2 字词组。

---

## 0. 先读这一节：对需求文档的一处修正

需求 §3.3 写「119 条不一致，集中在同部首字，属词库自身的简码约定」。我按实现要用的取码口径
重新全量回算，**这个 119 是「按最高权重取单字码」的口径，与实现必须采用的「按最长码取单字码」不是一回事**。
口径换掉后不一致从 119 降到 27，其中 24 条是词库同词多码行，只有 3 条是真的推导不出来。
两者都不影响结论（推导可行），但**影响实现**，所以本 ADR 以实测口径为准，并回写需求差异。

复现命令（只用 Python 3 标准库，不联网）：

```bash
python3 - <<'PY'
import collections, io
rows=[]
with io.open('app/src/main/assets/wubi86.dict', encoding='utf-8') as f:
    for line in f:
        line=line.rstrip('\n')
        if not line or line.startswith('#'): continue
        p=line.split('\t')
        if len(p)<3: continue
        rows.append((p[0], p[1], int(p[2]), p[3] if len(p)>=4 else ('p' if len(p[1])>1 else 's')))
singles=collections.defaultdict(list)
for c,t,w,typ in rows:
    if len(t)==1: singles[t].append((c,w))
two=[(c,t) for c,t,w,typ in rows if typ=='p' and len(t)==2]
def rule(cs):                                   # 取最长码，同长取高权重
    ml=max(len(c) for c,_ in cs); pool=[(c,w) for c,w in cs if len(c)==ml]
    pool.sort(key=lambda x:(-x[1], x[0])); return pool[0][0]
full={ch: rule(cs) for ch,cs in singles.items()}
bad=[(t,c,full[t[0]][:2]+full[t[1]][:2]) for c,t in two
     if t[0] in full and t[1] in full and full[t[0]][:2]+full[t[1]][:2] != c]
print("单字", len(singles), "双字词", len(two), "不一致", len(bad),
      "一致率", round(1-len(bad)/len(two), 5))
PY
```

实测输出（本机，本仓库 `app/src/main/assets/wubi86.dict`，165,599 行）：

| 取码口径 | 单字码 | 双字词一致 | 不一致 | 一致率 |
| --- | --- | ---: | ---: | ---: |
| 最高权重优先（需求 §3.3 口径） | `wqiy`→`你` | 66,413 | 119 | 99.82% |
| **最长码优先（本 ADR 采用）** | `wqiy`→`你` | **66,505** | **27** | **99.96%** |

**119 条的真实构成（这决定了规则必须换，不是精度问题）**：

| 成因 | 条数 | 说明 |
| --- | ---: | --- |
| `产` 的最高权重码是**单字码** `u` | **92** | `产` 在词库里有 `u`/`ut`/`ute` 三条，`u` 权重最高。取「最高权重码的前 2 位」只得到 1 位的 `u`，于是所有以「产」结尾的词都算错：`共产` 正确码 `awut`，按规则算成 `awu`。**这不是简码约定，是规则在 1 位码上崩了** |
| 词库同词多码行 | 24 | 推导出的码真实存在，只是同词的权重较低那条：`蹒跚` 同时有 `khkh`(38281) 和 `kakm`(26)；`跳跃` 有 `kikt`；`狐狸` 有 `qrqj` |
| 词库真简码约定 | 3 | `猪猡`(`qfql`)、`猿猴`(`qfqw`)、`狰狞`(`qqqp`)——按规则算出 `qtqt` |

92 + 24 + 3 = 119，与需求文档数字完全吻合。**换成长码优先后，92 条整体消失，27 条保留**。

27 条的完整清单（实现时对表核对，T-2 用集合精确断言）：

- 24 条同词多码：`蹒跚`、`蹂躏`、`踊跃`、`踌躇`、`蹊跷`、`践踏`、`跳跃`、`踯躅`、`蹉跎`、`跟踪`、
  `踉跄`、`围堵`、`鱿鱼`、`鱼鳞`、`猖獗`、`猖狂`、`猩猩`、`狩猎`、`狐狸`、`鲜鱼`、`狡猾`、
  `猕猴`、`鲸鱼`、`狼狈`——推导码（多为 `khkh`/`qtqt`/`qgqg`/`lfff`）下**就是该词**的另一条记录。
- 3 条真简码：`猪猡`、`猿猴`、`狰狞`。
- 另 2 条**不可推导**：`T恤`、`U盘`——首字不是单字词条（`T`/`U` 不在单字表里），规则直接放弃。

两种口径在「前 2 位」上真正分歧的字只有 **2 个**（全 70,391 字）：

- `产`：最高权重 `u`（1 位）vs 最长 `ute` → 前缀 `u` vs `ut`，影响 92 条词；
- `㣺`：最高权重 `nyi` vs 最长 `nhty` → 前缀 `ny` vs `nh`，只出现在生僻字，对 2 字词 **0 影响**。

**结论**：五笔 2 字词组编码可由两字码确定性推导，可以直接进验收标准。
实现必须采用「最长码优先」，且**必须保证取到的码长度 ≥ 2**——用短码会让一整类词静默算错，
而这类错误在候选条上只表现为「学到的词打不出来」，极难定位。

---

## 1. 学习判定位置

### 决策：位置

**放在独立 tracker：新增 `engine/PhraseLearningTracker.kt`，纯 Kotlin、零 Android 依赖、可 JVM 单测。
`IspeechImeService` 只做「把事件喂进去 + 拿结果去写库」的接线，不含任何判定逻辑。**

### 为什么

| 候选方案 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- |
| A. 判定写在 `IspeechImeService` 状态机里 | 少一个文件；能直接读 `buffer`/`panel` 等私有状态 | AC-3.1~3.6、3.9 明确要求「判定逻辑必须是可被 JVM 单测直接调用的纯函数」；`IspeechImeService` 是 2282 行的 Android `InputMethodService`，单测要跑 Robolectric；状态字段有 6 个以上互相耦合（`buffer`/`t9Digits`/`panel`/`mode`/`codeOverride`/`aiReplyActive`），判定与展示逻辑会继续缠在一起 | 否 |
| B. 独立 tracker（纯函数） + 服务接线 | 判定逻辑 100% 可 JVM 单测，直接覆盖 §3.5 的 8 条 JVM 用例；服务侧只留 4 个一行调用点，回归面最小；未来二期扩 3 字词 / 拼音只改 tracker | 多一个文件、多一次状态传递 | **选 B** |
| C. 放进 `UserDictStore` | 与写库同层 | `UserDictStore` 负责持久化，塞判定会让它同时承担「什么时候学」和「怎么存」，职责混乱；且它现在被设置页直接调用，加判定会污染管理接口 | 否 |

### 状态机

tracker 只认一种输入：**「刚刚上屏了一段文本」**，并自带「上一跳」记忆。

```text
状态：pending(上一跳) | idle

事件                        判定条件（全部满足）                      动作
──────────────────────────────────────────────────────────────────────────────
onCommit(text, source, t)    source == SINGLE_CHAR                     idle  → pending(text, t)
                             （单字候选上屏）

onInterrupt()                任何「不该学习的中断」                    任意  → idle
                             （见下表）

onCommit(text, source, t)    source == SINGLE_CHAR                     若 0 < t-pending.t ≤ W（默认 5 s）且
                             ？已达两跳                                两字不同 or 该词在词库 → 判定「成词」
                                                                      否则 idle
                             其余 source（含标点 / 空格 / 英文 /
                             emoji / 语音 / 短语 / 联想 / 面板）
                                                                      → idle（不学习）

reset()                      换输入框 / 换输入态 / 关面板               任意  → idle
```

**「单字候选上屏」的严格定义**（对应 `IspeechImeService.pickCandidate` 的调用链）：

```kotlin
source == SINGLE_CHAR ⟺ 候选来源 kind ∈ {WUBI} 且 insertText.length == 1（按码点）
```

- `kind == WUBIPHRASE / PINYIN / PHRASE / QUICKAPPLY` 一律不是单字候选；
- `commitTop()` / 空格自动上屏 / 数字键 1-9 选候选，最终都走 `pickCandidate()` 或
  `commit(top.insertText)`，算单字候选（只要 `kind == WUBI` 且文本长度 1）；
- **`commitDirect` 系列（标点 / 空格 / 换行 / 英文 / emoji / 语音）一律算中断**，
  它们不经过候选，与「拼」无关；这正是 AC-3.3 要挡的；
- **「无候选时上屏原始编码」不算单字**（`IspeechImeService.kt:596`、`:913` 的
  `commit(buffer, allowAssociation = false)`）：上屏的是 `klge` 这样的字符串而不是汉字，
  与「选了一个单字」不是一回事。这两条路径**不强制显式传参**——默认值 `WORD_CANDIDATE`
  与 `DIRECT` 在本状态机里结果都是 idle（§1 状态机表），差别只在日志 `reason`；
  实现时顺手传 `DIRECT` 可让跳过原因可读，非必做。

### 接线点（服务侧只改 6 处，全部是「喂事件」或「重置」）

| # | 位置 | 调用 | 说明 |
| --- | --- | --- | --- |
| 1 | `IspeechImeService.kt:1164` `pickCandidate()` 的 `commit(...)` 之前 | `tracker.onCommit(insertText, sourceOf(candidate), now)` | 唯一的「可能成词」入口 |
| 2 | `IspeechImeService.kt:921` `commit(text, allowAssociation, source)` | 新增 `source: CommitSource = CommitSource.WORD_CANDIDATE` 默认参数；原样透传给 tracker，不自行判定 | 默认值让 4 个既有调用点（`:596`/`:905`/`:913`/`:917`）**无需改动**：它们要么不是单字（`:905` 语音、`:917` 由 #1 显式传值）、要么是「无候选时上屏原码」（`:596`/`:913`）。`WORD_CANDIDATE` 与 `DIRECT` 在本状态机里**结果相同**（都 → idle），差别只在跳过日志的 `reason` 取值；因此这两处**不强制改**，实现时可顺手传 `DIRECT` 以让日志更准 |
| 3 | `IspeechImeService.kt:948` `commitDirect()` | `tracker.onInterrupt()` | 标点 / 空格 / 换行 / 英文 / emoji / 语音全部走这里 |
| 4 | `IspeechImeService.kt:1212` `openPanel()` / `:1268` `closePanel()` | `tracker.onInterrupt()` | AC-3.4「面板操作」 |
| 5 | `IspeechImeService.kt:752` `toggleMode()` / `:399` `onStartInput()` | `tracker.reset()` | 换态 / 换输入框 |
| 6 | 新增 `onUpdateSelection()` | `tracker.onInterrupt()` | AC-3.4「光标移动」，见下 |

**`source` 的取值规则**（实现时按此表填，不要就近判断）：

| 调用点 | 传什么 |
| --- | --- |
| `pickCandidate` → `commit(insertText, source = sourceOf(candidate))` | `sourceOf`：`kind == WUBI && insertText 码点长度 == 1` → `SINGLE_CHAR`，否则 `WORD_CANDIDATE` |
| `commitTop` 的 `commit(top.insertText)` | 同上，用 `sourceOf(top)` |
| `:596` / `:913` 的无候选原码上屏 | `DIRECT`（可选；不传也走默认 `WORD_CANDIDATE`，结果相同） |
| `commitT9` 的 `commit(text)` | `WORD_CANDIDATE`（九宫格不走五笔单字，且 `t9Digits` 非空时 `learn()` 已短路） |
| `commitVoiceText` → `commit(candidate.insertText)`（`:1646`） | `WORD_CANDIDATE`（语音上屏是整句） |

**光标移动**（AC-3.4 后半）现状**没有**任何事件源：`IspeechImeService` 未覆写 `onUpdateSelection`，
全仓 `getTextBeforeCursor` 只用于撤销与 AI 原文比对（`999`/`1226`/`1895`/`2092` 行）。
因此本 ADR 要求**新增 `onUpdateSelection` 覆写**，只做一件事：

```kotlin
override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
                               newSelEnd: Int, candStart: Int, candEnd: Int) {
    super.onUpdateSelection(...)
    // 只关心「非本输入法自己造成的光标变化」：本服务每次 commit 都会触发一次 selection 回调，
    // 用 lastCommit 的游标差值判定，避免用户正常上屏被误判为中断。
    if (!isSelfCausedSelectionChange(newSelStart)) tracker.onInterrupt()
}
```

**风险标注（待实现验证）**：`INPUT_METHOD_SERVICE` 的 `onUpdateSelection` 回调时序在各 ROM 上
不完全一致，本输入法自身 `commitText` 也会触发。判定「自因 vs 他因」的具体方法必须在实现阶段用
真机日志验证一次（见 §8 T-3），本轮架构层面只定契约：**任何非自因光标变化都必须中断**。
若验证发现无法可靠区分，降级方案是「只把 `newSelStart != lastCommittedCursor` 当用户移动」，
并在 AC-3.4 的真机验证里补一条人工操作用例；不得因为难判就删掉这条 AC。

### 失败分支

| 分支 | 行为 |
| --- | --- |
| 时间戳回退（`t < pending.t`，如系统时间被改） | `idle`，不学习；打 WARN 日志 `phrase_learn_clock_backwards` |
| 编码推导返回 null（见 §2） | `idle`，不写入；计入 `phrase_learn_skipped` 计数 |
| 该词已存在于系统词库 | `idle`，不写入；计入 `phrase_learn_existing` 计数 |
| 同字叠词且系统词库无此词 | `idle`，不写入；计入 `phrase_learn_repeat` 计数 |
| 写库抛异常 | 由 `UserDictStore` 现有 `runCatching` 吞掉并打日志；tracker 状态照常复位为 `idle`（不因写失败卡在 pending） |
| 学习开关关闭 | **tracker 仍照常推进状态**，只在「决定写库」那一刻检查开关。理由：开关是「要不要落盘」而不是「要不要识别」；若在事件入口就短路，切换开关的瞬间状态会不自洽 |

---

## 2. 编码例外回退

### 决策：例外处理

**采用「最长码优先」推导两字码；推导失败不猜、不查系统词库兜底，直接放弃这一次学习。
例外词由「系统词库已有则不学」这条规则天然吸收，不做任何词条级别的白名单。**

### 具体规则（可判定，逐条可测）

**步骤 1：单字码解析**（`PhraseCodeDeriver.charPrefix`）

对字 `c`，在 `wubi86.dict` 的单字条目集合 `S(c) = { (code, weight) | text == c, type == s }` 中：

```text
取 max(len(code)) 作为 L(c)
在 { code | len(code) == L(c) } 中取 weight 最大者；weight 相同取字典序最小者
返回该 code 的前 2 位
```

实测：70,391 个单字里，**最长码** `L(c)` 只有 3 和 4 两种分布（3,426 个 / 66,965 个），
**不存在 `L(c) < 2` 的字**，所以「前 2 位」永远取得到，不需要短码特判。

> 为什么是「最长码」而不是「最高权重码」：
>
> 1. **短码会崩**。`产` 的最高权重码是 1 位的 `u`（权重 5,668，高于 `ut`/`ute`），取前 2 位只能拿到 `u`，
>    92 个以「产」结尾的词会全部算错（`共产` 应为 `awut`，算成 `awu`）。这是 119 条例外里的 92 条。
> 2. **词组码的定义就是全码前 2 位**。`加` 的 `lk`(1513) 是简码、`lkg`(14048) 是次码，
>    「加班」的码 `lkgy` 用的是 `lkg` 的前 2 位 `lk`——这里恰好一致，但换作 `产` 就不一致。
>    依赖「碰巧一致」的规则不能进验收标准。
> 3. 两种口径的一致率是 99.82% vs 99.96%（见 §0），差距全在 92 条 `产` 类错误上。

#### 步骤 2：拼接

```text
code("喂猫") = charPrefix('喂') + charPrefix('猫') = "kl" + "qt" = "klqt"
```

#### 步骤 3：可判定性检查（两道闸，任一不满足即放弃）

| # | 闸门 | 判定 | 不满足时 |
| --- | --- | --- | --- |
| G1 | 两字都是 CJK 统一表意文字（`一..鿿` 与扩展区） | 直接判字符 | 放弃（覆盖 `T恤`/`U盘`） |
| G2 | 两字都能在单字表里解析出 `charPrefix`（长度 ≥ 2） | 查表 | 放弃 |

**只有这两道闸。** 下面这条曾经考虑过、最终否决，记录理由以免实现时被重新加回来：

> **否决的 G3**：「推导出的 4 位码不得是某个单字的码」。
>
> 否决理由（实测）：
>
> - 66,534 条**真实词库词组**里，有 **12,682 条（19.06%）** 推导码同时也是某个单字的码；
>   对随机字对抽样，这个比例是 **21.77%**。也就是说 G3 会**静默拦掉约 1/5 的合法学习**。
> - 它的动机是「防止用户随手连选两字被误学」。但这正是本功能要支持的场景——
>   长尾新词全部长这样。用 20% 的召回换一个已有三重保护兜底的假想风险，不划算。
> - 它挡住的**已知 24 条例外本来就被「系统词库已有则不学」覆盖**（推导码下就是该词的
>   另一条记录，`systemHasWord` 直接判真）。G3 对这 24 条没有额外贡献。
> - 更糟的是它的失效形态不可解释：用户会发现「有些词能学、有些不能」，而规律是
>   「推导码碰巧等于某个单字码」——用户无法从任何界面现象推断出来。

**例外回退的完整判断表**（27 条例外 + 2 条不可推导，逐条核对本规则的结果）：

| 词 | 推导码 | 词库实际码 | 本规则结果 | 依据 |
| --- | --- | --- | --- | --- |
| `蹒跚` 等 24 条多码词 | 真实存在的码（如 `khkh`） | 另有低权重码（如 `kakm`） | **放弃学习** | 「系统词库已有不学」：推导码下**就是**该词的另一条记录，用户在系统词库已能打出，学它属于重复占位 |
| `猪猡` / `猿猴` / `狰狞` | `qtqt` | `qfql`/`qfqw`/`qqqp` | **放弃学习** | 同上：这 3 个词已在系统词库，`systemHasWord` 直接判真 |
| `T恤` / `U盘` | 不可推导 | `ttnt`/`uute` | 放弃（G1/G2） | 首字非单字词条 |

**核对方式**：29 条（27 + 2）构成表驱动单测用例集，实现时必须全绿（见 §7 T-2）。
注意这 29 条**全部落在「放弃」分支**，且放弃理由**都不是 G3**——这正是可以安全撤掉 G3 的证据。

### 放弃的备选方案

| 方案 | 为什么不选 |
| --- | --- |
| 接受推导码、不做任何检查 | 会让 `T恤` 这类词挂到空码上；且无法挡住 19% 的单字码碰撞 |
| 查系统词库优先（推导不出就遍历词库找该词） | 全量遍历 9 万条词组每次上屏都要做，且「明明能推导却去查表」是多余复杂度；词库是只读资产，遍历成本换不来正确性 |
| 写入前校验（写入后回查确认能查到） | 回查只能确认「写进去了」，不能确认「码是对的」——`UserDictStore` 的 `lookupCandidates` 是精确/前缀匹配，写什么就能查到什么，校验形同虚设 |
| 维护 119（或 27）条白名单 | 词库更新就要重算白名单，且白名单本身无法覆盖未来新增词条；用规则（G1/G2 + 已有不学）表达同一语义更稳 |

---

## 3. 拼音二期方案（只列前置条件，不展开实现）

### 现状事实（本轮实测，`app/src/main/assets/pinyin86.dict`，75,993 行）

| 指标 | 数值 |
| --- | --- |
| 双字词条 | 43,053 |
| 单字 | 11,579，其中多音字 2,978（**25.7%**） |
| 按「取最高权重读音」推导，与词库一致 | 29,347（**68.2%**，13,675 不一致） |
| 词库码是否落在两字读音的笛卡尔积内 | 43,021（**99.93%**） |

即：**拼音的正确率远低于五笔**（68% vs 99.96%），因为多音字选择无法只靠单字表决定
（`的` 有 `de`/`di`/`dí`，`了` 有 `le`/`liao`；`猫` 有 `mao`/`miao`，词库里 `猫` 两条同权重 5517）。
「99.93% 落在笛卡尔积内」只说明码空间够，不说明选得对。

### 决策：二期不做，前置四条

**一期不做拼音词组学习；二期必须解决多音字消歧后才做，且二期不得复用一期的「推导」路径
作为唯一来源。**

### 二期前置条件（四条，缺任一不立项）

1. **多音字消歧数据源**：必须有一个「字 → 首选读音」的可离线数据（例如从 `pinyin86.dict` 的
   双字词反推的读音频率表，或引入词级注音数据）。**不接受**「两字读音笛卡尔积全试一遍再写多条」——
   那会把一个词写成 4 条（`猫`×`毛` 组合），污染用户词库。
2. **可测率门禁**：消歧方案在 43,053 条双字词上的回算一致率必须 ≥ 99%，否则不做。
   依据：五笔一期是 99.96%，拼音若只有 90%，用户学 10 次错 1 次，体验反而不如不学。
3. **音节空格问题的归属**：`UserDictStore.kt:95-98` 的 `buildUserDictEntry` 已经对 code 做了
   `filter { it in 'a'..'z' || it in '0'..'9' }`（**空格会被剔除**），所以 `ni hao` 与 `nihao`
   落盘后是同一个键。二期要明确：**写库统一写无空格全拼**，与 `PinyinEntry.full` 口径一致
   （`PinyinDictLoader.kt:18`）。这条现在就可以定，不需要二期再讨论。
4. **多音字与用户意图冲突的处理**：用户打 `miao` 选了「猫」，再打另一个字组成词时，
   应以**用户实际输入的音节**为优先，而不是推导值。这要求 tracker 在一期之外额外记录
   上一跳的输入码（`pending.code`），二期直接复用该字段扩展，不改状态机。

> 二期不在一期编码里预留接口之外的任何东西：`PhraseLearningTracker` 的 `source` 枚举
> 预留 `PINYIN_SINGLE_CHAR` 即可，判定逻辑与推导函数在二期新增实现。

---

## 4. 用户词库模型：词组是否需要显式标记位

### 决策：不加标记位

**一期不加标记位，继续用长度隐式判断；但把判断从两处（`UserDictStore.kt:362`
与 `IspeechImeService.kt:1051`）收敛到一处，并把长度口径从 UTF-16 修正为**码点**。
文件格式不变（仍是 `code<TAB>text<TAB>count<TAB>mode`），不需要版本号、不需要迁移。**

### 现状

| 位置 | 判断 | 用途 |
| --- | --- | --- |
| `UserDictStore.kt:362` | `if (text.length == 1) WUBI else PHRASE` | 决定候选档位（单字 < 词组） |
| `CellDictEngine.kt:100` | 同上，复制了一份 | 同上 |
| `IspeechImeService.kt:1051` `dictModeOf` | 看 `LookupKind` | 决定写哪个库（`wb`/`cn`） |

### 为什么可以不加标记位

- 「是不是词组」在**五笔模式**下与「文本是 1 个码点」等价：`LookupKind.WUBI` 只由
  `WubiEngine` 的单字条目产生（`WubiEngine.kt:202`，`entry.isPhrase` 为假），
  `WUBIPHRASE` 由词组条目产生；用户词库里的条目只要码点数 > 1 就必然是词组档。
- 加标记位要付出：文件格式变成 5 列 → `parseUserDictLine`（`UserDictStore.kt:57`）的
  兼容分支再加一层 → 导出/导入/同步（`DictionaryViewModel`、`SyncRepository`）三处都要改 →
  AC-3.10「现有用户词数据必须兼容、不得要求清库」的风险面从 0 变成 1。
- 收益为零：没有任何行为依赖「这个词是学来的还是手动加的」——AC-3.10 的治理入口（删除/清空）
  两者共用；AC-3.6 的 `count` 累加也共用。

### 代价与对冲

**代价**：隐式判断把「长度」当作「是什么」，一旦未来出现「用户手动加了一个 2 字词但希望它排单字档」
的诉求，就会冲突。

**对冲**（写进代码注释，不新增字段）：

```kotlin
// 不变量：UserDictMode.WUBI 下 codePointLength(text) == 1 ⟺ 单字档，> 1 ⟺ 词组档。
// 该不变量由 UserDictStoreTest#entryKindFollowsCodePointLength 守护；若将来需要打破它，
// 必须加显式列并按 ADR-0001 §4「迁移方式」执行，不允许在调用点各自特判。
```

**长度口径必须修（实测，非推断）**：`wubi86.dict` 的 70,391 个单字里有 **42,870 个是非 BMP 字符**
（`𦟖`、`𡊃` 等），UTF-16 下 `String.length == 2`。因此现有的 `text.length == 1` 判断
**会把这些字错判成词组档**（候选档位与排序都受影响）。这是既有缺陷，但本功能依赖同一判断，
必须改用 `codePointLength`（定义见 §5.2），否则「含生僻字的词」会在单字档/词组档之间串档。

**这是本 ADR 唯一要求改既有行为的地方**，影响面与回滚见 §15。

**唯一需要改的代码**：把 `UserDictStore.kt:356-366` 的 `toCandidate()` 里的判断抽成
`internal fun entryKindOf(text: String): LookupKind`（内部用 `codePointLength`），
让 `CellDictEngine.kt:100` 与 `IspeechImeService.dictModeOf` 复用同一判定，
消除「三处各自隐式判断」。行为变化仅限非 BMP 单字（42,870 个字从词组档回到单字档，即修正）。

### 迁移方式（若将来必须加显式列）

1. 文件格式加第 5 列 `kind`（`s` = 单字 / `p` = 词组）；
2. `parseUserDictLine` 的 `when` 增加分支：第 5 列为 `s`/`p` 时以其为准，否则回落到 `codePointLength(text)`；
   现有 4 列数据**原样按长度推断**，不需要重写文件；
3. 首次写盘时统一补列（`persist()` 已有全量重写逻辑，天然完成迁移）；
4. 回滚 = revert 单个 PR；旧版本读 5 列文件时，第 5 列会被 `fields.last().toIntOrNull()` 判为非数字，
   走 `else -> return null` **丢弃该行**——这是回滚风险，因此**必须**在版本号或文件头注释里标注格式版本，
   回滚前先确认无 5 列数据（见 §5）。

---

## 5. 接口与数据契约

### 5.1 学习判定输入（tracker 的公开接口）

```kotlin
/** 上屏来源：只有 [SINGLE_CHAR] 可能参与词组学习。 */
internal enum class CommitSource {
    SINGLE_CHAR,     // 五笔单字候选（kind == WUBI，文本按码点为 1 个字符）
    WORD_CANDIDATE,  // 词组候选 / 用户词 / 拼音候选
    DIRECT,          // 标点 / 空格 / 换行 / 英文 / emoji / 语音（commitDirect 系）
    PANEL,           // 快捷短语面板 / AI 回复
    ASSOCIATION,     // 联想上屏
}

/** 一次成词判定结果。null 表示「本次不学习」。 */
internal data class PhraseLearningDecision(
    val code: String,        // 推导出的五笔码，4 位小写 a-z
    val text: String,        // 两字词组，码点长度 2
    val firstCode: String,   // 第一字的全码（便于日志/复盘）
    val secondCode: String,  // 第二字的全码
)

internal class PhraseLearningTracker(
    private val codeOf: (String) -> String?,         // 单字（码点串）→ 全码（长度 ≥ 2）；查不到返回 null
    private val systemHasWord: (String) -> Boolean,  // 系统词库是否已有该词（精确匹配）
    private val windowMillis: Long = LEARN_WINDOW_MILLIS,  // W，见 §6；常量定义见下
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun onCommit(text: String, source: CommitSource): PhraseLearningDecision?
    fun onInterrupt()
    fun reset()
    /** 供单测注入固定时间：把 clock 换成可变实现即可，不需要额外 API。 */
}
```

> **`codeOf` 的参数类型是 `String` 而不是 `Char`**——见 §5.2 的说明：`wubi86.dict` 里
> 有 42,870 个非 BMP 单字（如 `𦟖`），单个 `Char` 承载不下，用 `Char` 会让这些字静默查不到。

#### 契约要点

- `onCommit` 是**纯函数式**的：不 IO、不打日志、不读 Android `Context`；
  所有外部依赖通过构造参数注入（`codeOf` / `systemHasWord` / `clock`）。
- 返回值只有两种：`PhraseLearningDecision`（调用方负责写库）或 `null`（不学）。
- **不抛异常**：任何非法输入（空串、超长、非 CJK）返回 `null`。
- 时间以 `Long` 毫秒传递，不引入 `Instant`/`LocalTime`（避免 API 版本差异）。
- **`codeOf` 必须返回长度 ≥ 2 的码**。这是调用方的义务，不是 tracker 的校验点——
  实现侧由 `WubiEngine.fullCodeOf` 保证（见 §5.2）。tracker 内部对长度 < 2 的返回值
  按「不可推导」处理（返回 `null`），以防实现违约时静默算出 1 位码。

#### 「同字不学」规则的落点

AC-3.9（`你`+`你` 不学）在 tracker 内部判定，不进 `systemHasWord`：

```kotlin
if (first == second && !systemHasWord(text)) return null   // 同字且词库无此词 → 误触
```

顺序很重要：**先查词库**。若词库已有该叠字词（`嘿嘿`/`呵呵`），走的是「已有不学」分支，
日志 `reason` 应为 `existing` 而不是 `repeat`——两者对排查的含义不同。

### 5.2 编码推导函数签名

```kotlin
/** 五笔双字词组码推导：纯函数，不查词库、不依赖 Android。 */
internal object PhraseCodeDeriver {
    /**
     * @param first 第一字（单个**码点**的 String，不是 Char）
     * @param second 第二字（同上）
     * @param codeOf 单字码点串 → 全码（实现侧由 WubiEngine 提供，测试侧用字典桩）
     * @return 4 位小写码；任一前置条件不满足返回 null
     */
    fun derive(first: String, second: String, codeOf: (String) -> String?): String?
}
```

> **为什么用 `String` 而不是 `Char`**（实测，不是推断）：`wubi86.dict` 的 70,391 个单字里，
> **42,870 个是非 BMP 字符**（如 `aaae` → `𧝣`、`aaal` → `𦾟`），UTF-16 下占 2 个 `Char`。
> 用 `Char` 会把它们拆成代理对的一半，`codeOf` 必然查不到——表现为「含生僻字的词永远学不会」，
> 且不会报错。用「单个码点的 `String`」既覆盖生僻字，也让 `text.codePointCount() == 1`
> 成为可断言的前置条件。
>
> 对应的判定口径统一为**码点**，不是 UTF-16 长度：
>
> ```kotlin
> /** 码点长度；不能用 String.length（非 BMP 字会被算成 2）。 */
> internal fun codePointLength(text: String): Int =
>     if (text.isEmpty()) 0 else text.codePointCount(0, text.length)
> ```
>
> 仓库里已有同类手写先例可对齐：`InputStats.charCountOf`（`InputStats.kt:207-221`）用
> `isHighSurrogate()` 逐位判断，`IspeechImeService.kt:2188` 用 `codePointAt`。本 ADR 选
> `codePointCount` 是因为它是最短实现且语义无歧义。
>
> 注意这与仓库现状**不一致**：`UserDictStore.kt:362` / `CellDictEngine.kt:100` /
> `WubiEngine.kt:53` / `PinyinDictLoader.kt:28` 都用 `text.length`。本 ADR 只要求修
> **`UserDictStore` 这一条链路**（见 §4），其余两处的同类缺陷记录在 §14 第 8 条，
> 建议单独开 issue。

`codeOf` 的实现侧契约（**这是本 ADR 对 `WubiEngine` 的唯一新增要求**）：

```kotlin
/** 单字全码：同字多条记录时取最长码，同长取权重最高，再同则取字典序最小。查不到返回 null。 */
fun fullCodeOf(char: String): String?
```

实测支撑与实现约束：`WubiEngine` 现在只建 `byCode: Map<String, List<DictEntry>>`（码 → 条目），
**这个方向反查不了「字 → 码」**——必须新增一张反向索引 `byText: Map<String, String>`
（单字 → 全码），在构造期一次性建好：

- 规模：单字条目 75,034 行 / **70,391 个不同字**，即 70,391 个 key；对比 `byCode`
  已持有的 165,599 个 `DictEntry`，增量同量级、可控。
- 时机：`WubiEngine` 的构造已在后台线程（`IspeechImeService.kt:275` 的
  `withContext(Dispatchers.Default)`，注释明确写了主线程构建会 `Input dispatching timed out`），
  新增索引不引入主线程风险；但**必须在现有单次遍历里顺手填表**，不能做第二遍全文解析。
- key 用 `String`（单字码点串），与 §5.2 的非 BMP 结论一致。`phrasesByFirstChar` 用 `Char`
  只做联想首字索引、不参与推导，本 ADR 不改它。

**不新建第二份词库解析**：`fullCodeOf` 挂在现有 `WubiEngine` 上，与 `byCode` 共用同一次解析。

### 5.3 词库条目结构（不变）

```text
# 用户词频 / 自造词（自动生成，可在设置页管理）
klqt<TAB>喂猫<TAB>1<TAB>wb
```

（`<TAB>` 表示一个制表符；此处用占位符是为了让本文档通过 markdown lint 的无硬制表符规则。）

| 列 | 语义 | 本 ADR 是否变更 |
| --- | --- | --- |
| `code` | 五笔码 / 拼音串（无空格） | 不变 |
| `text` | 上屏文本 | 不变（词组长度 2） |
| `count` | 使用次数 | 不变（首次写入 = 1，复用 `record()` 的 +1 语义） |
| `mode` | `wb` / `cn` | 不变（本功能只写 `wb`） |

**写入入口**：复用 `UserDictStore.record(code, text, UserDictMode.WUBI)`（`UserDictStore.kt:171`），
**不新增写入 API**。理由：`record` 已经处理了「已存在则 count+1」「persist」「线程安全」三件事，
AC-3.6 的「再次拼一次 → count=2、权重 140」直接由它满足（实测公式 `min(400, 120+10×count)`）。

---

## 6. 可代决默认值的落定与理由

| 项 | 默认值 | 落地方式 | 依据 / 可调性 |
| --- | --- | --- | --- |
| 时间窗 W | **5,000 ms** | `private companion object { const val LEARN_WINDOW_MILLIS = 5_000L }`，构造参数默认值引用它 | 需求给的估计值（五笔打两个四码字约 2–3 s）。**标注「待 UX 真机节奏复核」**；调整方式 = 改这一个构造参数 + 同步改 AC-3.2 的边界用例（5,001 ms），不动状态机 |
| 一期范围 | 五笔 2 字 | `CommitSource.SINGLE_CHAR` 只认 `kind == WUBI` | 拼音与 3 字词另立二期（§3） |
| 排序 | 复用 `+100_000` | 不改 `UserDictEngine.kt:26/36` | 系统词最大权重 65535 < 100000，用户词组恒在首位；AC-3.12 直接满足 |
| 防误触 | 三重保护 | G1/G2（§2）+ 「系统已有不学」+ 「中断即失效」+ 「同字不学」 | `嘿嘿`/`呵呵` 实测都在系统词库（`klkl`/`ksks`），由「已有不学」挡住；`你你` 实测不在（`wqwq` 下是 `像你`/`爷爷`/`爸爸`/`爹爹`），由「同字不学」挡住 |
| 开关 | 复用 `settings.learning` | 只在写库前检查 | `KeyboardSettings.kt:24`、`SettingsStore.kt:134` 已存在，UI 文案「用户词频学习」（`KeyboardScreen.kt:125-129`）无需改 |
| 学习粒度 | 每次成词写 1 次 | `record(code, text, WUBI)` | count 从 1 开始，第二次成词由 `record` 递增 |

**5 s 的具体风险**：五笔用户打两个字的真实间隔跨度可能很大——熟练用户 < 1 s，慢用户 > 8 s。
W 取 5 s 会让慢用户学不到。**建议 UX 复核时测两组**：① 连续打 20 个双字词的自然间隔分布；
② 慢速输入（逐字确认）的间隔。若 P90 > 5 s，建议 W 调到 8 s 而不是放弃功能。

---

## 7. 测试策略

### 7.1 分层归属

| 层 | 工具 | 覆盖内容 | 为什么在这一层 |
| --- | --- | --- | --- |
| 内环（JVM 纯函数） | JUnit 4（`app/src/test/`，已有 `QuickPhrasePinyinModeTest` 先例） | AC-3.1 ~ 3.6、3.9 | 需求 §3.5 明确要求「判定逻辑必须是可被 JVM 单测直接调用的纯函数」；这 7 条全是状态机 + 推导，无 Android 依赖 |
| 内环（JVM，词库表驱动） | JUnit 4 + 真实 `wubi86.dict`（`tools/verify/` 已有读取先例） | §2 的 27+2 条例外逐条核对（T-2） | 用真实资产而不是桩，防「推导规则实现与词库口径漂移」（需求文档 §9 A3 风险） |
| 外环（adb 真机） | 手工 + logcat | AC-3.7、3.8、3.10、3.11、3.12 | 涉及候选排序、设置页 UI、主线程无阻塞，JVM 无法覆盖 |
| 静态校验 | `tools/verify/*.py` | 不新增（本 ADR 不改代码） | — |

> **AC 逐条归属见 §11 的门禁表**，本节只定测试形式。

### 7.2 必须新增的测试文件（实现阶段交付，本 ADR 只定用例集）

**T-1 `PhraseLearningTrackerTest`** —— 状态机，用可变 `clock` 桩：

| 用例 | Given | When | Then | 对应 AC |
| --- | --- | --- | --- | --- |
| `derivesTwoCharWord` | pending「喂」@t0 | 「猫」@t0+1900 | 返回 `Decision(klqt, 喂猫)` | AC-3.1 |
| `expiresAfterWindow` | pending@t0 | @t0+5001 | `null` | AC-3.2 |
| `interruptByDirect` | pending@t0 | `onInterrupt()` 后 @t0+1000 上屏另一单字 | `null` | AC-3.3 |
| `interruptByPanel` | pending@t0 | `onInterrupt()` | 后续上屏 `null` | AC-3.4 |
| `resetOnInputChange` | pending | `reset()` | 后续上屏 `null` | AC-3.4 |
| `skipsExistingSystemWord` | pending「加」 | 「班」@+1000，`systemHasWord("加班")=true` | `null` | AC-3.5 |
| `skipsSameChar` | pending「你」 | 「你」@+1000，`systemHasWord=false` | `null` | AC-3.9 |
| `allowsSameCharWhenInDict` | pending「嘿」 | 「嘿」@+1000，`systemHasWord=true` | `null`（被已有挡掉） | AC-3.5 |
| `skipsNonCjk` | pending「T」 | 「恤」 | `null` | G1 |
| `skipsUnresolvable` | `codeOf` 返回 null | — | `null` | G2 |
| `skipsOneCharCode` | `codeOf` 返回 `"u"`（1 位，模拟 `产` 类错误） | — | `null`（不得算出 `u?`） | G2 |
| `firstCommitAloneLearnsNothing` | — | 只上屏 1 个单字 | `null` | 防回归 |
| `sameCharInDictReasonIsExisting` | pending「嘿」 | 「嘿」，`systemHasWord=true` | `null` 且日志 `reason=existing`（非 `repeat`） | AC-3.5 / AC-3.9 区分 |

> **`allowsSameCharWhenInDict` 的期望值说明**：需求 Q3-4 (a) 说「同字且系统词库无此词不学」，
> 隐含「同字但系统有则……」。实测 `嘿嘿`/`呵呵` 都在词库，会被 (b) 挡住，因此该用例实际也返回 `null`。
> 这不是规则冲突，而是 (a) 只对「词库没有的叠字」生效——`你你` 是唯一能触发 (a) 的现实样本。

**T-2 `PhraseCodeDeriverTest`** —— 表驱动，读 `app/src/main/assets/wubi86.dict`：

| 用例 | 断言 |
| --- | --- |
| `matchesDictForTwoCharPhrases` | 对 66,534 条双字词，推导与词库码一致率 **≥ 0.9995**（实测 0.99959）；不一致集合**必须恰为 §0 的 27 条**（集合精确断言，不是数量断言） |
| `longestCodeWinsOverSummaryCode` | `charPrefix('你') == "wq"`（`wq`/`wqi`/`wqiy` 三条中取最长 `wqiy`） |
| `summaryCodeForChanIsRejected` | `charPrefix('产') == "ut"`；若误用最高权重码会得到 1 位的 `u`，断言 `fullCodeOf('产') == "ute"` |
| `tiesBreakByWeightThenLexicographic` | 构造同长同权两条记录，断言取字典序小者 |
| `rejectsNonCjk` | `derive("T", "恤") == null` |
| `acceptsNonBmpFirstChar` | `codePointLength("𧝣") == 1`；`derive` 用真实词条 `aaae → 𧝣` 做桩，断言 `fullCodeOf("𧝣") == "aaae"` 且能正常参与推导（不被代理对拆断） |
| `rejectsCharWithoutFullCode` | `codeOf` 返回 null 时 `null` |
| `rejectsCodeShorterThanTwo` | `codeOf` 返回 `"u"` 时 `null` |

**T-2b `EntryKindTest`** —— 覆盖 §4 的码点口径修正（这是本 ADR 唯一改既有行为处）：

| 用例 | 断言 |
| --- | --- |
| `singleBmpCharIsSingle` | `entryKindOf("喂") == LookupKind.WUBI` |
| `singleNonBmpCharIsSingle` | `entryKindOf("𧝣") == LookupKind.WUBI`（现实现返回 `PHRASE`，本修正的核心） |
| `twoCharWordIsPhrase` | `entryKindOf("喂猫") == LookupKind.PHRASE` |
| `twoNonBmpCharsIsPhrase` | `entryKindOf("𧝣𦾟") == LookupKind.PHRASE`——用**合成字符串**，不是词库 fixture（实测词库里 0 条双字词含非 BMP 字），目的只是证明码点计数不会把两个非 BMP 字误判成 1 个单字 |

**T-3 `onUpdateSelection` 真机验证（不属于单测）**：见 §1 风险标注，验证「自因 vs 他因」区分方法。

### 7.3 不写的测试

- 不做「词组写入后候选排序」的 JVM 测试：排序由 `CandidateRanking` + `UserDictEngine` 的 `+100000`
  决定，两者已有既有测试覆盖（`tools/verify/check_input_logic.py` §5 用 Python 复刻校验），
  再加一层 JVM 测试是对同一事实的重复断言。
- 不做 `UserDictStore` 的写入测试：`record()` 是既有 API，行为不变。

---

## 8. 数据模型与兼容性

### 决策：**原地兼容，不加版本号，不需要迁移**

| 关注点 | 结论 | 证据 |
| --- | --- | --- |
| 现有 `user_dict.txt`（4 列）能否被新代码读 | 能，格式完全不变 | 本 ADR 不新增列（§4） |
| 现有 3 列 / 2 列历史数据 | 能，`parseUserDictLine` 已有 `when` 兼容分支 | `UserDictStore.kt:57-92` |
| 学习的词组会不会与现有词条冲突 | 不会，键是 `mode+code+text` 三元组 | `UserDictStore.kt:157` |
| 用户已手动加过「喂猫」 | `record()` 会把它 `count+1`，不会重复建条 | `UserDictStore.kt:171-181` |
| 导入 / 导出 / 同步是否受影响 | 不受影响，格式未变 | `exportText`/`importText`/`SyncRepository` 均按 4 列处理 |
| 旧版本 App 能否读新版本写的文件 | 能，完全一致 | — |

**唯一的数据侧新增**：无。**这是本 ADR 相对「加标记位方案」的核心优势**——
AC-3.10 的「不得要求用户清库」从「需要验证」降级为「结构上不可能破坏」。

---

## 9. 迁移与回滚

### 9.1 功能开关关闭

- 现有开关 `settings.learning`（默认 `true`，`KeyboardSettings.kt:24`）即为总开关。
- **关闭后的行为**：tracker 照常推进状态（§1 失败分支），但 `learn()` 与词组学习都不写库。
  已学到的词组**保留在词库里继续参与候选**——这是刻意的：关开关是「不再学新的」，
  不是「忘掉旧的」。用户若想彻底清除，用设置页「清空用户词库」（AC-3.10）。
- **文案**：不改（`KeyboardScreen.kt:125-129` 现有副标题「记住你选择过的词，用得越多越靠前」已覆盖）。

### 9.2 坏数据清理

| 场景 | 表现 | 处理 |
| --- | --- | --- |
| 写入的码是错的（理论上一期规则下不可能，但要防） | 该词组打不出来或出现在错码下 | 设置页单条删除（`KeyboardViewModel.removeUserWord`，`KeyboardViewModel.kt:128`）；无需专门工具 |
| 写入的文本异常（含控制字符等） | `escapeLineText` 已转义 `\n\r\t\\`（`UserDictStore.kt:368`），不会破坏行结构 | 无需额外处理 |
| 词库文件被外部改坏 | `ensureLoaded` 的 `runCatching` 会丢弃坏行，不崩 | 既有行为 |
| 需要批量回滚已学词组 | 无专用入口 | **不做**：AC-3.10 只要求单条删除 + 整体清空，不做「撤销词组学习」独立入口（需求 §3.4 明确不做） |

### 9.3 单 PR revert 影响面

预计实现分 2 个 PR（可独立 revert）：

| PR | 内容 | revert 影响 |
| --- | --- | --- |
| P1 | `PhraseCodeDeriver` + `WubiEngine.fullCodeOf` + `PhraseLearningTracker` + 单测 | 纯新增，revert 后服务行为不变（无人调用） |
| P2 | `IspeechImeService` 6 处接线（含 `onUpdateSelection`）+ 单测 | revert 后回到「只有单字学习」的现状；**已写入 `user_dict.txt` 的词组不会消失**，会继续作为用户词参与候选（用户可手动删） |

**回滚不需要清库**，因为文件格式未变（§8）。这是选择「不加标记位」的直接收益。

---

## 10. 可观测性

### 决策：**只加结构化计数与固定文案日志，不加新文件、不加埋点 SDK**

| 观测点 | 形式 | 字段（只记必要字段） | 发现什么 |
| --- | --- | --- | --- |
| 学习成功 | `Log.i(TAG, "phrase_learn ok code=$code text=$text")` | `code`、`text` | 确认功能在工作 |
| 学习跳过 | `Log.d` + 内存计数 `skippedCount` | `reason` 枚举：`window`/`interrupt`/`existing`/`repeat`/`undecidable`/`disabled` | 区分「学不到」的原因；`undecidable` 持续增长说明推导规则与词库漂移 |
| 推导不可判定 | `Log.w(TAG, "phrase_learn undecidable first=$first second=$second")` | `first`、`second`（**不记完整词组**） | 需求文档 §9 A3 风险的早期信号 |
| 写库失败 | 由 `UserDictStore.persist` 既有 `Log.w` 覆盖 | 文件路径 | — |
| 时钟回退 | `Log.w(TAG, "phrase_learn clock_backwards")` | 无 | 系统时间异常 |

#### 约束

- **不记录用户输入的完整上下文**，只记 `code`/`text`（已是最终产物）；不记录光标位置、
  不记录宿主 App 包名——这些属于隐私面，且对定位问题无必要。
- 计数**只存内存**（进程内 `AtomicInteger`），不落盘。理由：需求 §3.6 的成功度量靠
  `user_dict.txt` 的 `count` 列就能算（词组二次使用率），不需要另建统计文件；
  误学率靠设置页删除动作，目前**没有删除计数**，这是已知缺口，见下。
- **已知缺口**：AC-3.6 的「误学率」指标（7 天内被删除的词组数 / 写入总数）当前无法采集，
  因为 `remove()` 不计数。**取舍**：不为此新增统计文件（会把一个可选指标变成持久化负担），
  在设置页删除时打一条 `Log.i("phrase_remove")` 即可，人工用 `adb logcat` 抽样；
  若上线后确认要量化，另立 issue 加计数（不阻塞本 ADR）。

---

## 11. AC 覆盖门禁表

| AC | 场景 | 由哪个设计保证 | 由哪层测试验证 | 状态 |
| --- | --- | --- | --- | --- |
| AC-3.1 | 正常两字成词，`klqt`/`喂猫`/`count=1` | §1 状态机 + §2 推导 + §5.3 `record()` | T-1 `derivesTwoCharWord`、T-2 表驱动 | 可覆盖 |
| AC-3.2 | 超时 5.1 s 不写库 | §6 W=5000ms | T-1 `expiresAfterWindow` | 可覆盖 |
| AC-3.3 | 中间有标点/空格/英文/emoji 不写 | §1 `commitDirect → onInterrupt` | T-1 `interruptByDirect` | 可覆盖 |
| AC-3.4 | 切面板 / 移光标不写 | §1 #4（面板）+ `onUpdateSelection`（光标） | T-1 `interruptByPanel`/`resetOnInputChange`；光标部分 **T-3 真机** | **部分可覆盖**：光标分支需真机验证，见 §1 风险标注 |
| AC-3.5 | 系统已有「加班」不写 | §2「已有不学」+ `systemHasWord` 精确匹配 | T-1 `skipsExistingSystemWord`；实测 `lkgy` 下确为 `加班` | 可覆盖 |
| AC-3.6 | 再次拼写 count=2 权重 140 | §5.3 `record()` 的 +1 语义 | T-1（间接）+ 既有 `weightOf` 公式；**排序第 1 由 §6 的 +100000 保证** | 可覆盖（JVM 验 count，真机验排序） |
| AC-3.7 | 新输入框打 `klqt` 出「喂猫」 | §5.3 写入 + `UserDictEngine` 查询 | adb 真机 | **仅真机** |
| AC-3.8 | 开关关闭不写 | §6 开关 | adb 真机 | **仅真机** |
| AC-3.9 | 「你」「你」不写 | §6 同字规则 | T-1 `skipsSameChar`；实测 `wqwq` 下无「你你」 | 可覆盖 |
| AC-3.10 | 设置页可见/可删/可清空 | §8 格式不变，复用既有 UI | adb 真机 | **仅真机** |
| AC-3.11 | 连续 10 次无 ANR | §1 判在主线程（微秒级）、写库走 `Dispatchers.IO`（复用 `learn()` 的 `serviceScope.launch(Dispatchers.IO)`，`IspeechImeService.kt:1024-1027`） | adb 真机 + logcat 无 ANR | **仅真机** |
| AC-3.12 | 同码下新词第 1、原候选保留 | §6 `+100_000` + `CandidateRanking` 精确码优先 | adb 真机 | **仅真机** |

**无法由本 ADR 单层覆盖的项**：AC-3.4 的光标分支（需真机验证回调时序）、AC-3.7/3.8/3.10/3.11/3.12
（需真实候选条与设置页）。这 6 项**不构成需求回写**——它们本来就是 adb 层验证项（需求 §3.5 已如此标注）；
唯一需要回写的是 AC-3.4：需求原文把它标为 JVM，实际含一个无法纯 JVM 覆盖的真机依赖。

---

## 12. 失败模式清单

| # | 失败模式 | 影响 | 检测 | 处置 |
| --- | --- | --- | --- | --- |
| F1 | `onUpdateSelection` 把本服务自身的光标变化误判为中断 | 词组永远学不到（每次上屏都被自己中断） | 上线前 T-3 真机验证；`phrase_learn skipped{reason=interrupt}` 计数异常高 | 降级为「只比 `newSelStart`」，并由 UX 决定是否接受少量误判 |
| F2 | 推导规则与词库口径漂移（词库更新后） | 学到的码是错的，词打不出来 | T-2 的集合精确断言会在下次词库更新时立即失败 | 更新词库时同步更新 T-2 的 27 条基线 |
| F3 | `W` 设置不当（太短学不到 / 太长误学） | 功能可用性 | 上线 7 天误学率 > 10% | 调 §6 常量，不改状态机 |
| F4 | 用户习惯性连打两个不相关单字（如打字时停顿思考） | 误学 | 需用户主动删（AC-3.10） | 三重保护已尽力；接受残余，靠误学率指标监控 |
| F5 | 写库阻塞主线程 | ANR | AC-3.11 | 写库严格走 IO 线程；tracker 不接触文件 |
| F6 | 繁简转换导致存的与显示的不一致 | 学到的词与上屏字不符 | — | **现有行为**：`commit()` 里 `convertHan()` 在 `record` 之前，`learn()` 记录的是 `candidate.insertText`（简体），与词库一致。词组学习同样记录简体，**不记录转换后文本**——这是刻意的，否则繁简开关切换后词库会出现两套 |

---

## 13. 决策记录（供 ADR 追溯）

| 编号 | 决策 | 日期 | 决策人 | 备选 | 复审条件 |
| --- | --- | --- | --- | --- | --- |
| D1 | 判定放独立 tracker（纯函数） | 2026-11-10 | 架构师 | 服务内判定 / 放进 Store | AC-3.1~3.6/3.9 的 JVM 要求变化 |
| D2 | 编码推导用「最长码优先」 | 2026-11-10 | 架构师 | 最高权重优先（99.82%） | 词库换版 |
| D3 | 例外处理 = G1/G2 两闸 + 已有不学，不做白名单，不加 G3 | 2026-11-10 | 架构师 | 白名单 / 查表优先 / 写入校验 | T-2 基线变化 |
| D4 | 拼音二期，前置 4 条 | 2026-11-10 | 架构师 | 一五笔+拼音同做 | 二期立项 |
| D5 | 词库不加标记位，隐式长度判断收敛到一处 | 2026-11-10 | 架构师 | 加第 5 列 `kind` | 出现「2 字词要排单字档」诉求 |
| D6 | 词组学习复用 `settings.learning`，不加新开关 | 2026-11-10 | 架构师 | 独立开关 | 用户反馈要分开控制 |
| D7 | W = 5 s（待 UX 复核） | 2026-11-10 | 架构师（代 UX） | 3 s / 8 s | UX 真机节奏复核 |
| D8 | 可观测性只加日志与内存计数，不落盘 | 2026-11-10 | 架构师 | 新增统计文件 | 需要量化误学率 |

---

## 14. 已知限制（不在本 ADR 解决）

1. **AC-3.4 光标分支依赖 `onUpdateSelection` 时序**，各 ROM 不一致，需真机验证（T-3）。
2. **误学率指标不可采集**（§10），只做日志抽样。
3. **3 字及以上词组与拼音**完全不在范围内（§3）。
4. **`UserDictStore.kt:95-98` 会剔除 code 里的非字母数字**——本 ADR 依赖这条既有行为
   保证拼音串无空格，但一期不写拼音，属未来约束。
5. **`IspeechImeService` 无 `onUpdateSelection` 覆写**是现状事实，新增覆写会扩大该文件，
   与本 ADR「服务侧只改 6 处」的原则相符，但仍是 `IspeechImeService` 增长的一部分。
6. **推导规则依赖词库的取码约定**。若上游 `wubi86.dict` 换版且取码规则变化，
   T-2 的集合精确断言会立即失败——这是**刻意的**（宁可测试红，不要让用户学到错码）。
7. **`systemHasWord` 也必须建索引，不能遍历全表**。`byCode` 是「码 → 条目」，
   按 `text` 查需要另一张「词 → 是否存在」的表；与 §5.2 的 `byText`（单字 → 全码）
   在同一次构造遍历里一起建，不要新增第三次遍历。
8. **`CellDictEngine.kt:100` 与 `WubiEngine.kt:53`、`PinyinDictLoader.kt:28` 同样用 UTF-16 长度
   判单字/词组**。本 ADR 只要求把 `entryKindOf` 这一条链路改成码点；
   另外两处（细胞词库的 `isWord`、拼音词表的 `isWord`）的同类缺陷**不在本 ADR 修复范围**，
   建议单独开 issue 统一，避免为一个词组的自学习功能扩大改动面。
9. **`entryKindOf` 的修正会让 42,870 个非 BMP 单字的候选档位发生变化**。理论上这是修正，
   但候选条上会看到顺序变化；若测试同学发现「某些生僻字候选位置变了」，那是预期行为，
   不是回归。

---

## 15. 变更影响分析（本轮已执行）

| 变更点 | 影响面（grep 实证） | 需连带修改 |
| --- | --- | --- |
| 新增 `PhraseCodeDeriver` / `PhraseLearningTracker` | 纯新增，0 调用点 | 无 |
| `WubiEngine` 新增 `fullCodeOf` | 现有调用点：`QuickLocator.kt:105`、`DictionaryViewModel.kt:67`、`AboutViewModel.kt:40`（均为构造，不受新增方法影响） | 无 |
| `IspeechImeService` 6 处接线 | `pickCandidate:1164`、`commit:921`（新增 `source` 默认参数）、`commitDirect:948`、`openPanel:1212`/`closePanel:1268`、`toggleMode:752`/`onStartInput:399`、新增 `onUpdateSelection` | 无既有调用点必须改：`source` 默认值让 `:596`/`:905`/`:913`/`:917` 全部保持可用（默认 `WORD_CANDIDATE` 与 `DIRECT` 在本状态机里结果相同）。仅 `pickCandidate`/`commitTop` 需显式传 `sourceOf(...)`，那是新增调用而非改动 |
| 新增 `onUpdateSelection` | 全仓 0 处覆写 | 无 |
| 复用 `UserDictStore.record` | 现有调用点：`IspeechImeService.kt:1027`、`:1045` | 无 |
| 不改 `LookupKind` | 若改会波及 `QuickPhrasesScreen:787` 的穷尽 `when`、`IspeechImeService:1051` | **本 ADR 明确不改** |
| 不改 `CommitType` | `InputStats` 的 `MARK_COMMIT` 解析依赖枚举名 | **本 ADR 明确不改**（词组上屏仍算 `CANDIDATE`） |
| **改** `UserDictStore.toCandidate` 的单字/词组判定口径 | 判定点共 3 处：`UserDictStore.kt:362`、`CellDictEngine.kt:100`、`IspeechImeService.kt:1051`（前者是判定源，后两者各自复制） | 抽 `entryKindOf(text)` + `codePointLength(text)`，三处共用。**行为变化**：42,870 个非 BMP 单字从「词组档」回到「单字档」（即修正）。回滚 = revert 该 PR，无需数据迁移 |
| 新增 `codePointLength` 工具函数 | 放在 `data/UserDictStore.kt` 同级（`internal`），与 `escapeLineText` 等既有 internal 工具同文件 | 无既有调用点 |

### 15.1 命名与风格校验（按仓库既有惯例，非新建规约）

仓库内所有 `enum class` 的取值都是 SCREAMING_SNAKE_CASE（`KeyAction`、`Panel`、`Mode`、
`UserDictMode`、`CommitType`、`LookupKind` 全部如此，逐个核对过），因此：

- `CommitSource` 的取值必须是 `SINGLE_CHAR` / `WORD_CANDIDATE` / `DIRECT` / `PANEL` / `ASSOCIATION`；
- 类名用 UpperCamelCase（`PhraseLearningTracker`、`PhraseCodeDeriver`），与 `UserDictStore`、
  `CompositeEngine`、`PinyinEngine` 一致；
- 只在模块内可见的类型用 `internal`（仓库先例：`QueryPhraseCodec`、`RemoteRuleMap`、
  `parseUserDictLine`、`escapeLineText`）；
- 常量集中为 `private companion object` 里的 `const val`（先例：`UserDictStore` 的
  `BASE_WEIGHT`/`MAX_WEIGHT`、`WubiEngine` 的 `WEIGHT_RANK`），不散落魔法值——
  因此 `5_000` 这个时间窗必须命名为 `LEARN_WINDOW_MILLIS` 而不是字面量。

---

## 16. 下一步

| 项 | owner | 说明 |
| --- | --- | --- |
| 实现 P1（推导 + tracker + 单测） | 前端 | 按 §5 契约；T-1/T-2 必须先红后绿 |
| 实现 P2（服务接线 + `onUpdateSelection`） | 前端 | 必须先真机验证 T-3 |
| W 取值复核 | UX | §6，测 P90 间隔 |
| 二期立项评估 | 架构师 + 洞察BA | §3 四条前置条件 |
| 误学率采集（是否单独立项） | 架构师 | §10 已知缺口 |
