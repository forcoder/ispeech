#!/usr/bin/env python3
"""拼音码表 + 拼音引擎离线自检（纯 Python 3 标准库，不联网、不编译）。

复刻 app/src/main/java/com/ispeech/engine/{PinyinDictLoader,PinyinEngine}.kt 的
解析 / 索引 / 分层排序 / 模糊音逻辑，对 app/src/main/assets/pinyin86.dict 做
格式校验与行为断言。Kotlin 侧与这里不一致时本脚本的断言会失效——两边要同时改。

运行（仓库根目录或任意目录均可）：
    python3 tools/verify/check_pinyin.py

输出：每项一行 PASS / FAIL，末尾 `SUMMARY: passed=N failed=M warned=K`，
任一 FAIL 时退出码为 1。
"""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DICT_PATH = REPO_ROOT / "app" / "src" / "main" / "assets" / "pinyin86.dict"
ENGINE_PATH = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "engine" / "PinyinEngine.kt"

# ---- 与 Kotlin 侧保持一致的常量 ----
CODE_PATTERN = re.compile(r"^[a-z]+( [a-z]+)*$")
MAX_WEIGHT = 65535
MAX_CODE_LENGTH = 32
MAX_SEGMENT_LENGTH = 6
MIN_PREFIX_LENGTH = 2
# 前缀扫描按「前缀键个数」限流，而不是词条数：按词条数截断会漏掉
# 字典序靠后的高频词（zhong -> 中国、ni -> 你好）。
PREFIX_SCAN_LIMIT = 4000
PREFIX_PER_KEY_LIMIT = 4
PREFIX_POOL_LIMIT = 256
SINGLE_PER_TIER_LIMIT = 6
TIER_EXACT = 0
TIER_FUZZY_EXACT = 1
TIER_PREFIX = 2
TIER_INITIALS = 3
TIER_INITIALS_FUZZY = 4
CJK_RANGES = ((0x3400, 0x4DBF), (0x4E00, 0x9FFF), (0xF900, 0xFAFF))


class Report:
    def __init__(self) -> None:
        self.passed = 0
        self.failed = 0
        self.warned = 0

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        if ok:
            self.passed += 1
            print(f"PASS {name}")
        else:
            self.failed += 1
            print(f"FAIL {name}" + (f" —— {detail}" if detail else ""))
        return ok

    def warn(self, name: str, detail: str = "") -> None:
        self.warned += 1
        print(f"WARN {name}" + (f" —— {detail}" if detail else ""))

    def info(self, text: str) -> None:
        print(f"     {text}")


# ---------------- 解析（复刻 PinyinDictLoader.parse） ----------------


class Entry:
    __slots__ = ("code", "text", "weight", "full", "initials", "is_word")

    def __init__(self, code: str, text: str, weight: int) -> None:
        self.code = code
        self.text = text
        self.weight = weight
        self.full = code.replace(" ", "")
        self.initials = "".join(syllable[0] for syllable in code.split(" ") if syllable)
        self.is_word = len(text) > 1


def parse_dict(text: str) -> list[Entry]:
    entries: list[Entry] = []
    seen: set[str] = set()
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 2:
            continue
        code = fields[0].strip().lower()
        word = fields[1].strip()
        if not word or not CODE_PATTERN.match(code):
            continue
        weight = 0
        if len(fields) >= 3:
            try:
                weight = min(MAX_WEIGHT, max(0, int(fields[2])))
            except ValueError:
                weight = 0
        key = code + "\u0000" + word
        if key in seen:
            continue
        seen.add(key)
        entries.append(Entry(code, word, weight))
    return entries


# ---------------- 模糊音（复刻 PinyinEngine.kt 的顶层函数） ----------------


def fuzzy_syllable(syllable: str) -> str:
    if syllable.startswith("zh"):
        normalized = "z" + syllable[2:]
    elif syllable.startswith("ch"):
        normalized = "c" + syllable[2:]
    elif syllable.startswith("sh"):
        normalized = "s" + syllable[2:]
    elif syllable.startswith("n"):
        normalized = "l" + syllable[1:]
    else:
        normalized = syllable
    if normalized.endswith(("ang", "eng", "ing")):
        return normalized[:-1]
    return normalized


