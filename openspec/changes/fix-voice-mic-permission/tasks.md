# Tasks

实施按任务顺序派发，每条「先写测试跑红 → 改实现跑绿」。文案（任务 3）独立成组，先于代码落地，使资源与代码解耦。

> 落地状态记录在每条下面（VOCA-166 前端，2026-11-16）。提案里的行号基于当时的 `main`，
> 实现分支上已整体漂移，一律以函数名为准。

## 1. 文案（zh/en）

- [x] 1.1 在 `res/values/strings.xml` 与 `res/values-en/strings.xml` 新增 `voice_action_grant`（去授权 / Grant）与 `voice_permission_granted`（麦克风权限已开启，按住麦克风说话 / Microphone permission granted — hold the mic key to talk），并修订 `voice_model_ready` 把「再次点击」改为「按住」（zh/en 同步）；验证两条资源文件 grep 到新增 key 且 `voice_model_ready` 已改，`./gradlew :app:compileDebugKotlin` 或 lint 无资源缺失报错。
  - 落地：`voice_action_grant`（去授权 / Grant）与新增的 `voice_action_open_settings`（去设置 / Settings）中英齐备，`compileDebugKotlin` + `assembleRelease` 通过。
  - 偏差 1（2026-11-16 第二批已消除）：`voice_permission_granted` 已新增，S8「授权成功回场一次性提示」随架构师决策 4 的契约要求本批落地。文案用「点击麦克风」而非提案里的「按住麦克风」——当前 `main` 的语音键是 `OnClickListener`（QA 实证单击即触发），写「按住」是与实现相反的指令；与偏差 2 同源，一并交 UX 定稿。口径与下面的偏差 2 是同一条矛盾。
  - 偏差 2：`voice_model_ready` 的「再次点击」**没有**改成「按住」。当前 `main` 的 VOSK 分支是点按开始 / 点按结束的交互，改成「按住」会让文案与实现相反；该差异属 UX 文案定稿问题，已在 issue 里回给 UX，不在本 issue 顺手改。

- [x] 1.2 在两语言资源新增 `voice_permission_denied_forever`（麦克风权限已被拒绝，若系统不再弹出请到「系统设置 → 应用 → 智语 iSpeech → 权限」中开启麦克风 / 对应英文）与 `voice_permission_open_failed`（打不开授权页面，请在「系统设置 → 应用 → 智语 iSpeech → 权限」中开启麦克风 / 对应英文），确认长文案含换行描述、无 `shortText()` 截断依赖；验证 grep 命中且长度/换行满足 UX §5.3 预算。
  - 落地：`voice_permission_open_failed` 中英齐备；`voice_permission_denied_forever` 按 ADR-001 决策 5（U2 方案 R1）**合并进 `voice_need_permission`** —— 永久拒绝与「刚拒绝一次」共用一条正文，只靠按钮标签（去授权 / 去设置）区分，因此不再单设第二个长文案 key。
  - 候选条提示不走 `shortText()` 截断，长文案整条渲染（见 3.1 的 `candidate_code` 区域）。

## 2. 双引擎未授权提示态（去自动跳页）

- [x] 2.1 三处未授权分支（`IspeechImeService.kt` 的 `toggleVoiceInput`、`toggleFunAsrVoice`、`startFunAsrListening`）删除自动 `requestMicPermission()`，改为设置合并提示态（`statusText = R.string.voice_need_permission` + 一个服务侧显式 blocked 标志）后渲染；验证：相关单元测试（Mockito 桩 `checkSelfPermission` 返回未授予）断言 `startActivity` 未被调用、`renderCandidateView` 被调用，测试先红后绿，且 `tools/verify/check_crosscut.py` 中 8.4 节的自动代申请断言按新设计改为「未授权时 URL 跳转仅由用户点击触发」，并同步 `tools/verify/README.md` 说明。
  - 落地：本分支基线上是**两处**未授权分支（系统引擎 `toggleVoiceInput`、VOSK 语音入口），都改为 `showMicPermissionPrompt()` 后 `return`，不再自动 `startActivity`；`hasUsableService()` 拦截保持原位（VOCA-231 依赖）。
  - 测试形态偏差：仓库单测只有 JUnit4（无 Mockito / Robolectric），无法桩 `checkSelfPermission`。改为把「提示态该显示哪个动作」抽成纯函数 `input/MicGrantAction.kt`，用 `MicGrantActionTest`（5 例）写真值表；「跳转仅由用户点击触发」由 `check_crosscut.py` 8.4 静态断言 + 真机 TC-A 覆盖。
  - `tools/verify/README.md` 已补 `check_crosscut.py` 小节与新口径。

