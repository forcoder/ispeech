#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_keyboard_ui.py —— VOCA-30 键面规格静态断言

为什么要有这个文件：本单的键面规格 11-10 合进过 main（`0a53ab5`），11-14 又被 `07214d6`
用一条陈旧分支的 `SoftKeyboardView.kt` 整体覆盖（字母回到 21sp、键间距回到 4dp、圆角回到
10dp、字母页补位常量直接消失）；11-14 那轮挂着「校验断言回归」名字的恢复只改了引用点
（`setMargins(dpInt(keyMargin))`），没改取值，所以回退至今没人拦住。这里把**被覆盖的那几个
取值**写成断言，取值出处是 `docs/keyboard-ui.md` §2/§3/§4（同机搜狗五笔实测），不是新定的视觉。

检查项（全部纯文本解析，无需 JDK / SDK / 网络）：

  1. 尺寸常量 `keyMargin` / `keyRadius`
  2. 字母页栅格 `LETTER_ROW_UNITS`
  3. `keyTextSize` 非 T9 分支的各档字号
  4. 字母页底行权重序列（符 / 空格 / 中·英 / 换行）
  5. 「换行」键 = 浅蓝渐变键面 + 矢量 ↵ 图标着色，而不是纯色块 + 白色粗体

任一 FAIL 时退出码为 1。每条检查一行 `PASS` / `FAIL` / `WARN`，末尾 `SUMMARY: ...`。
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOFTKB = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "input" / "SoftKeyboardView.kt"
LAYOUTS = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "input" / "keyboard" / "KeyboardLayouts.kt"
IC_ENTER = ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_enter.xml"

# docs/keyboard-ui.md §2/§3/§4 的实测规格（改这些值得同步改文档）
EXPECTED_MARGIN_DP = 3
EXPECTED_RADIUS_DP = 6
EXPECTED_LETTER_ROW_UNITS = 10.0
EXPECTED_LETTER_SP = 18.0          # 搜狗字母字形高 48–53px ≈ 18sp；旧版 23sp/21sp 过大
EXPECTED_LETTER_LONG_SP = 15.0     # 长文案字母键（weight > 1.4f）
EXPECTED_SPACE_SP = 13.0
EXPECTED_FN_SP = 15.0
EXPECTED_PUNCT_SP = 16.0
EXPECTED_BOTTOM_WEIGHTS = [
    ("符", 1.6),
    ("123", 1.5),
    ("，", 1.0),
    ("空格", 3.3),
    ("。", 1.0),
    ("中/英", 1.45),
    ("换行", 1.8),
]

_passed = 0
_failed = 0
_warned = 0


def report(level: str, message: str) -> None:
    global _passed, _failed, _warned
    print(f"{level}: {message}")
    if level == "PASS":
        _passed += 1
    elif level == "FAIL":
        _failed += 1
    elif level == "WARN":
        _warned += 1


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def read(path: Path) -> str | None:
    if not path.is_file():
        report("FAIL", f"缺少 {rel(path)}：无法核对 VOCA-30 键面规格")
        return None
    return path.read_text(encoding="utf-8", errors="replace")


def strip_noise(text: str) -> str:
    """把字符串字面量与注释替换成等长空白，只保留代码结构（用于括号配对）。"""
    out = []
    state = "code"
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if ch == "/" and nxt == "/":
                state = "line"
                out.append("  ")
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block"
                out.append("  ")
                i += 2
                continue
            if ch == '"':
                state = "triple" if text.startswith('"""', i) else "str"
                out.append('"""' if state == "triple" else " ")
                i += 3 if state == "triple" else 1
                continue
            if ch == "'":
                state = "char"
                out.append(" ")
                i += 1
                continue
            out.append(ch)
            i += 1
        elif state == "line":
            if ch == "\n":
                state = "code"
                out.append(ch)
            else:
                out.append(" ")
            i += 1
        elif state == "block":
            if ch == "*" and nxt == "/":
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append(ch if ch == "\n" else " ")
            i += 1
        elif state in ("str", "char"):
            if ch == "\\":
                out.append("  ")
                i += 2
                continue
            if ch == ('"' if state == "str" else "'"):
                state = "code"
                out.append(" ")
                i += 1
                continue
            out.append(ch if ch == "\n" else " ")
            i += 1
        else:  # triple
            if text.startswith('"""', i):
                state = "code"
                out.append('"""')
                i += 3
                continue
            out.append(ch if ch == "\n" else " ")
            i += 1
    return "".join(out)


def brace_block(text: str, start: int) -> str | None:
    """返回从 `start`（应指向某个 `{`）开始的配对括号内文本（不含最外层花括号）。"""
    clean = strip_noise(text)
    if start >= len(clean) or clean[start] != "{":
        return None
    depth = 0
    for i in range(start, len(clean)):
        if clean[i] == "{":
            depth += 1
        elif clean[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1 : i]
    return None


