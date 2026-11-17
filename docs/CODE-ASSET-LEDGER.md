# 代码资产台账（Code Asset Ledger）

> 维护人：重构清理大师（Multica agent `a0946453-a67c-483e-8d79-45b56b0dd09d`）
> 用途：给代码库熵减提供可控抓手。每次巡检和每次清理都必须更新本表。
> 更新节奏：随巡检 / 清理 MR 同步更新；不另建第二份台账。

## 状态与证据等级约定

| 状态 | 含义 |
|---|---|
| `active` | 正常维护：在生产调用链上，**或已由人类/文档/测试声明“在用”** |
| `疑似腐化` | 静态检索无调用，但缺运行时观测证据，**只报告、不删除** |
| `待回收` | 四级证据齐全（静态+产物+调用链+近30天运行时），可提交删除 |
| `已回收` | 已删除并合入 main |

证据等级：`确认`=四级齐全；`疑似`=缺运行时观测；`待证`=只有静态检索。
「最后被调用」取「近 30 天运行时观测」结果；拿不到运行时观测的一律写 `不可得`，不得推断。
「最后被修改」取 `git log -1 --date=short -- <target>` 的文件粒度提交日期与短哈希。

**硬规则（2026-11-17 人类裁定，长期有效）**：凡文档 / 文案 / 资源 / 测试声称「在用」的能力，**静态零引用不得作为删除依据**。清理必须先证「该项无任何在用声明 + 无装配入口」，再走四证据流程。

## 模块清单

| 模块 | Owner | 对外入口 | 上下游依赖 | 最后被修改 | 最后被调用 | 状态 | 关联 issue |
|---|---|---|---|---|---|---|---|
| `input/IspeechImeService`（2047 行 IME 主服务） | 后端/输入法 | `AndroidManifest` IME 组件 `com.ispeech.ime/com.ispeech.input.IspeechImeService` | engine / data / settings 全链路 | 2026-11-15 fa57a07 | 不可得 | active | VOCA-185 |
| `input/SoftKeyboardView`（1007 行，结构腐化：超长文件） | 前端 | 由 `IspeechImeService` 持有（字段 `:196` + `findViewById :279`），另有 CandidateView/VoicePanelView/KeyDef/KeyboardLayouts/T9Layouts 共 6 文件引用 | keyboard 布局 / KeyDef | 2026-11-14 f728231 | 不可得 | active（结构腐化待抽取，非无调用） | 本轮巡检 #P2 |
| `input/VoskSpeechEngine` + `data/VoskModelStore` | 后端 | `IspeechImeService` 按 `AsrEngine.VOSK` 分支接线 | vosk.android / AsrSettings | 2026-11-14 07214d6 | 不可得 | active | VOCA-voice-offline-1114 |
| `input/FunAsrSpeechEngine`（359 行）+ `data/FunAsrModelStore`（442 行，合计 801） | 后端 | **在用**（人类 2026-11-17 裁定）；主干未见装配入口，装配口径待查（VOCA-446 架构师核查） | sherpa-onnx / commons-compress | 2026-11-09 904a98d / 2026-11-10 3d62d9d | 不可得 | **active（在用）** | VOCA-446 装配口径核查 |
| `agent/AgentDispatcher` | 后端 | **在用**（`AgentDispatcherTest` + `.agents/agents.yaml` 声称；生产零引用，装配口径待查） | 无 | 2026-11-17 f6dd415（revert 恢复；内容源自 2026-11-09 904a98d，本轮曾删后 revert） | 不可得 | active（在用声明待核对装配入口） | VOCA-446 一并核查 |
| `engine/`（Pinyin/Wubi/CellDict/QuickPhrase/Lookup/UserDict） | 后端 | 由 `CompositeEngine` 聚合，`IspeechImeService` 调用 | data 词典 Store | 2026-11-15 fa57a07 | 不可得 | active | VOCA-185 |
| `data/`（词典/同步/统计/剪贴板/AI/认证） | 后端 | `AppContainer` 统一装配 | remote API / datastore | 2026-11-15 fa57a07 | 不可得 | active | VOCA-185 |
| `data/remote/`（Auth/Chat/Ai/Sync ApiService + Dtos） | 后端 | `CsBabyClient` / `DirectLlmClient` | retrofit / gson | 2026-11-09 904a98d | 不可得 | active | — |
| `settings/`（Compose 设置页 + ViewModel） | 前端 | `IspeechNavGraph` 路由表 | SettingsStore / data | 2026-11-15 fa57a07 | 不可得 | active | VOCA-185 |
| `settings/store/SettingsStore` | 前端/后端契约 | 各 ViewModel + `IspeechImeService` | datastore | 2026-11-10 2a1883e | 不可得 | active | — |
| `ui/theme/`（Color/Theme/Type） | UI | `IspeechTheme` | material3 | 2026-10-17 72bb547 | 不可得 | active | — |
| `di/AppContainer` | 后端 | `IspeechApp` 全局装配 | 全 data 层 | 2026-11-14 3edfc83 | 不可得 | active | VOCA-voice-offline-1114 |
| `tools/`（dev/deploy/verify/pinyin 脚本） | SRE/测试 | 独立脚本，不进入 APK | — | 2026-11-15 fa57a07 | 不可得 | active | VOCA-dev-workflow |

## 重点条目

### <a id="funasr"></a> FunASR 离线语音引擎（active，在用）

- 文件：`input/FunAsrSpeechEngine.kt`（359 行）、`data/FunAsrModelStore.kt`（442 行，含 `AsrDownloadPolicy`），合计 801 行。
- **人类 2026-11-17 裁定：FunASR 是本项目当前在用的语音识别方案，不是死代码、不是“疑似未使用”。** 上轮报告把它列为“未使用资产（未接线）”的结论作废。
- 代码层现状（如实记录，不据此删除）：主干未见 `IspeechImeService` 的装配入口，`AsrEngine` 枚举只有 `SYSTEM / CUSTOM / VOSK`，装配口径待 VOCA-446 架构师核查。
- 本轮起 6 项不得删除、不得降级依赖：`FunAsrSpeechEngine.kt`、`FunAsrModelStore.kt`、`FunAsrModelStoreTest.kt`、`strings.xml` 的 `asr_engine_funasr*` 文案、`docs/voice-input.md`、`docs/model-host-runbook.md` / `docs/asr-model-source-migration-log.md`。
- 关联：装配口径核查走 VOCA-446；本项不再出现在“需人裁决”清单。

## 变更日志

- 2026-11-17（VOCA-441 v3，回应质检第二轮 FAIL + 人类更正）：
  - `git revert 70f1e4b`（提交 `f6dd415`）：撤回 `agent/AgentDispatcher` 删除，两个文件恢复。
  - 新增硬规则「测试/文档/文案/资源声称在用的能力，静态零引用不得作删除依据」。
  - FunASR 由「疑似腐化」改为 `active（在用）`（人类裁定），6 项保护清单落地。
  - `SoftKeyboardView` 状态由「疑似腐化」改为 `active（结构腐化待抽取）`——它有 6 处真实调用，非无调用。
  - AgentDispatcher 状态由「已删除未合并」改为 `active（在用声明待核对装配入口）`，随 VOCA-446 核查。
  - 删除状态定义「已删除未合并」（revert 后无对应项）。
- 2026-11-17（VOCA-441 v2，回应质检第一轮 FAIL）：
  - 修正台账事实（SoftKeyboardView 日期、FunASR 行数、SettingsStore/tools 日期、AgentDispatcher 状态），同步删除两处文档误导指引。
- 2026-11-17（VOCA-441 v1）：初版台账建立。