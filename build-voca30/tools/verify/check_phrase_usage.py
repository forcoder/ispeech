#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_phrase_usage.py —— csBaby 快捷短语增强（收藏 / 最近 / 计数 / 搜索）核心逻辑校验

本机没有 JDK / Android SDK / 网络，无法编译运行 Kotlin。因此脚本分两层：

  A. 源码漂移校验：从 Kotlin 源码里解析常量（RECENT_LIMIT / ID_PREFIX / ID_SEP /
     FNV 常量 / PhraseSearch 评分档位与附加分 / PhrasePinyin 首字母表）与公开 API
     （fun topOrRecent(...) 等），断言它们与 Python 复刻值一致；Kotlin 改了常量而
     没同步这里，脚本会 FAIL。
  B. 行为断言：用 Python 复刻 PhraseUsageStore / PhraseSearch / PhrasePinyin 的核心逻辑
     （id 归一化与哈希、文件读写、LRU 淘汰、去重合并、迁移、容错、搜索评分），逐条断言。

覆盖的断言：
  1. id 稳定性与归一化去重（同触发码 + 同正文 => 同 id）
  2. 常量 / 公开 API / 依赖白名单（无第三方库）
  3. 评分档位间距 > 附加分上限（完全 > 前缀 > 包含 不被使用次数 / 收藏翻转）
  4. 收藏置顶顺序（收藏组按最近使用倒序，其后是未收藏）
  5. 最近使用 LRU 上限（未收藏只留 50 条，收藏永不淘汰）
  6. 计数累加 / 同毫秒 tick 定序 / 同 id 只有一行
  7. 损坏文件容错（坏行跳过、备份 .corrupt、超大文件放弃读取、仍可继续写）
  8. 去重合并策略（star 取或、count 取最大、时间取最新）与旧 id 迁移
  9. 原子写入（tmp + replace，无残留）
 10. 搜索评分排序（完全 > 前缀 > 包含 > 首字母缩写）、分类关键词、过滤与去重
 11. 首字母表交叉校验（表内取值 + 内置规则文案覆盖率）
 12. prune / clear / forget / toggleStar 取消收藏（未使用则删除）

输出：每条检查一行 PASS / FAIL / WARN 前缀；末尾 SUMMARY: passed=N failed=M warned=K。
任一 FAIL 时退出码为 1，否则为 0。不依赖 cwd（仓库根 = Path(__file__).resolve().parents[2]）。

与 Kotlin 的已知差异（不影响本脚本断言的场景）：
  - Kotlin trim() 只裁剪码点 <= 0x20 的字符，Python .strip() 还会裁剪 U+3000 等 Unicode 空白；
  - Kotlin take(n) 按 UTF-16 code unit 计数，Python 切片按码点计数（测试数据都是 BMP + ASCII）。
