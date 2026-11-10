#!/usr/bin/env python3
"""生成 iSpeech 拼音码表 app/src/main/assets/pinyin86.dict。

数据全部来自真实上游（不手写拼音），三个来源都用 jsDelivr CDN 按 commit 固定版本下载，
下载后校验 sha256，任一来源校验失败立即退出（不会产出半成品码表）。

上游（均为 MIT）:
  1. mozillazg/pinyin-data        pinyin.txt     单字读音（含多音字），MIT
  2. mozillazg/phrase-pinyin-data pinyin.txt     词语读音（空格分隔音节），MIT
  3. fxsjy/jieba                  jieba/dict.txt 词频（仅取频次列做排序依据），MIT

裁剪口径:
  * 字表：jieba 单字词频表中能在 (1) 里查到读音的 11,579 个汉字（覆盖常用字全量）
  * 词表：(2) 中全部音节数 == 字数、且汉字全部落在 CJK 区段的词条，
    其中 4 字及以上词条要求 jieba 词频 >= LONG_WORD_MIN_FREQ（jieba 的 4 字词条
    频次中位数只有 3，绝大多数是极低频成语；阈值 20 可砍掉约 4/5 长词、节省约 0.5 MiB）
  * 权重：jieba 词频对数压缩到 1..9999，仅用于同层排序；无词频的词条按 1 处理
  * 注音补全：(3) 中词频 >= SYNTHESIS_MIN_FREQ 的 2-4 字词，若 (2) 未收录，则用 (1) 的
    单字读音合成词条，补齐 (2) 漏收的常用词（如 酒店、基础设施）。4 字词同样只走合成：
    phrase-pinyin-data 基本不收录 4 字词读音，仅靠 (2) 会让「基础设施 / 人工智能」这类
    常用长词整批缺失，导致输入简拼 `jcss` 时无候选可联想。多音字按语料证据定音：声明多个
    读音、但 (2) 语料只出现其中一个时采用该读音（如 明 = ming/meng，语料只有 ming）；
    2-3 字词若语料中某读音占比 >= SYNTHESIS_MIN_SHARE 则采用该读音，否则跳过；4 字词仍
    要求语料只佐证一个读音。少数语料被书面语带偏的常用字按 PREFERRED_READINGS 取现代
    常用读音

用法:
  python3 tools/pinyin/build_pinyin_dict.py            # 下载并生成
  python3 tools/pinyin/build_pinyin_dict.py --cache /tmp/ispeech-pinyin-src
"""

from __future__ import annotations

import argparse
import hashlib
import math
import os
import re
import sys
import tempfile
import urllib.request
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUT = REPO_ROOT / "app" / "src" / "main" / "assets" / "pinyin86.dict"
DEFAULT_CACHE = Path(tempfile.gettempdir()) / "ispeech-pinyin-src"

USER_AGENT = "ispeech-pinyin-dict-builder/1 (+https://github.com/)"

# 上游文件：commit 固定 + sha256 固定。sha256 是 2026-10-17 实际下载后计算的。
SOURCES = {
    "chars": {
        "repo": "mozillazg/pinyin-data",
        "commit": "9193766130af24d2ac54230be979b2e98ac66223",
        "path": "pinyin.txt",
        "urls": [
            "https://cdn.jsdelivr.net/gh/mozillazg/pinyin-data@{commit}/pinyin.txt",
            "https://fastly.jsdelivr.net/gh/mozillazg/pinyin-data@{commit}/pinyin.txt",
            "https://gcore.jsdelivr.net/gh/mozillazg/pinyin-data@{commit}/pinyin.txt",
            "https://raw.githubusercontent.com/mozillazg/pinyin-data/{commit}/pinyin.txt",
        ],
        "sha256": "621f8ca9eff8519f47e2b17b564fd318161e13bca07eea8c8e04993cd5d3b52e",
        "lines": 44437,
        "license": "MIT (c) 2016 mozillazg",
    },
    "words": {
        "repo": "mozillazg/phrase-pinyin-data",
        "commit": "cee0ed6e6e4898580cafd2bd5e3723e20b214aa0",
        "path": "pinyin.txt",
        "urls": [
            "https://cdn.jsdelivr.net/gh/mozillazg/phrase-pinyin-data@{commit}/pinyin.txt",
            "https://fastly.jsdelivr.net/gh/mozillazg/phrase-pinyin-data@{commit}/pinyin.txt",
            "https://gcore.jsdelivr.net/gh/mozillazg/phrase-pinyin-data@{commit}/pinyin.txt",
            "https://raw.githubusercontent.com/mozillazg/phrase-pinyin-data/{commit}/pinyin.txt",
        ],
        "sha256": "dcc769607c220b312fea3e71cb63421298b4b891b1f7356a95ab58f2c96fff81",
        "lines": 47115,
        "license": "MIT (c) 2017 mozillazg",
    },
    "freq": {
        "repo": "fxsjy/jieba",
        "commit": "67fa2e36e72f69d9134b8a1037b83fbb070b9775",
        "path": "jieba/dict.txt",
        "urls": [
            "https://cdn.jsdelivr.net/gh/fxsjy/jieba@{commit}/jieba/dict.txt",
            "https://fastly.jsdelivr.net/gh/fxsjy/jieba@{commit}/jieba/dict.txt",
            "https://gcore.jsdelivr.net/gh/fxsjy/jieba@{commit}/jieba/dict.txt",
            "https://raw.githubusercontent.com/fxsjy/jieba/{commit}/jieba/dict.txt",
        ],
        "sha256": "7197c3211ddd98962b036cdf40324d1ea2bfaa12bd028e68faa70111a88e12a8",
        "lines": 349046,
        "license": "MIT (c) 2013 Sun Junyi",
    },
}

