# ADR-0003：已启用输入法漏列（缺陷 D）的机制定论与修法契约（VOCA-167/S1）

- **状态**：Accepted（判别实验已定论）／**待人类拍板 §6 第 1 项后才进实现** —— 按项目规矩，manifest 改动属需人确认项
- **决策日期**：2026-11-17
- **决策人**：架构师（agent `e7ea905a-48f9-4dc9-86d5-32346dc237da`，VOCA-398）
- **关联 issue**：VOCA-398（本 ADR）→ 父 VOCA-167；需求澄清件 VOCA-168（`done`，验收文档 `c99b1f5:docs/requirements/voca-167-switch-system-ime.md`）；下游 **VOCA-401**（S2 实现，本 ADR 是其唯一输入）、VOCA-402（S3 机检与回归）；本 ADR 文本校正件 **VOCA-405**；同文件耦合关联方（§4.1 全量名单）：**PR #16 / VOCA-310**（语音侧依赖 `<queries>` 里那条 intent）、**PR #13 / VOCA-166**（唯一并发改 manifest 的 PR）、**PR #20 / VOCA-399**（与本件同为父单 VOCA-167 的兄弟子单，改 `check_crosscut.py` 的 `check_switch_ime_chain()` —— VOCA-402 的落点正撞它）
- **本文的基线口径**：所有行号与代码结论以 **`origin/main` tip `fa57a07`** 为准。本实验的实际跑数载体是分支 `agent/agent/voca-398` @ `007874d`，它与 `fa57a07` **只差 `dist/ispeech-release-1.5-20261115-1004.apk` 一个二进制**（`git diff --stat fa57a07 HEAD` → `1 file changed`，源码与文档逐字节相同），引用时以 `fa57a07` 为准。
- **范围**：只做架构决策与契约。不写业务实现、不合并 main、不改验收判据（AC-2 仍为「面板行数 == `|ENABLED|` 且每个 id 都有行」）。
- **校正记录（VOCA-405，2026-11-17）**：质检专员·架构师对 VOCA-398 裁决 **PASS-WITH-CAVEAT**（VOCA-398 评论 `01a1e606…`）后，合并前改掉本文三处文本缺陷：**R1** §7 性能行 / §8 F6 / §10 取证口径的滚动阈值（原「4 行 472px 已超出可见高 → 会滚动」把 472 与 660 比反了，正确为「上限 660px ÷ 118px/行 ⇒ ≥6 行才滚动」）；**R2** §1.5 与附录 A 的截图文件名与 issue 附件一一对应；**R3** §4 同文件耦合名单改为对**全部开着的 PR** 全量枚举（manifest 侧并发者是 PR #13 而非 #16；门禁脚本侧 9 个并发修改者，VOCA-402 真正会撞的是 PR #20 = VOCA-399 的 `check_switch_ime_chain()`）。**不动 D1–D4 决策、不动 AC 判据、不新增决策。**
- **复审条件**（任一满足即重审）：
  ① `targetSdk` 升到 35+ 或 AOSP 改了 `getEnabledInputMethodList()` 的可见性口径；
  ② 任何一台真机（尤其 EMUI）在装上本修法后仍漏列；
  ③ 语音侧（VOCA-310）把 `<queries>` 的 `RecognitionService` 条目改掉或收窄；
  ④ 产品决定列出「未启用」的输入法（那是 C 类新行为，需先回洞察BA 重开需求）。

---

## 0. 结论（一句话）

**BA 的假设成立**：漏列是 **Android 11+ 包可见性**在框架侧裁掉了 `getEnabledInputMethodList()` 的返回集合，智语应用侧没有任何过滤；修法是给 `app/src/main/AndroidManifest.xml` 的 `<queries>` **追加一条 `<intent><action android:name="android.view.InputMethod"/></intent>`**，实测面板行数 **2 → 4**，且未启用的输入法不会因此出现。不引入 `QUERY_ALL_PACKAGES`，不精确到 `<package>`。

三条本轮实测的硬结论：

| # | 结论 | 证据（本轮在 `emulator-5554` / Android 14 / API 34 实跑） |
|---|---|---|
| 1 | 缺陷 D 复现，且与「装的是哪棵树」无关 | 控制组包（源码 = `fa57a07`，APK SHA256 `48ee0d3d…b330`）`|ENABLED|=4` → 面板 **2 行 /「共 2 个」/「输入法 2 个」** |
| 2 | 加声明即修好 | 同一台设备、同一份源码、**只多 3 行 manifest**（APK SHA256 `4893699b…10c7`）→ 面板 **4 行 /「共 4 个」/「输入法 4 个」**，多出的是 `Gboard` 与 `搜狗输入法` |
| 3 | 不会过度列出 | 把 Google 语音输入法 `ime disable` 后（`|ENABLED|=3`）→ 面板 **恰好 3 行**，被禁用的那项不出现 |

判别实验是 **A/B/A** 三段（控制 → 处理 → 改回控制再测），不是「前后各看一次」，排除「重装/刷新顺带修好」这一混淆项；详见 §1 与附录 A。

---

## 1. 判别实验（交付物 1）

### 1.1 设备与前置状态（本轮原文）

```
$ adb devices -l
emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:3

$ adb shell getprop ro.build.version.release ; adb shell getprop ro.build.version.sdk
14
34

$ adb shell settings get secure enabled_input_methods
com.google.android.tts/…VoiceInputMethodService:com.sohu.inputmethod.sogou/.SogouIME:com.google.android.inputmethod.latin/…LatinIME:com.ispeech.ime/com.ispeech.input.IspeechImeService   → 4 项

$ adb shell ime list -s
（同上 4 项）

$ adb shell settings get secure default_input_method
com.ispeech.ime/com.ispeech.input.IspeechImeService
```

设备与 VOCA-168 §2.3 是**同一台** `emulator-5554`，`|ENABLED|` 同为 4 项（含搜狗）。与 BA 那一轮的差别：本轮**两个包都署得了名**（源码 commit + APK SHA256），BA 当次那个包她自己在 §2.0 行 ③ 标了「署不出来源」。

### 1.2 控制组（`fa57a07` 原样，无 `InputMethod` 声明）

```
$ ./gradlew --offline assembleDebug          # 源码 = 007874d（与 fa57a07 源码逐字节相同）
$ shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
48ee0d3d41b29ed50d4ad9c23c1dbf8cb42da763066ab4f17823b38fa32eb330  app-debug.apk
$ aapt dump badging app-debug.apk | grep ^package
package: name='com.ispeech.ime' versionCode='5' versionName='1.5' …
$ adb install -r app/build/outputs/apk/debug/app-debug.apk
Success                                     # lastUpdateTime=2026-11-17 03:50:18
```

