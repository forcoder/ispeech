package com.ispeech.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点选候选后联想存活的回归测试（VOCA-650）。
 *
 * v1.6 回归：VOCA-127 在 [IspeechImeService.pickCandidate] 的非联想分支顺手写了
 * `allowAssociation = false`，于是点候选 / 数字键 1-9 选候选上屏后 `startAssociation()`
 * 不再被调用，候选条直接清空；空格 / 回车走 `commitTop` 仍出联想，
 * 现象就是「有时有、点选就没有」。
 *
 * 断言的口径：非联想分支必须接着联想；联想分支保持 VOCA-121 的既有语义（不再接续）。
 */
class CandidateCommitPolicyTest {

    @Test
    fun `点选普通候选后要接着联想`() {
        assertTrue(CandidateCommitPolicy.allowAssociation(associationActive = false))
    }

    @Test
    fun `点选联想词后不再接续联想`() {
        // VOCA-121 已验证的口径：联想词上屏后不叠下一层联想
        assertFalse(CandidateCommitPolicy.allowAssociation(associationActive = true))
    }

    @Test
    fun `两条分支互斥 不会同时允许或同时禁止`() {
        val pickNormal = CandidateCommitPolicy.allowAssociation(associationActive = false)
        val pickAssociation = CandidateCommitPolicy.allowAssociation(associationActive = true)
        assertTrue(pickNormal)
        assertFalse(pickAssociation)
    }
}