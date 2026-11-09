# tools/verify —— 静态校验与数据/逻辑测试

本目录放的是**离线自查脚本**，用于在没有 JDK / Android SDK / 网络的机器上，
对「智语 iSpeech」仓库做一轮可自动化的检查与回归。只用 Python 3 标准库，
不联网、不 `pip install`、不依赖编译。

## 运行方式

在**仓库根目录**直接运行（脚本内部用 `Path(__file__).resolve().parents[2]`
定位仓库根，因此不依赖当前工作目录）：

```bash
python3 tools/verify/check_static.py
python3 tools/verify/check_input_logic.py
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

## check_input_logic.py —— 输入逻辑 / 数据测试

| # | 检查项 | 说明 |
|---|--------|------|
| 1 | 五笔词库 | 解析 `app/src/main/assets/wubi86.dict`（`code\ttext\tweight[\ttype]`）：样码 `wqiy→你` `trnt→我` `vbg→好` `jhnh→早` `hqce→餐`；打印总词条 / 单字 / 词组 / 最大 weight（期望 65535） |
| 2 | 快捷短语 | `app/src/main/assets/quick_phrases.txt` 每行 >= 3 个 tab 字段、`mode` 合法、`hi` 规则 kind=a |
| 3 | 键盘布局 | 解析 `KeyboardLayouts.kt` + `KeyDef.kt` + `EmojiData.kt`：`KeyAction` 使用值 ⊆ 声明值；`KeyboardLayoutKind` 四值在 `pages()` 的 `when` 里全覆盖；EmojiData 类别数 >= 6、每类 >= 24、全局去重；每个 `KeyboardPage` 的行数与每行键数（字母页应为 4 行 = 10/9/9/5） |
| 4 | 标点转换 | 解析 `Punctuation.kt` 的 `chineseMap`，用 Python 复刻 `PunctuationConverter.convert`（成对引号 + 先映射后全角）并断言 6 组用例 |
| 5 | 候选排序 | 用 Python 复刻 `CandidateRanking` 比较器 + `UserDictEngine` 的 `+100000` 加成，验证同码下用户词排在系统词前，并校验「精确码优先 → 应用<短语<五笔 → 权重降序」 |

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
- 脚本**不修改任何 Kotlin / XML 源码**，只读取与报告。

## measure_ime_inset.py —— 键盘占屏高度对照测量

需要**已连接的真机 / 模拟器**（与其他脚本不同，它要跑 `adb`）。读 `dumpsys window displays`
里的 `InsetsSource type=ITYPE_IME` —— 系统合成器给出的最终窗口矩形，比截图取色可靠
（候选条与键盘底色同为 `#F2F3F5`，截图分不出边界）。

```bash
python3 tools/verify/measure_ime_inset.py --compare   # 依次切 iSpeech / 搜狗 各测一次并打印差值
python3 tools/verify/measure_ime_inset.py             # 只测当前输入法
```

前置：目标输入法已启用、屏幕上已有获得焦点的输入框且键盘已弹出。不满足时报错退出，
不出假数据。`--compare` 会自动 `ime set` 切换并校验切换是否真的生效。

## 当前基线

最近一次运行（见上层汇报）：

- `check_static.py`：全绿（`SUMMARY: passed=5 failed=0 warned=0`，退出码 0）。
- `check_input_logic.py`：除 **EmojiData 跨分类重复 emoji** 外全绿
  （`😍` / `🥰` / `😘` 同时出现在「常用」与「情感」，见
  `app/src/main/java/com/ispeech/input/keyboard/EmojiData.kt:27-28` 与 `:50`），
  因此该项 `FAIL`、脚本退出码为 1，属**预期内的真实发现**（源码问题，交由开发修复）。
