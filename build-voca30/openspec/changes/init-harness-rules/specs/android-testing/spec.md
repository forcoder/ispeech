# Spec Delta

## Purpose

定义 Android 输入法应用的测试框架和规范，确保 IME 核心功能的质量和可靠性。

## ADDED Requirements

### Requirement: 单元测试覆盖核心组件
系统 MUST 为输入法核心组件（IspeechImeService、CandidateView、KeyboardLayouts）提供单元测试覆盖，测试覆盖率 MUST 达到 80% 以上。

#### Scenario: IspeechImeService 单元测试
- **WHEN** 运行 IspeechImeService 的单元测试套件
- **THEN** 所有测试用例必须通过，覆盖率报告 MUST 显示核心逻辑覆盖率达到 80% 以上

### Requirement: 仪器化测试验证 IME 生命周期
系统 MUST 提供仪器化测试来验证输入法服务的生命周期管理（onStartInput、onFinishInput、onDisplayCompletions 等），确保在各种 Android 版本上正常工作。

#### Scenario: IME 生命周期测试
- **WHEN** 在 Android 12 和 Android 14 设备上运行仪器化测试
- **THEN** 所有生命周期事件处理 MUST 正常工作，无崩溃或异常行为

### Requirement: UI 测试验证候选词显示
系统 MUST 提供 UI 测试来验证候选词显示逻辑，包括拼音匹配、权重排序和多语言支持。

#### Scenario: 候选词排序测试
- **WHEN** 输入 "min su" 并运行 UI 测试
- **THEN** 民宿相关候选词 MUST 出现在列表顶部，按权重降序排列

### Requirement: 测试环境配置
系统 MUST 提供标准化的测试环境配置，包括模拟器配置、测试数据和 CI/CD 集成脚本。

#### Scenario: CI/CD 测试集成
- **WHEN** 在 GitHub Actions CI 环境中运行测试
- **THEN** 所有测试套件 MUST 自动执行，失败时 MUST 发出警报