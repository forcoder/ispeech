# Tasks

实施按任务顺序派发，每条「先写测试跑红 → 改实现跑绿」。文案（任务 3）独立成组，先于代码落地，使资源与代码解耦。

## 1. 文案（zh/en）

- [ ] 1.1 在 `res/values/strings.xml` 与 `res/values-en/strings.xml` 新增 `voice_action_grant`（去授权 / Grant）与 `voice_permission_granted`（麦克风权限已开启，按住麦克风说话 / Microphone permission granted — hold the mic key to talk），并修订 `voice_model_ready` 把「再次点击」改为「按住」（zh/en 同步）；验证两条资源文件 grep 到新增 key 且 `voice_model_ready` 已改，`./gradlew :app:compileDebugKotlin` 或 lint 无资源缺失报错。
- [ ] 1.2 在两语言资源新增 `voice_permission_denied_forever`（麦克风权限已被拒绝，若系统不再弹出请到「系统设置 → 应用 → 智语 iSpeech → 权限」中开启麦克风 / 对应英文）与 `voice_permission_open_failed`（打不开授权页面，请在「系统设置 → 应用 → 智语 iSpeech → 权限」中开启麦克风 / 对应英文），确认长文案含换行描述、无 `shortText()` 截断依赖；验证 grep 命中且长度/换行满足 UX §5.3 预算。

## 2. 双引擎未授权提示态（去自动跳页）

- [ ] 2.1 三处未授权分支（`IspeechImeService.kt` 的 `toggleVoiceInput`、`toggleFunAsrVoice`、`startFunAsrListening`）删除自动 `requestMicPermission()`，改为设置合并提示态（`statusText = R.string.voice_need_permission` + 一个服务侧显式 blocked 标志）后渲染；验证：相关单元测试（Mockito 桩 `checkSelfPermission` 返回未授予）断言 `startActivity` 未被调用、`renderCandidateView` 被调用，测试先红后绿，且 `tools/verify/check_crosscut.py` 中 8.4 节的自动代申请断言按新设计改为「未授权时 URL 跳转仅由用户点击触发」，并同步 `tools/verify/README.md` 说明。
- [ ] 2.2 `startFunAsrListening`（`:1777`）、`toggleFunAsrVoice`（`:1683`）、`toggleVoiceInput`（`:1570`）的未授权分支 return 前补 `commitPending()`，保证组合码缓冲非空时先上屏；验证：单元测试构造 buffer 非空 → 触发未授权分支 → 断言 `commitTop` 路径被执行（`currentInputConnection` 收到上屏文本），先红后绿。

## 3. 候选条第 2 行授权按钮

- [ ] 3.1 `candidate_view.xml` 第 2 行、`candidate_scroll` 与 `candidate_settings` 之间、`itemContainer` 之外新增 `TextView candidate_mic_action`（默认 gone，文字色 `#1A1A1A`，padding 补到 ≥44dp 高 × ≥64dp 宽，与相邻控件净距 ≥8dp，`contentDescription` 显式）；验证：inflate 后 `findViewById` 非空且默认 `GONE`。
- [ ] 3.2 `CandidateView.update()` 增加显式「授权动作可见 + 按钮文案」入参（不由第一行文本推断），按钮出现/消失随该入参与 `statusText` 同生命周期；验证：`androidTest`/`robolectric` 测试断言「传入动作可见 → 按钮 VISIBLE 且文案正确」「未传入 → GONE」「第 1 行组合码仍只读不可点」。
- [ ] 3.3 服务侧 `renderCandidateView` 与各未授权分支接入该入参；点击回调只走一次由用户显式触发的跳转（跳转在途置 disabled，防连点两弹框），`click` 绑定、不接空格/回车/数字键；验证：新增「提示挂着时按空格/数字键必须不跳页」测试用例（`IspeechImeService` 单测 + 交叉验证脚本断言回调未绑定到 `commitTop`/`pickCandidate`），先红后绿。

## 4. 跳页异常与回场（X1 / AC-4）

- [ ] 4.1 `requestMicPermission()`（保留函数名以复用判定，语义改为「用户触发」）的 `catch(ActivityNotFoundException)` 设置 `voice_permission_open_failed` 状态文本 + 按钮保留可再试一次，并加结构化日志，不自动重试、不循环弹；验证：单测以伪造 resolve 失败的 intent 走 catch，断言 `statusText` 命中错误文案且仅 `Log.*` 一次。
- [ ] 4.2 `settings/MainActivity` 在 `REQUEST_MIC_EXTRA=true` 时，授权结果回调结束后 `finish()` 回场原输入框；验证：`androidTest` 断言授权流程结束后 Activity `isFinishing` 为真；QA TC-02（拒绝后不再停在设置页）回归转 PASS。

## 5. 副入口（E2/E3）与集成验证

- [ ] 5.1 `SettingsPanel` 新增常驻「麦克风权限：未开启 › / 已开启」行（复用 `buildChoiceRow`，实时 `checkSelfPermission`，已开启时无跳转去掉 `›`）；验证：面板打开时该行可见，点击未开启态触发用户授权跳转。
- [ ] 5.2 `AsrSettingsScreen`（设置页语音识别）补权限状态行 + 「开启麦克风」入口（键盘/TalkBack 焦点可达）；验证：Compose `androidTest` 断言入口 `contentDescription` 正确且可聚焦。
- [ ] 5.3 运行 `tools/verify/check_crosscut.py`（含修订后的 8.4 节）与 `./gradlew :app:testDebugUnitTest :app:lintDebug` 全绿；同步更新 `docs/voice-input.md`（记录 AC-1…AC-10 与 12 态映射、合并文案决策、回场规则、已知 R2 精化）与 README 变更点；验证：脚本退出码 0、测试全绿、文档提及 E1/E2/E3 与合并文案。

## Workflow follow-up

- 由 QA 按 AC-1…AC-10 在 `qa166_test`（AOSP 14）+ 报告人真机 `FMR0224913042917` 各复跑一遍（新增 AC-9/AC-10 反例），P1 清零条件为全 PASS。
- 架构师确认 U1/U2（`ACTION_APPLICATION_DETAILS_SETTINGS` 契约、一次性/永久拒绝标记）后，可做 S3 按钮转「去设置」的 R2 精化，不阻塞本批。
- 交付分支 + PR，squash merge 后归档 OpenSpec 变更。