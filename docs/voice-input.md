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
- 识别服务枚举用**服务方**声明的 action（VOCA-310）：`RecognitionServices.SERVICE_INTERFACE` = `android.speech.RecognitionService`，与发起识别用的 `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` 是两个不同的 action，拿后者枚举命中恒为 0（`AndroidManifest` 的 `<queries>` 也只放行了前者）。判定链路是「枚举 → 按 `BIND_RECOGNITION_SERVICE` 留下真服务（挡住华为 `FakeRecognitionService` 那类绑得上却永不回结果的桩服务）→ 纯函数 `RecognitionServices.blockedReason(available, usable)` 归类」；`createSpeechRecognizer` 失败不再往外抛，归为 `service_bind_failed`。

### 前置判定：为什么这一按没进「识别中」（VOCA-310）

判定收在一处、打点有两处出口：`IspeechImeService.toggleVoiceInput()` 问 `controller.probeBlockedReason(asr)`，非空就走 `traceVoiceBlocked(reason)` 拦下（控制器为空的分支同走这个入口）；第三条 `service_bind_failed` 在 `VoiceInputController.startListening()` 里就地打——探测放行、识别器却建不起来时已经不在服务侧代码路径上。所以「点了语音键却没进识别中、日志里却什么都查不到」这条路是被门禁堵住的，不是被单一出口堵住的：`tools/verify/check_crosscut.py` 8.2b 逐处钉住这两个出口（删掉任一处调用、或把某一处换成注释，离线门禁即 FAIL），再加三条断言把「新出口绕过上面这条」的路堵死：出口集合按**发射器**采集——`app/src/main` 下每个 `AsrTrace.w(` / `AsrTrace.i(` / `AsrTrace.line(` 调用点都算一个出口，把点号折下一行（`AsrTrace` 换行 `.w(…)`）也算（第二实参在 `w` / `i` / `line` 里形参同序，就是事件名；把事件名写成裸串 `"blocked"` 还是引用 `AsrTrace.EV_BLOCKED`，编出来的字节码与 logcat 一模一样，只按常量 token 采集会漏掉后者），集合必须恰好等于这两处；ev 实参一律要由 `AsrTrace.EV_*` 常量给出——出现字符串字面量（含 `if/else` 里混一条裸串）或整个实参不引用任何 `EV_*` 常量（绕本地 `val`）即 FAIL，`if (ok) AsrTrace.EV_EMPTY else AsrTrace.EV_RESULT` 这种纯常量条件是合法的，门禁按括号配平取实参，不会把它切半截误报；第三条拦绕过封装的写法——`app/src/main` 里出现 `Log.x(AsrTrace.TAG, …)` 即 FAIL，因为这种调用点的事件名不经过任何形参，前两条都读不到它（`AsrTrace.kt` 内部的 `Log.w(TAG, line(…))` 不带限定名，是发射器本体，不在这条范围里）。以上两句只覆盖**带限定名**的调用点，经 `import … as` 别名发射（`ASR.w(null, ASR.EV_BLOCKED, …)`）、把 TAG 存进变量再发（`Log.w(tag, …)`）、把 TAG 写成字面串（`Log.w("AsrTrace", …)`）这三种写法实测走得出采集，记为不在采集范围，覆盖面到此为止——走得出的是「日志行照样有 ev=blocked、只是没进采集」，破的是覆盖面被写成全称，不是「拦下必有归类」这条契约。采集范围与上面这几句措辞是否一致、变异条数是否漂了，由 `python3 tools/verify/check_voca310_gate.py` 逐只在副本里复跑核对（VOCA-310 条件项 C1′；同一张表里 M-B / M-B2 把「常量值写调用方 action」的两种读法各钉一条，2 条与 1 条不是矛盾，是两只变异）。三个 reason 是日志契约（同一节钉住字面量，改名即 FAIL）：