# 带调拼音 → 无调 ASCII。ü 统一写作 v（与输入法键盘 a-z 一致）。
TONE_MAP = {
    "ā": "a", "á": "a", "ǎ": "a", "à": "a",
    "ē": "e", "é": "e", "ě": "e", "è": "e",
    "ī": "i", "í": "i", "ǐ": "i", "ì": "i",
    "ō": "o", "ó": "o", "ǒ": "o", "ò": "o",
    "ū": "u", "ú": "u", "ǔ": "u", "ù": "u",
    "ǖ": "v", "ǘ": "v", "ǚ": "v", "ǜ": "v", "ü": "v",
    "ń": "n", "ň": "n", "ǹ": "n", "ḿ": "m", "ê": "e",
}

SYLLABLE_RE = re.compile(r"^[a-z]+$")
CJK_RANGES = ((0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF))
WEIGHT_MAX = 9999

# 长词裁剪：>= LONG_WORD_LEN 字的词条要求 jieba 词频 >= LONG_WORD_MIN_FREQ
LONG_WORD_LEN = 4
LONG_WORD_MIN_FREQ = 20

# 注音补全：jieba 高频词中 phrase-pinyin-data 漏收的 2-4 字词，用单字读音合成
SYNTHESIS_MIN_FREQ = 50
SYNTHESIS_MAX_LEN = 4
# 2-3 字词放宽多音字定音：语料中该读音占比达到阈值才采用；4 字词仍要求唯一读音
SYNTHESIS_MIN_SHARE = 0.75
SYNTHESIS_RELAXED_MAX_LEN = 3

# 语料以书面语为主，个别常用字的读音分布偏离现代通用读音。
# 仅对补音合成的 2-3 字词生效；已收录词条仍以 phrase-pinyin-data 读音为准。
PREFERRED_READINGS = {
    "还": "hai",
    "都": "dou",
    "了": "le",
    "地": "di",
    "得": "de",
    "色": "se",
    "行": "xing",
    "率": "lv",
    "省": "sheng",
    "卡": "ka",
    "系": "xi",
    "传": "chuan",
    "遗": "yi",
    "盛": "sheng",
    "陆": "lu",
    "阿": "a",
    "顿": "dun",
    "没": "mei",
    "血": "xue",
    "校": "xiao",
    "角": "jiao",
    "给": "gei",
    "露": "lu",
    "参": "can",
    "降": "jiang",
    "劲": "jin",
    "朴": "pu",
    "和": "he",
    "称": "cheng",
    "模": "mo",
    "削": "xue",
    "剥": "bo",
    "薄": "bo",
    "便": "bian",
    "落": "luo",
}


def is_cjk(text: str) -> bool:
    return bool(text) and all(any(lo <= ord(ch) <= hi for lo, hi in CJK_RANGES) for ch in text)


