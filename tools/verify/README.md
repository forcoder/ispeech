# tools/verify —— 静态校验与数据/逻辑测试

本目录放的是**离线自查脚本**，用于在没有 JDK / Android SDK / 网络的机器上，
对「智语 iSpeech」仓库做一轮可自动化的检查与回归。只用 Python 3 标准库，
不联网、不 `pip install`、不依赖编译。

> 唯一例外：`check_model_host.py` 需要联网（它探测的是外部模型托管源），
> 因此**不在离线自查套件里**，单独按需 / 定时运行。

## 运行方式

在**仓库根目录**直接运行（脚本内部用 `Path(__file__).resolve().parents[2]`
定位仓库根，因此不依赖当前工作目录）：

```bash
python3 tools/verify/check_static.py
python3 tools/verify/check_input_logic.py
python3 tools/verify/check_dist_manifest.py         # dist/SHA256SUMS 出包门禁
```

模型源探测（需要联网，退出码非 0 = 源不可用）：

```bash
python3 tools/verify/check_model_host.py                    # 探测 build.gradle.kts 的默认源
python3 tools/verify/check_model_host.py <url> --sha256     # 指定源并整包校验摘要
python3 tools/verify/check_model_host.py --self-test        # 不联网验证判定逻辑
```

输出约定：每条检查一行，以 `PASS` / `FAIL` / `WARN` 开头；末尾打印
`SUMMARY: passed=N failed=M warned=K`，**任一 `FAIL` 时退出码为 1**，全通过为 0，
可直接接入 CI 或 pre-commit。

## check_static.py —— 仓库静态一致性

| # | 检查项 | 说明 |
|---|--------|------|
| 1 | XML 可解析 | `app/src/main/res/**/*.xml` 全部过 `xml.etree.ElementTree`，失败打印文件:行:列 |
| 2 | 资源引用存在 | 扫描 `app/src/main/java/**/*.kt`（排除 `build/`）：`R.id.*` 需要在 layout 里有 `@+id/同名`；`R.layout.*` 需要 `res/layout/同名.xml`；`R.string/color/style.*` 需要在 `res/values*/` 里声明。`android.R.*` 框架资源不计入 |
| 3 | 自定义 View | layout 里的 `<com.ispeech.xxx.Yyy>` 必须在 Kotlin 里有同名 `class Yyy` |
| 4 | 括号配平 | 状态机（正确跳过 `"..."` / `"""..."""` / `'...'` 含转义 / `//` / `/* */` 嵌套注释）检查 `{} () []` |
| 5 | 孤立 `}` | 报告「栈为空时出现的 `}`」（顶层多余右花括号）。只做可可靠判定的子集，不做缩进启发式，避免误报 |
| 6 | 模型源配置自洽 | `build.gradle.kts` 的默认 `asrModelUrl` 若是明文 `http://`，其域名必须在 `network_security_config.xml` 里被 `cleartextTrafficPermitted="true"` 放行；HTTPS 源默认放行。防止「只改 URL 没改放行名单」导致全量用户下载失败 |

## check_input_logic.py —— 输入逻辑 / 数据测试

| # | 检查项 | 说明 |
|---|--------|------|
| 1 | 五笔词库 | 解析 `app/src/main/assets/wubi86.dict`（`code\ttext\tweight[\ttype]`）：样码 `wqiy→你` `trnt→我` `vbg→好` `jhnh→早` `hqce→餐`；打印总词条 / 单字 / 词组 / 最大 weight（期望 65535） |
| 2 | 快捷短语 | `app/src/main/assets/quick_phrases.txt` 每行 >= 3 个 tab 字段、`mode` 合法、`hi` 规则 kind=a |
| 3 | 键盘布局 | 解析 `KeyboardLayouts.kt` + `KeyDef.kt` + `EmojiData.kt`：`KeyAction` 使用值 ⊆ 声明值；`KeyboardLayoutKind` 四值在 `pages()` 的 `when` 里全覆盖；EmojiData 类别数 >= 6、每类 >= 24、全局去重；每个 `KeyboardPage` 的行数与每行键数（字母页应为 4 行 = 10/9/9/5） |
| 4 | 标点转换 | 解析 `Punctuation.kt` 的 `chineseMap`，用 Python 复刻 `PunctuationConverter.convert`（成对引号 + 先映射后全角）并断言 6 组用例 |
| 5 | 候选排序 | 用 Python 复刻 `CandidateRanking` 比较器 + `UserDictEngine` 的 `+100000` 加成，验证同码下用户词排在系统词前，并校验「精确码优先 → 应用<短语<五笔 → 权重降序」 |

