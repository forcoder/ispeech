#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_crosscut.py —— 交叉复核：新增功能「接线是否闭环 / 声明与实现是否一致」

本脚本只读源码与资源，不编译、不联网；面向最近一轮新增功能做「断链体检」：

  1. LookupKind 新增 WUBIPHRASE / PINYIN 后：枚举 / order / when / 字符串资源 /
     设置页下拉过滤是否一致
  2. KeyAction.SWITCH_IME 全链路：KeyDef -> KeyboardLayouts(工具栏) -> SoftKeyboardView
     -> IspeechImeService -> InputMethodPanel -> layout xml -> 服务回调
  3. KeyboardLayoutKind.T9：枚举 / pages() / 数字页入口 / 追加页下标；并检查服务侧
     T9 缓冲（数字串 -> 字母串）是否接线
  4. 新增设置项（拼音混输 / 模糊音 / 繁体输出）：key -> 默认值 -> UI -> 持久化 ->
     引擎重建是否闭环（含「运行中的输入法服务是否会用上新引擎」）
  5. 输入统计：InputStats -> 服务埋点 -> StatsViewModel/StatsScreen -> 导航 STATS ->
     首页卡片
  6. 短语使用记录 PhraseUsageStore：是否只从 AppContainer 单例获取、面板是否零 IO
  7. 口径一致性：版本号常量 vs build.gradle、注释 vs 枚举、校验脚本 vs 源码
  8. 语音输入（VOICE / ASR）全链路：KeyDef -> 工具栏 -> 服务状态机 -> VoiceInputController
     -> Manifest 权限 / queries -> AsrSettings -> 设置页 / 输入法内快捷面板 / 麦克风代申请
 9. 用户词库（模式维度 / 自造词 / 拼音态）：UserDictMode 声明 -> 文件格式兼容 -> 引擎按模式
     过滤 -> 服务长按记词 + 学习分流 -> 拼音态查询含用户词库 -> 短语不污染编码空间
10. 分类词库（细胞词库）：存储 / 索引解析 / 稳定 id / 分类头 / 引擎接线 / 设置页安装启停
11. 中文输入法（默认拼音 / 五笔 + 中英切换）：枚举 -> 持久化 -> 设置页 / 内置面板 -> 服务两态切换
12. 候选条字重（VOCA-56）：候选词常规字重、首选靠颜色 / 底色区分、类注释口径一致

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
JAVA = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech"
RES = ROOT / "app" / "src" / "main" / "res"

KEYDEF = JAVA / "input" / "keyboard" / "KeyDef.kt"
LAYOUTS = JAVA / "input" / "keyboard" / "KeyboardLayouts.kt"
T9 = JAVA / "input" / "keyboard" / "T9Layouts.kt"
VIEW = JAVA / "input" / "SoftKeyboardView.kt"
SERVICE = JAVA / "input" / "IspeechImeService.kt"
PANEL = JAVA / "input" / "InputMethodPanel.kt"
QUICK_PANEL = JAVA / "input" / "QuickPhrasePanel.kt"
CANDIDATE_VIEW = JAVA / "input" / "CandidateView.kt"
LAYOUT_XML = RES / "layout" / "ime_input_view.xml"
STRINGS = RES / "values" / "strings.xml"
STRINGS_EN = RES / "values-en" / "strings.xml"

LOOKUP_CANDIDATE = JAVA / "engine" / "LookupCandidate.kt"
LOOKUP_ENGINE = JAVA / "engine" / "LookupEngine.kt"
COMPOSITE_ENGINE = JAVA / "engine" / "CompositeEngine.kt"
QUICK_LOCATOR = JAVA / "engine" / "QuickLocator.kt"
PYINYIN_ENGINE = JAVA / "engine" / "PinyinEngine.kt"

QUICK_SCREEN = JAVA / "settings" / "QuickPhrasesScreen.kt"
SETTINGS_STORE = JAVA / "settings" / "store" / "SettingsStore.kt"
KEYBOARD_SETTINGS = JAVA / "settings" / "store" / "KeyboardSettings.kt"
KEYBOARD_VM = JAVA / "settings" / "keyboard" / "KeyboardViewModel.kt"
KEYBOARD_SCREEN = JAVA / "settings" / "KeyboardScreen.kt"
DICTIONARY_SCREEN = JAVA / "settings" / "DictionaryScreen.kt"
DICTIONARY_VM = JAVA / "settings" / "dictionary" / "DictionaryViewModel.kt"

APP_CONTAINER = JAVA / "di" / "AppContainer.kt"
HAN_CONVERSION = JAVA / "data" / "HanConversion.kt"
INPUT_STATS = JAVA / "data" / "InputStats.kt"
PHRASE_USAGE = JAVA / "data" / "PhraseUsageStore.kt"
USER_DICT_STORE = JAVA / "data" / "UserDictStore.kt"
USER_DICT_ENGINE = JAVA / "engine" / "UserDictEngine.kt"

CELL_DICT_STORE = JAVA / "data" / "CellDictStore.kt"
CELL_DICT_ENGINE = JAVA / "engine" / "CellDictEngine.kt"
CHINESE_INPUT_METHOD = JAVA / "settings" / "store" / "ChineseInputMethod.kt"
INPUT_MODE = JAVA / "input" / "InputMode.kt"
INPUT_MODE_TEST = ROOT / "app" / "src" / "test" / "java" / "com" / "ispeech" / "input" / "InputModePolicyTest.kt"

VOICE_CONTROLLER = JAVA / "input" / "VoiceInputController.kt"
SETTINGS_PANEL = JAVA / "input" / "SettingsPanel.kt"
ASR_SETTINGS = JAVA / "settings" / "store" / "AsrSettings.kt"
ASR_SCREEN = JAVA / "settings" / "asr" / "AsrSettingsScreen.kt"
ASR_VM = JAVA / "settings" / "asr" / "AsrSettingsViewModel.kt"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"

STATS_SCREEN = JAVA / "settings" / "StatsScreen.kt"
STATS_VM = JAVA / "settings" / "stats" / "StatsViewModel.kt"
NAV_GRAPH = JAVA / "settings" / "nav" / "IspeechNavGraph.kt"
HOME_SCREEN = JAVA / "settings" / "HomeScreen.kt"
MAIN_ACTIVITY = JAVA / "settings" / "MainActivity.kt"
BUILD_GRADLE = ROOT / "app" / "build.gradle.kts"

VERIFY_DIR = ROOT / "tools" / "verify"

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
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8", errors="replace")


def check(cond: bool, ok: str, fail: str) -> None:
    """单一断言：cond 为真报 PASS，否则报 FAIL。"""
    report("PASS" if cond else "FAIL", ok if cond else fail)


# ─────────────────────────────────────────────────────────────
# 通用：括号匹配（尊重字符串 / 字符字面量）
# ─────────────────────────────────────────────────────────────

def find_matching_brace(text: str, open_idx: int) -> int:
    """返回 text[open_idx]（必须是 '{'）对应的 '}' 下标；找不到返回 -1。"""
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


def enum_members(text: str, enum_name: str):
    """解析 `enum class X { A, B(...), C; ... }` 的成员名；找不到返回 None。"""
    m = re.search(rf"enum\s+class\s+{re.escape(enum_name)}\s*(?:\([^)]*\))?\s*\{{", text)
    if not m:
        return None
    open_idx = text.index("{", m.start())
    end = find_matching_brace(text, open_idx)
    if end < 0:
        return None
    body = strip_comments(text[open_idx + 1:end])
    # 成员既可能一行一个（枚举体多行），也可能压在单行（`{ A, B, C }`），
    # 所以按「行首 / , / ; / {」作为边界统一抽取。
    return re.findall(r"(?:^|[\n,;{])\s*([A-Z][A-Z0-9_]*)", body)