打开面板（工具栏第 1 键 = `SWITCH_IME`，`SoftKeyboardView.kt:316`），坐标不靠肉眼估：先从窗口管理器取 IME 窗口 frame，再按 `候选条 minHeight 44dp + 工具栏 TOOLBAR_HEIGHT_DP 34dp`（440dpi → 1dp=2.75px）算出第 1 键中心 `(70, 1469)`。

```
$ adb shell dumpsys window windows | grep -A45 "Window #.*InputMethod}:" | grep Frames:
    Frames: parent=[0,136][1080,2340] display=[0,136][1080,2340] frame=[0,1290][1080,2340]   # 键盘态
$ adb shell input tap 70 1469
    Frames: … frame=[0,1576][1080,2340]                                                       # 面板态
```

面板截图 `A-control-2of4.png`：**「输入法 2 个」+「共 2 个」+ 2 行**（`智语`（当前）、`Google 语音输入`）。缺 `Gboard`、`搜狗输入法`。
→ **AC-2 在控制组判 FAIL，缺陷 D 在署名包上复现。**

### 1.3 处理组（`<queries>` 追加 `android.view.InputMethod`）

唯一改动（`app/src/main/AndroidManifest.xml`，+3 行，其余源码一字未动）：

```xml
     <queries>
         <intent>
             <action android:name="android.speech.RecognitionService" />
         </intent>
+        <intent>
+            <action android:name="android.view.InputMethod" />
+        </intent>
     </queries>
```

```
$ ./gradlew --offline assembleDebug
$ shasum -a 256 app/build/outputs/apk/debug/app-debug.apk
4893699b8fb1deeac67ab46dc1e41a52b4db4cf4a6d036a0d13e00efb11510c7
$ aapt dump xmltree app-debug.apk AndroidManifest.xml | grep -A6 "E: queries"
E: queries (line=18)
      E: intent (line=19)  → action android:name="android.speech.RecognitionService"
      E: intent (line=22)  → action android:name="android.view.InputMethod"      # 确认进了合并后的清单
$ adb install -r app/build/outputs/apk/debug/app-debug.apk
Success                                     # lastUpdateTime=2026-11-17 03:56:35
$ adb shell settings get secure enabled_input_methods | tr ':' '\n' | wc -l
4                                           # 启用集合没动过
```

同一台设备、同一个宿主输入框、同一个键位坐标：

```
$ adb shell input tap 70 1469
    Frames: … frame=[0,1340][1080,2340]
```

面板截图 `B-treatment-4of4.png`：**「输入法 4 个」+「共 4 个」+ 4 行**（`智语`（当前）、`Gboard`、`Google 语音输入`、`搜狗输入法`）。
→ **行数 2 → 4，假设确认。** 窗口高度差 1576−1340 = 236px = 2 行 × 118px，与行数线性相关，不是裁切或滚动差异。

### 1.4 负控：未启用的不出现（AC-2 的后半句）

```
$ adb shell ime disable com.google.android.tts/…VoiceInputMethodService
$ adb shell settings get secure enabled_input_methods | tr ':' '\n' | wc -l
3
$ adb shell input tap 70 1469
    Frames: … frame=[0,1460][1080,2340]
```

面板截图 `C-treatment-3of3-disabled-hidden.png`：**「输入法 3 个」+「共 3 个」+ 3 行**（`智语`、`Gboard`、`搜狗输入法`），被 disable 的 Google 语音输入**不出现**。测完已 `ime enable` 还原（`ENABLED` 回到 4）。
→ 修法不会把清单放大到「设备上所有已安装的输入法」，`enabled` 语义仍由框架保证。

### 1.5 A/B/A 回控：改回原样仍是 2

```
$ cp <控制组 manifest> app/src/main/AndroidManifest.xml ; git diff --stat      # 空（工作树回到 HEAD）
$ ./gradlew --offline assembleDebug ; adb install -r app/build/outputs/apk/debug/app-debug.apk
Success
$ adb shell input tap 70 1469
    Frames: … frame=[0,1576][1080,2340]                                        # 回到 2 行的高度
```

面板截图 `E-abab-control-again-2of4.png`：**「输入法 2 个」+「共 2 个」+ 2 行**。
→ 2↔4 只由那 3 行 manifest 决定，**因果闭合**；「重装一次就好了」「刷新缓存」这类解释被排除。

> 附带记录一条与结论无关但要留痕的事实：回控重打的 APK 是 `ca90cb7e…0d61`，与控制组首次构建的 `48ee0d3d…b330` **字节不同**（源码相同）⇒ 本项目的 debug 构建**不是字节可复现的**。所以 §1.2/§1.3 的 SHA 只能署名「哪一次构建装的机」，不能用来比对「同一份源码的两次构建」。出包门禁若要按字节断言，需另立 issue（归属 SRE + 测试，不在本件范围）。

### 1.6 切换与兜底（顺带取到 §5 两个问题的证据）

```
# 处理组：点此前被裁掉的 Gboard 行
BEFORE=com.ispeech.ime/com.ispeech.input.IspeechImeService
$ adb shell input tap 500 1719
AFTER=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
$ adb shell dumpsys input_method | grep mCurMethodId
  mCurMethodId=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
（测完 `adb shell ime set com.ispeech.ime/…` 还原，DEFAULT 回到智语）

# 控制组（漏列状态）：点面板「系统选择器」，读系统对话框的可访问性树
$ adb shell uiautomator dump /sdcard/pk.xml ; adb shell cat /sdcard/pk.xml | grep -oE 'text="[^"]*"'
text="选择输入法"  text="Gboard"  text="Google 语音输入"  text="搜狗输入法"  text="智语"
```

→ AC-3 在修复后对曾被裁掉的项成立；**系统选择器在漏列状态下仍列全 4 项**（它由系统渲染，不吃调用方的可见性）。截图 `D-control-syspicker-4.png`。

### 1.7 机制侧的补充取证（为什么恰好是这 2 项可见）

```
$ adb shell cmd package query-services -a android.speech.RecognitionService | grep name=
      name=com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService     # com.google.android.as
      name=com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService   # com.google.android.tts
```

`<queries>` 里唯一的声明命中的提供者是 `com.google.android.as` 与 `com.google.android.tts`；两者里**只有 tts 同时是已启用输入法**。所以控制组可见集合 = **智语自身 ∪（`RecognitionService` 提供者 ∩ `ENABLED`）= {智语, Google 语音输入}** —— 与 §1.2 实测的 2 行逐项吻合，BA §2.3 的「可见集合 == 自身 ∪ RecognitionService 提供者」在本轮独立复现。

> 尝试过但不可用的路径（如实记录）：`adb shell cmd package query-services --pkg com.ispeech.ime -a android.view.InputMethod` 在 API 34 的 `pm` 上报 `Unknown option: --pkg`，无法以应用身份直接查可见集合。机制的因果由 §1.2～§1.5 的 A/B/A 单变量实验支撑，不依赖这条。

### 1.8 争用声明（影响证据强度，必须披露）

