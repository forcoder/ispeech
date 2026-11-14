package com.ispeech.data

import com.ispeech.engine.QuickEntry

/** 命中字段：面板高亮 / 排序解释用。 */
enum class PhraseHitField(val label: String) {
    TRIGGER("触发码"),
    LABEL("名称"),
    CONTENT("正文"),
    CATEGORY("分类"),
    INITIALS("拼音首字母"),
}

/**
 * 匹配档位：数值即基础分。
 *
 * 档位之间刻意留出 200 分以上的间距，而「字段加成 + 使用次数加成 + 收藏加成」上限只有
 * `100 + 20*2 + 30 = 170`，因此 **完全匹配 > 前缀 > 包含 > 首字母** 在任何数据下都不会被
 * 附加分翻转（tools/verify/check_phrase_usage.py 里对该不变量有断言）。
 */
enum class PhraseMatchTier(val score: Int) {
    EXACT(900),
    PREFIX(600),
    CONTAINS(300),
    INITIALS(100),
}

/**
 * 搜索结果：评分 + 命中字段 + 命中区间（供 UI 高亮）。
 */
data class PhraseMatch(
    /** 短语稳定标识，与 [PhraseUsageStore.idOf] 一致，用于收藏 / 计数 / 去重。 */
    val id: String,
    val entry: QuickEntry,
    val tier: PhraseMatchTier,
    val field: PhraseHitField,
    val score: Int,
    val starred: Boolean,
    val count: Int,
    /**
     * [field] 对应文本内的命中区间（Kotlin 闭区间，单位是 Char 下标），
     * 面板可直接用于 `SpannableString.setSpan(...)` 高亮。
     */
    val spans: List<IntRange>,
)

/**
 * 快捷短语搜索：跨「触发码 + 名称 + 正文 + 分类」匹配，返回带评分排序的结果。
 *
 * 匹配档位（强 -> 弱）：
 * 1. 完全匹配：任一字段整串等于关键词；
 * 2. 前缀匹配：任一字段以关键词开头；
 * 3. 包含匹配：任一字段包含关键词；
 * 4. 拼音首字母：ASCII 关键词（长度 >= 2）与中文的**首字母缩写**对齐命中，见 [PhrasePinyin]。
 *
 * 同档位内的加分：触发码命中 +100 > 名称 +60 > 正文 +20 > 分类 0；使用次数每 1 次 +2
 * （最多 20 次计入，即 +40）；已收藏 +30。排序权重完全确定（分数 -> 次数 -> weight -> label -> trigger -> id），
 * 因此同一批数据每次运行结果一致，便于测试与缓存。
 *
 * 纯逻辑 + 无 Android / Context 依赖，构造参数只接收两个查询函数，方便单测与面板复用。
 */
