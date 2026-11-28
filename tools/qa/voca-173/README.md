# VOCA-173 · 从 `817b6ad` 出候选包 + emulator-5554 A/B 复跑

被测代码 = VOCA-650 修复分支 `fix/voca-650-pick-association` head `817b6ade1cf5f3610724b1964592760720567b68`
（PR #48，base `main`，当前 `OPEN / MERGEABLE`，**未合入 main**）。

## 候选包

| 项 | 值 |
|---|---|
| 构建 commit | `817b6ade1cf5f3610724b1964592760720567b68`（`git archive 817b6ad` 导出干净树后 `:app:assembleDebug`） |
| APK | `ispeech-voca173-817b6ad-debug.apk`，55 161 219 B |
| sha256 | `a719f21ff5cb6d0f6ec30764135b3909b608276fbc49ec8a8e0934c1ab38fc4d` |
| versionCode / versionName | `5` / `1.5`（未改版本号，只能靠 sha256 / `lastUpdateTime` 分辨） |
| 签名 | `CN=Android Debug`，证书 SHA-256 `857ce64eebe7fed7c51596ed057030f70d38102e300116cd5aca8b4f9f66a0ed` |
| 设备侧 base.apk sha256 | `a719f21f…ab38fc4d`（与构建产物逐字节一致） |

**为什么只打 arm64-v8a**：全 ABI 包 145 873 847 B 超过平台单条评论附件上限（上传被 413 拒绝）。
用户机型 ALN-AL00 与 `emulator-5554` 都是 arm64-v8a，故本取证包用
`ndk { abiFilters += listOf("arm64-v8a") }` 收窄到 55 MB；**这一行只加在 `/tmp` 的临时构建副本里，
不在 817b6ad 源码树上**，所以与 PR #48 内容无关，不改 817b6ad 的代码。构建产物直接由 817b6ad
导出树编译，`AssociationCommit` / `CandidateCommitPolicy` 与该 head 逐字节相同。

构建环境（本机 MStudio，JDK 17 + SDK 34）：Gradle 用户目录与 `debug.keystore` 在沙箱下不可写，
故 `GRADLE_USER_HOME` / `ANDROID_PREFS_ROOT` / `TMPDIR` 都指向 `/tmp` 下的可写副本，
`gradle.properties` 补 `-Djava.io.tmpdir`。

## A/B 对照

同一台 `emulator-5554`（API 34 / google_apis arm64-v8a / 1080×2340@440），同一宿主
（Messages 短信输入框，`uiautomator` 读 `focused="true"` EditText），同一工装（`ab.py`）。
判定不靠截图猜：候选条是 Canvas 自绘、`uiautomator` 拿不到 IME 节点，故「点选后有没有出联想」
只认 `logcat -s IspeechIme` 的 `associate hit chars=N count=M`（M>0 出联想）/ `associate miss`（被关）。

### A = 候选包 `817b6ad`（sha256 `a719f21f…ab38fc4d`）

| 用例 | 步骤 | 观测 | 判定 |
|---|---|---|---|
| 1 | `zhi` → 点首选「之」 → 空格 | `associate hit chars=1 count=25`；联想条 6 项；空格后上屏整条联想词 | **PASS**（点选后联想存活） |
| 2 | `zhidao` → 点首选「指导老师」 → 读联想条 | `associate hit chars=4 count=24`；联想条 6 项 | **PASS** |

### B = 现行 main 出包 `ispeech-internal-v1.6-final-20261126.apk`（sha256 `02fa7b5d…cace907b`）

| 用例 | 步骤 | 观测 | 判定 |
|---|---|---|---|
| 1 | `zhi` → 点首选「之」 → 空格 | 无 `associate hit`、无 `associate miss`；联想条 0 项；空格后仅多 1 个空格 | **FAIL**（点选后联想被关） |
| 2 | `zhidao` → 点首选「指导老师」 → 读联想条 | 联想条 0 项（`cand_count=0`，无首选高亮盒） | **FAIL** |

### AC-1 / AC-2 复跑（A 包）

| AC | 步骤 | 实际输入框 | 判定 |
|---|---|---|---|
| AC-1 | `zhidao`→「指导老师」→ 点联想首选 | `指导老师专`（不是 `指导老师师专`） | **PASS** |
| AC-2 | `zhi`→「之」→ 点联想第 2 项 | `之一`（不是 `之之`） | **PASS** |

## 未覆盖 / 取证限制

- **真机项仍缺**：`adb devices -l` 只有 `emulator-5554`（`ioreg` 无 HUAWEI 节点）。以上全部是模拟器口径，
  **不得**当作用户登记机型 `FMR0224913042917` 的真机证据。
- 联想条候选**内容**未逐字读：只断言「出没出联想」与「上屏文本对不对」，不断言具体词条。
- 模拟器上重复跑会在 `files/user_dict.txt` 留自造词（如 `zhidaolaoshi 指导老师`），
  跨包对照前已 `pm clear com.ispeech.ime` 拉平起点。
- `ensure_host` 必须先点输入框再等键盘：`am start` 后上一轮的 `type=ime` inset 会残留，
  只看 frame 非空会过早判定「键盘就绪」，按键全落空（宿主文本仍为占位符「短信」）。

## 复现

```bash
# 1. 出包（干净树，别在工作区里删改）
git archive 817b6ad | tar -x -C /tmp/voca173-build
cd /tmp/voca173-build && ./gradlew assembleDebug
shasum -a 256 app/build/outputs/apk/debug/app-debug.apk

# 2. 装机
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable com.ispeech.ime/com.ispeech.input.IspeechImeService
adb shell ime set com.ispeech.ime/com.ispeech.input.IspeechImeService

# 3. 复跑（需 Pillow；band.py 是 VOCA-443 的候选带像素库）
cd tools/qa/voca-173 && python3 ab.py arm64A all
```