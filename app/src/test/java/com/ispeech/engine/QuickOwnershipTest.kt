package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0002 决策二：trigger 所有权。
 *
 * 覆盖判定与候选去重都只认归一化 trigger，正文 / 名称退出身份判定；
 * 否则「同码但正文已被用户改过」的本地条目既不算覆盖、又被当新规则推回云端。
 */
class QuickOwnershipTest {

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
        weight = 100,
        mode = mode,
        kind = LookupKind.PHRASE,
        source = source,
    )

    private fun user(
        trigger: String = "dtwsl",
        label: String = "泡池须知",
        content: String = "本地正文",
    ) = entry(trigger, label, content, QuickSource.USER)

    private fun remote(trigger: String = "dtwsl", content: String = "云端正文") =
        entry(trigger, trigger, content, QuickSource.REMOTE, QuickMatchMode.EXACT)

    private fun builtIn(trigger: String, content: String = "内置正文") =
        entry(trigger, trigger, content, QuickSource.BUILT_IN)

    @Test
    fun `C2-2 覆盖判定只看归一化 trigger`() {
        assertTrue(QuickOverride.matches(user(content = "A"), remote(content = "B")))
        assertTrue(QuickOverride.matches(user("DTWsl "), remote("dtwsl")))
        assertFalse("同正文不同码不是覆盖", QuickOverride.matches(user("abcd", content = "A"), remote("dtwsl", content = "A")))
        assertFalse("空 trigger 不算覆盖", QuickOverride.matches(user(""), remote("")))
    }

    @Test
    fun `C2-2 同码正文不同仍算覆盖`() {
        // 这条正是旧实现漏掉的场景：label+content 判等 → 不算覆盖 → 被当成新规则推上云端
        val local = user("dtwsl", label = "泡池须知", content = "用户改过的长正文")
        assertTrue(QuickOverride.matchesAny(local, listOf(remote("dtwsl", "云端旧正文"))))
    }

    @Test
    fun `C2-4 同 trigger 跨来源去重后只剩用户那一条`() {
        val groups = listOf(
            QuickRuleGroup(QuickSource.BUILT_IN, listOf(builtIn("dtwsl"), builtIn("yc"))),
            QuickRuleGroup(QuickSource.REMOTE, listOf(remote("dtwsl"), remote("abcd"))),
            QuickRuleGroup(QuickSource.USER, listOf(user("dtwsl"))),
        )

        val kept = QuickOwnership.dedupe(groups).flatMap { it.entries }

        assertEquals("同码只留 1 条", 1, kept.count { QuickTrigger.normalize(it.trigger) == "dtwsl" })
        assertEquals(QuickSource.USER, kept.single { it.trigger == "dtwsl" }.source)
        assertEquals("其它码不受影响", 3, kept.size)
        assertTrue(kept.any { it.trigger == "yc" })
        assertTrue(kept.any { it.trigger == "abcd" })
    }

    @Test
    fun `C2-4 没有用户条目时远端优先于内置`() {
        val groups = listOf(
            QuickRuleGroup(QuickSource.BUILT_IN, listOf(builtIn("dtwsl"))),
            QuickRuleGroup(QuickSource.REMOTE, listOf(remote("dtwsl"))),
        )

        val kept = QuickOwnership.dedupe(groups).flatMap { it.entries }

        assertEquals(1, kept.size)
        assertEquals(QuickSource.REMOTE, kept.single().source)
    }

    @Test
    fun `C2-4 空 trigger 条目不参与去重`() {
        val groups = listOf(
            QuickRuleGroup(QuickSource.REMOTE, listOf(remote(""))),
            QuickRuleGroup(QuickSource.USER, listOf(user(""))),
        )

        val kept = QuickOwnership.dedupe(groups).flatMap { it.entries }

        assertEquals("空码条目两侧都保留，交给引擎自行忽略", 2, kept.size)
    }
}