class PhraseSearch(
    /** id -> 使用次数（可以传 [PhraseUsageStore.countOf]）。 */
    private val usageCount: (String) -> Int = { 0 },
    /** id -> 是否收藏（可以传 [PhraseUsageStore.isStarred]）。 */
    private val starredFlag: (String) -> Boolean = { false },
) {

    /**
     * 搜索并排序。
     *
     * - [keyword] 为空：不做关键词过滤（「全部」列表），仍按使用次数 / 收藏 / weight 排序；
     * - [category] 非空：只保留来源分组（内置 / 远端 / 用户）或类型（短语 / 应用）匹配的条目，
     *   额外接受 `全部` / `all` 作为「不过滤」；
     * - [starredOnly]：只保留已收藏（面板「★ 收藏」胶囊）；
     * - [allowedIds]：白名单过滤（面板「最近」胶囊传 [PhraseUsageStore.recentIds] 的结果）。
     */
    fun search(
        entries: List<QuickEntry>,
        keyword: String = "",
        category: String? = null,
        limit: Int = DEFAULT_LIMIT,
        starredOnly: Boolean = false,
        allowedIds: Set<String>? = null,
    ): List<PhraseMatch> {
        if (limit <= 0) return emptyList()
        val needle = keyword.trim().lowercase()
        val filter = category?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "全部" && it != "all" }

        // 同一 id（同一触发码 + 同一正文）只保留分数最高的一条：去重规则与 PhraseUsageStore 完全一致。
        val dedup = LinkedHashMap<String, PhraseMatch>()
        for (entry in entries) {
            if (filter != null && !matchesCategory(entry, filter)) continue
            val id = PhraseUsageStore.idOf(entry)
            if (allowedIds != null && id !in allowedIds) continue
            val starred = starredFlag(id)
            if (starredOnly && !starred) continue
            val count = usageCount(id).coerceAtLeast(0)

            val hit = if (needle.isEmpty()) emptyHit() else (matchEntry(entry, needle) ?: continue)
            val score = hit.tier.score +
                hit.fieldBonus +
                usageBonus(count) +
                (if (starred) SCORE_STARRED else 0)
            val match = PhraseMatch(
                id = id,
                entry = entry,
                tier = hit.tier,
                field = hit.field,
                score = score,
                starred = starred,
                count = count,
                spans = hit.spans,
            )
            val previous = dedup[id]
            if (previous == null || match.score > previous.score) dedup[id] = match
        }
        if (dedup.isEmpty()) return emptyList()
        val list = ArrayList(dedup.values)
        list.sortWith(RANK)
        return list.take(limit)
    }

    /** 只要条目（面板只关心上屏内容时的便捷入口）。 */
    fun searchEntries(
        entries: List<QuickEntry>,
        keyword: String = "",
        category: String? = null,
        limit: Int = DEFAULT_LIMIT,
        starredOnly: Boolean = false,
        allowedIds: Set<String>? = null,
    ): List<QuickEntry> = search(entries, keyword, category, limit, starredOnly, allowedIds).map { it.entry }

    /** 无关键词时的「命中」：不参与字段判定，只保留使用次数 / 收藏排序。 */
    private fun emptyHit(): Hit = Hit(PhraseMatchTier.CONTAINS, PhraseHitField.CONTENT, 0, emptyList())

    private fun matchEntry(entry: QuickEntry, needle: String): Hit? {
        val fields = listOf(
            Field(PhraseHitField.TRIGGER, entry.trigger, SCORE_FIELD_TRIGGER),
            Field(PhraseHitField.LABEL, entry.label, SCORE_FIELD_LABEL),
            Field(PhraseHitField.CONTENT, entry.content, SCORE_FIELD_CONTENT),
            // 分类也参与关键词匹配：输入「内置 / 远端 / 用户 / 短语 / 应用」可直接把该组顶到前面。
            Field(PhraseHitField.CATEGORY, entry.source, 0),
            Field(PhraseHitField.CATEGORY, entry.kind.label, 0),
        )
        // 1) 完全匹配：整串等于关键词。
        for (field in fields) {
            if (field.text.isNotEmpty() && field.text.equals(needle, ignoreCase = true)) {
                return Hit(PhraseMatchTier.EXACT, field.field, field.bonus, listOf(0 until field.text.length))
            }
        }
        // 2) 前缀匹配。
        for (field in fields) {
            if (field.text.isNotEmpty() && field.text.regionMatches(0, needle, 0, needle.length, ignoreCase = true)) {
                return Hit(PhraseMatchTier.PREFIX, field.field, field.bonus, spansOf(field.text, needle))
            }
        }
        // 3) 包含匹配。中文关键词在「正文」位置走独立的字面包含分支（见 [matchContentLiteral]），
        //    其它字段与 ASCII 关键词保持原有顺序不变。
        val asciiKeyword = isAsciiKeyword(needle)
        for (field in fields) {
            if (!asciiKeyword && field.field == PhraseHitField.CONTENT) {
                matchContentLiteral(entry, needle)?.let { return it }
                continue
            }
            val spans = spansOf(field.text, needle)
            if (spans.isNotEmpty()) return Hit(PhraseMatchTier.CONTAINS, field.field, field.bonus, spans)
        }
        // 4) 拼音首字母缩写（仅 ASCII 关键词，长度 >= 2）。
        if (asciiKeyword) {
            for (field in fields) {
                val spans = PhrasePinyin.matchSpans(field.text, needle)
                if (spans.isNotEmpty()) return Hit(PhraseMatchTier.INITIALS, field.field, 0, spans)
            }
        }
        return null
    }

    /**
     * 中文关键词的「正文包含」分支：关键词含非 ASCII 字符时按短语正文做大小写不敏感的字面包含。
     *
     * ASCII 关键词不进入本方法，继续保留完全 / 前缀 / 包含 / 拼音首字母的原有逻辑；
     * 命中后使用 [PhraseMatchTier.CONTAINS] 档位与正文加成，使用次数 / 收藏加权的排序规则不变。
     */
    private fun matchContentLiteral(entry: QuickEntry, needle: String): Hit? {
        if (needle.all { it.code < 0x80 }) return null
        val spans = spansOf(entry.content, needle)
        if (spans.isEmpty()) return null
        return Hit(PhraseMatchTier.CONTAINS, PhraseHitField.CONTENT, SCORE_FIELD_CONTENT, spans)
    }

    /** 分类过滤：来源分组（内置 / 远端 / 用户）与类型（短语 / 应用）都算「分类」。 */
    private fun matchesCategory(entry: QuickEntry, filter: String): Boolean =
        entry.source.lowercase() == filter || entry.kind.label.lowercase() == filter

    private fun usageBonus(count: Int): Int =
        count.coerceAtMost(SCORE_USAGE_MAX_USES) * SCORE_USAGE_PER_USE

    private fun isAsciiKeyword(keyword: String): Boolean {
        if (keyword.length < MIN_INITIALS_QUERY) return false
        for (ch in keyword) {
            if (ch !in 'a'..'z' && ch !in '0'..'9') return false
        }
        return true
    }

    /** 内部：一次匹配的档位 / 字段 / 加成 / 高亮区间。 */
    private class Hit(
        val tier: PhraseMatchTier,
        val field: PhraseHitField,
        val fieldBonus: Int,
        val spans: List<IntRange>,
    )

    private class Field(val field: PhraseHitField, val text: String, val bonus: Int)

    companion object {
        /** 默认返回上限：与 QuickPhrasePanel 的 maxRows 一致。 */
        const val DEFAULT_LIMIT = 200

        /** 字段加成：触发码 > 名称 > 正文 > 分类。 */
        const val SCORE_FIELD_TRIGGER = 100
        const val SCORE_FIELD_LABEL = 60
        const val SCORE_FIELD_CONTENT = 20

        /** 使用次数加成：每 1 次 +2，最多计 20 次（+40）。 */
        const val SCORE_USAGE_PER_USE = 2
        const val SCORE_USAGE_MAX_USES = 20

        /** 收藏加成。 */
        const val SCORE_STARRED = 30

        /** 拼音首字母匹配要求关键词至少 2 个字符，避免单字母误命中。 */
        const val MIN_INITIALS_QUERY = 2

        /** 单条结果最多保留多少个高亮区间（避免长文本里到处都是区间）。 */
        const val MAX_SPANS = 8

        /** 无使用数据的「纯搜索」实例（不依赖 Context / 文件）。 */
        val PURE = PhraseSearch()

        /** 带使用数据（收藏 / 次数）的搜索实例。 */
        fun of(store: PhraseUsageStore): PhraseSearch =
            PhraseSearch(usageCount = store::countOf, starredFlag = store::isStarred)

        /**
         * 取 [text] 中所有命中 [keyword] 的区间（大小写不敏感、按 index 推进，
         * 用 `regionMatches` 而不是 `lowercase()`，避免个别字符大小写转换后长度变化导致下标错位）。
         */
        fun spansOf(text: String, keyword: String): List<IntRange> {
            if (text.isEmpty() || keyword.isEmpty() || keyword.length > text.length) return emptyList()
            val out = ArrayList<IntRange>(4)
            var index = 0
            while (index + keyword.length <= text.length) {
                if (text.regionMatches(index, keyword, 0, keyword.length, ignoreCase = true)) {
                    out.add(index until index + keyword.length)
                    if (out.size >= MAX_SPANS) break
                    index += keyword.length
                } else {
                    index++
                }
            }
            return out
        }
    }
}

