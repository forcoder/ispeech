# 语音输入（复刻搜狗五笔）

本页记录「复刻搜狗五笔语音输入」的事实来源、本实现接线、复刻义务清单与已知差异。
状态以 `app/src/main/java/com/ispeech/` 下的实际代码为准；**没有证据的行为一律写「未观测」**，
不用推测补齐。

## 一、原产品侧（搜狗五笔）

| 事实 | 证据 | 状态 |
| --- | --- | --- |
| 搜狗五笔有「语音」功能入口（功能图标 `ime_function_voice.png` / 按下态 `_p`） | 本机制品 `/Library/Input Methods/SogouWBInput.app` 内资源清单，见 `docs/icon-replication.md` 的 `ime_function_*` 行 | 已观测（仅证明入口存在） |
| 搜狗的语音交互形态（面板 / 波形 / 松手自动上屏 / 实时上屏候选） | — | **未观测**（未取到可回溯的运行时证据） |
| 搜狗语音使用的识别引擎 / 是否端侧 | — | **未观测** |

> 结论：目前能证明的只有「搜狗有语音入口」。面板级交互细节没有证据，因此本实现按
> 「系统识别器 + 候选条」落地，并在下表中显式记为「有差异」，不假装对齐。

## 二、本实现接线（零第三方 SDK）

```
工具栏语音键（KeyAction.VOICE + ic_voice）
  └─ 按住 / 抬起（SoftKeyboardView.installVoiceGesture；上滑到取消区 = 丢弃）
       └─ IspeechImeService.onVoicePressDown / onVoicePressUp
            ├─ FunASR：startFunAsrListening() / funasrVoice.finish()
            └─ 系统引擎：toggleVoiceInput() / VoiceInputController.finish()
                 ├─ 未授权：showMicPermissionPrompt() 只出候选条提示，不跳页；用户点「去授权」才 openMicAuthorization()
                 │    ├─ 系统还会弹框：拉起设置页 MainActivity（extra REQUEST_MIC_EXTRA）代弹授权框
                 │    └─ 已永久拒绝：拉起本应用「系统设置 → 应用 → 智语 iSpeech → 权限」页
                 │         └─ 会话结束 MainActivity.finish() 回到原输入框（不停在设置页）
                 ├─ 开始收音：Panel.VOICE（VoicePanelView：实时文字 + 音量波形）
                 ├─ onPartialResults -> voicePanel.setListeningText()（只进面板，不占候选条）
                 ├─ onResults       -> closeVoicePanel() + commitVoiceText()（松手即上屏）
                 └─ 6s 无回调       -> finishWatchdog 复位状态并提示「没听清」
```

| 关注点 | 位置 |
| --- | --- |
| 识别器封装 | `app/src/main/java/com/ispeech/input/VoiceInputController.kt` |
| 链路埋点 | `app/src/main/java/com/ispeech/input/AsrTrace.kt`（见 §七） |
| 服务状态机 | `app/src/main/java/com/ispeech/input/IspeechImeService.kt`（`toggleVoiceInput` / `showVoiceCandidate` / `commitVoice` / `cancelVoice` / `applyAsrChange`） |
| 设置模型 | `app/src/main/java/com/ispeech/settings/store/AsrSettings.kt`（`AsrEngine` / `AsrLanguage` / `AsrSettings` / `ASR_MODEL_EXTRA`） |
| 持久化 | `SettingsStore`（`asrSettingsFlow` / `asrSettings` / `setAsrSettings`） |
| 设置页 | `settings/asr/AsrSettingsScreen.kt` + `AsrSettingsViewModel.kt`（导航路由 `IspeechRoutes.ASR`，首页 ASR 卡片） |
| 输入法内面板 | `input/SettingsPanel.kt`（引擎 / 语言 / 参数分项，`onAsrChange -> applyAsrChange`） |
| 权限 | `AndroidManifest.xml`（`RECORD_AUDIO` + `queries/RecognitionService`，`MainActivity` 需 `singleTask`）、`input/MicGrantAction.kt`（提示态该显示哪个动作：纯决策，可单测）、`settings/MainActivity.kt`（`REQUEST_MIC_EXTRA` 代申请 + 会话结束 `finish()` 回场）、`SettingsStore.micPermanentlyDeniedFlow`（永久拒绝提示，仅作文案提示，是否可授权一律以 `checkSelfPermission` 实时结果为准） |
| 文案 | `res/values/strings.xml` / `values-en/strings.xml`（`voice_*` 6 项 + `asr_*` 19 项，中英同名） |

健壮性（真机踩坑后加）：

- `ERROR_RECOGNIZER_BUSY`：识别器在系统侧未完全释放时**原地重建重试一次**，避免「点了没反应」。
- 松手收尾的兜底：`finish()` 后 6s 仍未收到 `onResults` / `onError` 时由 `finishWatchdog` 复位并提示「没听清」——按住-说话没有「再点一次」可用于恢复，不复位就会卡在「收音中」。
- 取消即 `destroy()`：只 `stopListening()` 会让系统继续占麦克风，导致下一次 `ERROR_RECOGNIZER_BUSY`；因此取消 / 销毁都释放识别器。
- `EXTRA_CALLING_PACKAGE`：输入法不是宿主，部分 ROM 缺该 extra 会直接回 `ERROR_CLIENT`，故透传 `currentInputEditorInfo.packageName`。

