# Proposal

## Why

VOCA-166：未授予录音权限时，用户按下/按住语音键（或长按空格）会触发 `startActivity` 拉起设置页，输入法被整个顶掉、宿主 App 退后台，用户回不到正在打字的输入框。这是 P1 缺陷，是「碰一下语音键就丢了输入场景」的数据风险级体验，必须先于任何语音能力上线修复。

## What Changes

去掉「未授权即自动跳设置页」的行为，改为在输入法内给出可读提示与用户主动触发的授权入口，并覆盖永久拒绝、首启模型未下载、回场三个此前完全缺失的分支：

- 三处未授权分支（系统引擎 `toggleVoiceInput`、FunASR 点按 `toggleFunAsrVoice`、FunASR 按住 `startFunAsrListening`）在 `RECORD_AUDIO` 未授予时不再自动 `requestMicPermission()`，只渲染提示态与授权入口；前台 Activity 保持不变。
- 候选条第 2 行新增独立授权入口按钮（在 `candidate_items` 之外的兄弟控件），仅指针点击触发、且只在未授权态出现；永久拒绝态按钮变为「去设置」并给系统设置路径文案。
- 组合码未上屏时触发语音入口：先 `commitPending()` 上屏，再进入提示态，杜绝「缓冲码被状态文本盖住且不清空」的新丢字缺陷。
- 由 IME 发起的授权流程结束必须回场到原输入框（设置页结果回调后 `finish()`），拒绝/允许后回到键盘而非停在设置页。
- 新增 zh/en 文案（去授权/去设置/永久拒绝路径/授权成功回场/模型就绪还需权限/授权页打不开），并修正既有 `voice_model_ready` 文案的「点击」为「按住」。
- 「去设置」跳转目标（系统应用信息页 `ACTION_APPLICATION_DETAILS_SETTINGS`）与一次性/永久拒绝状态存放属于跨 Activity 契约，本提案按「合并文案 + 按钮文字区分」的降级方案落地（详见 design 与待确认项），不堵塞主路径。

## Capabilities

### New Capabilities
- `voice-input`: 语音入口（语音键按下/按住、长按空格）在四种权限态（未授权 / 拒绝一次 / 永久拒绝 / 已授权）与首启模型未下载态下的可观察行为——输入场景不丢失、提示可读、授权入口 ≤2 步可达、授权后回场、全程不崩溃。

### Modified Capabilities
- `<none>`: 当前 `openspec/specs/` 无既有能力规格。

## Impact

- 代码：`IspeechImeService.kt`（三处未授权分支 + `requestMicPermission`/跳页异常处理 + `commitPending` 时机 + `renderCandidateView` 授权态渲染），`CandidateView.kt`/`candidate_view.xml`（第 2 行新增授权按钮），`SettingsPanel.kt`（E2 副入口一行），`settings/MainActivity.kt`（授权结果回调 + 回场 `finish()`）。
- 资源：`res/values/strings.xml`、`res/values-en/strings.xml` 新增/修订 6 条文案。
- 不改变：已授权态的语音面板与上屏语义（S4/AC-7）、模型下载链路（S5/S6/AC-10 只保证键盘不消失）、`AsrEngine.SYSTEM` 与 FunASR 的识别接口。
- 不在本 issue：单击/按住语义取舍、74MB 下载顺序（Q1）、设置页落点 csBaby 登录页、D-2「设置 → 键盘与输入」必崩（已单开 VOCA-170）。