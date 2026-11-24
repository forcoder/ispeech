#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_static.py —— iSpeech 仓库静态一致性校验

在没有 JDK / Android SDK / 网络的环境里，用纯标准库做「能可靠判定」的检查：

  1. app/src/main/res/**/*.xml 全部能被 ElementTree 解析
  2. Kotlin 里引用的资源必须存在：
       R.id.*     -> 某个 layout XML 里定义了 @+id/同名
       R.layout.* -> res/layout/同名.xml
       R.string.* -> res/values*/strings.xml
       R.color.*  -> res/values*/colors.xml 或 res/color/<name>.xml
       R.style.*  -> res/values*/themes.xml
     （android.R.* 前缀的框架资源不计入项目资源）
  3. layout XML 里带包名的自定义 View（<com.ispeech.xxx.Yyy>）在 Kotlin 里有同名 class
  4. Kotlin 源码括号配平（状态机处理字符串 / 原始字符串 / 字符字面量 / 注释）
  5. 出现「栈为空时的孤立 }」——即顶层多余的右花括号（一定会编译失败的手误）
  6. 默认 `asrModelUrl` 与 `network_security_config.xml` 自洽：明文 http 源必须被放行

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

    # res/color/<name>.xml：Android 的「一文件一资源」形式，资源名取自文件名
    # （文件内容是 <selector>，没有 <color name="..."> 属性）——键面图标 tint 用它。
    color_dir = RES / "color"
    if color_dir.is_dir():
        colors |= {p.stem for p in color_dir.glob("*.xml")}

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


def check_quick_search_box_flags() -> None:
    """快捷短语搜索框的交互标志必须**晚于** `inputType` 生效，且四件套 + 长按开关齐全。

    为什么值得静态卡住（VOCA-627 质检打回项 1）：`TextView` 的
    `isSingleLine` / `inputType` 都会走 `setKeyListener -> fixFocusableAndClickableSettings()`，
    在 `mMovement != null` 时把 `focusable / clickable / longClickable` **强制改回 true**。
    写在 `inputType` 之前的 `isClickable = false` 因此是无效的：控件仍可点可长按，
    `View.onTouchEvent` 吞掉 ACTION_DOWN，父行「点搜索框回到输入态」在文本区（weight=1f）
    整段失效，OEM 长按还会弹选区手柄——这是相对 main 的行为退步。

    本项目把这组标志收在 `pinDisplayOnly()` 里；检查两件事：
      1. 该函数体包含 isFocusable / isFocusableInTouchMode / isCursorVisible /
         isClickable / isLongClickable 全 false，且先摘 movementMethod；
      2. `searchText` 的 apply 块里，`pinDisplayOnly()` 的调用在 `inputType` 之后。
    """
    kt = JAVA / "com" / "ispeech" / "input" / "QuickPhrasePanel.kt"
    if not kt.is_file():
        report("FAIL", f"缺少 {rel(kt)}：无法核对搜索框标志")
        return
    text = kt.read_text(encoding="utf-8", errors="replace")

    fn = re.search(r"private fun TextView\.pinDisplayOnly\(\)\s*\{(.*?)\n    \}", text, re.S)
    if not fn:
        report("FAIL", f"{rel(kt)} 里找不到 `pinDisplayOnly()`（搜索框交互标志的唯一出口）")
        return
    body = fn.group(1)
    required = {
        "movementMethod = null": "movementMethod 非空时 setKeyListener 会把标志翻回 true",
        "isCursorVisible = false": "光标必须自绘，系统光标要关",
        "isFocusable = false": "抢焦点会让系统把它当新的输入目标",
        "isFocusableInTouchMode = false": "同上，触摸模式也不能可聚焦",
        "isClickable = false": "可点会吞 ACTION_DOWN",
        "isLongClickable = false": "长按会弹系统选区手柄 / 工具栏",
    }
    missing = [f"{k}（{why}）" for k, why in required.items() if k not in body]
    if missing:
        report("FAIL", f"搜索框交互标志不全：{'；'.join(missing)}")
        return
    # movementMethod 必须最先摘：setMovementMethod 自己也会跑一遍 fixFocusableAndClickableSettings
    if body.find("movementMethod = null") > body.find("isClickable = false"):
        report("FAIL", "pinDisplayOnly() 里 `movementMethod = null` 必须放在其他标志之前："
                       "`setMovementMethod` 会再跑一遍 fixFocusableAndClickableSettings 把它们翻回 true")
        return

    m = re.search(r"searchText\s*=\s*EditText\(context\)\.apply\s*\{(.*?)\n        \}", text, re.S)
    if not m:
        report("FAIL", f"{rel(kt)} 里找不到 `searchText = EditText(context).apply {{ ... }}` 块")
        return
    block = m.group(1)
    pos_input = block.find("inputType")
    pos_pin = block.find("pinDisplayOnly()")
    if pos_input < 0:
        report("FAIL", f"搜索框没有设置 inputType（{rel(kt)}）")
        return
    if pos_pin < 0:
        report("FAIL", f"searchText 的 apply 块没有调用 pinDisplayOnly()（{rel(kt)}）")
        return
    if pos_pin < pos_input:
        report("FAIL", "`pinDisplayOnly()` 写在 `inputType` **之前**："
                       "`setInputType -> setKeyListener -> fixFocusableAndClickableSettings()` 会把标志改回 true，"
                       "控件重新变成可点可长按（吞掉 ACTION_DOWN，父行点击在文本区失效）")
        return
    report("PASS", "搜索框交互标志齐全（含 isLongClickable = false），且 pinDisplayOnly() 在 inputType 之后生效")


