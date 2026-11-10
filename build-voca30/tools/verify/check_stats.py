#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_stats.py —— 输入统计 + 简繁转换 离线校验（纯 Python 复刻）

没有 JDK / Android SDK，无法编译运行 Kotlin。本脚本沿用 tools/verify 的既有做法：
「解析 Kotlin 源码的常量与格式契约」+「在 Python 里复刻同一段逻辑」+「校验数据文件」。

覆盖内容：
  1. 源码契约：InputStats.kt / HanConversion.kt / StatsScreen.kt 的常量、落盘格式、
     原子写、失败降级等关键契约（源码一旦改坏格式，这里会 FAIL）
  2. 统计聚合（Python 复刻 InputStats 语义）：
     - 跨天分桶（今日 / 累计 / 分桶互不串味）
     - 命中率除零与 >1 上限
     - Top 词排序稳定性（频次降序 + 文本升序）与 Top 50 截断
     - 字数口径（码点、忽略空白、emoji 记 1 字）与词长上限
     - 序列化 → 反序列化往返一致、坏行容错、400 天裁剪
     - reset 清空且可继续记录
  3. 简繁映射数据（app/src/main/assets/han_s2t.txt / han_t2s.txt）：
     - 无坏行 / 无重复键 / 首选项 != 源字符 / 裁剪口径（GB2312 常用汉字集）
     - 体积上限与实际体积
     - 双向覆盖抽样（s2t→t2s 回环率）+ 已知常用字映射断言
     - Python 复刻 convert()：未命中原样保留、代理对不拆分
  4. 文档：docs/stats-and-hanconversion.md 是否记录了来源 / 许可 / sha256 / 接线片段

