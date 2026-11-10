#!/usr/bin/env python3
"""把搜狗拼音的内置常用语（`sgim_phrases.bin`）转换成 iSpeech 快捷短语规则。

证据来源（macOS 搜狗拼音 v6.25.3.12004）
----------------------------------------
`Contents/Resources/SogouImeKernelResources.bundle/SogouInput/sgim_phrases.bin`
该文件是 UTF-16LE 的「常用语短语池 + 缩写表」：
  * 短语池：诗词与网络常用语整句（锄禾日当午 / 床前明月光 / 好呆槑 / 牛牪犇 …）
  * 缩写表：小写触发码（chrdw / cqmyg / hdhxt / haodai / wocao / niubi …）
两者分表存放，映射索引未解出。

配对方式（用证据，不猜读音）
----------------------------
每个汉字可能有多个读音，按「所有读音的首字母组合」与搜狗缩写表求交：
  * 命中（如 汗∈{g,h}、土∈{c,d,t} 组合出 hdhxt）→ 采用搜狗原码
  * 多字短语额外尝试全拼组合（haodai → 好呆）
  * 完全配不上时用首选读音派生首字母，并在输出头注明「派生」
不臆造搜狗未出现的码；也不把候选词池（sgim_quick.bin）当常用语导入——
那会把普通词条塞进短语档位，抢占拼音候选。

输出格式与 `QuickPhraseEngine.fromText` 一致：
    trigger <TAB> label <TAB> content <TAB> exact <TAB> weight <TAB> p

用法
----
    python3 tools/import/import_sogou_quick_phrases.py \\
        --input "/Library/Input Methods/SogouInput.app/Contents/Resources/SogouImeKernelResources.bundle/SogouInput/sgim_phrases.bin" \\
        --output app/src/main/assets/sogou_phrases.txt
"""

from __future__ import annotations

import argparse
import itertools
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PINYIN_DICT = REPO_ROOT / "app" / "src" / "main" / "assets" / "pinyin86.dict"

CJK_RUN = re.compile(r"[㐀-䶿一-鿿]{2,12}")
TRIGGER = re.compile(r"[a-z]{2,12}")
NOISE = {"version", "and", "utf", "bin", "sgim"}

# 组合爆炸上限：5 字诗句 × 每字最多 3 读音 = 243，远低于该值
MAX_COMBOS = 512

PREFERRED_READINGS = {
    "还": "hai", "都": "dou", "了": "le", "地": "di", "得": "de",
    "色": "se", "行": "xing", "率": "lv", "省": "sheng", "卡": "ka",
    "系": "xi", "传": "chuan", "遗": "yi", "盛": "sheng", "陆": "lu",
    "阿": "a", "顿": "dun", "没": "mei", "血": "xue", "校": "xiao",
    "角": "jiao", "给": "gei", "露": "lu", "参": "can", "降": "jiang",
    "劲": "jin", "朴": "pu", "和": "he", "称": "cheng", "模": "mo",
    "削": "xue", "剥": "bo", "薄": "bo", "便": "bian", "落": "luo",
}


def load_char_readings(dict_path: Path) -> dict[str, list[str]]:
    """单字 -> 读音列表（首选在前：PREFERRED_READINGS，其次音节全局词频）。"""
    readings: dict[str, list[tuple[str, int]]] = {}
    syllable_freq: dict[str, int] = {}
    if not dict_path.exists():
        return {}
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
            if len(text) == 1:
                readings.setdefault(text, []).append((code, weight))
            else:
                for syllable in code.split(" "):
                    if syllable:
                        syllable_freq[syllable] = syllable_freq.get(syllable, 0) + weight
    out: dict[str, list[str]] = {}
    for ch, items in readings.items():
        ordered = sorted(items, key=lambda r: (-syllable_freq.get(r[0], 0), -r[1], r[0]))
        syllables = [code for code, _ in ordered]
        preferred = PREFERRED_READINGS.get(ch)
        if preferred is not None and preferred in syllables:
            syllables.remove(preferred)
            syllables.insert(0, preferred)
        out[ch] = list(dict.fromkeys(syllables))
    return out


