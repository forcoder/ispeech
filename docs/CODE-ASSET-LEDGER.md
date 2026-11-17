# 代码资产台账（Code Asset Ledger）

> 维护人：重构清理大师（Multica agent `a0946453-a67c-483e-8d79-45b56b0dd09d`）
> 用途：给代码库熵减提供可控抓手。每次巡检和每次清理都必须更新本表。
> 更新节奏：随巡检 / 清理 MR 同步更新；不另建第二份台账。

## 状态与证据等级约定

| 状态 | 含义 |
|---|---|
| `active` | 在生产调用链上，正常维护 |
| `疑似腐化` | 静态检索无调用，但缺运行时观测证据，**只报告、不删除** |
| `待回收` | 四级证据齐全（静态+产物+调用链+近30天运行时），可提交删除 |
| `已删除未合并` | 分支上已删除（待回收执行中），**尚未合入 main** |
| `已回收` | 已删除并合入 main |

证据等级：`确认`=四级齐全；`疑似`=缺运行时观测；`待证`=只有静态检索。
「最后被调用」取「近 30 天运行时观测」结果；拿不到运行时观测的一律写 `不可得`，不得推断。
「最后被修改」取 `git log -1 --date=short -- <target>` 的文件粒度提交日期与短哈希。

## 模块清单

| 模块 | Owner | 对外入口 | 上下游依赖 | 最后被修改 | 最后被调用 | 状态 | 关联 issue |
|---|---|---|---|---|---|---|---|
| `input/IspeechImeService`（2047 行 IME 主服务） | 后端/输入法 | `AndroidManifest` IME 组件 `com.ispeech.ime/com.ispeech.input.IspeechImeService` | engine / data / settings 全链路 | 2026-11-15 fa57a07 | 不可得 | active | VOCA-185 |
| `input/SoftKeyboardView`（1007 行键盘视图） | 前端 | 由 `IspeechImeService` 持有 | keyboard 布局 / KeyDef | 2026-11-14 f728231 | 不可得 | 疑似腐化（超长文件） | 本轮巡检 #P2 |
| `input/VoskSpeechEngine` + `data/VoskModelStore` | 后端 | `IspeechImeService` 按 `AsrEngine.VOSK` 分支接线 | vosk.android / AsrSettings | 2026-11-14 07214d6 | 不可得 | active | VOCA-voice-offline-1114 |
| `input/FunAsrSpeechEngine`（359 行）+ `data/FunAsrModelStore`（442 行，合计 801） | 后端 | **无入口**（`AsrEngine` 枚举无 FUNASR，`IspeechImeService` 无引用） | sherpa-onnx / commons-compress | 2026-11-09 904a98d / 2026-11-10 3d62d9d | 不可得 | **疑似腐化**（未接线 + 依赖面占用） | 本轮巡检 #P1，见 [[#funasr]] |
| `agent/AgentDispatcher` | —（死代码，已删除） | （无） | 无 | 2026-11-09 904a98d | 不可得（已删） | **已删除未合并**（分支 `agent/agent/voca-441` 已删，未合入 main） | VOCA-441 |
| `engine/`（Pinyin/Wubi/CellDict/QuickPhrase/Lookup/UserDict） | 后端 | 由 `CompositeEngine` 聚合，`IspeechImeService` 调用 | data 词典 Store | 2026-11-15 fa57a07 | 不可得 | active | VOCA-185 |
| `data/`（词典/同步/统计/剪贴板/AI/认证） | 后端 | `AppContainer` 统一装配 | remote API / datastore | 2026-11-15 fa57a07 | 不可得 | active | VOCA-185 |
| `data/remote/`（Auth/Chat/Ai/Sync ApiService + Dtos） | 后端 | `CsBabyClient` / `DirectLlmClient` | retrofit / gson | 2026-11-09 904a98d | 不可得 | active | — |
| `settings/`（Compose 设置页 + ViewModel） | 前端 | `IspeechNavGraph` 路由表 | SettingsStore / data | 2026-11-15 fa57a07 | 不可得 | active | VOCA-185 |
| `settings/store/SettingsStore` | 前端/后端契约 | 各 ViewModel + `IspeechImeService` | datastore | 2026-11-10 2a1883e | 不可得 | active | — |
| `ui/theme/`（Color/Theme/Type） | UI | `IspeechTheme` | material3 | 2026-10-17 72bb547 | 不可得 | active | — |
| `di/AppContainer` | 后端 | `IspeechApp` 全局装配 | 全 data 层 | 2026-11-14 3edfc83 | 不可得 | active | VOCA-voice-offline-1114 |
| `tools/`（dev/deploy/verify/pinyin 脚本） | SRE/测试 | 独立脚本，不进入 APK | — | 2026-11-15 fa57a07 | 不可得 | active | VOCA-dev-workflow |

