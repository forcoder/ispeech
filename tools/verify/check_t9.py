#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_t9.py -- 九宫格 T9 / 颜文字 / 更多符号 静态校验

没有 JDK / Android SDK / 网络，无法编译运行 Kotlin，所以这里改用
「解析 Kotlin 源码 + 在 Python 里复刻同一段逻辑」的方式做离线断言：

  1. T9 字母映射：T9_DIGIT_LETTERS 每键字母集合（2ABC 3DEF ... 9WXYZ）、
     26 个字母恰好各属一个数字键、0/1/*/# 不参与编码
  2. T9 数字串展开：用 Python 复刻 t9ToLetterPatterns，断言 "23" -> 9 组、顺序与实现一致
  3. 键盘栅格：DIGIT_GRID 覆盖 0-9 + `*` `#`（12 键、无重复）；数字页 4 行 x 4 键，
     含 2..9 编码键 / `1` 标点页入口 / `0` 空格 / `*` `#` 功能位 / 退格 / 回车；
     标点页含 `1` 回跳、退格、空格、回车，标点字符 >= 24
  4. 颜文字：7 类（开心/无奈/生气/惊讶/害羞/动物/鼓励）每类 >= 12 条，全局无重复
  5. 更多符号：6 类（数学/箭头/货币/单位/希腊/线框）每类 >= 12 个，全局无重复，
     且不与 KeyboardLayouts / EmojiData 已有字面量重复
  6. 声明冲突：每个新文件内声明名不重复，且不与 KeyDef / KeyboardLayouts / EmojiData 顶层类型撞名
  7. KeyDef 字段对齐：T9Layouts 里 KeyDef.char() / .punct() / .fn() / 构造调用的具名参数
     必须 ⊆ 真实声明参数（能抓出 checked / enabled 这类臆造字段），action 必须给出
  8. 枚举合法性：T9Layouts 用到的 KeyAction / KeyStyle / KeyboardLayoutKind 值必须已声明

输出：每条检查一行 `PASS` / `FAIL` / `WARN` 前缀；末尾 `SUMMARY: passed=N failed=M warned=K`。
任一 FAIL 时退出码为 1，否则为 0。不依赖 cwd（仓库根 = Path(__file__).resolve().parents[2]）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KEYBOARD_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "input" / "keyboard"

KEYDEF_KT = KEYBOARD_DIR / "KeyDef.kt"
LAYOUTS_KT = KEYBOARD_DIR / "KeyboardLayouts.kt"
EMOJI_KT = KEYBOARD_DIR / "EmojiData.kt"
T9_KT = KEYBOARD_DIR / "T9Layouts.kt"
KAOMOJI_KT = KEYBOARD_DIR / "KaomojiData.kt"
SOFT_KEYBOARD_KT = (
    ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "input" / "SoftKeyboardView.kt"
)

_passed = 0
_failed = 0
_warned = 0

# 期望值写死，不复用被测源码，避免自证
EXPECTED_T9_LETTERS = {
    "2": "ABC", "3": "DEF", "4": "GHI", "5": "JKL",
    "6": "MNO", "7": "PQRS", "8": "TUV", "9": "WXYZ",
}
EXPECTED_DIGIT_GRID = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"]
EXPECTED_KAOMOJI_CATEGORIES = ["开心", "无奈", "生气", "惊讶", "害羞", "动物", "鼓励"]
EXPECTED_SYMBOL_CATEGORIES = ["数学", "箭头", "货币", "单位", "希腊", "线框"]

# 接线基线：KeyAction 只允许 SWITCH_IME（切换系统输入法）与 VOICE（系统语音输入）这两个新增，其余一律不许变
EXPECTED_KEY_ACTIONS = [
    "CHAR", "PUNCTUATE", "SHIFT", "BACKSPACE", "SPACE", "ENTER", "TAB",
    "MOVE_LEFT", "MOVE_RIGHT", "MODE_SWITCH", "LAYOUT", "PAGE_PREV", "PAGE_NEXT",
    "PANEL_QUICK_PHRASE", "PANEL_CLIPBOARD", "AI", "SETTINGS", "HIDE_KEYBOARD", "CLEAR",
    "SELECT_ALL", "COPY", "PASTE", "UNDO_COMMIT", "TOGGLE_PUNCT", "TOGGLE_FULL_WIDTH",
    "SWITCH_IME", "VOICE",
]

EXPECTED_LAYOUT_KINDS = ["LETTER", "NUMBER", "SYMBOL", "EMOJI", "T9"]

# 工具栏最终键序（8 键：中英切换已改由字母页 Shift 键承担，新增语音键 🎤）
EXPECTED_TOOLBAR_LABELS = ["短语", "AI", "输", "中/英", "符", "☺", "剪贴", "语音", "⚙"]


def report(level: str, message: str) -> None:
    global _passed, _failed, _warned
    print(f"{level}: {message}")
    if level == "PASS":
        _passed += 1
    elif level == "FAIL":
        _failed += 1
    elif level == "WARN":
        _warned += 1


def check(ok: bool, message: str) -> None:
    report("PASS" if ok else "FAIL", message)


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


# ═════════════════════════════════════════════════════════════
# 通用：字符串感知的 Kotlin 源码扫描
# ═════════════════════════════════════════════════════════════

def strip_comments(src: str) -> str:
    """去掉 // 与 /* */ 注释（保留字符串字面量），用空格保持长度。"""
    out: list[str] = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            out.append(c)
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\" and i + 1 < n:
                    out.append(src[i])
                    out.append(src[i + 1])
                    i += 2
                    continue
                out.append(src[i])
                i += 1
            if i < n:
                out.append('"')
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            depth = 0
            while i < n:
                if src.startswith("/*", i):
                    depth += 1
                    out.append("  ")
                    i += 2
                    continue
                if src.startswith("*/", i):
                    depth -= 1
                    out.append("  ")
                    i += 2
                    if depth == 0:
                        break
                    continue
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def find_matching(src: str, open_idx: int, open_ch: str = "(", close_ch: str = ")") -> int:
    """返回 src[open_idx]（open_ch）对应的 close_ch 下标；找不到返回 -1。"""
    i, depth, n = open_idx, 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == "\\" else 1
            i += 1
            continue
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def body_after(src: str, pattern: str) -> str:
    """取匹配 pattern 的圆括号体内容（pattern 结尾必须是 `(`）。"""
    m = re.search(pattern, src)
    if not m:
        return ""
    close_idx = find_matching(src, m.end() - 1)
    return "" if close_idx < 0 else src[m.end():close_idx]


def split_args(arg_text: str) -> list[str]:
    """按顶层逗号切分实参 / 形参（尊重字符串与嵌套括号）。"""
    args: list[str] = []
    buf: list[str] = []
    depth = 0
    i, n = 0, len(arg_text)
    while i < n:
        c = arg_text[i]
        if c == '"':
            buf.append(c)
            i += 1
            while i < n and arg_text[i] != '"':
                if arg_text[i] == "\\" and i + 1 < n:
                    buf.append(arg_text[i])
                    buf.append(arg_text[i + 1])
                    i += 2
                    continue
                buf.append(arg_text[i])
                i += 1
            if i < n:
                buf.append('"')
                i += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(buf).strip())
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        args.append(tail)
    return args


def string_literals(src: str) -> list[str]:
    return re.findall(r'"((?:[^"\\]|\\.)*)"', strip_comments(src))


def enum_values(src: str, enum_name: str) -> list[str]:
    m = re.search(r"enum class " + enum_name + r"[^{]*\{", src)
    if not m:
        return []
    end = find_matching(src, m.end() - 1, "{", "}")
    body = src[m.end():end] if end > 0 else ""
    return re.findall(r"(?m)^\s*([A-Z][A-Z0-9_]*)\s*[,(]", body)


# ═════════════════════════════════════════════════════════════
# 1-3. T9 键位与映射
# ═════════════════════════════════════════════════════════════

def parse_t9_letters(t9_src: str) -> dict[str, str]:
    body = body_after(
        t9_src,
        r"val T9_DIGIT_LETTERS\s*:\s*Map<Char,\s*String>\s*=\s*mapOf\(",
    )
    return {d: letters for d, letters in re.findall(r"'(\d)'\s*to\s*\"([A-Za-z]+)\"", body)}


def parse_digit_grid(t9_src: str) -> list[str]:
    m = re.search(r"val DIGIT_GRID\s*:\s*List<String>\s*=\s*listOf\(([^)]*)\)", t9_src)
    return re.findall(r'"([^"]*)"', m.group(1)) if m else []


def check_t9_mapping(t9_src: str) -> None:
    letters = parse_t9_letters(t9_src)
    check(
        letters == EXPECTED_T9_LETTERS,
        f"T9 字母映射：解析 T9_DIGIT_LETTERS -> {letters}（期望 {EXPECTED_T9_LETTERS}）",
    )

    flat = "".join(letters.values())
    dup = sorted({c for c in flat if flat.count(c) > 1})
    check(not dup, f"T9 字母唯一性：26 个字母各属一个数字键（重复={dup or '无'}）")
    check(
        sorted(flat) == [chr(c) for c in range(ord("A"), ord("Z") + 1)],
        f"T9 字母覆盖 A-Z：实际 {len(flat)} 个字母 = {flat}",
    )
    check(
        not ({"0", "1", "*", "#"} & set(letters)),
        f"T9 非编码键不参与字母映射：键集合={sorted(letters)}",
    )
    check(
        re.search(r"fun t9Letters\(digit: Char\): String\s*=\s*T9_DIGIT_LETTERS\[digit\]", t9_src)
        is not None,
        "t9Letters(digit) 存在，且直接查 T9_DIGIT_LETTERS 表",
    )

    def expand(digits: str) -> list[str]:  # 复刻 t9ToLetterPatterns（纯展开版）
        clean = [d for d in digits if d in "23456789"]
        if not clean:
            return []
        patterns = [""]
        for d in clean:
            nth = letters.get(d, "")
            patterns = [p + c.lower() for p in patterns for c in nth]
        return patterns

    got = expand("23")
    expected = ["ad", "ae", "af", "bd", "be", "bf", "cd", "ce", "cf"]
    check(got == expected, f'T9 展开语义："23" -> {got}（期望 {expected}）')
    check(expand("2") == ["a", "b", "c"], f'T9 展开语义："2" -> {expand("2")}（期望 [a b c]）')
    check(
        expand("79") == [p + q for p in "pqrs" for q in "wxyz"],
        f'T9 展开语义："79" 共 {len(expand("79"))} 组（期望 16 组 = PQRS x WXYZ）',
    )
    check(
        expand("0") == [] and expand("1") == [] and expand("") == [] and expand("*") == [],
        'T9 展开语义："0"/"1"/"*"/空串 -> []（0 空格、1 标点页、* 功能位都不参与编码）',
    )
    check("filter { it in '2'..'9' }" in t9_src, "t9ToLetterPatterns 只取 2-9（filter { it in '2'..'9' }）")
    check("lowercaseChar()" in t9_src, "t9ToLetterPatterns 输出小写（lowercaseChar()）")
    cap = re.search(r"const val MAX_LETTER_PATTERNS\s*=\s*(\d+)", t9_src)
    ok_cap = cap is not None and 128 <= int(cap.group(1)) < 4 ** 9
    check(
        ok_cap,
        f"T9 展开上限保护：MAX_LETTER_PATTERNS={cap.group(1) if cap else '缺失'}"
        f"（9 位数字 4^9={4 ** 9} 组，必须截断）",
    )
    check(
        re.search(
            r"fun t9ToLetterPatterns\(digits: String, pinyinDictLookup: T9LetterLookup\)",
            t9_src,
        ) is not None,
        "存在 数字串 + 词库查询 重载：t9ToLetterPatterns(digits, pinyinDictLookup)",
    )
    check("fun interface T9LetterLookup" in t9_src, "存在 fun interface T9LetterLookup（服务侧可直接传 lambda）")


def row_blocks(page_src: str) -> list[str]:
    """把页面函数体按顶层 row( 调用切成若干行块。"""
    blocks = []
    for m in re.finditer(r"(?m)^[ ]{8,}row\(", page_src):
        close_idx = find_matching(page_src, m.end() - 1)
        if close_idx > 0:
            blocks.append(page_src[m.end():close_idx])
    return blocks


def count_keys(block: str) -> int:
    return len(re.findall(
        r"\b(?:KeyDef\.(?:char|punct|fn)|digitKey|backspaceKey|enterKey|spaceKey|t9SwitchKey"
        r"|letterSwitchKey|numberSwitchKey)\s*\(",
        block,
    ))


def check_t9_grid(t9_src: str) -> None:
    grid = parse_digit_grid(t9_src)
    check(grid == EXPECTED_DIGIT_GRID, f"DIGIT_GRID 栅格：{grid}（期望 {EXPECTED_DIGIT_GRID}）")
    check(
        len(set(grid)) == 12 and set("0123456789") <= set(grid),
        f"DIGIT_GRID 覆盖 0-9 + * # 且无重复：共 {len(set(grid))} 个键 {grid}",
    )

    digit_page = body_after(t9_src, r"fun digitPage\(\)\s*:\s*KeyboardPage\s*=\s*KeyboardPage\(")
    if not digit_page:
        report("FAIL", "找不到 digitPage()（数字页）")
        return

    codes = re.findall(r'digitKey\("(\d)"\)', digit_page)
    check(codes == ["2", "3", "4", "5", "6", "7", "8", "9"], f"数字页编码键 2..9 各一个：{codes}")
    check(
        re.search(r'KeyDef\.fn\(KeyAction\.PAGE_NEXT,\s*"1"', digit_page) is not None,
        "数字页左侧「1」= PAGE_NEXT（标点页入口）",
    )
    check(
        re.search(r'KeyDef\.fn\(KeyAction\.SPACE,\s*"0"', digit_page) is not None,
        "数字页「0」= SPACE（九宫格下 0 就是空格）",
    )
    check(
        digit_page.count('label = "*"') == 1 and digit_page.count('label = "#"') == 1,
        "数字页含 `*` / `#` 功能位",
    )
    check(
        digit_page.count("KeyboardLayoutKind.SYMBOL") == 2,
        "`*` / `#` 都切 SYMBOL 布局（`#` 额外带 pageOffset 指向更多符号页）",
    )
    check(
        "KeyboardLayoutKind.EMOJI" in digit_page and "kaomojiFirstPage()" in digit_page,
        "数字页「☺」切 EMOJI 布局的颜文字首页（kaomojiFirstPage()）",
    )
    check(
        digit_page.count("backspaceKey()") == 1 and digit_page.count("enterKey()") == 1,
        "数字页含退格与回车 / 发送（backspaceKey() / enterKey()）",
    )
    check("KeyAction.MODE_SWITCH" in digit_page, "数字页含中英切换键")

    sizes = [count_keys(b) for b in row_blocks(digit_page)]
    check(sizes == [4, 4, 4, 4], f"数字页 4 行 x 4 键：实际每行键数={sizes}")

    punct_page = body_after(t9_src, r"fun punctuationPage\(\)\s*:\s*KeyboardPage\s*=\s*KeyboardPage\(")
    if not punct_page:
        report("FAIL", "找不到 punctuationPage()（标点页）")
        return
    punct_chars = re.findall(r'KeyDef\.char\("([^"]*)"\)', punct_page)
    check(len(punct_chars) >= 24, f"标点页字符数={len(punct_chars)}（期望 >= 24）")
    check(
        len(set(punct_chars)) == len(punct_chars),
        f"标点页字符无重复：{len(punct_chars)} 个字符 / {len(set(punct_chars))} 个唯一",
    )
    check("KeyAction.PAGE_NEXT" in punct_page, "标点页「1」= PAGE_NEXT 循环回数字页")
    check(
        punct_page.count("backspaceKey()") == 1
        and punct_page.count("enterKey()") == 1
        and punct_page.count('KeyDef.fn(KeyAction.SPACE, "空格"') == 1,
        "标点页含退格 / 空格 / 回车",
    )

    # pages() 返回两页，但 KeyboardLayouts.pages() 的 when 分支现在有 5 个（含 T9），
    # 所以检查点改成：T9Layouts.pages() 本身返回 [digitPage, punctuationPage]
    check(
        re.search(
            r"fun pages\(\)\s*:\s*List<KeyboardPage>\s*=\s*listOf\(digitPage\(\),\s*punctuationPage\(\)\)",
            t9_src,
        ) is not None,
        "T9Layouts.pages() = [digitPage, punctuationPage]（页码与 DIGIT_PAGE_INDEX / PUNCT_PAGE_INDEX 一致）",
    )
    check(
        "fun moreSymbolPages()" in t9_src and "fun kaomojiPages()" in t9_src,
        "提供 moreSymbolPages() / kaomojiPages() 两组可追加页面",
    )
    check(
        'KeyboardPage(title = "颜·" + category.name' in t9_src,
        "颜文字页标题加「颜·」前缀（避开表情布局已有的「动物」页）",
    )


# ═════════════════════════════════════════════════════════════
# 4-5. 颜文字 / 更多符号数据
# ═════════════════════════════════════════════════════════════

def parse_categories(src: str, data_class: str) -> list[tuple[str, list[str]]]:
    out: list[tuple[str, list[str]]] = []
    pat = re.compile(
        data_class + r"\(\s*name = \"([^\"]+)\",\s*symbols = listOf\((.*?)\),\s*\)",
        re.S,
    )
    for m in pat.finditer(src):
        out.append((m.group(1), re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(2))))
    return out


def check_data() -> None:
    kao_src = strip_comments(read(KAOMOJI_KT))
    kaomoji = parse_categories(kao_src, "KaomojiCategory")
    symbols = parse_categories(kao_src, "SymbolCategory")

    names = [n for n, _ in kaomoji]
    check(names == EXPECTED_KAOMOJI_CATEGORIES, f"颜文字分类：{names}（期望 {EXPECTED_KAOMOJI_CATEGORIES}）")
    counts = {n: len(items) for n, items in kaomoji}
    check(all(c >= 12 for c in counts.values()), f"颜文字每类 >= 12 条：{counts}")
    kao_all = [s for _, items in kaomoji for s in items]
    check(
        len(set(kao_all)) == len(kao_all),
        f"颜文字全局无重复：{len(kao_all)} 条 / {len(set(kao_all))} 条唯一",
    )
    inner_dup = {
        n: sorted({s for s in items if items.count(s) > 1}) for n, items in kaomoji
    }
    bad_inner = {n: v for n, v in inner_dup.items() if v}
    check(not bad_inner, f"颜文字分类内无重复：{bad_inner or '无'}")

    sym_names = [n for n, _ in symbols]
    check(sym_names == EXPECTED_SYMBOL_CATEGORIES, f"更多符号分类：{sym_names}（期望 {EXPECTED_SYMBOL_CATEGORIES}）")
    sym_counts = {n: len(items) for n, items in symbols}
    check(all(c >= 12 for c in sym_counts.values()), f"更多符号每类 >= 12 个：{sym_counts}")
    sym_all = [s for _, items in symbols for s in items]
    check(
        len(set(sym_all)) == len(sym_all),
        f"更多符号全局无重复：{len(sym_all)} 个 / {len(set(sym_all))} 个唯一",
    )
    check(all(len(s) == 1 for s in sym_all), "更多符号每项都是单个字符（可直接做成键面）")

    emoji_lits = set(string_literals(read(EMOJI_KT)))
    layout_lits = set(string_literals(read(LAYOUTS_KT)))

    clash_emoji_sym = sorted(set(sym_all) & emoji_lits)
    clash_emoji_kao = sorted(set(kao_all) & emoji_lits)
    clash_layout_sym = sorted(set(sym_all) & layout_lits)
    clash_layout_kao = sorted(set(kao_all) & layout_lits)

    check(not clash_emoji_sym, f"更多符号 与 EmojiData 字面量无重复：{clash_emoji_sym or '无重复'}")
    check(not clash_layout_sym, f"更多符号 与 KeyboardLayouts 字面量无重复：{clash_layout_sym or '无重复'}")
    check(not clash_emoji_kao, f"颜文字 与 EmojiData 字面量无重复：{clash_emoji_kao or '无重复'}")
    if clash_layout_kao:
        report("WARN", f"颜文字 与 KeyboardLayouts 字面量重复：{clash_layout_kao}")

    title_clash = sorted(set(names + sym_names) & (emoji_lits | layout_lits))
    if title_clash:
        report(
            "WARN",
            f"分类名与既有页面同名（已知、已规避）：{title_clash}"
            f" —— 颜文字页标题统一加「颜·」前缀，所以页码条不会出现两个「{title_clash[0]}」页"
            f"（见 docs/t9-and-symbols.md）",
        )
    else:
        report("PASS", "页面标题不与既有页面撞名：无冲突")

    kao_titles = sorted(set(sym_names) | {f"颜·{n}" for n in names})
    check(
        not (set(kao_titles) & (emoji_lits | layout_lits)),
        f"拼接后的真实页标题（含「颜·」前缀）与既有页面不撞名：{kao_titles}",
    )


# ═════════════════════════════════════════════════════════════
# 6. 声明名冲突
# ═════════════════════════════════════════════════════════════

DECL_RE = re.compile(
    r"(?m)^ {0,4}(?:private |internal |public |const |@JvmStatic )*"
    r"(fun interface|data class|val|var|fun|class|object|interface)\s+([A-Za-z_]\w*)"
    r"(\s*\(([^()]*)\))?"
)

TYPE_RE = re.compile(
    r"(?m)^ {0,4}(?:data class|fun interface|class|object|interface)\s+([A-Za-z_]\w*)"
)


def strip_ctor_param_lists(src: str) -> str:
    """抹掉 `class / data class Foo(...)` 的主构造参数（那里的 val 不是独立声明）。"""
    clean = strip_comments(src)
    out = clean
    for m in re.finditer(r"(?:data )?class\s+[A-Za-z_]\w*\s*\(", clean):
        close_idx = find_matching(clean, m.end() - 1)
        if close_idx < 0:
            continue
        out = out[:m.end()] + " " * (close_idx - m.end()) + out[close_idx:]
    return out


def declarations(src: str) -> list[tuple[str, str, int]]:
    """声明列表 (kind, name, arity)；arity 只对 fun 有意义，其余为 -1。"""
    src = strip_ctor_param_lists(src)
    out: list[tuple[str, str, int]] = []
    for m in re.finditer(DECL_RE, src):
        kind, name, arglist = m.group(1), m.group(2), m.group(4)
        arity = len(split_args(arglist)) if (kind == "fun" and arglist is not None) else -1
        out.append((kind, name, arity))
    return out


def check_declarations() -> None:
    for path in (T9_KT, KAOMOJI_KT):
        if not path.exists():
            report("FAIL", f"缺少文件 {rel(path)}")
            return
        decls = declarations(read(path))
        labels = [
            f"{kind} {name}/{arity}" if arity >= 0 else f"{kind} {name}"
            for kind, name, arity in decls
        ]
        dups = sorted({d for d in labels if labels.count(d) > 1})
        check(
            not dups,
            f"{path.name} 内声明名无重复（同名且同签名）：共 {len(decls)} 个声明，冲突={dups or '无'}",
        )
        overloads = sorted({
            name for kind, name, arity in decls
            if kind == "fun" and sum(1 for k, n, _ in decls if k == "fun" and n == name) > 1
        })
        if overloads:
            report("PASS", f"{path.name} 的合法重载（同名不同签名）：{overloads}")

    existing_src = strip_comments(read(KEYDEF_KT)) + strip_comments(read(LAYOUTS_KT)) \
        + strip_comments(read(EMOJI_KT))
    existing_types = set(TYPE_RE.findall(existing_src))
    new_types = set(TYPE_RE.findall(strip_comments(read(T9_KT)))) \
        | set(TYPE_RE.findall(strip_comments(read(KAOMOJI_KT))))
    type_clash = sorted(new_types & existing_types)
    check(
        not type_clash,
        f"新文件类型声明不与既有文件撞名：新增类型={sorted(new_types)}，冲突={type_clash or '无'}",
    )


# ═════════════════════════════════════════════════════════════
# 7-8. KeyDef 字段对齐 / 枚举合法性
# ═════════════════════════════════════════════════════════════

def check_field_alignment() -> None:
    keydef_src = strip_comments(read(KEYDEF_KT))
    t9_src = strip_comments(read(T9_KT))

    ctor_params = re.findall(r"val (\w+):", body_after(keydef_src, r"data class KeyDef\("))
    char_params = re.findall(r"(\w+):\s*[A-Za-z]", body_after(keydef_src, r"fun char\("))
    punct_params = re.findall(r"(\w+):\s*[A-Za-z]", body_after(keydef_src, r"fun punct\("))
    fn_params = re.findall(r"(\w+):\s*[A-Za-z]", body_after(keydef_src, r"fun fn\("))

    check(bool(ctor_params), f"KeyDef 构造参数（源码解析）：{ctor_params}")
    check(
        bool(char_params) and bool(punct_params) and bool(fn_params),
        f"KeyDef 快捷构造参数：char={char_params} punct={punct_params} fn={fn_params}",
    )

    bad: list[str] = []
    calls = 0

    def audit(call_name: str, params: list[str], pattern: str, require_action: bool) -> None:
        nonlocal calls
        for m in re.finditer(pattern, t9_src):
            close_idx = find_matching(t9_src, m.end() - 1)
            if close_idx < 0:
                bad.append(f"{call_name} 括号不闭合")
                continue
            args = split_args(t9_src[m.end():close_idx])
            calls += 1
            named, positional = set(), 0
            for a in args:
                nm = re.match(r"^\s*(\w+)\s*=(?!=)", a)
                if nm:
                    named.add(nm.group(1))
                else:
                    positional += 1
            unknown = sorted(named - set(params))
            if unknown:
                bad.append(f"{call_name} 出现未声明字段 {unknown}（合法字段 {params}）")
            elif len(args) > len(params):
                bad.append(f"{call_name} 实参 {len(args)} 个 > 形参 {len(params)} 个")
            if require_action and "action" not in named and positional == 0:
                bad.append(f"{call_name} 缺少必填参数 action")

    audit("KeyDef(...)", ctor_params, r"KeyDef\(", True)
    audit("KeyDef.char(...)", char_params, r"KeyDef\.char\(", False)
    audit("KeyDef.punct(...)", punct_params, r"KeyDef\.punct\(", False)
    audit("KeyDef.fn(...)", fn_params, r"KeyDef\.fn\(", True)

    check(not bad, f"KeyDef 字段对齐：审计 {calls} 处键位构造，问题={bad or '无'}")
    check(
        "pageOffset" in t9_src and "kaomojiFirstPage()" in t9_src,
        "T9 键盘用 KeyDef.pageOffset 跳页（SoftKeyboardView 已支持 setLayout(kind, page)，"
        "服务侧仍需把 key.pageOffset 透传下去）",
    )

    for enum_name, path in (("KeyAction", KEYDEF_KT), ("KeyStyle", KEYDEF_KT),
                            ("KeyboardLayoutKind", KEYDEF_KT)):
        declared = set(enum_values(strip_comments(read(path)), enum_name))
        used = set(re.findall(enum_name + r"\.([A-Z][A-Z0-9_]*)", t9_src))
        unknown = sorted(used - declared)
        check(
            bool(declared) and not unknown,
            f"{enum_name} 使用值 ⊆ 声明值：used={sorted(used)} 未声明={unknown or '无'}",
        )


# ═════════════════════════════════════════════════════════════
# 9. 接线（KeyDef 枚举 / KeyboardLayouts 分派 / SoftKeyboardView 工具栏）
# ═════════════════════════════════════════════════════════════

def check_wiring() -> None:
    keydef = strip_comments(read(KEYDEF_KT))
    layouts = strip_comments(read(LAYOUTS_KT))
    view = strip_comments(read(SOFT_KEYBOARD_KT)) if SOFT_KEYBOARD_KT.exists() else ""

    actions = enum_values(keydef, "KeyAction")
    extra = sorted(set(actions) - set(EXPECTED_KEY_ACTIONS))
    check(
        actions == EXPECTED_KEY_ACTIONS,
        f"KeyAction 未新增枚举值：实际 {len(actions)} 个 = 基线 {len(EXPECTED_KEY_ACTIONS)} 个"
        f"（新增={extra or '无'}，缺失={sorted(set(EXPECTED_KEY_ACTIONS) - set(actions)) or '无'}）",
    )

    kinds = enum_values(keydef, "KeyboardLayoutKind")
    check(kinds == EXPECTED_LAYOUT_KINDS, f"KeyboardLayoutKind 五值：{kinds}（期望 {EXPECTED_LAYOUT_KINDS}）")
    check('T9("九宫格")' in keydef, 'KeyboardLayoutKind.T9 的 label 是「九宫格」：T9("九宫格")')

    branches = re.findall(r"KeyboardLayoutKind\.([A-Z][A-Z0-9_]*)\s*->", layouts)
    check(
        branches == EXPECTED_LAYOUT_KINDS,
        f"KeyboardLayouts.pages() 的 when 覆盖 5 个布局种类：{branches}",
    )
    check(
        "KeyboardLayoutKind.T9 -> T9Layouts.pages()" in layouts,
        "KeyboardLayouts.pages() 的 T9 分支返回 T9Layouts.pages()",
    )
    check(
        re.search(r"fun symbols\(\)\s*:\s*List<KeyboardPage>\s*=\s*listOf\(", layouts) is not None
        and ") + T9Layouts.moreSymbolPages()" in layouts,
        "KeyboardLayouts.symbols() 末尾追加 T9Layouts.moreSymbolPages()",
    )
    check(
        re.search(r"fun emoji\(\)\s*:\s*List<KeyboardPage>\s*=\s*EmojiData\.categories\.map", layouts) is not None
        and "} + T9Layouts.kaomojiPages()" in layouts,
        "KeyboardLayouts.emoji() 末尾追加 T9Layouts.kaomojiPages()",
    )

    # 跳页下标必须按「总页数 - 本组页数」算，否则追加后会指错页
    t9_src = strip_comments(read(T9_KT))
    check(
        re.search(
            r"fun moreSymbolFirstPage\(\): Int\s*=\s*"
            r"\(KeyboardLayouts\.symbols\(\)\.size - moreSymbolPages\(\)\.size\)\.coerceAtLeast\(0\)",
            t9_src,
        ) is not None,
        "moreSymbolFirstPage() = 符号总页数 - 更多符号页数（追加后仍指向首页）",
    )
    check(
        re.search(
            r"fun kaomojiFirstPage\(\): Int\s*=\s*"
            r"\(KeyboardLayouts\.emoji\(\)\.size - kaomojiPages\(\)\.size\)\.coerceAtLeast\(0\)",
            t9_src,
        ) is not None,
        "kaomojiFirstPage() = 表情总页数 - 颜文字页数（追加后仍指向首页）",
    )
    # 符号页已按搜狗面板改成 6 个分类页（常用/中文/英文/网络/数学/角标），
    # 标题由 symbolPage("符号N", ...) 传入，所以按这个调用形态计数。
    sym_literal = len(re.findall(r'symbolPage\("符号', layouts))
    # 注意：不能用 "EmojiCategory(" 计数，会把 data class EmojiCategory( 也算进去
    emoji_cats = len(parse_categories(read(EMOJI_KT), "EmojiCategory"))
    kao_cats = len(parse_categories(read(KAOMOJI_KT), "KaomojiCategory"))
    check(
        sym_literal == 6 and emoji_cats == 8 and kao_cats == 7,
        f"页码算术原料：符号页 = {sym_literal} 页（搜狗 6 分类），EmojiData 分类 = {emoji_cats} 个，颜文字分类 = {kao_cats} 个",
    )
    check(
        sym_literal == 6 and emoji_cats == 8,
        f"跳页下标实际值：moreSymbolFirstPage() = {sym_literal}（切到追加的首个符号页），"
        f"kaomojiFirstPage() = {emoji_cats}（切到「颜·开心」页）",
    )

    if not view:
        report("FAIL", f"缺少 {rel(SOFT_KEYBOARD_KT)}")
        return

    check(
        re.search(r"fun setLayout\(kind: KeyboardLayoutKind, page: Int = 0\)", view) is not None,
        "SoftKeyboardView.setLayout(kind, page: Int = 0)：支持 pageOffset，默认值对旧调用点不变",
    )
    toolbar = body_after(view, r"private fun toolbarKeys\(\)\s*:\s*List<KeyDef>\s*=\s*listOf\(")
    check(bool(toolbar), "找到 toolbarKeys()")
    count = toolbar.count("KeyDef.fn(")
    check(count == 9, f"工具栏共 9 个按键（实际 {count}）")
    labels = re.findall(r'"([^"]+)"', toolbar)
    check(labels == EXPECTED_TOOLBAR_LABELS, f"工具栏键序：{labels}（期望 {EXPECTED_TOOLBAR_LABELS}）")
    check(
        'KeyAction.PANEL_QUICK_PHRASE' in toolbar.split("KeyDef.fn(", 1)[1].split("KeyDef.fn(")[0],
        "工具栏第 1 键 = KeyAction.PANEL_QUICK_PHRASE（快捷短语）",
    )
    switch_ime = [seg for seg in toolbar.split("KeyDef.fn(") if "KeyAction.SWITCH_IME" in seg]
    check(
        bool(switch_ime) and '"输"' in switch_ime[0] and "imeSwitchLabel()" not in switch_ime[0],
        "第 3 键 = 切换系统输入法，键面固定「输」",
    )
    status_key = [
        seg for seg in toolbar.split("KeyDef.fn(")
        if "KeyAction.MODE_SWITCH" in seg
    ]
    check(
        bool(status_key) and "statusLabel = true" in status_key[0],
        "第 4 键 = 输入态状态键（statusLabel = true，键面走 imeSwitchLabel）",
    )
    check(
        "effectiveKey.statusLabel" in view and "imeSwitchLabel()" in view,
        "createKey 对 statusLabel 键渲染 imeSwitchLabel()（拼 / EN / 五）",
    )
    keydef = read(ROOT / "app/src/main/java/com/ispeech/input/keyboard/KeyDef.kt")
    fn_body = keydef.split("fun fn(", 1)[1].split("): KeyDef = KeyDef(", 1)[1]
    check(
        "statusLabel = statusLabel" in fn_body,
        "KeyDef.fn() 把 statusLabel 透传给 KeyDef（漏传会退回「中/英」）",
    )
    check(
        '"9键"' not in toolbar and "KeyboardLayoutKind.T9" not in toolbar,
        "九宫格入口不在工具栏（已移到数字页底行）",
    )
    check(
        '"颜"' not in toolbar and "kaomojiFirstPage" not in view,
        "没有独立「颜」工具栏键（颜文字跟在表情页末尾，靠页码条翻）",
    )
    check(
        "T9Layouts" not in view,
        "SoftKeyboardView 不再引用 T9Layouts（九宫格入口交接给 KeyboardLayouts）",
    )
    check(
        "minWidthPx = dpInt(TOOLBAR_KEY_MIN_WIDTH_DP)" in view
        and "const val TOOLBAR_KEY_MIN_WIDTH_DP = 40" in view,
        "工具栏单键最小宽度 40dp（TOOLBAR_KEY_MIN_WIDTH_DP = 40）",
    )
    check(
        "HorizontalScrollView(context)" in view
        and "isFillViewport = true" in view
        and "isHorizontalScrollBarEnabled = false" in view,
        "工具栏外层 HorizontalScrollView(isFillViewport = true)：放不下就横滑，不换行不加第二行",
    )
    weights = [float(w) for w in re.findall(r"weight = ([0-9.]+)f", toolbar)]
    check(len(weights) == 9, f"工具栏 9 键权重：{weights}（合计 {round(sum(weights), 2)}）")
    check("const val TOOLBAR_TEXT_SIZE = 12f" in view, "工具栏字号 12f（TOOLBAR_TEXT_SIZE = 12f）")

    # 九宫格入口：数字页（123）底行
    numbers_page = body_after(layouts, r"fun numbers\(\)\s*:\s*KeyboardPage\s*=\s*KeyboardPage\(")
    check(bool(numbers_page), "找到 KeyboardLayouts.numbers()")
    check("t9SwitchKey()" in numbers_page, "数字页（123）底行含「9键」入口：t9SwitchKey()")
    check("digitKeys()" in numbers_page, "数字页第 1 行 10 个数字键未动（digitKeys()）")
    check(
        all(name in numbers_page for name in ("letterSwitchKey()", "spaceKey()", "enterKey()")),
        "数字页底行仍保留 ABC / 空格 / ↵（只插入 9键，没替换任何键）",
    )
    check(
        re.search(
            r'private fun t9SwitchKey\(\): KeyDef\s*=\s*'
            r'KeyDef\.fn\(KeyAction\.LAYOUT, "9键", 1\.5f, KeyStyle\.SPECIAL, KeyboardLayoutKind\.T9\)',
            layouts,
        ) is not None,
        't9SwitchKey() = KeyDef.fn(KeyAction.LAYOUT, "9键", 1.5f, KeyStyle.SPECIAL, KeyboardLayoutKind.T9)',
    )
    digit_order = re.search(r"val DIGIT_ORDER: List<String> = listOf\(([^)]*)\)", layouts)
    n_digits = len(re.findall(r'"[^"]*"', digit_order.group(1))) if digit_order else 0
    check(n_digits == 10, f"数字页数字键仍是 10 个（DIGIT_ORDER 共 {n_digits} 个）")
    sizes = [count_keys(b) for b in row_blocks(numbers_page)]
    check(sizes == [9, 7, 4], f"数字页 2-4 行键数：{sizes}（期望 [9, 7, 4]，底行新增 9键）")


# ═════════════════════════════════════════════════════════════

def main() -> int:
    print(f"== check_t9.py == 仓库根：{ROOT}")

    check(T9_KT.exists(), f"交付文件存在：{rel(T9_KT)}")
    check(KAOMOJI_KT.exists(), f"交付文件存在：{rel(KAOMOJI_KT)}")
    if not (T9_KT.exists() and KAOMOJI_KT.exists()):
        print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
        return 1

    t9_src = strip_comments(read(T9_KT))
    check_t9_mapping(t9_src)
    check_t9_grid(t9_src)
    check_data()
    check_declarations()
    check_field_alignment()
    check_wiring()

    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