def strip_kotlin_comments(text: str) -> str:
    """去掉 Kotlin 注释（`//` 与可嵌套的 `/* */`），保留字符串字面量原样。

    为什么需要：先前的码点检查在整个文件里 grep `codePointCount`，而 KDoc 注释里就有这个词，
    于是「把 `fun length()` 改成 `= text.length`」这个真实回归被注释白白骗过
    （VOCA-631 质检实测：改完 `check_static` 仍报 failed=0）。查代码就得先剥掉注释。
    """
    out = []
    i = 0
    n = len(text)
    state = "normal"
    depth = 0
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if state == "normal":
            if two == "//":
                state = "line"
                i += 2
                continue
            if two == "/*":
                state = "block"
                depth = 1
                i += 2
                continue
            if c == '"':
                state = "string"
                out.append(c)
                i += 1
                continue
            out.append(c)
            i += 1
            continue
        if state == "line":
            if c == "\n":
                state = "normal"
                out.append(c)
            i += 1
            continue
        if state == "block":
            if two == "/*":
                depth += 1
                i += 2
                continue
            if two == "*/":
                depth -= 1
                i += 2
                if depth == 0:
                    state = "normal"
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        # state == "string"：转义与原始字符串都按最小必要处理，避免把 // 当注释切掉
        if c == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        out.append(c)
        if c == '"':
            state = "normal"
        i += 1
    return "".join(out)


def find_expression_function_body(code: str, name: str):
    """取 `fun <name>(...) = <expr>` 的表达式体文本；不是表达式形式时返回 None。

    只用来核对「函数体里到底用了哪个 API」，不做通用 Kotlin 解析：表达式体到下一个空行 /
    下一个声明 / 定义体结束为止，足够区分 `= text.codePointCount(...)` 与 `= text.length`。
    """
    m = re.search(
        rf"fun\s+{re.escape(name)}\s*\([^)]*\)\s*(?::\s*[A-Za-z_][\w<>?., ]*\s*)?=",
        code,
    )
    if not m:
        return None
    rest = code[m.end():]
    end = re.search(r"\n\s*\n|\n\s*(?:fun |private |internal |public |@)", rest)
    return rest[: end.start()] if end else rest


def _balanced_call_args(code: str, open_paren: int):
    """从 `(` 之后开始，返回括号配平的实参文本（处理嵌套括号与字符串）。"""
    depth = 1
    i = open_paren + 1
    start = i
    while i < len(code) and depth > 0:
        c = code[i]
        if c == '"':
            i += 1
            while i < len(code):
                if code[i] == "\\":
                    i += 2
                    continue
                if code[i] == '"':
                    break
                i += 1
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return code[start:i]
        i += 1
    return code[start:]


def iter_call_args(code: str, function: str):
    """逐个产出 `function(...)` 的实参文本（含嵌套调用的完整文本）。"""
    for m in re.finditer(rf"\b{re.escape(function)}\s*\(", code):
        yield _balanced_call_args(code, m.end() - 1)


