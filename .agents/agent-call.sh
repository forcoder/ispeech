#!/bin/bash

# iSpeech 智能体调用工具
# 根据任务类型自动选择并调用合适的 sub-agent

set -e

echo "=== iSpeech 智能体调用工具 ==="

echo "检测任务类型..."

# 从命令行参数获取任务描述
TASK_DESCRIPTION="$1"
if [ -z "$TASK_DESCRIPTION" ]; then
  echo "错误：请提供任务描述"
  echo "用法：$0 \"<任务描述>\""
  exit 1
fi

echo "任务描述：$TASK_DESCRIPTION"

echo "分析任务类型..."

# 检测任务类型
AGENT_TYPE="general-purpose"
REASON="默认通用智能体"

# 检测架构设计相关关键词
if echo "$TASK_DESCRIPTION" | grep -iqE "设计|架构|plan|review|review|review|review"; then
  AGENT_TYPE="plan"
  REASON="检测到架构设计关键词"
elif echo "$TASK_DESCRIPTION" | grep -iqE "搜索|查找|探索|search|explore|find|locate"; then
  AGENT_TYPE="explore"
  REASON="检测到代码探索关键词"
elif echo "$TASK_DESCRIPTION" | grep -iqE "研究|分析|执行|research|analyze|execute|debug|investigate"; then
  AGENT_TYPE="general-purpose"
  REASON="检测到通用任务关键词"
elif echo "$TASK_DESCRIPTION" | grep -iqE "claude|code|cli|command|help|usage"; then
  AGENT_TYPE="claude-code-guide"
  REASON="检测到 Claude Code 相关关键词"
elif echo "$TASK_DESCRIPTION" | grep -iqE "状态行|statusline|配置|setup|configure"; then
  AGENT_TYPE="statusline-setup"
  REASON="检测到状态行配置关键词"
fi

echo "选择智能体：$AGENT_TYPE ($REASON)"

echo "正在调用 $AGENT_TYPE 智能体..."

echo ""

echo "--- 调用详情 ---"
echo "Agent Type: $AGENT_TYPE"
echo "Task: $TASK_DESCRIPTION"
echo "Timestamp: $(date)"
echo "--- 结束 ---"

echo ""

echo "智能体已准备就绪！"
echo "在实际环境中，这将触发对应的 sub-agent 执行任务。"
echo "当前为模拟模式，显示调用逻辑。"