## check_dist_manifest.py —— 随包校验清单门禁（VOCA-400）

校验 `dist/SHA256SUMS` 与包字节是否一致，并把它接进出包流程。口径的出处是
`docs/adr/VOCA-400-dist-checksum-manifest.md`（ADR-0003），这里只列判据。

| # | 检查项 | 为什么 |
|---|--------|--------|
| 1 | 每行格式合法（64 位小写 hex + 两空格 + 路径），路径不越出 `dist/` | 手写 / 编辑器折行造成的坏行会被 `shasum -c` 静默跳过 |
| 2 | 同一路径不重复 | `>> SHA256SUMS` 追加的后果：两行并存时只认后一行，前一行永久静默 |
| 3 | 每条记录指向的文件真实存在 | 防止「包已删除、记录还在」的孤儿行 |
| 4 | 每条记录的 sha256 == 实际字节 | 等价 `cd dist && shasum -a 256 -c SHA256SUMS`；main 上曾有 2 条 FAILED |
| 5 | **穷尽覆盖**：`dist` 下每个该记录的 APK 都有一行 | 只校验「清单里有的」，漏记的包一声不吭 —— `_archive/` 三个历史包就是这么漏掉的 |
| 6 | 未跟踪且 >100 MB 的包不入册即中止 | 记了也推不上 GitHub（GH001），清单会指向仓库里永远不存在的字节。已跟踪超限只 `WARN`（归 VOCA-292），无 git 元数据时同样只 `WARN` 并写明「跟踪状态未知」—— 分不清就不抢判定权，也不谎称已跟踪 |

「该记录的集合」= `git ls-files --cached --others --exclude-standard -- dist` 里的 `*.apk`
（已跟踪 + 会被 `git add -A` 扫进来的），因此刚 `cp` 进 `dist/` 还没入库的新包立刻要求记录；
`.gitignore` 排除的本机残留不强加。无 git 元数据时（`git archive` 解出来的包）退回目录扫描。

**这条口径有一个必须知道的边界**：`deploy.sh` 不带 `--release` 时的产物名
`dist/ispeech-debug-<versionName>-<ts>.apk` 正落在 `.gitignore:43` 里，所以它**既不被要求入册、
`--regen` 也不会记它** —— 第五条门禁的「归档即记录」只对 `--release` 路径成立。
debug 包归手机自查用出包日志里的 `sha256=` 行 + 设备侧 `dumpsys package`，清单答不了它。
理由与待拍板冲突见 ADR-0003 D2。

```bash
python3 tools/verify/check_dist_manifest.py           # 校验；任一 FAIL 退出码 1
python3 tools/verify/check_dist_manifest.py --regen   # 按当前字节整份重写清单（唯一写入路径）
python3 tools/verify/check_dist_manifest.py --self-test  # 8 场景双向验判定逻辑本身，不动仓库（超限那条把两个分支各跑一次）
```