本轮全程 `emulator-5554` **同时被另一个 run 操作**：至少三次观测到非我发起的输入（Settings 搜索框先后出现 `sshous`、`s兽兽`；一次 `ime list` 后焦点活动被切走；一次崩溃对话框 `Application Error: com.ispeech.ime` 由 VOCA-170 那条 NPE 在 03:46/03:47 触发，早于我 03:50 的安装）。
应对：① 每个数字都取「同一屏内的三个计数 + 窗口 frame」自证（截图即证据，不依赖前后状态连续）；② 关键读数都在 `adb install` 后立刻重读 `ENABLED`/`DEFAULT` 确认没被动过；③ §1.5 用 A/B/A 回控，使「别人动了设备」不能解释 2↔4 的方向性。
残留风险：不能排除某一张截图的**瞬间**与他人操作重叠。若质检复现要求绝对独占，需人类给一台专用机（§6 第 4 项）。

---

## 2. 机制（写清楚，别只留结论）

**观测事实**：应用侧没有任何筛选 —— `IspeechImeService.kt:1390-1407` 的 `enabledInputMethods()` 把 `manager.enabledInputMethodList` 全量收进来，只有 `info.id ?: continue` 一处守卫；面板标签与行同源（`InputMethodPanel.kt:200-201` 都取 `items.size`）。所以「2」是**数据侧就只有 2**，不是渲染裁切。

**裁剪点**：`InputMethodManager.getEnabledInputMethodList()` 把系统侧维护的「已启用输入法 id 清单」解析成 `InputMethodInfo` 时，要按**调用方的包可见性**去解析每个 IME 服务；Android 11+（`targetSdk ≥ 30`）未在 `<queries>` 声明的包对调用方不可见，解析不到就不在返回列表里。智语 `targetSdk = 34`（`app/build.gradle.kts:31`），命中该规则。
声明 `<intent><action android:name="android.view.InputMethod"/></intent>`（= `InputMethod.SERVICE_INTERFACE`，也正是本应用自己在 `AndroidManifest.xml:44` 给 IME 服务声明的那个 action）后，所有声明了该 action 的包对智语可见，列表恢复完整。

**边界（哪些是本 ADR 实测、哪些是推断）**：

| 命题 | 状态 |
|---|---|
| 加声明 → 行数 2→4，去掉 → 回到 2 | **实测**（§1.2/§1.3/§1.5） |
| 可见集合与 `<queries>` 的 `RecognitionService` 声明逐项吻合 | **实测**（§1.7） |
| `enabled` 语义仍由框架保证（禁用项不出现） | **实测**（§1.4） |
| 裁剪发生在「框架把清单解析成 `InputMethodInfo`」这一步、而非智语代码 | **实测 + 强推断**：应用侧零筛选是读码事实，裁剪点是唯一能解释「单变量 manifest 改动翻转行数」的位置 |
| 具体是 `InputMethodManager` 客户端解析还是 `InputMethodManagerService` 侧过滤 | **未取证**（本轮没有读 framework 源码或进程内返回值）。对修法无影响：两种位置的修法都是同一条 `<queries>` 声明 |

---

## 3. 决策与最终形态（交付物 2）

### D1 修法：`<queries>` 追加一条 intent 声明（唯一规定的文本形态）

`app/src/main/AndroidManifest.xml` 的 `<queries>` 段最终形态，**VOCA-401 逐字采用**（含注释，注释说明「为什么需要」，避免下一轮有人当冗余删掉）：

```xml
    <!-- 包可见性声明（Android 11+ / targetSdk 30+）：
         1) RecognitionService —— 语音输入枚举系统识别服务（VOCA-310 依赖，勿动）；
         2) InputMethod —— 让「已启用的其他输入法」出现在 enabledInputMethodList，
            否则切换输入法面板会漏列（ADR-0003 / VOCA-167 缺陷 D）。 -->
    <queries>
        <intent>
            <action android:name="android.speech.RecognitionService" />
        </intent>
        <intent>
            <action android:name="android.view.InputMethod" />
        </intent>
    </queries>
```

- 只改 manifest，**不改任何 Kotlin**；`enabledInputMethods()` 一行不动。
- 这条声明是「清单可见性」，**不是运行时权限**，不新增 `<uses-permission>`。

### D2 不精确到 provider，不用 `QUERY_ALL_PACKAGES`

| 备选 | 判定 | 理由 |
|---|---|---|
| `<intent>android.view.InputMethod</intent>` | **采用** | 目标集合的定义就是「任何声明了 IME action 的已启用输入法」，与 intent 声明的语义一一对应；实测覆盖第三方（搜狗）与系统（Gboard） |
| `<package android:name="…"/>` 逐个点名 | **否决** | 要列的是**用户设备上装的**输入法，不可枚举：写死 = 换机 / 换 ROM 即失效，且每次新增输入法都要发版。精确度换不来任何收益，因为 IME 服务本身是系统级、由用户显式启用的类别，不属于需要收窄的敏感面 |
| `QUERY_ALL_PACKAGES` | **明确否决** | 父单范围边界；Google Play 对该权限有政策门槛；它把可见面放大到「全部包」，与本需求（只要 IME 一类）不成比例，违反最小权限 |
| 运行时改用别的取数口（如自己读 `Settings.Secure.ENABLED_INPUT_METHODS` 再拼 `InputMethodInfo`） | **否决** | 要自己解析 id、自己加载 label、自己处理禁用态，等于重造框架已经做的事；且拿不到 `loadLabel` 的正确本地化。见 D3 —— 这个字符串**只用于交叉校验**，不用于取数 |

### D3 取数侧：不新增封装层，但在**唯一入口**加一条一致性交叉校验

现状（实测）：全仓读 `enabledInputMethodList` 的只有 `IspeechImeService.kt:1397` 一处（`grep -rn "enabledInputMethodList" app/src/main/java` 仅此一条），所以**不需要**再抽一层「IME 清单仓库」—— 那会给一个单点调用套抽象，属过度设计。契约改为钉住两条约束：

1. **单点约束**：输入法清单的取数**只允许**发生在 `enabledInputMethods()`。任何第二处调用（含面板、设置页、语音侧）视为架构违规，由 VOCA-402 的机检断言守（可测点见 §5.3）。
2. **不得静默少列**：在该函数返回前做一次交叉校验 —— 用系统真值 `Settings.Secure.ENABLED_INPUT_METHODS` 的条数比对。签名与语义（伪代码，非实现）：

```kotlin
// 返回 (可见清单, 系统启用条数)；差值只用于「是否可见地提示」，不参与取数
private fun enabledInputMethods(): List<InputMethodPanel.InputMethodItem>
// 约束：
//   n = Settings.Secure.getString(contentResolver, "enabled_input_methods") 的冒号分段数
//   n > items.size  ⇒  必须可见提示 + Log.w(TAG, "ime list pruned: visible=%d enabled=%d")
//   n == items.size ⇒  正常渲染（AC-2 的期望态）
//   items.isEmpty() ⇒  沿用现有「读不到输入法列表」文案，不新增状态
```