## 三、复刻义务清单

| # | 类别 | 义务（要做成什么） | 原产品行为 | 证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| O-01 | 功能 | 工具栏提供语音入口 | 有语音入口（图标 `ime_function_voice.png`） | `docs/icon-replication.md` | 已复刻（`KeyAction.VOICE` + `ic_voice` 矢量图标） |
| O-02 | 识别 | 语音转文字 | 未观测 | 系统 `SpeechRecognizer`；真机 logcat 绑定识别服务见 `docs/v1.5-verify-log.md` §5 | 已复刻（依赖系统识别服务） |
| O-03 | 交互时序 | 识别结果如何上屏 | 未观测（推测为面板 / 松手自动上屏） | `SoftKeyboardView.installVoiceGesture` + `IspeechImeService.onVoicePressDown` / `onVoicePressUp` | 已对齐（按住说话 → 语音面板实时文字 + 波形 → 松手自动上屏；上滑进取消区丢弃）。**系统引擎与 FunASR 同形态**，部分结果只进面板、不再占候选条 |
| O-04 | 边界与错误 | 未授权 / 无匹配 / 识别器忙 | 未观测 | `VoiceInputController.errorMessage`、BUSY 重试；§5 记录 5s 超时优雅收尾 | 已复刻 |
| O-05 | 功能 | 识别配置（引擎 / 语言 / 参数） | 未观测 | `AsrSettings` + 设置页 + 输入法内面板 | 已复刻（含指定服务组件 / 优先离线 / 模型名透传） |
| O-06 | 文案 | 中英文案 | 未观测 | `strings.xml` / `values-en`（`voice_*` + `asr_*` 同名） | 已复刻 |
| O-07 | 图标与视觉 | 语音入口图标 | `ime_function_voice.png` / `_p` | `docs/icon-replication.md` 记录原始尺寸与形态 | 已复刻（`ic_voice` 矢量图标 + 键样式着色；原厂 PNG 仅作证据，不复用分发） |
| O-08 | 数据 | 语音上屏是否计入统计 | 未观测 | `commit()` -> `recordCommit(CANDIDATE)` | 已复刻（按候选档计入字数） |
| O-09 | 验收 | 真实语音转写准确率 | 未观测 | — | **未观测**（真机未朗读话筒，需补真机朗读用例） |

## 四、证据

| 事实 | 证据 |
| --- | --- |
| 语音链路真的起来了、麦克风真的被采集 | `docs/v1.5-verify-log.md` ①（真机 Huawei：`phone8.png` 状态条「语音识别中...」、`logcat` 绑定 `com.huawei.vassistant/.voiceui.service.FakeRecognitionService`）与 §5（模拟器：候选条「Listening…」+ 系统麦克风指示） |
| 状态机能优雅收尾、不卡「聆听中」 | 同上 §5：5 秒后候选条变「Voice recognition failed」、麦克风指示消失、输入法未崩溃 |
| 中英文案齐全 | `tools/verify/check_crosscut.py` 第 8 节（`voice_*` / `asr_*` 中英键名一致性断言） |

## 五、已知差异与限制

- **交互形态**：两种引擎统一为搜狗式「按住说话 + 语音面板（实时文字 / 波形）+ 松手上屏」，上滑进取消区丢弃本次识别。**残留差异**：面板额外占一行、放在软键盘**上方**（而非替换键盘区域）——被按住的语音键 View 一旦 GONE，系统会立刻补发 `ACTION_CANCEL`，`ACTION_UP` 永远到不了，表现为「松手什么都不上屏」，因此键盘必须保持可见。原产品面板行为未观测，不做逐像素对齐。
- **识别能力**：取决于设备上的 `RecognitionService`；设备无识别服务时入口直接提示「设备不支持语音输入」；离线能力取决于该服务是否支持 `EXTRA_PREFER_OFFLINE`。
- **模型名**：走应用自有 extra `com.ispeech.extra.ASR_MODEL`（非 Android 标准常量），仅识别服务主动读取时生效。
- **待补验收**：真机朗读一段话，验证转写文本与准确率（O-09）。

### 麦克风授权入口（VOCA-166）

按语音键时**没有录音权限**，过去的做法是立刻 `startActivity` 拉起设置页，宿主随之被切到后台、
整个输入法消失，用户还没说话就断了输入场景。现在的规则：

| 场景 | 行为 | 代码位置 |
| --- | --- | --- |
| 未授权（系统还会弹框） | 候选条显示合并提示文案 + 右侧「去授权」，**不跳页**；用户点按钮才代申请 | `IspeechImeService.showMicPermissionPrompt` / `openMicAuthorization` |
| 永久拒绝（不再弹框） | 同一提示补「系统设置 → 应用 → 智语 iSpeech → 权限」路径，按钮转「去设置」，跳本应用应用信息页；该页起不来才回落设置页代申请桥 | `MicGrantAction.jumpPlan` + `launchMicIntent` + `SettingsStore.micPermanentlyDeniedFlow` |
| 授权结束（允许 / 拒绝 / 返回） | `MainActivity.finish()` 回到原输入框，不停在设置页；输入法重新显示时按实时权限清提示 | `MainActivity.finishMicSession` / `IspeechImeService.onWindowShown` |
| 授权成功后回到键盘（S8） | 候选条给一次性指引「麦克风权限已开启，点击麦克风开始说话」，任何按键即清；ADR-001 决策 4 不承诺宿主重显键盘，这一句是用户回来后的下一步 | `onWindowShown` + `voice_permission_granted` |
| 提示期间用户打字 / 切面板 / 上屏 | 提示与按钮立即消失（提示文案与 `statusText` 同生命周期，无需额外清理钩子） | `CandidateView.update` |
| 跳转失败（ROM 拦截 / 无应用信息页） | 提示改为「打不开授权页面…」并保留按钮可再点，同时打一条含异常类型、带栈的 `mic_auth_jump_failed` | `launchMicIntent` 的 `catch (Throwable)` → `showMicJumpFailure` |

