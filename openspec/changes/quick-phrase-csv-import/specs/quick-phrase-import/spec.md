# Spec Delta

## Purpose

让用户把导出的常用语文件（CSV）一次性导入快捷短语，敲快捷字母即出候选，且导入过程不静默丢数据。

## ADDED Requirements

### Requirement: 编码探测
导入器 MUST 先按严格 UTF-8 解码，解码失败时回退 GBK，并 MUST 去掉 UTF-8 BOM。
MUST NOT 出现「GBK 文件按 UTF-8 硬解后中文变 `` 仍当作成功」的情况。

#### Scenario: Excel 另存的 GBK 文件
- **WHEN** 用户选择 GBK 编码的 `通用短语.csv`
- **THEN** 中文内容完整导入，候选条显示「鹭影」等原文，不出现替换字符

#### Scenario: 带 BOM 的 UTF-8 文件
- **WHEN** 用户选择带 BOM 的 UTF-8 CSV
- **THEN** 表头识别正常，首列名不带 BOM 残留，导入条数与无 BOM 时一致

### Requirement: CSV 结构解析
解析器 MUST 按 RFC 4180 处理双引号字段、字段内换行、`""` 转义的双引号，
MUST NOT 按行切分记录。表头 MUST 按别名识别（`INPUT_CODE` / `code` / `简写` / `编码`，
`CONTENT` / `内容` 等），列顺序无关。

#### Scenario: 内容字段含换行
- **WHEN** 某行 `CONTENT` 是引号包裹的三段多行文本
- **THEN** 该行解析为 1 条记录，后续行的列不串位，源文件 235 行解析为 235 条数据行

#### Scenario: 缺少必需列
- **WHEN** CSV 表头没有快捷字母列或内容列
- **THEN** 导入中止并提示缺少的列与当前表头，不写入任何规则

### Requirement: 快捷字母映射
每条记录 MUST 映射为一条 `QuickEntry`：`trigger` = 小写 `INPUT_CODE`（1–12 位小写字母），
`content` = 规范化后的 `CONTENT`，`kind` = `PHRASE`，`weight` = 100。
1 位字母 MUST 用 `EXACT` 模式（`prefix` 在「单字母不触发」守卫下永不命中），
2 位及以上 MUST 用 `PREFIX` 模式（超过 4 位仍导入，五笔态打前 4 位即可命中）。

#### Scenario: 单字母快捷码
- **WHEN** CSV 某行 `INPUT_CODE` 为 `j`
- **THEN** 导入为 `exact` 规则，输入 `j` 出该候选，输入 `jx` 不再命中该条

#### Scenario: 超过 4 位的快捷码
- **WHEN** CSV 某行 `INPUT_CODE` 为 `hxwswwjx`
- **THEN** 照常导入为 `prefix`，输入 `hxws`（前 4 位）即可命中该候选

### Requirement: 内容与短名规范化
`content` MUST 把 CRLF / 孤立 CR 统一为 `\n`、逐行去行尾空白、连续空行压成一行、
去首尾空行与首行前导空白；`label` MUST 取内容首个非空行并按码点截断到 20 位（超出加 `…`），
重名 MUST 追加 `·2` / `·3` 以保证全局唯一。

#### Scenario: 落盘回读一致
- **WHEN** 导入的 224 条规则经 `QuickPhraseCodec` 序列化后再解析
- **THEN** 每一行的 `content` 与导入时逐字相等，条数不变

#### Scenario: 短名重名
- **WHEN** 两条规则的内容首行相同
- **THEN** 第二条短名追加 `·2`，候选条不因 `display` 去重而互相吞并

### Requirement: 跳过上报
导入器 MUST 逐类统计并上报：缺快捷字母、快捷字母非法、同码重复、`IS_DELETE` 非 0、内容为空；
同码 MUST 保留先出现的一条。计数之和 MUST 能与源文件行数对上。

#### Scenario: 真实文件的分布
- **WHEN** 导入 235 行的源文件
- **THEN** 提示「已导入 224 条」，并列出同码合并 10 条、缺快捷字母 1 条、超 4 位 60 条、单字母 1 条

### Requirement: 导入入口与保存边界
设置页快捷短语 MUST 提供「导入 CSV」入口，通过 SAF 选择文件且 MUST NOT 申请存储权限。
导入结果 MUST 只进内存列表并标记未保存，由用户点「保存并同步」统一落盘与上传。

#### Scenario: 导入后不保存
- **WHEN** 用户导入后直接返回上一页、未点「保存并同步」
- **THEN** 本地文件与云端规则都不变，原有规则不受影响

#### Scenario: 重复导入同一文件
- **WHEN** 用户对同一份文件连续导入两次并保存
- **THEN** 结果与导入一次相同（同 `trigger` 覆盖），并提示覆盖了已有规则
