#!/usr/bin/env python3
"""快捷短语规则表的「有则更新、无则新增」写入器。

为什么需要它
------------
快捷规则是一条一行、tab 分隔的纯文本（`trigger \\t label \\t content \\t mode \\t weight \\t kind`），
正文里的换行必须写成字面量 `\\n`，否则会把一条规则拆成多行、直接破坏后面所有规则的解析。
手工粘贴 200+ 字的营业说明时，这类转义错误「看起来正常、用起来丢内容」，静态检查也很难发现。

本脚本把「写一行规则」变成一个可重复执行的操作：

* **有则更新**：同 trigger 的已有行原地替换（保持行号），多余的同 trigger 行一并清理，
  条目总数不增长 —— 重复执行同一个命令不会产生第二条；
* **无则新增**：没有该 trigger 时追加到文件末尾；
* **转义一致**：转义规则与 Kotlin 侧 `escapeLineText` / `QuickPhraseCodec.sanitizeContent`
  完全一致（`\\\\` / `\\n` / `\\t`，`\\r` 丢弃），正文按原样逐字保留；
* **只动一行**：注释、其它规则的字节不变（按行读写，不做整体重排）。

用法
----
    python3 tools/quick/upsert_quick_phrase.py \\
        --trigger dtwsl --label 泡池须知 \\
        --content-file tools/quick/phrases/dtwsl.txt \\
        --mode exact --weight 110 --kind a

追加 `--check` 时只比对不写盘：规则已是最新退出码 0，需要更新则 1（可接 CI / pre-commit）。
正文文件按原样读取，只把 CRLF 归一成 LF、并去掉末尾的一个换行（编辑器习惯）。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RULE_FILE = REPO_ROOT / "app" / "src" / "main" / "assets" / "quick_phrases.txt"

SEPARATOR = "\t"
COMMENT_PREFIX = "#"

# 触发码上限：拼音态组合串 MAX_PINYIN_LENGTH = 12；五笔态只有 MAX_CODE_LENGTH = 4，
# 故 5 位及以上的内置码只能在拼音态完整敲出来（见 docs/quick-phrase-enhancement.md 12.6）。
MAX_TRIGGER_LENGTH = 12

VALID_MODES = ("exact", "prefix", "contains", "regex")
VALID_KINDS = ("p", "a")


# ──────────────────────────────────────────────
#  转义（与 Kotlin 侧完全一致）
# ──────────────────────────────────────────────

def escape_content(text: str) -> str:
    """正文转义：`\\`→`\\\\`、换行→`\\n`、tab→`\\t`，`\\r` 丢弃（同 QuickPhraseCodec）。"""
    out: list[str] = []
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            continue
        elif ch == "\t":
            out.append("\\t")
        else:
            out.append(ch)
    return "".join(out)


def unescape_content(text: str) -> str:
    """转义反转义：`\\n` / `\\r` / `\\t` / `\\\\` 还原；未知转义原样保留（同 unescapeLineText）。"""
    if "\\" not in text:
        return text
    out: list[str] = []
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


# ──────────────────────────────────────────────
#  规则行
# ──────────────────────────────────────────────

def sanitize_one_line(value: str) -> str:
    """trigger / label 不允许出现分隔符与换行。"""
    return value.replace("\t", " ").replace("\n", " ").replace("\r", " ")


def build_row(
    trigger: str,
    label: str,
    content: str,
    mode: str = "exact",
    weight: int = 100,
    kind: str = "a",
) -> str:
    """拼出一整行规则：trigger \\t label \\t content \\t mode \\t weight \\t kind。"""
    return SEPARATOR.join(
        (
            sanitize_one_line(trigger),
            sanitize_one_line(label),
            escape_content(content),
            mode,
            str(weight),
            kind,
        )
    )


def row_trigger(line: str) -> str | None:
    """取一行的 trigger（小写）；注释 / 空行 / 字段不足的行返回 None。"""
    stripped = line.strip()
    if not stripped or stripped.startswith(COMMENT_PREFIX):
        return None
    fields = line.split(SEPARATOR)
    if len(fields) < 3:
        return None
    return fields[0].strip().lower()


def count_rows(text: str) -> int:
    return sum(1 for line in text.split("\n") if row_trigger(line) is not None)


def upsert_row(text: str, trigger: str, row: str) -> tuple[str, str, int]:
    """有则更新、无则新增。

    返回 `(新文本, 动作, 清理的重复行数)`，动作为 `inserted` / `updated` / `unchanged`。
    同 trigger 的第一行原地替换（行号不变），其余同 trigger 行删除，避免规则表出现重复条目。
    """
    lines = text.split("\n")
    target = trigger.strip().lower()
    hits = [i for i, line in enumerate(lines) if row_trigger(line) == target]
    removed = max(0, len(hits) - 1)

    if hits:
        first = hits[0]
        action = "unchanged" if lines[first] == row and removed == 0 else "updated"
        for index in reversed(hits[1:]):
            del lines[index]
        lines[first] = row
        return "\n".join(lines), action, removed

    # 追加到末尾：先去掉结尾空串（文件以换行结尾时的产物），再补回一个换行
    while lines and lines[-1] == "":
        lines.pop()
    lines.append(row)
    return "\n".join(lines) + "\n", "inserted", 0


def normalize_content(raw: str) -> str:
    """正文归一：CRLF → LF，去掉末尾一个换行（文件末尾的空行不属于短语正文）。"""
    text = raw.replace("\r\n", "\n")
    if text.endswith("\n"):
        text = text[:-1]
    return text


def sibling_rule_files(rule_file: Path) -> list[Path]:
    """同目录下的其它快捷规则文件（用于提示「同 trigger 已存在于另一个文件」）。"""
    names = ("quick_phrases.txt", "sogou_phrases.txt")
    out = []
    for name in names:
        path = rule_file.parent / name
        if path != rule_file and path.exists():
            out.append(path)
    return out


def validate(trigger: str, mode: str, kind: str, weight: int) -> list[str]:
    errors: list[str] = []
    if not trigger:
        errors.append("trigger 不能为空")
    elif not all("a" <= ch <= "z" for ch in trigger):
        errors.append(f"trigger 只能是小写 a-z：{trigger!r}")
    elif len(trigger) > MAX_TRIGGER_LENGTH:
        errors.append(
            f"trigger 长度 {len(trigger)} 超过拼音态组合串上限 {MAX_TRIGGER_LENGTH}，永远打不出来"
        )
    if mode not in VALID_MODES:
        errors.append(f"mode 非法：{mode!r}（应为 {'/'.join(VALID_MODES)}）")
    if kind not in VALID_KINDS:
        errors.append(f"kind 非法：{kind!r}（p=短语 a=应用）")
    if weight < 0:
        errors.append(f"weight 不能为负：{weight}")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="快捷短语规则表的「有则更新、无则新增」写入器",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--trigger", required=True, help="触发码（小写 a-z，最多 12 位）")
    parser.add_argument("--label", required=True, help="候选条显示的名称")
    parser.add_argument("--content-file", required=True, help="上屏正文（纯文本，可以有多行）")
    parser.add_argument("--mode", default="exact", help="exact / prefix / contains / regex（默认 exact）")
    parser.add_argument("--weight", type=int, default=100, help="权重，越大越靠前（默认 100）")
    parser.add_argument("--kind", default="a", help="p=短语 a=应用（默认 a）")
    parser.add_argument("--file", default=str(DEFAULT_RULE_FILE), help="规则表路径")
    parser.add_argument("--check", action="store_true", help="只比对不写盘：需要更新时退出码 1")
    args = parser.parse_args(argv)

    trigger = args.trigger.strip().lower()
    mode = args.mode.strip().lower()
    kind = args.kind.strip().lower()
    errors = validate(trigger, mode, kind, args.weight)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    content_file = Path(args.content_file)
    if not content_file.exists():
        print(f"ERROR: 正文文件不存在：{content_file}", file=sys.stderr)
        return 1
    content = normalize_content(content_file.read_text(encoding="utf-8"))
    if not content.strip():
        print(f"ERROR: 正文为空：{content_file}", file=sys.stderr)
        return 1

    rule_file = Path(args.file)
    if not rule_file.exists():
        print(f"ERROR: 规则表不存在：{rule_file}", file=sys.stderr)
        return 1
    original = rule_file.read_text(encoding="utf-8").replace("\r\n", "\n")

    row = build_row(trigger, args.label, content, mode, args.weight, kind)
    updated, action, removed = upsert_row(original, trigger, row)

    print(f"规则文件: {rule_file}")
    print(f"动作: {action}" + (f"（清理同 trigger 重复行 {removed} 条）" if removed else ""))
    print(f"条目数: {count_rows(original)} -> {count_rows(updated)}")
    print(f"正文: {len(content.split(chr(10)))} 行 / {len(content)} 字")
    print(f"执行行: {row}")

    for other in sibling_rule_files(rule_file):
        text = other.read_text(encoding="utf-8")
        if any(row_trigger(line) == trigger for line in text.split("\n")):
            print(f"WARN: 同一 trigger 也出现在 {other.name} —— 两个文件都会进候选，请确认是否需要删除其一")

    if updated == original:
        print("结果: 已是最新，无需写盘")
        return 0
    if args.check:
        print("结果: 规则表不是最新（--check 模式未写盘）")
        return 1

    rule_file.write_text(updated, encoding="utf-8")
    print("结果: 已写盘")
    return 0


if __name__ == "__main__":
    sys.exit(main())