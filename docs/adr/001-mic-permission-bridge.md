# ADR-001：IME → 设置页 麦克风权限桥契约与永久拒绝状态存放

- 状态：已采纳，前端已落地（分支 `agent/agent/voca-166-3ea682beec58`，评审见 VOCA-166）
- 日期：2026-11-14
- 关联：VOCA-166（语音输入点按即跳页）、`docs/requirements/VOCA-166-acceptance.md` AC-1…AC-10

## 背景

VOCA-166 定性：未授予 `RECORD_AUDIO` 时，语音键 `ACTION_DOWN`（用户还没说话）即经
`IspeechImeService.kt:1868 requestMicPermission()` 以 `FLAG_ACTIVITY_NEW_TASK` 拉起设置页并弹
系统授权框，把输入法顶掉、宿主退后台。前端锁定选型 **A+C**（未授权不自动跳页，仅候选条提示 +
用户主动触发「去授权」；永久拒绝给系统设置路径文案）。

本 ADR 只定两个契约，代码实现归前端（openspec propose/apply + TDD）：

1. **权限桥契约**：IME 拉起设置页授权，`REQUEST_MIC_EXTRA` 复用还是新增；
2. **永久拒绝状态存放**：`SettingsStore` 如何跨 Activity 记拒绝状态，驱动 AC-5 文案。

## 现状事实（逐行核对）

- 桥唯一实现：`IspeechImeService.kt:1868` `requestMicPermission()`，`putExtra(REQUEST_MIC_EXTRA, true)`
  + `setClassName(SETTINGS_ACTIVITY="com.ispeech.settings.MainActivity")` + `FLAG_ACTIVITY_NEW_TASK`。
- `REQUEST_MIC_EXTRA` 定义于 `settings/MainActivity.kt:24`；`MainActivity` 在 `onCreate` 读到 true 时
  经 `requestMicrophonePermissionIfNeeded()`（`:55-58`）弹系统框；结果回调 `micPermissionLauncher`
  当前为空实现（`:38-41`）。
- 三处未授权分支都走同一个桥：系统引擎 `toggleVoiceInput`（`:1570-1573`）、FunASR 点按
  `toggleFunAsrVoice`（`:1683-1686`）、FunASR 按住 `startFunAsrListening`（`:1777-1780`）。
- 进程模型：`AndroidManifest.xml` 中 `application`、`MainActivity`、`IspeechImeService` 均未声明
  `android:process` → 设置页与 IME 运行在**同一进程**，`SettingsStore`（DataStore Preferences，
  `ispeech_settings` 单文件）天然共享，无跨进程一致性风险。
- 现有文案：`strings.xml:146 voice_need_permission = 请先授予麦克风权限` 已存在但跳页后被盖掉。
- 现无 `ACTION_APPLICATION_DETAILS_SETTINGS` 相关跳转（全仓库 0 命中）。

## 备选方案

| 方案 | 描述 | 评估 |
|---|---|---|
| A. 复用 `REQUEST_MIC_EXTRA` + 新增「去授权」动作 | 保留唯一桥，永久拒绝时改跳系统应用详情页 | ✅ 采纳 |
| B. 新增 `REQUEST_MIC_EXTRA2` / 拆分两套 intent | 非永久与永久各走一个新 extra | 增加导出面与分支，无收益 |
| C. 不落盘，退出重进现场判定 | IME 不记永久拒绝，每次实时推文案 | 拒绝状态只能靠 `shouldShowRequestPermissionRationale`，而该 API 仅 Activity 可用，IME 无法判定 → 不成立 |

## 决策

### 决策 1：复用 `REQUEST_MIC_EXTRA`，语义收敛为「用户主动发起的授权」

- 不再新增 `REQUEST_MIC_*` 键；`REQUEST_MIC_EXTRA=true` 的语义从「IME 未授权即隐式拉起」收敛为
  **「用户已显式点击『去授权』」**。
- 桥保持单向命令：IME → 设置页。设置页不得回推结果给 IME；IME 每次语音键触发时**重新**
  `checkSelfPermission(RECORD_AUDIO)` 作为唯一事实来源。
