# Spec Delta

## Purpose

定义验收用例先行（ATDD）规范，确保输入法功能行为符合用户预期。

## ADDED Requirements

### Requirement: 验收用例先行
系统 MUST 要求所有新功能开发前必须先编写验收用例，并通过验收用例评审后才能开始编码。

#### Scenario: 民宿词库验收用例
- **WHEN** 开发民宿词库功能
- **THEN** 必须先编写验收用例：
  - 输入 "min su" → 候选词首位显示 "民宿"（权重9999）
  - 输入 "yong chi" → 候选词首位显示 "泳池"（权重9999）
  - 输入 "pao chi" → 候选词首位显示 "泡池"（权重9999）

### Requirement: 验收用例格式
系统 MUST 使用 Gherkin 格式（Given-When-Then）编写验收用例，并存储在 `features/` 目录下。

#### Scenario: 验收用例格式验证
- **WHEN** 运行验收用例格式检查工具
- **THEN** 所有 `.feature` 文件 MUST 符合 Gherkin 语法，无解析错误

### Requirement: 验收用例执行
系统 MUST 在 CI 流水线中自动执行验收用例，并将失败用例标记为阻塞构建。

#### Scenario: 验收用例执行验证
- **WHEN** 推送包含新功能的代码
- **THEN** CI 流水线 MUST 自动运行 `features/` 下所有验收用例，失败时构建状态为 "failed"

### Requirement: 验收用例覆盖率
系统 MUST 要求每个新功能至少包含 3 个验收用例（正常流、边界流、异常流）。

#### Scenario: 验收用例覆盖率验证
- **WHEN** 运行验收用例覆盖率检查
- **THEN** 新功能的验收用例数量 MUST ≥ 3，且覆盖所有业务场景