def fun_body(text: str, signature: str) -> str | None:
    """取签名 `signature` 起、到下一个函数声明前的源码片段。

    按缩进/声明边界切，而不是按花括号配对：`letterPage` 是表达式体
    （`= KeyboardPage(...)`，函数体里没有 `{`），配对法对它必然失败。
    """
    idx = text.find(signature)
    if idx < 0:
        return None
    nxt = re.compile(r"^\s*(?:private |internal |protected |public )*(?:suspend )?fun \w+", re.M)
    m = nxt.search(text, idx + len(signature))
    return text[idx : m.start()] if m else text[idx:]


def check_size_constants() -> None:
    text = read(SOFTKB)
    if text is None:
        return
    for name, expected in (("keyMargin", EXPECTED_MARGIN_DP), ("keyRadius", EXPECTED_RADIUS_DP)):
        m = re.search(rf"private val {name}\s*=\s*([0-9]+)", text)
        if not m:
            report("FAIL", f"{rel(SOFTKB)} 里没解析到 `private val {name}`：写法变了？VOCA-30 规格无法核对")
            continue
        actual = int(m.group(1))
        where = line_of(text, m.start())
        if actual == expected:
            report("PASS", f"{name} = {actual}dp（VOCA-30 规格 {expected}dp）:{where}")
        else:
            report(
                "FAIL",
                f"{name} = {actual}dp，VOCA-30 规格要求 {expected}dp（{rel(SOFTKB)}:{where}）"
                f" —— {actual} 是 `07214d6` 覆盖回去的旧值，见 docs/keyboard-ui.md §2",
            )


def line_of(text: str, idx: int) -> int:
    return text.count("\n", 0, idx) + 1


def check_letter_row_units() -> None:
    text = read(SOFTKB)
    if text is None:
        return
    m = re.search(r"const val LETTER_ROW_UNITS\s*=\s*([0-9]+(?:\.[0-9]+)?)f", text)
    if not m:
        report(
            "FAIL",
            f"{rel(SOFTKB)} 里没有 `const val LETTER_ROW_UNITS`：字母页第 2 行的补位居中已丢失"
            f"（`07214d6` 覆盖时把这个常量一起删了），见 docs/keyboard-ui.md §5",
        )
        return
    actual = float(m.group(1))
    where = line_of(text, m.start())
    if actual == EXPECTED_LETTER_ROW_UNITS:
        report("PASS", f"LETTER_ROW_UNITS = {m.group(1)}f（字母页栅格 {EXPECTED_LETTER_ROW_UNITS:g} 单位，第 2 行补位靠它对齐）:{where}")
    else:
        report(
            "FAIL",
            f"LETTER_ROW_UNITS = {m.group(1)}f，应为 {EXPECTED_LETTER_ROW_UNITS:g}f"
            f"（{rel(SOFTKB)}:{where}）—— 第 1 行是 10 个字母键，栅格单位不等于 10 时第 2 行补位算错",
        )


