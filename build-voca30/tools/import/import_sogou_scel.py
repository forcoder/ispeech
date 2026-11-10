#!/usr/bin/env python3
"""把搜狗细胞词库（.scel）转换成 iSpeech 可安装的分类词库文本。

证据来源（macOS 搜狗拼音 v6.25.3.12004）
----------------------------------------
* 词库文件：`~/Library/Application Support/Sogou/InputMethod/SogouPY/scd/<id>.scel`
* 索引：`~/Library/Application Support/Sogou/InputMethod/SogouPY/scdInfo.plist`
  （记录 id / 名称 / 条目数 / 版本）
* .scel 结构：头部 0x1540 字节，签名 "ECS\\x01"，0x130 起为 UTF-16LE 词库名，
  0x1540 之后依次是拼音音节表与词条（UTF-16LE 汉字 + 拼音索引）。
  本脚本不复刻搜狗私有索引编码，只按 UTF-16LE 抽取汉字词条；
  词条编码由 iSpeech 的 pinyin86.dict 反查补齐，反查不到的词条自动丢弃。

输出格式与 `CellDictStore` 完全一致（可直接在「词典 → 添加词库」安装）：
    # category: <分类>
    code<TAB>词条<TAB>次数<TAB>cn

用法
----
    python3 tools/import/import_sogou_scel.py \\
        --input ~/Library/Application\\ Support/Sogou/InputMethod/SogouPY/scd/4.scel \\
        --output app/src/main/assets/sogou_cell_wangluo.txt \\
        --name 网络流行新词 --category 网络流行语
"""

from __future__ import annotations

import argparse
import plistlib
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
ASSETS = REPO_ROOT / "app" / "src" / "main" / "assets"
PINYIN_DICT = ASSETS / "pinyin86.dict"

SCEL_SIGNATURE = b"ECS\x01"
SCEL_HEADER_OFFSET = 0x1540

# 词条过滤：常见汉字区段（基本区 + 扩展 A），排除扩展 B 以上（.scel 噪声多来自这里）
CJK_BASIC = re.compile(r"[㐀-䶿一-鿿]")
WORD_RUN = re.compile(r"[㐀-䶿一-鿿]{2,8}")
MAX_ENTRIES = 20000

# 语料/音节词频会把个别常用字带偏（如「阿」按音节词频会取 e，实际常用 a）。
# 与 tools/pinyin/build_pinyin_dict.py 的 PREFERRED_READINGS 同源，合成读音时优先采用。
PREFERRED_READINGS = {
    "还": "hai", "都": "dou", "了": "le", "地": "di", "得": "de",
    "色": "se", "行": "xing", "率": "lv", "省": "sheng", "卡": "ka",
    "系": "xi", "传": "chuan", "遗": "yi", "盛": "sheng", "陆": "lu",
    "阿": "a", "顿": "dun", "没": "mei", "血": "xue", "校": "xiao",
    "角": "jiao", "给": "gei", "露": "lu", "参": "can", "降": "jiang",
    "劲": "jin", "朴": "pu", "和": "he", "称": "cheng", "模": "mo",
    "削": "xue", "剥": "bo", "薄": "bo", "便": "bian", "落": "luo",
}


def load_code_index(dict_path: Path) -> tuple[dict[str, tuple[str, int]], dict[str, str]]:
    """码表 → (词语 -> (最优拼音编码, 权重), 单字 -> 读音)。

    单字读音表用于给「基础码表未收录的新词」合成拼音：
    细胞词库的词条本就是基础码表之外的新词（流行语 / 人名 / 影视名），
    只有反查会丢掉它们；用单字读音拼接可把词条补全。

    多音字定音：候选读音按「该音节在整张码表里出现的总词频」择优
    （如 于 → yu 远多于 wei/xu，适 → shi 远多于 kuo），
    避免按字典序拿到冷僻读音（`于适` 被拼成 `wei kuo` 的问题）。
    """
    index: dict[str, tuple[str, int]] = {}
    chars: dict[str, list[tuple[str, int]]] = {}
    syllable_freq: dict[str, int] = {}
    if not dict_path.exists():
        return {}, {}
    with dict_path.open("r", encoding="utf-8", errors="ignore") as handle:
        for line in handle:
            line = line.rstrip("\n")
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
            if len(text) == 1:
                chars.setdefault(text, []).append((code, weight))
            else:
                for syllable in code.split(" "):
                    if syllable:
                        syllable_freq[syllable] = syllable_freq.get(syllable, 0) + weight
    char_readings: dict[str, str] = {}
    for ch, readings in chars.items():
        preferred = PREFERRED_READINGS.get(ch)
        if preferred is not None and any(r[0] == preferred for r in readings):
            char_readings[ch] = preferred
            continue
        # 音节词频优先，其次单字自身在该读音下的权重，最后读音字典序兜底
        best = max(readings, key=lambda r: (syllable_freq.get(r[0], 0), r[1], r[0]))
        char_readings[ch] = best[0]
    return index, char_readings


