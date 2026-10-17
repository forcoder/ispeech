#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_static.py —— iSpeech 仓库静态一致性校验

在没有 JDK / Android SDK / 网络的环境里，用纯标准库做「能可靠判定」的检查：

  1. app/src/main/res/**/*.xml 全部能被 ElementTree 解析
  2. Kotlin 里引用的资源必须存在：
       R.id.*     -> 某个 layout XML 里定义了 @+id/同名
       R.layout.* -> res/layout/同名.xml
       R.string.* -> res/values*/strings.xml
       R.color.*  -> res/values*/colors.xml
       R.style.*  -> res/values*/themes.xml
     （android.R.* 前缀的框架资源不计入项目资源）
  3. layout XML 里带包名的自定义 View（<com.ispeech.xxx.Yyy>）在 Kotlin 里有同名 class
  4. Kotlin 源码括号配平（状态机处理字符串 / 原始字符串 / 字符字面量 / 注释）
  5. 出现「栈为空时的孤立 }」——即顶层多余的右花括号（一定会编译失败的手误）

输出：每条检查一行 `PASS` / `FAIL` / `WARN` 前缀；末尾 `SUMMARY: ...`。
任一 FAIL 时退出码为 1，否则为 0。

不依赖 cwd：仓库根用 Path(__file__).resolve().parents[2] 定位。
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
JAVA = ROOT / "app" / "src" / "main" / "java"

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


def kotlin_files():
    """项目 Kotlin 源文件（排除 build/ 产物）。"""
    for p in sorted(JAVA.rglob("*.kt")):
        if "build" in p.parts:
            continue
        yield p


# ─────────────────────────────────────────────────────────────
# 1. XML 解析
# ─────────────────────────────────────────────────────────────

def check_xml_parse() -> None:
    files = sorted(RES.rglob("*.xml"))
    if not files:
        report("FAIL", f"未找到任何 XML：{rel(RES)}")
        return
    failures = []
    for f in files:
        try:
            ET.parse(f)
        except ET.ParseError as exc:
            line, col = exc.position
            failures.append(f"{rel(f)}:{line}:{col} -> {exc.msg}")
        except Exception as exc:  # noqa: BLE001
            failures.append(f"{rel(f)} -> {exc!r}")
    if failures:
        for item in failures:
            report("FAIL", f"XML 解析失败 {item}")
    else:
        report("PASS", f"XML 解析：{len(files)} 个文件全部通过 ElementTree 解析")


# ─────────────────────────────────────────────────────────────
# 2. 资源引用
# ─────────────────────────────────────────────────────────────

def _value_names(path: Path, tag: str) -> set:
    if not path.exists():
        return set()
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return set()
    return set(re.findall(rf'<{tag}\s+name="([^"]+)"', text))


def collect_resource_index():
    layout_dir = RES / "layout"
    layouts = {p.stem for p in layout_dir.glob("*.xml")} if layout_dir.is_dir() else set()

    ids = set()
    for xml in RES.rglob("*.xml"):
        text = xml.read_text(encoding="utf-8", errors="replace")
        ids.update(re.findall(r"@\+id/([A-Za-z_0-9]+)", text))
        ids.update(re.findall(r"@id/([A-Za-z_0-9]+)", text))

    strings = set()
    colors = set()
    styles = set()
    if RES.is_dir():
        for d in sorted(RES.glob("values*")):
            if not d.is_dir():
                continue
            strings |= _value_names(d / "strings.xml", "string")
            colors |= _value_names(d / "colors.xml", "color")
            styles |= _value_names(d / "themes.xml", "style")

    return {"layout": layouts, "id": ids, "string": strings, "color": colors, "style": styles}