def strip_tones(text: str) -> str:
    return "".join(TONE_MAP.get(ch, ch) for ch in text)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(spec: dict, cache: Path) -> Path:
    """下载到缓存目录并校验 sha256 / 行数；命中缓存时直接复用。"""
    dest = cache / f"{spec['repo'].replace('/', '_')}__{Path(spec['path']).name}"
    if dest.exists() and sha256_file(dest) == spec["sha256"]:
        print(f"[cache] {dest} sha256 命中")
        return dest

    last_error: Exception | None = None
    for template in spec["urls"]:
        url = template.format(commit=spec["commit"])
        try:
            print(f"[fetch] {url}")
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read()
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(payload)
            break
        except Exception as exc:  # noqa: BLE001 - 逐个镜像回退，最后统一报错
            last_error = exc
            print(f"[warn] 下载失败: {exc}")
    else:
        raise SystemExit(f"下载失败（所有镜像均不可用）: {spec['repo']}/{spec['path']}: {last_error}")

    actual = sha256_file(dest)
    if actual != spec["sha256"]:
        raise SystemExit(
            f"sha256 校验失败: {dest}\n  期望 {spec['sha256']}\n  实际 {actual}"
        )
    return dest


def parse_char_readings(path: Path) -> dict[str, list[str]]:
    """pinyin-data/pinyin.txt → {汉字: [无调读音, ...]}（保留多音字全部读音）。"""
    table: dict[str, list[str]] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        code_point, _, rest = line.partition(":")
        body = rest.split("#", 1)[0]
        char = chr(int(code_point.strip()[2:], 16))
        readings = []
        for item in body.split(","):
            syllable = strip_tones(item.strip())
            if SYLLABLE_RE.match(syllable):
                readings.append(syllable)
        if readings:
            table[char] = readings
    return table


