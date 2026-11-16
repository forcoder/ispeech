package com.ispeech.engine

import com.ispeech.data.UserDictMode
import com.ispeech.data.UserDictStore
import com.ispeech.data.codePointLength
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 学到的词组在候选里的位置（AC-3.6 / AC-3.7 组内口径，JVM 段）：
 * 权重公式本身已由 [PhraseDictCompatibilityTest] 覆盖，这里验的是「写进词库之后，
 * 复合引擎查出来排第几」——公式对但排序挂掉，用户在候选条上一样看不到。
 *
 * 用真实 `assets/wubi86.dict` 的同码系统词（`klqt` 下「中国银行」62857 /「喂猪」54507）
 * 与真实 `UserDictStore` 落盘文件，不用桩候选。
 */
class PhraseRankingTest {

    private lateinit var file: File
    private lateinit var store: UserDictStore

    @Before
    fun setUp() {
        file = File.createTempFile("user_dict", ".txt").also { it.delete() }
        store = UserDictStore.open(file)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    /** 五笔态候选源：真实词库 + 用户词库（与 QuickLocator.buildComposite 的结构一致）。 */
    private fun wubiEngine(): CompositeEngine {
        val dict = File("src/main/assets/wubi86.dict")
        assertTrue("找不到真实词库：${dict.absolutePath}", dict.exists())
        return CompositeEngine(
            listOf(WubiEngine(dict.readText(Charsets.UTF_8)), UserDictEngine(store, UserDictMode.WUBI)),
        )
    }

    @Test
    fun `AC-3_6 学一次后 klqt 首选是喂猫，再学一次 count 与权重都递增`() {
        val first = store.record("klqt", "喂猫", UserDictMode.WUBI)
        assertEquals(1, first?.count)
        assertEquals(130, first?.weight)
        assertEquals("喂猫", wubiEngine().lookup("klqt", 10).first().insertText)

        val second = store.record("klqt", "喂猫", UserDictMode.WUBI)
        assertEquals(2, second?.count)
        assertEquals(140, second?.weight)
        assertEquals("学第二次仍排第 1", "喂猫", wubiEngine().lookup("klqt", 10).first().insertText)
    }

    @Test
    fun `学到的词组与同码系统候选并存，系统候选一条不少`() {
        val before = wubiEngine().lookup("klqt", 10).map { it.insertText }
        assertTrue("系统词库 klqt 下应已有候选：$before", before.size >= 2)

        store.record("klqt", "喂猫", UserDictMode.WUBI)
        val after = wubiEngine().lookup("klqt", 10).map { it.insertText }

        assertEquals("喂猫", after.first())
        assertEquals("原有候选必须全部保留，只多一条", before.size + 1, after.size)
        assertTrue("原有候选被挤掉了：$after", after.containsAll(before))
    }

    /**
     * 「词组 · 句子」组内第 1 这一段。AC-3.7 的**门禁仍是候选条序列 index 0**（VOCA-122 裁决 v1.1.2，
     * 引擎侧由 [学到的词组与同码系统候选并存，系统候选一条不少] 的 `after.first()` 钉住）；
     * 「组内第 1 与 index 0 是同一个结果」是裁决给的**推论**，不是被替换掉的门禁，本用例钉的是推论的这半段。
     *
     * 重合的前提是 `klqt` 在系统词库里只有两条词组（中国银行 62857 / 喂猪 54507）、没有单字候选，
     * 所以把该前提显式守住：将来换成含单字的码位（例如用户自装分类词库在满码下补出单字候选，
     * 即 VOCA-122 质检的 N-1 caveat），这条会先失败，而不是悄悄把两种口径混成一个结论。
     * 档序（词组与单字谁在前）裁决明确**不需要改**；若产品要求「学习词永远压过单字排全局首位」，
     * 那是对 `fa57a07` 已合并分组行为的变更，归 UX + 架构师新开 issue，不在本 issue 内。
     */
    @Test
    fun `学到的词在词组组内排第 1，同码系统词组一条不少`() {
        val system = wubiEngine().lookup("klqt", 10)
        assertTrue(
            "样本码 klqt 必须不含单字候选，否则「组内第 1」与「全局第 1」不再重合：$system",
            system.all { codePointLength(it.insertText) > 1 },
        )

        store.record("klqt", "喂猫", UserDictMode.WUBI)
        val after = wubiEngine().lookup("klqt", 10)
        val phrases = after.filter { codePointLength(it.insertText) > 1 }

        assertEquals("词组组内第 1 应是学到的词", "喂猫", phrases.first().insertText)
        assertTrue("同码系统词组被挤掉了：$after", after.containsAll(system))
    }

    @Test
    fun `写盘行是 4 列且回滚版本按 3 列也能读回`() {
        store.record("klqt", "喂猫", UserDictMode.WUBI)
        store.record("klqt", "喂猫", UserDictMode.WUBI)
        val line = file.readLines(Charsets.UTF_8).first { it.isNotBlank() && !it.startsWith("#") }

        assertEquals("klqt\t喂猫\t2\twb", line)
        assertEquals("回滚版本读 3 列不丢词", "klqt\t喂猫\t2", line.substringBeforeLast('\t'))
    }
}