package com.ispeech.settings.quick

import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickPhraseEngine
import com.ispeech.engine.QuickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：设置页「新增快捷短语」的落盘与列表契约。
 *
 * 覆盖三条真实约束（均由 [QuickPhrasesViewModel] + [QuickPhraseCodec] 保证）：
 *
 * 1. 新增条目经 `QuickPhraseCodec.format` 写盘后，必须能被 `QuickPhraseEngine` 解析并
 *    **精确命中**——这是「保存后立即被输入法候选命中」的唯一数据通路（引擎只读文件）；
 * 2. 同一快捷字母再次新增是覆盖，不是追加（否则文件里出现两行同 trigger，解析时后者吞掉前者）；
 * 3. 编辑时改了快捷字母必须移除旧条目，否则列表里会残留一条旧 trigger 的孤儿规则。
 */
class QuickPhraseCreateFlowTest {

    private fun entry(
        trigger: String,
        content: String,
        label: String = content,
        mode: QuickMatchMode = QuickMatchMode.PREFIX,
        weight: Int = 100,
        kind: LookupKind = LookupKind.QUICKAPPLY,
    ) = QuickEntry(
        trigger = trigger,
        label = label,
        content = content,
        weight = weight,
        mode = mode,
        kind = kind,
    )

    /** 复刻设置页「新增 / 编辑确认」时的列表变换（原条目 trigger 仅在编辑时为非空）。 */
    private fun applyUpsert(
        entries: List<QuickEntry>,
        entry: QuickEntry,
        originalTrigger: String? = null,
    ): List<QuickEntry> = QuickPhraseEdits.upsert(entries, entry, originalTrigger)

    /** 新增的本地短语写盘后必须能被输入法引擎精确命中（且内容原样上屏）。 */
    @Test
    fun `新增的本地短语保存后能被输入法候选精确命中`() {
        val created = entry("ycsf", "游泳池换水要收费的")
        val saved = applyUpsert(emptyList(), created)

        val engine = requireNotNull(QuickPhraseEngine.fromText(QuickPhraseCodec.format(saved))) {
            "写盘后应能被引擎解析（否则新短语永远不出现在候选里）"
        }
        val hits = engine.lookup("ycsf", 10)

        assertTrue("输入 ycsf 应命中新增的短语", hits.isNotEmpty())
        assertEquals("新增短语必须排在候选首位", "游泳池换水要收费的", hits.first().display)
        assertEquals("上屏内容应为候选内容", "游泳池换水要收费的", hits.first().insertText)
    }

    /** 新增条目必须打上「用户」来源，否则来源角标与远端覆盖判定都会错。 */
    @Test
    fun `新增条目的来源固定为用户`() {
        val saved = applyUpsert(emptyList(), entry("thx", "感谢您的咨询"))

        assertEquals(1, saved.size)
        assertEquals(QuickSource.USER, saved.single().source)
    }

    /** 同快捷字母再次新增是覆盖，不允许出现两条同 trigger 的规则。 */
    @Test
    fun `同快捷字母重复新增是覆盖而不是追加`() {
        val first = entry("thx", "感谢您的咨询")
        val second = entry("thx", "感谢您的耐心等待")

        val merged = applyUpsert(applyUpsert(emptyList(), first), second)

        assertEquals("同 trigger 只应保留一条", 1, merged.size)
        assertEquals("应以最后一条为准", "感谢您的耐心等待", merged.single().content)
    }

    /** 编辑时改了快捷字母：旧条目必须消失，列表里不能留旧 trigger 的孤儿规则。 */
    @Test
    fun `编辑改快捷字母后旧条目被移除`() {
        val original = entry("thx", "感谢您的咨询")
        val renamed = entry("thks", "感谢您的咨询")

        val merged = applyUpsert(listOf(original), renamed, originalTrigger = original.trigger)

        assertEquals("旧 trigger 的条目应被替换而不是残留", 1, merged.size)
        assertEquals("thks", merged.single().trigger)
    }

    /** 编辑没改快捷字母时只替换原条目，不应产生第二条。 */
    @Test
    fun `编辑不改快捷字母时原地替换`() {
        val original = entry("thx", "感谢您的咨询")
        val edited = entry("thx", "感谢您的耐心等待")

        val merged = applyUpsert(listOf(original), edited, originalTrigger = original.trigger)

        assertEquals(1, merged.size)
        assertEquals("感谢您的耐心等待", merged.single().content)
    }

    /** 新增不应动到无关条目。 */
    @Test
    fun `新增不影响其他既有条目`() {
        val existing = entry("addr", "广东省广州市天河区五山路483号")
        val added = entry("tel", "020-88888888")

        val merged = applyUpsert(listOf(existing), added)

        assertEquals(2, merged.size)
        assertNotNull(merged.firstOrNull { it.trigger == "addr" })
        assertNotNull(merged.firstOrNull { it.trigger == "tel" })
    }
}