# ADR-001：IME → 设置页 麦克风权限桥契约与永久拒绝状态存放

- 状态：已采纳并落地（PR forcoder/ispeech#13，分支 `fix/voca-166-mic-permission-entry`，base `main` fa57a07）
- 日期：2026-11-14（2026-11-16 增补决策 6 与决策 2/3 的实现期修正）
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
- **行号口径（2026-11-16 修正）**：上文 `:1868 / :1570-1573 / :1683-1686 / :1777-1780` 是分诊当时的
  行号，`main` 已三次漂移；FunASR 的两个语音入口（`toggleFunAsrVoice` / `startFunAsrListening`）
  已从 `IspeechImeService.kt` 移除，未授权分支现为**两处**：`toggleVoiceInput`（系统引擎）与
  `toggleVoskVoice`（离线引擎）。**本 ADR 与其门禁一律按函数名定位，不按行号**（行号只作历史线索）。

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

### 决策 2 补充（2026-11-16，实现期核对 API 后修正前提）

决策 2 的写入前提「回调已发生在一次真实请求被答复之后」**不成立**：授权框被返回键取消（用户没有
答复）同样会走这个回调。实读依赖库 `androidx.activity:activity:1.8.2`，
`ActivityResultContracts$RequestPermission.parseResult` 反编译结果：

```java
if (intent == null || resultCode != Activity.RESULT_OK) return false;   // 取消 / 无数据 → false
// 仅当 resultCode==RESULT_OK 时才读 PERMISSION_GRANT_RESULTS 判定 granted
```

即「显式拒绝」与「返回键取消」对本契约**都返回 `false`**，而取消时
`shouldShowRequestPermissionRationale` 仍为 `false`（系统没记下一次拒绝）→ 按原规则会误标永久拒绝。

- **裁定：不改契约，只加判别用例。** flag 仍是提示不是门禁（决策 2 末条），误标的后果是用户被多送到
  「系统设置 → 应用信息 → 权限」一步 —— 该页对已拒绝的 runtime 权限**提供可改的开关**，所以是
  「多一步」而不是死胡同，且下次授予即自愈。为消掉这个误标而引入 resultCode 定制契约有反向代价：
  若某 ROM 的「拒绝」也回 `RESULT_CANCELED`，flag 永远写不上去，永久拒绝用户会反复点「去授权」却弹不出
  系统框 —— 那才是 AC-6 死胡同。**误报优于漏报。**
- **要求测试补 TC-E（判别用例，本批必跑）**：进授权框后**按返回键**退出（不作答）→ 回键盘再点
  「去授权」，期望仍是「去授权」并能再次拉起系统框；实测若落进「去设置」，按预案加
  `MicPermissionContract`（返回 `granted` + `canceled`，`canceled` 不写 flag），不改动本 ADR 其余决策。
- ROM 差异同样落在这一格：EMUI 上 rationale 语义与 AOSP 不一致 → 按决策 3 只影响文案精度。

### 决策 3：回退与降级（失败模式清单）

- `ACTION_APPLICATION_DETAILS_SETTINGS` 解析/启动失败 → `try/catch` 兜底回落到决策 1.1 的
  `REQUEST_MIC_EXTRA` 桥（退化为非永久路径，仍可达授权入口，不会死胡同）。
- DataStore 读异常已被 `SettingsStore.safeData()` `catch { emptyPreferences() }` 兜底 → flag 读不到时
  按 `false`（非永久文案）处理：只降级文案，不崩溃、不丢键盘。
- ROM 偏差（EMUI 等）：`shouldShowRequestPermissionRationale` 语义可能与 AOSP 不一致 → flag 可能
  误判为永久。后果只影响「去授权」文案与跳转目标（走系统设置 vs 系统框），**不改变权限门禁**，
  因此最多是文案不精确，绝不引入闪退/键盘消失。回归需在报告人真机复跑（对齐 BA 风险 #4）。
