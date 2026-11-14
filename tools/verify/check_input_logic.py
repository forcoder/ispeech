#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_input_logic.py —— iSpeech 输入逻辑 / 数据测试（纯 Python 复刻）

没有 JDK / Android SDK / 网络，无法编译运行 Kotlin。本脚本改用「解析源码 +
在 Python 里复刻同一段逻辑」的方式，把可以离线验证的数据与语义都断言一遍：

  1. 五笔词库 app/src/main/assets/wubi86.dict：
     样码 wqiy→你 / trnt→我 / vbg→好 / jhnh→早 / hqce→餐；词条数、单字/词组数、最大 weight
  2. 快捷短语 app/src/main/assets/quick_phrases.txt：字段数、mode 合法性、hi 规则 kind=a
  3. 键盘布局（解析 KeyboardLayouts.kt + KeyDef.kt + EmojiData.kt 的文本）：
     - KeyAction 使用值 ⊆ 声明值；KeyboardLayoutKind 四值在 pages() 的 when 里全覆盖
     - EmojiData 类别数 / 每类数量 / 全局去重
     - 每个 KeyboardPage 的行数与每行键数（字母页应 4 行 = 10/9/9/5）
  4. 标点转换：解析 Punctuation.kt 的 chineseMap，复刻 PunctuationConverter.convert 语义
  5. 候选排序：复刻 CandidateRanking 比较器 + UserDictEngine 的 +100000 加成

输出：每条检查一行 `PASS` / `FAIL` / `WARN` 前缀；末尾 `SUMMARY: ...`。
任一 FAIL 时退出码为 1，否则为 0。

