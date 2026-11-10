---
name: replicate-software
description: 复刻软件（克隆某产品的功能 / 图标 / 交互 / 算法 / 词库）统一走 REA + reverse-skill + PentAGI 三件套——REA 从制品取证据并锁定复刻契约，reverse-skill 选工具链并随用随装，PentAGI 负责多智能体编排、人工闸门与留痕。Use for replication or parity work against a shipped artifact or a reference product; do not use for ordinary feature work in a source repo you already own.
metadata:
  trio: rea + reverse-skill + pentagi
  verified: 2026-10-18
  local_projects: /Users/mini/workspace/iSpeech (Android IME), /Users/mini/workspace/IME (macOS 复刻)
---

# 复刻三件套（REA + reverse-skill + PentAGI）

凡是「复刻 / 克隆 / 对齐某个产品」的活——不管要复刻的是功能、图标、键位、算法还是词库——
默认按本技能执行：**先用 REA 把制品变成证据，再用 reverse-skill 决定用什么工具拿证据，
最后用 PentAGI 的编排方式把证据变成可验收的复刻**。
不要凭记忆猜原产品的行为，不要跳过证据直接写代码，也不要把「应该会这样」写成结论。

## 一、三件套各自解决什么

| 件 | 角色 | 输入 → 输出 | 什么时候必须用 |
| --- | --- | --- | --- |
| **REA** | 证据源（制品真相） | 已发布制品（APK / `.app` / JS bundle / 固件 / 运行时）→ Evidence、行为矩阵、复刻义务清单 | 结论依赖制品：要解释「原产品到底怎么做的」、要对比版本、要反编译看逻辑 |
| **reverse-skill** | 工具链路由 | 目标形态 + 任务 → PRIMARY 路线 + 所需工具 + 缺失工具的 bootstrap 方案 | 不知道该上什么工具；环境缺件（jadx / Hopper / Ghidra / IDA / adb / apktool …） |
| **PentAGI** | 编排与留痕 | 范围 + 目标 → `recon → plan → implement → verify → report` 的流水线 + 可复核报告 | 任务跨多文件 / 多模块，要并行推进且必须可验收 |

三者的顺序是固定的：**REA 定事实 → reverse-skill 定工具 → PentAGI 定分工与验收**。
反过来（先搬工具、先并行、先写码）只会得到「看起来像」但无法验收的复刻。

## 二、本机现状（2026-10-18 实测，别凭记忆）

```bash
npx -y rea-agents@latest doctor --client codex --json
```

实测结果（rea-agents 6.5.0，153 个 CLI 命令 / 207 个 MCP 工具）：

- **CLI 可用**：`npx -y rea-agents@latest <command> --json` 能跑；目标无关命令（`doctor` / `capabilities` /
  `providers` / `evidence-export` / `inspect-*`）与静态分析命令（`analyze-javascript-application`、
  `inspect-android-package`、`decode-android-resources`、`extract-artifact`）不需要 MCP 注册即可用。
- **MCP 未注册**：`registration:codex` / `skill:identity` 都是 `config_drift`（`~/.codex/config.toml` 里
  没有 rea 的 `mcp_servers`）→ **当前会话没有 REA 工具**，要走 CLI；要开 MCP 得先 `setup` 再重启客户端。
- **分析引擎缺失**：Hopper / Ghidra / IDA / jadx 全部 `missing_analysis_engine`。
  影响面：原生反编译（`decompile` / `function` / `search`）拿不到；`inspect-macho`、`inspect-native-*`、
  `observe-native-calls` 这类不需要引擎的仍可用。Android 侧要 jadx / apktool 得**自带**（REA 不会替你装）。
- **工具链**：`node v26.10.0` + `npx` 可用；**JDK 17 已装**（Temurin 17.0.20.1，
  `~/Library/Java/JavaVirtualMachines/temurin-17.jdk`，`/usr/libexec/java_home -v 17` 可解析）；
  **Android SDK 仍缺**（`local.properties` 指向 `/opt/homebrew/share/android-commandlinetools`，本机无 brew）；
  无 Ghidra / Hopper / jadx / apktool；`adb` 只在 `~/Library/Android/sdk/platform-tools`。
- **构建 / 冒烟**：iSpeech 的打包与真机验证在远端构建机 `cookie@43.136.44.72:10010`
  （见仓库根 `README.md`「远端构建机」与 `~/workspace/ispeech-remote.sh`）。

**缺件就写缺件**：工具不存在时报告「缺什么 + 因此哪些行为无法观测」，不要用推测补上证据位。

## 三、标准流程（每阶段都有产物，缺一不可）

### P0 界定范围与授权（PentAGI 的 scope 门禁）

动手前必须写清：目标制品（绝对路径 + `shasum -a 256`）、归属与授权、允许的动作级别、
禁止事项（不改目标、不重分发原厂资源）。范围不明时只做只读解析。

### P1 REA 取证据（制品 → 行为矩阵）

先 `capabilities` / `inspect-artifact` 认形态，再按形态取证据；每条结论都必须能回溯到
「命令 → 原始输出 → 制品哈希」：

