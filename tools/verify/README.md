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

## check_voca167.py —— VOCA-167 文档指位（AC-1）与输入法列表完整性（AC-2）

VOCA-167 的机检与回归口径。**结论按「被验的那棵树」出**，不是按当前工作目录出：
`--tree <rev>` 用 `git archive` 把该提交导出到临时目录后再解析，每行输出前缀
`<tree-oid>:<label>`，所以 main / 出包分支 / PR head 的结论不会互相冒名。
`--tree` 也可以指**目录**（导出后改过东西的树、别人的工作区），此时身份只从该目录自身字节算：
干净 checkout ⇒ 它自己的 `HEAD^{tree}`，有改动 ⇒ `workdir-<摘要>`，快照 ⇒ `dir-<摘要>`，
并且**必须同时给 `--label`** —— 不给就拒绝运行，绝不拿运行仓库的 HEAD 当对象 id 给别棵树背书。
不写 `check_crosscut.py` 是为了避开 9 个在跑的 PR 对同一文件的冲突（ADR VOCA-398 §4.3）。

判定分三层，编号不互相冒充：

| 层 | 编号 | 什么时候才有结论 |
|---|---|---|
| 静态前置 | `AC3-S` / `AC4-S` / `AC5-S1..S3` | 读源码就能判（入口在不在、文案在不在、兜底链齐不齐）。**只证明「有实现」，不等于验收判定** |
| 运行时判定 | `AC3-1` / `AC4-1` / `AC5-1..3` / `AC6-1..2` | 只有 `--device --exclusive-device` 才判；没设备一律 `SKIP`，写明**「运行时判定未取证」** |
| 署名链 | `PRE-0/1/2/3` | 只有传了 `--package` 才跑；没传每棵树显式 `SKIP`，免得读者以为跑过 |

```bash
python3 tools/verify/check_voca167.py --tree main                      # 主干
python3 tools/verify/check_voca167.py --tree fa57a07 --tree pr20       # 多棵树逐个出结论
python3 tools/verify/check_voca167.py --tree <目录> --label <名字>      # 目录输入必须署名
python3 tools/verify/check_voca167.py --tree <rev> \
    --package dist/ispeech-internal-v1.6.apk --package-sha256 <摘要>   # 带上被验安装包
python3 tools/verify/check_voca167.py --tree <rev> --device emulator-5554 --exclusive-device
python3 tools/verify/check_voca167.py --self-test                      # 不读仓库，验证断言本身
```

汇报读数时**连同参数一起报**（脚本会回显 `CMD:`）：没传 `--package` 就没有署名链，
没传 `--device` 就没有运行时判定 —— 少了参数的读数不能写成「全量跑过」。