- [x] 2.2 `startFunAsrListening`（`:1777`）、`toggleFunAsrVoice`（`:1683`）、`toggleVoiceInput`（`:1570`）的未授权分支 return 前补 `commitPending()`，保证组合码缓冲非空时先上屏；验证：单元测试构造 buffer 非空 → 触发未授权分支 → 断言 `commitTop` 路径被执行（`currentInputConnection` 收到上屏文本），先红后绿。
  - 落地：`commitPending()` 收进 `showMicPermissionPrompt()` 内部，两条分支共用一处，避免以后新增入口漏掉；未做单测（同 2.1 的框架限制），由真机验证「打了一半码再点语音键，残码先上屏、提示随后出现」。

## 3. 候选条第 2 行授权按钮

- [x] 3.1 `candidate_view.xml` 第 2 行、`candidate_scroll` 与 `candidate_settings` 之间、`itemContainer` 之外新增 `TextView candidate_mic_action`（默认 gone，文字色 `#1A1A1A`，padding 补到 ≥44dp 高 × ≥64dp 宽，与相邻控件净距 ≥8dp，`contentDescription` 显式）；验证：inflate 后 `findViewById` 非空且默认 `GONE`。
  - 落地偏差：VOCA-185 之后候选条是**单行**（`candidate_code` + `HorizontalScrollView#candidate_scroll`），不存在「第 2 行」。按钮作为 `itemContainer` 的**兄弟节点**放在候选区右侧，默认 `gone`，`#1A1A1A` 走 `ACTION_TEXT` 常量、点击目标 ≥44dp×64dp、`contentDescription` 显式。
  - 验证：`check_crosscut.py` 8.4 断言该 id 存在且在 `itemContainer` 之外；真机 `uiautomator dump --windows` 读到 `com.ispeech.ime:id/candidate_mic_action` 可点击节点（bounds 219×121px @1080p）。

- [x] 3.2 `CandidateView.update()` 增加显式「授权动作可见 + 按钮文案」入参（不由第一行文本推断），按钮出现/消失随该入参与 `statusText` 同生命周期；验证：`androidTest`/`robolectric` 测试断言「传入动作可见 → 按钮 VISIBLE 且文案正确」「未传入 → GONE」「第 1 行组合码仍只读不可点」。
  - 落地（第二批已收敛为单通道）：`update(code, list, actionLabel = null, actionEnabled = true)` + `onMicActionClicked`，按钮的**文案与可用性只有 `update()` 一条写入通道**——第一批的公开 `setMicActionEnabled()` 已删除（任何一次 `update()` 都会把它恢复成可点，而出现「看着能点、点了没反应」的假可用；架构师评审建议 5）。服务侧用「提示文案 == 当前 `statusText`」这一条不变式决定按钮可见性，因此任何按键 / 上屏 / 切面板都会自动带走提示与按钮，不需要每个入口补清理钩子（真机验证：提示挂着时按 `q`，按钮与提示同时消失）。
  - 测试形态偏差同 2.1：无 Robolectric，改由静态断言 + 真机覆盖，未写 `androidTest`。