**出包门禁（第五条）**：`tools/deploy/deploy.sh` 在 ③ 校验段调用它，位置在 `--skip-verify`
分支**之外** —— 跳过这项等于允许带着一份会说谎的清单出包，而「会说谎」正是 VOCA-400 的原始故障。
⑤ 归档段在 `cp` 之后立即 `--regen`（重写 + 整份回读校验，FAIL 即中止出包），随后按本包是否真进了
清单分支打印：入册报「本包已入 dist/SHA256SUMS：<路径>」，被 `.gitignore` 排除则 `[warn]` 明说未入册
并给出自查方式。**不会对着一个没记录的 debug 包说「已记录」。**

超限那条断言的是**输出行的级别**，不只是退出码：`git add` 后（已跟踪）必须出现一行 `WARN:` 且 `rc=0`，
`git rm --cached` 后（未跟踪）必须出现一行 `FAIL:` 且 `rc=1`。上一版只比退出码，结果「已跟踪只 WARN」
在机器上打的其实是一行 `PASS`（连「超限」两个字都没有），文案与机器分叉了一整轮 —— 只比 `rc` 的自测
挡不住这种漂移，所以本条把两个分支的级别都验上。**同理**：演练脚本自己也要断言「被检对象真的执行了」，
上一版回滚演练用 `git revert --no-edit -q`（`-q` 不是 revert 的选项）导致命令直接吐 usage，而判定语句
只比 `git diff` 就打印「逐位一致」—— 假演练给出过假 PASS，读数见 ADR-0003 §5.3。

要改这项门禁或它的接线，先看 ADR-0003 **§5**（回滚两种粒度 + 触发阈值 + 实测时长）与 **§7**
（发布顺序、灰度等价物、成功/失败判定、通知对象）—— 它拦在出包链路上，止血时不必整包 revert。

## check_model_host.py —— 模型托管源可用性（**需联网**）

给定 `asrModelUrl`（缺省从 `app/build.gradle.kts` 解析），逐项断言：

| # | 断言 | 为什么 |
|---|------|--------|
| 1 | 重定向链上无 `dnspod.qcloud.com` / `webblock` | 备案拦截页（2026-11 事故原始症状） |
| 2 | 最终状态码 200 | 4xx/5xx、拦截页 502 |
| 3 | `Content-Type` 不是 `text/*` / `json` / `xml` | **200 + HTML** 会被客户端当模型写进 `.part` |
| 4 | `Content-Length == 77920048` | 截断 / 源上是别的文件 |
| 5 | 响应体前 3 字节是 `BZh` | 头部撒谎时唯一的硬证 |
| 6 | `Range: bytes=16780-` → 206 且总长一致 | 断点续传前提 |
| 7 | `--sha256` 整包摘要 == `da92b3db…b1450` | 内容替换 / 镜像不同步 |

`--self-test` 在 127.0.0.1 上伪造 6 类源（正常 / 200+HTML / 假二进制 / 拦截跳转 /
长度不符 / 不支持 Range），**不联网**验证判定逻辑双向准确；判定逻辑改动后先跑它。
处置流程见 `docs/model-host-runbook.md`。

## check_crosscut.py —— 跨组件接线（8.1–8.8）

按「一条能力要穿过几层文件」组织的连线检查：词库/编码（8.1）、键盘布局与按键动作
（8.2）、候选栏（8.3）、语音输入链路（8.4）、ASR 设置与 DataStore（8.5）、快捷短语
（8.6）、主题与令牌（8.7）、资源与字符串（8.8）。任一断言 `FAIL` 退出码 1。

8.4 语音链路的口径（VOCA-166 起）：**未授予 `RECORD_AUDIO` 不再是从输入法自动拉起
设置页的理由**，脚本改为断言「跳页只能由用户在候选条上主动点击触发」——即服务侧
`showMicPermissionPrompt()` / `openMicAuthorization()` 与 `MicGrantAction` 决策分离、
`MainActivity` 认 `REQUEST_MIC_EXTRA` 会话（新建与 `onNewIntent` 复用两条路都要认）、
manifest 里 `MainActivity` 声明 `singleTask`（否则复用实例时 `onNewIntent` 不发、
授权框弹不出来，用户被丢在设置页）、会话结束 `finish()` 回原输入框。

