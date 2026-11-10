package com.ispeech.agent

import android.util.Log

/**
 * iSpeech 智能体调用器
 * 根据任务描述自动选择并调用合适的 sub-agent
 */
class AgentDispatcher {

    companion object {
        private const val TAG = "AgentDispatcher"

        /**
         * 根据任务描述选择合适的智能体类型
         */
        fun selectAgent(taskDescription: String): String {
            val lowerDesc = taskDescription.lowercase()

            // 优先检测 IME 领域关键词（最高置信度）
            if (lowerDesc.contains("候选") ||
                lowerDesc.contains("ime") ||
                lowerDesc.contains("lifecycle") ||
                lowerDesc.contains("onstartinput") ||
                lowerDesc.contains("onfinishinput") ||
                lowerDesc.contains("键盘") ||
                lowerDesc.contains("layout") ||
                lowerDesc.contains("view")) {
                Log.d(TAG, "选择 ime-expert agent：检测到 IME 领域关键词")
                return "ime-expert"
            }

            // 检测架构设计相关关键词
            if (lowerDesc.contains("设计") ||
                lowerDesc.contains("架构") ||
                lowerDesc.contains("plan") ||
                lowerDesc.contains("review") ||
                lowerDesc.contains("评审") ||
                lowerDesc.contains("方案")) {
                Log.d(TAG, "选择 plan agent：检测到架构设计关键词")
                return "plan"
            }

            // 检测代码探索相关关键词
            if (lowerDesc.contains("搜索") ||
                lowerDesc.contains("查找") ||
                lowerDesc.contains("探索") ||
                lowerDesc.contains("search") ||
                lowerDesc.contains("explore") ||
                lowerDesc.contains("find") ||
                lowerDesc.contains("locate") ||
                lowerDesc.contains("分析") ||
                lowerDesc.contains("analyze")) {
                Log.d(TAG, "选择 explore agent：检测到代码探索关键词")
                return "explore"
            }

            // 检测测试相关关键词
            if (lowerDesc.contains("测试") ||
                lowerDesc.contains("验收") ||
                lowerDesc.contains("benchmark") ||
                lowerDesc.contains("performance") ||
                lowerDesc.contains("test") ||
                lowerDesc.contains("verify") ||
                lowerDesc.contains("验证")) {
                Log.d(TAG, "选择 testing-expert agent：检测到测试相关关键词")
                return "testing-expert"
            }

            // 检测本地化相关关键词
            if (lowerDesc.contains("拼音") ||
                lowerDesc.contains("五笔") ||
                lowerDesc.contains("混输") ||
                lowerDesc.contains("简繁") ||
                lowerDesc.contains("方言") ||
                lowerDesc.contains("locale") ||
                lowerDesc.contains("language") ||
                lowerDesc.contains("多语言")) {
                Log.d(TAG, "选择 localization-expert agent：检测到本地化相关关键词")
                return "localization-expert"
            }

            Log.d(TAG, "选择 general-purpose agent：默认选择")
            return "general-purpose"
        }

        /**
         * 调用智能体
         */
        fun dispatch(taskDescription: String) {
            val agentType = selectAgent(taskDescription)
            Log.i(TAG, "调度任务到 $agentType agent：$taskDescription")

            // 在实际实现中，这里会触发对应的 sub-agent
            // 例如：Agent(type = agentType, prompt = taskDescription).run()

            // 当前为模拟模式
            Log.i(TAG, "[模拟] $agentType agent 已准备就绪，将处理任务：$taskDescription")
        }
    }
}