- [x] 3.3 服务侧 `renderCandidateView` 与各未授权分支接入该入参；点击回调只走一次由用户显式触发的跳转（跳转在途置 disabled，防连点两弹框），`click` 绑定、不接空格/回车/数字键；验证：新增「提示挂着时按空格/数字键必须不跳页」测试用例（`IspeechImeService` 单测 + 交叉验证脚本断言回调未绑定到 `commitTop`/`pickCandidate`），先红后绿。
  - 落地：`renderCandidateView()` 统一带 `micActionLabel()` 与 `actionEnabled = !micJumpInFlight`；`openMicAuthorization()` 首行实时查权限，已授予只撤提示；在途用 `micJumpInFlight` 一个状态源驱动按钮转灰，输入法窗口重新显示时复位。按钮是候选条里的独立 `TextView`，不在候选 `itemContainer` 内，空格/数字键路径碰不到它。

## 4. 跳页异常与回场（X1 / AC-4）

- [x] 4.1 `requestMicPermission()`（保留函数名以复用判定，语义改为「用户触发」）的 `catch(ActivityNotFoundException)` 设置 `voice_permission_open_failed` 状态文本 + 按钮保留可再试一次，并加结构化日志，不自动重试、不循环弹；验证：单测以伪造 resolve 失败的 intent 走 catch，断言 `statusText` 命中错误文案且仅 `Log.*` 一次。
  - 落地（第二批按 ADR-001 决策 3 收紧）：两处跳转合并成一个 `launchMicIntent(target)`，`Intent` 构造与 `startActivity` 一起包在 `catch (t: Throwable)` 里（ROM 可抛 `SecurityException` / `IllegalArgumentException`，而这些调用跑在**输入法进程**的点击回调上，未捕获就是一次 IME 崩溃 = 本 issue 的 P1 症状），失败记一条 `mic_auth_jump_failed target=… type=…`**带栈**日志；跳转目标由纯函数 `MicGrantAction.jumpPlan(permanentlyDenied)` 给出、**首个成功即止**，所以一次点击最多把前台切走一次；全部失败才 `showMicJumpFailure()` 落 `voice_permission_open_failed` 文案、复位在途状态并保留按钮可重试。识别链路的错误边界不在这条线上，未动。
  - 测试形态：`jumpPlan` 的顺序与条数由 `MicGrantActionTest` 覆盖（非永久 1 条、永久 2 条且应用信息页在前）；`catch (Throwable)` 与「首个成功即止」由 `check_crosscut.py` 8.4 静态断言锁住，运行时行为需真机注入失败才能验（本批 0 次，见风险）。

- [x] 4.2 `settings/MainActivity` 在 `REQUEST_MIC_EXTRA=true` 时，授权结果回调结束后 `finish()` 回场原输入框；验证：`androidTest` 断言授权流程结束后 Activity `isFinishing` 为真；QA TC-02（拒绝后不再停在设置页）回归转 PASS。
  - 落地：`finishMicSession()` 在两个结束点被调用 —— 系统框结果回调、`onResume` 发现权限已授予。真机 TC-D 实测：允许后焦点回宿主 `SearchActivity`、`mInputShown=true`。
  - **实现期新发现（提案未覆盖）**：`MainActivity` 原为标准 launchMode，输入法以 `NEW_TASK` 拉起**已存活实例**时系统只做 `TO_FRONT`，`onCreate` / `onNewIntent` 都不发，授权框根本不弹、用户被丢在设置页。已加 `android:launchMode="singleTask"` + `onNewIntent` 认会话 + `onResume` 一拍后再 `launch()`；契约与副作用记在 `docs/adr/001-mic-permission-bridge.md`「决策 4 实现期补充」，待架构师确认。
  - 第二批更新：架构师已裁定 `singleTask` 升格为 **ADR-001 决策 6**（采纳，附复议条件与回归项 a/b），ADR 正文改用架构师那一份（`docs/adr/001-mic-permission-bridge.md` 与 `728b4d5` 逐字节一致，前端不加批注；文中「PR #13 待补」「两处跳转」两处口径已过时，请架构师在合并窗口更新）。