架构师评审（ADR-001 决策 3 / 4 / 6）后又锁了四条：两条语音链路**各自**都必须接
`showMicPermissionPrompt()`（不再用「调用点数 == 3」这种实现计数断言，将来接第三条链路
不会误伤）；`launchMicIntent` 必须 `catch (Throwable)` 并记含异常类型的 `mic_auth_jump_failed`
——跳转跑在输入法进程的点击回调里，漏一个 `SecurityException` 就是一次键盘消失；跳转目标走
`jumpPlan` + `firstOrNull`，一次点击最多成功跳一次；按钮的文案与可用性只有 `CandidateView.update()`
一条写入通道（公开 setter 一出现即 `FAIL`），且 `onWindowShown` 必须带 S8 那句一次性指引。

与 FunASR 接线（VOCA-190 / PR #6）合流后的补充口径：① 的 `toggleFunAsrVoice()` 未授权分支
仍按 main 旧名调用 `requestMicPermission()`，④ 保留该名字为**兼容私有 helper**、体内只委托
`showMicPermissionPrompt()`（不恢复自动跳页）。脚本只在「FunASR 入口与提示流并存」的树上
追加一条断言锁住这层接线；① 单独树（旧定义仍在）不触发，不影响其各自验收。

```bash
python3 tools/verify/check_crosscut.py
```

## 局限（这些脚本覆盖不到的东西）

- **不能替代编译**：Kotlin 类型推断、泛型、签名匹配、import 缺失、Compose API
  兼容性等只有真实 `./gradlew :app:assembleDebug` 才能发现。本脚本只做文本级断言。
- **括号配平**：字符串模板 `${...}` 内部的嵌套引号/大括号未做专门处理；若出现
  `"${ '}' }"` 这类极端写法可能误判（当前代码库没有这种写法）。
- **`KeyboardLayouts` 结构测试是「表达式估算」**：通过识别 `rows(...)` /
  `letterKeys("...")` / `row(...)` / `digitKeys()` / `bottomRow()` 等构造式来推断键数，
  不是真正执行 Kotlin。若布局写法换成了脚本不认识的形式，会把该项标为 `FAIL`
  而非静默通过。
- **emoji 去重按字面字符串**比较（含变体选择符 VS16），不按 Unicode 码点归一化；
  即「同一个 emoji 用不同码点序列表示」这种情况检测不到，但本项目都是字面量。
- **资源引用检查只覆盖 `R.` 前缀的直接引用**，覆盖不到反射 / 字符串拼装的资源名。
- **`check_model_host.py` 只看到服务端响应**：它证明「源现在能交付正确的包」，
  不证明客户端能装、能用（那靠 VOCA-34 的单测与真机冒烟）；探测环境到源站的网络
  也可能与终端用户不同，因此定时探测需在构建机与办公网各跑一份对照。
- 脚本**不修改任何 Kotlin / XML 源码**，只读取与报告。

## 当前基线

最近一次运行（见上层汇报）：

- `check_static.py`：全绿（`SUMMARY: passed=6 failed=0 warned=0`，退出码 0）。
- `check_input_logic.py`：除 **EmojiData 跨分类重复 emoji** 外全绿
  （`😍` / `🥰` / `😘` 同时出现在「常用」与「情感」，见
  `app/src/main/java/com/ispeech/input/keyboard/EmojiData.kt:27-28` 与 `:50`），
  因此该项 `FAIL`、脚本退出码为 1，属**预期内的真实发现**（源码问题，交由开发修复）。

> 2026-11-09 复核：`check_input_logic.py` 实测 `SUMMARY: passed=38 failed=1`，失败项为
> 「只解析到 2 个静态页（期望 >= 4）」（脚本解析不了当前 `KeyboardLayouts.kt` 的写法）。
> 该失败在 VOCA-35 改动前就存在，与本 issue 无关，建议另开 issue 修解析或补用例。
