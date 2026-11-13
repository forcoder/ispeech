# Tasks

## 1. 导入器

- [x] 1.1 `PhraseCsvImport.decode`：严格 UTF-8 → GBK 回退，去 BOM；对 GBK 基线文件断言中文完整
- [x] 1.2 `CsvTable.parse`：RFC 4180（引号字段 / 字段内换行 / `""` 转义 / 裸引号容错）；235 行解析为 235 条
- [x] 1.3 `PhraseCsvImport.parse`：表头别名定位、行映射、逐类跳过计数；真实文件导入 224 条
- [x] 1.4 `normalizeContent` / `labelOf` / `uniqueLabel`：换行规范化、码点截断、短名去重

## 2. 接线

- [x] 2.1 `QuickPhrasesViewModel.importPhraseCsv(uri)`：IO 线程读 SAF 流、合并用户规则、标记 dirty
- [x] 2.2 `QuickPhrasesScreen`：TopBar 下方新增「导入 CSV」按钮 + `OpenDocument` launcher（MIME 白名单）
- [x] 2.3 中英文案 13 条（含逐类跳过计数的提示拼接）

## 3. 验证

- [x] 3.1 `PhraseCsvImportTest`：13 个用例覆盖编码 / 多行 / 同码 / 单字母 / 长码 / 截断 / 落盘回读
- [x] 3.2 `tools/verify/check_phrase_csv_import.py`：44 项离线断言（含真实基线文件的条数与候选效果）
- [x] 3.3 全量离线自查：`check_static` / `check_crosscut` / `check_quick_trigger` / `check_phrase_usage` 等无新增失败
- [ ] 3.4 真机走查：安装 APK → 导入基线文件 → 敲 `ly` / `hxww` / `hxws` 验证候选（本次环境无 JDK / SDK，未能执行）
