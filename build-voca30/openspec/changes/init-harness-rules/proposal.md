# Proposal

## Why

iSpeech 是一个 Android 输入法应用，需要建立一套业界标准的 harness 规则来确保代码质量、性能和安全性。当前项目缺乏统一的质量保障体系，包括测试规范、性能要求、安全准则和开发流程约束。通过初始化 harness 规则，可以为项目建立坚实的质量基础，确保输入法的核心功能（如候选词生成、IME 生命周期管理、键盘布局等）始终保持高质量和高性能。

## What Changes

- 创建适用于 Android 输入法项目的 harness 规则体系
- 定义 Android 测试规范（单元测试、仪器化测试、UI 测试）
- 设定性能要求（IME 响应时间、内存使用限制）
- 制定安全准则（敏感数据处理、权限管理）
- 建立开发流程约束（代码风格、提交规范、CI/CD 集成）

## Capabilities

### New Capabilities
- `android-testing`: Android 测试框架和规范，包括针对 IME 服务、候选词视图和键盘布局的测试策略
- `ime-performance`: 输入法性能要求，包括响应时间、内存使用和电池消耗指标
- `ime-security`: 输入法安全准则，涵盖数据隐私、权限管理和安全编码实践
- `dev-process`: 开发流程约束，包括代码风格指南、提交信息规范和 CI/CD 集成要求

### Modified Capabilities
- `<none>`: 当前项目没有现有的 harness 规则需要修改

## Impact

- 影响整个 iSpeech 项目代码库
- 需要更新构建配置以集成新的测试框架
- 需要为现有核心组件（IspeechImeService、CandidateView、KeyboardLayouts 等）添加测试覆盖
- 需要更新开发文档以包含新的 harness 规则说明