提示文案（交 UX 定稿，架构侧只定「必须有可见提示 + 必须可跳系统选择器」这一不变量）：在「共 N 个」旁显示「系统另有 M 项未显示」，点按走已有的 `showInputMethodPicker()`。

**为什么这条要进契约**：本轮实验证明的是「这一版 AOSP + 这一份 manifest 下不再漏列」。可见性是**框架行为**，随 targetSdk / ROM / 声明漂移都可能复发；没有交叉校验，下一次复发仍然表现为「面板安静地少列」，用户和测试都无从发现（VOCA-167 三轮反复的根因正是「没有真的把面板打开数一遍」）。这是 D 类缺陷的**结构性防线**，成本是一个字符串比较 + 一条日志。

### D4 契约边界（不改的东西）

- **AC 判据不动**：AC-2 仍是「面板行数 == `|ENABLED|` 且每个 id 都有行；未启用的不出现」。
- **切换机制不动**：`setInputMethod(token, id)` + `showInputMethodPicker()` 兜底（`IspeechImeService.kt:1427-1452`）保持原样，本 ADR 不提出改动。
- **不写系统设置**：只读 `enabled_input_methods` / `default_input_method`，不 `settings put`。
- **不自建输入法管理页**：仍引导去系统设置（父单边界）。
- **不动工具栏键序 / 键面**（VOCA-185 / VOCA-121 那条线）。

---

## 4. `<queries>` 段所有权与合并顺序（交付物 3）

**先更正 issue 描述里的一条前提**（事实优先）：本 issue 说「VOCA-310 正在改语音侧 `hasUsableService` 的 intent action，两件事落在同一段 manifest」。**前半句成立，后半句在当前的 PR 上不成立** —— 本轮实查：

```
$ gh pr view 16 --repo forcoder/ispeech --json state,baseRefName,headRefName
{"baseRefName":"main","headRefName":"fix/voca-310-recognition-service-action","state":"OPEN","mergeable":"MERGEABLE"}

$ gh pr diff 16 --repo forcoder/ispeech --name-only
app/src/main/java/com/ispeech/input/AsrTrace.kt
app/src/main/java/com/ispeech/input/IspeechImeService.kt
app/src/main/java/com/ispeech/input/VoiceInputController.kt
app/src/test/java/com/ispeech/input/AsrTraceTest.kt
app/src/test/java/com/ispeech/input/RecognitionServicesTest.kt
docs/voice-input.md
tools/verify/check_crosscut.py
→ 不含 app/src/main/AndroidManifest.xml

$ git diff fa57a07 agent/agent/voca-310 -- app/src/main/AndroidManifest.xml   # 空

$ gh pr diff 16 --repo forcoder/ispeech | sed -n '/IspeechImeService/,/^diff --git/p' | grep '^@@'
@@ -1456,6 +1456,21 @@     # 新增 traceVoiceBlocked()
@@ -1470,7 +1485,9 @@    # toggleVoiceInput() 控制器为空分支
@@ -1478,8 +1495,19 @@    # toggleVoiceInput() 改走 probeBlockedReason()
```

VOCA-310 换的是**运行时查询用的 action 常量**（`RecognitionServices.SERVICE_INTERFACE = "android.speech.RecognitionService"`），它的值**恰好等于 `<queries>` 里已有的那条声明**，所以它不需要动 manifest，而是**依赖**这条声明存在。

**所以真实关系不是「两个 PR 改同一段」，而是「一个依赖、一个追加」**。

### 4.1 盘查方法：对全部开着的 PR 全量枚举（VOCA-405 R3 校正）

原 §4 只查了 issue 点名的 PR #16 一个。**方法是对的，覆盖面不对** —— 同期开着的 PR 有 20 个，同文件耦合必须按全量名单来钉，否则合并窗口会撞在错误的对象上。本轮实跑的枚举命令（原文与输出）：

```
$ gh pr list --repo forcoder/ispeech --json number -q '.[].number' | wc -l
20

# 谁在改 manifest
$ for p in $(gh pr list --json number -q '.[].number'); do
    gh pr diff $p --name-only | grep -q app/src/main/AndroidManifest.xml && echo $p; done
13

# 谁在改门禁脚本
$ for p in $(gh pr list --json number -q '.[].number'); do
    gh pr diff $p --name-only | grep -q check_crosscut.py && echo $p; done
20 18 16 14 13 12 10 6 4
```

⇒ **manifest 段：1 个并发修改者（PR #13）**；**`tools/verify/check_crosscut.py`：9 个并发修改者**（#4、#6、#10、#12、#13、#14、#16、#18、#20）。不是原 §4 假设的 1 个，也不是质检那轮同样方法只查到 4 个（#13/#16/#18/#20）的结果 —— 名单以上面这条命令的输出为准。

逐 PR 在 `check_crosscut.py` 内占用的区段（`gh pr diff <p> | awk` 本轮实取的 hunk 头；行号坐标 = `fa57a07` 上的 `tools/verify/check_crosscut.py`，全文 1759 行）：

| PR | 分支 / issue | 占用区段（旧行号） | 与 VOCA-402 的关系 |
|---|---|---|---|
| **#20** | `docs/voca-399-ac1-alignment`（VOCA-399 = 缺陷 A，**本父单的兄弟子单**） | **`check_switch_ime_chain()` `@@ -348,9 +348,16 @@`**（改 348–356：键面文案由硬编码「⌨输入法」改为从 `toolbarKeys()` 现解析） | **正撞同一个函数** —— 见 §4.3 |
| #16 | `fix/voca-310-recognition-service-action` | docstring `@@ -17,7`；常量 `@@ -81,6`；新 helper `enclosing_fun()` `@@ -227,6`；`check_voice_chain()` `@@ -895,6 +915,163 @@`（新增 8.2b 段） | 函数不同，但抢 docstring 第 8 条与常量块 |
| #13 | `fix/voca-166-mic-permission-entry` | docstring `@@ -17,7`；常量 `@@ -49,6`；`check_voice_chain()` `@@ -832,6 / -911,7 / -919,17 +922,121 / -1001,6`（新增 8.4 段） | 函数不同；**且它是唯一在改 manifest 的 PR**（§4.2） |
| #14 | `fix/voca-233-version-display` | docstring `@@ -15,7`（改写第 7 条）；常量 `@@ -91,6`；`check_phrase_usage_singleton()` `@@ -729,18 +731,38 @@`（新增 7.1） | 函数不同；docstring 第 7 条与常量块重叠 |
| #12 | `agent/agent/voca-231` | docstring `@@ -17,6`（新增「8b」条目）；常量 `@@ -81,6`；`check_voice_chain()` `@@ -1019,6 +1025,97 @@`；**`main()` `@@ -1743,6`**（dispatch 新增 `-- 8b.` → `check_voice_timeouts()`） | dispatch 插入点在 1745 之后，与 #4/#10/#18 的 1753 之后不重叠 |
| #4 | `feat/voca-124-phrase-learning` | docstring `@@ -23,6`（新增「**13.** 词组自学习」）；`check_cell_dict_chain()` `@@ -1489,11`；新函数 `@@ -1725,6 +1737,70 @@`；**`main()` `@@ -1751,6`**（dispatch `-- 13.`） | 见 §4.3「13 号已被三方抢占」 |
| #10 | `agent/agent/voca-121` | docstring `@@ -23,6`（新增「**13.** 联想词上屏去重」）；常量 `@@ -79,6`；新函数 `@@ -1725,6 +1729,101 @@`；**`main()` `@@ -1751,6`**（dispatch `-- 13.`） | 同上 |
| #18 | `feat/voca-46-ux-46dp-space-period` | docstring `@@ -23,6`（新增「**13.** 双击空格出句号」）；常量 `@@ -47,6`；helper + 新函数 `@@ -1725,6 +1728,91 @@`；**`main()` `@@ -1751,6`**（dispatch `-- 13.`） | 同上 |
| #6 | `agent/agent/voca-190` | `check_voice_chain()` `@@ -850,17 / -869,7 / -938,6 / -1002,6`（整段 850–1019 被动） | 函数不同 |