- 新增一个服务侧动作 `openMicAuthorization()`（名字示意，前端可定），按永久拒绝标志二分发：
  1. **非永久（未授权 / 拒绝一次）**：复用现有桥 —— `startActivity(REQUEST_MIC_EXTRA=true)`，
     Settings 弹系统权限框。步数 = 点「去授权」→ 系统框 = **2 步**，满足 AC-6。
  2. **永久拒绝**：不再进设置页，改开 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`
     （`package:<context.packageName>` URI，即 `com.ispeech.ime`）直达「系统设置 → 应用 → 智语 iSpeech → 权限」。
     步数 = 点「去授权」→ 系统应用详情 = **2 步导航**（余下「开麦克风」即授权目标本身，不计导航）。

### 决策 2：`SettingsStore` 只增一个布尔流 `micPermanentlyDenied`，「一次性拒绝」不落盘

- 新增（示意，键名与实现归前端）：
  - `fun micPermanentlyDeniedFlow(context): Flow<Boolean>`（默认 `false`）
  - `suspend fun setMicPermanentlyDenied(context, value: Boolean)`
- **只存「永久拒绝」，不存「拒绝一次」**。理由：一次性拒绝无需记忆——它落回「非永久」默认态，
  用户再点「去授权」本就该重弹系统框（`shouldShowRequestPermissionRationale` 会为 true，可加 rationale
  或直接重弹，形态由 UX 定）。把它当作需要跨 Activity 记忆的状态是多余 bookkeeping，且会引入
  「记忆态与系统真实态漂移」的新故障面。
- 写入与清除地点都在设置页 `micPermissionLauncher` 结果回调（`MainActivity.kt:38-41`，目前空实现）：
  - 授权成功 → `setMicPermanentlyDenied(false)`（清理陈旧永久标记）。
  - 拒绝 且 `shouldShowRequestPermissionRationale == false` → `setMicPermanentlyDenied(true)`。
    （回调已发生在一次真实请求被答复之后，此时 rationale=false 判「永久/不再询问」是 Android 文档契约。）
  - 拒绝 且 rationale==true → 不写（保持 false）。
- 读地点在 IME：候选条提示态择文案 + 择「去授权」目标（见决策 1 的二分发）。
- **flag 是提示、不是门禁**：授予态永远以 `checkSelfPermission` 为准。渲染顺序强制为
  ① `checkSelfPermission==GRANTED` → 走正常语音，且顺带清 flag（迟滞自愈）；② 未授予 → 读 flag 只影响
  文案与跳转目标，绝不影响是否允许请求。

### 决策 3：回退与降级（失败模式清单）

- `ACTION_APPLICATION_DETAILS_SETTINGS` 解析/启动失败 → `try/catch` 兜底回落到决策 1.1 的
  `REQUEST_MIC_EXTRA` 桥（退化为非永久路径，仍可达授权入口，不会死胡同）。
- DataStore 读异常已被 `SettingsStore.safeData()` `catch { emptyPreferences() }` 兜底 → flag 读不到时
  按 `false`（非永久文案）处理：只降级文案，不崩溃、不丢键盘。
- ROM 偏差（EMUI 等）：`shouldShowRequestPermissionRationale` 语义可能与 AOSP 不一致 → flag 可能
  误判为永久。后果只影响「去授权」文案与跳转目标（走系统设置 vs 系统框），**不改变权限门禁**，
  因此最多是文案不精确，绝不引入闪退/键盘消失。回归需在报告人真机复跑（对齐 BA 风险 #4）。
- 并发写：单进程 + 单 DataStore 实例，无跨进程竞态；`setMicPermanentlyDenied` 用现有 `editSettings`。

## 代价

- 永久拒绝判定依赖「一次请求被答复后」的 rationale 语义，首次安装从未请求过时 `false` 有歧义，但
  首次安装走的是「未授权」路径、不会先读 flag 走永久文案，不产生误判；只有「用户点过『去授权』且
  拒绝」后才可能有 flag=true，语义自洽。
- 新增一个 DataStore 键 + 一处 `MainActivity` 回调逻辑，改动面极小、可单测。
- 设置页使用 `lifecycleScope`（`ComponentActivity` + activity-ktx 传递依赖）写 DataStore；实现时确认
  `lifecycle-runtime-ktx` 在测试构建可见，若缺失则改用 `viewModelScope`，不新增依赖。

## 回滚条件

- 若 UX 定稿需要「去授权」前插入 rationale 中间页（正文说明为何要麦克风），在决策 1 的桥上加
  可选 extra（如 `REQUEST_MIC_RATIONALE`）作为扩展，**不回滚本 ADR**；该扩展属 YAGNI，本轮不建。
- 若真机回归证明 `shouldShowRequestPermissionRationale` 在目标 ROM 上不可靠到影响 AC-5 判定，
  回退：永久拒绝文案改为「去授权」点击后进设置页，由设置页在授权回调里再分流（即把决策 2 的
  判定结果由启动时读 flag 改为设置页现场计算后回显），仍不新增导出组件。
### 决策 4：回场契约（UX §1.7 +「回场规则」，TC-02 实证）

- **由 IME 发起的授权流程，终点必须回到用户刚才的输入框**，不得停在设置页/登录页。
- 实现契约：设置页 `MainActivity` 在「IME 授权会话结束」时 `finish()`。结束条件二选一现查：
  1. 系统权限框答复后（`micPermissionLauncher` 回调内）；
  2. 若 `REQUEST_MIC_EXTRA` 且权限已授予（用户手工在设置页开完权限再返回），`onResume`
     `checkSelfPermission==GRANTED` 时补一发 `finish()`。
- 「返回后键盘是否自动重显」由宿主/平台决定（`mInputShown` 非 IME 独占），不承诺自动重显；
  UX S8 已带「点一下输入框即可继续」兜底话术，验收按 S8 判定，不把平台行为当本契约义务。
- 区分回场的**用户可见期望**（UX 规格）与**回场的技术动作**（本 ADR 的 `finish()`）；视觉/文案归 UX/UI。

### 决策 5：采纳 UX §10 两项待确认的推荐

- U1 方案 A：S3「去设置」跳 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`
  （`package:<context.packageName>`，即 `com.ispeech.ime`），用户点击触发，一步到位；
  `try/catch` 兜底回落方案 1.1（`REQUEST_MIC_EXTRA` 桥），保持可达。原「回退与降级」已覆盖此兜底。