/** 搜索结果排序：分数倒序 -> 使用次数倒序 -> weight 倒序 -> label / trigger / id 升序。 */
private val RANK: Comparator<PhraseMatch> = Comparator { a, b ->
    val byScore = b.score.compareTo(a.score)
    when {
        byScore != 0 -> byScore
        b.count != a.count -> b.count.compareTo(a.count)
        b.entry.weight != a.entry.weight -> b.entry.weight.compareTo(a.entry.weight)
        a.entry.label != b.entry.label -> a.entry.label.compareTo(b.entry.label)
        a.entry.trigger != b.entry.trigger -> a.entry.trigger.compareTo(b.entry.trigger)
        else -> a.id.compareTo(b.id)
    }
}

/**
 * 极简拼音首字母表：把汉字映射到声母首字母，用于「输入 `fp` 命中『发票』」这类缩写搜索。
 *
 * 取舍说明（为什么是内嵌小表而不是完整拼音库）：
 * - 项目约定「无第三方依赖」，完整拼音表（几万行）会明显增大 APK，收益却集中在高频词；
 * - 因此这里只收录高频字（约 800 个），**未收录的汉字按通配处理**：关键词的对应位可以匹配任意字，
 *   即「命中即高亮」的降级策略——不会漏报，代价是可能多报；
 * - 首字母命中始终排在字面「包含匹配」之后（[PhraseMatchTier.INITIALS] = 100 < 300），
 *   且要求关键词至少 2 个字符、对齐窗口内至少有一个汉字，避免把邮箱 / 网址这类 ASCII 文本刷成命中。
 *
 * 扩展方式：在 [GROUPS] 里往对应声母分组追加汉字即可（格式 `声母:汉字...`，用 `|` 分隔分组），
 * 不要收录多音字以外的危险字（例如「长 / 重 / 行」这类多音字只在最常用读音里出现）。
 */