| `ev=blocked reason=` | 判定条件 | 候选条文案 |
| --- | --- | --- |
| `no_recognition_available` | `SpeechRecognizer.isRecognitionAvailable()` 为 false：系统侧没有可绑定的识别服务 | `voice_unavailable`（设备不支持语音输入） |
| `no_usable_service` | 服务查到了，但按 `BIND_RECOGNITION_SERVICE` 筛完剩 0 个：机型只预置桩服务 | `voice_service_unavailable` |
| `service_bind_failed` | 前置判定放行，`createSpeechRecognizer` 却建不起来（绑定失败） | `voice_service_unavailable` |

拦下会话**之前**先打一行 `ev=bind`，字段是 `engine=` / `services=`（枚举到几条）/ `usable=`（筛完剩几条）/ `dropped=`（被过滤几条）/ `requested=`（CUSTOM 指定的组件，SYSTEM 为 `system_default`）/ `first_usable=`。只有 `ev=blocked` 分不出「真没服务」和「查询写错了 action」，那正是本单最初的排查困境。

取证两条命令，**先跑 ② 再读 ①**（② 不装包、不改机器，回答的是「这台机到底有没有真服务」这个前提）：

```bash
# ① 会话侧：这一按被判成了什么（ev=bind 给计数，ev=blocked 给归类）
adb -s <serial> logcat -d -s AsrTrace | grep -E 'ev=(bind|blocked)'
# ② 机型侧：设备上声明了识别服务的组件与各自的保护权限
adb -s <serial> shell cmd package query-services -a android.speech.RecognitionService
```

