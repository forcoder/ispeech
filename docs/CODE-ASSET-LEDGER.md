# 代码资产台账（Code Asset Ledger）

> 维护人：重构清理大师（Multica agent `a0946453-a67c-483e-8d79-45b56b0dd09d`）
> 用途：给代码库熵减提供可控抓手。每次巡检和每次清理都必须更新本表。
> 更新节奏：随巡检 / 清理 MR 同步更新；不另建第二份台账。
> 本版基准：`origin/main` @ `0168e59`（2026-11-19）。

## 状态与证据等级约定

| 状态 | 含义 |
|---|---|
| `active` | 正常维护：在生产调用链上，**或已由人类/文档/测试声明“在用”** |
| `疑似腐化` | 静态检索无调用、缺运行时观测证据，**只报告、不删除**；含「未完成原型」（有测试/配置声明但实现未完成），此类保留意图线索、不得删 |
| `待回收` | 四级证据齐全（静态+产物+调用链+近30天运行时），可提交删除 |
| `已回收` | 已删除并合入 main |

证据等级：`确认`=四级齐全；`疑似`=缺运行时观测；`待证`=只有静态检索。
「最后被调用」取「近 30 天运行时观测」结果；拿不到运行时观测的一律写 `不可得`，不得推断。
「最后被修改」取 `git log -1 --date=short -- <target>` 的文件粒度提交日期与短哈希。

**硬规则一（2026-11-17 人类裁定，长期有效）**：凡文档 / 文案 / 资源 / 测试声称「在用」的能力，**静态零引用不得作为删除依据**。清理必须先证「该项无任何在用声明 + 无装配入口」，再走四证据流程。

**硬规则二（2026-11-17 质检裁定）**：「边标疑似边删」**禁止**，无例外可由清理方或质检方自行授予。本仓无埋点 / 监控 / CI，「近 30 天运行时观测」对任何项都结构性不可得（见「已知缺陷」），故本仓合规结论恒为**可回收 = 0**；若人类 owner 决定放行某项，必须落成「三级证据 + 具名批准人 + 日期 + 关联 issue + 独立可 revert 的 commit」写入台账。

## 模块清单

| 模块 | Owner | 对外入口 | 上下游依赖 | 最后被修改 | 最后被调用 | 状态 | 关联 issue |
|---|---|---|---|---|---|---|---|
| `input/IspeechImeService`（2471 行 IME 主服务） | 后端/输入法 | `AndroidManifest` IME 组件 `com.ispeech.ime/com.ispeech.input.IspeechImeService` | engine / data / settings 全链路 | 2026-11-19 0168e59 | 不可得 | active | VOCA-127 |
| `input/SoftKeyboardView`（1007 行，结构腐化：超长文件） | 前端 | 由 `IspeechImeService` 持有（字段 `:224` + `findViewById :360`），另有 CandidateView/VoicePanelView/KeyDef/KeyboardLayouts/T9Layouts/MainActivity 引用，`ime_input_view.xml:72` 实例化 | keyboard 布局 / KeyDef | 2026-11-14 f728231 | 不可得 | active（结构腐化待抽取，非无调用） | 本轮巡检 #P2 |
| `input/FunAsrSpeechEngine`（359 行）+ `data/FunAsrModelStore`（439 行） | 后端 | **在用（默认引擎）**：`AsrEngine.FUNASR` 枚举 + `IspeechImeService.kt:303/338/590/612/1733` 装配 + `AsrSettingsScreen` 选项 + `AsrSettings.parse` 默认回落 FUNASR | sherpa-onnx / commons-compress / `AsrModelInstall` | 2026-11-09 904a98d / 2026-11-19 a9c5109 | 不可得 | **active（在用）** | VOCA-127 / VOCA-33/34 |
| `input/VoskSpeechEngine` + `data/VoskModelStore` | 后端 | `IspeechImeService` 按 `AsrEngine.VOSK` 分支接线 | vosk.android / AsrSettings | 2026-11-14 07214d6 | 不可得 | active | VOCA-voice-offline-1114 |
| `agent/AgentDispatcher`（101 行） | 后端 | 生产零引用；`AgentDispatcherTest` 24 条断言中 **16 条失败**（实测），`selectAgent` 的 `claude-code-guide`/`statusline-setup` 两分支从未实现，且关键词优先级让 `plan`/`explore`/`general-purpose` 三类也大面积误判 → **未完成原型** | `.agents/agents.yaml`（未接入） | 2026-11-09 904a98d | 不可得 | **疑似腐化（未完成原型，不删）** | 测试基建单独立 issue |
| `engine/`（Pinyin/Wubi/CellDict/QuickPhrase/Lookup/UserDict） | 后端 | 由 `CompositeEngine` 聚合，`IspeechImeService` 调用 | data 词典 Store | 2026-11-19 0168e59 | 不可得 | active | VOCA-127 |
| `data/`（词典/同步/统计/剪贴板/AI/认证/ASR 安装） | 后端 | `AppContainer` 统一装配 | remote API / datastore | 2026-11-19 0168e59 | 不可得 | active | VOCA-127 |
| `data/remote/`（Auth/Chat/Ai/Sync ApiService + Dtos） | 后端 | `CsBabyClient` / `DirectLlmClient` | retrofit / gson | 2026-11-09 904a98d | 不可得 | active | — |
| `settings/`（Compose 设置页 + ViewModel） | 前端 | `IspeechNavGraph` 路由表 | SettingsStore / data | 2026-11-19 0168e59 | 不可得 | active | VOCA-127 |
| `settings/store/SettingsStore`（283 行） | 前端/后端契约 | 各 ViewModel + `IspeechImeService` | datastore | 2026-11-19 6ef10c2 | 不可得 | active | — |
| `ui/theme/`（Color/Theme/Type） | UI | `IspeechTheme` | material3 | 2026-10-17 72bb547 | 不可得 | active | — |
| `di/AppContainer` | 后端 | `IspeechApp` 全局装配 | 全 data 层 | 2026-11-14 3edfc83 | 不可得 | active | VOCA-voice-offline-1114 |
| `tools/`（dev/deploy/verify/pinyin 脚本） | SRE/测试 | 独立脚本，不进入 APK | — | 2026-11-19 0168e59 | 不可得 | active | VOCA-127 |

