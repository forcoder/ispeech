package com.ispeech.engine

import com.ispeech.data.codePointLength
import com.ispeech.data.entryKindOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 编码推导与单字/词组档判定（ADR-0001 §7.2 T-2 / T-2b）：
 * 用**真实** `assets/wubi86.dict` 做表驱动，而不是桩词库——桩只能证明规则自洽，
 * 证明不了规则的取码口径与词库一致（ADR §14 第 6 条）。
 */
class PhraseCodeDeriverTest {

    private val dict: WubiEngine by lazy {
        val file = File("src/main/assets/wubi86.dict")
        assertTrue("找不到真实词库：${file.absolutePath}", file.exists())
        WubiEngine(file.readText(Charsets.UTF_8))
    }

    private val codeOf: (String) -> String? = { dict.fullCodeOf(it) }

    private val twoCharPhrases: List<Pair<String, String>> by lazy {
        val file = File("src/main/assets/wubi86.dict")
        file.readLines(Charsets.UTF_8)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 4 && it[3] == "p" && codePointLength(it[1]) == 2 }
            .map { it[0] to it[1] }
            .toList()
    }

    @Test
    fun `与你 产 喂 猫 的取码口径符合最长码优先`() {
        assertEquals("wqiy", dict.fullCodeOf("你"))
        assertEquals("ute", dict.fullCodeOf("产"))
        assertEquals("klge", dict.fullCodeOf("喂"))
        assertEquals("qtal", dict.fullCodeOf("猫"))
        assertEquals("wq", PhraseCodeDeriver.prefix("wqiy"))
        assertEquals("ut", PhraseCodeDeriver.prefix("ute"))
    }

    @Test
    fun `AC-3_1 喂猫 推导出 klqt`() {
        assertEquals("klqt", PhraseCodeDeriver.derive("喂", "猫", codeOf))
    }

    @Test
    fun `AC-3_5 加班 推导出 lkgy 且系统词库确有该词`() {
        assertEquals("lkgy", PhraseCodeDeriver.derive("加", "班", codeOf))
        assertTrue("系统词库应已有加班", dict.hasWord("加班"))
    }

    @Test
    fun `全量双字词推导一致率不低于 99_95 百分比`() {
        assertTrue("双字词条数异常：${twoCharPhrases.size}", twoCharPhrases.size > 60_000)
        var consistent = 0
        val inconsistent = LinkedHashSet<String>()
        for ((code, text) in twoCharPhrases) {
            val first = text.substring(0, 1)
            val second = text.substring(1, 2)
            val derived = PhraseCodeDeriver.derive(first, second, codeOf) ?: continue
            if (derived == code) consistent++ else inconsistent.add(text)
        }
        val total = twoCharPhrases.size
        val ratio = consistent.toDouble() / total
        assertTrue("一致率 $ratio 低于 0.9995", ratio >= 0.9995)
        assertEquals("不一致集合必须恰为 ADR §0 的 27 条", EXPECTED_EXCEPTIONS, inconsistent)
    }

    @Test
    fun `27 条例外全部落在 系统已有不学 分支上`() {
        val undecided = ArrayList<String>()
        for (word in EXPECTED_EXCEPTIONS) {
            if (!dict.hasWord(word)) undecided.add(word)
        }
        assertEquals("这 27 条若不在系统词库，学习时会写成错码", emptyList<String>(), undecided)
    }

    @Test
    fun `T恤 U盘 不可推导且不在双字推导范围内`() {
        assertEquals("首字不在单字表", null, dict.fullCodeOf("T"))
        assertEquals("首字不在单字表", null, dict.fullCodeOf("U"))
        assertNull(PhraseCodeDeriver.derive("T", "恤", codeOf))
        assertNull(PhraseCodeDeriver.derive("U", "盘", codeOf))
    }

    @Test
    fun `非汉字与多码点输入都被拒绝`() {
        assertNull(PhraseCodeDeriver.derive("a", "喂", codeOf))
        assertNull("两字必须各是一个码点", PhraseCodeDeriver.derive("喂猫", "你", codeOf))
        assertTrue(PhraseCodeDeriver.isSingleCjk("喂"))
        assertTrue(!PhraseCodeDeriver.isSingleCjk("A"))
        assertTrue(!PhraseCodeDeriver.isSingleCjk("喂猫"))
    }

    @Test
    fun `全码不足 2 位或含非小写字母时放弃`() {
        assertNull(PhraseCodeDeriver.prefix("u"))
        assertNull(PhraseCodeDeriver.prefix(""))
        assertNull(PhraseCodeDeriver.prefix("k1ge"))
        assertEquals("kl", PhraseCodeDeriver.prefix("klge"))
    }

    @Test
    fun `非 BMP 单字可参与推导（不被代理对拆断）`() {
        assertEquals(1, codePointLength("𧝣"))
        assertEquals("aaae", dict.fullCodeOf("𧝣"))
        assertEquals("aaal", dict.fullCodeOf("𦾟"))
        // 两个字都在词库里，推导结果应是两者前 2 位的拼接（aaae + aaal → aa + aa）
        assertNotNull(PhraseCodeDeriver.derive("𧝣", "𦾟", codeOf))
        assertEquals("aaaa", PhraseCodeDeriver.derive("𧝣", "𦾟", codeOf))
        assertEquals("aawq", PhraseCodeDeriver.derive("𧝣", "你", codeOf))
    }

    @Test
    fun `单字与词组档按码点判定`() {
        assertEquals(LookupKind.WUBI, entryKindOf("喂"))
        assertEquals("非 BMP 单字必须是单字档（既有实现会误判成词组档）", LookupKind.WUBI, entryKindOf("𧝣"))
        assertEquals(LookupKind.PHRASE, entryKindOf("喂猫"))
        assertEquals(LookupKind.PHRASE, entryKindOf("𧝣𦾟"))
        assertEquals(LookupKind.WUBI, entryKindOf("a"))
        assertEquals(LookupKind.PHRASE, entryKindOf("abc"))
    }

    private companion object {
        /** ADR-0001 §0 实测的 27 条不一致：24 条同词多码 + 3 条词库真简码。 */
        val EXPECTED_EXCEPTIONS = setOf(
            "蹒跚", "蹂躏", "踊跃", "踌躇", "蹊跷", "践踏", "跳跃", "踯躅", "蹉跎", "跟踪",
            "踉跄", "围堵", "鱿鱼", "鱼鳞", "猖獗", "猖狂", "猩猩", "狩猎", "狐狸", "鲜鱼",
            "狡猾", "猕猴", "鲸鱼", "狼狈",
            "猪猡", "猿猴", "狰狞",
        )
    }
}