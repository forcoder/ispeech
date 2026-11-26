package com.ispeech.engine

import com.ispeech.data.PhraseCsvImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * VOCA-651：候选条被快捷短语挤满，2 码单字打不出（`hx` / `yp` / `dt`）。
 *
 * 用真实资产复现，不写桩候选：
 * - `assets/wubi86.dict` 提供五笔二级简码 `hx`→此 / `yp`→这 / `dt`→帮；
 * - `tools/verify/fixtures/general_phrases.csv`（与仓库根 `通用短语.csv` 同一份，224 条带码规则）
 *   走真实 [PhraseCsvImport] 导入，其中 `hx` 家族 17 条、`yp` 15 条、`dt` 9 条。
 *
 * 三条验收对应 issue 的三条 AC：首屏可见 / 4 码不再命中短触发码 / exact 命中不回归。
 */
class QuickPhraseCandidateStarvationTest {

    /** Gradle 单测工作目录是模块目录；IDE 里可能是仓库根目录，两处都兜住。 */
    private fun firstExisting(vararg paths: String): File =
        paths.map(::File).firstOrNull { it.isFile }
            ?: error("找不到文件：${paths.joinToString()}（工作目录 ${File(".").absolutePath}）")

    private fun wubiDict(): File =
        firstExisting("src/main/assets/wubi86.dict", "app/src/main/assets/wubi86.dict")

    private fun phraseCsv(): File =
        firstExisting(
            "../tools/verify/fixtures/general_phrases.csv",
            "tools/verify/fixtures/general_phrases.csv",
        )

    /** 真实快捷规则：CSV 字节 → PhraseCsvImport 解码 → 224 条带码规则。 */
    private fun importedEntries(): List<QuickEntry> {
        val bytes = phraseCsv().readBytes()
        val parsed = PhraseCsvImport.parse(PhraseCsvImport.decode(bytes))
        assertTrue("CSV 解析失败：${parsed.formatError}", parsed.formatError == null)
        assertEquals("导入条数应与 VOCA-28 基线一致", 224, parsed.entries.size)
        return parsed.entries
    }

    private fun wubiEngine(): WubiEngine = WubiEngine(wubiDict().readText(Charsets.UTF_8))

    /** 与输入法服务真实构建一致：五笔词库 + 快捷短语。 */
    private fun composite(): CompositeEngine =
        CompositeEngine(
            listOf(wubiEngine(), QuickPhraseEngine(importedEntries())),
        )

    // —— AC1：2 码单字必须落在首屏（默认 9 格） ——

    private val starvedPairs = listOf(
        "hx" to "此",
        "yp" to "这",
        "dt" to "帮",
        "ws" to "休",
    )

    @Test
    fun `AC1 真实短语导入后 2 码单字仍在首屏可见`() {
        val engine = composite()
        for (slots in listOf(9, 7, 5)) {
            val limit = slots * 3
            for ((code, expected) in starvedPairs) {
                val visible = engine.lookup(code, limit, slots).take(slots).map { it.insertText }
                assertTrue(
                    "敲 $code 时「$expected」必须在首屏 $slots 格内可见，实际首屏=$visible",
                    expected in visible,
                )
            }
        }
    }

    @Test
    fun `AC1 无格位兜底时问题可复现（反证）`() {
        // 只调 merge 口径（不传首屏格数）：短语档位在前，单字被挤出 9 格——这就是 VOCA-651 的现场。
        val engine = composite()
        val firstNine = engine.lookup("hx", 9).take(9).map { it.insertText }
        assertTrue("9 格内应全是快捷短语（复现挤出）", firstNine.none { it == "此" })
        assertTrue("首屏应确实被填满", firstNine.size == 9)
    }

    // —— AC2：输入满 4 码时，长度 < 4 的触发码不再命中 ——