| # | 断言 | 说明 |
|---|------|------|
| AC1-1 | 键数 | 文档写「工具栏（N 键）」必须等于该树 `toolbarKeys()` 解析出的键数 |
| AC1-2 | 「第 N 键」指位 | 按 `KeyAction.*` > `「」`内称呼 > 别名 > 键面 的顺序定指位对象，再比该树实际位置；解析不出/多义只 `WARN`，不猜 |
| AC1-3 | 键序 | 键名清单与反引号键序行逐位比对；个别称呼在该树解析不出时降级人工核对，但**错位就是红** |
| AC1-5 | 内嵌摘录 | `DOC_GLOBS` 里**任何** ```kotlin 块中的 `KeyDef.fn(...)` 都逐字段比（键面 / `weight` / `iconRes`），不要求先写 `SoftKeyboardView.kt:起-止`；`SWITCH_IME` 无条件按工具栏比，其余按最近锚点定归属，数字页 / 符号页 / 候选条的摘录不参与工具栏比对，判不出归属只 `WARN` 不猜。没写行号标记的摘录额外 `WARN`：键面比过了，但「行号随主干腐烂」这一维本轮无判据 |
| AC1-6 | 权重 | 权重序列与「权重和」声明都按该树实际值算 |
| AC1-7 | 机检文案 | `tools/verify/check_*.py` 里硬编码的键面称呼必须与该树键面同源（注释不算声明；`def self_test(` 之后的反例夹具不算声明） |
| AC1-8 | 口径腐烂 | 文档承诺「不嵌源码摘录」的**那一节**里不许回潮摘录 |
| AC2-1 | `<queries>` | `android.view.InputMethod` intent 必须在清单里 —— 缺陷 D（Android 11+ 包可见性裁掉 `enabledInputMethodList`）的根因门禁 |
| AC2-2/2-3 | 同源与截断 | 「共 N 个」与列表行同源；不允许 `take/drop/slice/filter` 截断 |
| AC2-4 | 滚动阈值 | 从面板源码符号链（`makeMeasureSpec` → 构造参数 → `dp(SYM)`）算出可视行数，据此把「≥6 条需滚动」标为**条件用例**。行距按取证设备实测（`DEVICE_DPI=440`、均值 `118.7px`）推得，换密度的设备报数必须写明「按 440dpi 取证设备」 |
| AC2-5/2-6 | 取列表 | `enabledInputMethodList` 存在且未被过滤 |
| AC2-8/2-9 | 分类计数 | 解析 `ImeListVisibility.kt` 的判据并移植到 Python 跑向量：被框架裁掉的项 ≠ 系统里就不存在 |
| AC2-D | 真机取证 | 行数 == `|ENABLED|`、「共 N 个」== `|ENABLED|`；行按 `InputMethodPanel.createRowView()` 写的 `contentDescription`（输入法名，当前项带「（当前）」）认，源码里没有 `ime-row:` / `ime_row` 这类标识。**「每个 id 都有行」只 `SKIP`** —— 面板行没有组件级 id 可对账，需产品侧加稳定标识后才能判。**仅在 `--exclusive-device` 时执行**，否则 `SKIP` |
| AC3-S / AC3-1 | 切走 | `S`：服务侧调了 `setInputMethod`。`1`：点行前后 `settings get secure default_input_method` 真的变了 —— 只有设备上取到才算 |
| AC4-S / AC4-1 | 失败兜底 | `S`：失败 → `showInputMethodPicker` → 「系统不允许切换输入法」链条齐备。`1`：切换真失败时选择器或文案真出现了；两者都没有 = `FAIL`（静默失败）。判据会先摘掉面板自带的「打开系统输入法选择器」按钮，否则面板一开着就永远算兜底成功 |
| AC5-S1..3 / AC5-1..3 | 空态 | `S`：空态文案与「系统选择器」入口在源码里存在。`1`：`|ENABLED| = 1` 时显示「没有其他输入法」；`2`：读不到列表时的文案（无外部注入手段，常态 `SKIP`）；`3`：dump 里那颗选择器按钮 `clickable="true"` |
| AC6-1 / AC6-2 | E 类（EMUI·鸿蒙拦截） | `1`：`ro.build.hw_emui_api_level` / `ro.build.version.emui` / manufacturer·brand 命中；不命中只 `SKIP` 并写明需要 EMUI 真机。`2`：命中后看切换是否被拦截 + 兜底是否出现，被拦且无提示 = `FAIL`。非独占设备一律 `SKIP`；E 类真机栈归 VOCA-228 第 5 项 |
| PRE-0/1/2 | 被验对象署名 | 安装包字节的 sha256 与声称摘要、与该树 `dist/SHA256SUMS` 条目三者对齐；对不上就不许按这份清单谈结论 |
| PRE-3 | 目录输入署名 | `--tree` 指目录时，前缀里的 id 必须能追溯到该目录自身字节（或它自己的 checkout tree），不得是运行仓库 HEAD |

输出除 `PASS/FAIL/WARN` 外还有 `SKIP`：`SKIP` **不计入通过**，也不许在汇报里写成绿。
运行时判定在没有独占取证设备时**只能是 `SKIP`** —— 「代码里有这段字符串」不是验收判定。

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