def extract(data: bytes) -> tuple[list[str], list[str]]:
    text = data.decode("utf-16le", "ignore")
    phrases = list(dict.fromkeys(CJK_RUN.findall(text)))
    triggers = [tok for tok in TRIGGER.findall(text) if tok not in NOISE and len(tok) >= 2]
    return phrases, list(dict.fromkeys(triggers))


def match_trigger(
    phrase: str,
    readings: dict[str, list[str]],
    trigger_set: set[str],
    used: set[str],
) -> tuple[str, str]:
    """返回 (触发码, 来源)；来源为「搜狗原码」或「派生」。"""
    options = [readings.get(ch, []) for ch in phrase]
    if any(not opts for opts in options):
        return "", "派生"

    # 1) 首字母组合与搜狗缩写表求交（证据命中）
    initials_options = [[s[0] for s in opts] for opts in options]
    total = 1
    for choices in initials_options:
        total *= len(set(choices))
        if total > MAX_COMBOS:
            break
    if total <= MAX_COMBOS:
        for combo in itertools.product(*[list(dict.fromkeys(c)) for c in initials_options]):
            candidate = "".join(combo)
            if candidate in trigger_set and candidate not in used:
                return candidate, "搜狗原码"

    # 2) 全拼组合（仅 2-4 字短语，避免组合过大）
    if len(phrase) <= 4:
        full_total = 1
        for opts in options:
            full_total *= len(opts)
            if full_total > MAX_COMBOS:
                break
        if full_total <= MAX_COMBOS:
            for combo in itertools.product(*options):
                candidate = "".join(combo)
                if candidate in trigger_set and candidate not in used:
                    return candidate, "搜狗原码"

    # 3) 派生：首选读音首字母
    derived = "".join(opts[0][0] for opts in options)
    return derived, "派生"


def main() -> None:
    parser = argparse.ArgumentParser(description="转换搜狗内置常用语为 iSpeech 快捷短语")
    parser.add_argument("--input", required=True, type=Path, help="sgim_phrases.bin")
    parser.add_argument("--output", required=True, type=Path, help="输出规则文件")
    args = parser.parse_args()

    if not args.input.exists():
        sys.stderr.write(f"输入文件不存在：{args.input}\n")
        raise SystemExit(1)

    readings = load_char_readings(PINYIN_DICT)
    if not readings:
        sys.stderr.write(f"拼音码表缺失：{PINYIN_DICT}\n")
        raise SystemExit(3)

    phrases, triggers = extract(args.input.read_bytes())
    trigger_set = set(triggers)
    used: set[str] = set()
    seen: set[str] = set()
    final: list[tuple[str, str, str]] = []
    for phrase in phrases:
        trigger, source = match_trigger(phrase, readings, trigger_set, used)
        if len(trigger) < 2 or trigger in seen:
            continue
        seen.add(trigger)
        used.add(trigger)
        final.append((trigger, phrase, source))

    matched = sum(1 for _, _, s in final if s == "搜狗原码")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        handle.write("# 搜狗拼音内置常用语（由 tools/import/import_sogou_quick_phrases.py 生成）\n")
        handle.write(f"# 证据: sgim_phrases.bin（v6.25.3.12004）; 触发码: 搜狗原码 {matched} 条, 派生 {len(final) - matched} 条\n")
        handle.write("# 字段: trigger <TAB> label <TAB> content <TAB> mode <TAB> weight <TAB> p\n")
        for trigger, phrase, _ in final:
            # 五笔态组合缓冲上限 4 位：超过 4 位的触发码用前缀模式，打前 4 位即可命中
            mode = "exact" if len(trigger) <= 4 else "prefix"
            handle.write(f"{trigger}\t{phrase}\t{phrase}\t{mode}\t90\tp\n")

    sys.stdout.write(
        "转换完成\n"
        f"  输入      : {args.input}\n"
        f"  输出      : {args.output}\n"
        f"  短语池    : {len(phrases)}\n"
        f"  缩写表    : {len(triggers)}\n"
        f"  写入规则  : {len(final)}（搜狗原码 {matched} / 派生 {len(final) - matched}）\n"
    )


if __name__ == "__main__":
    main()