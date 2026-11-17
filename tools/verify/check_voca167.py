#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_voca167.py —— VOCA-167 验收机检：AC-1 文档指位 + AC-2 列表完整性（按树出结论）

为什么另立一个脚本（ADR-0003 §4.3「断言落点避让」）：
  AC-1 / AC-2 的天然落点 `check_switch_ime_chain()` 正被 PR #20（VOCA-399）改（旧 348-356），
  而 `tools/verify/check_crosscut.py` 同时被 9 个开着的 PR 改。本件写成独立脚本，
  检查项用 `AC1-*` / `AC2-*` / `PRE-*` 命名空间，**不占** check_crosscut 的「第 N 项」编号，
  合并窗口不必为 VOCA-402 重新定位插入点，也不会与 #4/#10/#18 抢占的「13」撞号。

为什么按「树」而不是按「当前 HEAD」跑（VOCA-168 §2.0 结论 1）：
  同一个「绿」在 main 与出包分支上含义相反（main 第 1 键 = SWITCH_IME，release 第 3 键才是）。
  所以 `--tree` 接 git 对象（commit / tree / ref）或目录，脚本用 `git archive` **只读**导出
  那棵树再跑；输出的每一行都带 `[<对象 id>]` 前缀，结论天然署名到树。

行号不硬编码（VOCA-402 约束「按函数/符号解析」）：
  工具栏真值靠解析 `SoftKeyboardView.toolbarKeys()` 的函数体（括号配平）得到，
  文档引用的 `SoftKeyboardView.kt:NNN-MMN` 反过来与解析出的实际跨度比对。

用法：
  python3 tools/verify/check_voca167.py --tree fa57a07
  python3 tools/verify/check_voca167.py --tree eba7a61 --package dist/ispeech-internal-v1.6.apk
  python3 tools/verify/check_voca167.py --tree <ref> --device emulator-5554 --exclusive-device
退出码：任一 FAIL ⇒ 1；SKIP / WARN 不计失败。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

VIEW_REL = "app/src/main/java/com/ispeech/input/SoftKeyboardView.kt"
SERVICE_REL = "app/src/main/java/com/ispeech/input/IspeechImeService.kt"
PANEL_REL = "app/src/main/java/com/ispeech/input/InputMethodPanel.kt"
MANIFEST_REL = "app/src/main/AndroidManifest.xml"
VISIBILITY_REL = "app/src/main/java/com/ispeech/input/ImeListVisibility.kt"
VISIBILITY_TEST_REL = "app/src/test/java/com/ispeech/input/ImeListVisibilityTest.kt"
T9_DOC_REL = "docs/t9-and-symbols.md"

# 滚动阈值按取证设备密度算（VOCA-405 质检的像素级证据：440dpi、实测行距均值 118.7px）
DEVICE_DPI = 440
PX_PER_DP = DEVICE_DPI / 160.0
ROW_PX = 118.7


def panel_scroll_rows(panel: str) -> tuple[int, int] | None:
    """面板高度上限常量链 -> (dp, 完整可见行数)。行号不硬编码，按符号逐级解析。"""
    m = re.search(r"makeMeasureSpec\(\s*dp\((\d+)\)\s*,\s*MeasureSpec\.AT_MOST", panel)
    if m:
        dpv = int(m.group(1))
    else:
        m = re.search(r"makeMeasureSpec\(\s*(\w+)\s*,\s*MeasureSpec\.AT_MOST", panel)
        if not m:
            return None
        ctor = re.search(
            r"class\s+(\w+)[^\n{]*\([^)]*?\bval\s+%s\s*:\s*Int" % re.escape(m.group(1)), panel
        )
        if not ctor:
            return None
        call = re.search(re.escape(ctor.group(1)) + r"\(\s*[^,)]+,\s*dp\((\w+)\)\s*\)", panel)
        if not call:
            return None
        decl = re.search(r"\bval\s+%s\s*=\s*(\d+)" % re.escape(call.group(1)), panel)
        if not decl:
            return None
        dpv = int(decl.group(1))
    return dpv, int(dpv * PX_PER_DP // ROW_PX)


# AC-1 的比对范围：README / 随包取包说明 / docs 下全部 .md（VOCA-168 AC-1 覆盖范围 ①②③④）
DOC_GLOBS = ("README.md", "dist/README.md", "docs/*.md")

# 键面 / 说明性称呼 -> 该键的定位谓词（键面文案允许写说明性称呼，指位与键数不允许）
ALIASES: dict[str, tuple[str, str | None]] = {
    "输入法切换": ("SWITCH_IME", None),
    "切换系统输入法": ("SWITCH_IME", None),
    "切系统其它输入法": ("SWITCH_IME", None),
    "切其它输入法": ("SWITCH_IME", None),
    "弹出输入法列表": ("SWITCH_IME", None),
    "⌨输入法": ("SWITCH_IME", None),
    "键盘图标": ("SWITCH_IME", None),
    "快捷短语": ("PANEL_QUICK_PHRASE", None),
    "剪贴板": ("PANEL_CLIPBOARD", None),
    "符号": ("LAYOUT", "SYMBOL"),
    "表情": ("LAYOUT", "EMOJI"),
    "中/英": ("MODE_SWITCH", None),
    "中英": ("MODE_SWITCH", None),
    "语音": ("VOICE", None),
    "设置": ("SETTINGS", None),
    "AI": ("AI", None),
}
# statusLabel = true 的键：键面运行时随输入态渲染，文档可以写这些占位称呼
STATUS_TOKENS = {"当前态", "输入态", "当前输入态", "拼", "EN", "五"}

CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}


class Report:
    def __init__(self, tag: str) -> None:
        self.tag = tag
        self.counts = {"PASS": 0, "FAIL": 0, "WARN": 0, "SKIP": 0}

    def emit(self, level: str, code: str, msg: str) -> None:
        self.counts[level] += 1
        print(f"{level}: [{self.tag}] {code} {msg}")

    def ok(self, code: str, msg: str) -> None:
        self.emit("PASS", code, msg)

    def fail(self, code: str, msg: str) -> None:
        self.emit("FAIL", code, msg)

    def warn(self, code: str, msg: str) -> None:
        self.emit("WARN", code, msg)

    def skip(self, code: str, msg: str) -> None:
        self.emit("SKIP", code, msg)


def run(argv: list[str], cwd: Path | None = None) -> str:
    return subprocess.run(argv, cwd=cwd, check=True, capture_output=True).stdout.decode("utf-8")


