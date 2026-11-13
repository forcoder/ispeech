package com.ispeech.engine

import com.ispeech.data.UserDictEntry
import com.ispeech.data.UserDictMode
import com.ispeech.data.entryKindOf
import com.ispeech.data.parseUserDictLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 词库文件格式互换（VOCA-124 DoD 第 3 条）：词组学习写出的行必须能被现有解析读回，
 * 且本身就是现网 APK 认识的老格式——因此回滚到旧版本不会丢词、不需要清库。
 *
 * 只在 JVM 层验解析与档位；真实文件读写在 adb 真机项里验（见 `docs/voice-phrase-learning-verify.md`）。
 */
class PhraseDictCompatibilityTest {

    @Test
    fun `学习写出的 4 列行按五笔模式读回`() {
        val entry: UserDictEntry? = parseUserDictLine("klqt\t喂猫\t1\twb")

        assertNotNull("词组行必须能解析", entry)
        assertEquals("klqt", entry!!.code)
        assertEquals("喂猫", entry.text)
        assertEquals(1, entry.count)
        assertEquals(UserDictMode.WUBI, entry.mode)
        assertEquals(120 + 10 * 1, entry.weight)
    }

    @Test
    fun `历史 3 列回滚版本仍按五笔读入且不丢词`() {
        val legacy = parseUserDictLine("klqt\t喂猫\t1")

        assertNotNull(legacy)
        assertEquals(UserDictMode.WUBI, legacy!!.mode)
        assertEquals("喂猫", legacy.text)
    }

    @Test
    fun `词组条目的候选档是词组档、单字仍是单字档`() {
        val phrase = parseUserDictLine("klqt\t喂猫\t1\twb")!!
        val single = parseUserDictLine("klge\t喂\t1\twb")!!

        assertEquals(LookupKind.PHRASE, entryKindOf(phrase.text))
        assertEquals(LookupKind.WUBI, entryKindOf(single.text))
    }

    @Test
    fun `count 累加后权重按 120 加 10 倍次数且封顶 400`() {
        val second = parseUserDictLine("klqt\t喂猫\t2\twb")!!
        val capped = parseUserDictLine("klqt\t喂猫\t999\twb")!!

        assertEquals(140, second.weight)
        assertEquals("权重上限 400 不变", 400, capped.weight)
    }
}