    @Test
    fun `AC2 敲满 4 码时更短的触发码不再命中`() {
        val quick = QuickPhraseEngine(importedEntries())
        // 真实数据里 4 码规则与它的短前缀码并存：wxhp/wx、wsgl/ws、bxhd/bx、dtwl/dt…
        // 双向匹配时这些短码在打满 4 码后仍然冒出来（候选条越打越满），单向只认
        // 「触发码以输入为前缀」，短码必须退场。
        val cases = listOf(
            "wxhp" to "wx",
            "wsgl" to "ws",
            "bxhd" to "bx",
            "dtwl" to "dt",
            "hyht" to "hy",
        )
        for ((longCode, shortCode) in cases) {
            val hits = quick.lookup(longCode, 64)
            assertTrue("$longCode 本身应命中", hits.any { it.code.equals(longCode, true) })
            assertTrue(
                "敲满 $longCode 时长度更短的 $shortCode 不应再命中，实际命中=${hits.map { it.code }}",
                hits.none { it.code.equals(shortCode, true) },
            )
        }
    }

    @Test
    fun `AC2 长码打前 4 位仍然命中（既有可达性不回归）`() {
        val quick = QuickPhraseEngine(importedEntries())
        // 5 位码 hxwslw 在五笔态只能打 4 位，必须靠「trigger 以输入为前缀」命中
        val hits = quick.lookup("hxws", 32)
        assertTrue("长码打前 4 位应命中 hxwslw", hits.any { it.code == "hxwslw" })
    }

    // —— AC3：既有 exact 命中不受影响（VOCA-28 的 224 个编码仍能打出） ——

    @Test
    fun `AC3 全部 224 条快捷规则敲全码仍各自命中首位`() {
        val entries = importedEntries()
        val quick = QuickPhraseEngine(entries)
        val misses = ArrayList<String>()
        for (entry in entries) {
            val hit = quick.lookup(entry.trigger, 32)
                .firstOrNull { it.insertText == entry.content }
            if (hit == null) {
                misses.add("${entry.trigger}→${entry.label}")
            }
        }
        assertTrue("exact 命中不能因 VOCA-651 修复丢失，未命中=${misses.take(10)}", misses.isEmpty())
    }

    @Test
    fun `AC3 精确码命中的短语仍排在首位（档位顺序不动）`() {
        val engine = composite()
        // ly→鹭影 是同码竞争最强的样本（五笔 ly 下「滤」权重 30000）
        val top = engine.lookup("ly", 27, 9).first()
        assertEquals("ly", top.code.lowercase())
        assertEquals("鹭影", top.insertText)
    }

    @Test
    fun `AC3 格位兜底不挤掉精确码命中的短语`() {
        // 构造：首屏被短语填满，且输入正好等于其中一条短语的触发码
        val entries = importedEntries()
        val quick = QuickPhraseEngine(entries)
        val engine = CompositeEngine(listOf(wubiEngine(), quick))
        val hits = engine.lookup("hxww", 27, 9)
        val top = hits.first()
        assertEquals("精确码命中必须仍在首位", "hxww", top.code.lowercase())
    }

    // —— 结构化保障：兜底只在「短语过半」时生效 ——

    @Test
    fun `格位兜底在短语未过半时不动顺序`() {
        val entries = importedEntries()
        val quick = QuickPhraseEngine(entries)
        val engine = CompositeEngine(listOf(wubiEngine(), quick))
        // 3 字母码 zzz 在真实规则里几乎没有前缀命中，顺序应与无兜底一致
        val withSlots = engine.lookup("zzz", 27, 9)
        val merged = engine.lookup("zzz", 27)
        assertEquals(merged, withSlots)
    }

    @Test
    fun `首屏装不满时整条候选原样返回`() {
        val engine = composite()
        // limit 小于首屏格数：没有「挤出首屏」问题
        val result = engine.lookup("hx", 3, 9)
        assertTrue("不应凭空补充候选", result.size <= 3)
        assertNotNull(result)
    }
}