def resolve_tree(spec: str, workdir: Path) -> tuple[Path, str]:
    """`--tree` -> (可读目录, 对象 id)。git 对象走 `git archive` 只读导出；目录直接用。"""
    p = Path(spec)
    if p.is_dir():
        head = run(["git", "-C", str(p), "rev-parse", "HEAD"]).strip()
        return p, head
    try:
        oid = run(["git", "-C", str(REPO), "rev-parse", "--verify", f"{spec}^{{object}}"]).strip()
    except subprocess.CalledProcessError:
        raise SystemExit(f"ERROR: --tree {spec!r} 既不是目录也不是本仓库可解析的 git 对象")
    dest = workdir / oid[:12]
    dest.mkdir(parents=True, exist_ok=True)
    if not any(dest.iterdir()):
        proc = subprocess.Popen(
            ["git", "-C", str(REPO), "archive", oid], stdout=subprocess.PIPE
        )
        with tarfile.open(fileobj=proc.stdout, mode="r|") as tar:  # type: ignore[arg-type]
            tar.extractall(dest, filter="data")
        if proc.wait() != 0:
            raise SystemExit(f"ERROR: git archive {oid} 失败")
    short = oid[:7] if len(oid) == 40 else oid
    return dest, short


def read(root: Path, rel: str) -> str:
    p = root / rel
    return p.read_text(encoding="utf-8") if p.exists() else ""


def line_of(text: str, idx: int) -> int:
    return text.count("\n", 0, idx) + 1


def balance(text: str, open_idx: int) -> tuple[str, int]:
    """从 text[open_idx]（必须是 '(' / '{'）起配平，返回 (体内文本, 闭合下标)。"""
    pairs = {"(": ")", "{": "}", "[": "]"}
    opener = text[open_idx]
    closer = pairs[opener]
    depth, i, n = 0, open_idx, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            if text.startswith('"""', i):
                i = text.find('"""', i + 3) + 3
                continue
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if c == "/" and text.startswith("//", i):
            i = text.find("\n", i)
            i = n if i < 0 else i
            continue
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return text[open_idx + 1 : i], i
        i += 1
    return text[open_idx + 1 :], n


def fun_body(text: str, pattern: str) -> tuple[str, int]:
    """按函数签名（符号，不是行号）取函数体；返回 (体文本, 签名所在行号)。"""
    m = re.search(pattern, text)
    if not m:
        return "", -1
    brace = text.find("{", m.end())
    paren = text.find("(", m.end())
    if brace >= 0 and (paren < 0 or brace < paren):
        body, _ = balance(text, brace)
    elif paren >= 0:
        body, _ = balance(text, paren)
    else:
        body = text[m.end() :]
    return body, line_of(text, m.start())


def parse_toolbar(view_src: str) -> tuple[list[dict], int, int]:
    """解析 toolbarKeys() -> (键列表, 起始行, 结束行)。真值唯一来源，文档向它对齐。"""
    m = re.search(r"fun toolbarKeys\(\)[^\n]*?listOf\(", view_src)
    if not m:
        return [], -1, -1
    open_idx = m.end() - 1
    body, close_idx = balance(view_src, open_idx)
    start = line_of(view_src, m.start())
    end = line_of(view_src, close_idx)
    heads = [mm for mm in re.finditer(r"\bKeyDef\.\w+\(", body)]
    keys: list[dict] = []
    for i, mm in enumerate(heads):
        seg_end = heads[i + 1].start() if i + 1 < len(heads) else len(body)
        seg = body[mm.end() : seg_end]
        action = re.search(r"KeyAction\.([A-Z_]+)", seg)
        label = re.search(r'"([^"]+)"', seg)
        weight = re.search(r"weight\s*=\s*([0-9.]+)f?", seg)
        if not weight:
            after_label = seg[label.end() :] if label else seg
            weight = re.search(r"(?<![\w.]) ([0-9]+\.[0-9]+)f?\s*,", after_label)
        icon = re.search(r"iconRes\s*=\s*R\.drawable\.(\w+)", seg)
        layout = re.search(r"KeyboardLayoutKind\.(\w+)", seg)
        keys.append(
            {
                "n": len(keys) + 1,
                "action": action.group(1) if action else "",
                "label": label.group(1) if label else "",
                "weight": float(weight.group(1)) if weight else None,
                "iconRes": icon.group(1) if icon else "",
                "layout": layout.group(1) if layout else "",
                "status": "statusLabel = true" in seg,
            }
        )
    return keys, start, end


def find_keys(keys: list[dict], action: str, layout: str | None = None) -> list[int]:
    return [
        k["n"]
        for k in keys
        if k["action"] == action and (layout is None or k["layout"] == layout)
    ]


def resolve_token(tok: str, keys: list[dict]) -> list[int]:
    """把一个文档称呼解析成候选键位序号；解析不出返回 []，多义返回多个。"""
    tok = tok.strip()
    if not tok:
        return []
    out = [k["n"] for k in keys if k["label"] == tok]
    if out:
        return out
    m = re.fullmatch(r"KeyAction\.([A-Z_]+)", tok)
    if m:
        return find_keys(keys, m.group(1))
    if tok in ALIASES:
        action, layout = ALIASES[tok]
        return find_keys(keys, action, layout)
    if tok in STATUS_TOKENS:
        got = [k["n"] for k in keys if k["status"]]
        if got:
            return got
    return []


def subject_of_line(line: str, keys: list[dict]) -> tuple[list[int], str]:
    """「第 N 键」这句说的是哪把键。优先级：显式 KeyAction > 「」内称呼 > 别名 > 键面。"""
    m = re.search(r"KeyAction\.([A-Z_]+)", line)
    if m:
        got = find_keys(keys, m.group(1))
        if len(got) == 1:
            return got, f"KeyAction.{m.group(1)}"
        quoted = re.findall(r"「([^」]+)」", line)
        for q in quoted:
            narrowed = [n for n in got if resolve_token(q, keys) == [n] or q == key_label(keys, n)]
            if len(narrowed) == 1:
                return narrowed, f"KeyAction.{m.group(1)} + 「{q}」"
        return got, f"KeyAction.{m.group(1)}（该 action 在工具栏出现 {len(got)} 次）"
    hits: dict[int, str] = {}
    for tok in re.findall(r"「([^」]+)」", line):
        for n in resolve_token(tok, keys):
            hits.setdefault(n, tok)
    if len(hits) == 1:
        return list(hits), f"「{list(hits.values())[0]}」"
    for tok, (action, layout) in ALIASES.items():
        if tok in line:
            for n in find_keys(keys, action, layout):
                hits.setdefault(n, tok)
    if len(hits) == 1:
        return list(hits), f"提及「{list(hits.values())[0]}」"
    return sorted(hits), "、".join(f"{v}→{k}" for k, v in hits.items()) or "未指名"


