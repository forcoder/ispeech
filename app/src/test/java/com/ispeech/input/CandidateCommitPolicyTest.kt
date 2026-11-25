package com.ispeech.input

import java.io.File
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
 *
 * 不变量分两层，缺一不可（质检打回项）：policy 自身的两条分支语义，加上服务侧调用点真的
 * 按 policy 传值。IspeechImeService 依赖 Android 输入框架，JVM 单测实例化不了，所以调用点
 * 锁在源码结构层（同 KeyboardViewModelInitOrderTest 的做法）。只测 policy 的话，把
 * `pickCandidate` 改回 `allowAssociation = false` 单测仍然全绿，这条回归就只由
 * `check_crosscut.py` 第 13 项守护。
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

    @Test
    fun `调用点：非联想分支接 policy 而不是写死 false`() {
        val head = nonAssociationBranch()
        assertFalse(
            "pickCandidate 非联想分支又把联想写死成 false（VOCA-127 的回归写法），" +
                "点选候选 / 数字键 1-9 选候选上屏后不再出联想",
            ALLOW_ASSOCIATION_OFF.containsMatchIn(head),
        )
        assertTrue(
            "pickCandidate 非联想分支没把 allowAssociation 交给 CandidateCommitPolicy.allowAssociation(wasAssociation)，" +
                "点选后联想存活只剩 check_crosscut 守护",
            Regex("""CandidateCommitPolicy\.allowAssociation\(\s*wasAssociation\s*\)""").containsMatchIn(head),
        )
    }

    @Test
    fun `调用点：policy 的入参就是当次联想态`() {
        assertTrue(
            "pickCandidate 里 wasAssociation 不再取自 associationActive，非联想分支的判断口径被改动",
            Regex("""val\s+wasAssociation\s*=\s*associationActive\b""").containsMatchIn(pickCandidateBody()),
        )
    }

    @Test
    fun `调用点：联想分支上屏后仍不接续联想`() {
        // 反向写死 true 会让联想词上屏后再叠一层联想，同样违反 VOCA-121 口径
        val tail = stripComments(pickCandidateBody().substringAfter(ASSOCIATION_ANCHOR_CALL))
        assertTrue(
            "pickCandidate 联想分支的上屏调用被改动，需人工确认（口径：点联想词后不再接续联想、来源标 ASSOCIATION）",
            ALLOW_ASSOCIATION_OFF.containsMatchIn(tail) && tail.contains("CommitSource.ASSOCIATION"),
        )
    }

    /** pickCandidate 的正文，与 check_crosscut 第 13 项同口径：收到 4 空格缩进的收尾大括号为止。 */
    private fun pickCandidateBody(): String =
        Regex("""private fun pickCandidate\(.*?\n    }""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.value
            ?: error("IspeechImeService 里找不到 pickCandidate，测试口径需要更新")

    /** 非联想分支 = 联想锚点读取之前的代码；注释里会写这次回归的写法，判定前先剥掉。 */
    private fun nonAssociationBranch(): String {
        val body = pickCandidateBody()
        assertTrue(
            "pickCandidate 里找不到 $ASSOCIATION_ANCHOR_CALL 分界点，非联想分支的判定口径需要更新",
            body.contains(ASSOCIATION_ANCHOR_CALL),
        )
        return stripComments(body.substringBefore(ASSOCIATION_ANCHOR_CALL))
    }

    private fun stripComments(code: String): String = LINE_COMMENT.replace(code, "")

    private companion object {
        const val ASSOCIATION_ANCHOR_CALL = "getTextBeforeCursor"
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val ALLOW_ASSOCIATION_OFF = Regex("""allowAssociation\s*=\s*false""")
        val source: String by lazy {
            val relative = "src/main/java/com/ispeech/input/IspeechImeService.kt"
            val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
                ?: error("找不到 IspeechImeService.kt，工作目录 ${File(".").absolutePath}")
            file.readText()
        }
    }
}
