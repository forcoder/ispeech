#!/usr/bin/env python3
"""把搜狗拼音导出的「常用语 / 自定义短语」转换成 iSpeech 快捷短语规则。

背景
----
搜狗拼音的常用语 = 自定义短语 + 快捷字母（主二进制证据：`setUserPhrase:`、
`abbrPhrase`、`SGCustomPhraseSync`），与 iSpeech 的快捷短语（触发码 → 正文）
是同一模型。搜狗把用户短语存在账号云同步里，本机新装无本地明文；
用户可从搜狗「常用语」界面导出为文本后，用本脚本转换。

兼容输入格式（逐行自动识别）
----------------------------
    触发码<TAB>短语
    短语<TAB>触发码
    触发码,短语
    触发码 短语            （空白分隔）
行首 `#` 视为注释；触发码末尾的 `.` 会被去掉（搜狗习惯写作 `sb.`）。

输出
----
`trigger<TAB>label<TAB>content<TAB>exact<TAB>weight<TAB>p`，
与 `QuickPhraseEngine.fromText` 一致，可直接追加到
`filesDir/quick_phrases_user.txt`（设置页「快捷短语」的用户规则文件）。

用法
----
    python3 tools/import/import_sogou_phrases.py \\
        --input sogou_phrases_export.txt \\
        --output /tmp/quick_phrases_user_sogou.txt
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CJK = re.compile(r"[㐀-䶿一-鿿]")
TRIGGER_CLEAN = re.compile(r"[^a-z]")
MAX_TRIGGER = 8
MAX_ENTRIES = 20000


def normalize_trigger(token: str) -> str:
    """小写、去标点（含搜狗惯用的尾点）、截断；保持 a-z。"""
    return TRIGGER_CLEAN.sub("", token.strip().lower())[:MAX_TRIGGER]


def split_line(line: str) -> tuple[str, str] | None:
    """返回 (触发码, 短语)；无法识别返回 None。"""
    text = line.strip()
    if not text or text.startswith("#"):
        return None

    # 先按制表符 / 逗号切
    fields = [part.strip() for part in re.split(r"[\t,]", text) if part.strip()]
    if len(fields) != 2:
        # 单段：在第一个汉字处切开（`sb.什么`）
        match = CJK.search(text)
        if match and match.start() > 0:
            fields = [text[: match.start()].strip(), text[match.start():].strip()]
        else:
            # 空白分隔
            fields = [part.strip() for part in text.split() if part.strip()]
    if len(fields) != 2:
        return None

    left, right = fields
    left_trigger = normalize_trigger(left)
    right_trigger = normalize_trigger(right)
    left_is_trigger = bool(left_trigger) and not CJK.search(left)
    right_is_trigger = bool(right_trigger) and not CJK.search(right)

    if left_is_trigger and CJK.search(right):
        return left_trigger, right
    if right_is_trigger and CJK.search(left):
        return right_trigger, left
    # 都没汉字（纯英文短语）时，取更短的一侧当触发码
    if left_is_trigger and right_is_trigger:
        return (left_trigger, right) if len(left) <= len(right) else (right_trigger, left)
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description="转换搜狗常用语导出文件为 iSpeech 快捷短语规则")
    parser.add_argument("--input", required=True, type=Path, help="搜狗导出的常用语文本")
    parser.add_argument("--output", required=True, type=Path, help="输出规则文件")
    parser.add_argument("--weight", type=int, default=100, help="规则权重（默认 100）")
    args = parser.parse_args()

    if not args.input.exists():
        sys.stderr.write(f"输入文件不存在：{args.input}\n")
        raise SystemExit(1)

    seen: set[tuple[str, str]] = set()
    rows: list[tuple[str, str]] = []
    skipped = 0
    with args.input.open("r", encoding="utf-8", errors="ignore") as handle:
        for raw in handle:
            parsed = split_line(raw)
            if parsed is None:
                if raw.strip() and not raw.strip().startswith("#"):
                    skipped += 1
                continue
            trigger, phrase = parsed
            if not trigger or not phrase:
                skipped += 1
                continue
            key = (trigger, phrase)
            if key in seen:
                continue
            seen.add(key)
            rows.append((trigger, phrase))
            if len(rows) >= MAX_ENTRIES:
                break

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        handle.write("# 由搜狗常用语导出转换（tools/import/import_sogou_phrases.py）\n")
        handle.write("# 字段: trigger <TAB> label <TAB> content <TAB> mode <TAB> weight <TAB> p\n")
        for trigger, phrase in rows:
            label = phrase if len(phrase) <= 24 else phrase[:24]
            # 五笔态组合缓冲上限 4 位：超过 4 位的触发码用前缀模式，打前 4 位即可命中
            mode = "exact" if len(trigger) <= 4 else "prefix"
            handle.write(f"{trigger}\t{label}\t{phrase}\t{mode}\t{args.weight}\tp\n")

    sys.stdout.write(
        "转换完成\n"
        f"  输入    : {args.input}\n"
        f"  输出    : {args.output}\n"
        f"  写入规则: {len(rows)}\n"
        f"  跳过行  : {skipped}\n"
    )


if __name__ == "__main__":
    main()