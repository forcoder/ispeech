#!/usr/bin/env python3
"""「通用短语」CSV 导入链路校验。

纯静态 + 数据测试：不编译、不联网、不依赖 JDK —— 与 `check_quick_trigger.py` 同一套离线自查方式。

背景：用户把搜狗 / csBaby 导出的「通用短语.csv」（GBK、10 列、235 行、117 条内容带换行）
交给产品，要求「导入到常用语里，敲快捷字母就能出候选」。这条链路由四段组成，任何一段
偏离都会让导入静默失败（条数对不上、中文变乱码、长文本被截断、同码短语被吞）：

| 环节 | 位置 |
| --- | --- |
| 字节解码 | `PhraseCsvImport.decode`（严格 UTF-8 → GBK 回退） |
| CSV 解析 | `CsvTable.parse`（RFC 4180：引号字段 / 字段内换行 / `""` 转义） |
| 映射规则 | `PhraseCsvImport.parse`（trigger / label / content / mode / weight / kind） |
| 落盘兼容 | `QuickPhraseCodec.format` ↔ `QuickPhraseEngine.fromText` 的 `\\n` / `\\t` 字面量 |
| 入口与提示 | `QuickPhrasesScreen`（SAF）+ `QuickPhrasesViewModel.importPhraseCsv` + 中英文案 |

检查项：
    A. 真实 CSV 的解析结果与预期条数一致（235 行 → 224 条，跳过项逐类核对）；
    B. 映射口径：trigger 小写唯一、1 位用 exact、超 4 位仍导入、label 首行截断且全局唯一；
    C. 端到端候选：敲 `ly` / `hxww` / 长码前 4 位都能命中，1 位码不污染其它输入；
    D. 落盘回读：224 条内容经 `QuickPhraseCodec` 转义 / 反转义后逐字相等；
    E. 源码接线：解码 / 解析 / ViewModel / 屏幕入口 / 中英文案都在；
    F. 导入不破坏既有约束：手动录入仍是 2–4 位小写，`QuickPhraseCodec` 仍只转义 `\\r \\n \\t`。

用法：``python3 tools/verify/check_phrase_csv_import.py [csv路径]``
      默认读 ``tools/verify/fixtures/general_phrases.csv``（真实导出文件的基线，随仓库提交）；
      也接受仓库根目录的 ``通用短语.csv``（手工下载时常见位置）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE_CSV = ROOT / "tools/verify/fixtures/general_phrases.csv"
LEGACY_CSV = ROOT / "通用短语.csv"
IMPORTER = ROOT / "app/src/main/java/com/ispeech/data/PhraseCsvImport.kt"
QUICK_VM = ROOT / "app/src/main/java/com/ispeech/settings/quick/QuickPhrasesViewModel.kt"
QUICK_SCREEN = ROOT / "app/src/main/java/com/ispeech/settings/QuickPhrasesScreen.kt"
QUICK_TEST = ROOT / "app/src/test/java/com/ispeech/data/PhraseCsvImportTest.kt"
STRINGS_ZH = ROOT / "app/src/main/res/values/strings.xml"
STRINGS_EN = ROOT / "app/src/main/res/values-en/strings.xml"
DOC = ROOT / "docs/quick-phrase-enhancement.md"

# 导入结果的基线（235 行源文件的真实分布，改动导入口径要同步改这里）
EXPECTED_TOTAL_ROWS = 235
EXPECTED_IMPORTED = 224
EXPECTED_NO_CODE = 1
EXPECTED_DUPLICATE = 10
EXPECTED_DELETED = 0
EXPECTED_EMPTY = 0
EXPECTED_LONG = 60
EXPECTED_SINGLE = 1

MAX_TRIGGER_LENGTH = 12
LABEL_MAX_CODE_POINTS = 20
WUBI_MAX_CODE_LENGTH = 4
DEFAULT_WEIGHT = 100

TRIGGER_RE = re.compile(r"[a-z]{1,%d}" % MAX_TRIGGER_LENGTH)

CODE_HEADERS = ("input_code", "code", "keyword", "trigger", "mnemonic", "简写", "快捷字母", "编码")
CONTENT_HEADERS = ("content", "reply_template", "reply", "text", "内容", "短语内容")
GROUP_HEADERS = ("group_name", "group", "category", "分组", "分组名称")
DELETED_HEADERS = ("is_delete", "deleted", "is_deleted", "已删除")

_passed = 0
_failed = 0
_warned = 0


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def ok(msg: str) -> None:
    global _passed
    _passed += 1


def fail(msg: str) -> None:
    global _failed
    _failed += 1
    print(f"FAIL: {msg}")


def warn(msg: str) -> None:
    global _warned
    _warned += 1
    print(f"WARN: {msg}")


def check(cond: bool, ok_msg: str, fail_msg: str) -> None:
    if cond:
        ok(ok_msg)
    else:
        fail(fail_msg)


# ──────────────────────────────────────────────────────────────
#  PhraseCsvImport 的 Python 同构实现（逐行对照 Kotlin）
# ──────────────────────────────────────────────────────────────


def decode_csv_bytes(data: bytes) -> str:
    """与 PhraseCsvImport.decode 一致：去 BOM → 严格 UTF-8 → GBK 回退。"""
    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]
    try:
        return data.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        return data.decode("gbk")


def parse_csv_table(text: str) -> list[list[str]]:
    """与 CsvTable.parse 状态机一致：引号字段、字段内换行、`""` 转义。"""
    rows: list[list[str]] = []
    row: list[str] = []
    field: list[str] = []
    quoted = False
    field_started = False
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if quoted:
            if ch == '"':
                if i + 1 < n and text[i + 1] == '"':
                    field.append('"')
                    i += 2
                    continue
                quoted = False
                i += 1
                continue
            field.append(ch)
            i += 1
            continue
        if ch == '"' and not field_started:
            quoted = True
            field_started = True
            i += 1
        elif ch == '"':
            field.append(ch)
            i += 1
        elif ch == ",":
            row.append("".join(field))
            field = []
            field_started = False
            i += 1
        elif ch in "\r\n":
            row.append("".join(field))
            field = []
            field_started = False
            rows.append(row)
            row = []
            i += 2 if ch == "\r" and i + 1 < n and text[i + 1] == "\n" else 1
        else:
            field.append(ch)
            field_started = True
            i += 1
    if field or row:
        row.append("".join(field))
        rows.append(row)
    return rows


def normalize_header(raw: str) -> str:
    return raw.removeprefix("\ufeff").strip().strip('"').lower().replace("-", "_").replace(" ", "_")


def column_of(header: list[str], aliases: tuple[str, ...]) -> int | None:
    for alias in aliases:
        if alias in header:
            return header.index(alias)
    return None


def cell(row: list[str], index: int | None) -> str | None:
    if index is None or index < 0 or index >= len(row):
        return None
    return row[index]


def normalize_content(raw: str) -> str:
    """CRLF / 孤立 CR → \\n；逐行去行尾空白；去首尾空行；连续空行最多一行；首行去前导空白。"""
    if not raw:
        return ""
    unified = raw.replace("\r\n", "\n").replace("\r", "\n")
    lines = [line.rstrip(" \t") for line in unified.split("\n")]
    start = 0
    end = len(lines)
    while start < end and not lines[start].strip():
        start += 1
    while end > start and not lines[end - 1].strip():
        end -= 1
    out: list[str] = []
    blank_run = 0
    for line in lines[start:end]:
        if not line.strip():
            blank_run += 1
            if blank_run > 1:
                continue
        else:
            blank_run = 0
        out.append(line)
    return "\n".join(out).lstrip(" \t")


def label_of(content: str, group: str = "") -> str:
    first = ""
    for line in content.split("\n"):
        if line.strip():
            first = line.strip()
            break
    base = first or group
    if len(base) <= LABEL_MAX_CODE_POINTS:
        return base
    return base[:LABEL_MAX_CODE_POINTS] + "…"


def unique_label(base: str, counter: dict[str, int]) -> str:
    label = base or "短语"
    counter[label] = counter.get(label, 0) + 1
    count = counter[label]
    return label if count == 1 else f"{label}·{count}"


def is_deleted(raw: str | None) -> bool:
    if raw is None:
        return False
    value = raw.strip().lower()
    return value not in ("", "0", "false", "no")


def import_phrases(text: str) -> dict:
    """与 PhraseCsvImport.parse 一致的映射，返回 entries 与各类计数。"""
    result = {
        "entries": [],
        "total_rows": 0,
        "skipped_no_code": 0,
        "skipped_bad_code": 0,
        "skipped_duplicate": 0,
        "skipped_deleted": 0,
        "skipped_empty": 0,
        "long_codes": 0,
        "single_letter": 0,
        "format_error": None,
    }
    table = parse_csv_table(text)
    if not table:
        result["format_error"] = "CSV 为空"
        return result
    header = [normalize_header(h) for h in table[0]]
    code_index = column_of(header, CODE_HEADERS)
    content_index = column_of(header, CONTENT_HEADERS)
    if code_index is None or content_index is None:
        result["format_error"] = "缺少 INPUT_CODE / CONTENT 列"
        return result
    group_index = column_of(header, GROUP_HEADERS)
    deleted_index = column_of(header, DELETED_HEADERS)

    seen: set[str] = set()
    labels: dict[str, int] = {}
    for row in table[1:]:
        if all(not c.strip() for c in row):
            continue
        result["total_rows"] += 1
        if is_deleted(cell(row, deleted_index)):
            result["skipped_deleted"] += 1
            continue
        content = normalize_content(cell(row, content_index) or "")
        if not content:
            result["skipped_empty"] += 1
            continue
        code = (cell(row, code_index) or "").strip().lower()
        if not code:
            result["skipped_no_code"] += 1
            continue
        if not TRIGGER_RE.fullmatch(code):
            result["skipped_bad_code"] += 1
            continue
        if code in seen:
            result["skipped_duplicate"] += 1
            continue
        seen.add(code)
        group = (cell(row, group_index) or "").strip()
        label = unique_label(label_of(content, group), labels)
        if len(code) > WUBI_MAX_CODE_LENGTH:
            result["long_codes"] += 1
        if len(code) == 1:
            result["single_letter"] += 1
        result["entries"].append(
            {
                "trigger": code,
                "label": label,
                "content": content,
                "weight": DEFAULT_WEIGHT,
                "mode": "exact" if len(code) == 1 else "prefix",
                "kind": "p",
            }
        )
    return result


# ──────────────────────────────────────────────────────────────
#  QuickPhraseCodec / QuickPhraseEngine 的同构实现
# ──────────────────────────────────────────────────────────────


def sanitize_field(value: str) -> str:
    return value.replace("\t", " ").replace("\n", " ").replace("\r", " ")


def sanitize_content(value: str) -> str:
    return value.replace("\r", "").replace("\n", "\\n").replace("\t", "\\t")


def codec_format(entries: list[dict]) -> str:
    lines = ["# test"]
    for entry in entries:
        lines.append(
            "\t".join(
                [
                    sanitize_field(entry["trigger"]),
                    sanitize_field(entry["label"]),
                    sanitize_content(entry["content"]),
                    entry["mode"],
                    str(entry["weight"]),
                    entry["kind"],
                ]
            )
        )
    return "\n".join(lines) + "\n"


def unescape_line_text(text: str) -> str:
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
            elif nxt == "r":
                out.append("\r")
                i += 2
            elif nxt == "t":
                out.append("\t")
                i += 2
            elif nxt == "\\":
                out.append("\\")
                i += 2
            else:
                out.append(ch)
                i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def codec_parse(text: str) -> list[dict]:
    by_trigger: dict[str, dict] = {}
    for raw in text.split("\n"):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 3:
            continue
        trigger = fields[0].strip()
        content = unescape_line_text(fields[2].strip())
        if not trigger or not content:
            continue
        label = fields[1].strip() or content
        by_trigger[trigger] = {
            "trigger": trigger,
            "label": label,
            "content": content,
            "mode": fields[3].strip().lower() if len(fields) > 3 else "prefix",
            "weight": int(fields[4]) if len(fields) > 4 and fields[4].strip().lstrip("-").isdigit() else 100,
            "kind": "a" if len(fields) > 5 and fields[5].strip().lower().startswith("a") else "p",
        }
    return list(by_trigger.values())


KIND_ORDER = {"a": 0, "p": 1, "wubi": 2, "wubi_phrase": 3, "pinyin": 4}


def engine_lookup(entries: list[dict], needle: str, limit: int = 10, min_trigger_length: int = 2) -> list[dict]:
    """与 QuickPhraseEngine.lookup + CandidateRanking 一致（只到本用例需要的档位）。"""
    needle = needle.lower()
    if not needle or limit <= 0:
        return []
    out = []
    for entry in entries:
        trigger = entry["trigger"].lower()
        guarded = entry["mode"] in ("prefix", "contains")
        if guarded and len(needle) < min_trigger_length:
            continue
        if entry["mode"] == "exact":
            hit = trigger == needle
        elif entry["mode"] == "prefix":
            hit = trigger.startswith(needle) or needle.startswith(trigger)
        else:
            hit = False
        if hit:
            out.append(entry)
    out.sort(
        key=lambda e: (
            0 if e["trigger"].lower() == needle else 1,
            KIND_ORDER.get(e["kind"], 9),
            -e["weight"],
            e["label"],
        )
    )
    return out[:limit]


# ──────────────────────────────────────────────────────────────
#  检查项
# ──────────────────────────────────────────────────────────────


def check_real_csv(csv_path: Path) -> list[dict]:
    if not csv_path.exists():
        fail(f"找不到 CSV：{csv_path}（基线文件应随仓库提交）")
        return []
    raw = csv_path.read_bytes()
    text = decode_csv_bytes(raw)
    check("鹭影" in text, "真实 CSV：GBK 字节解码出中文（严格 UTF-8 失败后回退 GBK）",
          "真实 CSV 解码后没有「鹭影」：编码回退失效或被硬解成乱码")

    result = import_phrases(text)
    check(result["format_error"] is None, "真实 CSV：表头含 INPUT_CODE / CONTENT",
          f"真实 CSV 表头识别失败：{result['format_error']}")
    check(result["total_rows"] == EXPECTED_TOTAL_ROWS,
          f"真实 CSV：{EXPECTED_TOTAL_ROWS} 行数据全部被识别（引号多行字段没有拆行）",
          f"行数不符：期望 {EXPECTED_TOTAL_ROWS}，实际 {result['total_rows']}（多行字段被按行拆开了）")
    check(len(result["entries"]) == EXPECTED_IMPORTED,
          f"真实 CSV：导入 {EXPECTED_IMPORTED} 条（同码合并 / 无码跳过后）",
          f"导入条数不符：期望 {EXPECTED_IMPORTED}，实际 {len(result['entries'])}")
    check(result["skipped_no_code"] == EXPECTED_NO_CODE,
          f"真实 CSV：{EXPECTED_NO_CODE} 条无快捷字母被跳过并计数",
          f"无码计数不符：{result['skipped_no_code']}")
    check(result["skipped_duplicate"] == EXPECTED_DUPLICATE,
          f"真实 CSV：{EXPECTED_DUPLICATE} 条同码重复被合并并计数",
          f"同码计数不符：{result['skipped_duplicate']}")
    check(result["skipped_deleted"] == EXPECTED_DELETED and result["skipped_empty"] == EXPECTED_EMPTY,
          "真实 CSV：无删除标记 / 空内容行",
          f"删除 / 空内容计数异常：{result['skipped_deleted']} / {result['skipped_empty']}")
    check(result["long_codes"] == EXPECTED_LONG,
          f"真实 CSV：{EXPECTED_LONG} 条超 4 位快捷字母按前缀命中口径导入",
          f"超 4 位计数不符：{result['long_codes']}")
    check(result["single_letter"] == EXPECTED_SINGLE,
          f"真实 CSV：{EXPECTED_SINGLE} 条 1 位字母按 exact 导入",
          f"1 位字母计数不符：{result['single_letter']}")

    entries = result["entries"]
    triggers = [e["trigger"] for e in entries]
    check(len(triggers) == len(set(triggers)), "映射：trigger 全局唯一（用户文件按 trigger 唯一）",
          "映射：出现重复 trigger，落盘时会互相覆盖")
    check(all(TRIGGER_RE.fullmatch(t) for t in triggers), "映射：trigger 全是 1–12 位小写字母",
          "映射：存在非法 trigger（大写 / 数字 / 超长）")
    check(all(e["mode"] == "exact" for e in entries if len(e["trigger"]) == 1),
          "映射：1 位字母用 exact（prefix 会被「单字母不触发」守卫拦死）",
          "映射：1 位字母仍是 prefix —— 该条在默认设置下永远打不出来")
    check(all(e["mode"] == "prefix" for e in entries if len(e["trigger"]) > 1),
          "映射：2 位以上用 prefix（完整输入命中精确码，长码打前 4 位也能命中）",
          "映射：2 位以上不是 prefix，长码在五笔态将无法命中")
    check(all(e["kind"] == "p" for e in entries), "映射：全部按短语（kind=p）导入",
          "映射：出现非短语类型")
    labels = [e["label"] for e in entries]
    check(len(labels) == len(set(labels)),
          "映射：label 全局唯一（CompositeEngine 按 display 去重，重名会吞短语）",
          "映射：label 有重复，候选条会丢掉其中一条")
    check(all(e["label"] for e in entries), "映射：每条都有可展示的短名",
          "映射：存在空 label，候选条会显示空白")
    check(all(e["content"] for e in entries), "映射：每条都有上屏内容", "映射：存在空内容")
    multiline = [e for e in entries if "\n" in e["content"]]
    check(len(multiline) > 0, f"映射：多行内容保留换行（{len(multiline)} 条）",
          "映射：多行内容被压成单行")
    check(all("\r" not in e["content"] for e in entries), "映射：内容里的 CR / CRLF 已规范化",
          "映射：内容里仍残留 \\r（落盘会多出空行）")
    check(all(e["content"] == e["content"].rstrip() for e in entries),
          "映射：内容末尾无空白（QuickPhraseCodec 解析时会 trim，末尾空白存不住）",
          "映射：内容以空白结尾，落盘回读会丢字符")

    # —— 候选验收：敲快捷字母出对应短语 ——
    ly = engine_lookup(entries, "ly")
    check(bool(ly) and ly[0]["trigger"] == "ly" and ly[0]["content"] == "鹭影",
          "候选：敲 ly → 候选第 1 位是「鹭影」",
          f"候选：敲 ly 没拿到「鹭影」，实际 {[e['label'] for e in ly][:3]}")

    hxww = engine_lookup(entries, "hxww")
    check(bool(hxww) and hxww[0]["trigger"] == "hxww" and hxww[0]["label"].startswith("湖山鹭韵五街5号"),
          "候选：敲 hxww → 候选第 1 位是「湖山鹭韵五街5号」户型长文本",
          f"候选：敲 hxww 结果不符，实际 {[e['label'] for e in hxww][:3]}")

    # 长码在五笔态只能打到 4 位：必须靠「trigger 以输入为前缀」命中
    long_prefix = engine_lookup(entries, "hxws")
    check(any(e["trigger"] == "hxwslw" for e in long_prefix),
          "候选：长码 hxwslw 打前 4 位 hxws 即可命中",
          "候选：长码打前 4 位没有命中，五笔态下该短语不可达")

    j = engine_lookup(entries, "j")
    check(len(j) == 1 and j[0]["trigger"] == "j", "候选：敲 j → 只出 1 位码那一条",
          f"候选：敲 j 命中了 {len(j)} 条（1 位码不应参与前缀匹配）")
    check(not any(e["trigger"] == "j" for e in engine_lookup(entries, "jx")),
          "候选：1 位码不污染后面的输入（jx 不再出 j 那条）",
          "候选：1 位码在后续输入里仍然命中")

    # 同码竞争：精确命中 + 短语档位必须压过五笔同码候选
    ranking_input = "ly"
    phrase = {"trigger": "ly", "label": "鹭影", "content": "鹭影", "weight": 100, "mode": "prefix", "kind": "p"}
    wubi = {"trigger": "ly", "label": "滤", "content": "滤", "weight": 30000, "mode": "prefix", "kind": "wubi"}
    ranked = sorted(
        [phrase, wubi],
        key=lambda e: (
            0 if e["trigger"] == ranking_input else 1,
            KIND_ORDER[e["kind"]],
            -e["weight"],
            e["label"],
        ),
    )
    check(ranked[0]["label"] == "鹭影", "排序：精确码 + 短语档位排在五笔同码候选之前",
          "排序：短语被五笔同码候选挤到后面")

    # —— 落盘回读：224 条内容逐字一致 ——
    stored = codec_format(entries)
    read_back = codec_parse(stored)
    by_trigger = {e["trigger"]: e for e in read_back}
    mismatch = [
        e["trigger"] for e in entries
        if e["trigger"] not in by_trigger or by_trigger[e["trigger"]]["content"] != e["content"]
    ]
    check(not mismatch,
          f"落盘：{len(entries)} 条经 QuickPhraseCodec 转义 → 解析后内容逐字相等",
          f"落盘：{len(mismatch)} 条内容回读不一致，例如 {mismatch[:5]}")
    check(len(read_back) == len(entries), "落盘：回读条数与导入条数一致（无同码覆盖）",
          f"落盘：回读 {len(read_back)} 条 ≠ 导入 {len(entries)} 条")
    return entries


def check_source_wiring() -> None:
    importer = read(IMPORTER)
    check(
        "fun decode(bytes: ByteArray): String" in importer
        and "CodingErrorAction.REPORT" in importer
        and 'Charset.forName("GBK")' in importer,
        "源码：PhraseCsvImport 先严格 UTF-8 解码、失败回退 GBK",
        "源码：PhraseCsvImport 缺少编码探测（GBK 文件会变乱码）",
    )
    check(
        "private object CsvTable" in importer
        and 'text[i + 1] == \'"\'' in importer
        and "quoted" in importer,
        "源码：CSV 解析器处理引号字段 / 字段内换行 / 双引号转义",
        "源码：CSV 解析器不处理引号字段（多行内容会被拆行）",
    )
    check(
        "fun parse(text: String): PhraseCsvImportResult" in importer
        and "skippedDuplicateCode" in importer
        and "longCodeCount" in importer
        and "singleLetterCount" in importer,
        "源码：parse 返回导入结果并逐类计数（不静默丢行）",
        "源码：parse 缺少跳过计数",
    )
    check(
        "QuickMatchMode.EXACT" in importer and "QuickMatchMode.PREFIX" in importer,
        "源码：1 位码 exact、其余 prefix 的映射在实现里",
        "源码：映射模式缺失",
    )
    check(
        "trimStart(' ', '\\t')" in importer,
        "源码：normalizeContent 去掉首行前导空白（否则落盘 trim 后回读会少字符）",
        "源码：normalizeContent 未去首行前导空白，与 QuickPhraseCodec.parse 的 trim 不一致",
    )

    vm = read(QUICK_VM)
    check(
        "fun importPhraseCsv(uri: Uri)" in vm
        and "PhraseCsvImport.parse(PhraseCsvImport.decode(bytes))" in vm
        and "contentResolver.openInputStream(uri)" in vm,
        "源码：ViewModel 从 SAF Uri 读取字节并走导入器",
        "源码：ViewModel 未接线 CSV 导入",
    )
    check(
        "withContext(Dispatchers.IO)" in vm and "dirty = true" in vm and "QuickSource.USER" in vm,
        "源码：导入在 IO 线程读文件，结果进用户规则并标记待保存",
        "源码：导入未切 IO 线程或未进用户规则 / 未标记 dirty",
    )

    screen = read(QUICK_SCREEN)
    check(
        "ActivityResultContracts.OpenDocument()" in screen
        and "viewModel.importPhraseCsv(it)" in screen,
        "源码：快捷短语页有 SAF 文件选择入口（不申请存储权限）",
        "源码：屏幕没有 CSV 导入入口",
    )
    check(
        "R.string.quick_import_csv" in screen and "application/vnd.ms-excel" in screen,
        "源码：导入按钮用文案资源，并兼容 Excel 导出的 MIME",
        "源码：导入按钮缺文案资源或 MIME 白名单",
    )

    check(QUICK_TEST.exists() and "class PhraseCsvImportTest" in read(QUICK_TEST),
          "测试：PhraseCsvImportTest 覆盖编码 / 多行 / 同码 / 1 位 / 长码",
          "测试：缺少 PhraseCsvImportTest")

    doc = read(DOC) if DOC.exists() else ""
    check("通用短语.csv" in doc and "INPUT_CODE" in doc,
          "文档：docs/quick-phrase-enhancement.md 记录了 CSV 导入口径",
          "文档：未记录 CSV 导入（后续维护者看不出 1 位 / 长码 / 同码的处理口径）")

    zh = read(STRINGS_ZH)
    en = read(STRINGS_EN)
    zh_keys = set(re.findall(r'<string name="(quick_import_[a-z_]+)"', zh))
    en_keys = set(re.findall(r'<string name="(quick_import_[a-z_]+)"', en))
    used = set(re.findall(r"R\.string\.(quick_import_[a-z_]+)", read(QUICK_VM) + "\n" + screen))
    check(zh_keys and zh_keys == en_keys,
          f"文案：{len(zh_keys)} 个 quick_import_* 键中英文齐全",
          f"文案：中英文键不一致，zh-only={sorted(zh_keys - en_keys)} en-only={sorted(en_keys - zh_keys)}")
    check(used and used <= zh_keys,
          "文案：ViewModel / 屏幕引用的导入文案都在资源里（无 R.string 悬空引用）",
          f"文案：引用了未定义的键 {sorted(used - zh_keys)}")


def check_existing_contract_unchanged() -> None:
    """导入不能放宽手动录入的 2–4 位约束，也不能改落盘转义格式。"""
    screen = read(QUICK_SCREEN)
    check(
        "internal fun isValidShortcutTrigger(trigger: String): Boolean" in screen
        and "trigger.length in 2..4" in screen,
        "回归：手动录入仍是 2–4 位小写字母（导入路径独立，不放宽 UI 校验）",
        "回归：手动录入的快捷字母校验被改动",
    )
    entry = read(ROOT / "app/src/main/java/com/ispeech/engine/QuickEntry.kt")
    check("fun matchesAny(" in entry, "回归：QuickOverride 覆盖判定未被改动",
          "回归：QuickOverride 被改动")
    user_dict = read(ROOT / "app/src/main/java/com/ispeech/data/UserDictStore.kt")
    check(
        "internal fun unescapeLineText(text: String)" in user_dict and "'n' ->" in user_dict,
        "回归：unescapeLineText 仍还原 \\n（落盘格式与引擎一致）",
        "回归：unescapeLineText 被改动，落盘格式契约失效",
    )


def default_csv_path() -> Path:
    """基线文件优先用仓库内的固定路径，其次兼容用户手工下载到根目录的位置。"""
    return FIXTURE_CSV if FIXTURE_CSV.exists() else LEGACY_CSV


def main() -> int:
    csv_path = Path(sys.argv[1]) if len(sys.argv) > 1 else default_csv_path()
    entries = check_real_csv(csv_path)
    if entries:
        check_source_wiring()
        check_existing_contract_unchanged()
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())