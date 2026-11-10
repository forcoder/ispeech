# Spec Delta

## Purpose

定义输入法安全准则，确保用户数据隐私和系统安全。

## ADDED Requirements

### Requirement: 敏感数据处理
系统 MUST 不记录、不存储、不传输任何用户输入的敏感数据（如密码、银行卡号、身份证号）。

#### Scenario: 敏感数据输入测试
- **WHEN** 用户输入包含 "123456789012345678" 的字符串
- **THEN** 系统 MUST 不记录该字符串，日志中 MUST 不包含任何敏感模式匹配

### Requirement: 权限最小化
系统 MUST 仅请求必要的 Android 权限，并在运行时动态请求权限，提供清晰的权限使用说明。

#### Scenario: 权限请求测试
- **WHEN** 首次安装并启用输入法
- **THEN** 系统 MUST 只请求 READ_EXTERNAL_STORAGE 和 RECORD_AUDIO 权限（如果需要语音功能）

### Requirement: 数据加密
系统 MUST 对本地存储的用户词典和设置数据进行加密，使用 Android Keystore 系统。

#### Scenario: 数据加密测试
- **WHEN** 检查本地存储的 user_dict.txt 文件
- **THEN** 文件内容 MUST 是加密的，无法通过文本编辑器直接读取明文

### Requirement: 安全编码实践
系统 MUST 遵循 OWASP MASVS 标准，避免常见安全漏洞（如注入、XSS、不安全的组件暴露）。

#### Scenario: 安全扫描测试
- **WHEN** 运行 MobSF 安全扫描
- **THEN** 扫描结果 MUST 不显示高风险或严重风险漏洞