### 4.2 manifest 侧：并发修改者是 PR #13，不是 PR #16

```
$ gh pr diff 13 --repo forcoder/ispeech | awk '/^diff --git/{f=($0 ~ /AndroidManifest/)} f' | grep '^@@'
@@ -25,8 +25,14 @@
```

PR #13 的 hunk 落在旧 25 行（`android:theme="@style/Theme.Ispeech">` 之后，给 `MainActivity` 加 `android:launchMode="singleTask"` + 一段注释），而 `<queries>` 段是 **11–15 行**（`fa57a07` 原文，`</queries>` 在 15 行）。两者之间 16–24 共 **9 行未改文本 > git 默认 3 行上下文的 6 行缓冲 ⇒ 文本不冲突**，可自动合并。但名单里必须写上它：**在本 ADR 的 `<queries>` 追加落地前后，manifest 只有一个别的修改者在跑**，合并窗口核这一段时应当核对的是 #13 而不是 #16（#16 根本不碰这个文件，见 §4 开头那条更正）。

### 4.3 门禁脚本侧：VOCA-402 的断言落点规则

**这里没有距离保护可言** —— 9 个 PR 已经在改同一个脚本，其中三处是「谁都要插同一行」的热点：

| 热点 | 旧行号 | 已抢占者 |
|---|---|---|
| 模块 docstring 的检查项编号清单 | 15–25 | #14（改写第 7 条）、#12/#13/#16（第 8 条区域）、#4/#10/#18（在第 12 条后追加「13.」） |
| 路径常量块 | 47–91 | #18（47）、#13（49）、#10（79）、#12/#16（81）、#14（91） |
| `main()` 里的 dispatch | 1743–1753 | #12（1745 之后）、**#4/#10/#18（都在 1753 之后，且三方各自写了 `-- 13.`）** |

**规则（写给 VOCA-402，也写给合并窗口）**：

1. **AC-1/AC-2 的断言天然落在 `check_switch_ime_chain()`（`fa57a07` 上 339–419 行，420 行起是 `check_t9()`），而 PR #20 = VOCA-399 正在改这个函数的 348–356 行** —— 这两个是同一个父单 VOCA-167 的兄弟子单，缺陷 A 的机检和缺陷 D 的机检会写进同一个函数。所以本 ADR 原来那句「不复用、不重排 8.2b 附近区域」**不足以避开真正的碰撞点**（8.2b 在 `check_voice_chain()` 里，本来就离得很远）。正确的约束是：**VOCA-402 的插入点不得落在 345–359 这段窗口内**（= #20 的 348–356 各加 git 的 3 行上下文）；若确实需要扩充 `check_switch_ime_chain()`，要么插在 359 之后（并保证与 #20 的新增行之间留够未改文本），要么以 #20 先合入后的文本为基准重写 hunk。
2. **新断言优先另立独立函数，不要塞进别人正在改的函数里。** 但要注意「追加在文件末尾」并不天然安全：`check_candidate_style()` 之后、`def main()` 之前的这段空档（三方 hunk 都是 `@@ -1725,6`，覆盖旧 1725–1730）正是 #4、#10、#18 三方共同的函数插入点，**不是安全位置**。可行的做法是把它当成一次有意的串行化：VOCA-402 的 PR 在合并窗口**最后合**，落点以当时 main 的实际文本现取现改，而不是照本 ADR 记下的行号开 PR。
3. **编号「13」已被 #4、#10、#18 三方各自占用**（docstring 清单与 `main()` dispatch 两处都是）。所以 VOCA-402 **不得在 PR 描述或代码里预先声明自己叫 13**，也不得假定任何具体数字；编号在合并窗口按当前 main 的最大编号 +1 现赋，docstring 与 dispatch 两处同步改。
4. 合并次序仍然是「先 #16（VOCA-310），后 VOCA-401」；但同一段脚本的并发修改者名单变了，合并窗口必须**逐条核对上面 9 个 PR 在本件之后是否仍在开着**，每多一个合入，VOCA-402 的落点就要按新的 main 重新定位一次（与本 ADR §4 同文件耦合② 那句「不许沿用本 ADR 的行号当坐标」同理）。

下表是耦合与次序规则的汇总，名单口径以 §4.1～§4.3 为准：

