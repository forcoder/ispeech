package com.ispeech.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class AgentDispatcherTest {

    @Test
    fun testArchitectureDesignDetection() {
        // 测试架构设计相关关键词
        assertEquals("plan", AgentDispatcher.selectAgent("设计输入法架构"))
        assertEquals("plan", AgentDispatcher.selectAgent("架构评审"))
        assertEquals("plan", AgentDispatcher.selectAgent("plan architecture"))
        assertEquals("plan", AgentDispatcher.selectAgent("review design"))
    }

    @Test
    fun testCodeExplorationDetection() {
        // 测试代码探索相关关键词
        assertEquals("explore", AgentDispatcher.selectAgent("搜索候选词代码"))
        assertEquals("explore", AgentDispatcher.selectAgent("查找输入法服务"))
        assertEquals("explore", AgentDispatcher.selectAgent("探索键盘布局"))
        assertEquals("explore", AgentDispatcher.selectAgent("search for CandidateView"))
        assertEquals("explore", AgentDispatcher.selectAgent("explore keyboard code"))
    }

    @Test
    fun testGeneralTaskDetection() {
        // 测试通用任务相关关键词
        assertEquals("general-purpose", AgentDispatcher.selectAgent("研究性能优化方案"))
        assertEquals("general-purpose", AgentDispatcher.selectAgent("分析内存泄漏"))
        assertEquals("general-purpose", AgentDispatcher.selectAgent("执行单元测试"))
        assertEquals("general-purpose", AgentDispatcher.selectAgent("research security issues"))
        assertEquals("general-purpose", AgentDispatcher.selectAgent("analyze crash logs"))
        assertEquals("general-purpose", AgentDispatcher.selectAgent("execute performance test"))
    }

    @Test
    fun testClaudeHelpDetection() {
        // 测试 Claude Code 相关关键词
        assertEquals("claude-code-guide", AgentDispatcher.selectAgent("claude 命令帮助"))
        assertEquals("claude-code-guide", AgentDispatcher.selectAgent("code cli usage"))
        assertEquals("claude-code-guide", AgentDispatcher.selectAgent("how to use claude commands"))
    }

    @Test
    fun testStatuslineConfigDetection() {
        // 测试状态行配置相关关键词
        assertEquals("statusline-setup", AgentDispatcher.selectAgent("状态行配置"))
        assertEquals("statusline-setup", AgentDispatcher.selectAgent("statusline setup"))
        assertEquals("statusline-setup", AgentDispatcher.selectAgent("configure status line"))
    }

    @Test
    fun testDefaultSelection() {
        // 测试默认选择
        assertEquals("general-purpose", AgentDispatcher.selectAgent("处理用户请求"))
        assertEquals("general-purpose", AgentDispatcher.selectAgent("完成任务"))
        assertEquals("general-purpose", AgentDispatcher.selectAgent("do something"))
    }
}