## 重点条目

### FunASR 离线语音引擎（active，在用）

- 文件：`input/FunAsrSpeechEngine.kt`（359 行）、`data/FunAsrModelStore.kt`（439 行）、配套 `data/AsrModelInstall.kt`。
- **人类 2026-11-17 裁定：FunASR 是本项目当前在用的语音识别方案**；主干已于 2026-11-19 把 `AsrEngine.FUNASR` 落地为**默认引擎**（`AsrSettings.parse` 未存过时回落 FUNASR），装配入口在 `IspeechImeService`。
- 6 项保护（不得删除、不得降级依赖）：`FunAsrSpeechEngine.kt`、`FunAsrModelStore.kt`、`FunAsrModelStoreTest.kt`、`strings.xml` 的 `asr_engine_funasr*` 文案、`docs/voice-input.md`、`docs/model-host-runbook.md` / `docs/asr-model-source-migration-log.md`。

### AgentDispatcher（疑似腐化：未完成原型）

- 生产零引用（全仓 `grep -rIn AgentDispatcher` 仅自身 + 自身测试 + 台账）。
- `selectAgent` 实际返回 `ime-expert / plan / explore / testing-expert / localization-expert / general-purpose`，无 `claude-code-guide` / `statusline-setup`。
- **实测口径（2026-11-23 按源码逐字复刻 24 条断言）**：**16 条失败**，不止两条分支缺失——关键词优先级 + 判据过宽导致大面积误判。失败样例：
  - `review design` 期望 `plan`，实得 `ime-expert`——IME 组判据 `contains("view")` 命中了 `review` 的子串，IME 组先判即返回
  - `分析内存泄漏` 期望 `general-purpose`，实得 `explore`（`分析` 命中 explore 组，explore 先于默认分支）
  - `execute performance test` 期望 `general-purpose`，实得 `testing-expert`
  - `claude 命令帮助` / `状态行配置` 期望 `claude-code-guide` / `statusline-setup`，实得 `general-purpose`（两分支从未实现）
- 受影响类别：`plan` / `explore` / `general-purpose` 三类均有断言失败。属未完成原型，**保留意图线索、不得删**。

### AgentDispatcher 文档下线的理由

