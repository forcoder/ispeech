package com.ispeech.data

import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickMatchMode
import com.ispeech.engine.QuickSource
import com.ispeech.engine.QuickTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0002 C3：同步回写安全线（P0，数据丢失）。
 *
 * 旧实现里 `else -> updates.add(...)` 只要远端存在同 keyword 的规则就推本地正文，
 * 于是「别人在云端建的同码规则」会被本地内容整段覆盖，且没有任何提示。
 * 现在远端规则的身份只有映射表里的记录，无记录一律记冲突、不进上传集合。
 */
class LocalRuleSyncPlanTest {

    private fun local(
        trigger: String,
        content: String,
        mode: QuickMatchMode = QuickMatchMode.EXACT,
    ) = QuickEntry(
        trigger = trigger,
        label = trigger,
        content = content,
        weight = 100,
        mode = mode,
        kind = LookupKind.PHRASE,
        source = QuickSource.USER,
    )

    private fun rule(
        id: String,
        keyword: String,
        content: String,
        matchType: String = "EXACT",
        priority: Int = 0,
    ) = RemoteKeywordRule(
        id = id,
        keyword = keyword,
        matchType = matchType,
        replyTemplate = content,
        priority = priority,
    )

    private fun plan(
        locals: List<QuickEntry>,
        known: Map<String, RemoteRuleRef>,
        remote: List<RemoteKeywordRule>,
    ) = LocalRuleSyncPlan.decide(
        locals = locals,
        known = known,
        remoteById = remote.associateBy { it.id },
        remoteByKeyword = remote.associateBy { QuickTrigger.normalize(it.keyword) },
        // 单测用正文本身当指纹，映射记录按同一口径构造，避免指纹算法细节干扰裁决断言
        localFingerprintOf = { it.content },
        remoteFingerprintOf = { it.replyTemplate },
        remoteMatches = { candidate, entry -> SyncRepository.isRemoteRuleMatching(candidate, entry) },
    )

    private fun known(trigger: String, id: String, fingerprint: String) =
        mapOf(trigger to RemoteRuleRef(id, fingerprint))

    @Test
    fun `C3-1 无映射记录时同码远端正文不同只能记冲突`() {
        val decisions = plan(
            locals = listOf(local("dtwsl", "本地正文")),
            known = emptyMap(),
            remote = listOf(rule("r1", "dtwsl", "云端正文")),
        )

        assertTrue("无映射记录绝不能推更新", decisions.updates.isEmpty())
        assertEquals(1, decisions.conflicts.size)
        assertTrue("同码已被占用，不能另建一条", decisions.creates.isEmpty())
        assertTrue("正文不一致，不能顺手补映射", decisions.bindings.isEmpty())

        val conflict = decisions.conflicts.single()
        assertEquals("dtwsl", conflict.trigger)
        assertEquals("r1", conflict.remoteId)
        assertEquals("本地正文", conflict.localContent)
        assertEquals("云端正文", conflict.remoteContent)
    }

    @Test
    fun `C3-1 大小写不同的同码同正文不算冲突`() {
        val decisions = plan(
            locals = listOf(local("DTWSL", "同一份正文")),
            known = emptyMap(),
            remote = listOf(rule("r1", "dtwsl", "同一份正文")),
        )

        assertTrue(decisions.conflicts.isEmpty())
        assertEquals(1, decisions.bindings.size)
        assertTrue(decisions.updates.isEmpty())
    }

    @Test
    fun `C3-2 无映射且正文一致时只补映射不推送`() {
        val decisions = plan(
            locals = listOf(local("dtwsl", "同一份正文")),
            known = emptyMap(),
            remote = listOf(rule("r1", "dtwsl", "同一份正文")),
        )

        assertTrue(decisions.updates.isEmpty())
        assertTrue(decisions.creates.isEmpty())
        val binding = decisions.bindings.single()
        assertEquals("dtwsl", binding.trigger)
        assertEquals("r1", binding.remoteId)
        assertEquals("同一份正文", binding.fingerprint)
    }

    @Test
    fun `C3-3 码未被占用时正常新建`() {
        val decisions = plan(
            locals = listOf(local("abcd", "新规则")),
            known = emptyMap(),
            remote = listOf(rule("r1", "dtwsl", "别人的规则")),
        )

        assertEquals(1, decisions.creates.size)
        assertTrue(decisions.updates.isEmpty())
        assertTrue(decisions.conflicts.isEmpty())
    }

    @Test
    fun `C3-4 有映射且本地正文变了才允许更新`() {
        val decisions = plan(
            locals = listOf(local("dtwsl", "用户改过的新正文")),
            known = known("dtwsl", "r1", "上次同步的旧正文"),
            remote = listOf(rule("r1", "dtwsl", "上次同步的旧正文")),
        )

        val update = decisions.updates.single()
        assertEquals("r1", update.remoteId)
        assertEquals("用户改过的新正文", update.entry.content)
        assertTrue(decisions.conflicts.isEmpty())
    }