object PhrasePinyin {

    /** 声母分组表：`声母:汉字...`，用 `|` 分隔。 */
    private const val GROUPS =
        "a:啊爱安哎案按暗|" +
            "b:帮不备本表部报比边便标别病步补白百宝笔版办半并必包保被背堡班|" +
            "c:此餐产场车成出传存错从才参测次草层长彩持称程初除处窗纯促催查材充冲城池|" +
            "d:的得到大单地电订都多对打带当档道等第点调定东动度短段兑答代递店掉丢顶冬豆读独断堆队|" +
            "e:而二儿额恩|" +
            "f:发费付分服方法返房非复负份丰放否封繁反防访飞废风福附副|" +
            "g:感个根更工公功共购古关观管广规贵果过该高告格各给跟够干改盖敢挂官|" +
            "h:好号和很后户华话化还换回会活货合核候花欢黄灰坏或海含函河盒黑红厚湖互画怀环惠婚火恒缓|" +
            "j:家见件将交接解结姐金进近经就据决均机积极计记技及级价简建讲教较介今紧尽几既己间检减角脚叫街借仅静久酒旧局举具卷觉军敬界假季节|" +
            "k:可客开看考靠科刻空口苦快款况库卡肯坑哭|" +
            "l:里理力立例连联了领令流六楼路录律率论落来老类冷离礼丽两量列林另留旅良料李蓝劳乐雷累历浪疗|" +
            "m:吗买卖没每门们密面名明码满忙毛美免秒民模某目么母幕木慕米末默|" +
            "n:那内能你年女男南难呢您宁牛拿哪脑嫩尼念娘鸟弄农努暖|" +
            "o:哦偶|" +
            "p:怕排派盘旁品平评凭破普票篇批拼频朋片牌配皮盆泡疲|" +
            "q:其起前请区取全却确情期七齐器千签强清轻求去权且亲驱群穷球秋缺启弃晴气|" +
            "r:让人认任日如入软容热仍忍肉润若|" +
            "s:三上少什生时使是事收手输术数说送算所四思司私速随岁索商社身深神声省实始世市式试视适首书熟属双谁水顺丝似素色山设审升失师十识史示受述税睡湿|" +
            "t:他她它太谈提题天条通同统头图推退特台态填贴听停挺投团庭途土铁探讨逃替体|" +
            "w:我为未位文问无五物务网往望危微围维委卫温闻稳午武外完晚万王尾污吴舞乌|" +
            "x:下先现相想小些新信行性需许学讯息系细显限线详消效协写谢心星型修序选询迅希喜洗西箱像鞋校笑续宣悬雪习席项须|" +
            "y:一以已意因应用有于与语元员原远院月云允运压呀研严言眼样要也业页夜依仪移遗疑义议易益银引印英营影硬永优由邮犹游友又右余鱼愉育遇预域欲越阅盐阳养药爷野衣医椅艺饮应迎拥勇油雨玉园圆愿约泳浴瑶疫验|" +
            "z:在再早责怎增展站张找这着真正证之支知直值只指至志制质中终重周主住注祝转装状准资子字自总走租组最作座做址州账枝织职智昼致则暂"