`.agents/README.md` 与 `README.md.agent-docs` 中的 `AgentDispatcher.dispatch(...)` 调用指引已删除，而代码保留。理由是两者描述的不是同一件事：

- **代码保留**：`AgentDispatcher` 是未完成原型，保留其意图线索（含 16 条失败断言所记录的原始设计预期），供日后考古或补实现。
- **文档下线**：原指引教读者「在代码中调用 `dispatch()`」——但该方法在生产中零调用方、内部仅 `Log` 打日志（`[模拟]` 模式），照做不会产生任何实际调度行为，属误导性说明。删指引不影响原型保留。
- 二者口径：**代码按原型保留，文档不再对外宣传未接线的 stub**。

## 已知缺陷（基线既存，非本轮回归）

- **`AgentDispatcherTest initializationError`**：基线即存在，两层原因，**补依赖不足以转绿**：
  1. `app/build.gradle.kts` 只声明 junit / `androidx.test.ext:junit` / kotlin-test，缺 `androidx.test:runner`；且该用例用 `@RunWith(AndroidJUnit4)` 却放在 `test/`（JVM 单测）目录 —— 补 `testImplementation("androidx.test:runner:1.5.2")` 后预期仍落到 `android.os.StrictMode not mocked`（androidx.test 平台在 JVM 里初始化 `PlatformTestStorageRegistry`）。
  2. **断言与实现脱轨**：24 条断言中 16 条失败（实测），`claude-code-guide` / `statusline-setup` 两分支从未实现，且 IME 组 `contains("view")` 命中 `review` 等子串、`分析`/`performance` 等判据过宽，导致 `plan`/`explore`/`general-purpose` 三类也大面积误判 —— 即便补全 runner 仍会断言失败。
  治理归「测试基建 + 未完成原型」单独立 issue（需架构/测试决策：迁 `androidTest/`、接 Robolectric、或补实现），不在本熵减单内处理。`origin/main` @ `0168e59` 实测 `126 tests / 1 failed`（该树共 131 个 `@Test`，个别用例含多条断言/参数化未展开计数）。
  复跑命令：
  ```
  ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:testDebugUnitTest
  ```
- **运行时观测通道结构性缺失**：全仓无 analytics / crash / 监控依赖、无日志目录、无 CI。因此「近 30 天运行时观测」对任何项都不可得，本仓合规结论恒为可回收 = 0（见硬规则二）。这是规则与仓库现实的冲突，需人类 owner 给出「三级证据 + 书面批准人」例外协议。

## 变更日志

- 2026-11-24（VOCA-441 v6，回应质检第五轮 FAIL 5 条打回项，均经我方独立复现）：
  - 行号修正：`IspeechImeService.kt` 中 SoftKeyboardView 的调用点 `:196/:279` → **`:224/:360`**（旧值是已废弃基线 `fa57a07` 的行号）；验证 `git show origin/main:...IspeechImeService.kt | grep -n SoftKeyboardView`。
  - 测试数修正：`47 tests / 1 failed` → **`126 tests / 1 failed`**（`0168e59` 共 131 个 `@Test`）；本人于 `origin/main` 同树实跑确认。
  - AgentDispatcher 定性改为实测口径：24 条断言 **16 条失败**，`plan`/`explore`/`general-purpose` 三类均受影响，非「仅 2 条分支未实现」；结论仍为「不删」。
  - 文档自洽：新增「AgentDispatcher 文档下线的理由」小节，写明「代码按原型保留、文档不再宣传未接线的 stub」。
  - 五段式巡检报告重锚 `f224592` / `0168e59`（见 issue 评论）。
- 2026-11-24（VOCA-441 v5，基于 `origin/main` @ `0168e59` 重建）：
  - 因 main 已前进到 11-19 且 FunASR 已落地为默认引擎，台账事实单元格按 main 重算（ImeService 2471/0168e59、FunAsrModelStore 439/a9c5109、SettingsStore 283/6ef10c2、engine/data/settings/tools 均 0168e59）。
  - FunASR 由「主干未见装配入口」更正为「已装配为默认引擎」，状态 active。
  - 新增硬规则二：「边标疑似边删」禁止，本仓可回收恒为 0。
- 2026-11-17（VOCA-441 v1–v4）：初版建立并于多轮质检中修正（Fact 修正、状态词表、AgentDispatcher 定性、基线红测登记）。