- 并发写：单进程 + 单 DataStore 实例，无跨进程竞态；`setMicPermanentlyDenied` 用现有 `editSettings`。
- **用户点击发起的跳转必须按 Exception 级包裹（2026-11-16 加，PR #13 待补）**：`openMicAuthorization()`
  的两次 `startActivity` 目前只 `catch (ActivityNotFoundException)`。跳转目标是跨进程系统组件，
  ROM（EMUI「后台弹出界面」拦截等）可抛 `SecurityException` / `IllegalArgumentException`，而这些调用发生在
  **输入法进程**的点击回调里 —— 未捕获即 IME 崩溃、键盘整个消失，正是本 issue 的 P1 症状本身（AC-8
  「不新增 FATAL EXCEPTION」）。契约要求：两处跳转 `catch (t: Throwable)` → 记一条
  `mic_auth_jump_failed`（含异常类型，不吞栈）→ 走 `showMicJumpFailure` 的可读文案并保留按钮可重试。
  只包跳转，不包识别链路（那条已有自己的错误边界）。

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
- **S8 是本决策的契约依赖，不是可选优化（2026-11-16 明确）**：本 ADR 不承诺 `mInputShown=true`，
  用户回来时键盘可能不自动重显——「回来该干什么」完全靠 S8 那句一次性提示兜着。把它移出本批，等于
  把决策 4 的「不承诺平台行为」变成「用户回到输入框却没有任何下一步指引」，正是 BA 风险 #5 的
  「修完比现状更像故障」。落地口径二选一：① S8 随本批补一条一次性状态文本（`onWindowShown` 已知
  「权限刚开好 + 提示刚撤」这个时机，约 5 行）；② 由洞察BA/UX 明确签字「本批不承诺 S8」，
  并在真机回归证明宿主必然重显键盘后才有资格推迟。实现方不得自行降级。

### 决策 5：采纳 UX §10 两项待确认的推荐