- [x] 4.3 授权成功回场的一次性指引（S8，UX 状态矩阵）：`onWindowShown` 在「提示挂着 + 实时查得已授予」时撤提示并写一句下一步文案，任何按键即清；验证：真机点「允许」回键盘后候选条出现该句、按任意字母键后消失。
  - 落地：`onWindowShown()` 里 3 行（`clearMicPrompt()` → `statusText = voice_permission_granted` → `renderCandidateView()`）。它不是可选优化而是决策 4 的契约依赖：ADR 不承诺宿主重显键盘，缺这句就是「回到输入框却没有任何下一步指引」。
  - 由 `check_crosscut.py` 8.4 锁住（`onWindowShown` 区域必须同时含 `R.string.voice_permission_granted` 与 `micJumpInFlight = false`），JVM 侧无可测面。

## 5. 副入口（E2/E3）与集成验证

- [ ] 5.1 `SettingsPanel` 新增常驻「麦克风权限：未开启 › / 已开启」行（复用 `buildChoiceRow`，实时 `checkSelfPermission`，已开启时无跳转去掉 `›`）；验证：面板打开时该行可见，点击未开启态触发用户授权跳转。
  - **本批未做**：不在 QA 的 TC-A/B/C/D 与 P1 清零条件内，且它引入的是「面板行」这一新交互面（UX 需给异常态与焦点顺序）。建议另开 issue，避免与本 issue 的验收面混杂。

- [ ] 5.2 `AsrSettingsScreen`（设置页语音识别）补权限状态行 + 「开启麦克风」入口（键盘/TalkBack 焦点可达）；验证：Compose `androidTest` 断言入口 `contentDescription` 正确且可聚焦。
  - **本批未做**：同 5.1；且仓库无 Compose `androidTest` 基建，先补测试框架再动这条更稳。

- [x] 5.3 运行 `tools/verify/check_crosscut.py`（含修订后的 8.4 节）与 `./gradlew :app:testDebugUnitTest :app:lintDebug` 全绿；同步更新 `docs/voice-input.md`（记录 AC-1…AC-10 与 12 态映射、合并文案决策、回场规则、已知 R2 精化）与 README 变更点；验证：脚本退出码 0、测试全绿、文档提及 E1/E2/E3 与合并文案。
  - `check_crosscut.py`：`SUMMARY: passed=160 failed=0 warned=0`，退出码 0。第 2 批复审新增 4 组断言：跳转的 `catch (t: Throwable)` + `mic_auth_jump_failed` 带异常类型且保留堆栈、`MicGrantAction.jumpPlan` + `firstOrNull { launchMicIntent(it) }`（一次点击最多成功跳转一次）、按钮 label/enabled 只有 `update(...)` 一条写入通道（`setMicActionEnabled` 不得存在）、`onWindowShown` 内出现 S8 提示且复位 `micJumpInFlight`；原先按出现次数计数（`== 3`）的弱断言换成按函数区域逐处断言。
  - `testDebugUnitTest`：54 项，1 项失败为**存量** `com.ispeech.agent.AgentDispatcherTest > initializationError`（`AndroidJUnit4` 在无 Robolectric 的 JVM 上跑不了）；该测试文件、`app/build.gradle.kts`、`com/ispeech/agent/` 与 `main` 逐字节一致，本分支未触碰。本分支新增的 `MicGrantActionTest` 7/7 通过（含 `jumpPlan` 两条：非永久只有桥、永久先应用信息页再回落桥）。
  - `lintDebug`：14 errors / 81 warnings，全部落在本分支未改的文件或未改的行（`FunAsrSpeechEngine.kt:321`、`VoskSpeechEngine.kt:182`、`IspeechImeService.kt:355` 的 `onEvaluateInputViewShown`、两处 `themes.xml`、`QuickPhrasesViewModel.kt:401`、7 条 `quick_*` / `btn_add_cloud_shortcut` 缺 `en` 翻译）；本分支新增的 4 个 `voice_*` 键中英成对，未新增 lint 错误。存量项建议另开 issue 收敛。
  - `docs/voice-input.md`：新增「麦克风授权入口（VOCA-166）」小节（场景 → 行为 → 代码位置表）、接线树与关注点表更新；`tools/verify/README.md` 补 `check_crosscut.py` 说明。E2/E3 未做已写明。
  - AC-1…AC-10 的逐条映射没有整表搬进 `docs/voice-input.md`（该文档是能力说明，不是验收台账），落在本 tasks 与 issue 交付评论里；BA 的 `docs/requirements/VOCA-166-acceptance.md`、UX 的 `docs/ux/VOCA-166-mic-permission-flow.md` 目前只以 issue 附件形式存在、仓库里没有，需要 BA/UX 自己入库。