def check_resource_references() -> None:
    index = collect_resource_index()
    # 负向后顾：`android.R.id.foo` 属于框架资源，不能算项目资源。
    pattern = re.compile(r"(?<!android\.)\bR\.([A-Za-z]+)\.([A-Za-z_0-9]+)")
    checked_kinds = {"id", "layout", "string", "color", "style"}
    missing = []
    total = 0

    for kt in kotlin_files():
        text = kt.read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), start=1):
            for m in pattern.finditer(line):
                kind, name = m.group(1), m.group(2)
                if kind not in checked_kinds:
                    continue
                total += 1
                if name not in index[kind]:
                    missing.append((rel(kt), lineno, kind, name))

    if missing:
        for path, lineno, kind, name in missing:
            report("FAIL", f"资源缺失 {path}:{lineno} -> R.{kind}.{name}（在 res 中未找到）")
    else:
        report(
            "PASS",
            "资源引用："
            f"扫描 {total} 处 R.id/layout/string/color/style，全部存在"
            f"（id={len(index['id'])} layout={len(index['layout'])} "
            f"string={len(index['string'])} color={len(index['color'])} style={len(index['style'])}）",
        )


# ─────────────────────────────────────────────────────────────
# 3. 自定义 View
# ─────────────────────────────────────────────────────────────

def collect_declared_classes():
    classes = set()
    for kt in kotlin_files():
        text = kt.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(
            r"\b(?:class|interface|object|enum\s+class)\s+([A-Za-z_][A-Za-z_0-9]*)", text
        ):
            classes.add(m.group(1))
    return classes