    @Test
    fun `C3-1 有映射时远端正文被第三方改动，推回本地内容`() {
        val decisions = plan(
            locals = listOf(local("dtwsl", "本地正文")),
            known = known("dtwsl", "r1", "本地正文"),
            remote = listOf(rule("r1", "dtwsl", "云端被改成了别的")),
        )

        assertEquals(1, decisions.updates.size)
        assertTrue("映射在 = 这条规则归本地所有，不属于冲突", decisions.conflicts.isEmpty())
    }

    @Test
    fun `C3-5 映射指向的远端规则已不存在时走新建`() {
        val decisions = plan(
            locals = listOf(local("dtwsl", "本地正文")),
            known = known("dtwsl", "gone", "本地正文"),
            remote = emptyList(),
        )

        assertEquals(1, decisions.creates.size)
        assertTrue(decisions.updates.isEmpty())
        assertTrue(decisions.conflicts.isEmpty())
    }

    /**
     * C3-5 逐字实现：「有映射 + 远端已无该 id → 走 create 分支」。
     *
     * 这里刻意让映射指向一个已消失的 id、同时云端存在别人建的 `dtwsl`：要钉住的是
     * **绝不向别人的 id 推送**（updates 里不能出现 `r2`）。至于「新建会不会造成同码重复」，
     * ADR 只写了 create、没写这种组合的处置，已在 issue 追问架构师，不在此自行改判。
     */
    @Test
    fun `C3-5 映射指向的远端规则已不存在时走新建且不碰同码他人规则`() {
        val decisions = plan(
            locals = listOf(local("dtwsl", "本地正文")),
            known = known("dtwsl", "gone", "上次同步的旧正文"),
            remote = listOf(rule("r2", "dtwsl", "别人建的正文")),
        )

        assertTrue("映射失效不能退成 keyword 兜底覆盖", decisions.updates.isEmpty())
        assertTrue("create 分支不得写进别人的 id", decisions.updates.none { it.remoteId == "r2" })
        assertEquals(1, decisions.creates.size)
    }

    @Test
    fun `C2-5 正文比较用落盘转义口径`() {
        assertTrue(LocalRuleSyncPlan.contentEquals("行1\n行2", "行1\\n行2"))
        assertFalse("差一个空格就是冲突", LocalRuleSyncPlan.contentEquals("有详细 说明", "有详细说明"))
        assertFalse(LocalRuleSyncPlan.contentEquals("A", "B"))
    }

    @Test
    fun `C3-1 冲突记录的正文转义后逐字保留`() {
        val decisions = plan(
            locals = listOf(local("dtwsl", "行1\n行2")),
            known = emptyMap(),
            remote = listOf(rule("r1", "dtwsl", "行1\n行2\t缩进")),
        )

        val conflict = decisions.conflicts.single()
        assertEquals("行1\\n行2", conflict.localContent)
        assertEquals("行1\\n行2\\t缩进", conflict.remoteContent)
    }

    @Test
    fun `C2-5 离线对比同码不同正文`() {
        val conflicts = LocalRuleSyncPlan.conflictsFromFiles(
            locals = listOf(local("dtwsl", "本地正文"), local("yc", "一致")),
            remoteEntries = listOf(
                local("dtwsl", "云端正文", QuickMatchMode.PREFIX).copy(source = QuickSource.REMOTE),
                local("yc", "一致", QuickMatchMode.PREFIX).copy(source = QuickSource.REMOTE),
            ),
            remoteIdOf = { entry -> if (entry.trigger == "dtwsl") "r1" else "" },
        )

        val conflict = conflicts.single()
        assertEquals("dtwsl", conflict.trigger)
        assertEquals("r1", conflict.remoteId)
    }

    @Test
    fun `C3-7 采用云端正文时本地副本换个未占用的码`() {
        assertEquals("dtwslb", LocalRuleSyncPlan.backupTrigger(setOf("dtwsl"), "dtwsl"))
        assertEquals("dtwslc", LocalRuleSyncPlan.backupTrigger(setOf("dtwsl", "dtwslb"), "dtwsl"))
        assertNull("12 位再加后缀就超限", LocalRuleSyncPlan.backupTrigger(setOf("abcdefghijkl"), "abcdefghijkl"))
    }

    @Test
    fun `C3-9 冲突指纹对已转义正文幂等`() {
        val raw = LocalRuleSyncPlan.fingerprintOf("行1\n行2")
        val escaped = LocalRuleSyncPlan.fingerprintOf("行1\\n行2")

        assertEquals("联网正文与落盘正文必须算出同一个指纹", raw, escaped)
    }
}