② 里只有 `permission=android.permission.BIND_RECOGNITION_SERVICE` 的那几条是真服务；实测华为 ALN-AL00 唯一命中 `com.huawei.vassistant.voiceui.service.FakeRecognitionService`（`BIND_VOICE_INTERACTION`）——这种机器上 `usable=0` 并提示 `voice_service_unavailable` 是**预期结果**，不是本缺陷复发。换成 `android.speech.action.RECOGNIZE_SPEECH` 查会得到 `No services found`，那正是 VOCA-310 修掉的错法。

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
| 前置判定不再被「action 用错」挡掉（VOCA-310） | `tools/verify/check_crosscut.py` 8.2b（枚举 action、`BIND_RECOGNITION_SERVICE` 过滤、`ev=bind` 计数、三个 reason 字面量、两个打点出口逐处钉住、按发射器采集的 `ev=blocked` 出口集合断言、ev 实参必须引用 `AsrTrace.EV_*` 常量，共 20 向，写错即 FAIL）+ `app/src/test/java/com/ispeech/input/RecognitionServicesTest.kt`（真服务判据 + 前置判定两态） |
| 「点了语音键却没进识别中」必定有日志（VOCA-310 打回项 2） | 两个打点出口各有断言：`IspeechImeService.toggleVoiceInput()` 的三个拦下分支（`controller == null` / 探测拦下 / 绑定失败在 `VoiceInputController.startListening()` 内）删掉任一处打点、或换成注释，8.2b 即 FAIL；另有「`app/src/main` 里 `AsrTrace.w(` / `AsrTrace.i(` / `AsrTrace.line(` 打点中 ev=blocked 的出口恰好是 `traceVoiceBlocked` + `startListening` 两个」的结构性断言（按发射器调用点采集，新出口写成裸串 `"blocked"`、把点号折到下一行、或改走 `AsrTrace.line(` 同样被抓），外加「ev 实参一律引用 `AsrTrace.EV_*` 常量」与「不许绕过封装直接 `Log.x(AsrTrace.TAG, …)`」各一条。`rg -n "no_recognition_available" app/src/main` 命中常量与调用 |
| **门禁自己的口径与变异是否一致**（VOCA-310 条件项 C1′ + 质检需澄清 ③） | `python3 tools/verify/check_voca310_gate.py`：17 只变异在一次性副本里逐只复跑（8 种发射器写法 = K1~K5 拦住 / N1~N3 走得出，加 M-A / M-B / M-B2 / M-H / M-C1 / M-C2 / M-I / M-D / M-E 判定与打点方向），核对**新增 FAIL 条数**与**被点名的断言**；再核对三处覆盖面声明（`check_crosscut.py` 文档头 8.2b 段、出口集合断言标题与 FAIL 文案、本节 §二 那段）与本表同集合——全称没加限定、排除句漏点名、已被拦住的还留在排除句里，都是这条命令的 FAIL。读数 `SUMMARY: passed=29 failed=0`。**M-B 的两种读法已拆成两只变异**：改 `VoiceInputController.kt:391` 的**常量取值**新增 2 条 FAIL（取值 + `<queries>` 名单），改 `:92` 的**枚举调用点为裸串**新增 1 条（只命中 queryIntentServices 那条）；条数不同不是复现不出来，是两只不同的改法。**N1/N2/N3 走得出的是真实写法不是理论写法**：`python3 tools/verify/check_voca310_gate.py --emit /tmp/voca310-probes` 把 8 只探针全打进去，`./gradlew -p /tmp/voca310-probes --offline :app:compileDebugKotlin` → `BUILD SUCCESSFUL`（N3 必须是独立文件——别名 import 会把本文件的 `AsrTrace` 简单名遮掉，实测 9 处 `Unresolved reference`） |
| **设备侧口径**（VOCA-310 口径 1 / 3）：模拟器已取证、真机仍缺 | `emulator-5556`（**本单专用 AVD**，`qa166_test` 包同一 `android-34/google_apis/arm64-v8a` 镜像、`-port 5556` 独立 serial；不再与并发 run 共用 `emulator-5554`，这是质检打回项 3 的整改）实测，**包身份先钉死再读会话**：C1 `cmd package query-services -a android.speech.RecognitionService` → `2 services found:`（`AiAiSpeechRecognitionService` / `GoogleTTSRecognitionService`），两条 `permission=null`；对照组换回调用方 action `-a android.speech.action.RECOGNIZE_SPEECH` → `No services found`；C2 `adb install -r -d <HEAD 原样构建的 app-debug.apk>`：装前无包、装后 `versionCode=5` / `lastUpdateTime=2026-11-24 14:56:10`，`adb pull` 回来 sha256 `cd1cd21d5cb8110b02283f545c7d58ce1cfa0aeeb167d8a8d73152edcb6a1bb1` 与本地产物逐字节相同；dex 里 `RecognitionServices`(5) / `traceVoiceBlocked`(1) / `first_usable`(1) / `no_usable_service`(1) / `android.speech.RecognitionService`(1) 均在 `classes10.dex` 命中，`hasUsableService` 与 `StubRecognitionService` 均 0 命中（即机上跑的确含本单修复、且不是带桩服务的调试包）；C3 `logcat -c` → **一次**长按空格（与语音键同一 `toggleVoiceInput` 入口；默认引擎 FUNASR，取证前已在设置页把 `asr_engine` 存成 `SYSTEM`）→ `logcat -d -s AsrTrace` 整段只有两行，同一次按压、同一 `t=321131`：`I AsrTrace: session=- ev=bind t=321131 engine=SYSTEM services=2 usable=0 dropped=2 requested=system_default first_usable=-` ＋ `W AsrTrace: session=- ev=blocked t=321131 reason=no_usable_service engine=SYSTEM`；同轮 `cat /proc/uptime` = `320.58 …`（秒）⇒ `t=` 与开机时长同量级（差 0.55 s），候选条文案 `No usable speech recognition service on this device…` 同步实测 ⇒ 归 **1(b)**（拦得有据）。**`usable=0` 的成因是设备事实，不是本单缺陷**：这两条 `Android 14` 系统服务自身都没声明 `BIND_RECOGNITION_SERVICE`，按真服务过滤后剩 0 个——与华为 ALN-AL00 上只有 `FakeRecognitionService` 同一类现象。**真机 `FMR0224913042917`（ALN-AL00 / Android 12）仍缺**：该机自 11-19 18:43Z 起离线，正文口径 3 明确「模拟器与 AVD 记录只能作为前置证据，不能替代真机」；模拟器只代表「判定链路在真服务为 0 的设备上给出 1(b)」，**不能推断** FMR 上 `usable` 是否 > 0 ⇒ FMR 回线后仍须按 C1/C2/C3 复跑一次。设备窗口归 VOCA-228 / VOCA-127 |

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
