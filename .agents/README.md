# iSpeech Agency Agents

## 智能体目录

- **Plan Agent**: 架构设计专家 - 专注于输入法架构、性能优化和安全设计
- **Explore Agent**: 代码探索专家 - 专注于 Android 输入法代码库的深度搜索和分析
- **IME Expert Agent**: 输入法领域专家 - 专注于 IME 生命周期、候选词排序、键盘布局等核心功能
- **Testing Expert Agent**: 测试专家 - 专注于输入法测试策略、验收用例和性能基准
- **Localization Expert Agent**: 本地化专家 - 专注于多语言支持、拼音/五笔混合输入和文化适配

## 智能体能力矩阵

| 智能体 | 核心能力 | 典型任务 |
|---------|----------|----------|
| Plan Agent | 架构设计、技术评审、方案权衡 | 设计民宿词库集成方案、评审 IME 性能优化方案 |
| Explore Agent | 代码搜索、模式识别、依赖分析 | 查找所有候选词相关代码、分析 PhraseSearch 调用链 |
| IME Expert Agent | 输入法专业知识、Android IME API | 解决候选词显示问题、优化 IME 生命周期管理 |
| Testing Expert Agent | 测试策略、验收用例、性能基准 | 创建民宿词库验收用例、设计性能测试方案 |
| Localization Expert Agent | 多语言支持、拼音/五笔混合 | 实现拼音混输优化、添加方言支持 |

## 使用方法

1. 在命令行中：`.agents/agent-call.sh "设计民宿词库集成方案"`
2. 在代码中：`AgentDispatcher.dispatch("设计民宿词库集成方案")`
3. 在 IDE 中：使用快捷键触发对应智能体