跳转的三条不变量：① **只由用户点击发起**，语音键本身永不改前台（AC-1）；② 一次点击**最多成功跳一次**
——按 `jumpPlan` 逐项尝试、首个成功即止，且 in-flight 期间按钮转灰，连点不会叠出两个授权框（AC-8）；
③ 跳转包在 `catch (Throwable)` 里——这些调用跑在**输入法进程**的点击回调上，未捕获的 `SecurityException` /
`IllegalArgumentException`（EMUI 后台弹窗拦截）就是一次 IME 崩溃、键盘整个消失，正是本 issue 要修的症状。
按钮的文案与可用性只从 `CandidateView.update()` 一个入口渲染，视图侧不另开状态写入通道。

「永久拒绝」标志只是**文案提示**，不作为能否授权的门槛：能不能录音每次都以
`checkSelfPermission` 实时结果为准，标志写错也不会挡住已经授权好的用户。

未覆盖（另议，不在本 issue 范围）：键盘内设置面板的常驻权限行（E2）、设置页语音识别处的
「开启麦克风」入口（E3）。

## 六、中文输入法（拼音 / 五笔）

| 关注点 | 说明 |
| --- | --- |
| **默认行为** | 中文场景下，默认使用拼音输入法。 |
| **可选输入法** | 「设置」→「键盘与输入」→「中文输入法」卡片，单选「拼音」或「五笔」；键盘内置「设置」面板顶部也有同一选项。 |
| **中/英切换** | 中/英键只在「中文态 ⇄ 英文态」之间切换；从英文切回中文时固定使用设置选定的中文输入法。设为五笔后，切换顺序是「五笔 ⇄ 英文」，不会再经过拼音。 |
| **生效时机** | 在设置页或键盘内置面板切换后立即生效；中文态会立刻切到新输入法，英文态保持到下次切回中文。 |
| **五笔候选** | 五笔态仍加载五笔词库 / 用户词库，候选以五笔编码优先；「拼音混输」开关打开时保留拼音兜底候选。 |
| **影响范围** | 仅决定中文态默认输入法与中/英切换目标，不影响英文态、语音识别、符号面板等其他功能。 |

> 回归用例：`InputModePolicyTest` 覆盖「默认五笔 ⇄ 英文」「默认拼音 ⇄ 英文」以及从英文回到中文时以设置为准。

## 七、ASR 业务埋点（`AsrTrace`）

**为什么有这一节**（VOCA-231）：真机上语音键卡在「语音识别中…」长时间不复位，而 `logcat` 里
**一条 ASR 业务打点都没有** —— 既有日志全在 `catch` 分支，而「卡住」不抛异常，于是不可诊断。
埋点把「选了哪个引擎 → 服务 / 模型是否就绪 → 开始收音 → 首个结果 → 结束 / 出错 / 超时 → UI 是否复位」
逐段落成一行，**只加日志、不改任何行为**。

- **TAG**：`AsrTrace`（全链路单 TAG，一条命令抓完整会话）
- **实现**：`app/src/main/java/com/ispeech/input/AsrTrace.kt`
- **会话 id**：`voiceSessionId`，每次按键（含被拦下的按键）新开一个，串起同一次语音输入的所有行

```
adb logcat -d -s AsrTrace:* | grep -c 'session='          # 有没有打点（0 行即为缺陷本身）
adb logcat -d -s AsrTrace:* | grep 'session=v3'           # 追一次会话
adb logcat -d -s AsrTrace:* | grep -E 'ev=(timeout|error|blocked)'   # 只看异常
```