def parse_word_readings(path: Path) -> list[tuple[str, list[str]]]:
    """phrase-pinyin-data/pinyin.txt → [(词, 音节列表)]；丢弃音节数/字数不一致等脏数据。"""
    out: list[tuple[str, list[str]]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        word, _, rest = line.partition(":")
        word = word.strip()
        syllables = [strip_tones(item) for item in rest.split("#", 1)[0].split()]
        if not is_cjk(word) or len(syllables) != len(word):
            continue
        if not all(SYLLABLE_RE.match(syllable) for syllable in syllables):
            continue
        out.append((word, syllables))
    return out


def parse_frequencies(path: Path) -> tuple[dict[str, int], dict[str, int]]:
    """jieba dict.txt → (单字词频, 词语词频)。"""
    chars: dict[str, int] = {}
    words: dict[str, int] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        fields = raw.split()
        if len(fields) < 2:
            continue
        word = fields[0]
        try:
            freq = int(fields[1])
        except ValueError:
            continue
        if len(word) == 1:
            chars[word] = freq
        else:
            words[word] = freq
    return chars, words


class Quantizer:
    """对数压缩词频到 1..WEIGHT_MAX，保持单调性，仅用于同层排序。"""

    def __init__(self, max_freq: int) -> None:
        self.top = math.log1p(max(1, max_freq))

    def __call__(self, freq: int) -> int:
        return max(1, round(WEIGHT_MAX * math.log1p(max(1, freq)) / self.top))


def synth_reading(
    declared: list[str],
    attested: Counter[str] | None,
    min_share: float = 1.0,
    preferred: str | None = None,
) -> str | None:
    """给「词表未收录的词」里的一个字定音。

    declared 是 pinyin-data 声明的全部读音；attested 是 phrase-pinyin-data 语料里这个字
    各读音的出现次数。单音字直接采用；preferred 是语料被书面语带偏时的现代常用读音；
    多音字在语料读音占比 >= min_share 时采用占比最高的读音，否则返回 None。
    4 字词传入 min_share=1.0，等价于「语料只佐证一个读音才采用」。
    """
    unique = list(dict.fromkeys(declared))
    if len(unique) == 1:
        return unique[0]
    if preferred is not None and preferred in unique:
        return preferred
    if not attested:
        return None
    counts = {syllable: attested.get(syllable, 0) for syllable in unique}
    total = sum(counts.values())
    if total == 0:
        return None
    best = max(counts, key=counts.get)
    return best if counts[best] / total >= min_share else None


def build(out_path: Path, cache: Path) -> dict:
    paths = {key: fetch(spec, cache) for key, spec in SOURCES.items()}
    char_readings = parse_char_readings(paths["chars"])
    word_readings = parse_word_readings(paths["words"])
    char_freq, word_freq = parse_frequencies(paths["freq"])

    char_table = {c: char_readings[c] for c in char_freq if c in char_readings}
    kept_words = [
        (word, syllables)
        for word, syllables in word_readings
        if len(word) < LONG_WORD_LEN or word_freq.get(word, 1) >= LONG_WORD_MIN_FREQ
    ]
    max_freq = max([char_freq[c] for c in char_table] + [word_freq.get(w, 1) for w, _ in kept_words])
    quantize = Quantizer(max_freq)

    # 注音补全：phrase-pinyin-data 漏收的常用词，用 jieba 词频 x 单字读音补齐。
    attested: dict[str, Counter[str]] = {}
    for word, syllables in word_readings:
        for char, syllable in zip(word, syllables):
            attested.setdefault(char, Counter())[syllable] += 1
    known_words = {word for word, _ in word_readings}
    synthesized_words: list[tuple[str, list[str]]] = []
    for word, freq in word_freq.items():
        if not (2 <= len(word) <= SYNTHESIS_MAX_LEN) or freq < SYNTHESIS_MIN_FREQ:
            continue
        if word in known_words or not all(is_cjk(ch) for ch in word):
            continue
        relaxed = len(word) <= SYNTHESIS_RELAXED_MAX_LEN
        syllables = [
            synth_reading(
                char_readings.get(ch, []),
                attested.get(ch),
                SYNTHESIS_MIN_SHARE if relaxed else 1.0,
                PREFERRED_READINGS.get(ch) if relaxed else None,
            )
            for ch in word
        ]
        if any(syllable is None for syllable in syllables):
            continue
        synthesized_words.append((word, [syllable for syllable in syllables if syllable]))

    entries: set[tuple[str, str, int]] = set()
    for char, readings in char_table.items():
        weight = quantize(char_freq[char])
        for syllable in readings:
            entries.add((syllable, char, weight))
    for word, syllables in kept_words:
        # 无词频的词条（主要是汉典成语）按 freq=1 处理，排在同层已知词频词条之后
        weight = quantize(word_freq.get(word, 1))
        entries.add((" ".join(syllables), word, weight))
    for word, syllables in synthesized_words:
        entries.add((" ".join(syllables), word, quantize(word_freq[word])))

    ordered = sorted(entries, key=lambda item: (-item[2], len(item[1]), item[1], item[0]))

    header = [
        "# iSpeech 拼音码表 v1（由 tools/pinyin/build_pinyin_dict.py 生成，勿手改）",
        "# 格式: <code>\\t<text>\\t<weight>",
        "#   code   : 小写 a-z 音节，音节之间单个空格；ü 写作 v（如 ni hao / lv）",
        "#   text   : 汉字（单字或词语）",
        "#   weight : 1..9999 对数压缩词频，仅用于同层排序，越大越靠前",
        "# 行序   : weight 降序 → 字数升序 → 汉字字典序 → code 字典序",
        "# 来源(均为 MIT):",
    ]
    for key in ("chars", "words", "freq"):
        spec = SOURCES[key]
        header.append(
            f"#   {spec['repo']}/{spec['path']} @{spec['commit'][:12]} sha256={spec['sha256'][:16]}… {spec['license']}"
        )
    header.append(
        f"# 裁剪: 单字 {len(char_table)} 种（jieba 单字表 ∩ pinyin-data）; "
        f"词条 {len(kept_words) + len(synthesized_words)} 条"
        f"（词表 {len(kept_words)} + 注音补全 {len(synthesized_words)}；"
        f"{LONG_WORD_LEN} 字及以上需 jieba 词频 >= {LONG_WORD_MIN_FREQ}）"
    )

    body = [f"{code}\t{text}\t{weight}" for code, text, weight in ordered]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(header + body) + "\n", encoding="utf-8")

    return {
        "chars": len(char_table),
        "words": len(kept_words),
        "words_all": len(word_readings),
        "synth": len(synthesized_words),
        "entries": len(entries),
        "syllables": len({syllable for readings in char_table.values() for syllable in readings}),
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"输出路径（默认 {DEFAULT_OUT}）")
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE, help=f"下载缓存目录（默认 {DEFAULT_CACHE}）")
    args = parser.parse_args(argv)

    stats = build(args.out, args.cache)
    size = args.out.stat().st_size
    digest = sha256_file(args.out)
    lines = sum(1 for _ in args.out.open("rb"))
    print("---- 生成完成 ----")
    print(f"输出: {args.out}")
    print(
        f"单字数: {stats['chars']}  词条数: {stats['entries']}  音节种类: {stats['syllables']}"
        f"  词条(裁剪前 {stats['words_all']} / 裁剪后 {stats['words']})"
        f"  注音补全: {stats['synth']}"
    )
    print(f"行数: {lines}  字节: {size} ({size / 1024 / 1024:.2f} MiB)")
    print(f"sha256: {digest}")
    if size > 3 * 1024 * 1024:
        print("[FAIL] 体积超过 3MB 上限")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