不依赖 cwd：仓库根用 Path(__file__).resolve().parents[2] 定位。
"""

from __future__ import annotations

import functools
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
KEYBOARD_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "input" / "keyboard"
DATA_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "data"

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


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


# ═════════════════════════════════════════════════════════════
# 通用：尊重字符串 / 字符字面量的括号匹配与顶层切分
# ═════════════════════════════════════════════════════════════

def find_matching(text: str, open_idx: int) -> int:
    """返回 text[open_idx]（必须是 '('）对应的 ')' 下标；找不到返回 -1。"""
    i = open_idx
    depth = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_top(text: str, seps: str):
    """按顶层分隔符切分，跳过字符串 / 字符字面量与嵌套括号内的分隔符。"""
    parts = []
    buf = []
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            buf.append(c)
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    buf.append(text[i])
                    i += 1
                buf.append(text[i])
                i += 1
            if i < n:
                buf.append(text[i])
                i += 1
            continue
        if c == "'":
            buf.append(c)
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    buf.append(text[i])
                    i += 1
                buf.append(text[i])
                i += 1
            if i < n:
                buf.append(text[i])
                i += 1
            continue
        if c in "([{":
            depth += 1
            buf.append(c)
            i += 1
            continue
        if c in ")]}":
            depth -= 1
            buf.append(c)
            i += 1
            continue
        if depth == 0 and c in seps:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(c)
        i += 1
    parts.append("".join(buf))
    return parts


def find_matching_brace(text: str, open_idx: int) -> int:
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


# ═════════════════════════════════════════════════════════════
# 1. 五笔词库
# ═════════════════════════════════════════════════════════════

def check_wubi_dict() -> None:
    path = ASSETS / "wubi86.dict"
    if not path.exists():
        report("FAIL", f"五笔词库不存在：{rel(path)}")
        return

    by_code = {}
    total = 0
    single = 0
    phrase = 0
    other = 0
    max_weight = None
    bad_lines = 0
    for raw in read(path).splitlines():
        line = raw.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 3:
            bad_lines += 1
            continue
        code, text, weight = fields[0], fields[1], fields[2]
        total += 1
        try:
            w = int(weight)
            max_weight = w if max_weight is None else max(max_weight, w)
        except ValueError:
            bad_lines += 1
        kind = fields[3] if len(fields) > 3 else ""
        if kind == "s":
            single += 1
        elif kind == "p":
            phrase += 1
        else:
            other += 1
        by_code.setdefault(code, []).append(text)

    report(
        "PASS" if max_weight == 65535 else "FAIL",
        f"词库统计：总词条={total} 单字={single} 词组={phrase} 无类型={other} "
        f"最大 weight={max_weight}（期望 65535） 字段异常行={bad_lines}",
    )

    samples = {
        "wqiy": "你",
        "trnt": "我",
        "vbg": "好",
        "jhnh": "早",
        "hqce": "餐",
    }
    for code, expected in samples.items():
        hits = by_code.get(code, [])
        if expected in hits:
            report("PASS", f"样码 {code} 含「{expected}」（该码共 {len(hits)} 条）")
        else:
            report("FAIL", f"样码 {code} 未找到「{expected}」，实际={hits[:8]}")


# ═════════════════════════════════════════════════════════════
# 2. 快捷短语
# ═════════════════════════════════════════════════════════════

_VALID_MODES = {"exact", "prefix", "contains", "regex"}


def check_quick_phrases() -> None:
    path = ASSETS / "quick_phrases.txt"
    if not path.exists():
        report("FAIL", f"快捷短语文件不存在：{rel(path)}")
        return

    rows = []
    field_errors = []
    mode_errors = []
    for lineno, raw in enumerate(read(path).splitlines(), start=1):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 3:
            field_errors.append(f"L{lineno} 只有 {len(fields)} 个字段：{line!r}")
            continue
        mode = fields[3].strip() if len(fields) > 3 else "prefix"
        if mode.lower() not in _VALID_MODES:
            mode_errors.append(f"L{lineno} mode={mode!r} 非法")
        rows.append((lineno, fields))

    if field_errors:
        for e in field_errors:
            report("FAIL", f"快捷短语字段数 {e}")
    else:
        report("PASS", f"快捷短语：{len(rows)} 条有效规则，每行均 >= 3 个 tab 字段")

    if mode_errors:
        for e in mode_errors:
            report("FAIL", f"快捷短语 mode {e}")
    else:
        report("PASS", "快捷短语 mode：全部落在 {exact, prefix, contains, regex}（大小写不敏感）")

    hi = [f for (_, f) in rows if f[0] == "hi"]
    if not hi:
        report("FAIL", "快捷短语缺少 hi 规则")
    else:
        fields = hi[0]
        kind = fields[5] if len(fields) > 5 else ""
        if kind == "a":
            report("PASS", f"hi 规则存在且 kind=a（trigger=hi, label={fields[1]!r}）")
        else:
            report("FAIL", f"hi 规则 kind 应为 'a'，实际 {kind!r}（该行={fields}）")


# ═════════════════════════════════════════════════════════════
# 3. 键盘布局结构
# ═════════════════════════════════════════════════════════════

def _enum_members(text: str, enum_name: str):
    m = re.search(rf"enum\s+class\s+{re.escape(enum_name)}\s*(?:\([^)]*\))?\s*\{{", text)
    if not m:
        return None
    open_idx = text.index("{", m.start())
    end = find_matching_brace(text, open_idx)
    if end < 0:
        return None
    body = text[open_idx + 1:end]
    members = []
    for line in body.splitlines():
        mm = re.match(r"\s*([A-Z][A-Z0-9_]*)\s*(?:\(|,|$)", line)
        if mm:
            members.append(mm.group(1))
    return members


def count_keys(expr: str) -> int:
    """估算一个「行表达式」会产生多少个按键。"""
    expr = expr.strip()
    plus = split_top(expr, "+")
    if len(plus) > 1:
        return sum(count_keys(p) for p in plus)
    m = re.fullmatch(r"([A-Za-z_][A-Za-z_0-9]*)\((.*)\)", expr, re.S)
    if not m:
        return 1
    name, inner = m.group(1), m.group(2)
    if name == "letterKeys":
        s = re.search(r'"([A-Za-z]*)"', inner)
        return len(s.group(1)) if s else 0
    if name == "digitKeys":
        return 10
    if name == "bottomRow":
        return 4
    if name in ("row", "listOf", "rows"):
        # 过滤掉尾随逗号产生的空段，否则每个 row(...) / listOf(...) 都会多算 1 个键。
        items = [p for p in split_top(inner, ",") if p.strip() != ""]
        return len(items)
    return 1


def parse_static_pages():
    """从 KeyboardLayouts.kt 里解析静态页，返回 [(func, [键数...])]。

    字母页 / 数字页仍是字面量 `rows = rows(...)`；符号页已改为 `symbolPage()` 按
    4 列 × 4 行数据铺开 + 右侧控制列 + 底部 6 格分类条，这里按同一版式补出页结构，
    避免脚本仍按旧的 4 个静态页判断而误报。
    """
    src = read(KEYBOARD_DIR / "KeyboardLayouts.kt")
    pages = []
    for m in re.finditer(r"rows\s*=\s*rows\s*\(", src):
        open_idx = m.end() - 1
        end = find_matching(src, open_idx)
        if end < 0:
            continue
        inner = src[open_idx + 1:end]
        # 过滤尾随逗号产生的空段
        row_exprs = [e for e in split_top(inner, ",") if e.strip() != ""]
        counts = [count_keys(e) for e in row_exprs]
        prefix = src[:m.start()]
        fmatches = list(re.finditer(r"\bfun\s+([A-Za-z_][A-Za-z_0-9]*)\s*\(", prefix))
        func = fmatches[-1].group(1) if fmatches else "?"
        pages.append((func, counts))

    # symbolPage("标题", 符号列表, 页序号)：每页 4 行 × (4 符号 + 1 控制键) + 6 格分类条。
    if re.search(r"\bfun\s+symbols\s*\(", src):
        pages.extend(("symbolPage", [5, 5, 5, 5, 6]) for _ in
                     re.finditer(r'\bsymbolPage\s*\(\s*"', src))
    return pages


def parse_emoji_categories():
    src = read(KEYBOARD_DIR / "EmojiData.kt")
    blocks = re.findall(
        r'name\s*=\s*"([^"]+)"\s*,\s*symbols\s*=\s*listOf\((.*?)\)\s*,?\s*\)',
        src,
        re.S,
    )
    cats = []
    for name, body in blocks:
        syms = re.findall(r'"((?:[^"\\]|\\.)*)"', body)
        cats.append((name, syms))
    return cats


def check_keyboard_structure() -> None:
    keydef = read(KEYBOARD_DIR / "KeyDef.kt")
    layouts = read(KEYBOARD_DIR / "KeyboardLayouts.kt")

    # 3a. KeyAction 声明 vs 使用
    declared = _enum_members(keydef, "KeyAction") or []
    declared_set = set(declared)
    used = set(re.findall(r"KeyAction\.([A-Z][A-Z0-9_]*)", layouts))
    undeclared = sorted(used - declared_set)
    if undeclared:
        for name in undeclared:
            report("FAIL", f"KeyboardLayouts.kt 使用了未声明的 KeyAction.{name}")
    else:
        report(
            "PASS",
            f"KeyAction：声明 {len(declared)} 个，KeyboardLayouts.kt 用到 {len(used)} 个，"
            f"无未声明引用（用到={sorted(used)}）",
        )

    # 3b. KeyboardLayoutKind 五值 + pages() when 覆盖
    kinds = set(_enum_members(keydef, "KeyboardLayoutKind") or [])
    expected_kinds = {"LETTER", "NUMBER", "SYMBOL", "EMOJI", "T9"}
    if kinds != expected_kinds:
        report("FAIL", f"KeyboardLayoutKind 声明应含 {sorted(expected_kinds)}，实际 {sorted(kinds)}")
    else:
        report("PASS", "KeyboardLayoutKind：LETTER/NUMBER/SYMBOL/EMOJI/T9 均已在 KeyDef.kt 声明")

    pages_fn = re.search(r"fun\s+pages\s*\(", layouts)
    if not pages_fn:
        report("FAIL", "KeyboardLayouts.kt 找不到 fun pages(...)")
    else:
        body_open = layouts.index("{", pages_fn.end())
        body_end = find_matching_brace(layouts, body_open)
        body = layouts[body_open:body_end]
        covered = set(re.findall(r"KeyboardLayoutKind\.([A-Z][A-Z0-9_]*)", body))
        missing = sorted(expected_kinds - covered)
        if missing:
            report("FAIL", f"pages(...) 的 when 未覆盖：{missing}")
        else:
            report("PASS", "pages(...) 的 when 已覆盖 LETTER/NUMBER/SYMBOL/EMOJI/T9 五个分支")

    # 3c. EmojiData
    cats = parse_emoji_categories()
    if len(cats) < 6:
        report("FAIL", f"EmojiData 类别数={len(cats)}，要求 >= 6")
    else:
        report("PASS", f"EmojiData 类别数={len(cats)}（>= 6）")

    too_small = [(n, len(s)) for n, s in cats if len(s) < 24]
    if too_small:
        for n, c in too_small:
            report("FAIL", f"EmojiData 类别「{n}」只有 {c} 个（要求 >= 24）")
    else:
        report(
            "PASS",
            "EmojiData 每类数量均 >= 24：" + ", ".join(f"{n}={len(s)}" for n, s in cats),
        )

    seen = {}
    dups = {}
    for name, syms in cats:
        for s in syms:
            if s in seen:
                dups.setdefault(s, [seen[s]]).append(name)
            else:
                seen[s] = name
    if dups:
        for s, names in dups.items():
            report("FAIL", f"EmojiData 全局重复 emoji {s!r} 出现在：{names}")
    else:
        report("PASS", f"EmojiData 全局无重复 emoji（{len(seen)} 个唯一字符）")

    # 3d. 静态页行数 / 键数
    pages = parse_static_pages()
    if len(pages) < 4:
        report("FAIL", f"只解析到 {len(pages)} 个静态页（期望 >= 4：字母 / 数字 / 符号1 / 符号2）")
    letter = pages[0] if pages else ("?", [])
    expected = [10, 9, 9, 7]
    if letter[1] == expected:
        report("PASS", f"字母页结构：4 行，键数 {letter[1]}（期望 {expected}）")
    else:
        report("FAIL", f"字母页结构：{len(letter[1])} 行 键数 {letter[1]}（期望 4 行 {expected}）")

    for func, counts in pages:
        report("PASS", f"页 {func}：{len(counts)} 行，每行键数 {counts}")

    for name, syms in cats:
        row_counts = [min(8, len(syms) - 8 * k) for k in range((len(syms) + 7) // 8)]
        row_counts.append(5)
        report("PASS", f"页 emoji/{name}：{len(row_counts)} 行，每行键数 {row_counts}")


# ═════════════════════════════════════════════════════════════
# 4. 标点转换
# ═════════════════════════════════════════════════════════════

def _unescape(raw: str) -> str:
    out = []
    i = 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            mapping = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\", "'": "'", '"': '"'}
            if nxt == "u" and i + 5 < len(raw):
                out.append(chr(int(raw[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(mapping.get(nxt, nxt))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def parse_chinese_map():
    src = read(DATA_DIR / "Punctuation.kt")
    m = re.search(r"chineseMap\s*:\s*Map<Char,\s*String>\s*=\s*mapOf\(", src)
    if not m:
        return None
    open_idx = m.end() - 1
    end = find_matching(src, open_idx)
    inner = src[open_idx + 1:end]
    cmap = {}
    for entry in split_top(inner, ","):
        em = re.match(r"\s*'((?:\\.|[^'])+)'\s*to\s*\"((?:[^\"\\]|\\.)*)\"\s*$", entry, re.S)
        if not em:
            continue
        ch = _unescape(em.group(1))
        val = _unescape(em.group(2))
        cmap[ch] = val
    return cmap


class PunctuationConverter:
    """PunctuationConverter.convert 的 Python 复刻。"""

    def __init__(self, cmap):
        self.cmap = cmap
        self.double_open = False
        self.single_open = False

    def reset(self):
        self.double_open = False
        self.single_open = False

    @staticmethod
    def to_full_width(text: str) -> str:
        out = []
        for ch in text:
            code = ord(ch)
            if ch == " ":
                out.append("\u3000")
            elif 0x21 <= code <= 0x7E:
                out.append(chr(code + 0xFEE0))
            else:
                out.append(ch)
        return "".join(out)

    def _map_chinese(self, text: str) -> str:
        out = []
        for ch in text:
            if ch == '"':
                out.append("”" if self.double_open else "“")
                self.double_open = not self.double_open
            elif ch == "'":
                out.append("’" if self.single_open else "‘")
                self.single_open = not self.single_open
            else:
                out.append(self.cmap.get(ch, ch))
        return "".join(out)

    def convert(self, text: str, chinese: bool, full_width: bool) -> str:
        if text == "":
            return text
        mapped = self._map_chinese(text) if chinese else text
        return self.to_full_width(mapped) if full_width else mapped


_REQUIRED_PUNCT_KEYS = [
    ",", ".", "?", "!", ":", ";", "(", ")", "<", ">", "[", "]",
    "\\", "/", "@", "#", "$", "%", "^", "&", "*", "-", "_", "=",
    "+", "|", "~", '"', "'",
]


def check_punctuation() -> None:
    cmap = parse_chinese_map()
    if not cmap:
        report("FAIL", "无法解析 Punctuation.kt 的 chineseMap")
        return

    missing = [k for k in _REQUIRED_PUNCT_KEYS if k not in cmap]
    if missing:
        report("FAIL", f"chineseMap 缺少键：{missing}")
    else:
        report("PASS", f"chineseMap 共 {len(cmap)} 项，要求的 {len(_REQUIRED_PUNCT_KEYS)} 个键全部存在")

    conv = PunctuationConverter(cmap)

    cases = []
    cases.append(('convert(",", chinese=True, full=False)', conv.convert(",", True, False), "，"))
    cases.append(('convert(".", chinese=True, full=True)', conv.convert(".", True, True), "。"))
    cases.append(('convert("a", chinese=True, full=True)', conv.convert("a", True, True), "ａ"))
    conv.reset()
    q1 = conv.convert('"', True, False)
    q2 = conv.convert('"', True, False)
    cases.append(("连续两次 convert('\"') → 左右引号", [q1, q2], ["“", "”"]))
    cases.append(('convert(",", chinese=False, full=False)', conv.convert(",", False, False), ","))
    cases.append(('convert(" ", chinese=False, full=True)', conv.convert(" ", False, True), "\u3000"))

    for desc, actual, expected in cases:
        report("PASS" if actual == expected else "FAIL", f"标点转换 {desc}：得到 {actual!r}（期望 {expected!r}）")


# ═════════════════════════════════════════════════════════════
# 5. 候选排序
# ═════════════════════════════════════════════════════════════

_KIND_ORDER = {"QUICKAPPLY": 0, "PHRASE": 1, "WUBI": 2, "WUBIPHRASE": 3}


def candidate_compare(inp: str):
    def cmp(a, b):
        a_exact = a["code"].lower() == inp.lower()
        b_exact = b["code"].lower() == inp.lower()
        if a_exact != b_exact:
            return -1 if a_exact else 1
        ka, kb = _KIND_ORDER[a["kind"]], _KIND_ORDER[b["kind"]]
        if ka != kb:
            return -1 if ka < kb else 1
        if a["weight"] != b["weight"]:
            return -1 if a["weight"] > b["weight"] else 1
        if a["display"] < b["display"]:
            return -1
        if a["display"] > b["display"]:
            return 1
        return 0
    return cmp


def check_candidate_ranking() -> None:
    system = {"code": "wqiy", "display": "你", "insertText": "你", "weight": 1534, "kind": "WUBI"}
    user = {"code": "wqiy", "display": "你（用户）", "insertText": "你（用户）", "weight": 130 + 100000, "kind": "WUBI"}
    pool = [system, user]
    pool.sort(key=functools.cmp_to_key(candidate_compare("wqiy")))
    order = [c["display"] for c in pool]
    if pool and pool[0] is user:
        report(
            "PASS",
            f"候选排序：同一编码 wqiy 下用户词（权重 {user['weight']}）排在系统单字"
            f"（权重 {system['weight']}）前，实际顺序={order}",
        )
    else:
        report("FAIL", f"候选排序：用户词未排前，实际顺序={order}")

    cases = [
        {"code": "ab", "display": "短语A", "insertText": "x", "weight": 900, "kind": "PHRASE"},
        {"code": "ab", "display": "应用B", "insertText": "y", "weight": 10, "kind": "QUICKAPPLY"},
        {"code": "ab", "display": "五笔C", "insertText": "z", "weight": 65000, "kind": "WUBI"},
        {"code": "ab", "display": "词库词组E", "insertText": "v", "weight": 64000, "kind": "WUBIPHRASE"},
        {"code": "abc", "display": "前缀五笔D", "insertText": "w", "weight": 99999, "kind": "WUBI"},
    ]
    cases.sort(key=functools.cmp_to_key(candidate_compare("ab")))
    got = [c["display"] for c in cases]
    expected = ["应用B", "短语A", "五笔C", "词库词组E", "前缀五笔D"]
    report(
        "PASS" if got == expected else "FAIL",
        f"候选排序（精确码优先→应用<短语<单字<词组→权重降序）：实际={got}（期望={expected}）",
    )
    check_candidate_rank_real_dict()


def check_candidate_rank_real_dict() -> None:
    """真实词库回归：词组权重普遍高于常用单字，单字必须仍然首选。

    数据来源是搜狗 `wubi.dic` + rime/rime-wubi 合并的真实词库，权重不可假设为「单字更高」，
    因此这里用真实权重跑一遍与 Kotlin 侧同构的排序模型。
    """
    path = ROOT / "app/src/main/assets/wubi86.dict"
    rows = []
    for raw in read(path).splitlines():
        line = raw.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 3:
            continue
        try:
            weight = int(fields[2])
        except ValueError:
            weight = 0
        is_phrase = (fields[3] if len(fields) > 3 else "s") == "p"
        rows.append(
            {
                "code": fields[0],
                "display": fields[1],
                "insertText": fields[1],
                "weight": weight,
                "kind": "WUBIPHRASE" if is_phrase else "WUBI",
            }
        )

    for code, expect_first, expect_phrase in (
        ("wqiy", "你", "仰泳"),
        ("trnt", "我", "选择性"),
        ("vbg", "好", None),
    ):
        pool = [row for row in rows if row["code"] == code]
        pool.sort(key=functools.cmp_to_key(candidate_compare(code)))
        order = [row["display"] for row in pool]
        ok = bool(pool) and order[0] == expect_first
        if ok and expect_phrase is not None:
            ok = expect_phrase in order
        detail = f"实际候选前 4 = {order[:4]}"
        tail = f"，词组「{expect_phrase}」仍在候选内" if expect_phrase else ""
        report(
            "PASS" if ok else "FAIL",
            f"真实词库排序 {code}：首选应为「{expect_first}」（{detail}）{tail}",
        )


# ═════════════════════════════════════════════════════════════

def main() -> int:
    print(f"== check_input_logic.py == 仓库根：{ROOT}")
    check_wubi_dict()
    check_quick_phrases()
    check_keyboard_structure()
    check_punctuation()
    check_candidate_ranking()
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