- U2 方案 R1（不与决策 2 的标记矛盾）：前端可先按「合并文案」落地，AC-5 不必等
  `micPermanentlyDenied` 标记——永久拒绝与「刚拒绝一次」共用状态文案，仅按钮标签区分。
  因此 `micPermanentlyDenied` 降级为**后补精化（R2）**，不再是 AC-5 的阻塞前置：
  决策 2 仍成立（做就做对），但不能成为前端开工或单测的硬依赖。UX 已明示「不阻塞」。

### 决策 4 实现期补充（前端落地时发现，待架构师确认）

真机/模拟器复现出契约里没写的一个前提：**`MainActivity` 必须是 `singleTask`**，否则决策 4 的回场
链路在常见路径上根本不成立。

- 现象：用户点过齿轮（或任何一次进过设置页）后，`MainActivity` 实例仍存活在应用任务里。输入法
  以 `FLAG_ACTIVITY_NEW_TASK` 再次拉起时，系统只做 `TO_FRONT`（`ActivityTaskManager` 返回
  `result code=2`），既不走 `onCreate` 也不走 `onNewIntent`（标准 launchMode 下 `onNewIntent` 只在
  singleTop/singleTask 派发）。结果：授权框不弹，用户被停在自己上次浏览的设置页 —— AC-4/AC-6 双失败。
- 落地：`AndroidManifest.xml` 给 `MainActivity` 加 `android:launchMode="singleTask"`；会话识别抽成
  `beginMicSession(intent)`，`onCreate` 与 `onNewIntent` 都调用；系统框改为在 `onResume` 里由
  `micRequestPending` 一拍后发出（`launch()` 需 STARTED 之后），`onResume` 同时覆盖「用户在本页
  手工开完权限再返回」的第二结束条件。
- 副作用（可接受，需知会）：从桌面图标再次进入应用会复用同一实例并保留导航栈，而不是每次回到
  设置首页。这是 `singleTask` 的既定语义，不是本 issue 新加的行为开关。
- 门禁：`tools/verify/check_crosscut.py` 8.4 增加两条断言 —— manifest 里 `MainActivity` 声明
  `singleTask`、代码里 `beginMicSession` + `onNewIntent` 同时存在，防止后续改动把桥静默改回标准
  launchMode。
