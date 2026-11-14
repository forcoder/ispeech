# Tasks

## 1. 恢复工作区可编译基线

- [ ] 1.1 用 `git checkout HEAD -- app/src/main/java/com/ispeech/settings/QuickPhrasesScreen.kt` 还原 408 行完整文件，验证 `grep -c "fun QuickPhrasesScreen\|fun QuickEntryCard\|fun QuickEditorDialog" app/src/main/java/com/ispeech/settings/QuickPhrasesScreen.kt` 结果为 3
- [ ] 1.2 跑 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:compileDebugKotlin`，验证 BUILD SUCCESSFUL（还原前该命令应因 `QuickPhrasesScreen` 未解析而失败，可用作回归对照）
- [ ] 1.3 检查还原后的搜索栏确实接到 `QuickPhrasesViewModel.setKeyword()`：验证 `grep -n "setKeyword\|state.keyword" app/src/main/java/com/ispeech/settings/QuickPhrasesScreen.kt` 至少各出现一次；若 HEAD 版本的搜索栏缺失（`docs/quick-phrase-enhancement.md` §12.1 提到过 `QuickSearchBar`），在本任务内补上最小实现并在完成后更新该文档

## 2. 面板拼音组词输入链路

- [ ] 2.1 在 `IspeechImeService` 抽出统一入口 `handlePanelLetter(ch: Char)`，软键盘 `handleSoftKey()` 的 `CHAR` 分支与硬件 `onKeyDown()` 的 `KEYCODE_A..Z` 分支都改调它，验证 `grep -c "handlePanelLetter" app/src/main/java/com/ispeech/input/IspeechImeService.kt` ≥ 3（定义 1 + 两处调用）
- [ ] 2.2 在 `handlePanelLetter()` 实现 D5/D4：`mode == Mode.ENGLISH` 或 `pinyinEngine?.available != true` 或 `lookup()` 返回空 → 追加到 `panelKeyword`；否则字母进 `buffer` 并刷新候选，验证新增单测 / `tools/verify/check_input_logic.py` 断言覆盖这三条分支
- [ ] 2.3 改造面板内空格键语义（D3）：`buffer` 非空且有候选 → 选中当前候选并拼进 `panelKeyword`；否则维持 `insertPanelFirstMatch()`，验证两条路径各有断言
- [ ] 2.4 改造退格语义（D6）：`backspacePanelKeyword()` 先删 `buffer` 末字符、清理到空后再删 `panelKeyword` 末字符，验证 `tools/verify/check_input_logic.py` 新增断言通过
- [ ] 2.5 在 `openPanel()` / `closePanel()` 补齐 `buffer` / `candidates` / `pageIndex` 的清空（D2 的代价规避），验证关闭面板后 `buffer.isEmpty()`、重新打开面板候选条为空（源码断言 + 实机确认）
- [ ] 2.6 在 `tools/verify/check_input_logic.py` 增加「`panel != NONE` 分支必在 `return` 前处理完毕」的结构断言，验证脚本全绿且故意删除一处 `return` 时能报错

## 3. 面板搜索框组合态展示

- [ ] 3.1 `QuickPhrasePanel` 新增 `setComposing(pinyin: String, candidates: List<String>)`，搜索框在组合非空时显示拼音原文并用区别于已提交态的样式，验证 `render()` 中两种状态分支都可触发（源码断言）
- [ ] 3.2 `render()` 的标题行区分「组合中 / 已筛选」文案，验证 `strings.xml` 新增的组合态字符串被引用（`grep` 断言）
- [ ] 3.3 面板内候选条与短语列表同屏时的布局不溢出：验证实机截图，面板总高不超过既有 `maxPanelHeightDp` 约束

## 4. 中文匹配与排序校验

- [ ] 4.1 在 `tools/verify/check_phrase_usage.py` 增加中文关键词断言：正文 `北京市朝阳区发票专用` 搜 `朝阳` 命中且档位为「包含」、命中区间覆盖两字，验证脚本第 N 节输出 PASS
- [ ] 4.2 增加档位不变量断言：完全 > 前缀 > 包含 > 首字母在附加分（字段 ≤100、次数 ≤40、收藏 30）取满时仍成立，验证脚本断言总数增加并被 `docs/quick-phrase-enhancement.md` 记录
- [ ] 4.3 增加「单字母关键词不触发拼音首字母档」「纯 ASCII 文本不被缩写档刷屏」两条断言，验证脚本通过
- [ ] 4.4 跑 `python3 tools/verify/check_phrase_usage.py` 与 `python3 tools/verify/check_crosscut.py`，验证全部 PASS

## 5. 文档同步

- [ ] 5.1 更新 `docs/quick-phrase-enhancement.md`：新增「面板内中文组词输入」小节，说明双层状态（组合 / 已提交）、空格优先级、退格分层、无候选回落；验证文档中描述的行为与 `tasks` 2.2–2.4 的实现一致（逐条对照）
- [ ] 5.2 更新 `docs/sogou-feature-matrix.md` 第 64 行「快捷短语面板」条目，把「关键词匹配为智能联想（完全/前缀/包含/拼音首字母）」补充为「含面板内中文组词输入」；验证表格该行已更新
- [ ] 5.3 更新 `README.md` 中 `PhraseSearch.kt` 的说明行（第 90 行附近），如无相关内容则仅确认无需改动并记录

## 6. 集成验证

- [ ] 6.1 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug`，验证 BUILD SUCCESSFUL 且产出 `dist/app-debug.apk`
- [ ] 6.2 `adb install -r dist/app-debug.apk` 装机，实机走查：打开短语面板 → 拼音模式打 `fapiao` → 候选出现「发票」→ 选字后列表只剩命中条目 → 退格分层正确 → 关闭面板后主输入法拼音输入不受影响（截图存证）
- [ ] 6.3 实机走查英文模式：面板内按字母键走 ASCII 关键词（不组词），「中/英」徽标与行为一致
- [ ] 6.4 实机走查设置页：搜索栏输入中文即时过滤，且同一关键词下与面板结果顺序一致

## 7. 逐项复查

- [ ] 7.1 对照 `specs/quick-phrase-search/spec.md` 的每个 Scenario 逐条打勾，验证没有未被任何任务覆盖的断言
- [ ] 7.2 对照 `design.md` 的 D1–D8 逐条确认落地方式，验证没有偏离；偏离项记录原因

## Workflow follow-up

- 按项目 review 要求完成后归档该 change（`/opsx:archive`）。
- 归档后确认 `openspec/specs/quick-phrase-search/spec.md` 的 Purpose 已正确写入。