def check_quick_search_cursor_controls() -> None:
    """条目 1（VOCA-631）：`◀ ▶` 的尺寸 / 连移常量、码点口径、单一写入口必须同时成立。

    卡住四件事，全是「改错一行就静默退化」的：
      1. `◀ ▶ ✕` 可点边长 ≥ 40 dp（规格 §3.4 / AC-1.8，现状曾是 30×28）；
      2. 长按连移用 380 ms 延迟 + 90 ms 间隔（规格 §3.4 的定值，散落魔法值会漂）；
      3. `SearchCaret.length()` 的**函数体**必须走 `codePointCount`——R1-1 要求码点。
         只在整个文件里 grep 字符串不够：KDoc 注释里也有 `codePointCount`，
         把 `fun length()` 改成 `= text.length` 后仍会命中（VOCA-631 质检实测的逃逸路径）。
      4. 四条写入路径与 `◀ ▶` 只经 `applyPanelState` 一个入口改状态（R1-15）：
         服务里不得出现 `setPanelKeyword(panelKeyword + …)`、不得在视图侧拼串、
         `applyPanelState(…)` 的实参不得现场 `+` 拼串，且三处整串置空必须都在。
    """
    panel = JAVA / "com" / "ispeech" / "input" / "QuickPhrasePanel.kt"
    caret = JAVA / "com" / "ispeech" / "input" / "SearchCaret.kt"
    service = JAVA / "com" / "ispeech" / "input" / "IspeechImeService.kt"
    for f in (panel, caret, service):
        if not f.is_file():
            report("FAIL", f"缺少 {rel(f)}：无法核对条目 1 的光标契约")
            return

    panel_text = panel.read_text(encoding="utf-8", errors="replace")
    # ① 可点边长：常量必须是 40（◀ ▶ ✕ 三处都按它给尺寸）
    m = re.search(r"private val touchTargetDp = (\d+)", panel_text)
    if not m:
        report("FAIL", f"{rel(panel)} 里找不到 `touchTargetDp`（40 dp 可点边长的单一来源）")
        return
    if int(m.group(1)) < 40:
        report("FAIL", f"搜索行可点边长 touchTargetDp={m.group(1)} < 40 dp（规格 §3.4 / AC-1.8）")
        return
    # ② 连移常量
    delay = re.search(r"cursorRepeatDelayMs = (\d+)L", panel_text)
    interval = re.search(r"cursorRepeatIntervalMs = (\d+)L", panel_text)
    if not delay or not interval:
        report("FAIL", f"{rel(panel)} 缺少 `◀ ▶` 长按连移常量（规格 §3.4）")
        return
    if (int(delay.group(1)), int(interval.group(1))) != (380, 90):
        report("FAIL", "`◀ ▶` 连移参数应为 380 ms 后每 90 ms，实际 "
                       f"{delay.group(1)}/{interval.group(1)}（规格 §3.4 定值）")
        return
    # ③ 码点口径：查 length() 的**函数体**，不看注释、不用「排除含 fun length 的行」自我豁免
    caret_text = caret.read_text(encoding="utf-8", errors="replace")
    caret_code = strip_kotlin_comments(caret_text)
    if "codePointCount" not in caret_code or "offsetByCodePoints" not in caret_code:
        report("FAIL", f"{rel(caret)} 未按码点实现（R1-1）：代码里（注释不算）缺 "
                       "codePointCount / offsetByCodePoints")
        return
    length_body = find_expression_function_body(caret_code, "length")
    if length_body is None:
        report("FAIL", f"{rel(caret)} 里找不到 `fun length(` 的表达式函数体，无法核对码点口径")
        return
    if "codePointCount" not in length_body:
        report("FAIL", f"{rel(caret)} 的 `length()` 函数体没用 `codePointCount`："
                       "把码元数当码点数会让 clamp 上界超出、emoji 被拆半（R1-1）")
        return
    # 码元数只允许出现在 length() 那一行（它本来就是码元→码点的换算点）；
    # clamps / substring / coerceIn 的边界上出现就说明退回码元口径。
    offenders = [
        (i, line.strip())
        for i, line in enumerate(caret_code.splitlines(), 1)
        if "text.length" in line and "fun length(" not in line
    ]
    if offenders:
        detail = "；".join(f"第 {i} 行 {s}" for i, s in offenders[:3])
        report("FAIL", f"{rel(caret)} 用 UTF-16 码元数当边界（R1-1，emoji 会拆半留孤立代理）：{detail}")
        return
    # ④ 单一写入口
    service_text = service.read_text(encoding="utf-8", errors="replace")
    service_code = strip_kotlin_comments(service_text)
    if "private fun applyPanelState(" not in service_code:
        report("FAIL", f"{rel(service)} 缺少 `applyPanelState`（面板 (keyword,cursor) 唯一写入口，R1-15）")
        return
    if re.search(r"setPanelKeyword\(\s*panelKeyword(?:\.keyword)?\s*\+", service_code):
        report("FAIL", f"{rel(service)} 里仍有 `setPanelKeyword(panelKeyword + …)` 的末尾追加："
                       "绕过光标（R1-15 / R1-44）")
        return
    # 面板内拼串的另一种写法：直接对 keyword 做 substring / `+` 再交给 setPanelKeyword
    if re.search(r"setPanelKeyword\(\s*[^)]*\.(?:substring|drop|dropLast|take)\(", service_code):
        report("FAIL", f"{rel(service)} 仍在视图侧拼串（`.substring/.drop/.take` 后直接 setPanelKeyword）："
                       "插入 / 删除必须走 PanelKeywordState（R1-15）")
        return
    # 再换写法：`applyPanelState(panelKeyword.replace(panelKeyword.keyword + text))`
    # 在调用点现场拼串，同样绕过状态机的插删算式（VOCA-631 质检实测的逃逸路径）。
    for arg in iter_call_args(service_code, "applyPanelState"):
        if "+" in arg:
            report("FAIL", f"{rel(service)} 的 `applyPanelState(…)` 实参里现场拼串（含 `+`）："
                           f"{arg.strip()[:80]}；插删必须用 PanelKeywordState 的方法（R1-15）")
            return
    # 三处绕过 setter 的整串置空必须都在：少了 closePanel 那处，关面板后光标停在旧位置，
    # 下次开面板「重开回末尾」就失效（AC-1.10 / R1-31）。
    resets = len(re.findall(r"panelKeyword\s*=\s*PanelKeywordState\.EMPTY", service_code))
    if resets < 3:
        report("FAIL", f"{rel(service)} 的整串置空只有 {resets} 处，应 ≥3"
                       "（openPanel / closePanel / resetState 必须一起复位光标，AC-1.10）")
        return
    report("PASS", "条目 1 光标契约齐全（◀▶✕ ≥40dp、连移 380/90ms、length() 码点口径、"
                   "applyPanelState 单入口 + 三处整串复位）")