def check_custom_views() -> None:
    declared = collect_declared_classes()
    missing = []
    checked = 0
    for xml in sorted(RES.rglob("*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except Exception:  # noqa: BLE001  （第 1 项已单独报错）
            continue
        for elem in root.iter():
            tag = elem.tag
            # ElementTree 会把带命名空间的标签写成 "{uri}local"，直接跳过。
            if not isinstance(tag, str) or tag.startswith("{"):
                continue
            # 只看形如 com.ispeech.xxx.Yyy 的完全限定类名标签。
            if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)+", tag):
                continue
            simple = tag.rsplit(".", 1)[-1]
            checked += 1
            if simple not in declared:
                missing.append((rel(xml), tag, simple))
    if missing:
        for path, tag, simple in missing:
            report("FAIL", f"自定义 View 缺失 {path} -> <{tag}> 但没有 class {simple}")
    else:
        report("PASS", f"自定义 View：layout 中 {checked} 个带包名标签均在 Kotlin 中找到同名 class")


# ─────────────────────────────────────────────────────────────
# 4. 括号配平（状态机）
# ─────────────────────────────────────────────────────────────

_PAIRS = {"(": ")", "[": "]", "{": "}"}
_CLOSERS = {")": "(", "]": "[", "}": "{"}


def scan_kotlin(text: str):
    """状态机遍历 Kotlin 源码，返回 (errors, top_level_closers)。"""
    n = len(text)
    i = 0
    line = 1
    col = 1
    state = "normal"  # normal | line_comment | block_comment | dq | raw | char
    block_depth = 0
    stack = []  # (char, line, col)
    errors = []
    top_level_closers = []

    while i < n:
        c = text[i]
        three = text[i:i + 3]

        if state == "normal":
            if three == '"""':
                state = "raw"
                i += 3
                col += 3
                continue
            if text[i:i + 2] == "//":
                state = "line_comment"
                i += 2
                col += 2
                continue
            if text[i:i + 2] == "/*":
                state = "block_comment"
                block_depth = 1
                i += 2
                col += 2
                continue
            if c == '"':
                state = "dq"
                i += 1
                col += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                col += 1
                continue
            if c in _PAIRS:
                stack.append((c, line, col))
                i += 1
                col += 1
                continue
            if c in _CLOSERS:
                if not stack:
                    top_level_closers.append((line, col))
                else:
                    opener = stack.pop()
                    if opener[0] != _CLOSERS[c]:
                        errors.append(
                            f"括号交叉：{line}:{col} 遇到 '{c}'，"
                            f"但栈顶是 '{opener[0]}'（{opener[1]}:{opener[2]}）"
                        )
                i += 1
                col += 1
                continue
            if c == "\n":
                line += 1
                col = 1
            else:
                col += 1
            i += 1
            continue

        if state == "line_comment":
            if c == "\n":
                state = "normal"
                line += 1
                col = 1
                i += 1
            else:
                i += 1
                col += 1
            continue

        if state == "block_comment":
            if text[i:i + 2] == "/*":
                block_depth += 1
                i += 2
                col += 2
                continue
            if text[i:i + 2] == "*/":
                block_depth -= 1
                i += 2
                col += 2
                if block_depth == 0:
                    state = "normal"
                continue
            if c == "\n":
                line += 1
                col = 1
                i += 1
            else:
                i += 1
                col += 1
            continue

        if state == "dq":
            if c == "\\":
                i += 2
                col += 2
                continue
            if c == '"':
                state = "normal"
                i += 1
                col += 1
                continue
            if c == "\n":
                line += 1
                col = 1
                i += 1
            else:
                i += 1
                col += 1
            continue

        if state == "raw":
            if three == '"""':
                state = "normal"
                i += 3
                col += 3
                continue
            if c == "\n":
                line += 1
                col = 1
                i += 1
            else:
                i += 1
                col += 1
            continue

        if state == "char":
            if c == "\\":
                i += 2
                col += 2
                continue
            if c == "'":
                state = "normal"
                i += 1
                col += 1
                continue
            if c == "\n":  # 未终止字符字面量，尽量恢复
                state = "normal"
                line += 1
                col = 1
                i += 1
            else:
                i += 1
                col += 1
            continue

    if state == "dq":
        errors.append("文件结束时仍有未闭合的双引号字符串")
    if state == "raw":
        errors.append('文件结束时仍有未闭合的原始字符串 """')
    if state == "char":
        errors.append("文件结束时仍有未闭合的字符字面量")
    if state == "block_comment":
        errors.append("文件结束时仍有未闭合的块注释 /*")
    if stack:
        tail = ", ".join(f"'{o[0]}'({o[1]}:{o[2]})" for o in stack[-8:])
        errors.append(f"未闭合的括号（栈底在上，共 {len(stack)} 个）：{tail}")

    return errors, top_level_closers


def check_delimiters() -> None:
    files = list(kotlin_files())
    bad = 0
    for kt in files:
        text = kt.read_text(encoding="utf-8", errors="replace")
        errors, _ = scan_kotlin(text)
        for err in errors:
            report("FAIL", f"括号配平 {rel(kt)} -> {err}")
            bad += 1
    if bad == 0:
        report("PASS", f"括号配平：{len(files)} 个 Kotlin 文件的 {{}} () [] 全部配对")


# ─────────────────────────────────────────────────────────────
# 5. 孤立 / 顶层 }
# ─────────────────────────────────────────────────────────────

def check_isolated_braces() -> None:
    """报告「栈为空时的右花括号」——顶层多余的 }，一定编译失败。

    只做可可靠判定的子集：在忽略字符串 / 注释后的状态下，若某个 `}` 出现时
    没有任何未闭合的左括号，则它必然多余。不用「缩进异常」这种会误报的启发式。
    """
    bad = 0
    for kt in kotlin_files():
        text = kt.read_text(encoding="utf-8", errors="replace")
        _, top_level = scan_kotlin(text)
        for (line, col) in top_level:
            report("FAIL", f"多余右花括号 {rel(kt)}:{line}:{col} -> 该 }} 没有可匹配的 {{")
            bad += 1
    if bad == 0:
        report("PASS", "孤立右花括号：未发现任何没有匹配 { 的顶层 }")


def main() -> int:
    print(f"== check_static.py == 仓库根：{ROOT}")
    check_xml_parse()
    check_resource_references()
    check_custom_views()
    check_delimiters()
    check_isolated_braces()
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