"""

from __future__ import annotations

import ast
import os
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "app" / "src" / "main" / "java" / "com" / "ispeech" / "data"
STORE_KT = DATA_DIR / "PhraseUsageStore.kt"
SEARCH_KT = DATA_DIR / "PhraseSearch.kt"
ASSET_PHRASES = ROOT / "app" / "src" / "main" / "assets" / "quick_phrases.txt"

MASK64 = (1 << 64) - 1

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


def check(label: str, fn) -> None:
    """执行一条断言函数；返回字符串则作为详情附加到 PASS 行。"""
    try:
        detail = fn()
        report("PASS", f"{label}{(' — ' + detail) if detail else ''}")
    except AssertionError as exc:
        report("FAIL", f"{label} — {exc}")
    except Exception as exc:  # 校验脚本要报告而不是崩溃
        report("FAIL", f"{label} — 意外异常 {type(exc).__name__}: {exc}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


# ═════════════════════════════════════════════════════════════
# Kotlin 源码解析：常量 / API / 字符串字面量
# ═════════════════════════════════════════════════════════════

def kotlin_string_literal(raw: str) -> str:
    body = raw.strip()
    assert body.startswith('"') and body.endswith('"'), f"不是字符串字面量: {raw!r}"
    body = body[1:-1]
    out = []
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            mapping = {"n": "\n", "r": "\r", "t": "\t", "\\": "\\", '"': '"', "'": "'" }
            if nxt == "u" and i + 5 < len(body):
                out.append(chr(int(body[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append(mapping.get(nxt, "\\" + nxt))
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def kotlin_const(text: str, name: str):
    """解析 const val NAME = <字面量>（支持字符串 / 十进制 / 十六进制 / 负数 / Long 后缀）。"""
    pattern = re.compile(
        r"const\s+val\s+" + re.escape(name) + r"\s*(?::[A-Za-z]+)?\s*=\s*([^\n]+)"
    )
    match = pattern.search(text)
    assert match, f"未找到常量 {name}"
    raw = match.group(1).strip().rstrip(";")
    raw = raw.split("//")[0].strip()
    if raw.startswith('"'):
        return kotlin_string_literal(raw)
    return eval_int_expr(raw)


def eval_int_expr(raw: str) -> int:
    """整数常量求值：字面量，或只含 + - * / 与括号的简单表达式（例如 `512L * 1024L`）。"""
    expr = re.sub(r"([0-9a-fA-F])L\b", r"\1", raw.replace("_", "").replace(" ", ""))
    assert re.fullmatch(r"[-+*/()0-9xXa-fA-F]+", expr), f"无法解析常量表达式: {raw!r}"
    return eval_int_node(ast.parse(expr, mode="eval").body)


def eval_int_node(node) -> int:
    """受限求值：只接受整数常量、一元 +-、以及 + - * / 二元运算。"""
    if isinstance(node, ast.Constant):
        assert isinstance(node.value, int), f"常量不是整数: {node.value!r}"
        return node.value
    if isinstance(node, ast.UnaryOp):
        assert isinstance(node.op, (ast.USub, ast.UAdd)), "只允许 + / - 前缀"
        value = eval_int_node(node.operand)
        return -value if isinstance(node.op, ast.USub) else value
    if isinstance(node, ast.BinOp):
        left, right = eval_int_node(node.left), eval_int_node(node.right)
        if isinstance(node.op, ast.Add):
            return left + right
        if isinstance(node.op, ast.Sub):
            return left - right
        if isinstance(node.op, ast.Mult):
            return left * right
        if isinstance(node.op, ast.Div):
            return left // right
    raise AssertionError(f"不支持的常量表达式节点: {node!r}")
    value = raw[:-1] if raw.endswith("L") else raw
    value = value.replace("_", "")
    return int(value, 0)


def kotlin_concatenated_const(text: str, name: str, stop_marker: str) -> str:
    """解析由多个字符串字面量 + 拼接的常量（PhrasePinyin.GROUPS 用）。"""
    start = text.index(f"const val {name}")
    end = text.index(stop_marker, start)
    chunk = text[start:end]
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', chunk)
    assert parts, f"{name} 里没有字符串字面量"
    return "".join(kotlin_string_literal(f'"{p}"') for p in parts)


STORE_SRC = read(STORE_KT)
SEARCH_SRC = read(SEARCH_KT)


def kotlin_hex16(value: int) -> str:
    return f"{value & MASK64:016x}"


def fnv1a64(text: str) -> int:
    """与 PhraseUsageStore.fnv1a64 一致：对 UTF-8 字节流做 FNV-1a，64 位回绕。"""
    hash_value = K_FNV_OFFSET
    for byte in text.encode("utf-8"):
        hash_value ^= byte
        hash_value = (hash_value * K_FNV_PRIME) & MASK64
    return hash_value


K_RECENT_LIMIT = kotlin_const(STORE_SRC, "RECENT_LIMIT")
K_DEFAULT_TOP_LIMIT = kotlin_const(STORE_SRC, "DEFAULT_TOP_LIMIT")
K_ID_PREFIX = kotlin_const(STORE_SRC, "ID_PREFIX")
K_ID_SEP = kotlin_const(STORE_SRC, "ID_SEP")
K_FILE_NAME = kotlin_const(STORE_SRC, "FILE_NAME")
K_MAX_CODE_LENGTH = kotlin_const(STORE_SRC, "MAX_CODE_LENGTH")
K_MAX_TEXT_LENGTH = kotlin_const(STORE_SRC, "MAX_TEXT_LENGTH")
K_MAX_COUNT = kotlin_const(STORE_SRC, "MAX_COUNT")
K_MAX_FILE_BYTES = kotlin_const(STORE_SRC, "MAX_FILE_BYTES")
K_FNV_OFFSET = kotlin_const(STORE_SRC, "FNV_OFFSET_BASIS") & MASK64
K_FNV_PRIME = kotlin_const(STORE_SRC, "FNV_PRIME")


def search_const(name: str) -> int:
    return kotlin_const(SEARCH_SRC, name)


K_DEFAULT_LIMIT = search_const("DEFAULT_LIMIT")
K_SCORE_FIELD_TRIGGER = search_const("SCORE_FIELD_TRIGGER")
K_SCORE_FIELD_LABEL = search_const("SCORE_FIELD_LABEL")
K_SCORE_FIELD_CONTENT = search_const("SCORE_FIELD_CONTENT")
K_SCORE_USAGE_PER_USE = search_const("SCORE_USAGE_PER_USE")
K_SCORE_USAGE_MAX_USES = search_const("SCORE_USAGE_MAX_USES")
K_SCORE_STARRED = search_const("SCORE_STARRED")
K_MIN_INITIALS_QUERY = search_const("MIN_INITIALS_QUERY")
K_MAX_SPANS = search_const("MAX_SPANS")


def enum_scores(text: str, enum_name: str) -> dict:
    """解析 enum class X(val score: Int) { A(900), B(600) }。"""
    match = re.search(r"enum class " + enum_name + r"[^{]*\{(.*?)\n\}", text, re.S)
    assert match, f"未找到枚举 {enum_name}"
    pairs = re.findall(r"([A-Z_]+)\((\d+)\)", match.group(1))
    assert pairs, f"枚举 {enum_name} 没解析到取值"
    return {name: int(value) for name, value in pairs}


TIERS = enum_scores(SEARCH_SRC, "PhraseMatchTier")


# ═════════════════════════════════════════════════════════════
# Python 复刻：PhraseUsageStore
# ═════════════════════════════════════════════════════════════

def normalize_code(code: str) -> str:
    return code.strip().lower()[:K_MAX_CODE_LENGTH]


def normalize_text(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n").strip()[:K_MAX_TEXT_LENGTH]


def id_of(code: str, text: str) -> str:
    normalized = normalize_code(code) + K_ID_SEP + normalize_text(text)
    return K_ID_PREFIX + "-" + kotlin_hex16(fnv1a64(normalized))


def escape_line_text(text: str) -> str:
    sb = []
    for ch in text:
        if ch == "\\":
            sb.append("\\\\")
        elif ch == "\n":
            sb.append("\\n")
        elif ch == "\r":
            sb.append("\\r")
        elif ch == "\t":
            sb.append("\\t")
        else:
            sb.append(ch)
    return "".join(sb)


def unescape_line_text(text: str) -> str:
    if "\\" not in text:
        return text
    sb = []
    i = 0
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else None
        if ch == "\\" and nxt is not None:
            if nxt == "n":
                sb.append("\n")
                i += 2
                continue
            if nxt == "r":
                sb.append("\r")
                i += 2
                continue
            if nxt == "t":
                sb.append("\t")
                i += 2
                continue
            if nxt == "\\":
                sb.append("\\")
                i += 2
                continue
            sb.append(ch)
            i += 1
            continue
        sb.append(ch)
        i += 1
    return "".join(sb)


class Usage:
    """PhraseUsage 的 Python 镜像。"""

    __slots__ = ("id", "code", "text", "starred", "count", "last_used_ms", "tick")

    def __init__(self, id, code, text, starred, count, last_used_ms, tick):
        self.id = id
        self.code = code
        self.text = text
        self.starred = starred
        self.count = count
        self.last_used_ms = last_used_ms
        self.tick = tick

    def clone(self):
        return Usage(self.id, self.code, self.text, self.starred, self.count,
                     self.last_used_ms, self.tick)


def usage_rank_key(usage: Usage):
    """与 Kotlin USAGE_RANK 等价：时间倒序 -> tick 倒序 -> 次数倒序 -> id 升序。"""
    return (-usage.last_used_ms, -usage.tick, -usage.count, usage.id)


def merge_usage(a: Usage, b: Usage) -> Usage:
    newer, older = (b, a) if b.last_used_ms >= a.last_used_ms else (a, b)
    return Usage(
        id=a.id,
        code=newer.code if newer.code else older.code,
        text=newer.text if newer.text else older.text,
        starred=a.starred or b.starred,
        count=max(a.count, b.count),
        last_used_ms=max(a.last_used_ms, b.last_used_ms),
        tick=max(a.tick, b.tick),
    )


class Store:
    """PhraseUsageStore 的 Python 镜像（原子写 + 懒加载 + 容错 + LRU 淘汰）。"""

    def __init__(self, directory: Path, clock):
        self.file = directory / K_FILE_NAME
        self.records: dict = {}
        self.clock = clock
        self.loaded = False
        self.tick_counter = 0
        self.skipped_lines = 0
        self.duplicate_lines = 0
        self.migrated_ids = 0
        self.backup_written = False

    # ── 解析 / 序列化 ──

    def parse(self, raw: str):
        merged: dict = {}
        self.skipped_lines = 0
        self.duplicate_lines = 0
        self.migrated_ids = 0
        for raw_line in raw.split("\n"):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) < 7:
                self.skipped_lines += 1
                continue
            stored_id = unescape_line_text(fields[0]).strip()
            code = normalize_code(unescape_line_text(fields[1]))
            text = normalize_text(unescape_line_text(fields[2]))
            starred = fields[3].strip() == "1"
            try:
                count = int(fields[4].strip())
                last_used = int(fields[5].strip())
                tick = int(fields[6].strip())
            except ValueError:
                self.skipped_lines += 1
                continue
            if not stored_id:
                self.skipped_lines += 1
                continue
            recomputed = id_of(code, text) if code and text else stored_id
            if recomputed != stored_id:
                self.migrated_ids += 1
            record = Usage(
                id=recomputed,
                code=code,
                text=text,
                starred=starred,
                count=min(max(count, 0), K_MAX_COUNT),
                last_used_ms=max(last_used, 0),
                tick=max(tick, 0),
            )
            if record.id in merged:
                self.duplicate_lines += 1
                merged[record.id] = merge_usage(merged[record.id], record)
            else:
                merged[record.id] = record
        return list(merged.values())

    @staticmethod
    def format(records) -> str:
        sb = ["# 短语使用记录（Python 复刻）",
              "# 字段：id\\tcode\\ttext\\tstar(0/1)\\tcount\\tlastUsedMs\\ttick"]
        for record in records:
            sb.append("\t".join([
                escape_line_text(record.id),
                escape_line_text(record.code),
                escape_line_text(record.text),
                "1" if record.starred else "0",
                str(record.count),
                str(record.last_used_ms),
                str(record.tick),
            ]))
        return "\n".join(sb) + "\n"

    # ── 落盘 ──

    def persist(self) -> None:
        text = self.format(self.ranked())
        tmp = self.file.parent / (self.file.name + ".tmp")
        tmp.write_text(text, encoding="utf-8")
        os.replace(tmp, self.file)

    def backup_corrupt(self) -> None:
        if self.file.exists():
            (self.file.parent / (self.file.name + ".corrupt")).write_bytes(self.file.read_bytes())
            self.backup_written = True

    def ensure_loaded(self) -> None:
        if self.loaded:
            return
        self.loaded = True
        if not self.file.exists():
            return
        if self.file.stat().st_size > K_MAX_FILE_BYTES:
            self.backup_corrupt()
            return
        raw = self.file.read_text(encoding="utf-8", errors="replace")
        parsed = self.parse(raw)
        if self.skipped_lines > 0:
            self.backup_corrupt()
        for record in parsed:
            previous = self.records.get(record.id)
            self.records[record.id] = record if previous is None else merge_usage(previous, record)
        self.tick_counter = max([r.tick for r in self.records.values()], default=0)
        if self.migrated_ids > 0:
            self.persist()

    # ── 查询 ──

    def ranked(self):
        starred = sorted([r for r in self.records.values() if r.starred], key=usage_rank_key)
        rest = sorted([r for r in self.records.values() if not r.starred], key=usage_rank_key)
        return starred + rest

    def all(self):
        self.ensure_loaded()
        return self.ranked()

    def usage(self, id):
        key = id.strip()
        if not key:
            return None
        self.ensure_loaded()
        return self.records.get(key)

    def is_starred(self, id) -> bool:
        record = self.usage(id)
        return bool(record and record.starred)

    def star_count(self) -> int:
        self.ensure_loaded()
        return sum(1 for r in self.records.values() if r.starred)

    def count_of(self, id) -> int:
        record = self.usage(id)
        return record.count if record else 0

    def recent(self, limit=None):
        limit = K_RECENT_LIMIT if limit is None else limit
        if limit <= 0:
            return []
        self.ensure_loaded()
        return sorted(self.records.values(), key=usage_rank_key)[:limit]

    def recent_ids(self, limit=None):
        return [r.id for r in self.recent(limit)]

    def top_or_recent(self, limit=None, known_ids=None):
        limit = K_DEFAULT_TOP_LIMIT if limit is None else limit
        if limit <= 0:
            return []
        self.ensure_loaded()
        starred, rest = [], []
        for record in self.records.values():
            if known_ids is not None and record.id not in known_ids:
                continue
            (starred if record.starred else rest).append(record)
        return (sorted(starred, key=usage_rank_key) + sorted(rest, key=usage_rank_key))[:limit]

    # ── 写入 ──

    def toggle_star(self, id, code: str = "", text: str = "") -> bool:
        key = id.strip()
        if not key:
            return False
        self.ensure_loaded()
        previous = self.records.get(key)
        starred = not (previous.starred if previous else False)
        if not starred and (previous.count if previous else 0) == 0:
            self.records.pop(key, None)
            self.persist()
            return False
        safe_code, safe_text = normalize_code(code), normalize_text(text)
        tick = self.tick_counter + 1 if starred else (previous.tick if previous else 0)
        if starred:
            self.tick_counter = tick
        record = Usage(
            id=key,
            code=safe_code if safe_code else (previous.code if previous else ""),
            text=safe_text if safe_text else (previous.text if previous else ""),
            starred=starred,
            count=previous.count if previous else 0,
            last_used_ms=self.clock() if starred else (previous.last_used_ms if previous else 0),
            tick=tick,
        )
        self.records[key] = record
        self.trim()
        self.persist()
        return starred

    def record_use(self, id, code: str = "", text: str = "") -> None:
        key = id.strip()
        if not key:
            return
        self.ensure_loaded()
        previous = self.records.get(key)
        safe_code, safe_text = normalize_code(code), normalize_text(text)
        self.tick_counter += 1
        self.records[key] = Usage(
            id=key,
            code=safe_code if safe_code else (previous.code if previous else ""),
            text=safe_text if safe_text else (previous.text if previous else ""),
            starred=previous.starred if previous else False,
            count=min((previous.count if previous else 0) + 1, K_MAX_COUNT),
            last_used_ms=self.clock(),
            tick=self.tick_counter,
        )
        self.trim()
        self.persist()

    def forget(self, id) -> bool:
        key = id.strip()
        if not key:
            return False
        self.ensure_loaded()
        removed = self.records.pop(key, None) is not None
        if removed:
            self.persist()
        return removed

    def prune(self, known_ids) -> int:
        self.ensure_loaded()
        removed = 0
        for key in list(self.records.keys()):
            if key not in known_ids:
                self.records.pop(key, None)
                removed += 1
        if removed:
            self.persist()
        return removed

    def clear(self) -> None:
        self.ensure_loaded()
        self.records.clear()
        self.tick_counter = 0
        self.persist()

    def trim(self) -> None:
        unstarred = sorted([r for r in self.records.values() if not r.starred], key=usage_rank_key)
        for record in unstarred[K_RECENT_LIMIT:]:
            self.records.pop(record.id, None)

    def file_records(self):
        """直接读文件（断言落盘内容用，不走缓存）。"""
        rows = []
        raw = self.file.read_text(encoding="utf-8", errors="replace")
        for raw_line in raw.split("\n"):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            rows.append({
                "id": fields[0],
                "starred": fields[3] == "1" if len(fields) > 3 else False,
                "count": int(fields[4]) if len(fields) > 4 else 0,
            })
        return rows


class Clock:
    """可控时钟：默认每次 tick() 前进 1 秒，便于断言「最近使用」顺序。"""

    def __init__(self, start: int = 1_700_000_000_000):
        self.now = start

    def __call__(self) -> int:
        return self.now

    def tick(self, ms: int = 1000) -> None:
        self.now += ms


# ═════════════════════════════════════════════════════════════
# Python 复刻：PhraseSearch + PhrasePinyin
# ═════════════════════════════════════════════════════════════

def parse_groups(groups: str):
    table: dict = {}
    conflicts = []
    for group in groups.split("|"):
        if len(group) < 2 or group[1] != ":":
            continue
        initial = group[0]
        for ch in group[2:]:
            if ch in table and table[ch] != initial:
                conflicts.append((ch, table[ch], initial))
            table[ch] = initial
    return table, conflicts


GROUPS = kotlin_concatenated_const(SEARCH_SRC, "GROUPS", "private const val WILDCARD")
INITIAL_BY_CHAR, GROUP_CONFLICTS = parse_groups(GROUPS)
WILDCARD = "\0"


def is_cjk(ch: str) -> bool:
    code = ord(ch)
    return 0x3400 <= code <= 0x9FFF or 0xF900 <= code <= 0xFAFF


def pinyin_keys(text: str):
    keys = []
    for index, ch in enumerate(text):
        if "a" <= ch <= "z" or "0" <= ch <= "9":
            keys.append((index, ch, False))
        elif "A" <= ch <= "Z":
            keys.append((index, ch.lower(), False))
        elif ch in INITIAL_BY_CHAR:
            keys.append((index, INITIAL_BY_CHAR[ch], True))
        elif is_cjk(ch):
            keys.append((index, WILDCARD, True))
    return keys


def pinyin_initials(text: str) -> str:
    return "".join("?" if initial == WILDCARD else initial for _, initial, _ in pinyin_keys(text))


def initials_spans(text: str, keyword: str):
    needle = keyword.lower()
    if not text or len(needle) < K_MIN_INITIALS_QUERY:
        return []
    if any(not ("a" <= ch <= "z" or "0" <= ch <= "9") for ch in needle):
        return []
    keys = pinyin_keys(text)
    if len(keys) < len(needle):
        return []
    out = []
    start = 0
    while start + len(needle) <= len(keys):
        aligned = all(
            keys[start + offset][1] == WILDCARD or keys[start + offset][1] == needle[offset]
            for offset in range(len(needle))
        )
        if aligned:
            if any(keys[start + i][2] for i in range(len(needle))):
                out.append((keys[start][0], keys[start + len(needle) - 1][0]))
                if len(out) >= K_MAX_SPANS:
                    break
            start += len(needle)
        else:
            start += 1
    return out


def spans_of(text: str, keyword: str):
    if not text or not keyword or len(keyword) > len(text):
        return []
    needle = keyword.lower()
    out = []
    index = 0
    while index + len(needle) <= len(text):
        segment = text[index:index + len(needle)]
        if len(segment) == len(needle) and segment.lower() == needle:
            out.append((index, index + len(needle) - 1))
            if len(out) >= K_MAX_SPANS:
                break
            index += len(needle)
        else:
            index += 1
    return out


class Entry:
    """QuickEntry 的 Python 镜像。"""

    def __init__(self, trigger, label, content, weight=100, kind="短语", source="内置"):
        self.trigger = trigger
        self.label = label
        self.content = content
        self.weight = weight
        self.kind = kind
        self.source = source


FIELD_TRIGGER, FIELD_LABEL, FIELD_CONTENT, FIELD_CATEGORY = "触发码", "名称", "正文", "分类"


def usage_bonus(count: int) -> int:
    return min(count, K_SCORE_USAGE_MAX_USES) * K_SCORE_USAGE_PER_USE


def match_entry(entry: Entry, needle: str):
    """返回 (档位, 字段, 字段加成, 区间) 或 None。"""
    fields = [
        (FIELD_TRIGGER, entry.trigger, K_SCORE_FIELD_TRIGGER),
        (FIELD_LABEL, entry.label, K_SCORE_FIELD_LABEL),
        (FIELD_CONTENT, entry.content, K_SCORE_FIELD_CONTENT),
        (FIELD_CATEGORY, entry.source, 0),
        (FIELD_CATEGORY, entry.kind, 0),
    ]
    for field, text, bonus in fields:
        if text and text.lower() == needle:
            return (TIERS["EXACT"], field, bonus, [(0, len(text) - 1)])
    for field, text, bonus in fields:
        if text and text.lower().startswith(needle):
            return (TIERS["PREFIX"], field, bonus, spans_of(text, needle))
    for field, text, bonus in fields:
        spans = spans_of(text, needle)
        if spans:
            return (TIERS["CONTAINS"], field, bonus, spans)
    if len(needle) >= K_MIN_INITIALS_QUERY and all("a" <= ch <= "z" or "0" <= ch <= "9" for ch in needle):
        for field, text, _bonus in fields:
            spans = initials_spans(text, needle)
            if spans:
                return (TIERS["INITIALS"], field, 0, spans)
    return None


class Match:
    __slots__ = ("id", "entry", "tier", "field", "score", "starred", "count", "spans")

    def __init__(self, id, entry, tier, field, score, starred, count, spans):
        self.id = id
        self.entry = entry
        self.tier = tier
        self.field = field
        self.score = score
        self.starred = starred
        self.count = count
        self.spans = spans


def match_rank_key(match: Match):
    return (-match.score, -match.count, -match.entry.weight,
            match.entry.label, match.entry.trigger, match.id)


def search(entries, keyword="", category=None, limit=None, starred_only=False,
           allowed_ids=None, usage_count=lambda _id: 0, starred=lambda _id: False):
    limit = K_DEFAULT_LIMIT if limit is None else limit
    if limit <= 0:
        return []
    needle = keyword.strip().lower()
    filter_value = (category or "").strip().lower()
    if filter_value in ("", "全部", "all"):
        filter_value = None
    dedup: dict = {}
    for entry in entries:
        if filter_value is not None and entry.source.lower() != filter_value and entry.kind.lower() != filter_value:
            continue
        entry_id = id_of(entry.trigger, entry.content)
        if allowed_ids is not None and entry_id not in allowed_ids:
            continue
        is_star = bool(starred(entry_id))
        if starred_only and not is_star:
            continue
        count = max(usage_count(entry_id), 0)
        if needle:
            hit = match_entry(entry, needle)
            if hit is None:
                continue
            tier, field, bonus, spans = hit
        else:
            tier, field, bonus, spans = TIERS["CONTAINS"], FIELD_CONTENT, 0, []
        score = tier + bonus + usage_bonus(count) + (K_SCORE_STARRED if is_star else 0)
        match = Match(entry_id, entry, tier, field, score, is_star, count, spans)
        previous = dedup.get(entry_id)
        if previous is None or match.score > previous.score:
            dedup[entry_id] = match
    return sorted(dedup.values(), key=match_rank_key)[:limit]


# ═════════════════════════════════════════════════════════════
# A. 源码漂移校验
# ═════════════════════════════════════════════════════════════

def t_constants_match():
    expected = {
        "RECENT_LIMIT": (K_RECENT_LIMIT, 50),
        "DEFAULT_TOP_LIMIT": (K_DEFAULT_TOP_LIMIT, 12),
        "ID_PREFIX": (K_ID_PREFIX, "p1"),
        "ID_SEP": (K_ID_SEP, "\u0001"),
        "FILE_NAME": (K_FILE_NAME, "phrase_usage.txt"),
        "MAX_CODE_LENGTH": (K_MAX_CODE_LENGTH, 64),
        "MAX_TEXT_LENGTH": (K_MAX_TEXT_LENGTH, 2000),
        "MAX_COUNT": (K_MAX_COUNT, 999_999),
        "MAX_FILE_BYTES": (K_MAX_FILE_BYTES, 512 * 1024),
        "FNV_OFFSET_BASIS": (K_FNV_OFFSET, 0xCBF29CE484222325),
        "FNV_PRIME": (K_FNV_PRIME, 0x100000001B3),
    }
    bad = [f"{name}: 源码 {got!r} != 期望 {want!r}" for name, (got, want) in expected.items() if got != want]
    assert not bad, "; ".join(bad)
    return "PhraseUsageStore 11 个常量全部一致"


def t_search_constants_match():
    expected = {
        "DEFAULT_LIMIT": (K_DEFAULT_LIMIT, 200),
        "SCORE_FIELD_TRIGGER": (K_SCORE_FIELD_TRIGGER, 100),
        "SCORE_FIELD_LABEL": (K_SCORE_FIELD_LABEL, 60),
        "SCORE_FIELD_CONTENT": (K_SCORE_FIELD_CONTENT, 20),
        "SCORE_USAGE_PER_USE": (K_SCORE_USAGE_PER_USE, 2),
        "SCORE_USAGE_MAX_USES": (K_SCORE_USAGE_MAX_USES, 20),
        "SCORE_STARRED": (K_SCORE_STARRED, 30),
        "MIN_INITIALS_QUERY": (K_MIN_INITIALS_QUERY, 2),
        "MAX_SPANS": (K_MAX_SPANS, 8),
    }
    bad = [f"{name}: 源码 {got!r} != 期望 {want!r}" for name, (got, want) in expected.items() if got != want]
    assert not bad, "; ".join(bad)
    tiers = {"EXACT": 900, "PREFIX": 600, "CONTAINS": 300, "INITIALS": 100}
    assert TIERS == tiers, f"档位取值不一致: {TIERS} != {tiers}"
    return "PhraseSearch 9 个常量 + 4 个档位取值全部一致"


def t_tier_gap_invariant():
    bonuses = K_SCORE_FIELD_TRIGGER + usage_bonus(K_SCORE_USAGE_MAX_USES) + K_SCORE_STARRED
    assert bonuses == 170, f"附加分上限应为 170，实际 {bonuses}"
    gaps = sorted(TIERS.values())
    for i in range(1, len(gaps)):
        assert gaps[i] - gaps[i - 1] > bonuses, (
            f"档位间距 {gaps[i] - gaps[i - 1]} 必须大于附加分上限 {bonuses}，否则档位会被附加分翻转"
        )
    return f"最小档位间距 {min(b - a for a, b in zip(gaps, gaps[1:]))} > 附加分上限 {bonuses}"


def t_public_api():
    required_store = [
        "fun topOrRecent(", "fun topOrRecentIds(", "fun recent(", "fun recentIds(",
        "fun toggleStar(", "fun recordUse(", "fun isStarred(", "fun starCount(",
        "fun countOf(", "fun usage(", "fun all(", "fun forget(", "fun prune(", "fun clear(",
        "fun idOf(", "fun open(", "fun parse(", "fun format(",
    ]
    required_search = [
        "fun search(", "fun searchEntries(", "fun of(", "fun spansOf(",
        "object PhrasePinyin", "fun initials(", "fun matchSpans(", "fun matches(",
    ]
    missing = [name for name in required_store if name not in STORE_SRC]
    missing += [name for name in required_search if name not in SEARCH_SRC]
    assert not missing, f"缺少 API: {missing}"
    sync_count = STORE_SRC.count("@Synchronized")
    assert sync_count >= 14, f"@Synchronized 只出现 {sync_count} 次，读写没上锁？"
    return f"store {len(required_store)} 项 + search {len(required_search)} 项 API 齐全，@Synchronized x{sync_count}"


def t_dependency_whitelist():
    allowed = {"android.util.Log", "java.io.File", "com.ispeech.engine.QuickEntry"}
    found = set()
    for src in (STORE_SRC, SEARCH_SRC):
        for match in re.finditer(r"^import\s+([^\s;]+)", src, re.M):
            found.add(match.group(1))
    extra = sorted(found - allowed)
    assert not extra, f"出现了白名单之外的依赖（违反「无第三方依赖」）: {extra}"
    return f"仅依赖 {sorted(found)}"


def t_no_existing_file_touched():
    """只允许新建文件：校验两个 Kotlin 文件存在、包名正确、可解析。"""
    assert STORE_KT.exists() and SEARCH_KT.exists(), "新增文件缺失"
    for name, src in (("PhraseUsageStore.kt", STORE_SRC), ("PhraseSearch.kt", SEARCH_SRC)):
        assert src.lstrip().startswith("package com.ispeech.data"), f"{name} 包名不是 com.ispeech.data"
        assert "class " in src or "object " in src, f"{name} 没有类型声明"
    return "两个新文件包名 / 声明正常"


# ═════════════════════════════════════════════════════════════
# B. 行为断言（Store）
# ═════════════════════════════════════════════════════════════

def t_id_stability():
    plain = id_of("hi", "你好")
    assert plain == id_of("HI", "  你好  "), "触发码大小写 / 首尾空白应归一化为同一 id"
    assert plain == id_of("hi", "你好\r\n"), "正文尾部换行应被归一化"
    assert plain != id_of("hi2", "你好"), "不同触发码必须生成不同 id"
    assert plain != id_of("hi", "你好呀"), "不同正文必须生成不同 id"
    assert re.fullmatch(r"p1-[0-9a-f]{16}", plain), f"id 格式不符: {plain}"
    return f"id('hi','你好') = {plain}"


def t_fnv_is_standard():
    assert fnv1a64("") == 0xCBF29CE484222325, "空串哈希应等于 FNV offset basis"
    assert fnv1a64("a") == 0xAF63DC4C8601EC8C, f"FNV-1a 64('a') 不符: {kotlin_hex16(fnv1a64('a'))}"
    return "FNV-1a 64 与标准向量一致（空串 / 'a'）"


def t_star_top_order():
    with tempfile.TemporaryDirectory() as tmp:
        clock = Clock()
        store = Store(Path(tmp), clock)
        id_a, id_b, id_c = id_of("aa", "甲"), id_of("bb", "乙"), id_of("cc", "丙")
        for code, text, ident in (("aa", "甲", id_a), ("bb", "乙", id_b), ("cc", "丙", id_c)):
            store.record_use(ident, code, text)
            clock.tick()
        store.toggle_star(id_c, "cc", "丙")
        clock.tick()
        store.toggle_star(id_a, "aa", "甲")
        order = [r.id for r in store.top_or_recent(limit=10)]
        assert order == [id_a, id_c, id_b], f"收藏应置顶（组内按最近使用倒序）: {order}"
        assert store.star_count() == 2, f"starCount 应为 2，实际 {store.star_count()}"
        assert len(set(order)) == 3, "top_or_recent 不应出现重复 id"
        known = store.top_or_recent(limit=10, known_ids={id_b})
        assert [r.id for r in known] == [id_b], f"knownIds 过滤失效: {known}"
        assert store.top_or_recent(limit=0) == [], "limit<=0 应返回空"
        return f"顺序 {[oid[:6] for oid in order]}，starCount=2，knownIds 过滤生效"


def t_recent_lru_limit():
    with tempfile.TemporaryDirectory() as tmp:
        clock = Clock()
        store = Store(Path(tmp), clock)
        ids = []
        for i in range(80):
            ident = id_of(f"t{i:02d}", f"短语{i:02d}")
            ids.append(ident)
            store.record_use(ident, f"t{i:02d}", f"短语{i:02d}")
            clock.tick(10)
        recent = store.recent(999)
        assert len(recent) == K_RECENT_LIMIT, f"最近使用应截断到 {K_RECENT_LIMIT}，实际 {len(recent)}"
        assert [r.id for r in recent] == list(reversed(ids))[:K_RECENT_LIMIT], "最近使用应按时间倒序留最新 50 条"
        assert len(store.records) == K_RECENT_LIMIT, f"未收藏记录应被淘汰，实际 {len(store.records)}"
        assert len(store.file_records()) == K_RECENT_LIMIT, "文件里的记录数也应 <= 50"
        store.toggle_star(ids[0], "t00", "短语00")
        for i in range(80, 140):
            ident = id_of(f"u{i}", f"新短语{i}")
            store.record_use(ident, f"u{i}", f"新短语{i}")
            clock.tick(10)
        assert store.is_starred(ids[0]), "收藏记录不能被淘汰"
        assert store.star_count() == 1
        assert len([r for r in store.records.values() if not r.starred]) == K_RECENT_LIMIT
        assert store.top_or_recent(limit=5)[0].id == ids[0], "收藏记录应排在 topOrRecent 首位"
        return f"recent 上限 {len(recent)}，未收藏保留 {K_RECENT_LIMIT} 条，收藏不被淘汰"


def t_count_accumulate_and_dedupe():
    with tempfile.TemporaryDirectory() as tmp:
        clock = Clock()
        store = Store(Path(tmp), clock)
        ident = id_of("hi", "你好")
        for _ in range(3):
            store.record_use(ident, "hi", "你好")
            clock.tick(77)
        assert store.count_of(ident) == 3, f"计数应为 3，实际 {store.count_of(ident)}"
        assert store.count_of(id_of("HI", " 你好 ")) == 3, "归一化后应命中同一条记录"
        store.record_use(ident, "HI", " 你好 ")
        assert store.count_of(ident) == 4, "再次使用应累加到 4"
        rows = store.file_records()
        assert len(rows) == 1, f"同一短语在文件里只能有一行: {rows}"
        assert rows[0]["count"] == 4
        return "3 次累加 -> 4 次，归一化去重后文件仍只有 1 行"


def t_tick_orders_same_millisecond():
    with tempfile.TemporaryDirectory() as tmp:
        clock = Clock()
        store = Store(Path(tmp), clock)
        ids = [id_of(f"k{i}", f"文{i}") for i in range(5)]
        for i, ident in enumerate(ids):
            store.record_use(ident, f"k{i}", f"文{i}")
        assert [r.id for r in store.recent(5)] == list(reversed(ids)), "同毫秒应由 tick 定序（后用的在前）"
        assert store.recent(5)[0].last_used_ms == store.recent(5)[-1].last_used_ms
        return "同毫秒 5 条按 tick 倒序，顺序确定"


def t_corrupt_file_tolerance():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        valid = Store.format([Usage(id_of("hi", "你好"), "hi", "你好", True, 5, 1700000000000, 3)])
        lines = [
            "# 注释行",
            valid.split("\n")[2],
            "a\tb\tc",
            "id\tcode\ttext\t0\tNaN\t1\t1",
            "\t\t\t0\t1\t1\t1",
            "\x00\x01garbage",
            "",
        ]
        (path / K_FILE_NAME).write_text("\n".join(lines), encoding="utf-8")
        store = Store(path, Clock())
        assert len(store.records) == 0, "尚未访问就不该读盘（懒加载）"
        records = store.all()
        assert len(records) == 1, f"合法行应被加载: {records}"
        assert records[0].starred and records[0].count == 5, f"合法行解析错误: {records[0].count}"
        assert store.skipped_lines == 4, f"坏行数应为 4，实际 {store.skipped_lines}"
        assert store.backup_written, "发现坏行后应写 .corrupt 备份"
        assert (path / (K_FILE_NAME + ".corrupt")).exists(), "备份文件不存在"
        store.record_use(id_of("hi", "你好"), "hi", "你好")
        assert store.count_of(id_of("hi", "你好")) == 6
        return "1 条合法记录 + 4 行坏数据被跳过，备份已写，仍可继续写入"


def t_oversized_file_backed_off():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        (path / K_FILE_NAME).write_bytes(b"x" * (K_MAX_FILE_BYTES + 1))
        store = Store(path, Clock())
        assert store.all() == [], "超大文件应放弃读取并当空处理"
        assert store.backup_written, "超大文件也应备份"
        store.record_use(id_of("hi", "你好"), "hi", "你好")
        assert store.count_of(id_of("hi", "你好")) == 1
        return f">{K_MAX_FILE_BYTES} 字节 -> 视为损坏，不影响后续写入"


def t_merge_and_migrate():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        duplicate = "\n".join([
            "# 头",
            f"{id_of('hi', '你好')}\thi\t你好\t0\t3\t1000\t1",
            f"{id_of('hi', '你好')}\thi\t你好\t1\t7\t2000\t5",
        ])
        (path / K_FILE_NAME).write_text(duplicate, encoding="utf-8")
        store = Store(path, Clock())
        records = store.all()
        assert len(records) == 1, f"同 id 两行应合并为 1 条: {records}"
        assert store.duplicate_lines == 1, "重复行数应统计为 1"
        assert store.migrated_ids == 0, "id 一致时不该发生迁移"
        merged = records[0]
        assert merged.starred, "star 应取或"
        assert merged.count == 7, f"count 应取最大 7，实际 {merged.count}"
        assert merged.last_used_ms == 2000 and merged.tick == 5, "时间 / tick 应取最新"
        assert merged.code == "hi" and merged.text == "你好", "code/text 应保留"
        return "重复行合并：star 取或、count 取最大、时间取最新"


def t_legacy_id_migration():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        expected = id_of("hi", "你好")
        (path / K_FILE_NAME).write_text(
            "# 旧版头（哈希算法变更 / 手工编辑）\n"
            "legacy-abc123\thi\t你好\t1\t9\t1700000000000\t4\n",
            encoding="utf-8",
        )
        store = Store(path, Clock())
        records = store.all()
        assert len(records) == 1, f"旧 id 记录应被迁移而不是丢弃: {records}"
        assert store.migrated_ids == 1, f"迁移计数应为 1，实际 {store.migrated_ids}"
        assert records[0].id == expected, "迁移后应使用重算 id"
        assert store.is_starred(expected), "迁移必须保留收藏状态"
        assert store.count_of(expected) == 9, "迁移必须保留计数"
        text = (path / K_FILE_NAME).read_text(encoding="utf-8")
        assert expected in text and "legacy-abc123" not in text, "迁移后应立刻重写文件"
        return f"legacy id -> {expected[:11]}...，收藏 / 计数保留并重写文件"


def t_atomic_write_and_tmp_cleanup():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        clock = Clock()
        store = Store(path, clock)
        ident = id_of("hi", "你好")
        store.record_use(ident, "hi", "你好")
        assert not (path / (K_FILE_NAME + ".tmp")).exists(), "写入后不应残留 .tmp"
        assert (path / K_FILE_NAME).exists(), "目标文件应存在"
        clock.tick()
        store.record_use(ident, "hi", "你好")
        assert len(store.file_records()) == 1, "覆盖写之后仍只有 1 行"
        assert (path / (K_FILE_NAME + ".corrupt")).exists() is False, "没有坏行时不该产生备份"
        return "写入走 tmp -> os.replace，无 .tmp / .corrupt 残留"


def t_prune_forget_clear():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        clock = Clock()
        store = Store(path, clock)
        id_a, id_b, id_c = id_of("aa", "甲"), id_of("bb", "乙"), id_of("cc", "丙")
        for code, text, ident in (("aa", "甲", id_a), ("bb", "乙", id_b), ("cc", "丙", id_c)):
            store.record_use(ident, code, text)
            clock.tick()
        store.toggle_star(id_c, "cc", "丙")
        removed = store.prune({id_a})
        assert removed == 2, f"prune 应删除 2 条（含已收藏的孤儿），实际 {removed}"
        assert [r.id for r in store.all()] == [id_a], "只应保留白名单内的记录"
        assert store.forget(id_a) and store.all() == [], "forget 应删除记录"
        assert not store.forget(id_a), "重复 forget 应返回 False"
        store.record_use(id_b, "bb", "乙")
        store.clear()
        assert store.all() == [] and store.star_count() == 0, "clear 应清空全部"
        assert len(store.file_records()) == 0, "clear 后文件里不应有数据行"
        return "prune 删 2 条 / forget / clear 均生效"


def t_unstar_unused_record_removed():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        store = Store(path, Clock())
        ident = id_of("hi", "你好")
        assert store.toggle_star(ident, "hi", "你好") is True, "首次切换应为收藏"
        assert store.is_starred(ident) and store.star_count() == 1
        assert store.toggle_star(ident) is False, "再次切换应为取消收藏"
        assert not store.is_starred(ident), "取消收藏后不应是收藏态"
        assert store.usage(ident) is None, "取消收藏 + 从未使用 => 记录应被删除"
        assert store.file_records() == [], "文件里也不应留下空记录"
        store.record_use(ident, "hi", "你好")
        store.toggle_star(ident, "hi", "你好")
        store.toggle_star(ident)
        assert store.usage(ident) is not None and store.count_of(ident) == 1, "用过的短语取消收藏后应保留"
        return "未使用短语取消收藏即删除，已使用的保留计数"


def t_format_round_trip():
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        weird_text = "第一行\t带制表符\n# 看起来像注释\n反斜杠\\结尾"
        records = [
            Usage(id_of("hi", weird_text), "hi", normalize_text(weird_text), True, 2, 1700000000000, 1),
            Usage(id_of("a", "普通文本"), "a", "普通文本", False, 1, 1700000000001, 2),
        ]
        (path / K_FILE_NAME).write_text(Store.format(records), encoding="utf-8")
        store = Store(path, Clock())
        loaded = {r.id: r for r in store.all()}
        assert len(loaded) == 2, f"round-trip 记录数不符: {loaded}"
        first = loaded[records[0].id]
        assert first.text == normalize_text(weird_text), f"转义 round-trip 失败: {first.text!r}"
        assert first.starred and first.count == 2
        return "含制表符 / 换行 / 反斜杠 / # 的正文 round-trip 一致"


# ═════════════════════════════════════════════════════════════
# B. 行为断言（Search / Pinyin）
# ═════════════════════════════════════════════════════════════

def t_search_tier_order():
    entries = [
        Entry("fp", "发票抬头", "发票抬头内容", source="用户"),          # 触发码完全匹配
        Entry("fpx", "前缀命中", "前缀命中内容", source="用户"),          # 触发码前缀匹配
        Entry("zz", "含字样", "这里是 fp 的正文内容", source="用户"),      # 正文包含 ASCII 关键词
        Entry("yy", "发票", "发票", source="用户"),                       # 只能靠拼音首字母命中
    ]
    results = search(entries, keyword="fp")
    tiers = [m.tier for m in results]
    labels = [m.entry.label for m in results]
    assert tiers == [TIERS["EXACT"], TIERS["PREFIX"], TIERS["CONTAINS"], TIERS["INITIALS"]], \
        f"档位顺序错误: {list(zip(labels, tiers))}"
    scores = [m.score for m in results]
    assert scores[0] > scores[1] > scores[2] > scores[3], f"分数未严格递减: {scores}"
    assert results[0].field == FIELD_TRIGGER, f"完全匹配应命中触发码: {results[0].field}"
    assert results[2].field == FIELD_CONTENT, f"包含匹配应命中正文: {results[2].field}"
    assert results[3].field == FIELD_LABEL and results[3].spans, "首字母命中应有高亮区间"
    # 中文关键词：名称完全匹配压过名称前缀匹配，且不误命中纯 ASCII 文本。
    cn = search(entries, keyword="发票")
    assert [m.entry.label for m in cn][:2] == ["发票", "发票抬头"], f"中文关键词排序错误: {[m.entry.label for m in cn]}"
    return f"完全 > 前缀 > 包含 > 首字母，分数 {scores}；中文关键词排序正常"


def t_search_usage_and_star_do_not_flip_tier():
    usage = {id_of("zz", "这里是 fp 的正文内容"): 99}
    starred = {id_of("yy", "发票")}
    entries = [
        Entry("fp", "发票抬头", "发票抬头内容"),
        Entry("zz", "含字样", "这里是 fp 的正文内容"),
        Entry("yy", "发票", "发票"),
    ]
    results = search(entries, keyword="fp", usage_count=lambda i: usage.get(i, 0),
                     starred=lambda i: i in starred)
    assert [m.tier for m in results] == [TIERS["EXACT"], TIERS["CONTAINS"], TIERS["INITIALS"]], \
        "使用次数 99 次 / 已收藏也不得越过完全匹配这一档"
    assert results[1].count == 99 and results[2].starred, "附加分应记录在结果里"
    return "99 次使用 + 收藏仍排在完全匹配之后"


def t_search_prefix_beats_contains_in_other_field():
    entries = [
        Entry("xx", "无关", "包含关键词 fp 的正文"),
        Entry("zz", "fp 开头", "无关", source="远端"),
    ]
    results = search(entries, keyword="fp")
    assert [m.tier for m in results] == [TIERS["PREFIX"], TIERS["CONTAINS"]], \
        f"前缀必须整体压过包含: {[(m.entry.label, m.tier) for m in results]}"
    return "前缀档（名称命中）> 包含档（正文命中）"


def t_search_category_keyword():
    entries = [
        Entry("a1", "内置一", "文一", source="内置"),
        Entry("a2", "远端一", "文二", source="远端"),
        Entry("a3", "用户一", "文三", source="用户"),
        Entry("a4", "应用一", "文四", source="内置", kind="应用"),
    ]
    by_kind = search(entries, keyword="应用")
    assert [m.entry.label for m in by_kind] == ["应用一"], \
        f"关键词「应用」应命中类型分类: {[m.entry.label for m in by_kind]}"
    assert by_kind[0].field == FIELD_CATEGORY, "命中字段应为分类"
    by_source = search(entries, keyword="远端")
    assert [m.entry.label for m in by_source] == ["远端一"], "关键词应能命中来源分类"
    filtered = search(entries, category="内置")
    assert [m.entry.label for m in filtered] == ["内置一", "应用一"], \
        f"分类过滤应保留全部条目: {[m.entry.label for m in filtered]}"
    assert len(search(entries, category="全部")) == 4, "「全部」应等于不过滤"
    return "关键词命中类型 / 来源分类，category 过滤正常"


def t_search_dedupe_and_filters():
    entries = [
        Entry("hi", "你好", "你好，请问有什么可以帮您？"),
        Entry("hi", "同码同内容", "你好，请问有什么可以帮您？"),
        Entry("hi2", "你好2", "不同内容"),
    ]
    dedup = search(entries, keyword="你好")
    assert len(dedup) == 2, f"应只剩「同 id 去重 1 条」+「你好2」: {[m.entry.label for m in dedup]}"
    assert len({m.id for m in dedup}) == 2, "结果里不应出现重复 id"
    assert sorted(m.entry.label for m in dedup) == ["你好", "你好2"], \
        f"同 id 条目只应保留一条: {[m.entry.label for m in dedup]}"
    ident = id_of("hi", "你好，请问有什么可以帮您？")
    starred_only = search(entries, keyword="你好", starred_only=True, starred=lambda i: i == ident)
    assert len(starred_only) == 1, "starredOnly 过滤失效"
    allowed = search(entries, keyword="你好", allowed_ids={id_of("hi2", "不同内容")})
    assert [m.entry.label for m in allowed] == ["你好2"], "allowedIds 白名单过滤失效"
    assert search(entries, keyword="你好", limit=0) == [], "limit<=0 应返回空"
    return "同 id 去重为 1 条，starredOnly / allowedIds / limit 均生效"


def t_search_empty_keyword_ranks_by_usage():
    entries = [
        Entry("a1", "甲", "文甲", weight=100),
        Entry("a2", "乙", "文乙", weight=100),
    ]
    usage = {id_of("a2", "文乙"): 5}
    results = search(entries, keyword="", usage_count=lambda i: usage.get(i, 0))
    assert [m.entry.label for m in results] == ["乙", "甲"], "空关键词时应按使用次数排序"
    return "空关键词 -> 使用次数多的在前"


def t_spans_limits_and_case():
    spans = spans_of("AaAaAa", "aa")
    assert spans == [(0, 1), (2, 3), (4, 5)], f"大小写不敏感高亮区间错误: {spans}"
    many = spans_of("fp" * 12, "fp")
    assert len(many) == K_MAX_SPANS, f"高亮区间应截断到 {K_MAX_SPANS}，实际 {len(many)}"
    assert spans_of("你好", "你好世界") == [], "关键词长于文本应返回空"
    return f"多命中 {len(many)} 个区间（上限 {K_MAX_SPANS}），大小写不敏感"


def t_pinyin_table_integrity():
    assert len(GROUPS) > 200, "首字母表太小"
    assert not GROUP_CONFLICTS, f"同一汉字出现在不同声母分组: {GROUP_CONFLICTS}"
    assert len(INITIAL_BY_CHAR) >= 300, f"收录汉字过少: {len(INITIAL_BY_CHAR)}"
    return f"收录 {len(INITIAL_BY_CHAR)} 个汉字，无冲突分组"


def t_pinyin_known_cases():
    cases = {
        "发票": "fp", "你好": "nh", "电话": "dh", "客户": "kh", "地址": "dz",
        "快递": "kd", "订单": "dd", "金额": "je", "数量": "sl", "备注": "bz",
        "密码": "mm", "微信": "wx", "邮箱": "yx", "表格": "bg", "文件": "wj",
        "图片": "tp", "消息": "xx", "会议": "hy", "时间": "sj", "日期": "rq",
        "联系": "lx", "广东": "gd", "广州": "gz", "天河": "th", "技术": "js",
        "服务": "fw", "感谢": "gx", "生活": "sh", "愉快": "yk", "家庭": "jt",
        "品牌": "pp", "身份证": "sfz", "联系方式": "lxfs", "技术支持": "jszc",
        "公司": "gs", "账号": "zh", "支付": "zf", "退款": "tk", "物流": "wl",
    }
    bad = []
    for word, want in cases.items():
        got = pinyin_initials(word)
        if got != want:
            bad.append(f"{word}: {got} != {want}")
    assert not bad, "; ".join(bad)
    return f"{len(cases)} 组拼音首字母用例全部正确"


def t_pinyin_matching_rules():
    assert initials_spans("发票信息", "fp") == [(0, 1)], "连续首字母应命中前两个汉字"
    assert initials_spans("请提供发票", "fp") == [(3, 4)], "首字母应能在中间对齐"
    assert initials_spans("发票", "f") == [], "单字符关键词不应触发首字母匹配"
    assert initials_spans("support@example.com", "fp") == [], "纯 ASCII 文本不应被首字母匹配（防误报）"
    assert pinyin_initials("发票") == "fp"
    assert pinyin_initials("余额¥100") == "ye100", f"未收录字应显示为通配: {pinyin_initials('余额¥100')}"
    assert initials_spans("未知词条", "wz") == [(0, 1)], "未收录汉字按通配降级命中"
    assert initials_spans("知未", "wz") == [], "已收录汉字的声母不能被关键词错误匹配"
    assert initials_spans("你好世界", "nhsj") == [(0, 3)], "四个字的首字母缩写应整体命中"
    return "通配降级 / 单字符拒绝 / ASCII 拒绝 / 中间对齐均符合预期"


def t_pinyin_covers_builtin_phrases():
    assert ASSET_PHRASES.exists(), f"内置规则文件缺失: {ASSET_PHRASES}"
    total = unknown = 0
    unknown_chars = set()
    for line in read(ASSET_PHRASES).split("\n"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        for text in fields[1:3]:
            for ch in text:
                if is_cjk(ch):
                    total += 1
                    if ch not in INITIAL_BY_CHAR:
                        unknown += 1
                        unknown_chars.add(ch)
    assert total > 0, "内置规则里没有汉字？"
    coverage = (total - unknown) / total
    if coverage < 0.9:
        report("FAIL", f"内置规则汉字首字母覆盖率 {coverage:.1%} < 90%，缺失: {''.join(sorted(unknown_chars))}")
    elif coverage < 1.0:
        report("WARN", f"内置规则汉字首字母覆盖率 {coverage:.1%}，未收录: {''.join(sorted(unknown_chars))}")
    return f"内置规则 {total} 个汉字，覆盖率 {coverage:.1%}"


# ═════════════════════════════════════════════════════════════
# 主流程
# ═════════════════════════════════════════════════════════════

def main() -> int:
    print("── A. 源码漂移校验 ──")
    check("源码常量与复刻常量一致（PhraseUsageStore）", t_constants_match)
    check("源码常量与复刻常量一致（PhraseSearch）", t_search_constants_match)
    check("评分档位间距 > 附加分上限（完全 > 前缀 > 包含 不可翻转）", t_tier_gap_invariant)
    check("公开 API 齐全 + 读写上锁", t_public_api)
    check("依赖白名单（无第三方库）", t_dependency_whitelist)
    check("新增文件包名 / 结构", t_no_existing_file_touched)

    print("── B. PhraseUsageStore 行为 ──")
    check("id 稳定性 / 归一化去重", t_id_stability)
    check("FNV-1a 64 与标准向量一致", t_fnv_is_standard)
    check("收藏置顶顺序", t_star_top_order)
    check("最近使用 LRU 上限 + 收藏不被淘汰", t_recent_lru_limit)
    check("使用计数累加 + 同 id 单行", t_count_accumulate_and_dedupe)
    check("同毫秒按 tick 定序", t_tick_orders_same_millisecond)
    check("损坏文件容错 + .corrupt 备份", t_corrupt_file_tolerance)
    check("超大文件放弃读取", t_oversized_file_backed_off)
    check("重复行合并策略", t_merge_and_migrate)
    check("旧 id 迁移策略", t_legacy_id_migration)
    check("原子写入 / 无 .tmp 残留", t_atomic_write_and_tmp_cleanup)
    check("prune / forget / clear", t_prune_forget_clear)
    check("取消收藏（未使用则删除记录）", t_unstar_unused_record_removed)
    check("存储格式 round-trip（转义）", t_format_round_trip)

    print("── C. PhraseSearch / PhrasePinyin 行为 ──")
    check("搜索档位排序（完全 > 前缀 > 包含 > 首字母）", t_search_tier_order)
    check("使用次数 / 收藏不翻转档位", t_search_usage_and_star_do_not_flip_tier)
    check("前缀档压过包含档（跨字段）", t_search_prefix_beats_contains_in_other_field)
    check("关键词命中分类 + category 过滤", t_search_category_keyword)
    check("搜索去重 + starredOnly / allowedIds", t_search_dedupe_and_filters)
    check("空关键词按使用次数排序", t_search_empty_keyword_ranks_by_usage)
    check("高亮区间（大小写 / 上限 / 越界）", t_spans_limits_and_case)
    check("首字母表完整性", t_pinyin_table_integrity)
    check("拼音首字母已知用例", t_pinyin_known_cases)
    check("首字母匹配规则（通配 / 拒绝条件）", t_pinyin_matching_rules)
    check("内置规则汉字覆盖率", t_pinyin_covers_builtin_phrases)

    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