```bash
sha256sum app.apk                       # 或 shasum -a 256；证据行的制品指纹
npx -y rea-agents@latest inspect-artifact /abs/path/app.apk --json
npx -y rea-agents@latest inspect-android-package /abs/path/app.apk --json     # APK：清单 / 权限 / 声明
npx -y rea-agents@latest decode-android-resources /abs/path/app.apk --json    # 需要自带 apktool
npx -y rea-agents@latest extract-artifact /abs/path/app.apk --json            # 内置数据 / 资源
npx -y rea-agents@latest inspect-macho /abs/path/App.app --json              # macOS bundle（无需 Hopper）
npx -y rea-agents@latest analyze-javascript-application /abs/path/app --json  # JS / Electron / ASAR
npx -y rea-agents@latest capture-adb-screen --json                           # 运行时截图（adb 设备）
```

证据落地成**行为矩阵**：一行 = 一个可观测行为（功能 / 图标 / 文案 / 键位 / 数据 / 边界），
列 = 原产品行为、证据、复刻实现、状态（已复刻 / 有差异 / 未观测）。没有证据的行写「未观测」，不要留空。

### P2 reverse-skill 选路

按目标形态查 `~/.codex/skills/reverse-skill/references/MASTER-ROUTING.md`
（APK → `apk-reverse/`、macOS/Mach-O → `macos-reverse/`、JS → `js-reverse/`、固件 → `firmware-pentest/`…），
输出「PRIMARY 路线 + 一句话依据」，再按路线核对工具：**只认存在的工具**，缺件先 bootstrap
（只装 manifest 里声明需要的能力），装不了就停下来报告。

### P3 PentAGI 编排（recon → plan → implement → verify → report）

- **冻结契约再并行**：先定接口（函数签名 / 数据结构 / 资源命名 / 文件边界），再按**独占写集**分工，一个文件只归一个人。
- **关键路径自己留着做**：下一个动作依赖它的那件事不进并行队列。
- **验证者 ≠ 实现者**：验证要么跑独立脚本，要么换人做对抗式评审。
- **人工闸门**：破坏性动作、越界目标、写客户端配置（MCP 注册、`AGENTS.md`）都必须先给出确切改动再执行。
- **留痕**：每轮记录「目标 / 冻结契约 / 执行者与写集 / 验证命令与结果（含 PASS-FAIL 计数）/ 未闭环项 / 下一轮最高优先级动作」。

### P4 复刻验收（复刻 ≠ 看着像）

1. **黄金样例差分**：在原制品上批量生成「输入 → 输出」fixture，在复刻实现上跑同一批，`diff -u` 只看首个分歧点。
2. **复刻义务清单**：`npx -y rea-agents@latest build-reconstruction-obligation-ledger --json`，
   用 `evaluate-reconstruction-coverage` 检查哪些义务还没覆盖——**未覆盖的要显式列出，不能算完成**。
3. **真机 / 真环境实测**：把实测结论写成表格（用例 → 结果 → 证据路径），不是「应该没问题」。
4. **回归**：复刻改动后跑本仓全部静态/逻辑校验脚本（见第五节），并把结果计数写进汇报。

## 四、复刻义务清单（reconstruction obligations）

每项复刻内容都要有一条义务行，格式固定：

| # | 类别 | 义务（要做成什么） | 原产品行为 | 证据（命令 / 输出 / 制品哈希） | 状态 |
| --- | --- | --- | --- | --- | --- |
| O-01 | 行为 | … | … | `rea inspect-… --json` → … | 已复刻 / 有差异 / 未观测 |

类别至少覆盖：**功能 / 图标与视觉 / 文案 / 数据与词库 / 交互时序 / 边界与错误**。
图标类义务额外记录：来源制品路径、原始尺寸、像素/矢量形态、重建方式（重绘 / 描摹 / 打包 PNG）、
**是否可分发**（原厂资源默认只做证据，不复用其原始文件）。

## 五、本仓（iSpeech）的落地映射

- 行为矩阵：`docs/sogou-feature-matrix.md`（搜狗五笔对照表）、`docs/t9-and-symbols.md`、`docs/quick-phrase-enhancement.md`
- 证据链：`README.md`「词库与排序的证据链」；macOS 侧原始报告 `~/workspace/IME/REVERSE-ENGINEERING-REPORT.md`
- 回归：`for s in static input_logic t9 stats phrase_usage pinyin crosscut; do python3 tools/verify/check_$s.py; done`
- 构建 / 冒烟：`~/workspace/ispeech-remote.sh {sync,build,install,test,pull}`（JDK 17 + Android SDK + emulator-5554）
- 只分析**本机已有、且有权分析**的制品：`/Library/Input Methods/SogouWBInput.app`（含 `Skins/`、`SogouWBPreference.app`）

## 六、红线

- 不伪造工具输出：命令没跑、结果没拿到，就写「未执行 / 未获取」。
- 不越界：只分析自己拥有或已获授权的制品；不绕授权、不碰 DRM。
- 不冒充进度：缺引擎 / 缺设备 / 缺网络时报告阻塞点，而不是用推测补证据。
- 原厂资源（图标 / 词库 / 皮肤）默认只用作**证据**；要随复刻产物分发，必须先确认许可。

## 七、速查

```bash
npx -y rea-agents@latest doctor --client codex --json          # 就绪检查（先看它，别猜）
npx -y rea-agents@latest capabilities --json                   # 当前可用能力与副作用
npx -y rea-agents@latest --llms                                # 命令清单（LLM 可读）
npx -y rea-agents@latest setup --client codex --dry-run --json  # 注册 MCP（只读计划；写配置前先给用户看）
```

相关技能：`rea`（安装与边界）、`reverse-engineer-anything`（REA 工具契约与 references）、
`reverse-skill`（路由表）、`pentagi`（编排与授权约束）、`rea-loop`（多轮 Reason-Act-Verify 循环）。