| 事件 `ev=` | 打点位置 | 说明 |
| --- | --- | --- |
| `begin` | `IspeechImeService.startVoiceTrace` | 一次**真正开始**的语音会话：`via`（`voice_key` / `press_down`）、引擎、进入时的 UI 与收音状态 |
| `blocked` | `IspeechImeService.toggleVoiceInput` / `toggleVoskVoice` / `onVoicePressDown`、两个引擎的 `start`、`VoiceInputController` 前置校验 | 被拦下：无服务 / 无可用识别服务 / 无麦克风权限 / 模型未就绪 / 已在收音（沿用上一个没结束的 id，所以「再按也没反应」会打在卡住那条会话上） |
| `bind` | `VoiceInputController.logBind` | **实际会绑到谁**（`effective=`：CUSTOM 取配置组件，否则读 `Settings.Secure.voice_recognition_service`）+ 设备上识别服务数量与桩的数量（`usable=` / `stubs=`）。`first_queried=` 只是 PackageManager 查询顺序第一项，**不是**绑定结果 |
| `press` | `IspeechImeService.onVoicePressUp` | 按住说话的**抬起**（按下由 `begin via=press_down` 表示）；只有按下没有抬起 = 手势没回来、收尾没触发 |
| `start` | `VoiceInputController.startListening`、`VoskSpeechEngine.start` / `FunAsrSpeechEngine.start`、`startVoskModelDownload` | 已下发开始收音；带 `lang` / `partial` / `offline` / `model` / `calling` / `watchdog` |
| `first_result` | 三个引擎的中间结果首条 + `IspeechImeService.showVoiceCandidate` | **本次会话有没有回调过文本**（只打首条，不刷屏） |
| `end` | `VoiceInputController.finish` / `stopListening` 分支、离线引擎 `finish` | 进入「等收尾结果」阶段，带 `limit_ms` |
| `result` / `empty` | `VoiceInputController.onResults`、离线引擎收尾、模型下载收尾 | 会话闭环；`empty` = 回调了但没有文本 |
| `error` | `VoiceInputController.onError`（带 `code` + `name`）、离线引擎各失败阶段 | 失败原因类别 |
| `timeout` | `VoiceInputController` 的 `startWatchdog` / `sessionWatchdog` / `finishWatchdog`、两个离线引擎的 `startWatchdog` / `finishWatchdog` + 收音循环、`IspeechImeService.armVoiceSessionGuard` | 兜底超时触发并复位；`stage=` 区分是哪一段超时（取值见 §八） |
| `retry` | `VoiceInputController.onError`（识别器忙）、CUSTOM 建识别器失败回退 | 原地重试 / 静默回退不再静默 |
| `cancel` | `VoiceInputController.stop` / `destroy`、`IspeechImeService.cancelVoice` / `resetState` | 会话被丢弃 |
| `commit` | `IspeechImeService.commitVoiceText` | 最终结果写入输入框（只记长度） |
| `ui_reset` | `IspeechImeService.endVoiceSession`（语音会话的单一出口，见 §十一） | **UI 是否离开「识别中」——卡住的直接判据**；`reason=` 取值 `candidate_commit`（点候选条上屏）/ `space_commit` / `engine_result` / `engine_error` / `user_stop` / `backspace` / `escape` / `session_timeout` / `reset_state` |

隐私：只记长度（`chars=`）与状态位，**绝不**记录识别文本、输入框内容或宿主编辑字段（共享规程 §8 / §9）。

单测：`app/src/test/java/com/ispeech/input/AsrTraceTest.kt`（行格式、缺省占位、空白压平、id 递增）。

**判读口径**（真机采集时直接分辨触发源）：

| 日志形态 | 结论 |
| --- | --- |
| 有 `begin` 无 `press` / `end` | 抬起事件没回来、收尾没被触发（现在会由 `timeout stage=session` 兜住） |
| 有 `start` 无 `first_result` | 识别服务收了会话不回话（现在会由 `timeout stage=start` 在 10 秒内复位） |
| 有 `first_result` 无 `result` / `ui_reset` | 只回中间结果、收尾不来（现在会由 `timeout stage=finish` 兜住） |
| 有 `ui_reset` 且 `ok=true` | 链路正常，UI 已离开「识别中」 |

`grep -c 'session='` 仍是「这个包到底有没有埋点」的判据；`grep -E 'ev=(timeout|error|blocked)'` 直接列出异常。

## 八、语音「识别中」的超时收敛（VOCA-231）

§七 的定位结论是「卡住 = UI 状态机没复位」，根因是**全链路只有一处超时**（松手后等收尾 6 秒），
而且它只在收到抬起事件之后才挂表 —— 抬起事件丢失、识别服务不回话、离线引擎卡在 `AudioRecord.read()`
三种情况都没有任何时限，语音键于是永久卡在「识别中」，之后每次按键再开始的尝试又被「已在收音」挡回。

**时限集中在一处**：`app/src/main/java/com/ispeech/input/AsrTiming.kt`（共享规程 §7：不散落魔法值）。

| 常量（毫秒） | 覆盖的阶段 | 到点做什么 |
| --- | --- | --- |
| `FIRST_CALLBACK_TIMEOUT_MS = 10_000` | 已下发开始收音 → 第一个回调（系统识别的 `onReadyForSpeech` 等 / 离线引擎真正开始收音） | 复位 + `voice_timeout_service` |
| `SESSION_TIMEOUT_MS = 60_000` | 系统识别「收音中」硬上限（覆盖按住不放、抬起事件丢失） | 自动按松手处理（走 `finish()`，已说的话还能上屏） |
| `FINISH_TIMEOUT_MS = 6_000` | 松手 / 二次点击 → 最终结果 | 复位 + `voice_timeout_result` |
| `UI_SESSION_TIMEOUT_MS = SESSION + FINISH` | UI 侧最后一道（引擎彻底没接住时） | 丢弃会话 + `voice_timeout_session` |
| `NO_AUDIO_TIMEOUT_MS = 10_000` | 离线引擎收音循环连续拿不到样本 | 退出自旋 + `voice_audio_error` |

任何一条超时都必须做满三件事：①复位状态 ②给用户可读文案（`AsrTiming.messageFor(stage)`）
③打 `ev=timeout` + `stage=`。缺任一件视为未完成（角色规程：禁止静默失败）。