## 重点条目

### <a id="funasr"></a> FunASR 离线语音引擎（疑似腐化）

- 文件：`input/FunAsrSpeechEngine.kt`（359 行）、`data/FunAsrModelStore.kt`（442 行，含 `AsrDownloadPolicy`），合计 801 行
- 证据：
  - 静态：`IspeechImeService` 无任何 `FunAsrSpeechEngine` 引用；`AsrEngine` 枚举只有 `SYSTEM / CUSTOM / VOSK`，无 `FUNASR`；`AsrSettingsScreen` 引擎选项只渲染这 3 项。
  - 配置：`app/build.gradle.kts` 的 `ASR_MODEL_URL` 与 `BuildConfig.ASR_MODEL_URL` **唯一消费者是 `FunAsrModelStore`** —— 若删除需连带收敛这条配置与 `docs/model-host-runbook.md` / `docs/asr-model-source-migration-log.md`。
  - 依赖：`sherpa.onnx` 与 `commons.compress` **仅被 FunASR 链使用**（`VoskSpeechEngine` 走 `org.vosk`，不进 APK 打包面内无他者）。
  - 构建产物：未验证是否打入 release bundle。
  - 运行时：**不可得**。
- 结论：静态证据强（未接线 + 依赖独占），但缺运行时观测与历史意图（VOCA-33/34 曾投入 OSS 换源、失败自愈改造），故判 **疑似**，不删。
- 建议：需人裁决 —— 是「已被 Vosk 取代的废弃链路」还是「待接线的下一版本语音方案」。若裁决废弃，删除范围要连带 `ASR_MODEL_URL` 配置、strings.xml 的 `asr_engine_funasr*` 两条文案、`FunAsrModelStoreTest`、两个依赖坐标与两份 OSS 文档，属跨契约清理，应单独立 issue。

## 变更日志

- 2026-11-17（VOCA-441 v2，回应质检 FAIL）：
  - 修正台账事实：`SoftKeyboardView` 最后被修改 → `2026-11-14 f728231`；`FunAsrSpeechEngine` 行数 344 → 359、合计 786 → 801；`SettingsStore` 最后被修改 2026-11-15 → `2026-11-10 2a1883e`；`tools/` 最后被修改 2026-11-14 → `2026-11-15 fa57a07`；FunASR 捆绑行拆分为两份各自日期。
  - AgentDispatcher 状态由稿内「已回收」更正为 `已删除未合并`（新增该状态定义），对应「已回收=已合入 main」定义。
  - 同步删除 `README.md.agent-docs:27` 与 `.agents/README.md:24` 中「代码调用 `AgentDispatcher.dispatch(...)`」的误导指引。
- 2026-11-17（VOCA-441 v1）：初版台账建立；删除 `agent/AgentDispatcher`（死代码，165 行，分支 `agent/agent/voca-441` 未合入 main）；标记 `input/FunAsr*` 为疑似腐化、`SoftKeyboardView` 超长文件为疑似腐化。