输出：每条检查一行 `PASS` / `FAIL` / `WARN` 前缀；末尾 `SUMMARY: ...`。
任一 FAIL 时退出码为 1，否则为 0。不联网、不依赖 cwd。
"""

from __future__ import annotations

import hashlib
import random
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
DATA_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "data"
SETTINGS_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "settings"
GEN_SCRIPT = ROOT / "tools" / "gen" / "build_han_assets.py"
DOC = ROOT / "docs" / "stats-and-hanconversion.md"

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


def check(ok: bool, message: str, warn: bool = False) -> bool:
    report("PASS" if ok else ("WARN" if warn else "FAIL"), message)
    return ok


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


# ═════════════════════════════════════════════════════════════
# 0. 通用：源码常量解析
# ═════════════════════════════════════════════════════════════

_CONST_RE = re.compile(r'(?:private\s+)?const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*("[^"]*"|-?\d+)')


def kotlin_constants(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for name, raw in _CONST_RE.findall(text):
        out[name] = raw[1:-1] if raw.startswith('"') else raw
    return out


# ═════════════════════════════════════════════════════════════
# 1. Python 复刻 InputStats（语义必须与 InputStats.kt 一致）
# ═════════════════════════════════════════════════════════════

COMMIT_TYPES = ("CANDIDATE", "ENGLISH", "PUNCTUATION", "PHRASE", "EMOJI")
WORD_TYPES = {"CANDIDATE", "PHRASE"}
HEADER = "# 输入统计（自动生成，可在设置页查看 / 重置）"
MARK_QUERY = "q"
MARK_COMMIT = "c"
MARK_WORD = "w"
TOP_WORDS_LIMIT = 50
MAX_TRACKED_DAYS = 400
MAX_TRACKED_WORDS = 500
MAX_WORD_LENGTH = 32


def char_count(text: str) -> int:
    """复刻 InputStats.charCountOf：按码点计数、忽略空白、代理对记 1 个字。"""
    count = 0
    i = 0
    while i < len(text):
        ch = text[i]
        cp = ord(ch)
        if 0xD800 <= cp <= 0xDBFF and i + 1 < len(text) and 0xDC00 <= ord(text[i + 1]) <= 0xDFFF:
            count += 1
            i += 2
        else:
            if not ch.isspace():
                count += 1
            i += 1
    return count


def escape_line_text(text: str) -> str:
    if not any(c in text for c in "\n\r\t\\"):
        return text
    return (
        text.replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )


def unescape_line_text(text: str) -> str:
    if "\\" not in text:
        return text
    out: list[str] = []
    i = 0
    mapping = {"n": "\n", "r": "\r", "t": "\t", "\\": "\\"}
    while i < len(text):
        ch = text[i]
        if ch == "\\" and i + 1 < len(text) and text[i + 1] in mapping:
            out.append(mapping[text[i + 1]])
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


class DayBucket:
    def __init__(self) -> None:
        self.commits = 0
        self.queries = 0
        self.by_type: dict[str, int] = {}
        self.by_type_chars: dict[str, int] = {}

    def chars(self) -> int:
        return sum(self.by_type_chars.values())

    def hit_rate(self) -> float:
        if self.queries <= 0:
            return 0.0
        return min(1.0, self.commits / self.queries)


class StatsModel:
    """InputStats 的 Python 复刻：same format, same semantics."""

    def __init__(self) -> None:
        self.days: dict[str, DayBucket] = {}
        self.words: dict[str, int] = {}

    # —— 写入 ——

    def record_commit(self, ctype: str, text: str, date_key: str) -> None:
        chars = char_count(text)
        bucket = self.days.setdefault(date_key, DayBucket())
        bucket.commits += 1
        bucket.by_type[ctype] = bucket.by_type.get(ctype, 0) + 1
        bucket.by_type_chars[ctype] = bucket.by_type_chars.get(ctype, 0) + chars
        if ctype in WORD_TYPES:
            word = text.strip()
            if word and char_count(word) <= MAX_WORD_LENGTH:
                self.words[word] = self.words.get(word, 0) + 1
        self.prune()

    def record_query(self, date_key: str) -> None:
        self.days.setdefault(date_key, DayBucket()).queries += 1
        self.prune()

    def reset(self) -> None:
        self.days.clear()
        self.words.clear()

    def prune(self) -> None:
        if len(self.days) > MAX_TRACKED_DAYS:
            keep = sorted(self.days.keys(), reverse=True)[:MAX_TRACKED_DAYS]
            self.days = {k: self.days[k] for k in keep}
        if len(self.words) > MAX_TRACKED_WORDS:
            kept = sorted(self.words.items(), key=lambda kv: (-kv[1], kv[0]))[:MAX_TRACKED_WORDS]
            self.words = dict(kept)

    # —— 读取 ——

    def total(self) -> dict:
        commits = sum(b.commits for b in self.days.values())
        queries = sum(b.queries for b in self.days.values())
        return {"commits": commits, "chars": sum(b.chars() for b in self.days.values()), "queries": queries}

    def top_words(self, limit: int = TOP_WORDS_LIMIT) -> list[tuple[str, int]]:
        return sorted(self.words.items(), key=lambda kv: (-kv[1], kv[0]))[:limit]

    def snapshot(self, date_key: str) -> dict:
        today = self.days.get(date_key, DayBucket())
        return {
            "today": {
                "date": date_key,
                "commits": today.commits,
                "chars": today.chars(),
                "queries": today.queries,
                "commits_of": dict(today.by_type),
                "hit_rate": today.hit_rate(),
            },
            "total": self.total(),
            "tracked_days": len(self.days),
            "top_words": self.top_words(),
        }

    # —— 持久化（格式与 InputStats.serialize 一一对应） ——

    def serialize(self) -> str:
        out = [HEADER + "\n"]
        for date in sorted(self.days.keys()):
            bucket = self.days[date]
            out.append(f"{MARK_QUERY}\t{date}\t{bucket.queries}\n")
            for ctype in COMMIT_TYPES:
                if ctype not in bucket.by_type:
                    continue
                out.append(
                    f"{MARK_COMMIT}\t{date}\t{ctype}\t{bucket.by_type[ctype]}"
                    f"\t{bucket.by_type_chars.get(ctype, 0)}\n"
                )
        for word, count in sorted(self.words.items(), key=lambda kv: (-kv[1], kv[0])):
            out.append(f"{MARK_WORD}\t{escape_line_text(word)}\t{count}\n")
        return "".join(out)

    @classmethod
    def deserialize(cls, text: str) -> "StatsModel":
        model = cls()
        for raw_line in text.split("\n"):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            mark = fields[0] if fields else ""
            if mark == MARK_QUERY:
                if len(fields) < 3:
                    continue
                queries = _to_int(fields[2])
                if queries is None or queries <= 0:
                    continue
                model.days.setdefault(fields[1], DayBucket()).queries = queries
            elif mark == MARK_COMMIT:
                if len(fields) < 4:
                    continue
                ctype = fields[2] if fields[2] in COMMIT_TYPES else None
                commits = _to_int(fields[3])
                if ctype is None or commits is None:
                    continue
                chars = _to_int(fields[4]) if len(fields) > 4 else 0
                chars = chars if chars is not None else 0
                bucket = model.days.setdefault(fields[1], DayBucket())
                commits = max(0, commits)
                bucket.by_type[ctype] = bucket.by_type.get(ctype, 0) + commits
                bucket.by_type_chars[ctype] = bucket.by_type_chars.get(ctype, 0) + max(0, chars)
                bucket.commits += commits
            elif mark == MARK_WORD:
                if len(fields) < 3:
                    continue
                count = _to_int(fields[2])
                word = unescape_line_text(fields[1])
                if not word or count is None or count <= 0:
                    continue
                model.words[word] = model.words.get(word, 0) + count
        model.prune()
        return model


def _to_int(raw: str) -> int | None:
    try:
        return int(raw.strip())
    except (TypeError, ValueError):
        return None


# ═════════════════════════════════════════════════════════════
# 2. 源码契约
# ═════════════════════════════════════════════════════════════

STATS_KT = DATA_DIR / "InputStats.kt"
HAN_KT = DATA_DIR / "HanConversion.kt"
SCREEN_KT = SETTINGS_DIR / "StatsScreen.kt"
VIEWMODEL_KT = SETTINGS_DIR / "stats" / "StatsViewModel.kt"


def check_source_contract() -> None:
    missing = [rel(p) for p in (STATS_KT, HAN_KT, SCREEN_KT, VIEWMODEL_KT) if not p.exists()]
    if not check(not missing, f"新增源码文件齐全（缺: {missing or '无'}）"):
        return

    stats_src = read(STATS_KT)
    consts = kotlin_constants(stats_src)

    expected = {
        "FILE_NAME": "input_stats.txt",
        "TOP_WORDS_LIMIT": str(TOP_WORDS_LIMIT),
        "MARK_QUERY": MARK_QUERY,
        "MARK_COMMIT": MARK_COMMIT,
        "MARK_WORD": MARK_WORD,
        "MAX_TRACKED_DAYS": str(MAX_TRACKED_DAYS),
        "MAX_TRACKED_WORDS": str(MAX_TRACKED_WORDS),
        "MAX_WORD_LENGTH": str(MAX_WORD_LENGTH),
    }
    bad = [f"{k}={consts.get(k)!r}（期望 {v!r}）" for k, v in expected.items() if consts.get(k) != v]
    check(not bad, f"InputStats.kt 常量与校验脚本模型一致（不符: {bad or '无'}）")

    check(
        "HEADER" in consts and consts["HEADER"].startswith("#"),
        f"InputStats.kt 文件头注释行存在：{consts.get('HEADER')!r}",
    )

    # 落盘格式：三条记录都必须是 mark <TAB> ... <TAB> 形态
    shapes = {
        "查询行": re.search(r"append\(MARK_QUERY\)\.append\('\\t'\)\.append\(date\)\.append\('\\t'\)", stats_src),
        "上屏行": re.search(r"append\(MARK_COMMIT\)\.append\('\\t'\)\.append\(date\)\.append\('\\t'\)\.append\(type\.name\)", stats_src),
        "词频行": re.search(r"append\(MARK_WORD\)\.append\('\\t'\)\.append\(escapeLineText\(entry\.key\)\)", stats_src),
    }
    check(
        all(shapes.values()),
        "InputStats.kt 落盘格式（q/c/w + TAB 分隔 + 词文本转义）与校验脚本一致"
        f"（缺失: {[k for k, v in shapes.items() if not v] or '无'}）",
    )

    check(
        "renameTo" in stats_src and '".tmp"' in stats_src.replace("'", '"'),
        "InputStats.kt 采用原子写（同目录 .tmp + renameTo，失败回退直写）",
    )
    check(
        stats_src.count("runCatching") >= 2 and "Log.w(TAG" in stats_src,
        "InputStats.kt 读 / 写 / 解析均有 runCatching + 日志兜底（不抛异常）",
    )
    check(
        "LocalDate.now().toString()" in stats_src,
        "InputStats.kt 跨天分桶用本地日期字符串 LocalDate.now().toString()",
    )
    check(
        "@Synchronized" in stats_src and stats_src.count("@Synchronized") >= 4,
        f"InputStats.kt 公开方法加锁（@Synchronized ×{stats_src.count('@Synchronized')}）",
    )

    # 简繁转换：资产名 + 失败降级 + 顶层函数
    han_src = read(HAN_KT)
    han_consts = kotlin_constants(han_src)
    asset_names = {han_consts.get("ASSET_S2T"), han_consts.get("ASSET_T2S")}
    existing = {p.name for p in ASSETS.glob("han_*.txt")}
    check(
        asset_names == existing and None not in asset_names,
        f"HanConversion.kt 资产常量与 assets 实际文件一致（常量={sorted(asset_names)} / 实际={sorted(existing)}）",
    )
    check(
        "fun toTraditional(text: String): String" in han_src
        and "fun toSimplified(text: String): String" in han_src
        and han_src.count("\nfun toTraditional(text: String)") == 1
        and han_src.count("\nfun toSimplified(text: String)") == 1,
        "HanConversion.kt 提供对象方法 + 顶层函数 toTraditional / toSimplified(text: String)",
    )
    check(
        han_src.count("runCatching") >= 2 and "Log.w(TAG" in han_src and "throw " not in han_src,
        "HanConversion.kt 装载失败只打日志（runCatching + Log.w），不抛异常",
    )
    check(
        "return text" in han_src and "map.isEmpty()" in han_src,
        "HanConversion.kt 未装载 / 空映射时原样返回 text",
    )
    check(
        "isHighSurrogate" in han_src and "isLowSurrogate" in han_src,
        "HanConversion.kt 逐码点转换，代理对（emoji）整对拷贝不拆分",
    )

    screen_src = read(SCREEN_KT)
    check(
        "InputStats.TOP_WORDS_LIMIT" in screen_src
        and "viewModel.reset()" in screen_src
        and "percentText" in screen_src
        and "AlertDialog" in screen_src,
        "StatsScreen.kt 展示 Top 词 / 命中率，重置带二次确认（只读页 + 重置按钮）",
    )


# ═════════════════════════════════════════════════════════════
# 3. 统计聚合逻辑
# ═════════════════════════════════════════════════════════════


def check_aggregation() -> None:
    # —— 跨天分桶 ——
    model = StatsModel()
    model.record_commit("CANDIDATE", "你好", "2026-10-16")
    model.record_commit("ENGLISH", "ab c", "2026-10-16")
    model.record_query("2026-10-16")
    model.record_query("2026-10-16")
    model.record_commit("CANDIDATE", "世界", "2026-10-17")
    model.record_commit("PUNCTUATION", "，", "2026-10-17")
    model.record_commit("EMOJI", "😀", "2026-10-17")
    model.record_query("2026-10-17")

    snap16 = model.snapshot("2026-10-16")
    snap17 = model.snapshot("2026-10-17")
    # 逐项断言（不写成一行大布尔，便于定位）
    bucket_ok = (
        snap16["today"]["commits"] == 2
        and snap16["today"]["queries"] == 2
        and snap16["today"]["commits_of"] == {"CANDIDATE": 1, "ENGLISH": 1}
        and snap17["today"]["commits"] == 3
        and snap17["today"]["queries"] == 1
        and snap17["today"]["commits_of"] == {"CANDIDATE": 1, "PUNCTUATION": 1, "EMOJI": 1}
    )
    check(bucket_ok, f"跨天分桶：两日分桶互不串味（16 日={snap16['today']['commits_of']}, 17 日={snap17['today']['commits_of']}）")

    total = model.total()
    expect_chars = 2 + 3 + 2 + 1 + 1  # 你好 / ab c(去空格) / 世界 / ， / 😀
    check(
        total == {"commits": 5, "chars": expect_chars, "queries": 3},
        f"累计 = 各分桶求和（commits=5, chars={expect_chars}, queries=3；实际={total}）",
    )
    check(
        snap17["tracked_days"] == 2 and sorted(model.days) == ["2026-10-16", "2026-10-17"],
        f"分桶按日期升序可枚举（days={sorted(model.days)}）",
    )

    # —— 字数口径 ——
    mixed = "你好 world!\n"
    check(
        char_count(mixed) == 8,
        f"字数口径：忽略空白（'你好 world!\\n' -> {char_count(mixed)}，期望 8）",
    )
    check(
        char_count("😀😀") == 2 and char_count("👨‍👩‍👧") == 5,
        f"字数口径：emoji 按码点计数（😀😀={char_count('😀😀')}；ZWJ 组合序列 👨‍👩‍👧={char_count('👨‍👩‍👧')}，"
        "由 3 个 emoji + 2 个连接符组成，故记 5 个字）",
    )

    # —— 命中率边界 ——
    zero = StatsModel()
    zero.record_commit("CANDIDATE", "字", "2026-10-17")
    check(
        zero.snapshot("2026-10-17")["today"]["hit_rate"] == 0.0,
        "命中率除零：查询次数为 0 时为 0.0（不抛 ZeroDivisionError）",
    )
    clamped = StatsModel()
    clamped.record_commit("CANDIDATE", "字", "2026-10-17")
    clamped.record_commit("CANDIDATE", "字", "2026-10-17")
    clamped.record_query("2026-10-17")
    check(
        clamped.snapshot("2026-10-17")["today"]["hit_rate"] == 1.0,
        f"命中率上限：上屏 2 次 / 查询 1 次 -> 1.0（实际 {clamped.snapshot('2026-10-17')['today']['hit_rate']}）",
    )
    half = StatsModel()
    half.record_commit("CANDIDATE", "字", "2026-10-17")
    half.record_commit("CANDIDATE", "字", "2026-10-17")
    half.record_commit("CANDIDATE", "字", "2026-10-17")
    for _ in range(4):
        half.record_query("2026-10-17")
    check(
        half.snapshot("2026-10-17")["today"]["hit_rate"] == 0.75,
        f"命中率正常值：3 / 4 -> 0.75（实际 {half.snapshot('2026-10-17')['today']['hit_rate']}）",
    )

    # —— Top 词排序稳定性 + Top 50 截断 ——
    rank = StatsModel()
    for word, times in (("甲", 3), ("乙", 3), ("丙", 3), ("丁", 1), ("戊", 1)):
        for _ in range(times):
            rank.record_commit("CANDIDATE", word, "2026-10-17")
    got_words = [w for w, _ in rank.top_words()]
    expect_words = sorted(
        ["甲", "乙", "丙", "丁", "戊"], key=lambda w: (-{"甲": 3, "乙": 3, "丙": 3, "丁": 1, "戊": 1}[w], w)
    )
    check(got_words == expect_words, f"Top 词排序：频次降序 + 文本升序（实际={got_words}，期望={expect_words}）")
    check(
        rank.top_words() == StatsModel.deserialize(rank.serialize()).top_words(),
        "Top 词排序稳定：序列化 → 反序列化后顺序不变（同频次不抖动）",
    )

    many = StatsModel()
    for i in range(60):
        # 频次 = 60 - i，保证第 51 名及以后可预测
        for _ in range(60 - i):
            many.record_commit("PHRASE", f"词{i:02d}", "2026-10-17")
    top = many.top_words()
    check(
        len(top) == TOP_WORDS_LIMIT
        and top[0] == ("词00", 60)
        and top[-1] == ("词49", 11)
        and ("词59", 1) not in top,
        f"Top 词截断：只保留 {TOP_WORDS_LIMIT} 条（实际 {len(top)} 条，首={top[0]}，末={top[-1]}）",
    )

    # —— 词长上限：超长文本不计入词频 ——
    long_word = "长" * (MAX_WORD_LENGTH + 1)
    limit = StatsModel()
    limit.record_commit("CANDIDATE", long_word, "2026-10-17")
    limit.record_commit("CANDIDATE", "短", "2026-10-17")
    check(
        [w for w, _ in limit.top_words()] == ["短"],
        f"词长上限：>{MAX_WORD_LENGTH} 码点的文本不进词频（实际保留={limit.top_words()}）",
    )

    # —— 序列化往返 + 坏行容错 ——
    sample = StatsModel()
    sample.record_commit("CANDIDATE", "测试", "2026-10-17")
    sample.record_commit("CANDIDATE", "带\t制表符", "2026-10-17")
    sample.record_commit("PHRASE", "换行\n词", "2026-10-17")
    sample.record_query("2026-10-17")
    payload = sample.serialize()
    check(
        StatsModel.deserialize(payload).serialize() == payload,
        "持久化往返：serialize → deserialize → serialize 字节一致（含制表符 / 换行的词）",
    )
    check(
        all(line.count("\t") >= 2 for line in payload.split("\n") if line and not line.startswith("#")),
        f"持久化格式：每条记录都是 `mark<TAB>...` 形态（{len([l for l in payload.split(chr(10)) if l])} 行）",
    )
    corrupted = (
        HEADER
        + "\nq\t2026-10-17\tnot-a-number\n"
        + "c\t2026-10-17\tNOT_A_TYPE\t3\t3\n"
        + "c\t2026-10-17\tCANDIDATE\t2\t4\n"
        + "x\t乱码行\n"
        + "w\t\n"
        + "w\t好词\t5\n"
    )
    healed = StatsModel.deserialize(corrupted)
    check(
        healed.snapshot("2026-10-17")["today"]["commits"] == 2
        and [w for w, _ in healed.top_words()] == ["好词"]
        and healed.top_words()[0][1] == 5,
        "坏行容错：非法数字 / 未知类型 / 未知标记被跳过，合法记录仍完整（不抛异常）",
    )

    # —— 400 天裁剪 ——
    pruned = StatsModel()
    for day in range(1, MAX_TRACKED_DAYS + 2):
        pruned.record_commit("CANDIDATE", "字", f"2020-01-{day:02d}" if day <= 31 else f"2021-{1 + (day - 32) // 28:02d}-{1 + (day - 32) % 28:02d}")
    check(
        len(pruned.days) == MAX_TRACKED_DAYS,
        f"分桶裁剪：最多保留 {MAX_TRACKED_DAYS} 天（实际 {len(pruned.days)}）",
    )

    # —— reset ——
    reset_model = StatsModel()
    reset_model.record_commit("CANDIDATE", "字", "2026-10-17")
    reset_model.record_query("2026-10-17")
    reset_model.reset()
    empty_snap = reset_model.snapshot("2026-10-17")
    check(
        empty_snap["total"] == {"commits": 0, "chars": 0, "queries": 0}
        and empty_snap["top_words"] == []
        and empty_snap["tracked_days"] == 0
        and reset_model.serialize() == HEADER + "\n"
        and empty_snap["today"]["hit_rate"] == 0.0,
        "reset：清空分桶 + 词频，落盘只剩文件头，命中率归零",
    )
    reset_model.record_commit("CANDIDATE", "新", "2026-10-18")
    check(
        reset_model.snapshot("2026-10-18")["total"]["commits"] == 1,
        "reset 后可继续记录（不会残留旧数据）",
    )


# ═════════════════════════════════════════════════════════════
# 4. 简繁映射数据
# ═════════════════════════════════════════════════════════════

S2T = ASSETS / "han_s2t.txt"
T2S = ASSETS / "han_t2s.txt"

# 体积上限：裁剪后应远小于上游（上游 ST 36 KB + TS 105 KB），留出充足余量
MAX_ASSET_BYTES = 64 * 1024

# 已知常用字映射（上游 OpenCC 事实，用于人肉可读的定点断言）
KNOWN_S2T = {"发": "發", "后": "後", "万": "萬", "国": "國", "学": "學", "车": "車", "书": "書", "汉": "漢", "们": "們"}
KNOWN_T2S = {"發": "发", "國": "国", "學": "学", "漢": "汉", "們": "们", "車": "车"}

# 双向回环率下限（实测 s2t≈0.996 / t2s≈0.891；低于下限说明数据被改坏）
MIN_S2T_ROUNDTRIP = 0.95
MIN_T2S_ROUNDTRIP = 0.80


def gb2312_bytes(ch: str) -> bytes | None:
    if len(ch) != 1:
        return None
    try:
        raw = ch.encode("gb2312")
    except UnicodeEncodeError:
        return None
    return raw if len(raw) == 2 else None


def is_common_hanzi(ch: str) -> bool:
    raw = gb2312_bytes(ch)
    return raw is not None and 0xB0 <= raw[0] <= 0xF7 and 0xA1 <= raw[1] <= 0xFE


def load_asset(path: Path) -> tuple[dict[str, list[str]], list[str]]:
    """返回 (映射, 坏行描述列表)。坏行 = 格式不合法 / 键重复 / 首项等于源字符。"""
    mapping: dict[str, list[str]] = {}
    problems: list[str] = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").split("\n"), start=1):
        line = line.rstrip("\r")
        if not line.strip() or line.startswith("#"):
            continue
        if "\t" not in line:
            problems.append(f"{lineno}: 缺少 TAB")
            continue
        key, rest = line.split("\t", 1)
        values = rest.split(" ")
        if len(key) != 1:
            problems.append(f"{lineno}: 键不是单个字符 {key!r}")
            continue
        if not values or any(not v for v in values):
            problems.append(f"{lineno}: 值列表为空或含空项")
            continue
        if any(" " in v or "\t" in v for v in values):
            problems.append(f"{lineno}: 值含空白")
            continue
        if key in mapping:
            problems.append(f"{lineno}: 键重复 {key}")
            continue
        if values[0] == key:
            problems.append(f"{lineno}: 首选项等于源字符 {key}")
            continue
        mapping[key] = values
    return mapping, problems


def convert(text: str, mapping: dict[str, list[str]]) -> str:
    """复刻 HanConversion.convert：逐码点查表（取首项），未命中原样保留，代理对整对拷贝。"""
    if not mapping or not text:
        return text
    out: list[str] = []
    changed = False
    i = 0
    while i < len(text):
        cp = ord(text[i])
        if 0xD800 <= cp <= 0xDBFF and i + 1 < len(text) and 0xDC00 <= ord(text[i + 1]) <= 0xDFFF:
            out.append(text[i : i + 2])
            i += 2
            continue
        mapped = mapping.get(text[i])
        if mapped is None:
            out.append(text[i])
        else:
            out.append(mapped[0])
            changed = True
        i += 1
    return "".join(out) if changed else text


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_han_assets() -> None:
    if not check(S2T.exists() and T2S.exists(), "简繁资产存在（han_s2t.txt / han_t2s.txt）"):
        return

    s2t, s2t_bad = load_asset(S2T)
    t2s, t2s_bad = load_asset(T2S)
    check(not s2t_bad, f"han_s2t.txt 无坏行（{len(s2t)} 条有效；坏行={s2t_bad[:3] or '无'}）")
    check(not t2s_bad, f"han_t2s.txt 无坏行（{len(t2s)} 条有效；坏行={t2s_bad[:3] or '无'}）")

    sizes = {S2T.name: S2T.stat().st_size, T2S.name: T2S.stat().st_size}
    total_bytes = sum(sizes.values())
    check(
        all(v <= MAX_ASSET_BYTES for v in sizes.values()),
        f"资产体积在预算内（{sizes} 字节，合计 {total_bytes} 字节，单文件上限 {MAX_ASSET_BYTES}）",
    )

    # 裁剪口径：简体侧必须属于 GB2312 常用汉字集
    bad_keys = [k for k in s2t if not is_common_hanzi(k)]
    check(not bad_keys, f"han_s2t.txt 源字符全部属于 GB2312 汉字集（越界={bad_keys[:5] or '无'}）")
    bad_targets = [k for k, v in t2s.items() if not any(is_common_hanzi(x) for x in v)]
    check(not bad_targets, f"han_t2s.txt 每条至少一个目标字符属于 GB2312 汉字集（越界={bad_targets[:5] or '无'}）")

    # 上游 sha256 记录在资产头 + 生成脚本中
    gen_src = read(GEN_SCRIPT) if GEN_SCRIPT.exists() else ""
    s2t_head = read(S2T).split("上游 sha256:")[1].split("\n")[0].strip() if "上游 sha256:" in read(S2T) else ""
    t2s_head = read(T2S).split("上游 sha256:")[1].split("\n")[0].strip() if "上游 sha256:" in read(T2S) else ""
    check(
        len(s2t_head) == 64 and s2t_head in gen_src and len(t2s_head) == 64 and t2s_head in gen_src,
        f"上游 sha256 记录一致（资产头 s2t={s2t_head[:16]}… t2s={t2s_head[:16]}…，均已写入生成脚本）",
    )
    check(
        "Apache-2.0" in read(S2T) and "Apache-2.0" in read(T2S) and "BYVoid/OpenCC" in read(S2T),
        "资产头部记录了上游仓库与 Apache-2.0 许可",
    )
    check(
        "裁剪口径" in read(S2T) and "GB2312" in read(S2T),
        "资产头部记录了裁剪口径（GB2312 汉字集 + 剔除首选项等于源字符的条目）",
    )

    # —— 定点断言：常用字 ——
    wrong = [f"{k}->{s2t.get(k, ['<缺失>'])[0]}（期望 {v}）" for k, v in KNOWN_S2T.items() if s2t.get(k, [None])[0] != v]
    check(not wrong, f"简 → 繁 常用字映射正确（不符={wrong or '无'}）")
    wrong_back = [f"{k}->{t2s.get(k, ['<缺失>'])[0]}（期望 {v}）" for k, v in KNOWN_T2S.items() if t2s.get(k, [None])[0] != v]
    check(not wrong_back, f"繁 → 简 常用字映射正确（不符={wrong_back or '无'}）")

    # —— 双向覆盖抽样（固定种子，结果可复现） ——
    rng = random.Random(20261017)
    sample_size = 40
    s2t_sample = rng.sample(sorted(s2t.keys()), min(sample_size, len(s2t)))
    ok_forward = [k for k in s2t_sample if t2s.get(s2t[k][0], [None])[0] == k]
    rate_forward = len(ok_forward) / len(s2t_sample)
    check(
        rate_forward >= MIN_S2T_ROUNDTRIP,
        f"双向覆盖抽样（简→繁→简，{len(s2t_sample)} 例）：回环率 {rate_forward:.3f} ≥ {MIN_S2T_ROUNDTRIP}"
        f"（未回环示例={[k for k in s2t_sample if k not in ok_forward][:3]}）",
    )

    t2s_sample = rng.sample(sorted(t2s.keys()), min(sample_size, len(t2s)))
    ok_backward = [k for k in t2s_sample if s2t.get(t2s[k][0], [None])[0] == k]
    rate_backward = len(ok_backward) / len(t2s_sample)
    check(
        rate_backward >= MIN_T2S_ROUNDTRIP,
        f"双向覆盖抽样（繁→简→繁，{len(t2s_sample)} 例）：回环率 {rate_backward:.3f} ≥ {MIN_T2S_ROUNDTRIP}"
        f"（未回环示例={[k for k in t2s_sample if k not in ok_backward][:3]}）",
    )

    # —— 复刻 convert 的行为 ——
    # 注：字符级转换取上游首项，'里' 的首项是 '裏'（OpenCC 靠短语表消歧，字符表只给首选）
    check(
        convert("发展后万里车", s2t) == "發展後萬裏車",
        f"复刻 convert：'发展后万里车' -> {convert('发展后万里车', s2t)}（期望 發展後萬裏車，字符级取首选项）",
    )
    check(
        convert("發展後萬里車", t2s) == "发展后万里车",
        f"复刻 convert：'發展後萬里車' -> {convert('發展後萬里車', t2s)}（期望 发展后万里车）",
    )
    check(
        convert("你好 abc", s2t) == "你好 abc" and convert("你好 abc", t2s) == "你好 abc",
        "未命中字符原样保留（'你好 abc' 在两侧都不变）",
    )
    check(
        convert("😀发达👨‍👩‍👧", s2t) == "😀發達👨‍👩‍👧",
        f"代理对不拆分：'😀发达👨‍👩‍👧' -> {convert('😀发达👨‍👩‍👧', s2t)}（期望 😀發達👨‍👩‍👧）",
    )
    check(
        convert("", s2t) == "" and convert("text", {}) == "text",
        "空串 / 空映射直接原样返回（对应 Kotlin 的 early return，不进行任何遍历）",
    )


# ═════════════════════════════════════════════════════════════
# 5. 文档
# ═════════════════════════════════════════════════════════════


def check_docs() -> None:
    if not check(DOC.exists(), f"文档存在：{rel(DOC)}"):
        return
    src = read(DOC)
    required = {
        "上游仓库 URL": "github.com/BYVoid/OpenCC",
        "许可": "Apache-2.0",
        "ST sha256": "a0ca1601c70648cf48b33c3c6210ccbecc5c7eead4b4c3daf76587ba2c03582b",
        "TS sha256": "9ff46a7d30e5765375eb13d33f2b03a34d298913caf2b120380679f33ae1642d",
        "资产名 s2t": "han_s2t.txt",
        "资产名 t2s": "han_t2s.txt",
        "裁剪口径": "GB2312",
        "导航接线": "IspeechRoutes",
        "统计保存点": "recordCommit",
        "查询保存点": "recordQuery",
        "简繁调用": "HanConversion",
    }
    missing = [name for name, needle in required.items() if needle not in src]
    check(not missing, f"文档覆盖来源 / 许可 / sha256 / 接线片段（缺: {missing or '无'}）")
    check(
        str(S2T.stat().st_size) in src and str(T2S.stat().st_size) in src,
        "文档记录了资产实际体积（与当前文件一致）",
    )


# ═════════════════════════════════════════════════════════════


def main() -> int:
    print(f"== check_stats.py == 仓库根：{ROOT}")
    check_source_contract()
    check_aggregation()
    check_han_assets()
    check_docs()
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