def check_key_text_size() -> None:
    text = read(SOFTKB)
    if text is None:
        return
    body = fun_body(text, "private fun keyTextSize(")
    if body is None:
        report("FAIL", f"{rel(SOFTKB)} 里没解析到 `private fun keyTextSize(`：VOCA-30 字号档位无法核对")
        return

    # T9 分支是函数里第一个 `when (key.style)`，非 T9 是最后一个；本单规格针对非 T9。
    arms = list(re.finditer(r"when \(key\.style\)\s*\{", body))
    if len(arms) < 2:
        report("FAIL", f"keyTextSize 里只找到 {len(arms)} 个 `when (key.style)`，"
                       f"预期 T9 / 非 T9 各一个：无法定位非 T9 字号分支")
        return
    block = brace_block(body, arms[-1].end() - 1)
    if block is None:
        report("FAIL", "非 T9 分支的 `when (key.style) {` 括号不配对：无法解析字号档位")
        return

    for style, expected, why in (
        (r"KeyStyle\.SPACE\s*->\s*([0-9]+(?:\.[0-9]+)?)f", EXPECTED_SPACE_SP, "空格键文案"),
        (r"KeyStyle\.SPECIAL,[^\n]*?->\s*([0-9]+(?:\.[0-9]+)?)f", EXPECTED_FN_SP, "功能键（Shift/123/中·英/退格）"),
        (r"KeyStyle\.PUNCT\s*->\s*if \([^)]*\)\s*[0-9]+(?:\.[0-9]+)?f else\s*([0-9]+(?:\.[0-9]+)?)f",
         EXPECTED_PUNCT_SP, "底行标点（符号页另有 20sp）"),
    ):
        m = re.search(style, block)
        label = "键面字号"
        if not m:
            report("FAIL", f"非 T9 分支里没解析到 {why} 的字号（{rel(SOFTKB)}）：写法变了？")
            continue
        actual = float(m.group(1))
        if actual == expected:
            report("PASS", f"{label} {why} = {trim_float(actual)}sp")
        else:
            report(
                "FAIL",
                f"{label} {why} = {trim_float(actual)}sp，VOCA-30 规格要求 {trim_float(expected)}sp"
                f"（{rel(SOFTKB)}:{line_of(text, text.find('private fun keyTextSize('))} 起的非 T9 分支）"
                f"，见 docs/keyboard-ui.md §3",
            )

    # 字母键：`KeyStyle.LETTER -> when { weight > 1.4f -> 15f; else -> 18f }`
    letter = re.search(r"KeyStyle\.LETTER\s*->\s*when\s*\{", block)
    if not letter:
        report("FAIL", f"非 T9 分支里没找到 `KeyStyle.LETTER -> when {{`：字母键字号档位无法核对"
                       f"（可能被改回单一 `-> 21f`，那正是 `07214d6` 的覆盖写法）")
        return
    arm = brace_block(block, letter.end() - 1)
    if arm is None:
        report("FAIL", "字母键的 `when {{` 括号不配对：无法解析字母字号档位")
        return
    for pattern, expected, why in (
        (r"else\s*->\s*([0-9]+(?:\.[0-9]+)?)f", EXPECTED_LETTER_SP, "字母键"),
        (r"weight\s*>\s*1\.4f\s*->\s*([0-9]+(?:\.[0-9]+)?)f", EXPECTED_LETTER_LONG_SP, "长文案字母键"),
    ):
        m = re.search(pattern, arm)
        if not m:
            report("FAIL", f"字母键分支里没解析到 {why} 的字号（`{pattern}`）：写法变了？")
            continue
        actual = float(m.group(1))
        if actual == expected:
            report("PASS", f"{why} = {trim_float(actual)}sp")
        else:
            report(
                "FAIL",
                f"{why} = {trim_float(actual)}sp，VOCA-30 规格要求 {trim_float(expected)}sp"
                f"（{rel(SOFTKB)}）—— {trim_float(actual)} 是覆盖后的旧值，"
                f"搜狗同机实测字形高 48–53px ≈ {trim_float(expected)}sp，见 docs/keyboard-ui.md §2/§3",
            )


def trim_float(v: float) -> str:
    return f"{v:g}"


def check_letter_bottom_row() -> None:
    text = read(LAYOUTS)
    if text is None:
        return
    body = fun_body(text, "private fun letterPage(")
    if body is None:
        report("FAIL", f"{rel(LAYOUTS)} 里没解析到 `private fun letterPage(`：字母页底行权重无法核对")
        return
    lists = list(re.finditer(r"listOf\(", body))
    if not lists:
        report("FAIL", "letterPage 里没找到 `listOf(`：底行布局不存在？")
        return
    # 底行是最后一个 `listOf(...)`，按圆括号配对取内容。
    start = lists[-1].end() - 1
    depth = 0
    end = -1
    clean = strip_noise(body)
    for i in range(start, len(clean)):
        if clean[i] == "(":
            depth += 1
        elif clean[i] == ")":
            depth -= 1
            if depth == 0:
                end = i
                break
    if end < 0:
        report("FAIL", "字母页底行 `listOf(` 括号不配对：无法解析权重")
        return
    bottom = body[start + 1 : end]

    entries = [
        (r"symbolSwitchKey\(\)", "符"),
        (r"numberSwitchKey\(\)", "123"),
        (r"spaceKey\(([0-9.]+)f?\)", "空格"),
        (r'KeyAction\.MODE_SWITCH,\s*"中/英",\s*([0-9.]+)f', "中/英"),
        (r"enterKey\(([0-9.]+)f?\)", "换行"),
    ]
    positions = []
    actual: dict[str, float] = {}
    for pattern, label in entries:
        m = re.search(pattern, bottom)
        if not m:
            report("FAIL", f"字母页底行缺少 `{label}` 键（{rel(LAYOUTS)} 的 letterPage 底行）："
                           f"VOCA-30 按搜狗实测重排过这一行")
            continue
        positions.append((m.start(), label))
        if m.lastindex:
            actual[label] = float(m.group(1))

    expected_map = dict(EXPECTED_BOTTOM_WEIGHTS)
    for _, label in positions:
        if label not in actual:
            continue  # 符 / 123 的权重写在各键构造函数里，单独断言
        want = expected_map.get(label)
        got = actual[label]
        if want is None:
            continue
        if got == want:
            report("PASS", f"字母页底行 `{label}` weight = {trim_float(got)}")
        else:
            report(
                "FAIL",
                f"字母页底行 `{label}` weight = {trim_float(got)}，VOCA-30 规格要求 {trim_float(want)}"
                f"（{rel(LAYOUTS)}）—— 底行按键宽度比例来自搜狗五笔实测，见 docs/keyboard-ui.md §2",
            )

    # 顺序：符 / 123 / ，/ 空格 / 。/ 中·英 / 换行 —— 中·英 必须仍在换行左侧
    order = [label for _, label in positions]
    if "中/英" in order and "换行" in order and order.index("中/英") > order.index("换行"):
        report("FAIL", "字母页底行顺序不对：`中/英` 排到了 `换行` 右侧（docs/keyboard-ui.md §2）")
    elif len(positions) == len(entries):
        report("PASS", f"字母页底行顺序保持 {order}")

    for pattern, label, expected in (
        (r'KeyDef\.fn\(KeyAction\.LAYOUT,\s*"符",\s*([0-9.]+)f', "符", 1.6),
        (r'KeyDef\.fn\(KeyAction\.LAYOUT,\s*"123",\s*([0-9.]+)f', "123", 1.5),
    ):
        m = re.search(pattern, text)
        if not m:
            report("FAIL", f"{rel(LAYOUTS)} 里没解析到 `{label}` 键的权重定义")
            continue
        got = float(m.group(1))
        if got == expected:
            report("PASS", f"`{label}` 键权重定义 = {trim_float(got)}")
        else:
            report("FAIL", f"`{label}` 键权重定义 = {trim_float(got)}，应为 {trim_float(expected)}"
                           f"（docs/keyboard-ui.md §2）")


