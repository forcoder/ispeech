#!/usr/bin/env python3
"""把搜狗输入法导出的用户词库转换成 iSpeech 可导入的文本。

背景
----
搜狗 Android 的用户词库位于应用私有目录（加密 SQLite），外部不可读；
其 ContentProvider `com.sohu.inputmethod.sogou.userdict.export` 也有白名单保护
（SecurityException: package not in whitelist）。因此唯一合规路径是由用户在
搜狗设置里主动执行「词库 → 导出」，本脚本负责把导出的文件转成 iSpeech 格式。

输入
----
每行一个词条，兼容以下写法（自动识别）：

    词语
    词语<TAB>频次
    词语 频次
    频次<TAB>词语
    词语<TAB>编码<TAB>频次      （已有编码时直接采用）

输出
----
`code<TAB>text<TAB>count<TAB>mode`，与 `UserDictStore` 的磁盘格式一致，
可被 `UserDictStore.importText()` 直接导入，也可追加到已有 user_dict.txt。

编码来源：先取输入文件自带的编码；没有时反查 iSpeech 码表
（wubi86.dict / pinyin86.dict），都查不到的词会被跳过并计入统计。

用法
----
    python3 tools/import/import_sogou_userdict.py \\
        --input /path/to/sogou_export.txt \\
        --output /tmp/user_dict_import.txt \\
        [--mode wb|cn] [--stats]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ASSETS = REPO_ROOT / "app" / "src" / "main" / "assets"
WUBI_DICT = ASSETS / "wubi86.dict"
PINYIN_DICT = ASSETS / "pinyin86.dict"

# 一个汉字
HAN_RE = re.compile(r"[㐀-鿿]")
# 频次列：纯整数
COUNT_RE = re.compile(r"^\d+$")
# 输出上限，避免异常文件把词库撑爆
MAX_ENTRIES = 50000


def load_code_index(dict_path: Path) -> dict[str, str]:
    """读码表，返回 词语 -> 最优编码（取频次最高的一条）。"""
    index: dict[str, tuple[str, int]] = {}
    if not dict_path.exists():
        return {}
    with dict_path.open("r", encoding="utf-8", errors="ignore") as handle:
        for line in handle:
            line = line.strip("\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) < 2:
                continue
            code, text = fields[0], fields[1]
            try:
                weight = int(fields[2]) if len(fields) > 2 else 0
            except ValueError:
                weight = 0
            current = index.get(text)
            if current is None or weight > current[1]:
                index[text] = (code, weight)
    return {text: pair[0] for text, pair in index.items()}


def is_chinese_word(token: str) -> bool:
    return bool(HAN_RE.search(token))


def parse_line(line: str) -> tuple[str, str, int] | None:
    """解析一行，返回 (词语, 自带编码或空串, 频次)；无法识别返回 None。"""
    line = line.strip()
    if not line or line.startswith("#"):
        return None

    fields = [part for part in re.split(r"[\t,]", line) if part.strip()]
    # 空白分隔的兜底：词语 频次
    if len(fields) == 1:
        fields = line.split()

    if not fields:
        return None

    word = ""
    code = ""
    count = 1

    for field in fields:
        token = field.strip()
        if not token:
            continue
        if is_chinese_word(token) and not word:
            word = token
        elif COUNT_RE.match(token):
            count = max(1, int(token))
        elif re.fullmatch(r"[a-z]{1,12}", token) and not code:
            code = token

    if not word:
        return None
    return word, code, count


def convert(input_path: Path, output_path: Path, mode: str) -> None:
    suffix = input_path.suffix.lower()
    if suffix in {".scel", ".bin", ".dat"}:
        sys.stderr.write(
            f"输入疑似二进制词库（{suffix}），本脚本只处理纯文本导出。\n"
            "请在搜狗设置中选择「导出为文本」，或先用搜狗官方工具转换后再导入。\n"
        )
        raise SystemExit(2)

    index = load_code_index(WUBI_DICT if mode == "wb" else PINYIN_DICT)
    if not index:
        sys.stderr.write(f"码表缺失或为空：{WUBI_DICT if mode == 'wb' else PINYIN_DICT}\n")
        raise SystemExit(3)

    seen: set[tuple[str, str]] = set()
    entries: list[tuple[str, str, int]] = []
    skipped_no_code = 0
    skipped_duplicate = 0
    skipped_invalid = 0

    with input_path.open("r", encoding="utf-8", errors="ignore") as handle:
        for raw_line in handle:
            parsed = parse_line(raw_line)
            if parsed is None:
                if raw_line.strip():
                    skipped_invalid += 1
                continue
            word, code, count = parsed
            if not code:
                code = index.get(word, "")
            if not code:
                skipped_no_code += 1
                continue
            key = (code, word)
            if key in seen:
                skipped_duplicate += 1
                continue
            seen.add(key)
            entries.append((code, word, count))

    entries.sort(key=lambda item: item[2], reverse=True)
    entries = entries[:MAX_ENTRIES]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as handle:
        for code, word, count in entries:
            handle.write(f"{code}\t{word}\t{count}\t{mode}\n")

    sys.stdout.write(
        "导入完成\n"
        f"  输入文件      : {input_path}\n"
        f"  输出文件      : {output_path}\n"
        f"  模式          : {mode}\n"
        f"  写入词条      : {len(entries)}\n"
        f"  跳过·无可用编码: {skipped_no_code}\n"
        f"  跳过·重复      : {skipped_duplicate}\n"
        f"  跳过·无法解析  : {skipped_invalid}\n"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="转换搜狗导出词库为 iSpeech 用户词库格式")
    parser.add_argument("--input", required=True, type=Path, help="搜狗导出的文本词库")
    parser.add_argument("--output", required=True, type=Path, help="输出文件路径")
    parser.add_argument("--mode", choices=["wb", "cn"], default="wb", help="wb=五笔，cn=拼音")
    args = parser.parse_args()

    if not args.input.exists():
        sys.stderr.write(f"输入文件不存在：{args.input}\n")
        raise SystemExit(1)

    convert(args.input, args.output, args.mode)


if __name__ == "__main__":
    main()