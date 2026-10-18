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
工具栏「🎤」(KeyAction.VOICE, SoftKeyboardView.toolbarKeys)
  └─ IspeechImeService.handleSoftKey -> toggleVoiceInput()
       ├─ 未授权：requestMicPermission() -> 设置页 MainActivity（extra REQUEST_MIC_EXTRA）弹系统授权
       ├─ 识别中：VoiceInputController.stop() -> cancelVoice()（状态条「语音已取消」）
       └─ 未开始：VoiceInputController.start(asr, callingPackage, onPartial/onFinal/onError)
            └─ 系统 SpeechRecognizer（RecognizerIntent.ACTION_RECOGNIZE_SPEECH）
                 ├─ onPartialResults -> showVoiceCandidate()（候选条「语音」）
                 └─ onResults       -> showVoiceCandidate() -> 空格 / 点选 -> commitVoice()
```

| 关注点 | 位置 |
| --- | --- |
| 识别器封装 | `app/src/main/java/com/ispeech/input/VoiceInputController.kt` |
| 服务状态机 | `app/src/main/java/com/ispeech/input/IspeechImeService.kt`（`toggleVoiceInput` / `showVoiceCandidate` / `commitVoice` / `cancelVoice` / `applyAsrChange`） |
| 设置模型 | `app/src/main/java/com/ispeech/settings/store/AsrSettings.kt`（`AsrEngine` / `AsrLanguage` / `AsrSettings` / `ASR_MODEL_EXTRA`） |
| 持久化 | `SettingsStore`（`asrSettingsFlow` / `asrSettings` / `setAsrSettings`） |
| 设置页 | `settings/asr/AsrSettingsScreen.kt` + `AsrSettingsViewModel.kt`（导航路由 `IspeechRoutes.ASR`，首页 ASR 卡片） |
| 输入法内面板 | `input/SettingsPanel.kt`（引擎 / 语言 / 参数分项，`onAsrChange -> applyAsrChange`） |
| 权限 | `AndroidManifest.xml`（`RECORD_AUDIO` + `queries/RecognitionService`）、`settings/MainActivity.kt`（`REQUEST_MIC_EXTRA` 代申请） |
| 文案 | `res/values/strings.xml` / `values-en/strings.xml`（`voice_*` 6 项 + `asr_*` 19 项，中英同名） |

健壮性（真机踩坑后加）：

- `ERROR_RECOGNIZER_BUSY`：识别器在系统侧未完全释放时**原地重建重试一次**，避免「点了没反应」。
- 取消即 `destroy()`：只 `stopListening()` 会让系统继续占麦克风，导致下一次 `ERROR_RECOGNIZER_BUSY`；因此取消 / 销毁都释放识别器。
- `EXTRA_CALLING_PACKAGE`：输入法不是宿主，部分 ROM 缺该 extra 会直接回 `ERROR_CLIENT`，故透传 `currentInputEditorInfo.packageName`。

## 三、复刻义务清单

| # | 类别 | 义务（要做成什么） | 原产品行为 | 证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| O-01 | 功能 | 工具栏提供语音入口 | 有语音入口（图标 `ime_function_voice.png`） | `docs/icon-replication.md` | 已复刻（`KeyAction.VOICE` + `🎤`） |
| O-02 | 识别 | 语音转文字 | 未观测 | 系统 `SpeechRecognizer`；真机 logcat 绑定识别服务见 `docs/v1.5-verify-log.md` §5 | 已复刻（依赖系统识别服务） |
| O-03 | 交互时序 | 识别结果如何上屏 | 未观测（推测为面板 / 松手自动上屏） | `IspeechImeService.toggleVoiceInput` | 有差异（候选条 + 空格 / 点选上屏） |
| O-04 | 边界与错误 | 未授权 / 无匹配 / 识别器忙 | 未观测 | `VoiceInputController.errorMessage`、BUSY 重试；§5 记录 5s 超时优雅收尾 | 已复刻 |
| O-05 | 功能 | 识别配置（引擎 / 语言 / 参数） | 未观测 | `AsrSettings` + 设置页 + 输入法内面板 | 已复刻（含指定服务组件 / 优先离线 / 模型名透传） |
| O-06 | 文案 | 中英文案 | 未观测 | `strings.xml` / `values-en`（`voice_*` + `asr_*` 同名） | 已复刻 |
| O-07 | 图标与视觉 | 语音入口图标 | `ime_function_voice.png` / `_p` | `docs/icon-replication.md` 记录原始尺寸与形态 | 有差异（用 `🎤` 文字；原厂 PNG 仅作证据，不复用分发） |
| O-08 | 数据 | 语音上屏是否计入统计 | 未观测 | `commit()` -> `recordCommit(CANDIDATE)` | 已复刻（按候选档计入字数） |
| O-09 | 验收 | 真实语音转写准确率 | 未观测 | — | **未观测**（真机未朗读话筒，需补真机朗读用例） |

## 四、证据

| 事实 | 证据 |
| --- | --- |
| 语音链路真的起来了、麦克风真的被采集 | `docs/v1.5-verify-log.md` ①（真机 Huawei：`phone8.png` 状态条「语音识别中...」、`logcat` 绑定 `com.huawei.vassistant/.voiceui.service.FakeRecognitionService`）与 §5（模拟器：候选条「Listening…」+ 系统麦克风指示） |
| 状态机能优雅收尾、不卡「聆听中」 | 同上 §5：5 秒后候选条变「Voice recognition failed」、麦克风指示消失、输入法未崩溃 |
| 中英文案齐全 | `tools/verify/check_crosscut.py` 第 8 节（`voice_*` / `asr_*` 中英键名一致性断言） |

## 五、已知差异与限制

- **交互形态**：本实现是候选条 + 手动上屏；搜狗是语音面板（波形、松手自动上屏）。原产品面板行为未观测，故不做形式对齐。
- **识别能力**：取决于设备上的 `RecognitionService`；设备无识别服务时入口直接提示「设备不支持语音输入」；离线能力取决于该服务是否支持 `EXTRA_PREFER_OFFLINE`。
- **模型名**：走应用自有 extra `com.ispeech.extra.ASR_MODEL`（非 Android 标准常量），仅识别服务主动读取时生效。
- **待补验收**：真机朗读一段话，验证转写文本与准确率（O-09）。
