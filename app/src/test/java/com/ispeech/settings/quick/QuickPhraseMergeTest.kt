package com.ispeech.settings.quick

import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户条目与远端规则的展示合并。
 *
 * `mergeForDisplay` 把「用户文件里 label + content 与远端规则相同的条目」认作远端规则的
 * 快捷字母覆盖（见 QuickOverride），折叠到远端卡片上。问题在于判据只有 label + content：
 *
 * - 用户**自己新增**了一条内容恰好与远端相同的短语时，也会被折叠成「远端规则」，
 *   于是刚新增的条目从列表里消失、来源显示为远端、删除按钮也没了（远端卡片只能恢复默认）。
 * - 覆盖条目是**本地别名**，不该再把同样的内容当成一条新规则推给云端。
 *
 * 这里锁定合并决策本身：结果里每条的来源、是否带远端信息、以及哪些条目应由同步跳过。
 */
class QuickPhraseMergeTest {

    private fun entry(
        trigger: String,
        content: String,
        label: String = content,
        source: String = QuickSource.USER,
        kind: LookupKind = LookupKind.QUICKAPPLY,
    ) = QuickEntry(
        trigger = trigger,
        label = label,
        content = content,
        weight = 100,
        mode = QuickMatchMode.PREFIX,
        kind = kind,
        source = source,
    )

    private val remoteRule = entry(
        trigger = "ytyc",
        content = "雨天泳池",
        source = QuickSource.REMOTE,
    )

    /** 用户给远端规则设了快捷字母：折叠成一条远端卡片，带上「已覆盖为 xxx」。 */
    @Test
    fun `label 与 content 均相同的用户条目折叠为远端覆盖`() {
        val override = entry(trigger = "ytsc", content = "雨天泳池", source = QuickSource.USER)

        val merged = QuickPhraseMerge.merge(listOf(override), listOf(remoteRule))

        assertEquals("应只展示一条，不能既有远端又有覆盖条目", 1, merged.entries.size)
        val shown = merged.entries.single()
        assertEquals("展示的快捷字母应为用户覆盖的", "ytsc", shown.trigger)
        assertNotNull("该条应带远端信息（卡片上要能恢复默认）", merged.infoById[QuickPhraseMerge.idOf(shown)])
        assertEquals("ytsc", merged.infoById[QuickPhraseMerge.idOf(shown)]?.overrideTrigger)
    }

    /**
     * 用户新增一条**与远端同内容**的短语，且没有走「设置快捷字母」：
     * 二者语义不同（一个是新建本地短语，一个是给远端规则起别名），不应互相吞并。
     */
    @Test
    fun `用户新增的同内容短语不会被误判为远端覆盖`() {
        // 用户新建：label 用自己的标题，与远端 label 不同
        val created = entry(trigger = "rain", content = "雨天泳池", label = "雨天泳池地址")

        val merged = QuickPhraseMerge.merge(listOf(created), listOf(remoteRule))

        assertEquals("远端规则与用户新建的短语是两条，应都展示", 2, merged.entries.size)
        val mine = merged.entries.first { it.trigger == "rain" }
        assertEquals(QuickSource.USER, mine.source)
        assertNull("用户新建的短语不该被挂上远端信息", merged.infoById[QuickPhraseMerge.idOf(mine)])
        assertTrue(
            "用户新建的短语不能被同步跳过（否则云端永远收不到这条）",
            merged.skipPushTriggers.isEmpty() || "rain" !in merged.skipPushTriggers,
        )
    }

    /** 覆盖条目应被同步跳过：它是本地别名，推上去会在云端产生同内容重复规则。 */
    @Test
    fun `覆盖条目被标记为同步跳过`() {
        val override = entry(trigger = "ytsc", content = "雨天泳池")

        val merged = QuickPhraseMerge.merge(listOf(override), listOf(remoteRule))

        assertTrue("覆盖条目的 trigger 应进入 skipPushTriggers", "ytsc" in merged.skipPushTriggers)
    }

    /** 普通用户条目照常展示、照常推送。 */
    @Test
    fun `普通用户条目不受远端影响`() {
        val plain = entry(trigger = "addr", content = "广东省广州市天河区五山路483号", label = "家庭住址")

        val merged = QuickPhraseMerge.merge(listOf(plain), listOf(remoteRule))

        assertEquals(2, merged.entries.size)
        assertTrue(merged.skipPushTriggers.isEmpty())
    }

    /** 远端规则没有对应覆盖时，快捷字母取远端原值、overrideTrigger 为空。 */
    @Test
    fun `未被覆盖的远端规则展示原始快捷字母`() {
        val merged = QuickPhraseMerge.merge(emptyList(), listOf(remoteRule))

        val shown = merged.entries.single()
        assertEquals("ytyc", shown.trigger)
        assertNotNull(merged.infoById[QuickPhraseMerge.idOf(shown)])
        assertNull("没有覆盖时 overrideTrigger 应为空", merged.infoById[QuickPhraseMerge.idOf(shown)]?.overrideTrigger)
    }

    /** 远端规则被删除后遗留的覆盖条目：照常作为用户条目展示。 */
    @Test
    fun `远端消失后遗留的覆盖条目作为用户条目保留`() {
        val orphan = entry(trigger = "ytsc", content = "雨天泳池")

        val merged = QuickPhraseMerge.merge(listOf(orphan), emptyList())

        assertEquals(1, merged.entries.size)
        assertEquals(QuickSource.USER, merged.entries.single().source)
        assertTrue(merged.infoById.isEmpty())
    }
}