分层：引擎管**自己阶段**的时限（`VoiceInputController` 三段、两个离线引擎各两段 + 收音循环内的
无音频判定）；`IspeechImeService.armVoiceSessionGuard` 管**整段 UI**，比引擎侧晚一个收尾窗口，
所以「自动收尾让已说的话落地」这条正常路径不会被 UI 抢先掐断。离线引擎的 `cancel()` / `destroy()`
与超时复位都**就地**把 `isListening` 置回 false —— 工作线程可能永远不返回，等它收尾等于永远等不到。

`start` 埋点里的 `watchdog=` 字段就是这一节的接线状态：系统识别 `start+session+finish`、
离线引擎 `start+finish`；`watchdog=none` 已经不再出现（静态检查会拦住回退）。

回归：`AsrTimingTest`（时限层级、UI 兜底与收尾窗口的关系、每类超时都有独立可读文案）
+ `check_crosscut.py` 8b 段（时限只在 AsrTiming 定义、三段都挂表、收尾都摘表、中英文案齐全）。

**仍未覆盖（下一步）**：抬起事件丢失的护栏目前是「60 秒后自动收尾」，没有把「再按一次 = 结束上一次」
改成显式语义；桩识别服务（华为 `FakeRecognitionService`）仍需用户在设置里换引擎，代码只能早 10 秒
告知，不能替它出结果。

## 九、真机复测前置核查（2026-11-16 实测，设备 `FMR0224913042917`）

原始报障机回来了，但**装不上本单的诊断包**，且**这台机器跑不到 §八 的超时分支**。两条都是先跑
只读命令得到的，不是推断；复测前先把这一节的两道门禁过掉，否则会像上一轮那样「包身份四项全对却装不上」。

### 9.1 现装包身份与签名门禁

| 项 | 机上现装 | 本单诊断包 |
| --- | --- | --- |
| versionCode / versionName | `6` / `1.6` | `5` / `1.5` |
| base.apk sha256 | `9cddcec5a88d53017c5680eb07ba52e643d523d273f5d3649180df44f8d43f42` | `1f21e23c21b95ae580619be5558e560a85f4ac5aad6b128d2a3277c191f17621` |
| 签名证书 | `CN=Android Debug` → `857ce64e…a0ed` | `CN=iSpeech Prototype` → `70b4f4a2…2ac7` |
| `lastUpdateTime` | 2026-11-15 10:29:20 | — |
| 是否含 §七/§八 代码 | **否**：dex 里 `AsrTrace` / `AsrTiming` / `armVoiceSessionGuard` / `voice_timeout_result` 全 0 命中，`FunAsrSpeechEngine` 7 命中 | 是 |

分支归属（推断，非包内构建元数据坐实）：`origin/main` 是 `versionCode 5 / 1.5`，只有
`origin/release/voca-186-v1.6` 是 `6 / 1.6` 且 `AsrSettings` 默认引擎仍为 `FUNASR` —— 与上表的版本号
和 dex 符号同时吻合，所以判定机上装的是 186 那条发布线的包。要坐实只需在出包机上比对 `dist` 产物的 sha256。

- 签名者不同 → `adb install -r` 必失败（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）；再叠加 6→5 是降级，
  还需 `-d`。**唯一可行路径是先卸载现装包 = 清空应用数据**（学习词库、设置、剪贴板），属生产操作，
  需要人类批准后才执行；换包后 `RECORD_AUDIO` 会回到未授权状态。
- EMUI 的 `pm install` 不支持 `--dry-run`（实测 `Unknown option --dry-run`），**没法在不改设备状态的前提下
  预验可安装性**；只读预验只能做到上表：`pm path` → `adb pull` → `apksigner verify --print-certs` + `dumpsys package`。
- 注意：dex 在 APK 里是压缩存储的，直接对 `.apk` 做 `grep` 只能命中 zip 条目名（`assets/…sherpa-onnx…`），
  **判类名/字符串必须先 `unzip classes*.dex`**。用 `pm path` 的结果直接 `grep` 会得出假的「0 命中」。

### 9.2 这台机器上 `ev=timeout` 跑不到（服务清单实测）

| 命令 | 实测结果 |
| --- | --- |
| `cmd package query-services -a android.speech.action.RECOGNIZE_SPEECH` | `No services found`（0 个） |
| `cmd package query-services -a android.speech.RecognitionService` | 1 个：`com.huawei.vassistant.voiceui.service.FakeRecognitionService`，`permission=android.permission.BIND_VOICE_INTERACTION` |
| `settings get secure default_speech_recognizer` | `null` |
| `dumpsys package com.ispeech.ime`（权限） | `RECORD_AUDIO: granted=true (USER_SET)` |

∴ `VoiceInputController.hasUsableService()` 在这台机器上恒为 false —— 判据是 `serviceInfo.permission ==
BIND_SPEECH_RECOGNITION_SERVICE`，而唯一的服务声明的是 `BIND_VOICE_INTERACTION`。**含义**：即使 VOCA-310
把查询 action 修对，这里仍然是 `services=1 usable=0`，`SYSTEM` 引擎只会出 `ev=blocked`。
要验 §八 的 `timeout stage=start/finish/session` 三条分支，只有两条路：① 有真实识别服务的机器（基线机
`23F0224224000950` / ALN-AL10，验收口径要求的正是它）；② 离线引擎路径（`model_not_ready` / 无音频判定）。
看到 `ev=blocked` 不算 §八 的超时分支被验证，也不算回归。

