package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：点候选词上屏后再点联想词，第一个字不能重复。
 *
 * 真实缺陷：「你」上屏后联想出「你好」，点它整词上屏得到「你你好」；
 * 联想词应当只补上多出来的部分，让已上屏的字接续。
 */
class AssociationCommitTest {

    @Test
    fun `single char commit continues with association word`() {
        assertEquals("好", AssociationCommit.textToCommit("你", "你好"))
        assertEquals("们", AssociationCommit.textToCommit("你", "你们"))
    }

    @Test
    fun `association word starting with whole committed word drops the overlap`() {
        assertEquals("吗", AssociationCommit.textToCommit("你好", "你好吗"))
        assertEquals("预报", AssociationCommit.textToCommit("天气", "天气预报"))
    }

    @Test
    fun `association word without overlap is committed whole`() {
        // 用户词库的「包含」联想：「气」→「天气」并不以「气」开头，整词上屏
        assertEquals("天气", AssociationCommit.textToCommit("气", "天气"))
        // 上屏的是词组时保持整词接续（「天气」+「气温」→「天气气温」）
        assertEquals("气温", AssociationCommit.textToCommit("天气", "气温"))
    }

    @Test
    fun `candidate identical to committed text is not written twice`() {
        assertEquals("", AssociationCommit.textToCommit("天气", "天气"))
        assertEquals("", AssociationCommit.textToCommit("你", "你"))
    }

    @Test
    fun `no committed text keeps candidate untouched`() {
        assertEquals("你好", AssociationCommit.textToCommit("", "你好"))
        assertEquals("", AssociationCommit.textToCommit("你", ""))
    }
}