def fuzzy_syllables(code: str) -> str:
    return "".join(fuzzy_syllable(s) for s in code.split(" ") if s)


def fuzzy_initials(initials: str) -> str:
    return initials.replace("n", "l")


def segment_syllables(code: str, syllables: set[str]) -> list[str]:
    if not code:
        return []
    out: list[str] = []
    index = 0
    while index < len(code):
        matched = 0
        length = min(MAX_SEGMENT_LENGTH, len(code) - index)
        while length >= 1:
            if code[index:index + length] in syllables:
                matched = length
                break
            length -= 1
        if matched == 0:
            out.append(code[index:])
            break
        out.append(code[index:index + matched])
        index += matched
    return out


def sanitize(text: str) -> str:
    return "".join(ch.lower() for ch in text if ch.isascii() and ch.isalpha())


# ---------------- 引擎（复刻 PinyinEngine） ----------------


class Match:
    __slots__ = ("entry", "tier")

    def __init__(self, entry: Entry, tier: int) -> None:
        self.entry = entry
        self.tier = tier


def match_rank(match: Match) -> tuple:
    entry = match.entry
    return (match.tier, -entry.weight, len(entry.text), entry.text)


class PinyinEngine:
    def __init__(self, entries: list[Entry], fuzzy: bool = False, initials: bool = True) -> None:
        self.fuzzy = fuzzy
        self.initials_on = initials
        full_table: dict[str, list[Entry]] = {}
        initial_table: dict[str, list[Entry]] = {}
        syllables: set[str] = set()
        for entry in entries:
            if not entry.is_word:
                syllables.add(entry.code)
            full_key = fuzzy_syllables(entry.code) if fuzzy else entry.full
            full_table.setdefault(full_key, []).append(entry)
            if initials:
                initial_key = fuzzy_initials(entry.initials) if fuzzy else entry.initials
                initial_table.setdefault(initial_key, []).append(entry)
        self.syllables = syllables
        self.by_full = {
            key: sorted(value, key=lambda e: (-e.weight, len(e.text), e.text))
            for key, value in full_table.items()
        }
        self.sorted_full = sorted(self.by_full)
        self.by_initials = (
            {
                key: sorted(value, key=lambda e: (-e.weight, len(e.text), e.text))
                for key, value in initial_table.items()
            }
            if initials
            else {}
        )

    def lookup(self, code: str, limit: int, with_tier: bool = False):
        """复刻 PinyinEngine.lookup；with_tier=True 时返回 (text, tier, weight)。"""
        if limit <= 0:
            return []
        text = sanitize(code)
        if not text or len(text) > MAX_CODE_LENGTH or not self.by_full:
            return []
        pool: list[Match] = []
        seen: set[str] = set()

        def add(entry: Entry, tier: int) -> None:
            if entry.text in seen:
                return
            seen.add(entry.text)
            pool.append(Match(entry, tier))

        # 1) 完全匹配
        bucket = self.by_full.get(self._index_key(text))
        if bucket:
            for entry in bucket:
                tier = TIER_EXACT if entry.full == text else TIER_FUZZY_EXACT
                add(entry, tier)

        # 2) 全拼前缀（全量收集后再排序，不做先到先得截断）
        def scan_prefix() -> None:
            key = self._index_key(text)
            index = self._lower_bound(key)
            keys = 0
            while index < len(self.sorted_full) and keys < PREFIX_SCAN_LIMIT:
                candidate = self.sorted_full[index]
                if not candidate.startswith(key):
                    return
                for entry in self.by_full[candidate][:PREFIX_PER_KEY_LIMIT]:
                    add(entry, TIER_PREFIX)
                if len(pool) > PREFIX_POOL_LIMIT:
                    pool.sort(key=match_rank)
                    del pool[PREFIX_POOL_LIMIT // 2:]
                index += 1
                keys += 1

        if len(text) >= MIN_PREFIX_LENGTH:
            scan_prefix()

        # 3) 简拼
        if self.initials_on:
            bucket = self.by_initials.get(fuzzy_initials(text) if self.fuzzy else text)
            if bucket:
                for entry in bucket:
                    tier = TIER_INITIALS if entry.initials == text else TIER_INITIALS_FUZZY
                    add(entry, tier)

        pool.sort(key=match_rank)
        # 同一层里的单字限量：ni / zhong 这类音节的同音生僻字能塞满整个候选条，
        # 把「你好 / 中国」这种高频词（在下一层）挤出去。
        result: list[Match] = []
        tier = None
        singles_in_tier = 0
        for match in pool:
            if len(result) >= limit:
                break
            if match.tier != tier:
                tier = match.tier
                singles_in_tier = 0
            if not match.entry.is_word:
                if singles_in_tier >= SINGLE_PER_TIER_LIMIT:
                    continue
                singles_in_tier += 1
            result.append(match)
        if with_tier:
            return [(match.entry.text, match.tier, match.entry.weight) for match in result]
        return [match.entry.text for match in result]

    def _index_key(self, code: str) -> str:
        if not self.fuzzy:
            return code
        segments = segment_syllables(code, self.syllables)
        if not segments:
            return code
        return "".join(fuzzy_syllable(segment) for segment in segments)

    def _lower_bound(self, key: str) -> int:
        low, high = 0, len(self.sorted_full)
        while low < high:
            mid = (low + high) // 2
            if self.sorted_full[mid] < key:
                low = mid + 1
            else:
                high = mid
        return low


# ---------------- 码表格式校验 ----------------


def check_format(report: Report, text: str, raw: bytes) -> dict:
    lines = text.splitlines()
    data_lines = [line.strip() for line in lines if line.strip() and not line.strip().startswith("#")]
    fields_list = [line.split("\t") for line in data_lines]
    report.check(
        "码表 头部标记",
        any(line.startswith("# iSpeech 拼音码表 v1") for line in lines),
        "缺少 `# iSpeech 拼音码表 v1` 头注释",
    )
    bad_fields = [line for line in data_lines if len(line.split("\t")) != 3]
    report.check("码表 每行 3 个 tab 字段", not bad_fields, f"{len(bad_fields)} 行异常，例如 {bad_fields[:1]}")

    bad_code = [fields[0] for fields in fields_list if len(fields) == 3 and not CODE_PATTERN.match(fields[0])]
    report.check("码表 code 全为小写音节 + 单空格", not bad_code, f"{len(bad_code)} 行非法，例如 {bad_code[:2]}")

    bad_weight = [
        fields[2]
        for fields in fields_list
        if len(fields) == 3 and (not fields[2].isdigit() or not 1 <= int(fields[2]) <= 9999)
    ]
    report.check("码表 weight 为 1..9999 整数", not bad_weight, f"{len(bad_weight)} 行非法，例如 {bad_weight[:2]}")

    bad_text = [
        fields[1]
        for fields in fields_list
        if len(fields) == 3
        and not all(any(lo <= ord(ch) <= hi for lo, hi in CJK_RANGES) for ch in fields[1])
    ]
    report.check("码表 text 全为汉字", not bad_text, f"{len(bad_text)} 行含非汉字，例如 {bad_text[:2]}")

    keys = [line.rsplit("\t", 1)[0] for line in data_lines]
    report.check("码表 无重复 (code, text)", len(keys) == len(set(keys)), f"重复 {len(keys) - len(set(keys))} 条")

    entries = parse_dict(text)
    char_entries = [e for e in entries if not e.is_word]
    word_entries = [e for e in entries if e.is_word]
    report.check(
        "码表 解析后条数与文件行数一致",
        len(entries) == len(data_lines),
        f"文件 {len(data_lines)} 行 / 解析 {len(entries)} 条（非法行会被 parse 跳过）",
    )
    report.check(
        "码表 规模（单字 >= 10000 且 词组 >= 20000）",
        len(char_entries) >= 10000 and len(word_entries) >= 20000,
        f"实际 单字 {len(char_entries)} / 词组 {len(word_entries)}",
    )
    report.check("码表 体积 < 3 MiB", len(raw) < 3 * 1024 * 1024, f"实际 {len(raw) / 1024 / 1024:.2f} MiB")

    header = re.search(r"# 裁剪: 单字 (\d+) 种.*?词条 (\d+) 条", text)
    if header:
        expected_chars, expected_words = int(header.group(1)), int(header.group(2))
        actual_chars = len({e.text for e in char_entries})
        report.check(
            "码表 头注释统计与实际一致",
            expected_chars == actual_chars and expected_words == len(word_entries),
            f"头注释 {expected_chars} 单字 / {expected_words} 词条，实际 {actual_chars} / {len(word_entries)}",
        )
    else:
        report.warn("码表 头注释统计", "未找到 `# 裁剪:` 统计行，跳过")

    report.info(f"词条 {len(entries)} 条（单字行 {len(char_entries)} / 词组行 {len(word_entries)}）")
    report.info(f"文件 {len(raw)} 字节（{len(raw) / 1024 / 1024:.2f} MiB），sha256 {hashlib.sha256(raw).hexdigest()}")
    return {"entries": entries}


def check_engine_source(report: Report) -> None:
    """Kotlin 侧常量与 Python 复刻一致；两边不一致时本脚本断言失效。"""
    if not ENGINE_PATH.exists():
        report.check("引擎源码存在", False, f"缺少 {ENGINE_PATH}")
        return
    source = ENGINE_PATH.read_text(encoding="utf-8")
    report.check("引擎源码存在", True)
    for needle in (
        "const val MIN_PREFIX_LENGTH = 2",
        "const val PREFIX_SCAN_LIMIT = 4000",
        "const val PREFIX_PER_KEY_LIMIT = 4",
        "const val PREFIX_POOL_LIMIT = 256",
        "const val SINGLE_PER_TIER_LIMIT = 6",
        "const val MAX_CODE_LENGTH = 32",
        "private const val MAX_SEGMENT_LENGTH = 6",
        "class PinyinEngine(",
        "override fun lookup(input: String, limit: Int): List<LookupCandidate>",
        "LookupKind.PINYIN",
    ):
        report.check(f"引擎源码含 `{needle}`", needle in source, "Kotlin 侧改动后需同步本脚本")


def main() -> int:
    report = Report()
    if not DICT_PATH.exists():
        print(f"FAIL 码表文件存在 —— 缺少 {DICT_PATH}")
        print("SUMMARY: passed=0 failed=1 warned=0")
        return 1

    raw = DICT_PATH.read_bytes()
    text = raw.decode("utf-8")
    stats = check_format(report, text, raw)
    check_engine_source(report)

    entries = stats["entries"]
    plain = PinyinEngine(entries, fuzzy=False)
    fuzzy = PinyinEngine(entries, fuzzy=True)
    no_initials = PinyinEngine(entries, fuzzy=False, initials=False)

    def texts(engine: PinyinEngine, code: str, limit: int = 18) -> list[str]:
        return engine.lookup(code, limit)

    # --- 全拼命中 ---
    report.check("全拼 `nihao` 命中 你好 且排第一", texts(plain, "nihao")[:1] == ["你好"], f"实际 {texts(plain, 'nihao')[:3]}")
    report.check("全拼 `zhongguo` 命中 中国", "中国" in texts(plain, "zhongguo"), f"实际 {texts(plain, 'zhongguo')[:5]}")
    report.check("全拼 `ni` 命中 你", "你" in texts(plain, "ni"), f"实际 {texts(plain, 'ni')[:5]}")
    report.check("全拼 `lv` 命中 绿（ü 记作 v）", "绿" in texts(plain, "lv"), f"实际 {texts(plain, 'lv')[:5]}")
    report.check("全拼 `chang` 命中 长", "长" in texts(plain, "chang"), f"实际 {texts(plain, 'chang')[:5]}")
    report.check("全拼 `zhang` 命中 长（多音字两读都可检）", "长" in texts(plain, "zhang"), f"实际 {texts(plain, 'zhang')[:5]}")

    # --- 词表缺口回归：只含 phrase-pinyin-data 时，jiudian / mingtian / shouji 这类常用词
    #     整词缺失，只能逐字输入；注音补全后必须能整词命中 ---
    for code, word in (("jiudian", "酒店"), ("jintian", "今天"), ("mingtian", "明天"), ("shouji", "手机")):
        report.check(f"补全词 `{code}` 命中 {word}", word in texts(plain, code, 20), f"实际 {texts(plain, code, 8)}")

    # --- 简拼命中 ---
    report.check("简拼 `nh` 命中 你好", "你好" in texts(plain, "nh"), f"实际 {texts(plain, 'nh')[:5]}")
    report.check("简拼 `zg` 命中 中国", "中国" in texts(plain, "zg"), f"实际 {texts(plain, 'zg')[:5]}")
    report.check("简拼 `n` 命中 你（单字母）", "你" in texts(plain, "n"), f"实际 {texts(plain, 'n')[:5]}")
    report.check("关闭简拼后 `nh` 不再命中 你好", "你好" not in texts(no_initials, "nh"), f"实际 {texts(no_initials, 'nh')[:5]}")

    # --- 前缀匹配 ---
    prefix_hits = texts(plain, "zhong", 40)
    report.check("前缀 `zhong` 命中 中 与 中国", "中" in prefix_hits and "中国" in prefix_hits, f"实际 {prefix_hits[:8]}")
    report.check("前缀 `ni` 命中 你好（字典序靠后也不能漏）", "你好" in texts(plain, "ni", 60), f"实际 {texts(plain, 'ni', 12)}")
    rows = plain.lookup("zhong", 18, with_tier=True)
    tiers = [row[1] for row in rows]
    report.check("分层单调（精确 → 前缀 → 简拼）", tiers == sorted(tiers), f"分层序列 {tiers}")

    # --- 排序 ---
    rows = plain.lookup("ni", 18, with_tier=True)
    weights_ok = True
    for tier in {row[1] for row in rows}:
        tier_weights = [row[2] for row in rows if row[1] == tier]
        weights_ok = weights_ok and tier_weights == sorted(tier_weights, reverse=True)
    report.check("同层内词频降序", weights_ok, f"实际 {rows[:6]}")
    report.check(
        "整词完全匹配 > 全拼前缀：`nihao` 首位是精确层",
        plain.lookup("nihao", 9, with_tier=True)[0][1] == TIER_EXACT,
        f"实际 {plain.lookup('nihao', 3, with_tier=True)}",
    )
    report.check(
        "精确单字排在 你好（前缀层）之前",
        texts(plain, "ni", 60).index("你") < texts(plain, "ni", 60).index("你好"),
        f"你@{texts(plain, 'ni', 60).index('你')} 你好@{texts(plain, 'ni', 60).index('你好')}",
    )
    report.check(
        "简拼只出现在简拼层",
        all(row[1] in (TIER_INITIALS, TIER_INITIALS_FUZZY) for row in plain.lookup("nh", 9, with_tier=True)),
        f"实际 {plain.lookup('nh', 6, with_tier=True)}",
    )

    # --- 模糊音开 / 关差异 ---
    report.check("模糊音关：`zongguo` 不命中 中国", "中国" not in texts(plain, "zongguo"), f"实际 {texts(plain, 'zongguo')[:5]}")
    report.check("模糊音开：`zongguo` 命中 中国（z = zh）", "中国" in texts(fuzzy, "zongguo"), f"实际 {texts(fuzzy, 'zongguo')[:5]}")
    report.check("模糊音开：`zhongguo` 仍命中 中国", "中国" in texts(fuzzy, "zhongguo"), f"实际 {texts(fuzzy, 'zhongguo')[:5]}")

    report.check("模糊音关：`lh` 不命中 你好", "你好" not in texts(plain, "lh", 40), f"实际 {texts(plain, 'lh', 8)}")
    # 模糊开时 `lh` 合并两批：原样简拼 l+h（tier3）与 n→l 后的简拼（tier4）。
    # 原样简拼的词数随词表增长，`你好` 只能落在 tier4 且排在其后，故断言「能检出 +
    # 落在模糊简拼层」，不写固定的前 N 名（那种断言会随词表变化失效）。
    lh_fuzzy = fuzzy.lookup("lh", 10000, with_tier=True)
    lh_texts = [row[0] for row in lh_fuzzy]
    report.check("模糊音开：`lh` 命中 你好（n = l）", "你好" in lh_texts, f"实际候选 {len(lh_texts)} 条，命中={('你好' in lh_texts)}")
    lh_tier = next((row[1] for row in lh_fuzzy if row[0] == "你好"), None)
    report.check(
        "模糊音开：`lh` 里的 你好 落在模糊简拼层（经 n→l）",
        lh_tier == TIER_INITIALS_FUZZY,
        f"实际层={lh_tier}（期望 {TIER_INITIALS_FUZZY}）",
    )
    report.check("模糊音开：`nh` 仍命中 你好", "你好" in texts(fuzzy, "nh"), f"实际 {texts(fuzzy, 'nh')[:5]}")

    zang_fuzzy = fuzzy.lookup("zang", 24, with_tier=True)
    zang_texts = [row[0] for row in zang_fuzzy]
    report.check("模糊音关：`zang` 不命中 张（z ≠ zh）", "张" not in texts(plain, "zang"), f"实际 {texts(plain, 'zang')[:5]}")
    report.check("模糊音开：`zang` 命中 张 与 脏（z = zh / an = ang）", "张" in zang_texts and "脏" in zang_texts, f"实际 {zang_fuzzy[:5]}")
    report.check(
        "模糊音开：`zang` 中原样 脏 排在模糊 张 之前",
        zang_texts.index("脏") < zang_texts.index("张"),
        f"实际 {zang_fuzzy[:5]}",
    )

    report.check("模糊音关：`jing` 不命中 金（in ≠ ing）", "金" not in texts(plain, "jing"), f"实际 {texts(plain, 'jing')[:5]}")
    report.check("模糊音开：`jing` 命中 金", "金" in texts(fuzzy, "jing"), f"实际 {texts(fuzzy, 'jing')[:5]}")
    # 注意：`jin` 能匹配到 京（key=jing）是**前缀层**行为（少打一个字母），不是模糊音生效；
    # 模糊音关的真正判据是「京 不能出现在精确层」。
    jin_tier = next((tier for text, tier, _ in plain.lookup("jin", 60, with_tier=True) if text == "京"), None)
    report.check(
        "模糊音关：`jin` 里的 京 只能走前缀层（in ≠ ing）",
        jin_tier == TIER_PREFIX,
        f"京 所在层={jin_tier}（期望 {TIER_PREFIX}=前缀层）",
    )
    report.check("模糊音开：`jin` 命中 京", "京" in texts(fuzzy, "jin"), f"实际 {texts(fuzzy, 'jin')[:5]}")

    # --- 候选去重 ---
    duplicated = ""
    for query in ("ni", "nihao", "zhong", "zhongguo", "nh", "zg", "zang", "jing", "jin", "shi", "an", "lh"):
        for engine, label in ((plain, "plain"), (fuzzy, "fuzzy")):
            result = engine.lookup(query, 50)
            if len(result) != len(set(result)) and not duplicated:
                duplicated = f"{query}({label})"
    report.check("候选去重（display 不重复）", not duplicated, f"重复查询 {duplicated}")

    # --- 输入清洗 / 边界 ---
    report.check("输入清洗：`Ni2 Hao` 等价 `nihao`", texts(plain, "Ni2 Hao")[:5] == texts(plain, "nihao")[:5])
    report.check("空输入返回空", plain.lookup("", 9) == [])
    report.check("limit<=0 返回空", plain.lookup("ni", 0) == [])
    report.check("无候选返回空（`qqqqqqq`）", plain.lookup("qqqqqqq", 9) == [], f"实际 {texts(plain, 'qqqqqqq')[:3]}")
    report.check("limit 生效（`ni` 取 3 条）", len(plain.lookup("ni", 3)) == 3, f"实际 {len(plain.lookup('ni', 3))}")
    report.check("空词典不抛异常", PinyinEngine([], fuzzy=True).lookup("ni", 9) == [])
    report.check("超长输入返回空（> 32 字母）", plain.lookup("a" * 40, 9) == [])

    # --- 防回归断言：高频长词必须可简拼联想 ---
    jcss = plain.lookup("jcss", 12, with_tier=True)
    report.check(
        "高频长词『基础设施』必须可简拼联想（jcss）",
        len(jcss) > 0 and jcss[0][0] == "基础设施",
        f"实际 {jcss[:3]}",
    )

    rgzn = plain.lookup("rgzn", 12, with_tier=True)
    report.check(
        "高频长词『人工智能』必须可简拼联想（rgzn）",
        len(rgzn) > 0 and rgzn[0][0] == "人工智能",
        f"实际 {rgzn[:3]}",
    )

    print(f"SUMMARY: passed={report.passed} failed={report.failed} warned={report.warned}")
    return 1 if report.failed else 0


if __name__ == "__main__":
    sys.exit(main())