### 9.3 报障现象的根因链（补齐）

报障包 `agent/agent/voca-121@e8dc6f1` 的默认引擎是 `AsrEngine.FUNASR`（`AsrSettings.kt:57`，离线
sherpa-onnx），它的模型源 `http://cvm.agentai0.com/funasr/sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`
实测返回 **HTTP 502**（域名可解析 43.136.44.72，源站挂着）→ 模型下不来；而当时的离线引擎路径**没有任何
时限**，UI 于是停在「语音识别中…」不动。`voca-186-v1.6` 起模型源已换成阿里云 OSS，实测 Range 请求返回
**206 / 1KB 可读** —— 换源这件事不在本单范围，本单解的是「源挂了、服务不回话、引擎卡住时必须按时复位并把
原因打进日志」，对应 §七 的 `blocked reason=model_not_ready model_dir=`。

仍未解释的一条：报障截图里那个单候选「饭」。模型若从未下成，离线引擎不该出字；但现装包非 debuggable
（`run-as: package not debuggable`），拿不到 `filesDir` 去证明模型在不在。复测时按日志判，不要靠猜——
`ev=start model_ready=true` 与 `ev=first_result` 同时出现才说明那次出字来自 ASR。

## 十、`hasUsableService()` 恒为假：语音键根本进不到「识别中」（2026-11-16 实测坐实并修复）

§九 只解释了「这台华为机器为什么跑不到超时分支」。本轮在 API 34 `google_apis` 镜像（AVD
`ispeech_test`，`sdk_gphone64_arm64`）上用只读的 PackageManager 查询复核，发现判据本身是错的，
而且**不止华为那一台**：

```
$ adb -s emulator-5554 shell cmd package query-services -a android.speech.action.RECOGNIZE_SPEECH
No services found                                    ← 旧代码查的就是这个 action，恒空

$ adb -s emulator-5554 shell cmd package query-services -a android.speech.RecognitionService
2 services found:
  AiAiSpeechRecognitionService   (com.google.android.as)   permission=null
  GoogleTTSRecognitionService    (com.google.android.tts)  permission=null

$ adb -s emulator-5554 shell pm list permissions | grep -iE 'speech|recogn'
permission:android.permission.MANAGE_SPEECH_RECOGNITION
permission:android.permission.BIND_MUSIC_RECOGNITION_SERVICE      ← 没有 BIND_SPEECH_RECOGNITION_SERVICE
$ … | grep -c BIND_RECOGNITION_SERVICE  → 0                        ← 旧代码比对的字符串在系统里不存在
```

**结论**：修复前 `hasUsableService()` = 「用 Activity 的 action 查服务」∩「权限恰好等于一个不存在的
字符串」，两个条件各自都恒假，所以它在**任何**机器上都返回 false。`IspeechImeService.kt:1585` 拿它当
语音键的前置门禁，于是每次点麦克风都直接落到 `blocked reason=no_usable_service` + 「服务不可用」，
`SYSTEM` 引擎永久不可用，本单要复测的 `ev=timeout stage=` 三条分支也就永远复测不到。

判据不能反过来写成「权限必须等于 `BIND_SPEECH_RECOGNITION_SERVICE`」——实测真服务声明的是
`permission=null`，那样写还是恒假。现在的规则只拦**实测唯一确认的桩特征**（语音助手的
`BIND_VOICE_INTERACTION`，见 §9.2），其余一律放行：误拦的代价是功能永久不可用且没有日志可循，
误放的代价由 §八 的 `FIRST_CALLBACK_TIMEOUT_MS` 在 10 秒内复位并给出可读文案，是可恢复的错误。
判据抽成纯函数 `isUsableRecognitionService()`，四种实测取值都有单测
（`RecognitionServiceUsabilityTest`）。

`ev=bind` 因此多了一个 `stubs=` 字段：`services=2 usable=2 stubs=0` 说明这台机器该正常出字，
`services=1 usable=0 stubs=1` 说明命中的是助手桩、该换引擎——这两种情况以前在日志里长得一模一样。

**与 VOCA-310 的关系**：310 登记的是「查询 action 用错」这半条，本轮把 action 与权限判据一起改了，
因为只修 action 在这台 AVD 上仍然 `usable=0`（真服务 `permission=null`），本单验收照旧过不去。
310 的代码部分已并入本 PR，剩下的应是真机验证与关单。

## 十一、语音会话的单一出口（VOCA-231，架构复审 §一 的行为回归）

**现象**（静态可达路径，2026-11-17 架构复审提出）：一次已经上屏成功的语音会话，66 秒后仍会打出
`ev=timeout stage=session`，把用户当时正在看的候选（很可能是拼音联想）清空、
覆盖成「说话时间过长，本次语音输入已自动结束」。

**根因**：UI 侧兜底表按 `voiceActive || 系统识别器 isListening || 离线引擎 isListening` 判「还卡在识别中」，
而「结束一次语音会话」这件事在旧实现里散落在 7 处改写里，其中点候选条上屏那条——
`bar.onCandidatePicked → pickCandidate() → commit()`——只写了 `voiceActive = false`：
既没停识别器，也没摘兜底表。识别器在 `onResults` 之前 `isListening` 恒为真，于是三个条件里剩下的两个
仍把这次会话算成进行中。空格的 `commitVoice()` 摘了表、点选没摘，两条并存的出口长得不一样。

