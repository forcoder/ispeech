# Spec Delta

## Purpose

定义开发流程约束，确保代码质量和团队协作效率。

## ADDED Requirements

### Requirement: 代码风格指南
系统 MUST 遵循 Kotlin 编码规范，包括命名约定（camelCase）、缩进（4 空格）和文件末尾空行要求。

#### Scenario: 代码风格检查
- **WHEN** 运行 ktlint 检查
- **THEN** 所有 Kotlin 文件 MUST 通过代码风格检查，无格式错误

### Requirement: 提交信息规范
系统 MUST 使用 Conventional Commits 规范，提交信息格式为 "type(scope): subject"，其中 type 为 feat、fix、docs、style、refactor、test、chore、revert。

#### Scenario: 提交信息验证
- **WHEN** 提交代码到 GitHub
- **THEN** CI 流水线 MUST 验证提交信息格式，不符合规范的提交 MUST 被拒绝

### Requirement: CI/CD 集成
系统 MUST 集成 GitHub Actions CI/CD 流水线，自动运行测试、构建和代码质量检查。

#### Scenario: CI/CD 流水线测试
- **WHEN** 推送代码到 main 分支
- **THEN** CI 流水线 MUST 自动运行所有测试套件，构建 APK，并生成代码质量报告

### Requirement: 文档更新要求
系统 MUST 在每次功能变更后更新相关文档，包括 README.md、ARCHITECTURE.md 和 CHANGELOG.md。

#### Scenario: 文档更新验证
- **WHEN** 合并包含新功能的 PR
- **THEN** CI 流水线 MUST 检查相关文档是否已更新，未更新的 PR MUST 被标记为需要修改

### Requirement: 验收用例先行（ATDD）
系统 MUST 强制执行验收用例先行开发流程，所有新功能必须先编写验收用例并通过评审，才能开始编码。

#### Scenario: ATDD 流程验证
- **WHEN** 创建新功能分支
- **THEN** PR 描述中 MUST 包含链接到 `features/` 目录下的验收用例文件，且该文件 MUST 通过 Gherkin 语法检查

### Requirement: 测试驱动开发（TDD）
系统 MUST 要求核心组件（QuickPhraseEngine、PhraseSearch、CandidateView）采用 TDD 开发，每个新功能必须先编写单元测试，再编写实现代码。

#### Scenario: TDD 实施验证
- **WHEN** 运行 TDD 合规性检查
- **THEN** 新功能的提交记录中 MUST 先出现测试代码，后出现实现代码，且测试覆盖率报告 MUST 显示新增测试覆盖了所有新逻辑

### Requirement: 验收用例模板
系统 MUST 提供标准验收用例模板，包含民宿词库、IME 生命周期、候选词排序等典型场景。

#### Scenario: 民宿词库验收用例
- **WHEN** 开发民宿词库功能
- **THEN** 必须使用标准模板，包含以下用例：
  - Given 用户输入 "min su"，When 查看候选词，Then "民宿" 必须出现在首位（权重9999）
  - Given 用户输入 "yong chi"，When 查看候选词，Then "泳池" 必须出现在首位（权重9999）
  - Given 用户输入 "pao chi"，When 查看候选词，Then "泡池" 必须出现在首位（权重9999）