def check_asr_model_config() -> None:
    """默认模型源与网络安全配置必须自洽。

    `ASR_MODEL_URL` 是编译期常量；Android 只对 `network_security_config.xml`
    里放行的域名允许明文 HTTP。换源时只改其中一个（或把源换成未放行的
    http 域名），客户端的模型下载会在建立连接前就被明文策略拒绝。
    换源流程见 docs/model-host-runbook.md。
    """
    gradle = ROOT / "app" / "build.gradle.kts"
    nsc = RES / "xml" / "network_security_config.xml"
    if not gradle.is_file():
        report("FAIL", f"缺少 {rel(gradle)}：无法核对默认模型源")
        return

    text = gradle.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"asrModelUrl: String\s*=.*?\?:\s*\"([^\"]+)\"", text, re.S)
    if not m:
        report(
            "WARN",
            f"{rel(gradle)} 里没解析到 asrModelUrl 的默认值（写法变了？），跳过配置核对",
        )
        return

    url = m.group(1)
    scheme, _, rest = url.partition("://")
    host = rest.split("/", 1)[0].split(":", 1)[0].lower()
    if scheme not in ("http", "https") or not host:
        report("FAIL", f"默认 asrModelUrl 不是 http(s) URL：{url}")
        return
    if scheme == "https":
        report("PASS", f"默认模型源 {host} 走 HTTPS（系统默认信任，无需放行明文）")
        return

    allowed: list[tuple[str, bool]] = []
    if nsc.is_file():
        try:
            tree = ET.parse(nsc)
        except ET.ParseError:
            tree = None  # 已在第 1 项报 XML 解析失败
        if tree is not None:
            for cfg in tree.getroot().iter("domain-config"):
                if (cfg.get("cleartextTrafficPermitted") or "").lower() != "true":
                    continue
                for dom in cfg.iter("domain"):
                    name = (dom.text or "").strip().lower()
                    sub = (dom.get("includeSubdomains") or "").lower() == "true"
                    if name:
                        allowed.append((name, sub))

    hit = any(
        host == name or (sub and host.endswith("." + name)) for name, sub in allowed
    )
    if hit:
        report("PASS", f"默认模型源 {host} 为明文 HTTP，已在 network_security_config.xml 放行")
    else:
        report(
            "FAIL",
            f"默认模型源 {url} 是明文 HTTP，但 {rel(nsc)} 未放行 {host}"
            f"（客户端会直接失败）——要么改用 HTTPS，要么在 domain-config 里加 "
            f'<domain includeSubdomains="true">{host}</domain>',
        )


def main() -> int:
    print(f"== check_static.py == 仓库根：{ROOT}")
    check_xml_parse()
    check_resource_references()
    check_custom_views()
    check_delimiters()
    check_isolated_braces()
    check_asr_model_config()
    check_quick_search_box_flags()
    check_quick_search_cursor_controls()
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