def strip_comments(text: str) -> str:
    """去掉块注释与行注释（尊重字符串 / 字符字面量），避免注释里的词被当成代码。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "/*":
            i = text.find("*/", i + 2)
            i = n if i < 0 else i + 2
            continue
        if two == "//":
            i = text.find("\n", i + 2)
            i = n if i < 0 else i
            continue
        c = text[i]
        if c in "\"'":
            quote = c
            out.append(c)
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\":
                    out.append(text[i])
                    i += 1
                out.append(text[i])
                i += 1
            if i < n:
                out.append(text[i])
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def function_region(text: str, header_regex: str) -> str:
    """截取从 header_regex 匹配处到「下一个 4 空格缩进的 fun / 注释横幅」之间的源码。"""
    m = re.search(header_regex, text)
    if not m:
        return ""
    stop = re.search(r"\n    (?://|private fun |fun )", text[m.end():])
    end = m.end() + stop.start() if stop else len(text)
    return text[m.start():end]


def when_subject_blocks(text: str):
    """产出 (subject, body) —— 所有 `when (subject) { ... }` 块。"""
    for m in re.finditer(r"\bwhen\s*\(([^()\n]*)\)\s*\{", text):
        open_idx = text.index("{", m.end() - 1)
        end = find_matching_brace(text, open_idx)
        if end < 0:
            continue
        yield m.group(1), text[open_idx:end]


def xml_ids(path: Path):
    try:
        text = read(path)
    except OSError:
        return set()
    return set(re.findall(r"@\+id/([A-Za-z_0-9]+)", text))


# ═════════════════════════════════════════════════════════════
# 1. LookupKind：WUBIPHRASE / PINYIN 全链路一致
# ═════════════════════════════════════════════════════════════

def check_lookup_kind() -> None:
    src = read(LOOKUP_CANDIDATE)
    if not src:
        report("FAIL", f"缺少 {rel(LOOKUP_CANDIDATE)}")
        return

    members = set(enum_members(src, "LookupKind") or [])
    expected = {"WUBI", "WUBIPHRASE", "PHRASE", "QUICKAPPLY", "PINYIN"}
    check(
        members == expected,
        f"LookupKind 枚举值 = {sorted(expected)}",
        f"LookupKind 枚举值应含 {sorted(expected)}，实际 {sorted(members)}",
    )

    # order getter：QUICKAPPLY(0) < PHRASE(1) < WUBI(2) < WUBIPHRASE(3) < PINYIN(4)
    order_map = dict(re.findall(r"(QUICKAPPLY|PHRASE|WUBI|WUBIPHRASE|PINYIN)\s*->\s*(\d+)", src))
    want = {"QUICKAPPLY": "0", "PHRASE": "1", "WUBI": "2", "WUBIPHRASE": "3", "PINYIN": "4"}
    check(
        order_map == want,
        f"LookupKind.order：{want}（应用<短语<单字<词组<拼音）",
        f"LookupKind.order 应为 {want}，实际 {order_map}",
    )

    # CandidateRanking 真的用 kind.order 排序（不是只声明不实现）
    ranking = read(LOOKUP_ENGINE)
    check(
        "a.kind.order" in ranking and "b.kind.order" in ranking,
        "CandidateRanking 用 kind.order 排序（声明有被使用）",
        f"未在 {rel(LOOKUP_ENGINE)} 找到 kind.order 排序（LookupKind.order 可能是死代码）",
    )
    check(
        "kind.order" in read(COMPOSITE_ENGINE) or "CandidateRanking.comparator" in read(COMPOSITE_ENGINE),
        "CompositeEngine.lookup 走 CandidateRanking 排序",
        f"{rel(COMPOSITE_ENGINE)} 未使用 CandidateRanking 排序",
    )

    # 每个 when(只看 LookupKind 主题) 必须覆盖 5 个分支或有 else
    when_bad = []
    for path in sorted(JAVA.rglob("*.kt")):
        text = read(path)
        if "LookupKind" not in text:
            continue
        for subject, body in when_subject_blocks(text):
            if "kind" not in subject.lower():
                continue
            branches = set(re.findall(r"LookupKind\.([A-Z][A-Z0-9_]*)\s*->", body))
            if branches and branches != expected and "else ->" not in body:
                when_bad.append(f"{rel(path)} when({subject}) 缺 {sorted(expected - branches)}")
    check(
        not when_bad,
        "所有 when(LookupKind) 均覆盖全部 5 个分支",
        "when(LookupKind) 分支不全：" + "；".join(when_bad),
    )

    # 设置页下拉必须排除由引擎产出、不应人工指定的两个 kind
    quick_screen = read(QUICK_SCREEN)
    check(
        "it != LookupKind.WUBIPHRASE" in quick_screen and "it != LookupKind.PINYIN" in quick_screen,
        "QuickPhrasesScreen 下拉过滤排除 WUBIPHRASE / PINYIN（引擎产出，不人工指定）",
        f"{rel(QUICK_SCREEN)} 下拉未同时排除 WUBIPHRASE 与 PINYIN",
    )

    # 5 个来源角标的字符串资源，中英都要有
    kind_res = {"lookup_kind_wubi", "lookup_kind_wubi_phrase",
                "lookup_kind_pinyin", "lookup_kind_phrase", "lookup_kind_quickapply"}
    for path in (STRINGS, STRINGS_EN):
        names = set(re.findall(r'<string\s+name="([^"]+)"', read(path)))
        missing = sorted(kind_res - names)
        check(
            not missing,
            f"{rel(path)} 含全部 5 个 lookup_kind_* 字符串",
            f"{rel(path)} 缺字符串：{missing}",
        )

    # 设置页 lookupKindRes 把两个新 kind 映射到对应资源（不是漏分支 / 错资源）
    check(
        "LookupKind.WUBIPHRASE -> R.string.lookup_kind_wubi_phrase" in quick_screen
        and "LookupKind.PINYIN -> R.string.lookup_kind_pinyin" in quick_screen,
        "lookupKindRes：WUBIPHRASE->lookup_kind_wubi_phrase、PINYIN->lookup_kind_pinyin",
        f"{rel(QUICK_SCREEN)} lookupKindRes 未正确映射 WUBIPHRASE / PINYIN",
    )


# ═════════════════════════════════════════════════════════════
# 2. KeyAction.SWITCH_IME 全链路
# ═════════════════════════════════════════════════════════════

def check_switch_ime_chain() -> None:
    keydef = read(KEYDEF)
    view = read(VIEW)
    service = read(SERVICE)
    panel = read(PANEL)
    xml = read(LAYOUT_XML)

    check(
        "SWITCH_IME" in (enum_members(keydef, "KeyAction") or []),
        "KeyAction 声明了 SWITCH_IME",
        f"{rel(KEYDEF)} 的 KeyAction 未声明 SWITCH_IME",
    )
    check(
        "KeyAction.SWITCH_IME" in view,
        "SoftKeyboardView 工具栏含 KeyAction.SWITCH_IME（「⌨输入法」）",
        f"{rel(VIEW)} 工具栏未出现 KeyAction.SWITCH_IME",
    )
    check(
        "KeyAction.SWITCH_IME -> openPanel(Panel.SWITCH_IME)" in service
        or ("KeyAction.SWITCH_IME ->" in service and "Panel.SWITCH_IME" in service),
        "IspeechImeService.handleSoftKey 处理 SWITCH_IME -> Panel.SWITCH_IME",
        f"{rel(SERVICE)} handleSoftKey 未把 SWITCH_IME 接到 Panel.SWITCH_IME",
    )
    check(
        "SWITCH_IME" in (enum_members(service, "Panel") or []),
        "Service Panel 枚举含 SWITCH_IME",
        f"{rel(SERVICE)} Panel 枚举缺 SWITCH_IME",
    )
    check(
        "Panel.SWITCH_IME" in service and "inputMethodPanel?.visibility" in service,
        "updatePanelVisibility 用 Panel.SWITCH_IME 控制 inputMethodPanel 显隐",
        f"{rel(SERVICE)} updatePanelVisibility 未绑定 Panel.SWITCH_IME",
    )
    check(
        "input_method_panel" in xml_ids(LAYOUT_XML) and "com.ispeech.input.InputMethodPanel" in xml,
        "layout ime_input_view.xml 声明 @+id/input_method_panel（InputMethodPanel）",
        f"{rel(LAYOUT_XML)} 缺 @+id/input_method_panel 或 InputMethodPanel 标签",
    )
    check(
        all(token in panel for token in ("onPick", "onClose", "onSystemPicker", "fun submit(")),
        "InputMethodPanel 暴露 onPick / onClose / onSystemPicker / submit",
        f"{rel(PANEL)} 缺 onPick/onClose/onSystemPicker/submit 之一",
    )
    bound = (
        "R.id.input_method_panel" in service
        and "imeSwitcher.onPick" in service
        and "imeSwitcher.onClose" in service
        and "imeSwitcher.onSystemPicker" in service
    )
    check(
        bound,
        "服务绑定 input_method_panel 并接上 onPick/onClose/onSystemPicker",
        f"{rel(SERVICE)} 未完整绑定输入法切换面板三个回调",
    )
    check(
        "setInputMethod(token, id)" in service and "showInputMethodPicker()" in service,
        "切换走 setInputMethod(window token)，失败兜底 showInputMethodPicker",
        f"{rel(SERVICE)} 未同时实现 setInputMethod 与 showInputMethodPicker 兜底",
    )
    guard_count = service.count("panel == Panel.QUICK_PHRASE &&")
    check(
        guard_count >= 4,
        f"面板键盘分支加 QUICK_PHRASE 守卫（{guard_count} 处），SWITCH_IME 面板下字母不会误进筛选",
        f"{rel(SERVICE)} 面板键盘分支守卫不足（仅 {guard_count} 处），SWITCH_IME 面板下按键可能误触发",
    )

    # KeyAction 声明 vs 服务分支：每个声明的 action 都必须在服务里有分支
    declared = set(enum_members(keydef, "KeyAction") or [])
    wired = set(re.findall(r"KeyAction\.([A-Z][A-Z0-9_]*)\s*->", service))
    missing = sorted(declared - wired)
    check(
        not missing,
        f"handleSoftKey 覆盖全部 {len(declared)} 个 KeyAction（无声明未接线的按键行为）",
        f"{rel(SERVICE)} handleSoftKey 未处理 KeyAction：{missing}",
    )


# ═════════════════════════════════════════════════════════════
# 3. KeyboardLayoutKind.T9
# ═════════════════════════════════════════════════════════════

def check_t9() -> None:
    keydef = read(KEYDEF)
    layouts = read(LAYOUTS)
    t9 = read(T9)
    view = read(VIEW)
    service = read(SERVICE)

    kinds = set(enum_members(keydef, "KeyboardLayoutKind") or [])
    check("T9" in kinds, "KeyboardLayoutKind 声明了 T9", f"{rel(KEYDEF)} 未声明 KeyboardLayoutKind.T9")

    # pages() 的 when 覆盖全部 kind（含 T9 -> T9Layouts.pages()）
    pages_body = ""
    for subject, body in when_subject_blocks(layouts):
        if subject.strip() == "kind":
            pages_body = body
            break
    covered = set(re.findall(r"KeyboardLayoutKind\.([A-Z][A-Z0-9_]*)\s*->", pages_body))
    check(
        covered and not (kinds - covered),
        f"KeyboardLayouts.pages() 的 when 覆盖 {sorted(kinds)}",
        f"pages() 未覆盖 KeyboardLayoutKind：{sorted(kinds - covered)}",
    )
    check(
        "KeyboardLayoutKind.T9 -> T9Layouts.pages()" in pages_body,
        "pages() 把 T9 分派给 T9Layouts.pages()",
        "pages() 的 T9 分支未指向 T9Layouts.pages()",
    )
    check(
        re.search(
            r"fun\s+pages\(\)\s*:\s*List<KeyboardPage>\s*=\s*"
            r"listOf\(digitPage\(\),\s*punctuationPage\(\)\)",
            t9,
        ) is not None,
        "T9Layouts.pages() = 数字页 + `1` 标点页两页",
        "T9Layouts.pages() 结构与「digitPage() + punctuationPage()」不一致",
    )

    # 九宫格入口在数字页底行（工具栏已满，入口让给数字页）
    numbers_body = function_region(layouts, r"fun\s+numbers\s*\(\s*\)\s*:\s*KeyboardPage\s*=")
    check(
        "t9SwitchKey()" in numbers_body and "KeyboardLayoutKind.T9" in layouts,
        "数字页（123）底行含 t9SwitchKey() -> KeyboardLayoutKind.T9 入口",
        f"{rel(LAYOUTS)} numbers() 底行未提供 T9 入口（t9SwitchKey）",
    )

    # 追加页下标是按「总页数 - 本组页数」反推，不是硬编码
    check(
        "KeyboardLayouts.symbols().size - moreSymbolPages().size" in t9
        and "KeyboardLayouts.emoji().size - kaomojiPages().size" in t9,
        "moreSymbolFirstPage / kaomojiFirstPage 按总页数反推（不硬编码）",
        f"{rel(T9)} 追加页下标疑似硬编码（与 KeyboardLayouts 追加位置可能漂移）",
    )
    check(
        "+ T9Layouts.moreSymbolPages()" in layouts and "+ T9Layouts.kaomojiPages()" in layouts,
        "KeyboardLayouts.symbols()/emoji() 末尾确实追加了更多符号 / 颜文字页",
        f"{rel(LAYOUTS)} 未在符号 / 表情页末尾追加 T9Layouts 页（入口页下标会指空）",
    )

    # 服务侧 T9 缓冲（数字串 -> 字母串）接线：布局能进，但按下 2-9 会怎样？
    service_uses_t9 = ("T9Layouts" in service) or ("isT9CodeKey" in service)
    view_uses_t9 = ("T9Layouts" in view)
    digits_are_char = "output = digit" in t9 and "KeyDef.char(" in t9
    if not (service_uses_t9 or view_uses_t9) and digits_are_char:
        report(
            "FAIL",
            "T9 九宫格可进入（数字页「9键」），但服务侧未接线 T9 缓冲："
            "T9Layouts 的 2-9 是 KeyAction.CHAR(output=\"2\"..\"9\")，"
            f"{rel(SERVICE)} / {rel(VIEW)} 既未引用 T9Layouts 也未判断 isT9CodeKey，"
            "按 2-9 会走 handleChar 的非字母分支直接上屏数字，数字串→字母展开不生效"
            "（见 docs/t9-and-symbols.md 第 4 节「T9 缓冲归主 Agent」）",
        )
    else:
        report("PASS", "T9 服务侧缓冲已接线（引用 T9Layouts 或 isT9CodeKey）")

    # 服务侧细节：数字串缓冲 / 上屏 / 查询源 / 与五笔缓冲互斥
    check(
        "t9Digits" in service and "handleT9Digit" in service
        and "refreshT9Candidates()" in service and "fun commitT9()" in service,
        "T9 缓冲链路完整：t9Digits -> handleT9Digit -> refreshT9Candidates -> commitT9",
        f"{rel(SERVICE)} 缺少 t9Digits / handleT9Digit / refreshT9Candidates / commitT9 之一",
    )
    container_src = read(APP_CONTAINER)
    check(
        "AppContainer.pinyinEngine(" in service and "fun pinyinEngine(" in container_src,
        "T9 查询源是九宫格专用拼音引擎（AppContainer.pinyinEngine），与「拼音混输」开关解耦",
        "T9 未接拼音引擎：数字展开出的字母串会去查五笔，候选语义不对",
    )
    check(
        "fun engineGeneration(" in container_src and "engineGeneration++" in container_src,
        "AppContainer 暴露引擎代次，供服务判断「设置变了要重建引擎」",
        "AppContainer 未暴露引擎代次，服务无法发现 invalidateEngine 后的设置变化",
    )
    check(
        "hasGlyph" in service and "fun List<LookupCandidate>.withoutTofu()" in service,
        "候选展示前按字形过滤（无字形生僻字不再显示成豆腐块）",
        f"{rel(SERVICE)} 未做 hasGlyph 过滤，非 BMP 生僻字候选会显示豆腐块",
    )


# ═════════════════════════════════════════════════════════════
# 4. 新增设置项：拼音混输 / 模糊音 / 繁体输出
# ═════════════════════════════════════════════════════════════

def check_new_settings() -> None:
    store = read(SETTINGS_STORE)
    settings = read(KEYBOARD_SETTINGS)
    vm = read(KEYBOARD_VM)
    screen = read(KEYBOARD_SCREEN)
    container = read(APP_CONTAINER)
    service = read(SERVICE)

    # 持久化 key
    keys = {"pinyin_mixed", "fuzzy_pinyin", "traditional_output"}
    missing_keys = sorted(k for k in keys if f'booleanPreferencesKey("{k}")' not in store)
    check(
        not missing_keys,
        f"SettingsStore 声明持久化 key：{sorted(keys)}",
        f"{rel(SETTINGS_STORE)} 缺持久化 key：{missing_keys}",
    )

    # 数据类字段
    fields = {"pinyinMixed", "fuzzyPinyin", "traditionalOutput"}
    missing_fields = sorted(f for f in fields if f not in settings)
    check(
        not missing_fields,
        f"KeyboardSettings 含字段 {sorted(fields)}",
        f"{rel(KEYBOARD_SETTINGS)} 缺字段：{missing_fields}",
    )

    # flow 读取（默认值兜底）
    missing_flow = sorted(
        f for f, key in (("pinyinMixed", "KEY_PINYIN_MIXED"),
                         ("fuzzyPinyin", "KEY_FUZZY_PINYIN"),
                         ("traditionalOutput", "KEY_TRADITIONAL_OUTPUT"))
        if not re.search(rf"{f}\s*=\s*prefs\[{key}\]", store)
    )
    check(
        not missing_flow,
        "keyboardSettingsFlow 读取三个新 key（带默认值兜底）",
        f"{rel(SETTINGS_STORE)} keyboardSettingsFlow 未读取：{missing_flow}",
    )

    # setter
    missing_setter = sorted(
        name for name in ("setPinyinMixed", "setFuzzyPinyin", "setTraditionalOutput")
        if f"suspend fun {name}(" not in store
    )
    check(
        not missing_setter,
        "SettingsStore 三个 setter 齐全",
        f"{rel(SETTINGS_STORE)} 缺 setter：{missing_setter}",
    )

    # ViewModel：三个开关 + 两个引擎相关开关触发 invalidateEngine
    vm_missing = sorted(
        n for n in ("setPinyinMixed", "setFuzzyPinyin", "setTraditionalOutput") if f"fun {n}(" not in vm
    )
    check(
        not vm_missing,
        "KeyboardViewModel 暴露三个 setter",
        f"{rel(KEYBOARD_VM)} 缺方法：{vm_missing}",
    )
    pinyin_block = re.search(r"fun setPinyinMixed.*?\n    \}", vm, re.S)
    fuzzy_block = re.search(r"fun setFuzzyPinyin.*?\n    \}", vm, re.S)
    check(
        bool(pinyin_block) and "AppContainer.invalidateEngine()" in pinyin_block.group(0)
        and bool(fuzzy_block) and "AppContainer.invalidateEngine()" in fuzzy_block.group(0),
        "拼音混输 / 模糊音 setter 均调用 AppContainer.invalidateEngine()",
        f"{rel(KEYBOARD_VM)} 拼音混输 / 模糊音 setter 未触发 invalidateEngine",
    )

    # UI 开关
    ui_missing = sorted(
        title for title, handler in (("拼音混输", "setPinyinMixed"),
                                     ("模糊音", "setFuzzyPinyin"),
                                     ("繁体输出", "setTraditionalOutput"))
        if f'title = "{title}"' not in screen or f"viewModel.{handler}(it)" not in screen
    )
    check(
        not ui_missing,
        "KeyboardScreen 三个开关均绑定 ViewModel",
        f"{rel(KEYBOARD_SCREEN)} 开关未绑定：{ui_missing}",
    )

    # 引擎构建把两个开关透传下去
    check(
        "pinyin = keyboard.pinyinMixed" in container and "fuzzyPinyin = keyboard.fuzzyPinyin" in container,
        "AppContainer.buildEngineFromSettings 把 pinyinMixed/fuzzyPinyin 透传给 buildComposite",
        f"{rel(APP_CONTAINER)} 未把两个拼音开关透传给 QuickLocator.buildComposite",
    )
    locator = read(QUICK_LOCATOR)
    check(
        "pinyin: Boolean = false" in locator and "fuzzyPinyin: Boolean = false" in locator
        and "kind = LookupKind.PINYIN" in locator,
        "QuickLocator.buildComposite 支持 pinyin/fuzzyPinyin 参数并挂 PINYIN 档位",
        f"{rel(QUICK_LOCATOR)} buildComposite 参数或 PINYIN 档位缺失",
    )

    # 繁体输出：服务侧读 settings.traditionalOutput + HanConversion 惰性装载
    check(
        "settings.traditionalOutput" in service and "HanConversion.toTraditional(text)" in service,
        "服务 convertHan 读 settings.traditionalOutput 并调用 HanConversion.toTraditional",
        f"{rel(SERVICE)} 未把繁体开关接到上屏转换",
    )
    han = read(HAN_CONVERSION)
    check(
        "AppContainer.applicationContext()" in han and "ensureInstalled" in han,
        "HanConversion 惰性装载（AppContainer 兜底），无需显式 install",
        f"{rel(HAN_CONVERSION)} 未实现惰性装载，繁体开关可能永远拿不到映射",
    )

    # 闭环缺口：运行中的服务是否会用上新引擎？
    # 机制：AppContainer 引擎代次自增 <- invalidateEngine()，服务比对代次重取引擎。
    generation_ok = (
        "fun engineGeneration()" in container
        and "engineGeneration++" in container
        and "refreshEngineIfStale()" in service
        and "refreshEngineIfStale" in service
        and "engine = latest" in service
    )
    check(
        generation_ok,
        "服务按引擎代次重取引擎（AppContainer.engineGeneration -> refreshEngineIfStale），"
        "改「拼音混输 / 模糊音」不必重启输入法进程",
        "服务只在 onCreate 取一次引擎：invalidateEngine() 只清缓存，"
        "运行中的输入法进程仍用旧引擎（新开/关「拼音混输 / 模糊音」需重启输入法进程才生效）",
    )


# ═════════════════════════════════════════════════════════════
# 5. 输入统计 + 导航
# ═════════════════════════════════════════════════════════════

def check_stats_chain() -> None:
    stats = read(INPUT_STATS)
    service = read(SERVICE)
    vm = read(STATS_VM)
    screen = read(STATS_SCREEN)
    nav = read(NAV_GRAPH)
    home = read(HOME_SCREEN)

    check(
        "fun get(context: Context)" in stats and "cached" in stats,
        "InputStats.get 进程内单例",
        f"{rel(INPUT_STATS)} 未提供带缓存的 InputStats.get",
    )
    check(
        "InputStats.get(this)" in service
        and "stats.recordCommit(" in service
        and "stats.recordQuery()" in service,
        "服务埋点：recordCommit / recordQuery 均落到 InputStats.get",
        f"{rel(SERVICE)} 统计埋点未接 InputStats",
    )
    check(
        "InputStats.get(" in vm,
        "StatsViewModel 读 InputStats.get",
        f"{rel(STATS_VM)} 未使用 InputStats.get",
    )
    check(
        "fun StatsScreen(onBack: () -> Unit)" in screen,
        "StatsScreen 签名与导航调用一致（onBack 参数）",
        f"{rel(STATS_SCREEN)} 签名与导航调用不一致",
    )
    check(
        'const val STATS = "stats"' in nav and "composable(IspeechRoutes.STATS)" in nav
        and "StatsScreen(onBack" in nav,
        "导航注册 STATS 路由 -> StatsScreen",
        f"{rel(NAV_GRAPH)} 缺 STATS 路由或未指向 StatsScreen",
    )
    check(
        "onOpenStats" in home and "onOpenStats" in nav and "输入统计" in home,
        "首页「输入统计」卡片 -> onOpenStats -> 导航 STATS",
        f"{rel(HOME_SCREEN)} / {rel(NAV_GRAPH)} 首页卡片与导航未接上",
    )


# ═════════════════════════════════════════════════════════════
# 6. PhraseUsageStore 单例 / 面板零 IO
# ═════════════════════════════════════════════════════════════

def check_phrase_usage_singleton() -> None:
    offenders = []
    for path in sorted(JAVA.rglob("*.kt")):
        if path == APP_CONTAINER:
            continue
        if "PhraseUsageStore.open(" in read(path):
            offenders.append(rel(path))
    check(
        not offenders,
        "PhraseUsageStore.open( 只在 AppContainer 出现（全局单例，避免整文件重写互相覆盖）",
        f"以下文件绕过 AppContainer 自行 open：{offenders}",
    )
    service = read(SERVICE)
    check(
        "AppContainer.phraseUsage(this)" in service,
        "服务通过 AppContainer.phraseUsage(this) 取单例",
        f"{rel(SERVICE)} 未通过 AppContainer.phraseUsage 取 store",
    )
    panel = read(QUICK_PANEL)
    check(
        "AppContainer" not in panel and "File(" not in panel
        and "readText" not in panel and "writeText" not in panel,
        "QuickPhrasePanel 零 IO / 零 AppContainer（暂停存与渲染分离）",
        f"{rel(QUICK_PANEL)} 出现 IO 或 AppContainer 依赖（应只回调服务）",
    )


# ═════════════════════════════════════════════════════════════
# 7. 口径一致性
# ═════════════════════════════════════════════════════════════

def check_consistency() -> None:
    # 7.1 版本号：代码常量 vs build.gradle
    gradle = read(BUILD_GRADLE)
    m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    gradle_ver = m.group(1) if m else "?"
    m2 = re.search(r'const val ISPEECH_VERSION\s*=\s*"([^"]+)"', read(MAIN_ACTIVITY))
    const_ver = m2.group(1) if m2 else "?"
    check(
        gradle_ver == const_ver and gradle_ver != "?",
        f"版本号一致：gradle versionName={gradle_ver} == ISPEECH_VERSION={const_ver}",
        f"版本号不一致：app/build.gradle.kts versionName={gradle_ver} 但 "
        f"{rel(MAIN_ACTIVITY)} ISPEECH_VERSION={const_ver}"
        "（设置页「关于 / 首页」会显示旧版本号）",
    )

    # 7.2 排序注释口径：CandidateRanking / CompositeEngine 的注释是否提到新档位
    for path in (LOOKUP_ENGINE, COMPOSITE_ENGINE):
        text = read(path)
        if "应用 < 短语" in text and "拼音" not in text.split("应用 < 短语", 1)[1][:40]:
            report(
                "WARN",
                f"{rel(path)} 排序注释仍写「应用 < 短语 < 词库单字 < 词库词组」，"
                "未包含新增的 PINYIN 档位（代码已含 5 档，注释少一档）",
            )
        else:
            report("PASS", f"{rel(path)} 排序注释包含 PINYIN 档位口径")

    # 7.3 源码里的 isFillViewport（HorizontalScrollView 的正确属性名）
    view = read(VIEW)
    check(
        "isFillViewport = true" in view and "fillViewport = true" not in view,
        "SoftKeyboardView 使用 HorizontalScrollView 的正确属性 isFillViewport",
        f"{rel(VIEW)} 出现编译不过的 `fillViewport`（应为 `isFillViewport`）",
    )
    t9_check = read(VERIFY_DIR / "check_t9.py")
    check(
        '"isFillViewport = true" in view' in t9_check,
        "check_t9.py 断言口径与源码一致（isFillViewport = true）",
        "check_t9.py 的断言字符串与源码不一致（断言会失败）",
    )

    # 7.4 校验脚本口径：check_input_logic expected_kinds 是否含 T9
    logic = read(VERIFY_DIR / "check_input_logic.py")
    m3 = re.search(r"expected_kinds\s*=\s*\{([^}]*)\}", logic)
    kinds = set(re.findall(r'"([A-Z0-9_]+)"', m3.group(1))) if m3 else set()
    declared = set(enum_members(read(KEYDEF), "KeyboardLayoutKind") or [])
    check(
        kinds == declared and "T9" in kinds,
        "check_input_logic.expected_kinds 与 KeyboardLayoutKind 声明一致（含 T9）",
        f"check_input_logic.expected_kinds={sorted(kinds)} 与声明 {sorted(declared)} 不一致",
    )
    if "四个值" in logic or "四个分支" in logic:
        report(
            "WARN",
            "check_input_logic.py 文案仍写「四个值 / 四个分支」，但断言已含 T9（文案滞后，仅措辞问题）",
        )

    # 7.5 layout xml 能解析（输入法根视图）
    try:
        ET.parse(LAYOUT_XML)
        report("PASS", f"{rel(LAYOUT_XML)} 可被 ElementTree 解析")
    except Exception as exc:  # noqa: BLE001
        report("FAIL", f"{rel(LAYOUT_XML)} XML 解析失败：{exc!r}")

    # 7.6 文档口径：t9-and-symbols.md 声明「T9 缓冲归主 Agent」是否已兑现
    doc = read(ROOT / "docs" / "t9-and-symbols.md")
    if doc and "T9 缓冲" in doc:
        service = read(SERVICE)
        if "T9Layouts" in service or "isT9CodeKey" in service:
            report("PASS", "docs/t9-and-symbols.md 声明的服务侧 T9 缓冲已兑现")
        else:
            report(
                "WARN",
                "docs/t9-and-symbols.md 第 4 节声明「服务侧 T9 缓冲归主 Agent」，"
                "但源码仍未实现（与本文档口径不符）",
            )


# ═════════════════════════════════════════════════════════════
# 8. 语音输入（VOICE / ASR）全链路
# ═════════════════════════════════════════════════════════════

def _named_strings(path: Path) -> set:
    """取 strings.xml 全部 <string name="..."> 的名字；解析失败返回空集。"""
    try:
        root = ET.parse(path).getroot()
    except Exception:  # noqa: BLE001
        return set()
    return {el.get("name") for el in root.findall("string") if el.get("name")}


def check_voice_chain() -> None:
    keydef = read(KEYDEF)
    view = read(VIEW)
    service = read(SERVICE)
    controller = read(VOICE_CONTROLLER)
    panel = read(SETTINGS_PANEL)
    ime_xml = read(LAYOUT_XML)
    manifest = read(MANIFEST)
    asr = read(ASR_SETTINGS)
    store = read(SETTINGS_STORE)
    nav = read(NAV_GRAPH)
    home = read(HOME_SCREEN)
    activity = read(MAIN_ACTIVITY)

    # 8.1 语音键：声明 -> 工具栏 -> 服务分支
    check(
        "VOICE" in (enum_members(keydef, "KeyAction") or []),
        "KeyAction 声明了 VOICE",
        f"{rel(KEYDEF)} 的 KeyAction 未声明 VOICE",
    )
    check(
        "KeyAction.VOICE" in view and '"🎤"' in view,
        "SoftKeyboardView 工具栏含 KeyAction.VOICE（「🎤」）",
        f"{rel(VIEW)} 工具栏未出现 KeyAction.VOICE「🎤」",
    )
    check(
        "KeyAction.VOICE -> toggleVoiceInput()" in service,
        "handleSoftKey 把 VOICE 接到 toggleVoiceInput()",
        f"{rel(SERVICE)} handleSoftKey 未把 VOICE 接到 toggleVoiceInput()",
    )

    # 8.1b 按住说话：三种引擎都要接线（系统引擎曾因早退直接失效）。
    #       引擎由 FunASR 切换（临时切换到 VOSK/VOCA-190 前的断线）恢复为 FunASR 默认并保留
    #       VOSK：按下按 asr.engine 分派到 FunAsrSpeechEngine / VoskSpeechEngine /
    #       VoiceInputController，松手由各自 finish() 收尾。
    down = function_region(service, r"private fun onVoicePressDown\(")
    up = function_region(service, r"private fun onVoicePressUp\(")
    down_tokens = (
        "if (asr.engine == AsrEngine.FUNASR)",
        "toggleFunAsrVoice()",
        "if (asr.engine == AsrEngine.VOSK)",
        "toggleVoskVoice()",
        "toggleVoiceInput()",
    )
    up_tokens = (
        "if (asr.engine == AsrEngine.FUNASR)",
        "funasrVoice?.finish()",
        "if (asr.engine == AsrEngine.VOSK)",
        "voskVoice?.finish()",
        "voiceController?.finish()",
    )
    missing = [f"按下缺 {t}" for t in down_tokens if t not in down]
    missing += [f"松手缺 {t}" for t in up_tokens if t not in up]
    check(
        not missing,
        "按住说话对三种引擎都接线（FunASR / VOSK / 系统识别）：按下开始收音、抬起收尾上屏",
        f"{rel(SERVICE)} 按住说话接线缺项：{missing}",
    )
    check(
        "fun finish()" in controller and "stopListening()" in controller,
        "VoiceInputController 提供 finish()：松手收尾但保留回调（结果仍要上屏）",
        f"{rel(VOICE_CONTROLLER)} 缺少 finish()：系统引擎按松手无法收尾上屏",
    )

    # 8.2 VoiceInputController：系统识别器的关键约束
    controller_tokens = (
        "class VoiceInputController",
        ": RecognitionListener",
        "SpeechRecognizer.isRecognitionAvailable",
        "createSpeechRecognizer(context, component)",
        "EXTRA_CALLING_PACKAGE",
        "ERROR_RECOGNIZER_BUSY",
        "recognizer?.destroy()",
    )
    missing = [t for t in controller_tokens if t not in controller]
    check(
        not missing,
        "VoiceInputController 覆盖系统识别器 / 忙重试 / 释放麦克风 / 宿主包名 / 自定义组件",
        f"{rel(VOICE_CONTROLLER)} 缺少关键实现：{missing}",
    )

    # 8.3 服务接线：创建 / 销毁 / 语音态上屏与取消
    service_tokens = (
        "VoiceInputController(applicationContext)",
        "voiceController?.destroy()",
        "voiceActive",
        "private fun commitVoice()",
        "private fun cancelVoice()",
        'codeOverride = "语音"',
    )
    missing = [t for t in service_tokens if t not in service]
    check(
        not missing,
        "服务接线完整：创建 / 销毁 Controller + 语音候选上屏 / 取消 + 编码区「语音」",
        f"{rel(SERVICE)} 语音接线缺项：{missing}",
    )

    # 8.4 权限：Manifest 声明 + 包可见性 + 设置页代申请
    check(
        "android.permission.RECORD_AUDIO" in manifest
        and "android.speech.RecognitionService" in manifest
        and "<queries>" in manifest,
        "Manifest 声明 RECORD_AUDIO 且 queries 暴露 RecognitionService（targetSdk 34 包可见性）",
        f"{rel(MANIFEST)} 缺 RECORD_AUDIO 或 queries/RecognitionService",
    )
    check(
        "REQUEST_MIC_EXTRA" in service and "requestMicPermission()" in service,
        "输入法服务未授权时请求麦克风（requestMicPermission -> 设置页 extra）",
        f"{rel(SERVICE)} 未实现麦克风代申请请求",
    )
    check(
        "REQUEST_MIC_EXTRA" in activity
        and "requestMicrophonePermissionIfNeeded" in activity
        and "Manifest.permission.RECORD_AUDIO" in activity,
        "设置页按 REQUEST_MIC_EXTRA 拉起系统麦克风授权（输入法无法自弹对话框）",
        f"{rel(MAIN_ACTIVITY)} 未实现 REQUEST_MIC_EXTRA 代申请",
    )

    # 8.5 ASR 设置模型 + DataStore 读写
    check(
        {"SYSTEM", "CUSTOM"} <= set(enum_members(asr, "AsrEngine") or []),
        "AsrEngine 含 SYSTEM / CUSTOM（系统识别器 / 指定服务组件）",
        f"{rel(ASR_SETTINGS)} 的 AsrEngine 缺 SYSTEM / CUSTOM",
    )
    check(
        {"FUNASR", "VOSK"} <= set(enum_members(asr, "AsrEngine") or []),
        "AsrEngine 含 FUNASR / VOSK（两种内置离线识别引擎都保留）",
        f"{rel(ASR_SETTINGS)} 的 AsrEngine 缺 FUNASR / VOSK",
    )
    check(
        "val engine: AsrEngine = AsrEngine.FUNASR" in asr
        and "?:" in asr
        and "FUNASR" in asr,
        "AsrSettings 默认引擎为 FUNASR（parse() 未存过 / 不识别时也回落 FUNASR）",
        f"{rel(ASR_SETTINGS)} 默认引擎未设为 FUNASR 或 parse() 未回落 FUNASR",
    )
    check(
        "ASR_MODEL_EXTRA" in asr and "ZH_CN" in asr and '"zh-CN"' in asr,
        "AsrSettings 含模型透传 extra 与 zh-CN 默认语言",
        f"{rel(ASR_SETTINGS)} 缺 ASR_MODEL_EXTRA / zh-CN 语言取值",
    )
    store_tokens = (
        "KEY_ASR_ENGINE",
        "KEY_ASR_LANGUAGE",
        "KEY_ASR_PARTIAL",
        "asrSettingsFlow(",
        "setAsrSettings(",
    )
    missing = [t for t in store_tokens if t not in store]
    check(
        not missing,
        "SettingsStore 提供 ASR 持久化键与 asrSettingsFlow / setAsrSettings 读写",
        f"{rel(SETTINGS_STORE)} 的 ASR 读写缺项：{missing}",
    )

    # 8.6 设置页闭环：导航路由 + 首页卡片 + 屏幕 / ViewModel
    check(
        'const val ASR = "asr"' in nav and "AsrSettingsScreen" in nav,
        "设置导航注册 ASR 路由并挂载 AsrSettingsScreen",
        f"{rel(NAV_GRAPH)} 未注册 ASR 路由",
    )
    check(
        "onOpenAsr" in home and "R.string.asr_title" in home and "R.string.asr_description" in home,
        "首页 ASR 卡片接 onOpenAsr 并显示 asr_title / asr_description",
        f"{rel(HOME_SCREEN)} 未接入 ASR 入口卡片",
    )
    check(
        "AsrSettingsViewModel" in read(ASR_SCREEN)
        and "setAsrSettings" in read(ASR_VM),
        "AsrSettingsScreen 经 ViewModel 保存（setAsrSettings）到 DataStore",
        f"{rel(ASR_SCREEN)} / {rel(ASR_VM)} 未闭环到 setAsrSettings",
    )

    # 8.7 输入法内快捷面板：ASR 分项 + 服务绑定
    panel_tokens = ("onAsrChange", "AsrEngine", "AsrLanguage", "AsrSettings")
    missing = [t for t in panel_tokens if t not in panel]
    check(
        not missing,
        "SettingsPanel 含 ASR 分项（引擎 / 语言 / 参数）并暴露 onAsrChange",
        f"{rel(SETTINGS_PANEL)} 的 ASR 分项缺项：{missing}",
    )
    check(
        "settings_panel" in xml_ids(LAYOUT_XML)
        and "com.ispeech.input.SettingsPanel" in ime_xml,
        "layout ime_input_view.xml 声明 @+id/settings_panel（SettingsPanel）",
        f"{rel(LAYOUT_XML)} 缺 @+id/settings_panel 或 SettingsPanel 标签",
    )
    check(
        "panelSettings.onAsrChange" in service and "applyAsrChange" in service,
        "服务绑定 settingsPanel.onAsrChange -> applyAsrChange（设置即时生效）",
        f"{rel(SERVICE)} 未把快捷面板 ASR 改动写回",
    )

    # 8.8 中英文案：语音 / ASR 键名齐全且一致
    zh = _named_strings(STRINGS)
    en = _named_strings(STRINGS_EN)
    required = {
        "voice_listening", "voice_need_permission", "voice_no_match",
        "voice_error", "voice_cancel", "voice_unavailable",
        "asr_title", "asr_description", "asr_engine_system", "asr_engine_custom",
        "asr_engine_funasr", "asr_engine_vosk",
        "asr_partial_desc", "asr_offline_desc", "asr_saved", "asr_save_failed",
    }
    missing = sorted(k for k in required if k not in zh or k not in en)
    check(
        not missing,
        f"语音 / ASR 文案中英各齐 {len(required)} 项",
        f"中英 strings.xml 缺语音 / ASR 文案：{missing}",
    )
    zh_pairs = {s for s in zh if s.startswith(("voice_", "asr_"))}
    en_pairs = {s for s in en if s.startswith(("voice_", "asr_"))}
    check(
        zh_pairs == en_pairs,
        f"语音 / ASR 文案中英键名一致（{len(zh_pairs)} 项）",
        f"中英键名不一致：仅中文 {sorted(zh_pairs - en_pairs)}，仅英文 {sorted(en_pairs - zh_pairs)}",
    )


# ═════════════════════════════════════════════════════════════
# 9. 用户词库（模式维度 / 自造词 / 拼音态）
# ═════════════════════════════════════════════════════════════


def _user_dict_unescape(text: str) -> str:
    """复刻 UserDictStore.unescapeLineText（\\n / \\r / \\t / \\\\）。"""
    if "\\" not in text:
        return text
    out: list = []
    i = 0
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else None
        if ch == "\\" and nxt is not None:
            if nxt == "n":
                out.append("\n")
                i += 2
                continue
            if nxt == "r":
                out.append("\r")
                i += 2
                continue
            if nxt == "t":
                out.append("\t")
                i += 2
                continue
            if nxt == "\\":
                out.append("\\")
                i += 2
                continue
            out.append(ch)
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _build_user_dict_entry(code: str, word: str, count: int, mode: str):
    """复刻 buildUserDictEntry：编码归一成小写 ASCII 字母 / 数字，空码空词丢弃。"""
    normalized = "".join(
        ch for ch in _user_dict_unescape(code).lower() if ch.isascii() and ch.isalnum()
    )
    text = _user_dict_unescape(word)
    if not normalized or not text:
        return None
    return mode, normalized, text, max(0, count)


def _parse_user_dict_line(line: str, default_mode: str = "wb"):
    """复刻 UserDictStore.parseUserDictLine → (mode, code, text, count) 或 None。"""
    trimmed = line.strip()
    if not trimmed or trimmed.startswith("#"):
        return None
    if "\t" not in trimmed:
        parts = trimmed.split()
        if len(parts) != 2:
            return None
        return _build_user_dict_entry(parts[0], parts[1], 1, default_mode)
    fields = trimmed.split("\t")
    if len(fields) < 2:
        return None
    mode_cell = fields[-1].strip().lower()
    has_mode = (
        len(fields) >= 4
        and mode_cell in ("wb", "cn")
        and fields[-2].strip().isdigit()
    )
    mode = mode_cell if has_mode else default_mode
    if has_mode:
        text_end = len(fields) - 2
        count = int(fields[text_end].strip())
    elif len(fields) == 2:
        text_end = 2
        count = 1
    elif fields[-1].strip().isdigit():
        text_end = len(fields) - 1
        count = int(fields[-1].strip())
    else:
        return None
    return _build_user_dict_entry(fields[0], "\t".join(fields[1:text_end]), count, mode)


def check_user_dict_chain() -> None:
    store = read(USER_DICT_STORE)
    engine = read(USER_DICT_ENGINE)
    service = read(SERVICE)
    view = read(CANDIDATE_VIEW)
    container = read(APP_CONTAINER)
    locator = read(QUICK_LOCATOR)
    keyboard_vm = read(KEYBOARD_VM)

    # 9.1 模式枚举：wb / cn，命名对齐搜狗 wb_dict_user / cn_dict_user
    check(
        "enum class UserDictMode" in store and 'WUBI("wb"' in store and 'PINYIN("cn"' in store,
        "UserDictMode 声明 WUBI(wb) / PINYIN(cn)（对齐搜狗 wb_dict_user / cn_dict_user）",
        f"{rel(USER_DICT_STORE)} 未声明 UserDictMode(wb/cn)",
    )
    check(
        "fun parse(raw: String?)" in store and "else -> WUBI" in store,
        "UserDictMode.parse 缺列 / 未知值兜底五笔",
        f"{rel(USER_DICT_STORE)} 缺少 UserDictMode.parse 兜底",
    )

    # 9.2 文件格式：写入 mode 列；历史 3 列仍按五笔读入（用真实解析复刻断言）
    check(
        ".append(entry.mode.id)" in store,
        "persist 写出第 4 列 mode",
        f"{rel(USER_DICT_STORE)} persist 未写出 mode 列",
    )
    legacy = _parse_user_dict_line("ggll\t你\t3")
    four = _parse_user_dict_line("jiudian\t酒店\t5\tcn")
    tabbed = _parse_user_dict_line("ggll\ta\tb\t3")
    bad = _parse_user_dict_line("ggll\t你\tabc\tcn")
    check(
        legacy == ("wb", "ggll", "你", 3),
        "历史 3 列行仍解析为 wb（不丢老数据）",
        f"3 列解析错误：{legacy}",
    )
    check(
        four == ("cn", "jiudian", "酒店", 5),
        "4 列行解析出 cn 模式",
        f"4 列解析错误：{four}",
    )
    check(
        tabbed is not None and tabbed[0] == "wb" and tabbed[2] == "a\tb",
        "文本含未转义制表符的老数据仍按 3 列解析（mode 列不会被误判）",
        f"含制表符老数据解析错误：{tabbed}",
    )
    check(
        bad is None,
        "计数非法时整行跳过（4 列亦然）",
        f"非法计数未被跳过：{bad}",
    )
    shorthand = _parse_user_dict_line("jiudian\t酒店")
    spaced = _parse_user_dict_line("ggll 你")
    normalized = _parse_user_dict_line("Jiu Dian\t酒店\t2\tcn")
    targeted = _parse_user_dict_line("nihao\t你好", "cn")
    check(
        shorthand == ("wb", "jiudian", "酒店", 1),
        "2 列简写按次数 1 导入（导入格式）",
        f"2 列简写解析错误：{shorthand}",
    )
    check(
        spaced == ("wb", "ggll", "你", 1),
        "「编码 词条」空白分隔两段式可导入",
        f"空白分隔解析错误：{spaced}",
    )
    check(
        normalized == ("cn", "jiudian", "酒店", 2),
        "编码归一：大小写 / 音节间空格被清成拼音串（jiudian）",
        f"编码归一错误：{normalized}",
    )
    check(
        targeted == ("cn", "nihao", "你好", 1),
        "无模式列的行落到导入时选的词库（cn）",
        f"默认模式未生效：{targeted}",
    )
    check(
        store.count("internal fun parseUserDictLine") == 1
        and "parseUserDictLine(rawLine, UserDictMode.WUBI)" in store
        and "parseUserDictLine(rawLine, defaultMode)" in store,
        "读盘与导入共用同一套行解析（只有一处实现）",
        f"{rel(USER_DICT_STORE)} 存在第二套解析规则（读盘 / 导入会漂移）",
    )

    # 9.3 引擎按模式过滤
    check(
        "private val mode: UserDictMode = UserDictMode.WUBI" in engine,
        "UserDictEngine 持有 mode（默认五笔）",
        f"{rel(USER_DICT_ENGINE)} 未按模式读用户词库",
    )
    check(
        "store.lookupCandidates(input, limit, mode)" in engine
        and "store.related(text, limit, mode)" in engine,
        "UserDictEngine 把 mode 透传给 store 的查询与联想",
        f"{rel(USER_DICT_ENGINE)} 查询 / 联想未透传 mode",
    )

    # 9.4 拼音态查询含用户词库 cn
    check(
        "fun pinyinLookupEngine(context: Context): CompositeEngine" in container
        and "UserDictEngine(userDict(appCtx), UserDictMode.PINYIN)" in container,
        "AppContainer.pinyinLookupEngine = 用户词库 cn + 拼音词表",
        f"{rel(APP_CONTAINER)} 未提供 pinyinLookupEngine（用户词库 cn）",
    )
    check(
        "AppContainer.pinyinLookupEngine(this)" in service,
        "拼音态 lookupSource 走 pinyinLookupEngine",
        f"{rel(SERVICE)} 拼音态未接入用户词库 cn",
    )
    check(
        "pinyinLookupCache = null" in container,
        "invalidateEngine 同时丢弃 pinyinLookupEngine 缓存",
        f"{rel(APP_CONTAINER)} invalidateEngine 未清 pinyinLookupCache",
    )

    # 9.5 自造词：长按 -> 服务记词（mode 由候选来源决定）
    check(
        "onCandidateLongPressed" in view and "setOnLongClickListener" in view,
        "CandidateView 暴露长按回调（自造词入口）",
        f"{rel(CANDIDATE_VIEW)} 缺少长按回调",
    )
    check(
        "bar.onCandidateLongPressed = { index -> rememberCandidate(index) }" in service,
        "服务把长按接到 rememberCandidate（自造词）",
        f"{rel(SERVICE)} 未接线 rememberCandidate",
    )
    check(
        "store.record(code, candidate.insertText, dictMode)" in service,
        "rememberCandidate 按 dictMode 写入用户词库",
        f"{rel(SERVICE)} rememberCandidate 未按模式写入",
    )

    # 9.6 学习分流：拼音不再整体禁用；短语 / 应用不写词库
    learn_region = function_region(service, r"private fun learn\(candidate: LookupCandidate\)")
    check(
        "mode == Mode.PINYIN) return" not in learn_region,
        "learn 不再整体禁用拼音态（改为按候选来源分流）",
        f"{rel(SERVICE)} learn 仍整体禁用拼音学习",
    )
    check(
        "dictModeOf(candidate.kind)" in learn_region,
        "learn 用 dictModeOf 分流模式",
        f"{rel(SERVICE)} learn 未用 dictModeOf",
    )
    mode_region = function_region(service, r"private fun dictModeOf\(kind: LookupKind\)")
    check(
        all(
            f"LookupKind.{name} ->" in mode_region
            for name in ("WUBI", "WUBIPHRASE", "PINYIN", "PHRASE", "QUICKAPPLY")
        ),
        "dictModeOf 覆盖全部 LookupKind（短语 / 应用 -> null）",
        f"{rel(SERVICE)} dictModeOf 分支不全",
    )
    check(
        mode_region.count("-> null") == 2,
        "dictModeOf 只让 PHRASE / QUICKAPPLY 落到 null（不写词库）",
        f"dictModeOf 返回 null 的分支数不是 2：{mode_region.count('-> null')}",
    )

    # 9.7 设置页删除带模式；快捷定位器可指定模式
    check(
        "store.remove(entry.code, entry.text, entry.mode)" in keyboard_vm,
        "设置页删除用户词条时带上模式",
        f"{rel(KEYBOARD_VM)} 删除未传 entry.mode（拼音词会删不掉）",
    )
    check(
        "userDictMode: UserDictMode = UserDictMode.WUBI" in locator,
        "QuickLocator.buildComposite 支持指定用户词库模式",
        f"{rel(QUICK_LOCATOR)} 未支持 userDictMode",
    )

    # 9.8 导入 / 导出（S2）：store -> ViewModel -> SAF 选择器 -> 文案
    dictionary_vm = read(DICTIONARY_VM)
    dictionary_screen = read(DICTIONARY_SCREEN)
    check(
        "fun exportText(): String" in store
        and "fun importText(text: String, defaultMode: UserDictMode = UserDictMode.WUBI)" in store,
        "UserDictStore 提供 exportText / importText",
        f"{rel(USER_DICT_STORE)} 缺少导入 / 导出接口",
    )
    check(
        "existing == null ->" in store and "parsed.count > existing.count ->" in store,
        "导入按 mode+code+text 合并，已存在取较大 count",
        f"{rel(USER_DICT_STORE)} 导入合并规则缺失",
    )
    check(
        "contentResolver.openInputStream(uri)" in dictionary_vm
        and "contentResolver.openOutputStream(uri)" in dictionary_vm
        and "importText(text, mode)" in dictionary_vm
        and "exportText()" in dictionary_vm,
        "DictionaryViewModel 走 contentResolver 读 / 写并调用 store",
        f"{rel(DICTIONARY_VM)} 未接线导入 / 导出",
    )
    check(
        "ActivityResultContracts.OpenDocument()" in dictionary_screen
        and 'CreateDocument("text/plain")' in dictionary_screen
        and "viewModel.importUserWords(it, importMode)" in dictionary_screen
        and "viewModel.exportUserWords(it)" in dictionary_screen,
        "词典页接上 SAF 文件选择器（导入 / 导出）",
        f"{rel(DICTIONARY_SCREEN)} 未接文件选择器",
    )
    check(
        "R.string.dict_import_hint" in dictionary_screen,
        "词典页把孤儿字符串 dict_import_hint 接上（不再悬空）",
        f"{rel(DICTIONARY_SCREEN)} 未使用 dict_import_hint",
    )
    zh = _named_strings(STRINGS)
    en = _named_strings(STRINGS_EN)
    dict_keys = (
        "dict_user_title",
        "dict_user_count",
        "dict_import_wubi",
        "dict_import_pinyin",
        "dict_export",
        "dict_import_result",
        "dict_import_failed",
        "dict_export_failed",
        "dict_import_hint",
    )
    missing_dict = sorted(k for k in dict_keys if k not in zh or k not in en)
    check(
        not missing_dict,
        f"用户词库导入 / 导出文案中英各齐 {len(dict_keys)} 项",
        f"中英 strings.xml 缺词库文案：{missing_dict}",
    )


# ═════════════════════════════════════════════════════════════
# 10. 分类词库（细胞词库）
# ═════════════════════════════════════════════════════════════


def _fnv1a64(text: str) -> str:
    """复刻 CellDictStore.fnv1a64（FNV-1a 64 位，UTF-8 字节序，16 位小写十六进制）。"""
    h = 0xCBF29CE484222325
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return format(h, "016x")


def _cell_dict_id(name: str, content: str) -> str:
    """复刻 CellDictStore.cellDictId：`c1-` + FNV-1a64(显示名 + \\u0001 + 正文)。"""
    return "c1-" + _fnv1a64(name + "\u0001" + content)


def _parse_cell_index_line(line: str):
    """复刻 CellDictStore.parseCellDictIndexLine → (id, name, category, enabled) 或 None。"""
    trimmed = line.strip()
    if not trimmed or trimmed.startswith("#"):
        return None
    fields = trimmed.split("\t")
    if len(fields) < 4:
        return None
    cid = fields[0].strip()
    if not cid:
        return None
    flag = fields[3].strip()
    if flag not in ("0", "1"):
        return None
    return (
        cid,
        _user_dict_unescape(fields[1]).strip(),
        _user_dict_unescape(fields[2]).strip(),
        flag == "1",
    )


def _parse_cell_category(text: str):
    """复刻 CellDictStore.parseCellDictCategory → 分类名或 None（认 `# category: X`）。"""
    for line in text.split("\n"):
        trimmed = line.strip()
        if not trimmed.startswith("#"):
            continue
        body = trimmed[1:].strip()
        if not body.lower().startswith("category"):
            continue
        rest = body[len("category"):].strip().lstrip(":：").strip()
        if not rest:
            continue
        return rest[:32]
    return None


def check_cell_dict_chain() -> None:
    cell_store = read(CELL_DICT_STORE)
    cell_engine = read(CELL_DICT_ENGINE)
    locator = read(QUICK_LOCATOR)
    container = read(APP_CONTAINER)
    dictionary_vm = read(DICTIONARY_VM)
    dictionary_screen = read(DICTIONARY_SCREEN)

    # 10.1 存储：目录 / 索引文件 / 正文解析复用用户词库（不做第二套解析）
    check(
        "class CellDictStore private constructor" in cell_store
        and 'INDEX_FILE_NAME = "index.txt"' in cell_store,
        "CellDictStore 以 cell_dicts/index.txt 为唯一索引",
        f"{rel(CELL_DICT_STORE)} 缺少索引文件声明",
    )
    check(
        "parseUserDictLine(rawLine, UserDictMode.WUBI)" in cell_store
        and "internal fun parseUserDictLine" not in cell_store,
        "分类词库正文复用用户词库行解析（无第二套规则）",
        f"{rel(CELL_DICT_STORE)} 未复用 parseUserDictLine（解析规则会漂移）",
    )
    check(
        all(
            marker in cell_store
            for marker in (
                "fun list(): List<CellDictInfo>",
                "fun entries(enabledOnly: Boolean = true)",
                "fun add(name: String, content: String)",
                "fun remove(id: String): Boolean",
                "fun setEnabled(id: String, enabled: Boolean): Boolean",
            )
        ),
        "CellDictStore 提供 列表 / 取词条 / 安装 / 删除 / 启停",
        f"{rel(CELL_DICT_STORE)} 缺少安装 / 启停 / 删除接口",
    )

    # 10.2 索引行解析：合法 4 列可读；注释 / 列数不足 / 启用位非法一律跳过（复刻断言）
    good = _parse_cell_index_line("c1-0123456789abcdef\t医学词库\t医学\t1")
    disabled = _parse_cell_index_line("c1-0123456789abcdef\t医学词库\t医学\t0")
    commented = _parse_cell_index_line("# 分类词库（细胞词库）索引")
    short = _parse_cell_index_line("c1-abcdef\t医学词库\t医学")
    bad_flag = _parse_cell_index_line("c1-abcdef\t医学词库\t医学\t2")
    blank_id = _parse_cell_index_line("\t医学词库\t医学\t1")
    check(
        good == ("c1-0123456789abcdef", "医学词库", "医学", True),
        "索引行解析出 id / 显示名 / 分类 / 启用",
        f"索引行解析错误：{good}",
    )
    check(
        disabled is not None and disabled[3] is False,
        "启用位 0 解析为停用",
        f"启用位 0 解析错误：{disabled}",
    )
    check(
        commented is None and short is None and bad_flag is None and blank_id is None,
        "注释 / 缺列 / 非法启用位 / 空 id 的索引行全部跳过（孤儿行不炸）",
        f"坏索引行未被跳过：{commented}, {short}, {bad_flag}, {blank_id}",
    )

    # 10.3 稳定 id：同名同正文重复安装命中同 id，正文不同则不同 id
    same_a = _cell_dict_id("医学词库", "xie\t血\twb\n")
    same_b = _cell_dict_id("医学词库", "xie\t血\twb\n")
    other = _cell_dict_id("医学词库", "xue\t血\tcn\n")
    check(
        same_a == same_b and same_a.startswith("c1-") and len(same_a) == 19 and same_a != other,
        "词库 id = c1- + FNV-1a64(名 + \\u0001 + 正文)（重复安装幂等，不同正文不撞号）",
        f"id 计算错误：{same_a} / {same_b} / {other}",
    )
    check(
        '"c1-" + fnv1a64(name + "\\u0001" + content)' in cell_store
        and "internal fun cellDictId(name: String, content: String)" in cell_store,
        "id 生成只有一处实现（cellDictId）",
        f"{rel(CELL_DICT_STORE)} 缺少 cellDictId 单一实现",
    )

    # 10.4 分类头：`# category: 医学` 可读，读不到回落界面文案
    check(
        _parse_cell_category("# category: 医学\nxie\t血") == "医学"
        and _parse_cell_category("xie\t血") is None
        and 'CATEGORY_KEY = "category"' in cell_store,
        "分类头 `# category: X` 解析一致（读不到返回 null）",
        "分类头解析与实现不一致",
    )
    check(
        "if (enabledOnly && !meta.enabled) continue" in cell_store,
        "entries(enabledOnly) 真的过滤掉停用词库",
        f"{rel(CELL_DICT_STORE)} 未按启用位过滤词条",
    )

    # 10.5 引擎：LookupEngine + AssociationSource，按模式过滤，权重固定 8 万
    check(
        "class CellDictEngine(" in cell_engine
        and ": LookupEngine, AssociationSource" in cell_engine,
        "CellDictEngine 实现 LookupEngine + AssociationSource",
        f"{rel(CELL_DICT_ENGINE)} 未实现查询 / 联想接口",
    )
    check(
        cell_engine.count("if (entry.mode != mode) continue") == 2,
        "CellDictEngine 查询与联想都按模式过滤（wb / cn 编码空间隔离）",
        f"{rel(CELL_DICT_ENGINE)} 未按模式过滤（查到 {cell_engine.count('if (entry.mode != mode) continue')} 处）",
    )
    check(
        "internal const val CELL_DICT_WEIGHT = 80_000" in cell_engine
        and "UserDictMode.WUBI -> if (text.length == 1) LookupKind.WUBI else LookupKind.PHRASE" in cell_engine
        and "UserDictMode.PINYIN -> LookupKind.PINYIN" in cell_engine,
        "权重 8 万（让位用户词 / 压过系统词），档位与用户词库一致",
        f"{rel(CELL_DICT_ENGINE)} 权重或档位映射缺失",
    )
    check(
        100_000 > 80_000 > 65_535 > 9_999,
        "权重关系自洽：用户词(10万) > 分类词库(8万) > 系统五笔词(65535) > 拼音词(9999)",
        "权重关系不自洽",
    )

    # 10.6 接线：QuickLocator 组装 + AppContainer 单例与引擎重建
    check(
        "includeCellDicts: Boolean = true" in locator
        and "cellDict: CellDictStore? = null" in locator
        and "CellDictEngine(cells, userDictMode)" in locator,
        "QuickLocator.buildComposite 并入已启用的分类词库",
        f"{rel(QUICK_LOCATOR)} 未组装分类词库引擎",
    )
    check(
        'CELL_DICT_DIR_NAME = "cell_dicts"' in container
        and "fun cellDict(context: Context): CellDictStore" in container
        and "fun invalidateCellDict()" in container,
        "AppContainer 提供 cellDict 单例与 invalidateCellDict",
        f"{rel(APP_CONTAINER)} 缺少分类词库单例",
    )
    check(
        "cellDict = cellDict(ctx)" in container
        and "CellDictEngine(cells, UserDictMode.PINYIN)" in container,
        "五笔态（buildEngineFromSettings）与拼音态（pinyinLookupEngine）都接上分类词库",
        f"{rel(APP_CONTAINER)} 分类词库未接入引擎构建",
    )
    check(
        "invalidateEngine()" in function_region(container, r"fun invalidateCellDict\(\)"),
        "invalidateCellDict 同时让查询引擎重建（装了词库立刻生效）",
        f"{rel(APP_CONTAINER)} invalidateCellDict 未失效引擎缓存",
    )

    # 10.7 词典页：SAF 安装 + 启停 / 删除 + 引擎重建
    check(
        "fun addCellDict(uri: Uri)" in dictionary_vm
        and "fun removeCellDict(id: String)" in dictionary_vm
        and "fun setCellDictEnabled(id: String, enabled: Boolean)" in dictionary_vm,
        "DictionaryViewModel 提供 安装 / 删除 / 启停",
        f"{rel(DICTIONARY_VM)} 缺少分类词库操作",
    )
    check(
        "AppContainer.cellDict(app).add(cellDictName(uri), text)" in dictionary_vm
        and "contentResolver.openInputStream(uri)" in dictionary_vm
        and "AppContainer.invalidateCellDict()" in dictionary_vm,
        "词典页走 contentResolver 读文件并落库 + 失效缓存",
        f"{rel(DICTIONARY_VM)} 未接线分类词库安装",
    )
    check(
        "cellAddLauncher" in dictionary_screen
        and "viewModel.addCellDict(it)" in dictionary_screen
        and "viewModel.setCellDictEnabled(info.id, it)" in dictionary_screen
        and "viewModel.removeCellDict(info.id)" in dictionary_screen,
        "词典页接上 SAF 安装入口与每库开关 / 删除",
        f"{rel(DICTIONARY_SCREEN)} 未接分类词库 UI",
    )
    check(
        "private fun CellDictRow(" in dictionary_screen
        and "R.string.dict_cell_add" in dictionary_screen,
        "词典页新增分类词库行（名称 / 分类 / 词条数 + 开关）",
        f"{rel(DICTIONARY_SCREEN)} 缺少分类词库行",
    )
    zh = _named_strings(STRINGS)
    en = _named_strings(STRINGS_EN)
    cell_keys = (
        "dict_cell_title",
        "dict_cell_hint",
        "dict_cell_add",
        "dict_cell_empty",
        "dict_cell_entry_count",
        "dict_cell_category_unknown",
        "dict_cell_added",
        "dict_cell_updated",
        "dict_cell_add_failed",
        "dict_cell_remove",
    )
    missing_cell = sorted(k for k in cell_keys if k not in zh or k not in en)
    check(
        not missing_cell,
        f"分类词库文案中英各齐 {len(cell_keys)} 项",
        f"中英 strings.xml 缺分类词库文案：{missing_cell}",
    )
    unused_cell = sorted(k for k in cell_keys if f"R.string.{k}" not in dictionary_screen)
    check(
        not unused_cell,
        f"分类词库文案全部接线（{len(cell_keys)} 项无孤儿）",
        f"分类词库文案定义了但没人用：{unused_cell}",
    )


# ═════════════════════════════════════════════════════════════
# 11. 中文输入法（默认拼音 / 五笔 + 中英切换）
# ═════════════════════════════════════════════════════════════

def check_chinese_input_method() -> None:
    """新增中文输入法设置：设置项 -> 持久化 -> UI / 内置面板 -> 中英切换策略。"""
    enum_src = read(CHINESE_INPUT_METHOD)
    check(
        "enum class ChineseInputMethod" in enum_src
        and 'PINYIN("拼音")' in enum_src
        and 'WUBI("五笔")' in enum_src
        and "?: PINYIN" in enum_src,
        "ChineseInputMethod 枚举含拼音 / 五笔，未知值回落拼音",
        f"{rel(CHINESE_INPUT_METHOD)} 枚举或默认值缺失",
    )

    store = read(SETTINGS_STORE)
    check(
        'stringPreferencesKey("input_method_mode")' in store
        and "fun chineseInputMethodFlow(" in store
        and "ChineseInputMethod.parse(" in store
        and "fun setChineseInputMethod(" in store
        and "fun chineseInputMethod(" in store,
        "SettingsStore 提供中文输入法 flow / 阻塞读取 / 写入（沿用旧 key）",
        f"{rel(SETTINGS_STORE)} 中文输入法持久化未闭环",
    )

    vm = read(KEYBOARD_VM)
    check(
        "chineseInputMethodFlow(app)" in vm
        and "val chineseInputMethod" in vm
        and "fun setChineseInputMethod(" in vm,
        "KeyboardViewModel 订阅并暴露中文输入法设置",
        f"{rel(KEYBOARD_VM)} 未接中文输入法状态",
    )

    screen = read(KEYBOARD_SCREEN)
    check(
        'KeyboardCardTitle("中文输入法")' in screen
        and "ChineseInputMethod.entries" in screen
        and "viewModel.setChineseInputMethod(it)" in screen,
        "设置页新增「中文输入法」卡片并接单选",
        f"{rel(KEYBOARD_SCREEN)} 缺少中文输入法卡片",
    )

    panel = read(SETTINGS_PANEL)
    check(
        "onChineseInputMethodChange" in panel
        and "R.string.panel_chinese_input_method" in panel
        and "ChineseInputMethod.entries" in panel,
        "键盘内置设置面板新增中文输入法切换行",
        f"{rel(SETTINGS_PANEL)} 未接中文输入法行",
    )

    service = read(SERVICE)
    check(
        "InputModePolicy.toggle(mode, chineseInputMethod)" in service
        and "InputModePolicy.chineseMode(chineseInputMethod)" in service
        and "applyChineseInputMethodChange" in service,
        "输入法服务按设置做「中文 ⇄ 英文」两态切换",
        f"{rel(SERVICE)} 未接 InputModePolicy",
    )
    check(
        "InputMode.WUBI -> InputMode.PINYIN" not in service,
        "旧的三态循环（五笔 → 拼音 → 英文）已移除",
        f"{rel(SERVICE)} 仍保留三态循环",
    )

    policy = read(INPUT_MODE)
    check(
        "enum class InputMode" in policy
        and "object InputModePolicy" in policy
        and "if (current == InputMode.ENGLISH) chineseMode(method) else InputMode.ENGLISH" in policy,
        "InputModePolicy 只做中文态 / 英文态两态切换",
        f"{rel(INPUT_MODE)} 切换策略缺失",
    )

    test = read(INPUT_MODE_TEST)
    check(
        "InputModePolicy.toggle(InputMode.WUBI, ChineseInputMethod.WUBI)" in test
        and "InputModePolicy.toggle(InputMode.ENGLISH, ChineseInputMethod.WUBI)" in test
        and "ChineseInputMethod.PINYIN" in test,
        "回归测试覆盖默认五笔 / 默认拼音两条中英切换路径",
        f"{rel(INPUT_MODE_TEST)} 缺少中英切换回归用例",
    )

    zh = _named_strings(STRINGS)
    en = _named_strings(STRINGS_EN)
    keys = (
        "panel_chinese_input_method",
        "panel_chinese_input_pinyin",
        "panel_chinese_input_wubi",
    )
    missing = sorted(k for k in keys if k not in zh or k not in en)
    check(
        not missing,
        "中文输入法内置面板文案中英各齐",
        f"中英 strings.xml 缺中文输入法文案：{missing}",
    )


# ═════════════════════════════════════════════════════════════
# 12. 候选条字重（VOCA-56）：候选词不加粗
# ═════════════════════════════════════════════════════════════

def check_candidate_style() -> None:
    view = read(CANDIDATE_VIEW)
    item_region = function_region(view, r"private fun createItemView\(")

    # 11.1 候选文本用常规字重（Typeface.DEFAULT），不能再回到 DEFAULT_BOLD
    check(
        "typeface = Typeface.DEFAULT_BOLD" not in item_region
        and "typeface = Typeface.DEFAULT" in item_region,
        "候选词文本常规字重（createItemView 用 Typeface.DEFAULT）",
        f"{rel(CANDIDATE_VIEW)} createItemView 仍把候选词设为粗体"
        "（VOCA-56 要求候选词不加粗）",
    )

    # 11.2 首选 / 非首选只靠颜色 + 底色区分，字重保持一致。
    #      首选色跟随主题色（primaryColor 由 ispeech_primary 解析，非固定搜狗橙）——
    #      这是「主题外观（浅色 / 深色 / 跟随系统）」引入后的有意差异，见 docs/keyboard-ui.md。
    check(
        "setTextColor(if (highlighted) primaryColor else TEXT_PRIMARY)" in item_region
        and "primaryColor: Int = resolvePrimaryColor()" in view
        and 'COLOR_ISPEECH_PRIMARY = "ispeech_primary"' in view,
        "首选与非首选靠颜色 / 底色区分（字重一致，首选走主题色）",
        f"{rel(CANDIDATE_VIEW)} 候选高亮不再走颜色区分（首选识别会退化），"
        "或首选色未跟随主题解析",
    )

    # 11.3 KDoc 口径：注释里不能仍声明候选「加粗」，否则下一次改样式会被误导
    #（允许否定句「不加粗」——它记录的是这次的决定本身）
    kdoc = view.split("class CandidateView", 1)[0]
    bold_claims = [ln for ln in kdoc.splitlines() if "加粗" in ln and "不加粗" not in ln]
    check(
        not bold_claims,
        "CandidateView 类注释已去掉「加粗」口径",
        f"{rel(CANDIDATE_VIEW)} 类注释仍声明候选「加粗」，与实现不符：{bold_claims}",
    )


def main() -> int:
    print(f"== check_crosscut.py == 仓库根：{ROOT}")
    print("-- 1. LookupKind（WUBIPHRASE / PINYIN）--")
    check_lookup_kind()
    print("-- 2. KeyAction.SWITCH_IME 全链路 --")
    check_switch_ime_chain()
    print("-- 3. KeyboardLayoutKind.T9 --")
    check_t9()
    print("-- 4. 新增设置项闭环 --")
    check_new_settings()
    print("-- 5. 输入统计 + 导航 --")
    check_stats_chain()
    print("-- 6. PhraseUsageStore 单例 --")
    check_phrase_usage_singleton()
    print("-- 7. 口径一致性 --")
    check_consistency()
    print("-- 8. 语音输入（VOICE / ASR）全链路 --")
    check_voice_chain()
    print("-- 9. 用户词库（模式 / 自造词 / 拼音态）--")
    check_user_dict_chain()
    print("-- 10. 分类词库（细胞词库）--")
    check_cell_dict_chain()
    print("-- 11. 中文输入法（默认拼音 / 五笔 + 中英切换）--")
    check_chinese_input_method()
    print("-- 12. 候选条字重（VOCA-56）--")
    check_candidate_style()
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