| 项 | 规则 |
|---|---|
| 语义耦合 | `RecognitionService` 那条 intent 是 VOCA-310 语音枚举的**运行前提**；谁把它删掉或收窄（例如换成 `<package>` 精确声明），语音侧立刻退化成「无可用服务」 |
| **同文件耦合 ①（manifest）** | 两条 intent 落在同一个 `<queries>` 元素内（11–15 行），属**同一 3～5 行窗口**，格式重排就会撞。全量枚举后这段的**并发修改者只有一个：PR #13（VOCA-166）**，hunk 在旧 25 行、距 `</queries>` 有 9 行未改文本 > 6 ⇒ **文本不冲突**；PR #16 当前不碰这个文件（§4.2）。合并窗口核这段 manifest 时要核对的是 **#13**，不是 #16 |
| **同文件耦合 ②（代码，实测新发现）** | PR #16 与 D3 的交叉校验**改同一个文件** `IspeechImeService.kt`：PR #16 的 3 个 hunk 在 1456/1470/1478（语音段），D3 的改动在 `enabledInputMethods()` 1390-1407 / `refreshInputMethods()` 1410-1418 —— **最近距离 38 行 > git 3 行上下文，不会文本冲突**，可自动合并。但 D3 落地后行号会整体下移，**任何引用行号的文档/评论都必须按「合入后的 main」重新定位**，不许沿用本 ADR 的行号当坐标 |
| **同文件耦合 ③（门禁脚本）** | `tools/verify/check_crosscut.py` 被 **9 个开着的 PR** 同时改（#4、#6、#10、#12、#13、#14、#16、#18、#20，见 §4.1）—— PR #16 只是其中一个（新增 8.2b 出口断言），VOCA-402 的机检也要往同一脚本加断言（下表「门禁」行②）。**这里没有距离保护**，且三处是同行热点（docstring 编号清单 15–25、常量块 47–91、`main()` dispatch 1743–1753）。规则见 §4.3：**AC-1/AC-2 的断言天然落在 `check_switch_ime_chain()`（339–419），正撞 PR #20 = VOCA-399 已占用的 348–356**，插入点必须避开该段而不是「不插在 8.2b 附近」；编号不得预先自称 13（#4/#10/#18 三方都已写 13），由合并窗口按当时 main 的最大编号 +1 现赋 |
| 合并次序 | **PR #16（VOCA-310）先合，VOCA-401（本 ADR 的实现）后合**。理由：① 310 已在 `in_progress`、PR 已开且 `MERGEABLE`，401 还被本件挡着（契约未 PASS 不排工期）；② 310 不动 manifest，先合不会给 401 制造冲突；③ 代码侧 38 行距离使 401 基于合入后的 main 改写 `IspeechImeService.kt` 时无冲突；④ 反过来若 401 先合，310 后续任何补 manifest 的整改就要 rebase 本 ADR 的段；⑤ 窗口内除 #16 外还要各核对一次 **PR #13（唯一并发改 manifest 的，§4.2）与 PR #20（与 VOCA-402 撞同一函数 `check_switch_ime_chain()`，§4.3）** —— 名单口径以 §4.1 的全量枚举为准，开着的 PR 每多一个，这两处核对就要重跑一次 |
| 后合方的改法 | VOCA-401 的 diff 必须是**纯追加**：在 `</queries>` 前插入 §3 D1 那段 `<intent>`，**不重排、不改缩进、不动 `RecognitionService` 那条、不删既有注释** |
| 冲突处理 | 若合并窗口里 `<queries>` 出现冲突，以「两条 intent 都在 + 注释按 D1 文本」为唯一正确解；不接受「二选一」或「合并成 `<package>` 列表」 |
| 门禁 | ① 任何后续往 `<queries>` 增删条目的 PR，描述必须引用本 ADR；② VOCA-402 的机检需断言：manifest 同时含 `android.speech.RecognitionService` 与 `android.view.InputMethod` 两条 intent（静态可检，不需要设备）；③ 该断言在两棵真值树（main 与最终出包树）分别出结论 |

---

## 5. 两个影响验收判读的技术问题（交付物 4）

### 5.1 ① `setInputMethod(windowToken, id)` 是否同样受可见性裁剪？

**结论：不受调用方可见性裁剪。** 三层证据：

1. **实测（修复后）**：处理组面板里点曾被裁掉的 `Gboard` 行 → `DEFAULT` 由 `com.ispeech.ime/…` 变为 `com.google.android.inputmethod.latin/…LatinIME`，`mCurMethodId` 同步变化（§1.6 原文）。AC-3 对「曾被漏列的项」成立。
2. **实测（漏列状态下）**：系统自己的选择器在控制组上仍列全 4 项（§1.6）→ 系统侧的启用/校验集合是完整的，裁剪只发生在「交给应用的那份清单」。
3. **调用面**：`setInputMethod(IBinder, String)` 只传一个 id 字符串，绑定目标服务由 system_server 完成，不需要调用方解析目标包 —— 与需要「解析成 `InputMethodInfo` 才能返回给应用」的 `getEnabledInputMethodList()` 不是一类调用。

**判读口径（给测试与 VOCA-228）**：
- 修好 D 之后，若某台机器仍「点了切不过去」，**不要再怀疑可见性**，那是 B 类（ROM 拦截 `setInputMethod`），按 AC-4/AC-6 取证：`adb logcat -d` 里 `IspeechImeService` 的 `setInputMethod 失败: …`（`IspeechImeService.kt:1436` 的 `Log.w`）是否出现、`showInputMethodPicker` 是否被调用。
- 残余不确定性（如实标注）：本轮**没有**直接实验「应用看不见该 id 时 `setInputMethod` 会不会被拒」—— 修好后这个状态在 UI 上已不可达，要造它得临时改代码造一个「只切不可见 id」的探针，属实现侧动作，不在架构件做。上面第 2、3 条支撑的是「不受影响」，若 VOCA-402 要钉死，建议加一条设备侧断言：处理组下逐行点全部 `|ENABLED|` 项并比对 `DEFAULT`（AOSP 一台 + EMUI 一台各一次）。

### 5.2 ② 目标输入法不可见时，兜底路径是否仍满足 AC-4「不得静默失败」？

**分两层，答案不同：**

| 层 | 判定 | 依据 |
|---|---|---|
| **选择器兜底**（`setInputMethod` 失败 → `showInputMethodPicker` → 再失败显示「系统不允许切换输入法」） | **满足**，且不受可见性裁剪 | `IspeechImeService.kt:1427-1452` 三段式；实测控制组点「系统选择器」弹出的系统对话框列出全 4 项（§1.6），用户总能切走 |
| **清单本身**（目标输入法根本不出现在面板里） | **不满足 AC-4 的精神** —— 它是**静默**的：面板显示「共 2 个」+ 2 行，没有任何「还有 N 项未显示」的提示，标签与行同源（`InputMethodPanel.kt:200-201`），所以「少列」在 UI 上完全不可区分于「系统里就只有 2 个」 | §1.2 控制组截图 `A-control-2of4.png` 与 `settings get secure enabled_input_methods` 的 4 项同框对比 |

**这条区分很重要，因为它决定 AC-4 怎么判**：AC-4 原文写的是「**切换失败**不得静默」，漏列不是切换失败，所以按 AC-4 字面判，控制组是 PASS 的 —— 缺陷 D 只能被 AC-2 抓到。因此：
- **不改 AC 文本**（本 ADR 无此权限，判据归洞察BA）；
- 由 **D3 的交叉校验**在实现侧把「静默少列」变成可见状态，让下一次复发不再只能靠人肉数行数；
- 给 VOCA-402 的可测点：AC-2 的取证必须**同框**包含 ① `settings get secure enabled_input_methods` 的条数 ② 面板「共 N 个」③ 列表行数（BA §2.3 打回项 4 已定这口径，本轮沿用并复现）；再加一条静态断言：`enabledInputMethods()` 内存在对 `Settings.Secure.ENABLED_INPUT_METHODS` 的交叉校验调用（防「实现被删」）。