**修法**：收成一个出口 `endVoiceSession(reason, ok)`，一次做完五件事——停两路识别器、清 `voiceActive`、
摘兜底表、清 `codeOverride`、打 `ev=ui_reset reason=`。七处收尾全部改走它，
`cancelVoice(reason)` 的 `reason` 改为必填（谁结束的会话必须能从日志判读）。
判据抽成纯函数 `voiceSessionOccupied(voiceActive, systemListening, voskListening)`，
兜底表与出口共用同一个判据，避免「出口认为自己收完了、兜底表却还觉得卡着」。

**非语音态是 no-op**：`commit()` 是每条候选上屏的必经路（拼音 / T9 / 联想都走它），
出口先用 `voiceSessionOccupied` 判有没有会话在进行，没有就直接返回，不给正常打字刷埋点。

**顺带消失的两个副作用**（同一处的连带修复，不是新需求）：
1. 空格上屏语音候选后，识别器随之停掉，晚到的 `onFinal` 不会再往输入框补第二次同样的文本；
2. 语音会话结束后的下一次点语音键，不再因为 `isListening` 残留而先走「取消」分支。

**门禁**：`tools/verify/check_crosscut.py` 8b.3 从「数 disarm 出现几次」改成守结构与行为——
出口必须含五步、`voiceActive = false` 只许出现在出口内、`cancelVoice()` 调用必须带 reason、
`commit()` / `cancelVoice` / `commitVoice` / `commitVoiceText` 必须调用 `endVoiceSession`。
把 `commit()` 改回旧写法会立刻红 2 条（本轮实测：`passed=174 failed=2`），旧断言当时是绿的——
这就是复审说的「门禁守得住字符串，守不住行为」。

**单测**：`app/src/test/java/com/ispeech/input/VoiceSessionExitTest.kt`（3 例）钉住判据：
只清 `voiceActive` 不停识别器 = 仍算进行中（正是漏网那条）、只清识别器不清 UI = 仍算进行中、
三个全清 = 结束。判据之外的「每条出口都走单一出口」由上面的静态门禁守。

**仍未坐实的部分**：本条是静态可达路径 + 判据单测，不是运行时日志。
`ev=timeout stage=session` 至今零运行时样本，要等基线机 `23F0224224000950` 接线后按 §八 的判读表复测。

## 十二、`ev=timeout stage=start` 的运行时取证（VOCA-231，2026-11-24 实测）

§八 的时限此前只有单测与静态门禁，`ev=timeout` 零运行时样本。本节补上**第一条真实日志**，
并把「怎么稳定造出这个分支」固化成可复跑装置。

**为什么需要装置**：AVD 上的系统识别服务即使被 `kill -STOP` 冻住，`SpeechRecognizer` 客户端框架
仍会在约 5 秒时自己回 `ERROR_NETWORK`（实测 5.03 s），于是走的是 `ev=error` 而不是 `stage=start`。
不造一个「绑得上、一个回调都不回」的服务，`FIRST_CALLBACK_TIMEOUT_MS` 这条在任何可用机器上都验证不到。

- **装置**：`app/src/debug/java/com/ispeech/debug/StubRecognitionService.kt` +
  `app/src/debug/AndroidManifest.xml`。`onStartListening` / `onStopListening` / `onCancel` 全部只打一行日志、不回回调。
- **隔离**：在 `src/debug/`，release 变体不编译。实测 release 包 dex `StubRecognitionService` = 0 命中、debug 包 = 5 命中。
- **复跑**：

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings put secure voice_recognition_service \
    com.ispeech.ime/com.ispeech.debug.StubRecognitionService
# 点语音键，等 10 秒
adb logcat -d -s AsrTrace:* StubRecognitionService:*
adb shell settings put secure voice_recognition_service \
    com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService   # 复跑完还原
