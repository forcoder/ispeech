package com.ispeech.settings.quick

import com.ispeech.data.PhraseUsageStore
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickSource
import com.ispeech.engine.QuickTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0002 C2-3 / C2-5 / C2-7：展示层折叠。
 *
 * 折叠键必须是归一化 trigger：旧实现用 `label to content`，用户改了正文就不算覆盖，
 * 同一条短语在列表里出现两次，而且那条本地条目还会被当成新规则推上云端。
 */
class QuickDisplayMergeTest {

    private fun entry(
        trigger: String,
        label: String,
        content: String,
        source: String,
        mode: QuickMatchMode = QuickMatchMode.PREFIX,
    ) = QuickEntry(
        trigger = trigger,
        label = label,
        content = content,
        weight = if (source == QuickSource.USER) 1100 else 100,
        mode = mode,
        kind = LookupKind.QUICKAPPLY,
        source = source,
    )

    private fun user(trigger: String, content: String, label: String = "泡池须知") =
        entry(trigger, label, content, QuickSource.USER)

    private fun remote(trigger: String, content: String) =
        entry(trigger, trigger, content, QuickSource.REMOTE, QuickMatchMode.EXACT)

    private fun infoOf(merged: QuickDisplayMerged, displayEntry: QuickEntry) =
        merged.infoById.getValue(PhraseUsageStore.idOf(displayEntry))

    @Test
    fun `C2-3 同 trigger 折叠成一张卡片且正文取本地`() {
        val local = user("dtwsl", "本地正文")
        val cloud = remote("dtwsl", "云端正文")

        val merged = QuickDisplayMerge.merge(listOf(local), listOf(cloud))

        assertEquals("同码只能有一张卡片", 1, merged.entries.size)
        assertEquals("本地正文", merged.entries.single().content)
        val info = infoOf(merged, merged.entries.single())
        assertEquals(cloud, info.original)
        assertNotNull("折叠卡片要标出覆盖来源", info.overrideTrigger)
        assertEquals("dtwsl", info.overrideTrigger)
    }

    @Test
    fun `C2-5 同 trigger 同正文不算冲突，差一个空格算冲突`() {
        val same = QuickDisplayMerge.merge(
            listOf(user("dtwsl", "同一份正文")),
            listOf(remote("dtwsl", "同一份正文")),
        )
        assertFalse(infoOf(same, same.entries.single()).conflict)

        val spaced = QuickDisplayMerge.merge(
            listOf(user("dtwsl", "有详细 说明")),
            listOf(remote("dtwsl", "有详细说明")),
        )
        assertTrue(infoOf(spaced, spaced.entries.single()).conflict)
    }

    @Test
    fun `C2-2 正文相同但简写不同不算覆盖`() {
        val merged = QuickDisplayMerge.merge(listOf(user("abcd", "同一份正文")), listOf(remote("dtwsl", "同一份正文")))

        assertEquals("两条各自成卡", 2, merged.entries.size)
        assertTrue(merged.entries.any { it.source == QuickSource.USER && it.trigger == "abcd" })
        assertTrue(merged.entries.any { it.source == QuickSource.REMOTE && it.trigger == "dtwsl" })
    }

    @Test
    fun `C2-7 折叠卡片记住覆盖前的远端 id，使用记录能并过来`() {
        val local = user("dtwsl", "本地正文")
        val cloud = remote("dtwsl", "云端正文")

        val merged = QuickDisplayMerge.merge(listOf(local), listOf(cloud))

        val displayId = PhraseUsageStore.idOf(local)
        assertEquals(PhraseUsageStore.idOf(cloud), merged.originalIdById.getValue(displayId))
    }

    @Test
    fun `C2-3 归一化后才算同码`() {
        val merged = QuickDisplayMerge.merge(listOf(user("DTWSL ", "本地正文")), listOf(remote("dtwsl", "云端正文")))

        assertEquals(1, merged.entries.size)
        assertTrue(infoOf(merged, merged.entries.single()).conflict)
    }

    @Test
    fun `未覆盖的远端规则与普通本地规则各自展示`() {
        val merged = QuickDisplayMerge.merge(
            listOf(user("zz", "我的短语", label = "我的短语")),
            listOf(remote("dtwsl", "云端正文")),
        )

        assertEquals(2, merged.entries.size)
        val cloudId = PhraseUsageStore.idOf(remote("dtwsl", "云端正文"))
        assertEquals("云端正文", merged.infoById.getValue(cloudId).original.content)
        assertNull("没被覆盖的远端规则没有覆盖标记", merged.infoById.getValue(cloudId).overrideTrigger)
        assertFalse(merged.infoById.containsKey(PhraseUsageStore.idOf(user("zz", "我的短语", "我的短语"))))
        assertEquals("zz", QuickTrigger.normalize(user("zz", "我的短语", "我的短语").trigger))
    }

    @Test
    fun `空 trigger 的本地条目不参与折叠也不会丢`() {
        val merged = QuickDisplayMerge.merge(
            listOf(entry("", "无码短语", "正文", QuickSource.USER)),
            listOf(remote("dtwsl", "云端正文")),
        )

        assertEquals(2, merged.entries.size)
        assertTrue(merged.entries.any { it.trigger.isEmpty() })
    }
}
