# Proposal

## Why

用户手里有一份从搜狗 / csBaby 导出的「常用语」文件 `通用短语.csv`（235 行、GBK、61 KB，
108 条内容是多行文本），要求「导入到系统的常用词里，敲快捷字母就出对应候选」。

现状只能一条条手录：新增弹窗一次一条、快捷字母限 2–4 位，235 条要录几个小时，且多行内容
（户型介绍、好评返现模板）在弹窗里编辑体验极差。导入还要跨过三个坑，否则数据必然被糟蹋：

- 文件是 GBK，按 UTF-8 硬读会把中文全变成 ``；
- 内容字段带换行且用双引号包裹，按行 split 会把一条短语拆成多行、后续列全部错位；
- 源文件有 1 条无快捷字母、10 条同码重复、60 条超过 4 位（五笔态组合缓冲上限）、1 条单字母，
  不说明处理口径就会让用户以为「导入丢了 11 条」。

## What Changes

- 新增 `data/PhraseCsvImport.kt`：字节解码（严格 UTF-8 → GBK 回退）、RFC 4180 CSV 解析、
  行到 `QuickEntry` 的映射与逐类跳过计数。
- 快捷短语页新增「导入 CSV」入口（SAF `OpenDocument`，不申请存储权限），
  导入结果进内存列表并标记待保存，由既有「保存并同步」统一落盘 / 上传。
- 导入提示逐项报数：同码合并、缺快捷字母、非法码、已删除、空内容、超 4 位、单字母、覆盖已有。
- 新增 12 条中英文案；`docs/quick-phrase-enhancement.md` 追加第 13 节记录映射口径。

## Capabilities

### New Capabilities
- `quick-phrase-import`: 常用语 / 通用短语文件的导入能力，含编码探测、CSV 解析、快捷字母映射与跳过上报。

### Modified Capabilities
- `<none>`：现有手动新增 / 云端同步契约不变，导入复用其落盘与同步路径。

## Impact

- `app/src/main/java/com/ispeech/data/PhraseCsvImport.kt`（新增）
- `app/src/main/java/com/ispeech/settings/quick/QuickPhrasesViewModel.kt`（新增 `importPhraseCsv`）
- `app/src/main/java/com/ispeech/settings/QuickPhrasesScreen.kt`（新增入口按钮 + SAF launcher）
- `app/src/main/res/values{,-en}/strings.xml`（新增 13 条文案）
- `app/src/test/java/com/ispeech/data/PhraseCsvImportTest.kt`（新增 13 个用例）
- `tools/verify/check_phrase_csv_import.py`（新增离线校验，44 项）
- `docs/quick-phrase-enhancement.md`（新增第 13 节）

校验基线：`通用短语.csv` 以 `tools/verify/fixtures/general_phrases.csv` 入库（仅短语模板与 UUID / 时间戳，无个人信息）。