def synthesize_code(word: str, char_readings: dict[str, str]) -> str:
    """按单字读音拼出全拼；任一字缺读音返回空串。"""
    syllables = []
    for ch in word:
        syllable = char_readings.get(ch)
        if syllable is None or " " in syllable:
            return ""
        syllables.append(syllable)
    return " ".join(syllables)


def read_scel_meta(data: bytes) -> dict:
    """读取 .scel 头部词库名（0x130 起 UTF-16LE，长度由 0x10 处 uint32 给出）。"""
    name = ""
    try:
        name_len = int.from_bytes(data[0x10:0x14], "little")
        if 0 < name_len < 512:
            name = data[0x130:0x130 + name_len].decode("utf-16le", "ignore")
    except Exception:  # noqa: BLE001 - 头部格式异常时保持空名
        pass
    return {"name": name}


def extract_words(data: bytes) -> list[str]:
    """抽取词条：只保留 2..8 个常用汉字的连续串，去重保序。"""
    if not data.startswith(b"", 4) and SCEL_SIGNATURE not in data[:8]:
        raise SystemExit("不是 .scel 文件（缺少 ECS 签名）")
    text = data[SCEL_HEADER_OFFSET:].decode("utf-16le", "ignore")
    seen: set[str] = set()
    words: list[str] = []
    for run in WORD_RUN.findall(text):
        # 去掉夹带噪声：必须全部落在基本汉字区
        if not all(CJK_BASIC.match(ch) for ch in run):
            continue
        if run not in seen:
            seen.add(run)
            words.append(run)
    return words


def read_scd_info(hint_path: Path | None) -> dict:
    """读取 scdInfo.plist（可选），返回 id -> 元信息。"""
    if hint_path is None or not hint_path.exists():
        return {}
    try:
        items = plistlib.loads(hint_path.read_bytes())
    except Exception:  # noqa: BLE001
        return {}
    out = {}
    if isinstance(items, list):
        for item in items:
            if isinstance(item, dict) and "id" in item:
                out[int(item["id"])] = item
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description="转换搜狗细胞词库(.scel)为 iSpeech 分类词库")
    parser.add_argument("--input", required=True, type=Path, help=".scel 文件路径")
    parser.add_argument("--output", required=True, type=Path, help="输出词库文本路径")
    parser.add_argument("--name", default="", help="词库显示名（缺省取 .scel 头部）")
    parser.add_argument("--category", default="网络流行语", help="分类名（写入 # category 头）")
    args = parser.parse_args()

    if not args.input.exists():
        sys.stderr.write(f"输入文件不存在：{args.input}\n")
        raise SystemExit(1)

    data = args.input.read_bytes()
    meta = read_scel_meta(data)
    name = args.name or meta["name"] or args.input.stem

    index, char_readings = load_code_index(PINYIN_DICT)
    if not index:
        sys.stderr.write(f"拼音码表缺失：{PINYIN_DICT}\n")
        raise SystemExit(3)

    words = extract_words(data)
    entries: list[tuple[str, str, int]] = []
    skipped = 0
    synthesized = 0
    seen: set[tuple[str, str]] = set()
    for word in words:
        hit = index.get(word)
        if hit is not None:
            code, weight = hit
        else:
            code = synthesize_code(word, char_readings)
            weight = 100  # 新词排在基础词之后，靠分类词库的 8 万权重进入候选
            if not code:
                skipped += 1
                continue
            synthesized += 1
        key = (code, word)
        if key in seen:
            continue
        seen.add(key)
        entries.append((code, word, max(1, weight)))
    entries.sort(key=lambda item: item[2], reverse=True)
    entries = entries[:MAX_ENTRIES]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        handle.write(f"# 搜狗细胞词库转换：{name}\n")
        handle.write(f"# category: {args.category}\n")
        for code, word, weight in entries:
            handle.write(f"{code}\t{word}\t{weight}\tcn\n")

    sys.stdout.write(
        "转换完成\n"
        f"  输入      : {args.input}\n"
        f"  词库名    : {name}\n"
        f"  输出      : {args.output}\n"
        f"  抽取词条  : {len(words)}\n"
        f"  写入词条  : {len(entries)}（反查 {len(entries) - synthesized} / 合成 {synthesized}）\n"
        f"  丢弃词条  : {skipped}（单字读音不全，无法合成）\n"
    )


if __name__ == "__main__":
    main()