    /** 未收录汉字：单词匹配时通配任意一个关键词字符。 */
    private const val WILDCARD = '\u0000'

    /** Unicode BMP 汉字区间（扩展 A + 基本区 + 兼容表意文字）。 */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3400..0x9FFF || code in 0xF900..0xFAFF
    }

    /** 汉字 -> 声母首字母；ASCII 字母 / 数字原样（小写）；其它字符返回 null（不参与对齐）。 */
    private val initialByChar: Map<Char, Char> = parseGroups(GROUPS)

    private fun parseGroups(groups: String): Map<Char, Char> {
        val map = HashMap<Char, Char>()
        for (group in groups.split('|')) {
            if (group.length < 2 || group[1] != ':') continue
            val initial = group[0]
            var i = 2
            while (i < group.length) {
                map[group[i]] = initial
                i++
            }
        }
        return map
    }

    /** 调试 / 测试用：把文本转成首字母串（未收录汉字显示为 `?`）。 */
    fun initials(text: String): String {
        val sb = StringBuilder(text.length)
        for (key in keysOf(text)) {
            sb.append(if (key.initial == WILDCARD) '?' else key.initial)
        }
        return sb.toString()
    }

    /** 表内收录的汉字数量（文档与测试用）。 */
    fun tableSize(): Int = initialByChar.size

    /** [text] 是否能用首字母缩写 [keyword]（ASCII、小写）命中。 */
    fun matches(text: String, keyword: String): Boolean = matchSpans(text, keyword).isNotEmpty()

    /**
     * 首字母缩写命中的区间（对应 [text] 的 Char 下标，供 UI 高亮）。
     *
     * 对齐规则：把 [text] 逐字换成「首字母 / 通配」，然后整体对齐 [keyword]；
     * 已收录汉字必须首字母相等，未收录汉字可以顶任意字符；要求窗口内至少有一个汉字。
     */
    fun matchSpans(text: String, keyword: String): List<IntRange> {
        val needle = keyword.lowercase()
        if (text.isEmpty() || needle.length < PhraseSearch.MIN_INITIALS_QUERY) return emptyList()
        for (ch in needle) {
            if (ch !in 'a'..'z' && ch !in '0'..'9') return emptyList()
        }
        val keys = keysOf(text)
        if (keys.size < needle.length) return emptyList()

        val out = ArrayList<IntRange>(2)
        var start = 0
        while (start + needle.length <= keys.size) {
            if (alignAt(keys, needle, start)) {
                if (windowHasCjk(keys, start, needle.length)) {
                    out.add(keys[start].index..keys[start + needle.length - 1].index)
                    if (out.size >= PhraseSearch.MAX_SPANS) break
                }
                start += needle.length
            } else {
                start++
            }
        }
        return out
    }

    private fun alignAt(keys: List<At>, needle: String, start: Int): Boolean {
        for (offset in needle.indices) {
            val key = keys[start + offset]
            if (key.initial != WILDCARD && key.initial != needle[offset]) return false
        }
        return true
    }

    private fun windowHasCjk(keys: List<At>, start: Int, length: Int): Boolean {
        for (i in start until start + length) {
            if (keys[i].cjk) return true
        }
        return false
    }

    /** 文本逐字展开成对齐序列：跳过标点 / 空白，保留下标。 */
    private fun keysOf(text: String): List<At> {
        val out = ArrayList<At>(text.length)
        for ((index, ch) in text.withIndex()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> out.add(At(index, ch, false))
                ch in 'A'..'Z' -> out.add(At(index, ch.lowercaseChar(), false))
                initialByChar[ch] != null -> out.add(At(index, initialByChar.getValue(ch), true))
                isCjk(ch) -> out.add(At(index, WILDCARD, true))
            }
        }
        return out
    }

    /** 对齐序列中的一项：原文下标 + 首字母（可能为通配）+ 是否汉字。 */
    private class At(val index: Int, val initial: Char, val cjk: Boolean)
}