```

**实测原文**（Android 14 / `emulator-5554`，包 `versionCode 5`，2026-11-24 10:14 CST）：

```
W StubRecognitionService: ev=stub_start 已收下会话，故意不回任何回调（VOCA-231 复现装置）
W AsrTrace: session=v1 ev=timeout t=10872220 stage=start limit_ms=10000 ms=10013 vad_began=false vad_ended=false
I AsrTrace: session=v1 ev=ui_reset t=10872222 reason=engine_error ok=false ms=10016
```

三条都到位：`stage=start`（判读表里「有 `start` 无 `first_result`」那一行）、`limit_ms=10000`（时限来自
`AsrTiming`，不是散落常量）、`ui_reset` 在 **10.016 秒**离开「识别中」。连按两次得到 `session=v2` 同样是
`timeout stage=start → ui_reset`，即「卡住后越点越没反应」不再成立。

**`ui_reset reason=` 的口径**（判错会把人带去改错地方；本节初稿写成 `session_timeout`，已订正）：
上面那次复位走的是 **`engine_error`** —— 超时文案经 `VoiceInputController.resetOnTimeout() → onError` 上抛，
`IspeechImeService` 的 `onError` 打 `endVoiceSession("engine_error", ok = false)`。
`session_timeout` 只属于 `IspeechImeService.armVoiceSessionGuard` 那条 66 秒 UI 兜底（`IspeechImeService.kt:395` 的
`cancelVoice("session_timeout")`）；本装置 10 秒就复位，永远走不到它。两条路径的区别就是「谁先到点」。

**`ev=bind` 的 `effective=`**（同一轮订正）：探针现在打**实际生效**的识别服务 —— `CUSTOM` 取配置里的组件，
其余读 `Settings.Secure.voice_recognition_service`（设了桩就指向桩）。旧字段 `first` 打的是
`queryServices()` 返回列表的第一项，顺序由 PackageManager 决定、**不是**绑定结果：设桩复跑时它照样打
`…AiAiSpeechRecognitionService`，看日志的人会以为桩没生效。原字段保留为 `first_queried`，
两者不同时正好把「查询顺序 ≠ 实际绑定」这个坑暴露出来。

### 12.1 覆盖边界：装置日志产出在 rebase 前的树上

本节日志是 **2026-11-24 10:14 CST 在 `emulator-5554` 上、rebase 前的分支树**（`13bd530`）采的，
判读前必须先看清这一点，否则会把「已证」放大成「全路径已证」：

- 采日志的那棵树 `AsrEngine` 只有 `SYSTEM / CUSTOM / VOSK`，默认 `SYSTEM`，全树没有 `FunAsrSpeechEngine(` 构造点。
  所以那次跑到的只有 **`engine=SYSTEM`** 一条链路；本 PR 给离线引擎补的
  `stage=start` / `stage=finish` / `NO_AUDIO` 兜底在那棵树上**不可达、运行时样本为 0**。
- 本分支已 rebase 到 `origin/main@0168e59`（PR #6 的 FunASR 接线在内），现在这棵树上
  `AsrEngine { SYSTEM, CUSTOM, FUNASR, VOSK }`、默认 `FUNASR`（`AsrSettings.kt:70`），
  并且 `IspeechImeService.kt:459` 构造 `FunAsrSpeechEngine` —— **`engine=FUNASR` 已是本树实际出厂默认路径**，
  但仍**没有**它的运行时样本。合并后必须在这棵新树上补一次。

| 分支 / 阶段 | 判据 | 结论 |
| --- | --- | --- |
| `engine=SYSTEM` + 识别服务零回调 | `ev=timeout stage=start` → `ev=ui_reset` ≤ 10.02 s | **已证**（本节实测原文，rebase 前树） |
| `engine=SYSTEM` + 回了中间结果、收尾不回 | `ev=timeout stage=finish` | 未证（`stage=finish` 零运行时样本） |
| `engine=SYSTEM` + 60 s 硬上限 | `ev=timeout stage=session` | 未证（`stage=session` 零运行时样本） |
| `engine=FUNASR`（rebase 后本树出厂默认路径） | start / finish / `NO_AUDIO` 三条兜底 | 未证（本树可达，运行时样本为 0；旧树不可达） |
| `engine=VOSK` | start / finish 两条兜底 | 未证（本树可达，本轮未跑） |
| 真机 `FMR0224913042917` / `23F0224224000950` | 复位时间 + 可读文案 + `stage=session`/`stage=finish` | 未达成（`adb devices -l` 无该机） |

要补的三件事（按 §八 判读表，真机 / rebase 后树上各采一次）：`stage=session` / `stage=finish`
两条运行时样本；`engine=FUNASR` 这条**实际出厂默认路径**的 start 兜底样本；以及真机三类产物。

### 12.2 真机回线后的采集步骤（未执行，设备当前不可达）

本节是**待执行清单**，不是结果。2026-11-24 实测 `adb devices -l` 只列出 `emulator-5554`，
`FMR0224913042917` / `23F0224224000950` 均未出现（USB 总线无 ADB 接口，`adb connect <手机IP>:5555` 也拿不到地址）。
设备一回到线上，按下面四步一次采完，产物直接贴进本 issue：

```
# 1. 先坐实设备与包身份（VOCA-228 的 A0 口径：serial + sha256 + lastUpdateTime 一起记）
adb devices -l
adb -s <serial> shell dumpsys package com.ispeech.ime | grep -E 'versionCode|versionName|lastUpdateTime'

# 2. 清屏 → 点语音键 → 每 10 秒截一张，连截 6 张，同时记开始时刻
adb -s <serial> logcat -c
# …点语音键，间隔截图…

# 3. 取业务打点：判读只看 AsrTrace，不用系统日志兜
adb -s <serial> logcat -d -s AsrTrace:* StubRecognitionService:*

# 4. UI 侧兜底状态（判「是否真的离开识别中」的最后一道）
adb -s <serial> shell dumpsys input_method | grep -E 'mCurMethodId|mInputShown'
```

判读口径（配 §八 表）：
- 出 `ev=begin` 后 **10 秒内**必须有 `ev=timeout stage=start` + `ev=ui_reset` —— 这是「有界复位」的判据，缺任一即未达标。
- 出 `ev=first_result` 但收尾不回 → 期望 `ev=timeout stage=finish`（6 秒）；按住不放超 60 秒 → `ev=timeout stage=session`。
- 真机上 `ui_reset reason=` 若为 `session_timeout`，说明走的是 66 秒 UI 兜底而不是引擎侧时限 —— 那是超时链路没生效的证据，不是达标。
- 打点行数为 **0** 时，先判包是不是本分支（`AsrTrace` 只在本分支引入），再判 §9.1 的分支归属；不要直接归因到引擎。
