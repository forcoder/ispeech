# 键盘界面规格（对齐搜狗五笔默认皮肤）

本文记录 26 键键盘的键面尺寸 / 配色 / 换行键设计，以及这些数值的来源。
所有数字都是**同一台真机上的像素实测**，不是估算：改键盘外观时先对着这张表量，别凭感觉调。

## 1. 取证环境

| 项 | 值 |
| --- | --- |
| 设备 | 华为 ALN-AL00（FMR0224913042917），1260 × 2720 px，density 520dpi（≈3.25x），fontScale 1.15 |
| 对照 | 搜狗输入法 Android 版（`com.sohu.inputmethod.sogou`，默认皮肤） |
| 取样 | `adb exec-out screencap -p` 截图 → PIL 逐像素找键位带 / 字形 bbox / 填充色 |
| 复现 | `scratch` 下的远程构建脚本装机后，在系统备忘录新建页截图（见 `docs/v1.5-verify-log.md`） |

`sp → px` 换算：`px = sp × 1.15 × 3.25`（本机）；`dp → px`：`px = dp × 3.25`。

## 2. 改前 / 改后 / 搜狗对照

| 项 | 搜狗实测 | 改前 | 改后 |
| --- | --- | --- | --- |
| 字母键字形高 | 48–53 px | 63–77 px | **49–50 px** |
| 字母字号 | ≈18 sp | 23 sp | **18 sp** |
| 键面右上角提示字号 | 21 px 高（≈8 sp） | 35 px（11 sp） | **22 px（8 sp）** |
| 功能键底色 | `#C6C9D2` | `#CDD1D8` | **`#C9CDD5`** |
| 键间距 | 6–7 px | 8 px（4 dp 边距） | **6 px（3 dp 边距）** |
| 键帽圆角 | ≈12 px | ≈30 px（10 dp） | **≈12 px（6 dp）** |
| 键盘底色 | 纵向渐变 `#F2F3F6` → `#D6D8E0` | 纯色 `#F2F3F5` | **纵向渐变 `#F4F5F8` → `#E3E6EC`** |
| 回车键 | 灰底 `#C6C9D2` + 99 × 48 px 箭头 | 纯蓝 `#4C7DF0` + 120 × 61 px 白字 | **浅蓝渐变胶囊 + 34 × 28 px 箭头 + 79 × 37 px 小字** |

字母页底行（`符 / 123 / ，/ 空格 / 。/ 中英 / 换行`）按搜狗实测宽度比例定权重，
改后实测 155 / 145 / 96 / 317 / 97 / 141 / 175 px，搜狗 169 / 134 / 102 / 309 / 102 / 134 / 168 px。

## 3. 字号（`SoftKeyboardView.keyTextSize`）

```
26 键布局（LETTER / NUMBER / SYMBOL 页）
  LETTER  18sp（weight > 1.4f 的长文案键 15sp）
  PUNCT   16sp
  SPECIAL / ACCENT / PRIMARY / DANGER  15sp
  SPACE   13sp
九宫格布局（T9）沿用旧字号，不跟着 26 键一起收：
  LETTER  23sp（weight > 1.4f 的颜文字 18sp）
  PUNCT   20sp / SPECIAL 18sp / SPACE 16sp
```

- **为什么是 18sp**：搜狗字母字形 48–53 px ≈ 18 sp；23 sp 在 98 px 宽的键里只剩 2.5 dp 侧边，
  字几乎顶满键面，这是「字母太大」的直接来源。
- **九宫格为什么不动**：T9 键宽是 26 键字母键的 2–3 倍，同样字号不会显得拥挤；一起收反而显空。
- 长按预览浮层（`showPreview`）与备选条字号同步降一档（22 → 20 / 20 → 18），否则预览比键面字还大。

## 4. 「换行」键

`SoftKeyboardView.buildEnterKey` 单独渲染，两种形态：

| 形态 | 用在 | 键面 |
| --- | --- | --- |
| 文案键 | 26 键字母页 / 数字页（`KeyDef` label = `换行`） | `↵` 图标 15 dp + 「换行」小字 11 sp |
| 字形键 | 九宫格 / 标点 / 颜文字底行（label = `↵`） | 只有 `↵` 图标 24 dp |

底色（`enterKeySelector`）：纵向渐变 `#EFF4FF → #E1EAFE`、1 dp 描边 `#C5D7F8`、按下 `#E4EDFF → #D3E1FA`；
主色 `#2E5FD8` 只用在图标 / 文字 / 描边上。

- 旧版是整块 `#4C7DF0` + 18 sp 粗体白字：134 px 宽的键里字面占 120 px（左右各 2.5 dp），
  且这是键盘上唯一的深色大色块，比字母键还抢眼。
- 搜狗的回车键其实是**灰键 + 大箭头**（无文字）。完全照搬会丢掉「换行」这两个字，
  所以保留小字标签、把主色收进描边和图标：既一眼能认出是主操作，又不压过字母键。
- 九宫格页如果也画「图标 + 文字」，会出现两个箭头（label 本身就是 `↵`），
  且 `↵` 当文字渲染在 300 px 宽的键里只有 26 px，比相邻的 `⌫`（66 × 46）`☺`（72 × 67）小一大截。

## 5. 其他一起调整的细节

- **字母页第 2 行居中**：`asdfghjkl` 只有 9 键，旧版每键比上下行宽 8 px、整行左对齐，行间错位；
  现在左右各补半个空位（`LETTER_ROW_UNITS = 10f`），三行按同一栅格对齐（搜狗同款）。
- **底行功能键底色**：旧版按 action 白名单猜「是不是工具栏键」，把底行的 `符`（LAYOUT→SYMBOL）
  和 `中/英`（MODE_SWITCH）误画成透明；现在由 `createKey(inToolbar)` 显式传参，只有真工具栏键才是透明底。
- **工具栏按下反馈**：工具栏键无实体键底，补了一层 `#E1E5EC` 的按下态（之前只有选中态）。
- **空格键文案**：搜狗空格键无文字；这里保留「空格」但用弱化灰 `#7C838D` + 13 sp。

## 6. 改动落点

| 文件 | 内容 |
| --- | --- |
| `app/src/main/java/com/ispeech/input/SoftKeyboardView.kt` | 调色板 / 尺寸常量、`keyTextSize`、`buildRow` 补位、`createKey(inToolbar)`、`buildEnterKey`、`enterKeySelector` |
| `app/src/main/java/com/ispeech/input/keyboard/KeyboardLayouts.kt` | 字母页底行权重（`符` / 空格 / `中/英` / `换行`） |
| `app/src/main/res/drawable/ic_enter.xml` | 回车箭头（矢量，运行时着色） |

改完跑 `python3 tools/verify/check_*.py`（纯静态解析源码），再按 `README` 的远端构建流程装机目检。