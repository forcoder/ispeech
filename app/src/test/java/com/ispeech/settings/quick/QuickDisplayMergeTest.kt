package com.ispeech.settings.quick

import com.ispeech.data.PhraseUsageStore
import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickPhraseEngine
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

    /**
     * ADR-0002 C1-6b 的展示侧半边（VOCA-207 质检打回项 1）：面板卡片渲染的 mode 必须与引擎
     * 生效的 mode 同源。此前 `QuickPhraseCodec.parse` 直取磁盘声明值，卡片显示 `PREFIX` 而
     * 引擎已按 `EXACT` 生效 —— 用户看到的和实际命中的不是一套口径，编辑器又从这个 display
     * entry 继承 mode，保存进用户文件后误弹路径会重新出现。
     */
    @Test
    fun `C1-6b 云端行的展示 mode 与 fromRemoteText 的生效 mode 逐条一致`() {
        val rows = listOf(
            "dtwsl\t泡池须知\t长正文\tPREFIX",
            "dtwsl2\t另一条\t长正文\tcontains",
            "dtwsl3\t缺省第四字段\t长正文",
            "dtws\t短码\t短正文\tPREFIX",
            "d1\t精确\t正文\texact",
            "dtwsl4\t正则\t正文\tregex",
        ).joinToString("\n")

        val display = QuickPhraseCodec.parse(rows, applyRemoteModeGuard = true).associateBy({ it.trigger }, { it.mode })
        val engine = requireNotNull(QuickPhraseEngine.fromRemoteText(rows)).entries.associateBy({ it.trigger }, { it.mode })

        assertEquals("展示侧与引擎侧对同一份云端文本必须给出同一张 mode 表", engine, display)
        assertEquals("5 位云端 PREFIX 行在面板上也要显示 EXACT", QuickMatchMode.EXACT, display["dtwsl"])
        assertEquals(QuickMatchMode.EXACT, display["dtwsl2"])
        assertEquals("缺省第 4 字段解析成 PREFIX，同样在守卫内", QuickMatchMode.EXACT, display["dtwsl3"])
        assertEquals("4 位及以内不受守卫", QuickMatchMode.PREFIX, display["dtws"])
        assertEquals(QuickMatchMode.EXACT, display["d1"])
        assertEquals("REGEX 与 EXACT 同属不受长度守卫的模式", QuickMatchMode.REGEX, display["dtwsl4"])
    }

    /**
     * 守卫吃的是第 1 字段的**原样**长度，不是归一化长度：`"dtws "` 归一化后只剩 4 位会逃过守卫，
     * 而写侧 `SyncRepository` 不 trim，这种行可以落盘。口径与引擎侧逐字对齐。
     */
    @Test
    fun `C1-6b 带尾随空格的畸形云端行同样收严为 EXACT`() {
        val row = "dtws \t尾随空格\t长正文\tPREFIX"

        val engineMode = requireNotNull(QuickPhraseEngine.fromRemoteText(row)).entries.single().mode
        val displayMode = QuickPhraseCodec.parse(row, applyRemoteModeGuard = true).single().mode

        assertEquals(QuickMatchMode.EXACT, engineMode)
        assertEquals("展示侧不能因为 trigger 存盘前被 trim 就绕开守卫", engineMode, displayMode)
    }

    /** C1-6b 作用域限云端落盘文件：用户文件里的 mode 是用户在编辑器里选的（C1-4 / C1-9），不收严。 */
    @Test
    fun `C1-6b 守卫只作用于云端行，用户文件的声明 mode 原样展示`() {
        val row = "dtwsl\t我的短语\t长正文\tPREFIX"

        assertEquals(QuickMatchMode.PREFIX, QuickPhraseCodec.parse(row).single().mode)
        assertEquals(QuickMatchMode.PREFIX, QuickPhraseCodec.parse(row, applyRemoteModeGuard = false).single().mode)
    }
}