def check_enter_key() -> None:
    text = read(SOFTKB)
    if text is None:
        return

    if not IC_ENTER.is_file():
        report("FAIL", f"缺少 {rel(IC_ENTER)}：「换行」键回到纯色块 + 白色粗体文字（VOCA-30 11-15 打回项）")
    else:
        try:
            root = ET.parse(IC_ENTER).getroot()
        except ET.ParseError as exc:
            report("FAIL", f"{rel(IC_ENTER)} 解析失败 -> {exc}")
            root = None
        if root is not None:
            tag = root.tag.rsplit("}", 1)[-1]
            paths = root.findall("path")
            if tag != "vector" or not paths:
                report("FAIL", f"{rel(IC_ENTER)} 不是含 <path> 的 <vector>（实际根元素 <{tag}>，{len(paths)} 个 path）")
            else:
                report("PASS", f"ic_enter.xml 为 {len(paths)} 条路径的矢量图标（↵ 用字形渲染会随 ROM 粗细不一）")

    if not re.search(r"setImageResource\(R\.drawable\.ic_enter\)", text):
        report("FAIL", "buildEnterKey 没有 `setImageResource(R.drawable.ic_enter)`：换行键图标未接上")
    else:
        report("PASS", "「换行」键引用 R.drawable.ic_enter")

    if not re.search(r"imageTintList\s*=\s*ColorStateList\.valueOf\(enterAccent\)", text):
        report("FAIL", "「换行」键图标未用 enterAccent 着色（docs/keyboard-ui.md §4 要求主色 #2E5FD8）")
    else:
        report("PASS", "「换行」键图标按 enterAccent 统一着色")

    sel = fun_body(text, "private fun enterKeySelector(")
    if sel is None:
        report("FAIL", f"{rel(SOFTKB)} 里没解析到 `private fun enterKeySelector(`：换行键面退回单色块")
    elif "enterTop" not in sel or "enterBottom" not in sel:
        report("FAIL", "enterKeySelector 未使用 enterTop/enterBottom 渐变（docs/keyboard-ui.md §4 的浅蓝胶囊键面）")
    else:
        report("PASS", "「换行」键面为 enterTop→enterBottom 渐变胶囊（非纯色块）")

    build = fun_body(text, "private fun buildEnterKey(")
    if build is not None and "background = enterKeySelector()" not in build:
        report("FAIL", "buildEnterKey 未把键面设为 enterKeySelector()")
    elif build is None:
        report("WARN", f"{rel(SOFTKB)} 里没解析到 `private fun buildEnterKey(`，跳过键面接线检查")
    else:
        report("PASS", "buildEnterKey 键面接的是 enterKeySelector()")


def main() -> int:
    print(f"== check_keyboard_ui.py == 仓库根：{ROOT}")
    check_size_constants()
    check_letter_row_units()
    check_key_text_size()
    check_letter_bottom_row()
    check_enter_key()
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