## Workflow follow-up

- 由 QA 按 AC-1…AC-10 在 `qa166_test`（AOSP 14）+ 报告人真机 `FMR0224913042917` 各复跑一遍（新增 AC-9/AC-10 反例），P1 清零条件为全 PASS。
- 架构师确认 U1/U2（`ACTION_APPLICATION_DETAILS_SETTINGS` 契约、一次性/永久拒绝标记）后，可做 S3 按钮转「去设置」的 R2 精化，不阻塞本批。→ 前端已按 R2 落地（`micPermanentlyDenied` 标志 + 「去设置」按钮），另需架构师确认新增的 `singleTask` 前提（ADR-001 决策 4 实现期补充）。
- 交付分支 + PR，squash merge 后归档 OpenSpec 变更。
- 前端自测环境记录（便于 QA 复现）：AVD `ispeech_test`（AOSP 14，1080×2340 @440dpi），安装本分支 `assembleRelease` 产物；引擎设为 Offline (built-in) 并放一个最小模型标记文件绕过 42MB 下载，使 VOSK 分支可达（SYSTEM 分支在这台 AVD 上会先被 `hasUsableService()` 判为不可用）。TC-A/B/C/D 与「提示态打字清除」全部通过，`logcat -b crash` 0 FATAL。
- 第 2 批复审项在同台 AVD 上的真机证据（`adb -s emulator-5554`，坐标按 `uiautomator dump --windows` 的 bounds 取中心）：
  - TC-A 未授权点语音键（877,1487）：`mInputShown=true`、`mCurrentFocus` 仍在宿主 `ContactEditorActivity`、logcat 无任何 `START u0`；候选条节点 `candidate_mic_action text="Grant" clickable="true" enabled="true" bounds="[887,1218][1063,1339]`（64dp×44dp）。
  - TC-B 连点两次「去授权」（975,1278）：只有 **1 条** `START u0 {flg=0x10000000 cmp=com.ispeech.ime/com.ispeech.settings.MainActivity ... with LAUNCH_SINGLE_TASK ... result code=2}` + 紧随其后的 `GrantPermissionsActivity`，即「一次点击最多改一次前台」；`result code=2`（START_DELIVERED_TO_TOP）同时证明 `singleTask` 复用实例路径仍能把系统框送出去（ADR-001 决策 6 回归项 a）。
  - TC-D 授权成功回场：焦点回宿主编辑器、`RECORD_AUDIO: granted=true`、`candidate_code` 显示「麦克风权限已开启，点击麦克风开始说话」且 `candidate_mic_action` 节点消失（S8 + 权限实时判定隐藏按钮）；再按 `q` 后 `candidate_code="q"`、宿主字段文本 `"q"`，即 S8 是一次性提示，同字段输入未中断（AC-9）。
  - 本轮未能复跑「永久拒绝 → 去设置」的设备用例：跑到该场景时 AVD 进程退出（`adb devices` 只剩真机 `FMR0224913042917`），没有在他人真机上做点击验证。该分支的行为由 `jumpPlan` 单测 + 结构断言锁定，第 1 批的设备证据为改造前所取，QA 需在新 AVD 上复跑（`pm revoke` 后连点两次「Don't allow」使 flags 出现 `USER_FIXED`）。