- U1 方案 A：S3「去设置」跳 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`
  （`package:<context.packageName>`，即 `com.ispeech.ime`），用户点击触发，一步到位；
  `try/catch` 兜底回落方案 1.1（`REQUEST_MIC_EXTRA` 桥），保持可达。原「回退与降级」已覆盖此兜底。
- U2 方案 R1（不与决策 2 的标记矛盾）：前端可先按「合并文案」落地，AC-5 不必等
  `micPermanentlyDenied` 标记——永久拒绝与「刚拒绝一次」共用状态文案，仅按钮标签区分。
  因此 `micPermanentlyDenied` 降级为**后补精化（R2）**，不再是 AC-5 的阻塞前置：
  决策 2 仍成立（做就做对），但不能成为前端开工或单测的硬依赖。UX 已明示「不阻塞」。

### 决策 6：`MainActivity` 的 launchMode 裁定为 `singleTask`（决策 4 的实现前提，2026-11-16）

前端在 PR #13 落地时发现决策 4 缺一个前提并已自行改掉，请架构师确认。裁定：**采纳，作为决策 4 的
组成部分入库。**

- **问题**：用户只要进过一次设置页，`MainActivity` 实例就存活在应用任务里。输入法（非 Activity 上下文）
  以 `FLAG_ACTIVITY_NEW_TASK` 再次拉起时，标准 launchMode 下系统只做 `TO_FRONT`（前端实测
  `ActivityTaskManager result code=2`），`onCreate` 与 `onNewIntent` 都不派发 → 授权框根本不弹，用户被
  丢在自己上次浏览的设置页。决策 4 的回场与 AC-4/AC-6 同时失效，且这条路径在真机上是**常态**（齿轮
  入口与授权入口共用同一个 Activity）。
- **备选**：

| 方案 | 描述 | 评估 |
|---|---|---|
| ① 不改 launchMode，靠 `onResume` 现场判定 | 复用实例起来后自己判断该不该弹框 | 不成立：没有 intent，分不清「IME 授权会话」与「用户自己回来继续浏览设置页」（`REQUEST_MIC_EXTRA` 到不了），要么误弹框打断浏览，要么静默不弹 |
| ② `singleTop` | 只在实例已位于**栈顶**时派发 `onNewIntent` | 今天可用（全应用只有一个 Activity，它必然在栈顶），但桥的生效条件变成「恰好没人压在它上面」，是**静默失效**型的脆弱前提——正是本次缺陷的形态。不作首选 |
| ③ 新增 `MicGrantActivity` 桥接页（trampoline，`excludeFromRecents` + 独立 task） | 把授权会话从主设置页剥出来，`MainActivity` launchMode 一字不动 | 契约最干净，但要为这一个 bug 给应用加**第二个 Activity** 与第二套生命周期/回场逻辑；`noHistory` 类配置还会销毁 `ActivityResult` 回调宿主，得额外绕。超出本 issue 必要范围 |
| ④ `singleTask`（采纳） | 设置页恒为任务根，intent 必达 `onNewIntent`，其上的 Activity 会被清掉 | 见下 |

- **决策**：`AndroidManifest.xml` 给 `MainActivity` 声明 `android:launchMode="singleTask"`；会话识别抽成
  `beginMicSession(intent)`，`onCreate` 与 `onNewIntent` 都调用；系统框在 `onResume` 由
  `micRequestPending` 发一拍（`launch()` 需 STARTED 之后），`onResume` 同时承担决策 4 的第 2 结束条件
  （用户在本页手工开完权限返回）。
- **为什么影响面可接受（架构师自查事实，非推测）**：`singleTask` 的额外语义只有两条——「intent 到达时清掉
  压在它上面的 Activity」和「恒为任务根」。本应用 `AndroidManifest.xml` **只声明 1 个 `<activity>`**
  （已核 `git show pr/13:app/src/main/AndroidManifest.xml`，`<activity>` 计数 = 1），也没有
  `taskAffinity` / `android:process` / deep link / intent 驱动的 start destination
  （`IspeechNavGraph.kt:45 startDestination = IspeechRoutes.HOME`，写死、不读 intent），所以「清上面」无可清；
  IME 侧拉起它的只有两处（`IspeechImeService.kt` 的麦克风桥与配置面板「更多设置」），都带 `NEW_TASK`。
  桌面图标入口按 launcher 的 `NEW_TASK|RESET_TASK_IF_NEEDED` 语义，标准模式下本来就回到既有任务，
  因此预期不是新增差异 —— 这条是行为推断，未实测，列入下方回归确认项。
- **代价**：① 从桌面图标再进应用复用同一实例并保留导航栈（`singleTask` 既定语义；按 launcher 的
  `RESET_TASK_IF_NEEDED` 推断改动前也是回到既有任务，故预期无差异，但未实测，见下方回归项 (b)）；
  ② 决策 4 的 `finish()` 现在会连带结束整个应用任务——这正是「回到输入框」需要的，但要写明：
  **任何未来新增的 Activity 压在设置页之上时，一次麦克风授权会话会把它清掉**；③ 该属性被
  `tools/verify/check_crosscut.py` 8.4 锁成门禁，后续换桥实现必须同步改门禁，不能只改代码。
- **回滚 / 复议条件**：出现下列任一情形，本决策必须重新评审，不默认延续——
  (a) 应用增加第二个 Activity（尤其需要多实例或压在设置页之上）；(b) 需要 deep link / 通知直达设置子页
  （`singleTask` 的清栈语义会丢用户上下文）；(c) 真机回归证明 EMUI 对 `singleTask` + `NEW_TASK`
  的派发仍不等于 `onNewIntent`（此时改走方案 ③，而不是再叠 flag）。回滚方式：撤 `android:launchMode`
  一行 + 撤门禁一条断言，代码侧 `onNewIntent`/`beginMicSession` 可保留（标准模式下不会被派发，无副作用）。
- **未由架构师独立复现的部分（交测试工程师，两条都要跑）**：我这轮只做了代码与 manifest 面的核对
  （单 Activity、两处拉起、无 intent 驱动导航）；`TO_FRONT` 不派发 intent 是前端 TC-B 预热情形的实测结论。
  - (a) 复用实例桥：齿轮进设置页 → HOME → 键盘点语音 → 「去授权」，期望
    `dumpsys window | grep mCurrentFocus` 落 `GrantPermissionsActivity`；`qa166_test` 与真机 ALN-AL10
    （EMUI）各一次 —— 后者是唯一能证伪「EMUI 对 `singleTask`+`NEW_TASK` 的派发」的样本。
  - (b) 桌面入口对照：改动前后各从桌面图标进一次应用，看是否都停在用户上次浏览的设置子页；
    若行为变了（改前回首页），说明推断不成立，本决策代价①需重写。