---

## 6. 需人类拍板（交付物 5）

| # | 要拍什么 | 架构侧推荐 | 不拍的后果 |
|---|---|---|---|
| 1 | **授权 manifest 改动**：`<queries>` 追加 `android.view.InputMethod`。按项目规矩清单变更需人确认后才进实现（VOCA-401） | **批准**，按 §3 D1 逐字文本 | VOCA-401 不能开工，AC-2 继续 FAIL，用户仍看不到搜狗五笔 / 千问语音 |
| 2 | **D3 交叉校验是否本轮就落地**（提示文案 + `Log.w`） | **本轮落地**，文案由 UX 定稿；实现量小、收益是结构性防线 | 下次可见性口径变化仍会静默复发 |
| 3 | **出包前提**：下一次出包必须**用当前 `origin/main` 重打**（M5）。用户机上那一包出自 `origin/release/voca-186-v1.6` tip `eba7a61`（9 键 / v1.6·6，包字节 SHA256 `9cddcec5…43f42`），该分支与 main 分叉 `10 7`（落后 10） | **确认由项目经理在统一合并窗口执行**，且合并窗口需带上 PR #16 | 按 main 验收全绿、用户手上的包照旧漏列，第 4 轮再来一遍 |
| 4 | **取证设备独占**：本轮 `emulator-5554` 与其他 run 争用（§1.8） | 给架构/测试一台**专用**模拟器或真机，或约定取证时段 | 后续复现证据仍可能被质疑 |
| 5 | **EMUI 真机归属**：B 类（ROM 拦截）取证继续挂 VOCA-228 第 5 项；该单跟踪的 serial `23F0224224000950`（ALN-AL10 / Android 12）与质检取证的 `FMR0224913042917`（ALN-AL00）不是同一台 | 人类先确认哪台是用户手机 | 本修法在 EMUI 上是否够用，无法判定 |
| 6 | **是否另开单**：debug 构建不可字节复现（§1.5 附注） | 建议 SRE + 测试在出包门禁里加一条构建指纹口径，**不在本件范围**，本件不重复开单 | 包署名链（AC-1 的「被验对象必须署名」）继续有洞 |

---

## 7. 非功能影响

| 维度 | 影响 |
|---|---|
| 性能 | 无新增 IPC、无新增查询。`enabledInputMethods()` 仍在主线程调用（`refreshInputMethods()` 注释已定「本地数据，主线程查询即可」，`IspeechImeService.kt:1409-1411`），行数从 2 变 4 的量级对渲染无影响，**且 4 行根本不触发滚动**（VOCA-405 R1 校正 —— 本行原文「4 行 472px 已超出可见高 → 会滚动」把 472 与 660 比反了，并与 §1.3 截图 B 自相矛盾）。正确的算术：`BoundedScrollView` 用 `MeasureSpec.AT_MOST(maxHeight)`（`InputMethodPanel.kt:314-318`），`maxHeight = dp(240)`（`maxPanelHeightDp = 240` 在 57 行、构造在 161 行，`dp()` 在 283 行），440dpi ⇒ 1dp = 2.75px ⇒ **上限 660px**；实测 1 行 = 118px（§1.2～§1.4 的 frame 顶差：2→3 行 1576−1460 = 116、3→4 行 1460−1340 = 120，均值 118）⇒ **660 ÷ 118 = 5.59 ⇒ 滚动门槛 ≥6 行**（5 行 590px 不滚，6 行 708px 才滚）。就算扣掉 ScrollView 自身的 `dp(4)+dp(8)` = 33px 内边距（内容区 627px），5 行 590 仍不滚、6 行 708 仍要滚，**结论对两种口径都成立**。故 AC-2 取证「滚到底补一张」的适用条件是 **`|ENABLED| ≥ 6`**，不是 4 行（同口径见 §8 F6、§10 证据口径） |
| 包体 / 依赖 | manifest +3 行，无新依赖、无新权限、无新 SDK 调用面 |
| 隐私与合规 | 不引入 `QUERY_ALL_PACKAGES`；可见面只扩到「声明了 IME action 的包」这一系统级类别。隐私政策无需新增条目（不采集、不上报清单内容；日志只记条数，不记 id —— 见 §9） |
| 可观测性 | D3 的 `Log.w` 是唯一的可观测性新增，字段只有两个整数（`visible` / `enabled`），不含包名与用户标识 |
| 兼容性 | `minSdk = 26`：`<queries>` 在 API 30 以下被忽略（无害）；`android.view.InputMethod` 自 API 1 起就是 IME 服务的标准 action，声明本身零风险 |

---

## 8. 失败模式清单（本修法之后仍会坏的地方）

| # | 失败模式 | 触发条件 | 现有缓解 | 缺口 / 归属 |
|---|---|---|---|---|
| F1 | ROM 拦截 `setInputMethod`（EMUI/MIUI） | 华为真机 | 回落 `showInputMethodPicker`，再失败显示「系统不允许切换输入法」 | 未取证，挂 VOCA-228 第 5 项（§6 第 5 项） |
| F2 | 可见性口径再变（targetSdk 升 35 / AOSP 改实现） | 升级 targetSdk 或换 ROM | **本轮前无任何防线**；D3 交叉校验后 → 可见提示 + 日志 | 依赖 §6 第 2 项批准 |
| F3 | `RecognitionService` 那条 intent 被删/收窄 | 后续有人「整理」`<queries>` | §4 的门禁 + VOCA-402 静态断言 | 断言落地归 VOCA-402 |
| F4 | 用户要的其实是「未启用的也列出来 + 引导启用」（C 类） | 需求变化 | 本 ADR 明确不做（§3 D2/D4） | 回洞察BA 重开需求，不在本件 |
| F5 | 出包分支不回追 main，修好的是 main、装出去的是 release tip | 合并窗口没执行 M5 | §6 第 3 项已列为硬前提 | 项目经理 + SRE 执行，需人类确认 |
| F6 | 面板行多到超出 ScrollView 660px 上限，单张截图只拍到前面几行 | **`|ENABLED| ≥ 6`**（6 行 708px > 660px）。**不是 4 行** —— 4 行 472px 不触发滚动（VOCA-405 R1 校正：原条件写成「4 行」会造出一条永远不可能触发的失败模式，并让 4 行的取证去补一张根本不存在的滚动图） | BA 口径「滚到底补一张」按 ≥6 行适用 + 标签同源（`InputMethodPanel.kt:200` 「共 N 个」与行同源） | 归 VOCA-401/402 执行；断言与取证口径见 §10 |
| F7 | 设置页崩溃（VOCA-170 NPE）污染复现路径 | 验证时打开设置页 | 修复 `d3b6f84` 在 PR #9，**既不在 main 也不在 release tip**（BA §8.2 复核） | 合并窗口必须核对 PR #9；不在本件 |

