package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：点候选词上屏后再点联想词，第一个字不能重复。
 *
 * 真实缺陷：「你」上屏后联想出「你好」，点它整词上屏得到「你你好」；
 * 多字候选更常见：「指导老师」上屏后联想出「师专」，整词上屏得到「指导老师师专」。
 * 联想词只补上不相重的部分，让已上屏的文字接续。
 */
class AssociationCommitTest {

    @Test
    fun `single char commit continues with association word`() {
        assertEquals("好", AssociationCommit.textToCommit("你", "你好"))
        assertEquals("们", AssociationCommit.textToCommit("你", "你们"))
    }

    @Test
    fun `multi char commit drops the last char repeated by the association word`() {
        // 联想按末字查（「师」），候选开头就是这个字
        assertEquals("专", AssociationCommit.textToCommit("指导老师", "师专"))
        assertEquals("傅", AssociationCommit.textToCommit("指导老师", "师傅"))
        assertEquals("理", AssociationCommit.textToCommit("知道", "道理"))
        // 重合不止一个字时按最长重合裁剪：「老师好」要保留「好」
        assertEquals("好", AssociationCommit.textToCommit("指导老师", "老师好"))
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
        assertEquals("认知", AssociationCommit.textToCommit("知", "认知"))
    }

    @Test
    fun `candidate identical to committed text is not written twice`() {
        assertEquals("", AssociationCommit.textToCommit("天气", "天气"))
        assertEquals("", AssociationCommit.textToCommit("你", "你"))
        assertEquals("", AssociationCommit.textToCommit("指导老师", "指导老师"))
    }

    @Test
    fun `long committed text only looks back a short anchor window`() {
        // 19 字：完全相同必须整体判重，不能因窗口截断而残留半截
        val long = "中华人民共和国全国人民代表大会常务委员会"
        assertEquals("", AssociationCommit.textToCommit(long, long))
        // 窗口只管裁剪长度，不影响正常接续
        assertEquals("傅", AssociationCommit.textToCommit(long + "师", "师傅"))
    }

    @Test
    fun `no memory of last commit falls back to the text before the cursor`() {
        // 宿主重启输入连接会清掉 lastCommit，此时以输入框里的真实文字为锚点
        assertEquals("专", AssociationCommit.textToCommit("", "师专", "指导老师"))
        assertEquals("", AssociationCommit.textToCommit("", "师专", "师专"))
        // 输入框也是空的：原样上屏
        assertEquals("师专", AssociationCommit.textToCommit("", "师专", ""))
        // lastCommit 有值时优先信它
        assertEquals("好", AssociationCommit.textToCommit("师傅", "师傅好", "别的内容"))
    }

    @Test
    fun `no committed text keeps candidate untouched`() {
        assertEquals("你好", AssociationCommit.textToCommit("", "你好"))
        assertEquals("", AssociationCommit.textToCommit("你", ""))
    }
}
