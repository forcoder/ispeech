package com.ispeech.data

import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickPhraseEngine
import com.ispeech.settings.quick.QuickPhraseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * VOCA-28 端到端回归：真实「通用短语.csv」→ 导入 → 落盘 → 读盘 → 敲快捷字母出候选。
 *
 * 既有 [PhraseCsvImportTest] 只锁到「解析出多少条」，没有一条断言走完「导入的规则
 * 能不能真的打出候选」。2026-11-25 复验时发现这正好是 AC 的核心（快捷字母 → 对应候选），
 * 而当时的证据只有真机手测截图，没有可重放的自动化断言。这里补上：
 *
 * 1. 用仓库里那份 235 行的真实 GBK 导出文件，不另造样本；
 * 2. 走与设置页完全相同的两条链路：[PhraseCsvImport.parse] → [QuickPhraseCodec.format]
 *    → 写盘 → [QuickPhraseCodec.parse] → [QuickPhraseEngine.fromText]；
 * 3. 断言 `ly` / `ls` / `hxww` / `hxws`（原码 8 位打前 4 位）/ `j`（1 位码）各自的候选结果。
 *
 * 数据侧契约（别在断言里「修正」这些数字，它们来自 CSV 本身）：
 * 235 行数据、1 条无 INPUT_CODE、10 条同码合并、60 条超 4 位仍按 prefix 导入 → 实导 224 条。
 */
class PhraseCsvImportCandidateTest {

    /** Gradle 单测工作目录是模块目录；IDE 里可能是仓库根目录，两处都兜住。 */
    private fun fixtureFile(): File =
        listOf(
            File("../tools/verify/fixtures/general_phrases.csv"),
            File("tools/verify/fixtures/general_phrases.csv"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 fixtures/general_phrases.csv（工作目录 ${File(".").absolutePath}）")

    private fun importFixture(): PhraseCsvImportResult =
        PhraseCsvImport.parse(PhraseCsvImport.decode(fixtureFile().readBytes()))

    /** 复刻设置页「导入 → 保存写盘 → 输入法读盘」的完整往返，返回输入法拿到的引擎。 */
    private fun engineAfterRoundTrip(): QuickPhraseEngine {
        val imported = importFixture()
        val fileText = QuickPhraseCodec.format(imported.entries)
        // 输入法读用户规则文件时不带长度守卫（>4 位 + prefix 在用户文件里是有意数据）
        return requireNotNull(QuickPhraseEngine.fromText(fileText))
    }

    @Test
    fun `真实 CSV 导入 224 条且各类跳过计数如实`() {
        val result = importFixture()

        assertEquals("数据行数（不含表头）", 235, result.totalRows)
        assertEquals("缺 INPUT_CODE", 1, result.skippedNoCode)
        assertEquals("同码只有首条保留", 10, result.skippedDuplicateCode)
        assertEquals("IS_DELETE=0 覆盖全部", 0, result.skippedDeleted)
        assertEquals("非法扫码（本文件为 0）", 0, result.skippedBadCode)
        assertEquals("实导条数", 224, result.entries.size)
        // 60 条超 4 位的是「照导但打前 4 位前缀命中」，不是跳过，应记在 longCodeCount 上
        assertEquals("超 4 位仍导入", 60, result.longCodeCount)
        assertEquals("1 位码条数", 1, result.singleLetterCount)
    }

    @Test
    fun `导入落盘往返后规则条数与快捷字母不变`() {
        val imported = importFixture()
        val reparsed = QuickPhraseCodec.parse(QuickPhraseCodec.format(imported.entries))

        assertEquals("落盘往返条数", 224, reparsed.size)
        assertEquals(
            "落盘往返 trigger 集合",
            imported.entries.map { it.trigger }.toSet(),
            reparsed.map { it.trigger }.toSet(),
        )
        assertEquals(
            "落盘往返正文",
            imported.entries.associate { it.trigger to it.content },
            reparsed.associate { it.trigger to it.content },
        )
    }

    @Test
    fun `敲 ly 出鹭影候选`() {
        val candidates = engineAfterRoundTrip().lookup("ly", 10)

        assertTrue("ly 应出候选", candidates.isNotEmpty())
        assertEquals("ly 首选", "鹭影", candidates.first().display)
    }

    @Test
    fun `敲 ls 出揽墅候选`() {
        val candidates = engineAfterRoundTrip().lookup("ls", 10)

        assertTrue("ls 应出候选", candidates.isNotEmpty())
        assertEquals("ls 首选", "揽墅", candidates.first().display)
    }

    @Test
    fun `敲 hxww 出湖山鹭韵五街5号且正文含换行`() {
        val candidates = engineAfterRoundTrip().lookup("hxww", 10)

        assertNotNull("hxww 应出候选", candidates.firstOrNull())
        // hxww 是 4 位码，命中的是该房源的多行长文
        assertTrue(
            "hxww 首选应是湖山鹭韵五街5号，实际 ${candidates.first().display}",
            candidates.first().display.contains("湖山鹭韵五街5号"),
        )
        assertTrue("多行正文需保留换行", candidates.first().insertText.contains("\n"))
    }

    @Test
    fun `8 位长码 hxws 打前 4 位即前缀命中且不截断正文`() {
        val engine = engineAfterRoundTrip()

        // 原码 8 位：打全不现实，打前 4 位就要能进候选
        val candidates = engine.lookup("hxws", 10)
        assertTrue("hxws 打前 4 位应命中", candidates.isNotEmpty())

        val hit = candidates.first { it.display.contains("湖山鹭韵五街3号") }
        assertTrue("长码命中后正文必须完整（>200 字）", hit.insertText.length > 200)
        assertTrue("长码命中后正文要保留换行", hit.insertText.contains("\n"))
    }

    @Test
    fun `1 位码 j 也能出候选`() {
        val candidates = engineAfterRoundTrip().lookup("j", 10)

        assertTrue("j 应出候选", candidates.isNotEmpty())
        assertTrue(
            "j 首选应是「亲，距离很近…」，实际 ${candidates.first().display}",
            candidates.first().display.startsWith("亲，距离很近"),
        )
    }

    @Test
    fun `导入的 4 位以内短码全部按 prefix 可被完整输入命中`() {
        val entries: List<QuickEntry> = importFixture().entries
        val engine = engineAfterRoundTrip()

        // 4 位及以内的码在五笔态可以完整敲出来，必须能自己命中自己
        val shortCodes = entries.filter { it.trigger.length <= 4 }
        assertTrue("应有足量短码", shortCodes.size > 150)

        val misses = shortCodes.filter { entry ->
            engine.lookup(entry.trigger, 20).none { it.display == entry.label || it.insertText == entry.content }
        }.map { it.trigger }

        assertEquals("短码完整输入后未命中的 trigger", emptyList<String>(), misses)
    }

    @Test
    fun `重复导入同一份文件幂等`() {
        val first = importFixture().entries
        val second = importFixture().entries

        assertEquals("同文件二次导入条数", first.size, second.size)
        assertEquals(
            "同文件二次导入内容",
            first.associate { it.trigger to it.content },
            second.associate { it.trigger to it.content },
        )
    }

    @Test
    fun `超 4 位长码在用户文件里保持 prefix 而不被降级成 exact`() {
        val entries = importFixture().entries
        val longOnes = entries.filter { it.trigger.length > 4 }

        assertTrue("应有超 4 位长码", longOnes.isNotEmpty())
        assertTrue(
            "用户文件的 >4 位码必须保持 prefix（打前 4 位即命中），不能被远端守卫误降级",
            longOnes.all { it.mode == QuickMatchMode.PREFIX },
        )
    }
}