**外部依赖三问**（本件唯一的外部依赖是 Android framework 的可见性规则）：挂了怎么办 —— 该规则不会「挂」，但会变（F2），缓解是 D3 让变化可见；限流怎么办 —— 不适用（无网络调用）；价格变了怎么办 —— 不适用（无付费服务）。

---

## 9. 回滚条件与方式

- **回滚方式**：删掉 `<queries>` 里那 3 行 `<intent>`（单文件、单 hunk），重新出包。无数据迁移、无状态残留、不影响语音侧（语音侧依赖的是另一条 intent）。
- **回滚触发**：① 装上后语音链路出现任何回归（`hasUsableService` 判「无可用服务」、识别失败率上升）；② 任何一台真机出现「面板列出无法绑定的输入法并导致切换后键盘起不来」；③ Play 政策 / 隐私审查对该声明提出异议（预期不会：它不是 `QUERY_ALL_PACKAGES`）。
- **回滚后**：AC-2 回到 FAIL 状态，本 ADR 状态改回 `Proposed` 并重开备选方案（届时唯一可行方向是自己读 `Settings.Secure.ENABLED_INPUT_METHODS` + `InputMethodInfo` 自建清单，代价见 §3 D2 第 4 行）。
- **日志脱敏**：D3 的 `Log.w` 只允许 `visible=%d enabled=%d` 两个整数，**禁止**打印输入法 id、包名或用户输入法组合（共享 Harness 规则 §8/§9）。

---

## 10. 给下游的接口契约（VOCA-401 唯一输入）

```
契约名        : IME 清单完整性（AC-2）
前置          : §6 第 1 项人类批准
改动面        : app/src/main/AndroidManifest.xml 的 <queries> 段（+3 行，纯追加）
               + IspeechImeService.enabledInputMethods() 内的交叉校验（若 §6 第 2 项批准）
不变量        : ① 面板行数 == |ENABLED|，且 ENABLED 中每个 id 都有行
               ② 未启用的输入法不出现
               ③ 「共 N 个」标签 == 行数 == |ENABLED|（三者同框可比）
               ④ 取数只允许一处（enabledInputMethods()）
               ⑤ n > items.size 时必须可见提示，禁止静默
禁止          : QUERY_ALL_PACKAGES / 新运行时权限 / <package> 点名 / 写 settings / 自建输入法管理页
               / 改 AC 判据 / 改工具栏键序 / 动 RecognitionService 那条 intent
可测点        : 静态 —— manifest 两条 intent 都在；enabledInputMethods() 内存在交叉校验调用
               设备 —— |ENABLED|=4 → 4 行；disable 一项 → 3 行且该项不出现；逐行点切 → DEFAULT 逐项变化
证据口径      : 同一张截图内 ① 共 N 个 ② 全部行（列表只在 |ENABLED| ≥ 6 时才滚动，届时才补「滚到底」一张；
               4 行 472px < 660px 上限，不滚动、不需要补图 —— 见 §7 / §8 F6） ③ 标题栏「输入法 N 个」，
               外加 `settings get secure enabled_input_methods` 与 `ime list -s` 命令原文
回滚          : 删 3 行重出包（§9）
```

---

## 附录 A：本轮实验的完整命令序列（可复现）

```
# 载体
git rev-parse HEAD                     # 007874d…（源码 == fa57a07）
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --offline assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk

# 控制组
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk        # 48ee0d3d…b330
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell settings get secure enabled_input_methods            # 4 项
adb shell am start -n com.google.android.settings.intelligence/.modules.search.SearchActivity
adb shell input tap 500 224                                    # 聚焦搜索框 → 键盘起
adb shell dumpsys window windows | grep -A45 "Window #.*InputMethod}:" | grep Frames:
adb shell input tap 70 1469                                    # 工具栏第 1 键 = SWITCH_IME
# → 截图 A-control-2of4.png：共 2 个 / 2 行 / frame=[0,1576][1080,2340]

# 处理组
#   app/src/main/AndroidManifest.xml 的 <queries> 追加 <intent><action android:name="android.view.InputMethod"/></intent>
./gradlew --offline assembleDebug
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk        # 4893699b…10c7
aapt dump xmltree app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | grep -A6 "E: queries"
adb install -r app/build/outputs/apk/debug/app-debug.apk
# → 同上取键盘 + tap 70 1469
# → 截图 B-treatment-4of4.png：共 4 个 / 4 行 / frame=[0,1340][1080,2340]
adb shell input tap 500 1719                                   # 点 Gboard 行
adb shell settings get secure default_input_method             # → …latin/LatinIME
adb shell ime set com.ispeech.ime/com.ispeech.input.IspeechImeService   # 还原

# 负控（处理组）
adb shell ime disable com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService
# → 截图 C-treatment-3of3-disabled-hidden.png：共 3 个 / 3 行 / frame=[0,1460][1080,2340]
adb shell ime enable com.google.android.tts/…VoiceInputMethodService     # 还原为 4 项

# A/B/A 回控
git checkout app/src/main/AndroidManifest.xml ; git diff --stat          # 空
./gradlew --offline assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
# → 截图 E-abab-control-again-2of4.png：共 2 个 / 2 行 / frame=[0,1576][1080,2340]

# 兜底（控制组）
adb shell input tap 154 2110                                   # 面板「系统选择器」
adb shell uiautomator dump /sdcard/pk.xml                      # 选择输入法 / Gboard / Google 语音输入 / 搜狗输入法 / 智语
# → 截图 D-control-syspicker-4.png

# 机制侧
adb shell cmd package query-services -a android.speech.RecognitionService | grep name=
adb shell cmd package query-services --pkg com.ispeech.ime -a android.view.InputMethod   # API 34 不支持 --pkg（记录）

# 收尾设备状态
DEFAULT  = com.ispeech.ime/com.ispeech.input.IspeechImeService
ENABLED  = 4 项（与本轮开始时一致）
安装态   = 控制组构建（源码 == fa57a07），实验用的 manifest 改动未进分支
```

## 附录 B：本 ADR 与 VOCA-168 验收条目的对应

| AC | 本 ADR 的关系 |
|---|---|
| AC-1 文档键位 | 无关（归 VOCA-399 文档件） |
| **AC-2 列表完整性** | **本 ADR 定修法**；判据不变，§10 是不变量 |
| AC-3 点行切走 | §5.1 给出「曾被漏列的项同样可切」的实测与判读口径 |
| AC-4 失败不得静默 | §5.2 拆成「选择器层满足 / 清单层不满足」，缺口由 D3 补，不改 AC 文本 |
| AC-5 空态 | 不受本修法影响（`|ENABLED|=1` 时 items.size=1，走「没有其他输入法」分支）；取证仍归 VOCA-401 |
| AC-6 ROM 拦截 | §5.1 判读口径 + F1，归属 VOCA-228 |
| AC-7 人工可发现 | 无关（归 VOCA-399） |