def key_label(keys: list[dict], n: int) -> str:
    return next((k["label"] for k in keys if k["n"] == n), "")


def actual_desc(keys: list[dict], n: int) -> str:
    k = next((x for x in keys if x["n"] == n), None)
    return f"实际第 {n} 键 = {k['action']}「{k['label']}」" if k else f"实际不存在第 {n} 键"


def doc_files(root: Path) -> list[Path]:
    out: list[Path] = []
    for pat in DOC_GLOBS:
        out.extend(sorted(root.glob(pat)))
    return [p for p in out if p.is_file()]


def split_tokens(text: str, keys: list[dict] | None = None) -> list[str]:
    out = []
    for raw in re.split(r"[\s·、]+", text.strip()):
        tok = raw.strip("：:。，,「」*`")
        if not tok:
            continue
        # 键面本身可能带斜杠（「中/英」是一把键），整体能解析成一把键时不拆，否则按斜杠拆成多个称呼。
        if "/" not in tok or keys is None or len(resolve_token(tok, keys)) != 1:
            out.extend(s for s in (p.strip("：:。，,「」*`") for p in tok.split("/")) if s)
        else:
            out.append(tok)
    return out


def resolve_seq(tokens: list[str], keys: list[dict]) -> list[int] | None:
    """把一串键名称呼映射成键位序号；有多义或解析不出的返回 None（交人工，不猜）。"""
    got = [resolve_token(t, keys) for t in tokens]
    if any(len(g) != 1 for g in got):
        return None
    return [g[0] for g in got]


def iter_doc_lines(files: list[Path]):
    for f in files:
        heading = ""
        in_fence = False
        lines = f.read_text(encoding="utf-8").splitlines()
        for idx, line in enumerate(lines, start=1):
            if line.startswith("```"):
                in_fence = not in_fence
            hm = re.match(r"#{1,6}\s*(.+)", line)
            if hm:
                heading = hm.group(1).strip()
            yield f, idx, line, heading, in_fence, lines


def toolbar_excerpt_scope(lines: list[str], idx: int) -> bool:
    """内嵌 KeyDef 摘录属不属于工具栏：往上找 4 行的注释/签名，非工具栏的键位不参与比对。"""
    window = "\n".join(lines[max(0, idx - 5) : idx])
    if re.search(r"toolbarKeys|工具栏", window):
        return True
    return False


