# AI 建议回复（快捷短语优先 + 大模型 + 本地学习）

点击工具栏的 **AI** 键时，输入法按下面顺序给出「可以直接发出去的一句话」：

1. **快捷短语优先**：用输入框里的文本去匹配内置 / 远端 / 用户的快捷短语，命中就直接把短语当建议回复展示（最多 3 条），不联网、不花钱；
2. **复用已采纳回复**：这次输入与某条历史采纳记录的字符相似度 ≥ 0.8 时，直接给那条（是用户自己写过的，采纳率高、零延迟）；
3. **大模型生成**：以上都没命中，才按设置页配置的模型来源生成一条回复；
4. **学习**：用户采纳过的回复记在本机，下次生成时作为 few-shot 例子带进提示词，让输出贴近用户自己的口气。

## 数据流

```
AI 键 ─► startAiReply()
          │
          ├─ matchQuickPhrases(message)        IO 线程读规则文件（内置 + 远端 + 用户）
          │     └─ 命中 ─► 候选条展示 1..3 条短语  ─► 结束（不调模型、不计采纳率）
          │
          └─ AiProvider.isDirect ?
                ├─ 否（csBaby 云端）─► CsBabyClient.generateReply()
                └─ 是（OpenAI 兼容）─► DirectLlmClient.generate()
                                           └─ 提示词 = 系统约束 + 采纳过的回复（few-shot）+ 本次输入
          │
          └─ 采纳（点结果行 / 空格 / 点候选）─► AiLearningStore.recordAccepted()
                                             └─ 生成前原文未变才 deleteSurroundingText（AiSourceGuard）
```

## 模型配置（设置页 → AI 建议）

内置预设都走 **OpenAI 兼容协议**（`POST {baseUrl}chat/completions`），换厂商只改 Base URL / 模型 / Key：

| 来源 | Base URL | 默认模型 |
| --- | --- | --- |
| csBaby 云端（默认） | 由后端决定，需登录 | — |
| OpenAI | `https://api.openai.com/v1/` | `gpt-4o-mini` |
| DeepSeek | `https://api.deepseek.com/v1/` | `deepseek-chat` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1/` | `qwen-plus` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4/` | `glm-4-flash` |
| Kimi（Moonshot） | `https://api.moonshot.cn/v1/` | `moonshot-v1-8k` |
| 自定义 | 自填（自建网关 / Ollama `/v1`） | 自填 |

切换来源会**清空** Base URL 与模型名，回落到新来源的预设 —— 否则从 OpenAI 切到 DeepSeek 会带着 `openai.com` 的地址请求，直接鉴权失败。

配置不全时点 AI 键会提示「请先在设置页配置大模型」，而不是抛一个看不懂的 HTTP 错误。

## 结果面板与「一步填入」（VOCA-65 条目 5a）

AI 键的结果同时进两处，**同一份 `candidates`**，所以两条上屏入口行为一致：

| 入口 | 动作 | 关闭面板 |
| --- | --- | --- |
| 点结果行 | 该条原文一步上屏 | 是 |
| 长按结果行 / 点行尾「…」 | 进详情页看全文，再点「上屏」 | 是 |
| 候选条点击 / 空格 / 回车 | 上屏（既有入口，保留） | — |

两条路径都走 `IspeechImeService.commitAiReply(index)`，**生成前原文的替换校验只有一份**
（`AiSourceGuard.shouldReplace`）：只有光标前 `aiSourceLen` 个字逐字等于生成时的原文才删原文，
用户中途改了输入框就只插入、不删除。面板里不得再复制一份删除逻辑。

> 历史坑：`AiReplyPanel` 曾在布局里声明、服务侧从未 `findViewById`，是死代码；5a 之前 AI 结果
> 实际只走候选条。本次重新接线，改动才可被真机点验证。

真机判定：点行后输入框出现该条原文且面板收起、长按进详情、改了输入框再点不吞字。

### adb 复现（AC-5a.1~5a.5）

```bash
# 设备先装 debug 包
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 短语模式：3 条短语命中，离线可跑（验 5a.1 / 5a.2 / 5a.3 / 5a.4）
python3 tools/verify/voca628_ai_one_tap_adb.py --serial emulator-5554

# 模型模式：本机起 OpenAI 兼容假端点，adb reverse 进设备，验 5a.5 采纳率与 few-shot
python3 tools/verify/voca628_ai_one_tap_adb.py --serial emulator-5554 --mock-llm
```

脚本按截图色块定位输入法窗口（`uiautomator` 导不出 IME 窗口层级），逐条打印 `PASS` / `FAIL` / `SKIP`
与判定依据（输入框文本 + `ai_adopt` 日志 + IME 窗口高度变化）。`--strict-device` 强制要求
真机（AC-5a.3 要真机时序，模拟器证据只作补充）。模型模式不用真 Key、不出站访问外部厂商。

三档读法（`SKIP` 是为了不把「没取证」混进「通过」）：

- `PASS`：拿到了本条 AC 要求的实测证据；
- `SKIP`：前置不满足，本次没取证。两种情形——快捷短语命中按设计不计采纳率（5a.5 需 `--mock-llm`）；
  非真机跑 5a.3（issue 明文要求真机，加 `--strict-device` 才算 PASS）。`SKIP` 不算失败，也不算通过；
- `FAIL`：拿到了证据且不符合 AC。`5a.0`（AI 面板开不出来）是硬门，失败即返回非 0、后续用例不执行。

## 学习与采纳率

- 存储：`filesDir/ai_learning.txt`（`次数 <TAB> 输入 <TAB> 采纳的回复`，转义规则与 `quick_phrases_user.txt` 一致）；
  统计在 `filesDir/ai_learning_stats.txt`（`生成次数 <TAB> 采纳次数`）。
- 只记**模型产出**且被采纳的建议；快捷短语命中的不计入采纳率，也不进学习语料。
- 示例排序：与本次输入的字符重合度高的优先，其次按使用次数；语料上限 200 条，提示词里最多带 5 条。
- 复用判定：字符集合 Jaccard 相似度 ≥ 0.8 才直接复用，同分取使用次数多的；低于阈值一律回退模型生成，宁可多花一次调用也不给不贴题的旧回复。
- 设置页可查看「已生成 / 采纳 / 采纳率」，并一键清空。
- **全部数据只存本机，不上传云端。**

## 已知边界

- 学习靠 few-shot，不做微调；语料很少时效果与手写提示词一致。
- 直连请求没有流式输出，等整条返回再展示；单次上限 256 tokens。
- API Key 以明文存于应用私有 DataStore（`ispeech_settings`），仅本机可见，不做额外加密。
- 快捷短语匹配是**双向包含**：短语正文出现在输入里，或输入是短语正文的一段都算命中；单字短语被排除，避免噪声。

## 验证

```bash
python3 tools/verify/check_ai_suggest.py   # 51 项：预设 / 存储 / 生成链路 / 学习链路 / 设置入口
python3 tools/verify/check_quick_trigger.py # 65 项：快捷短语与空格长按

# 真机 / 模拟器黑盒复现（AC-5a.1~5a.5），需设备 + debug 包
python3 tools/verify/voca628_ai_one_tap_adb.py --serial <device>
```