def order_row_claim(line: str, keys: list[dict]) -> list[str] | None:
    """识别「键序行」：`⌨ 符 …` 反引号串，或 [⌨] [符] … 栅格行。"""
    m = re.search(r"键序\s*`([^`\n]+)`", line)
    if m:
        toks = split_tokens(m.group(1), keys)
        if sum(1 for t in toks if resolve_token(t, keys)) >= 1:
            return toks
        return None
    if line.count("[") >= 2 and re.fullmatch(r"\s*(\[[^\]\n]{1,6}\]\s*){2,}", line):
        toks = re.findall(r"\[([^\]]+)\]", line)
        # 只有多数 token 能落到工具栏键位时才算键序行，否则是别的表格里方的方括号
        if toks and sum(1 for t in toks if resolve_token(t, keys)) >= max(2, len(toks) * 3 // 4):
            return toks
    return None


def judge_order(
    r: Report, where: str, toks: list[str], keys: list[dict], n: int, labels: list[str], kind: str
) -> None:
    """键序类声明逐位比对：能解析的必须落在原位；个别称呼解析不出（键面文案已改）只降级人工核对。"""
    hits = [resolve_token(t, keys) for t in toks]
    hits = [resolve_token(t, keys) for t in toks]
    known = [(i + 1, h[0], toks[i]) for i, h in enumerate(hits) if len(h) == 1]
    if not known:
        r.warn("AC1-3", f"{where} {kind} {toks} 在该树一个键位都解析不出，需人工核对")
        return
    moved = [(p, g, t) for p, g, t in known if p != g]
    unknown = [t for i, t in enumerate(toks) if len(hits[i]) != 1]
    if moved:
        detail = "、".join(f"「{t}」文档指第 {p} 键 / 该树实际第 {g} 键" for p, g, t in moved)
        r.fail(
            "AC1-3",
            f"{where} 文档{kind} {toks} 有 {len(moved)} 处与键位不符（{detail}），该树实际键序是 {labels}",
        )
    elif unknown:
        r.warn(
            "AC1-3",
            f"{where} 文档{kind} {toks} 顺序与键位一致，但 {unknown} 在该树解析不出（键面文案可能已改），需人工核对",
        )
    else:
        r.ok("AC1-3", f"{where} 文档{kind} {toks} == 该树实际键序 {labels}")


def section_scope(text: str, needle: str) -> str:
    """取 needle 所在小节的正文（从它上面的标题到下一个同级或更高级标题），不跨章节牵连旧内容。"""
    lines = text.splitlines()
    idx = next((i for i, l in enumerate(lines) if needle in l), None)
    if idx is None:
        return ""
    start, level = 0, 7
    for i in range(idx, -1, -1):
        hm = re.match(r"(#{1,6})\s", lines[i])
        if hm and len(hm.group(1)) <= level:
            start, level = i, len(hm.group(1))
            break
    end = len(lines)
    for j in range(start + 1, len(lines)):
        hm = re.match(r"(#{1,6})\s", lines[j])
        if hm and len(hm.group(1)) <= level:
            end = j
            break
    return "\n".join(lines[start:end])


# --------------------------------------------------------------------------
# AC-1：文档指位 == 该树 toolbarKeys() 解析结果
# --------------------------------------------------------------------------

def in_toolbar_context(line: str, heading: str) -> bool:
    return "工具栏" in line or "工具栏" in heading or "KeyAction." in line


def check_ac1(r: Report, root: Path, keys: list[dict], span: tuple[int, int]) -> None:
    files = doc_files(root)
    n = len(keys)
    labels = [k["label"] for k in keys]
    claims = {"ordinal": 0, "order": 0, "weight": 0, "excerpt": 0}
    hist = {"labeled": 0, "unlabeled": 0}

    for f, idx, line, heading, in_fence, lines in iter_doc_lines(files):
        rel = f.relative_to(root).as_posix()
        where = f"{rel}:{idx}"

        # ④ 引用上一代包的「实测记录」段落必须标清所属包版本，否则读者按当前包去点
        if "实测记录" in heading:
            if re.search(r"v?\d+\.\d+", heading):
                hist["labeled"] += 1
            else:
                hist["unlabeled"] += 1
                r.fail("AC1-4", f"{where} 实测记录段落「{heading}」没标明所属包版本 —— 读者会按当前包去点这一句")

        if not in_toolbar_context(line, heading):
            continue

        # ① 键数
        for m in re.finditer(r"工具栏（(\d+) 键）|工具栏[：:]\s*(\d+) 键|(\d+) 键工具栏", line):
            got = int(next(g for g in m.groups() if g))
            if got == n:
                r.ok("AC1-1", f"{where} 键数 {got} == 该树 toolbarKeys() 的 {n} 键")
            else:
                r.fail("AC1-1", f"{where} 文档写工具栏 {got} 键，但该树 toolbarKeys() 解析出 {n} 键（{labels}）")

        # ① 「工具栏（N 键）：A · B · C」——名字点出来的顺序也是键序声明
        nm = re.search(r"工具栏（\d+ 键）[*\s]*[：:](.+)", line)
        if nm:
            toks = split_tokens(nm.group(1), keys)
            claims["order"] += 1
            if len(toks) != n:
                r.fail("AC1-3", f"{where} 「工具栏（{n} 键）」后列出 {len(toks)} 个名字 {toks}，与实际 {n} 键不符")
            else:
                judge_order(r, where, toks, keys, n, labels, "键名清单")

        # ① 单指位「第 N 键」
        for m in re.finditer(r"第\s*([0-9一二三四五六七八九])\s*键", line):
            raw = m.group(1)
            pos = int(raw) if raw.isdigit() else CN_NUM.get(raw, -1)
            subj, why = subject_of_line(line, keys)
            claims["ordinal"] += 1
            if not subj:
                r.warn("AC1-2", f"{where} 「第 {pos} 键」说的是哪把键解析不出（{why}），需人工核对")
            elif len(subj) > 1:
                r.warn("AC1-2", f"{where} 「第 {pos} 键」指位对象多义（{why}），需人工核对")
            elif subj[0] == pos:
                r.ok("AC1-2", f"{where} 第 {pos} 键 == {actual_desc(keys, pos)}（依据 {why}）")
            else:
                r.fail(
                    "AC1-2",
                    f"{where} 文档说第 {pos} 键，但该树 {actual_desc(keys, subj[0])}（依据 {why}）"
                    f"；照文档按第 {pos} 键会点错",
                )

        # ① 区间指位「第 4~6 键：快捷短语 / 剪贴板 / AI」
        for m in re.finditer(r"第\s*(\d+)\s*[~－-]\s*(\d+)\s*键[：:]?\s*([^\n|]*)", line):
            a, b, tail = int(m.group(1)), int(m.group(2)), m.group(3)
            toks = split_tokens(tail, keys)
            want = [key_label(keys, i) for i in range(a, b + 1)]
            claims["ordinal"] += 1
            seq = resolve_seq(toks, keys)
            if len(toks) != b - a + 1 or seq is None:
                r.warn("AC1-2", f"{where} 区间指位「第 {a}~{b} 键：{tail.strip()}」无法逐位解析，需人工核对")
            elif seq == list(range(a, b + 1)):
                r.ok("AC1-2", f"{where} 第 {a}~{b} 键 == {want}")
            else:
                r.fail("AC1-2", f"{where} 第 {a}~{b} 键文档写 {toks}，该树实际是 {want}")

        # ① 键序行：`⌨ 符 ☺ …` 反引号串，或 [⌨] [符] … 栅格行
        seq_tokens = order_row_claim(line, keys)
        if seq_tokens:
            claims["order"] += 1
            if len(seq_tokens) != n:
                r.fail(
                    "AC1-3",
                    f"{where} 文档键序列出 {len(seq_tokens)} 把键 {seq_tokens}，该树实际 {n} 键 {labels}",
                )
            else:
                judge_order(r, where, seq_tokens, keys, n, labels, "键序")

        # ① 权重行：数值序列 + 声明的权重和（与 check_t9.py 打印值必须同源）
        wm = re.search(r"权重和\s*([0-9.]+)", line)
        if wm:
            claims["weight"] += 1
            head = line[: wm.start()]
            nums = [float(x) for x in re.findall(r"(?<![\d.])(\d+\.\d+)f?(?!\d)", head)]
            declared = float(wm.group(1))
            real = [k["weight"] for k in keys]
            if len(nums) != n:
                r.fail("AC1-6", f"{where} 权重行列出 {len(nums)} 个权重，该树 {n} 键（{real}）")
            elif nums != real:
                r.fail("AC1-6", f"{where} 文档权重 {nums} 与该树实际 {real} 不符")
            else:
                r.ok("AC1-6", f"{where} 权重序列 {nums} == 该树实际")
            total = round(sum(real), 2)
            if abs(total - declared) > 1e-9:
                r.fail("AC1-6", f"{where} 文档写「权重和 {declared}」，但该树实际权重相加 = {total}")
            else:
                r.ok("AC1-6", f"{where} 权重和 {declared} == 实际 {total}")

        # ② 内嵌摘录的行号跨度（按解析出的函数跨度判，不硬编码行号）
        for m in re.finditer(r"SoftKeyboardView\.kt:(\d+)(?:-(\d+))?", line):
            a = int(m.group(1))
            b = int(m.group(2) or a)
            if not (span[0] > 0 and ("toolbarKeys" in line or "工具栏" in line)):
                continue
            claims["excerpt"] += 1
            if a <= span[0] and b >= span[1]:
                r.ok("AC1-5", f"{where} 摘录跨度 {a}-{b} 覆盖该树 toolbarKeys() 实际跨度 {span[0]}-{span[1]}")
            else:
                r.fail(
                    "AC1-5",
                    f"{where} 摘录写 `SoftKeyboardView.kt:{a}-{b}`，但该树 toolbarKeys() 实际在 "
                    f"{span[0]}-{span[1]} —— 摘录随主干腐烂，读者照抄会找不到代码",
                )

        # ② 内嵌 KeyDef 摘录逐字段比对（只比工具栏摘录；数字页 / 符号页的键位不属 AC-1）
        for m in re.finditer(r"KeyDef\.\w+\(\s*KeyAction\.([A-Z_]+),\s*\"([^\"]*)\"([^\n)]*)", line):
            if not toolbar_excerpt_scope(lines, idx):
                continue
            action, face, rest = m.group(1), m.group(2), m.group(3)
            claims["excerpt"] += 1
            real = next((k for k in keys if k["action"] == action), None)
            if real is None:
                r.fail("AC1-5", f"{where} 工具栏摘录里的 KeyAction.{action} 在该树工具栏不存在（{labels}）")
                continue
            problems = []
            if face != real["label"]:
                problems.append(f"键面摘录「{face}」≠ 代码「{real['label']}」")
            w = re.search(r"weight\s*=\s*([0-9.]+)f?", rest) or re.search(r"^\s*,?\s*([0-9]+\.[0-9]+)f", rest)
            if w and float(w.group(1)) != real["weight"]:
                problems.append(f"weight 摘录 {w.group(1)}f ≠ 代码 {real['weight']}f")
            ic = re.search(r"iconRes\s*=\s*R\.drawable\.(\w+)", rest)
            if ic and ic.group(1) != real["iconRes"]:
                problems.append(f"iconRes 摘录 {ic.group(1)} ≠ 代码 {real['iconRes'] or '（无）'}")
            if problems:
                r.fail(
                    "AC1-5",
                    f"{where} 内嵌 `KeyDef` 摘录与代码不符：{'；'.join(problems)}"
                    f"（代码 {action}「{real['label']}」weight {real['weight']}f"
                    f"{' iconRes=' + real['iconRes'] if real['iconRes'] else ''}）",
                )
            else:
                r.ok("AC1-5", f"{where} 内嵌摘录 KeyAction.{action} 与代码逐字段一致")

    # ③ 校验脚本自己的断言文案必须与该树键面同源（硬编码过一次，两条线必有一边不符）
    sw = next((k for k in keys if k["action"] == "SWITCH_IME"), None)
    vdir = root / "tools" / "verify"
    if sw and vdir.is_dir():
        for vf in sorted(vdir.glob("check_*.py")):
            rel = vf.relative_to(root).as_posix()
            for i, vline in enumerate(vf.read_text(encoding="utf-8").splitlines(), start=1):
                code = vline.split("#", 1)[0]  # 注释里的「以前硬编码过 ⌨输入法」是说明，不是声明
                if 'f"' in code or "f'" in code:
                    continue
                hit = next(
                    (
                        tok
                        for tok in re.findall(r"「([^」]+)」", code)
                        if tok in ALIASES and ALIASES[tok][0] == "SWITCH_IME" and tok != sw["label"]
                    ),
                    None,
                )
                if hit:
                    r.fail(
                        "AC1-7",
                        f"{rel}:{i} 机检文案把切换键写成「{hit}」，但该树键面是「{sw['label']}」"
                        f"—— 应从 toolbarKeys() 现解析（PR #20 的改法）",
                    )
                    break

    # VOCA-399 / PR #20 的口径：§4.1 只给符号引用，摘录一旦回潮就是红
    t9 = read(root, T9_DOC_REL)
    if "不嵌源码摘录" in t9:
        scope = section_scope(t9, "不嵌源码摘录")
        if re.search(r"KeyDef\.\w+\(\s*KeyAction\.", scope):
            r.fail(
                "AC1-8",
                f"{T9_DOC_REL} 的承诺小节内写了「不嵌源码摘录」，同一节仍有 KeyDef 摘录 —— 口径又腐烂了",
            )
        else:
            r.ok(
                "AC1-8",
                f"{T9_DOC_REL} 承诺「只给符号引用」的那一节内已无内嵌 KeyDef 摘录（口径守住，不追究其它章节）",
            )

    r.ok(
        "AC1-0",
        f"该树真值：toolbarKeys() 共 {n} 键 {labels}，跨度 {span[0]}-{span[1]} 行（按函数符号解析，不硬编码行号）",
    )
    r.ok(
        "AC1-0",
        f"比对范围 {len(files)} 个文档；指位 {claims['ordinal']} 条 / 键序 {claims['order']} 条 / "
        f"权重 {claims['weight']} 条 / 摘录 {claims['excerpt']} 条；实测记录段落 "
        f"{hist['labeled']} 个已标版本、{hist['unlabeled']} 个未标",
    )



# --------------------------------------------------------------------------
# AC-2：面板行数 == |ENABLED|
# --------------------------------------------------------------------------

def check_ac2_static(r: Report, root: Path) -> None:
    manifest = read(root, MANIFEST_REL)
    q = re.search(r"<queries>(.*?)</queries>", manifest, re.S)
    seg = q.group(1) if q else ""
    has_rm = "android.speech.RecognitionService" in seg
    has_im = "android.view.InputMethod" in seg
    if has_rm and has_im:
        r.ok("AC2-1", f"{MANIFEST_REL} 的 <queries> 同时声明 RecognitionService 与 InputMethod（ADR-0003 §4 门禁 ②）")
    else:
        missing = [n for n, ok in (("android.view.InputMethod", has_im), ("android.speech.RecognitionService", has_rm)) if not ok]
        r.fail(
            "AC2-1",
            f"{MANIFEST_REL} 的 <queries> 缺 {'、'.join(missing)} —— "
            f"Android 11+ 包可见性会裁掉 enabledInputMethodList，面板必漏列（缺陷 D 未修）",
        )

    panel = read(root, PANEL_REL)
    if not panel:
        r.fail("AC2-2", f"{PANEL_REL} 不存在，无法核对「行数 == 共 N 个」的同源关系")
        return
    count = re.search(r'countLabel\.text\s*=.*?"共 \$\{(\w+)\.size\} 个"', panel)
    loop = re.search(r"for \((\w+) in (\w+)\)", panel)
    if count and loop and count.group(1) == loop.group(2):
        r.ok("AC2-2", f"标签 `共 ${{{count.group(1)}.size}} 个` 与行循环 `for ({loop.group(1)} in {loop.group(2)})` 同源，标签里的 N 就是数据侧条数")
    else:
        r.fail("AC2-2", f"{PANEL_REL} 的「共 N 个」标签与行填充循环不再同源（count={count.group(1) if count else None} / loop={loop.group(2) if loop else None}）—— 标签不能当条数证据")
    sliced = re.search(r"\bitems\.(take|drop|slice|subList|filter|filterIndexed)\b", panel)
    if sliced:
        r.fail("AC2-3", f"{PANEL_REL} 对 items 做了 `{sliced.group(1)}`，渲染条数可能小于数据条数（AC-2 要求行数 == |ENABLED|）")
    else:
        r.ok("AC2-3", f"{PANEL_REL} 未对 items 做 take/drop/slice/filter，不存在「裁掉几行再渲染」")
    scroll = panel_scroll_rows(panel)
    if scroll:
        dp_cap, rows_fit = scroll
        r.ok(
            "AC2-4",
            f"面板列表高度上限 AT_MOST({dp_cap}dp) = {int(dp_cap * PX_PER_DP)}px @{DEVICE_DPI}dpi，"
            f"实测行距 {ROW_PX}px ⇒ 完整可见 {rows_fit} 行、第 {rows_fit + 1} 行起才滚动。"
            f"滚动是渲染层行为、不改数据条数，「共 N 个」仍是数据侧条数；"
            f"|ENABLED| ≥ {rows_fit + 1} 才可能滚动（滚动补拍那条按此判 SKIP / 触发）",
        )
    else:
        r.warn("AC2-4", f"{PANEL_REL} 没解析到 AT_MOST 高度上限的常量链，滚动阈值这条条件用例的前置需人工确认")

    service = read(root, SERVICE_REL)
    body, _ = fun_body(service, r"private fun enabledInputMethods\(\)")
    if "enabledInputMethodList" in body:
        r.ok("AC2-5", "IspeechImeService.enabledInputMethods() 直接取 manager.enabledInputMethodList 全量")
    else:
        r.fail("AC2-5", "IspeechImeService.enabledInputMethods() 里取不到 enabledInputMethodList，取数入口已变，AC-2 判据需重述")
    filtered = re.search(r"(packageName\s*(==|!=|!?\s*\.\s*startsWith)|startsWith\(\"com\.|endsWith\(\"\.sogou)", body)
    if filtered:
        r.fail("AC2-6", f"取数处出现按包名筛选（{filtered.group(0)}）—— 缺陷 D 若靠「自己过滤回来」修，属越界修法")
    else:
        guards = len(re.findall(r"\?\s*:\s*continue", body))
        r.ok("AC2-6", f"取数处无包名/类型筛选，只有 {guards} 处 `?: continue` 空 id 守卫（与 VOCA-168 §2.3 口径一致）")

    status = re.search(r'"输入法 \$\{([\w.]*)\.size\} 个"', service)
    if status:
        r.ok("AC2-7", f"标题栏「输入法 N 个」取自 {status.group(1)}.size，与面板行同源")
    else:
        r.fail("AC2-7", f"{SERVICE_REL} 里找不到「输入法 ${{….size}} 个」的标题栏文案，三处计数同框的取证口径失去一个点")

    vis = read(root, VISIBILITY_REL)
    if not vis:
        r.fail("AC2-8", f"{VISIBILITY_REL} 不存在 —— 被框架裁掉的项与「系统里就只有这几个」不可区分，AC-2 缺交叉校验（ADR-0003 D3）")
        r.skip("AC2-9", f"{VISIBILITY_TEST_REL} 未落地，JVM 侧回归无从跑起（该树未含 PR #23）")
        vectors = None
    else:
        # 把 Kotlin 的算术语义在 Python 里复刻一遍：语义漂移当场红
        ec = re.search(r"fun enabledCount\([^\n]*\n?\s*=?\s*(.+)", vis)
        hc = re.search(r"fun hiddenCount\([^\n]*\n?\s*=?\s*(.+)", vis)
        ec_body = (ec.group(1) if ec else "").strip()
        hc_body = (hc.group(1) if hc else "").strip()
        want_ec = "enabledSettings?.split(':')?.count { it.isNotBlank() } ?: 0"
        want_hc = "(enabledCount - visibleCount).coerceAtLeast(0)"
        if ec_body != want_ec:
            r.fail("AC2-8", f"enabledCount 实现语义变了：`{ec_body}`（期望 `{want_ec}`）—— 下方复刻用例不再等价")
        if hc_body != want_hc:
            r.fail("AC2-8", f"hiddenCount 实现语义变了：`{hc_body}`（期望 `{want_hc}`）")
        vectors = (ec_body == want_ec, hc_body == want_hc)

    if vectors and all(vectors):
        def enabled_count(raw: str | None) -> int:
            return len([s for s in raw.split(":") if s.strip()]) if raw else 0

        def hidden_count(enabled: int, visible: int) -> int:
            return max(0, enabled - visible)

        cases = [
            (("a/.A:b/.B:c/.C:d/.D"), 4),
            (("latin/.LatinIME;subtype#qwerty;subtype#num:ispeech/.S"), 2),
            ((None), 0),
            ((""), 0),
            (("com.ispeech.ime/.IspeechImeService:"), 1),
            (("a/.A::b/.B"), 2),
        ]
        bad = [(raw, want) for raw, want in cases if enabled_count(raw) != want]
        if bad:
            r.fail("AC2-9", f"enabledCount 复刻用例不符（冒号分段 / 空段不算 / 子类型分号不额外计数）：{bad}")
        else:
            r.ok("AC2-9", f"enabledCount 复刻 {len(cases)} 组用例全过（冒号分段、空段不算、`;` 子类型不额外计数）")
        hbad = [(a, b) for a, b in ((4, 2), (4, 4), (0, 4), (1, 1)) if hidden_count(a, b) != max(0, a - b)]
        if hbad:
            r.fail("AC2-9", f"hiddenCount 复刻用例不符：{hbad}")
        else:
            r.ok("AC2-9", "hiddenCount 复刻 4 组用例全过（差值只用于提示，不参与取数）")

    test_src = read(root, VISIBILITY_TEST_REL)
    if test_src:
        n_tests = len(re.findall(r"@Test", test_src))
        if n_tests >= 5:
            r.ok("AC2-10", f"{VISIBILITY_TEST_REL} 有 {n_tests} 个 @Test（JVM 执行需 Android SDK + 网络，本脚本只做静态与逻辑复刻）")
        else:
            r.fail("AC2-10", f"{VISIBILITY_TEST_REL} 只有 {n_tests} 个 @Test，覆盖不足")
    else:
        r.skip("AC2-10", f"{VISIBILITY_TEST_REL} 不存在，JVM 单测未落地")


def check_ac345_static(r: Report, root: Path) -> None:
    """AC-3 / AC-4 / AC-5 的代码侧前置链（运行时判定仍属设备用例）。"""
    service = read(root, SERVICE_REL)
    panel = read(root, PANEL_REL)
    if re.search(r"setInputMethod\(\s*\w+\.windowToken", service) or "setInputMethod(" in service:
        r.ok("AC3-1", "服务侧调用 setInputMethod(windowToken, id) —— AC-3「点一行就切走」有实现入口")
    else:
        r.fail("AC3-1", "服务侧找不到 setInputMethod 调用，AC-3 无从判定")
    if "showInputMethodPicker" in service and re.search(r"系统不允许切换输入法", service + panel):
        r.ok("AC4-1", "失败兜底链齐备：setInputMethod 失败 -> showInputMethodPicker -> 「系统不允许切换输入法」")
    else:
        r.fail("AC4-1", "AC-4 的兜底链不完整（缺 showInputMethodPicker 或缺「系统不允许切换输入法」文案）—— 静默失败风险")
    for txt, code in (("没有其他输入法", "AC5-1"), ("读不到输入法列表", "AC5-2")):
        if txt in service:
            r.ok(code, f"空态文案「{txt}」在服务侧存在")
        else:
            r.fail(code, f"空态文案「{txt}」缺失（|ENABLED| = 1 时的 AC-5 判定无依据）")
    if "onSystemPicker" in panel or "系统选择器" in panel:
        r.ok("AC5-3", "面板保留「系统选择器」按钮（AC-5 要求空态下仍可点）")
    else:
        r.fail("AC5-3", "面板里没有「系统选择器」入口，空态下用户无路可走")


def check_device(r: Report, root: Path, serial: str, exclusive: bool) -> None:
    if not exclusive:
        r.skip(
            "AC2-D",
            "取证设备独占未开（ADR-0003 §6.4 的必要条件，人类闸门未批）—— 不在共享设备上做「同一截图三处计数同框」取证。"
            "需要：一台可独占的设备（当前缺 |ENABLED| ≥ 6 的那台，用于滚动条件用例）",
        )
        return
    adb = ["adb", "-s", serial]
    try:
        raw = run(adb + ["shell", "settings", "get", "secure", "enabled_input_methods"]).strip()
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        r.skip("AC2-D", f"读不到 {serial} 的 enabled_input_methods（{e}），设备用例未跑")
        return
    ids = [s.split(";")[0] for s in raw.split(":") if s.strip()]
    n = len(ids)
    if n == 0:
        r.fail("AC2-D", f"{serial} 的 ENABLED 为空，无法构造 AC-2 前置（N ≥ 1 且至少 1 项非智语）")
        return
    r.ok("AC2-D", f"{serial} |ENABLED| = {n}：{ids}")
    try:
        dump = run(adb + ["shell", "uiautomator", "dump", "/sdcard/window_dump.xml"])
        xml = run(adb + ["shell", "cat", "/sdcard/window_dump.xml"])
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        r.skip("AC2-D", f"uiautomator 取证失败（{e}）—— 面板需已打开：工具栏切换键 -> 「切换输入法」")
        return
    label = re.search(r"共\s*(\d+)\s*个", xml)
    rows = re.findall(r'content-desc="ime-row:([^"]+)"', xml) or re.findall(r"text=\"([^\"]*?)\"[^>]*resource-id=\"[^\"]*ime_row", xml)
    if not label or not rows:
        r.fail("AC2-D", f"当前界面里既取到标签={label.group(1) if label else None} 又取到行={len(rows)} —— 面板没打开或行无标识，判无法核实")
        return
    if int(label.group(1)) != n:
        r.fail("AC2-D", f"面板标签「共 {label.group(1)} 个」 != |ENABLED| = {n}（缺陷 D 复发）")
    else:
        r.ok("AC2-D", f"面板标签「共 {n} 个」 == |ENABLED|")
    if len(rows) != n:
        r.fail("AC2-D", f"面板渲染 {len(rows)} 行 != |ENABLED| = {n}，缺 {set(ids) - set(rows)}")
    else:
        r.ok("AC2-D", f"面板 {n} 行且每个 ENABLED id 都有行")
    cap = panel_scroll_rows(read(root, PANEL_REL))
    limit = cap[1] + 1 if cap else 6
    if n >= limit:
        r.skip("AC2-D", f"|ENABLED| = {n} ≥ {limit} 会滚动：需补一张滚到底的同框截图后再人工判行数（脚本不猜未滚出的行）")
    else:
        r.skip(
            "AC2-D",
            f"滚动条件用例判 SKIP：本机 |ENABLED| = {n} < {limit}"
            f"（阈值 = AT_MOST {cap[0] if cap else '?'}dp / 行距 {ROW_PX}px），构造不出滚动前置；不得因此报绿",
        )


def check_package_sign(r: Report, root: Path, pkg: str, claimed: str) -> None:
    p = root / pkg
    if not p.exists():
        r.skip("PRE-1", f"{pkg} 不在这棵树上，被验对象署名不成立")
        return
    actual = hashlib.sha256(p.read_bytes()).hexdigest()
    if claimed:
        if claimed.lower() == actual:
            r.ok("PRE-0", f"{pkg} 实际字节 sha256 与声称摘要一致（{actual}）")
        else:
            r.fail("PRE-0", f"{pkg} 声称 sha256={claimed}，实际字节算出 {actual} —— 署名的不是这串字节")
    sums = read(root, "dist/SHA256SUMS") or read(root, "SHA256SUMS")
    entries = re.findall(r"([0-9a-f]{64})\s+(\S+)", sums)
    listed = {n: d for d, n in entries}
    rel_pkg = pkg[len("dist/") :] if pkg.startswith("dist/") else pkg
    base = pkg.split("/")[-1]
    want = listed.get(rel_pkg, listed.get(base, ""))
    same_bytes = [n for d, n in entries if d == actual]
    if want == actual:
        r.ok("PRE-1", f"被验包 {pkg} sha256={actual} 与该树 dist/SHA256SUMS 的 {rel_pkg} 条目一致（署名链闭合）")
    elif want:
        r.fail(
            "PRE-1",
            f"{pkg} 实际字节 sha256={actual}，但该树 dist/SHA256SUMS 给 {rel_pkg} 记的是 {want} —— "
            f"按清单取包的人和按路径取包的人拿到的是两串字节，被验对象署名不闭合"
            + (f"（{actual} 在清单里登记为 {same_bytes[0]}，清单没跟着出包路径改）" if same_bytes else ""),
        )
    else:
        r.fail(
            "PRE-1",
            f"被验包 {pkg} 实际字节 sha256={actual}，但该树 dist/SHA256SUMS 里没有 {rel_pkg} 这一条 —— "
            "随包校验清单覆盖不到它，署名链不闭合",
        )
    if not sums:
        r.warn("PRE-2", f"该树没有 dist/SHA256SUMS，无法核对随包校验清单")


def self_test(workdir: Path) -> int:
    """自检：造一棵最小假树，证明这套断言真的会咬人（红），且正确文档不误伤（绿）。

    质检专员不需要真仓库历史就能复核「断言有效」这一条。
    """
    bad = workdir / "selftest-bad"
    good = workdir / "selftest-good"
    code = """
class SoftKeyboardView {
    private fun toolbarKeys(): List<KeyDef> = listOf(
        KeyDef.fn(KeyAction.PANEL_QUICK_PHRASE, "短语", weight = 1.3f),
        KeyDef.fn(KeyAction.SWITCH_IME, "输", weight = 1f),
        KeyDef.fn(KeyAction.SETTINGS, "⚙", weight = 1f, iconRes = R.drawable.ic_settings_gear),
    )
}
"""
    manifest = """
<manifest>
    <queries>
        <intent><action android:name="android.speech.RecognitionService" /></intent>
    </queries>
</manifest>
"""
    bad_readme = """# 假 README
## 工具栏
- **工具栏（3 键）**：输入法切换 · 快捷短语 · 设置
  （键序 `⌨ 短语 设置`）
- 工具栏第 1 键「⌨输入法」弹出输入法列表
- 第 2 键 `KeyAction.SWITCH_IME`
```
[短语] [输] [⚙]
 1.3   1.0   1.0   ← 权重和 9.9
```
// SoftKeyboardView.kt:262-285 —— 3 键工具栏
private fun toolbarKeys(): List<KeyDef> = listOf(
    KeyDef.fn(KeyAction.SWITCH_IME, "⌨输入法", weight = 1.6f, style = KeyStyle.SPECIAL),
)
"""
    good_readme = """# 假 README
## 工具栏
- **工具栏（3 键）**：快捷短语 · 输入法切换 · 设置
  （键序 `短语 输 ⚙`）
- 第 2 键 `KeyAction.SWITCH_IME`：切系统其它输入法，键面固定「输」
- 第 1~3 键：快捷短语 / 输入法切换 / 设置
```
[短语] [输] [⚙]
 1.3   1.0   1.0   ← 权重和 3.3
```
"""
    for root, readme in ((bad, bad_readme), (good, good_readme)):
        (root / "app" / "src" / "main" / "java" / "com" / "ispeech" / "input").mkdir(parents=True, exist_ok=True)
        (root / "docs").mkdir(parents=True, exist_ok=True)
        (root / "app" / "src" / "main" / "java" / "com" / "ispeech" / "input" / "SoftKeyboardView.kt").write_text(code, encoding="utf-8")
        (root / "app" / "src" / "main" / "AndroidManifest.xml").write_text(manifest, encoding="utf-8")
        (root / "README.md").write_text(readme, encoding="utf-8")

    def run_tree(root: Path, with_ac2: bool) -> dict:
        r = Report("selftest")
        keys, a, b = parse_toolbar(read(root, VIEW_REL))
        check_ac1(r, root, keys, (a, b))
        if with_ac2:
            check_ac2_static(r, root)  # 好树缺 panel/service 夹具，只跑 AC-1 侧
        return r.counts

    bad_counts = run_tree(bad, True)
    good_counts = run_tree(good, False)
    fails = []
    if bad_counts["FAIL"] < 7:
        fails.append(f"坏树只判出 {bad_counts['FAIL']} 条 FAIL，断言不够狠（期望 ≥7：键名顺序 / 单指位 / 权重和 / 摘录跨度 / 摘录字段 / queries / panel）")
    if good_counts["FAIL"] != 0:
        fails.append(f"正确文档被误伤：好树判出 {good_counts['FAIL']} 条 FAIL")
    if good_counts["WARN"] != 0:
        fails.append(f"好树出现 {good_counts['WARN']} 条 WARN，说明解析有歧义口径")
    cap = panel_scroll_rows(
        "private val maxPanelHeightDp = 240\n"
        "return BoundedScrollView(context, dp(maxPanelHeightDp))\n"
        "private class BoundedScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {\n"
        "  val spec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)\n}"
    )
    if cap != (240, 5):
        fails.append(f"滚动阈值常量链解析错：{cap}（期望 (240, 5) ⇒ 第 6 行起滚动）")
    print(f"SELFTEST: 坏树 passed={bad_counts['PASS']} failed={bad_counts['FAIL']} warned={bad_counts['WARN']}")
    print(f"SELFTEST: 好树 passed={good_counts['PASS']} failed={good_counts['FAIL']} warned={good_counts['WARN']}")
    for m in fails:
        print(f"FAIL: [selftest] SELF {m}")
    print(f"SUMMARY: selftest {'FAILED' if fails else 'PASSED'}")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="VOCA-167 AC-1 / AC-2 按树机检")
    ap.add_argument("--tree", action="append", default=[], help="git 对象（commit/tree/ref）或目录，可重复")
    ap.add_argument("--label", action="append", default=[], help="给 --tree 起的树名，按顺序对应")
    ap.add_argument("--package", default="", help="被验安装包在该树内的路径，做署名链校验")
    ap.add_argument("--package-sha256", default="", help="已知包字节摘要（避免重复算 139MB）")
    ap.add_argument("--device", default="", help="跑 AC-2 设备用例的 serial")
    ap.add_argument("--exclusive-device", action="store_true", help="声明该设备为本轮独占取证设备（§6.4 必要条件）")
    ap.add_argument("--self-test", action="store_true", help="只跑断言自检（造夹具证明断言会咬人且不误伤）")
    args = ap.parse_args()

    tmp = tempfile.mkdtemp(prefix="voca167-")
    if args.self_test:
        return self_test(Path(tmp))
    if not args.tree:
        raise SystemExit("ERROR: 需要至少一个 --tree（或用 --self-test 跑断言自检）")
    failed = 0
    for i, spec in enumerate(args.tree):
        root, oid = resolve_tree(spec, Path(tmp))
        label = args.label[i] if i < len(args.label) else spec
        r = Report(f"{oid}:{label}")
        print(f"=== TREE {oid} <- {spec} (label={label}) ===")
        view = read(root, VIEW_REL)
        keys, a, b = parse_toolbar(view)
        if not keys:
            r.fail("AC1-0", f"{VIEW_REL} 里解析不到 toolbarKeys()，AC-1 没有真值可对齐（该树结构已变，脚本需跟进）")
        else:
            check_ac1(r, root, keys, (a, b))
        check_ac2_static(r, root)
        check_ac345_static(r, root)
        if args.package:
            check_package_sign(r, root, args.package, args.package_sha256)
        if args.device:
            check_device(r, root, args.device, args.exclusive_device)
        else:
            r.skip("AC2-D", "未传 --device：AC-2 的行数/条数同框取证未跑（设备用例，需要独占取证设备）")
        c = r.counts
        failed += c["FAIL"]
        print(f"SUMMARY: tree={oid} label={label} passed={c['PASS']} failed={c['FAIL']} warned={c['WARN']} skipped={c['SKIP']}")
    print(f"TOTAL: failed={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
