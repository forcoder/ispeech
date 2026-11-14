#!/usr/bin/env python3
"""快捷短语「简写（触发码）」规则校验。

纯静态检查：解析 assets/quick_phrases.txt 与引擎 / 排序源码，不编译、不联网。

背景：用户要求「输入某几个字母 → 候选第 1 位是该短语，后面再跟其他候选」。
该能力由三处共同保证，任何一处被改坏都会让需求静默失效：

1. ``LookupKind.order``：``QUICKAPPLY`` 必须排在 ``WUBI`` 之前（短语才能压过五笔候选）；
2. ``CandidateRanking``：精确命中触发码的候选要优先（输入完整简写时排最前）；
3. ``QuickPhraseEngine.matches``：``prefix`` / ``contains`` 模式在输入长度不足
   ``minTriggerLength`` 时不返回（默认 2，"单字母不触发快捷规则" 打开时），
   ——**因此 1 字母简写必须写 ``exact`` 模式，否则该条静默失效**。

检查项：
    A. 文件中每条规则的字段完整性（trigger / label / content 必填）；
    B. trigger 只能是小写 a-z；> 12 位（拼音态组合串 MAX_PINYIN_LENGTH）直接判死，
       5-12 位且用 prefix / contains 时告警 —— 五笔态组合缓冲区 MAX_CODE_LENGTH=4，
       完整码只能在拼音态敲出，拿不到「精确码优先」的首位加成（exact / regex 不受影响）；
    C. trigger 全局唯一（大小写不敏感）；
    D. **1 字母 trigger 必须是 exact / regex 模式**（否则静默失效）；
    E. weight 为整数、kind 为 p/a、mode 为四种之一；
    F. 无重复的 (trigger, content)；
    G. 源码侧的排序契约未被改坏（QUICKAPPLY 在 WUBI 之前 + 精确码优先 + 守卫存在）；
    H. 用户自定义快捷字母全链路：设置页 2–4 位小写校验 / 中英文案 / 面板角标 / 保存即生效；
    I. 需求场景验收：输入 ``ycsf`` 时快捷短语排在候选第 1 位（与 Kotlin 排序同构）；
    J. 远端规则的查看与快捷字母覆盖：远端进列表 / 覆盖写用户文件 / 排序胜出 / 恢复默认；
    L. 内置 5 位触发码 dtwsl（泡池 / 充气城堡 / 泳池收费说明）：正文逐字一致、exact 排首位、
       两种输入态的缓冲区行为、upsert 工具幂等。

用法：``python3 tools/verify/check_quick_trigger.py``
"""

from __future__ import annotations

import hashlib
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PHRASES = ROOT / "app/src/main/assets/quick_phrases.txt"
LOOKUP_CANDIDATE = ROOT / "app/src/main/java/com/ispeech/engine/LookupCandidate.kt"
LOOKUP_ENGINE = ROOT / "app/src/main/java/com/ispeech/engine/LookupEngine.kt"
QUICK_ENGINE = ROOT / "app/src/main/java/com/ispeech/engine/QuickPhraseEngine.kt"
QUICK_ENTRY = ROOT / "app/src/main/java/com/ispeech/engine/QuickEntry.kt"
QUICK_LOCATOR = ROOT / "app/src/main/java/com/ispeech/engine/QuickLocator.kt"
COMPOSITE_ENGINE = ROOT / "app/src/main/java/com/ispeech/engine/CompositeEngine.kt"
QUICK_SCREEN = ROOT / "app/src/main/java/com/ispeech/settings/QuickPhrasesScreen.kt"
QUICK_PANEL = ROOT / "app/src/main/java/com/ispeech/input/QuickPhrasePanel.kt"
QUICK_VM = ROOT / "app/src/main/java/com/ispeech/settings/quick/QuickPhrasesViewModel.kt"
SYNC_REPO = ROOT / "app/src/main/java/com/ispeech/data/SyncRepository.kt"
APP_CONTAINER = ROOT / "app/src/main/java/com/ispeech/di/AppContainer.kt"
STRINGS_ZH = ROOT / "app/src/main/res/values/strings.xml"
STRINGS_EN = ROOT / "app/src/main/res/values-en/strings.xml"
DOC = ROOT / "docs/quick-phrase-enhancement.md"

# 内置 5 位触发码验收（泡池 / 充气城堡 / 泳池收费说明）
DTWSL_TRIGGER = "dtwsl"
DTWSL_LABEL = "泡池须知"
DTWSL_SOURCE = ROOT / "tools/quick/phrases/dtwsl.txt"
UPSERT_TOOL = ROOT / "tools/quick/upsert_quick_phrase.py"
IME_SERVICE = ROOT / "app/src/main/java/com/ispeech/input/IspeechImeService.kt"
PHRASE_SEARCH = ROOT / "app/src/main/java/com/ispeech/data/PhraseSearch.kt"
SOGOU_PHRASES = ROOT / "app/src/main/assets/sogou_phrases.txt"

VALID_MODES = {"exact", "prefix", "contains", "regex"}
# prefix / contains 会被 minTriggerLength 守卫拦下；单字母规则只能用这两个模式之一
UNGUARDED_MODES = {"exact", "regex"}

_passed = 0
_failed = 0
_warned = 0


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


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
    """单一断言：cond 为真记 PASS，否则记 FAIL。"""
    if cond:
        ok(ok_msg)
    else:
        fail(fail_msg)


def parse_phrases() -> list[dict]:
    """解析规则表；返回 [{line, trigger, label, content, mode, weight, kind}]."""
    rows: list[dict] = []
    if not PHRASES.exists():
        fail(f"规则表不存在：{PHRASES}")
        return rows
    for lineno, raw in enumerate(PHRASES.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = raw.split("\t")
        if len(fields) < 3:
            fail(f"quick_phrases.txt:{lineno} 字段不足 3 个（trigger/label/content 必填）")
            continue
        rows.append(
            {
                "line": lineno,
                "trigger": fields[0].strip(),
                "label": fields[1].strip(),
                "content": fields[2].strip(),
                "mode": fields[3].strip().lower() if len(fields) > 3 else "prefix",
                "weight": fields[4].strip() if len(fields) > 4 else "100",
                "kind": fields[5].strip().lower() if len(fields) > 5 else "",
            }
        )
    return rows


def check_rows(rows: list[dict]) -> None:
    seen_trigger: dict[str, int] = {}
    seen_pair: dict[tuple[str, str], int] = {}
    single_letter: list[str] = []

    for row in rows:
        tag = f"quick_phrases.txt:{row['line']}"

        # A. 必填字段
        if not row["label"]:
            fail(f"{tag} label 为空")
        if not row["content"]:
            fail(f"{tag} content 为空")

        # B. trigger 形态：只允许小写 a-z（长度单独判定，见下）
        trig = row["trigger"]
        if not re.fullmatch(r"[a-z]+", trig):
            fail(f"{tag} trigger 含非法字符: {trig!r}（只允许小写 a-z）")
            continue
        if len(trig) > 4 and row["mode"] not in UNGUARDED_MODES:
            warn(
                f"{tag} trigger {trig!r} 超过 4 位且用的是 {row['mode']} —— "
                f"五笔态组合缓冲区上限 MAX_CODE_LENGTH=4，完整码只能在拼音态敲出，"
                f"拿不到「精确码优先」的首位加成"
            )
        if len(trig) > 12:
            fail(
                f"{tag} trigger {trig!r} 超过拼音态组合串上限 MAX_PINYIN_LENGTH=12 —— "
                f"完整码永远打不出来，该条静默失效"
            )

        # C. trigger 唯一
        low = trig.lower()
        if low in seen_trigger:
            fail(
                f"{tag} trigger 重复: {row['trigger']!r}"
                f"（已在第 {seen_trigger[low]} 行定义）"
            )
        else:
            seen_trigger[low] = row["line"]

        # D. 1 字母必须用不会被守卫拦下的模式
        if len(low) == 1:
            single_letter.append(low)
            if row["mode"] not in UNGUARDED_MODES:
                fail(
                    f"{tag} 单字母 trigger {low!r} 用了 {row['mode']!r} 模式 —— "
                    f"在「单字母不触发快捷规则」开启（默认）时会被 minTriggerLength=2 拦下，"
                    f"该条静默失效；请改成 exact（或 regex）"
                )

        # E. mode / weight / kind
        if row["mode"] not in VALID_MODES:
            fail(f"{tag} mode 非法: {row['mode']!r}（应为 {sorted(VALID_MODES)}）")
        if not re.fullmatch(r"-?\d+", row["weight"]):
            fail(f"{tag} weight 非整数: {row['weight']!r}")
        if row["kind"] and not row["kind"].startswith(("p", "a")):
            fail(f"{tag} kind 非法: {row['kind']!r}（应为 p / a，留空则按 content==label 推断）")

        # F. (trigger, content) 唯一
        pair = (low, row["content"])
        if pair in seen_pair:
            fail(
                f"{tag} 同一 (trigger, content) 重复：{low!r} + {row['content']!r}"
                f"（已在第 {seen_pair[pair]} 行定义）"
            )
        else:
            seen_pair[pair] = row["line"]

    if single_letter:
        warn(
            "存在单字母简写 "
            + ", ".join(sorted(set(single_letter)))
            + " —— 这些键会抢在五笔一级简码之前（短语 QUICKAPPLY 档位高于五笔），属预期行为"
        )
    if rows:
        ok(f"解析 {len(rows)} 条规则，字段 / 形态 / 唯一性检查完成")
    else:
        warn("规则表为空（只有注释）")


def check_source_contract() -> None:
    """G. 源码侧的排序契约：短语能压过五笔，且守卫仍生效。"""
    if LOOKUP_CANDIDATE.exists():
        text = LOOKUP_CANDIDATE.read_text(encoding="utf-8")
        orders = {
            name: int(value)
            for name, value in re.findall(r"(WUBI|WUBIPHRASE|PHRASE|QUICKAPPLY|PINYIN)\s*->\s*(\d+)", text)
        }
        if {"QUICKAPPLY", "PHRASE", "WUBI"} <= orders.keys():
            if orders["QUICKAPPLY"] < orders["WUBI"]:
                ok("LookupKind.order：QUICKAPPLY 先于 WUBI（短语能排到五笔之前）")
            else:
                fail(
                    f"LookupKind.order 被改坏：QUICKAPPLY={orders['QUICKAPPLY']} "
                    f"不再小于 WUBI={orders['WUBI']}，简写命中后短语排不到第一"
                )
            if not (orders["QUICKAPPLY"] <= orders["PHRASE"]):
                fail("LookupKind.order：PHRASE 不应先于 QUICKAPPLY（应用档位应最高）")
        else:
            fail("LookupCandidate.kt 中未解析到 LookupKind.order 的档位映射")
    else:
        fail(f"文件不存在：{LOOKUP_CANDIDATE}")

    if LOOKUP_ENGINE.exists():
        text = LOOKUP_ENGINE.read_text(encoding="utf-8")
        if "code.equals(input" in text and "ignoreCase" in text:
            ok("CandidateRanking：精确命中触发码的候选优先（输入完整简写时排最前）")
        else:
            fail("CandidateRanking 的「精确码优先」判定被移除，简写排第一不再有保证")
    else:
        fail(f"文件不存在：{LOOKUP_ENGINE}")

    if QUICK_ENGINE.exists():
        text = QUICK_ENGINE.read_text(encoding="utf-8")
        if "needle.length < minTriggerLength" in text:
            ok("QuickPhraseEngine：prefix/contains 的 minTriggerLength 守卫仍在（单字母回退给五笔简码）")
        else:
            fail(
                "QuickPhraseEngine 的 minTriggerLength 守卫被移除 —— "
                "单字母 prefix 规则会误触；若是有意改动，请同步更新本脚本的 D 项"
            )
        if "minTriggerLength: Int = 2" in text:
            ok("QuickPhraseEngine：minTriggerLength 默认仍为 2")
        else:
            warn("QuickPhraseEngine.minTriggerLength 默认值不是 2 —— 请确认与「单字母不触发」默认开关一致")
    else:
        fail(f"文件不存在：{QUICK_ENGINE}")


def check_user_trigger_chain() -> None:
    """H. 用户自定义快捷字母全链路：设置页 → 输入法面板 → 保存即生效。"""
    # 中英文案：字段改叫「快捷字母」，并提供示例与校验提示
    for path, value in ((STRINGS_ZH, "快捷字母"), (STRINGS_EN, "Shortcut letters")):
        if not path.exists():
            fail(f"文件不存在：{path}")
            continue
        text = path.read_text(encoding="utf-8")
        if re.search(rf'<string name="quick_trigger">{re.escape(value)}</string>', text):
            ok(f"{rel(path)}：quick_trigger 文案为「{value}」")
        else:
            fail(f"{rel(path)}：quick_trigger 文案不是「{value}」（用户看不懂「触发词」）")
    zh = STRINGS_ZH.read_text(encoding="utf-8") if STRINGS_ZH.exists() else ""
    en = STRINGS_EN.read_text(encoding="utf-8") if STRINGS_EN.exists() else ""
    for key in ("quick_trigger_hint", "quick_trigger_error"):
        if f'name="{key}"' in zh and f'name="{key}"' in en:
            ok(f"中英文案均提供 {key}")
        else:
            fail(f"缺少中英文案 {key}（zh={key in zh} en={key in en}）")

    # 设置页：2–4 位小写字母校验，未通过时确认按钮禁用
    screen = read(QUICK_SCREEN) if QUICK_SCREEN.exists() else ""
    if not screen:
        fail(f"文件不存在：{QUICK_SCREEN}")
    else:
        if "isValidShortcutTrigger" in screen and "in 2..4" in screen and "'a'..'z'" in screen:
            ok("QuickPhrasesScreen：快捷字母校验为 2–4 位小写字母")
        else:
            fail("QuickPhrasesScreen 未实现 2–4 位小写字母校验（超出 IME 组合缓冲区上限会打不出来）")
        if "triggerValid && editor.content.isNotBlank()" in screen:
            ok("QuickPhrasesScreen：快捷字母非法时确认按钮禁用")
        else:
            fail("QuickPhrasesScreen 的确认按钮未使用 triggerValid 校验")
        if "quick_trigger_hint" in screen and "quick_trigger_error" in screen:
            ok("QuickPhrasesScreen：快捷字母字段带示例与错误提示")
        else:
            fail("QuickPhrasesScreen 快捷字母字段缺少示例 / 错误提示")
        if "filter { it in 'a'..'z' }" in screen:
            ok("QuickPhrasesScreen：输入时自动转小写并过滤非法字符")
        else:
            fail("QuickPhrasesScreen 未过滤快捷字母非法字符")

    # 输入法面板：每行展示快捷字母角标
    panel = read(QUICK_PANEL) if QUICK_PANEL.exists() else ""
    if "createTagView(entry.trigger)" in panel:
        ok("QuickPhrasePanel：行内展示快捷字母角标（沿用已有 tag 样式）")
    else:
        fail("QuickPhrasePanel 行内看不到快捷字母，用户无法把短语和简写对应起来")

    # 保存即生效：写盘后重建引擎
    vm = read(QUICK_VM) if QUICK_VM.exists() else ""
    if "AppContainer.invalidateEngine()" in vm:
        ok("QuickPhrasesViewModel.save：写盘后 invalidateEngine，无需重启输入法")
    else:
        fail("QuickPhrasesViewModel.save 后未 invalidateEngine，新快捷字母不会立即生效")

    # 引擎：精确命中不能被扫描上限截断
    engine = read(QUICK_ENGINE) if QUICK_ENGINE.exists() else ""
    if "val exact = entry.trigger.equals(needle, ignoreCase = true)" in engine and \
            "if (!exact && out.size" in engine:
        ok("QuickPhraseEngine：精确命中不受扫描上限截断，始终参与排序")
    else:
        fail("QuickPhraseEngine 的扫描上限会截断精确命中，大规则表下简写可能排不到第一")


_KIND_ORDER_FALLBACK = {"QUICKAPPLY": 0, "PHRASE": 1, "WUBI": 2, "WUBIPHRASE": 3, "PINYIN": 4}


def _kind_order_map() -> dict[str, int]:
    """从 LookupCandidate.kt 读取真实档位顺序，读不到时退回参考值。"""
    text = read(LOOKUP_CANDIDATE) if LOOKUP_CANDIDATE.exists() else ""
    found = {
        name: int(value)
        for name, value in re.findall(
            r"(WUBI|WUBIPHRASE|PHRASE|QUICKAPPLY|PINYIN)\s*->\s*(\d+)", text
        )
    }
    return {**_KIND_ORDER_FALLBACK, **found}


def check_exact_trigger_ranking() -> None:
    """I. 需求场景验收：输入 ycsf，快捷短语必须是候选第 1 位（与 Kotlin 排序同构）。"""
    order = _kind_order_map()

    def rank(candidate: dict) -> tuple:
        return (
            0 if candidate["code"].lower() == "ycsf" else 1,
            order.get(candidate["kind"], 9),
            -candidate["weight"],
            candidate["display"],
        )

    pool = [
        {"code": "ycsf", "display": "游泳池换水要收费的", "kind": "QUICKAPPLY", "weight": 100},
        {"code": "ycsf", "display": "同码五笔候选", "kind": "WUBI", "weight": 99999},
        {"code": "yc", "display": "前缀短语", "kind": "QUICKAPPLY", "weight": 999},
    ]
    got = [c["display"] for c in sorted(pool, key=rank)]
    if got[0] == "游泳池换水要收费的":
        ok(f"输入 ycsf：快捷短语排在候选首位（实际顺序={got}）")
    else:
        fail(f"输入 ycsf：快捷短语未排首位（实际顺序={got}）")


def check_remote_shortcut_chain() -> None:
    """J. 远端规则的查看与快捷字母覆盖（列表 -> 用户文件 -> 排序 -> 恢复默认）。"""
    vm = read(QUICK_VM)
    screen = read(QUICK_SCREEN)
    entry = read(QUICK_ENTRY)
    container = read(APP_CONTAINER)
    sync = read(SYNC_REPO)
    ranking = read(LOOKUP_ENGINE)
    composite = read(COMPOSITE_ENGINE)
    doc = read(DOC)

    # J1. 远端规则进入列表数据源
    check(
        "fun quickPhrasesRemoteFile(" in container and "QuickLocator.REMOTE_FILE_NAME" in container,
        "AppContainer 暴露 quickPhrasesRemoteFile（指向 quick_phrases_remote.txt）",
        "AppContainer 未提供远端规则文件定位，设置页拿不到远端规则",
    )
    check(
        "AppContainer.quickPhrasesRemoteFile(app)" in vm
        and "QuickSource.REMOTE" in vm
        and "mergeForDisplay(" in vm
        and "entries = merged.entries" in vm,
        "QuickPhrasesViewModel 读取远端规则、标记来源并与用户规则一起搜索",
        "QuickPhrasesViewModel 未把远端规则并入列表搜索",
    )

    # J2. 覆盖条目写入用户文件（远端文件只读）
    check(
        "fun setRemoteShortcut(" in vm
        and "REMOTE_OVERRIDE_WEIGHT_BONUS = 1000" in vm
        and "original.weight + REMOTE_OVERRIDE_WEIGHT_BONUS" in vm,
        "覆盖条目 weight = 远端 weight + 1000（候选排序压过原远端条目）",
        "覆盖条目权重未高于远端规则，排序可能输给原条目",
    )
    check(
        "QuickPhraseCodec.format(entries)" in vm and "QuickOverride.matches(it, original)" in vm,
        "覆盖条目与普通用户规则一样由 save() 写入 quick_phrases_user.txt",
        "覆盖条目未落用户文件或未与远端规则建立覆盖关系",
    )
    check(
        "object QuickOverride" in entry and "fun matchesAny(" in entry,
        "QuickOverride 统一「label + content 相同即覆盖」的判定口径",
        "QuickEntry.kt 缺少 QuickOverride 覆盖判定",
    )

    # J3. 排序胜出：精确码 -> kind -> weight 降序 -> display 去重
    check(
        "b.weight.compareTo(a.weight)" in ranking and "seen.add(candidate.display)" in composite,
        "CandidateRanking 权重降序 + CompositeEngine 按 display 去重（先入者胜）",
        "排序 / 去重契约被改坏，覆盖条目可能被原远端条目挤掉",
    )

    # J4. UI：来源角标 / 已覆盖提示 / 恢复默认
    check(
        "rule.source" in screen and "R.string.quick_remote_overridden" in screen
        and "R.string.quick_remote_restore" in screen and "RemoteShortcutEditorDialog" in screen,
        "QuickPhrasesScreen：卡片显示来源、已覆盖为 xxx、设置快捷字母与恢复默认",
        "设置页缺少远端来源 / 覆盖提示 / 恢复默认入口",
    )
    check(
        "fun removeRemoteOverride(" in vm
        and "filterNot { QuickOverride.matches(it, original) }" in vm,
        "removeRemoteOverride 删除覆盖条目（恢复远端默认快捷字母）",
        "恢复默认未删除用户文件中的覆盖条目",
    )

    # J5. 覆盖条目是本地别名，不应作为新规则推送
    check(
        "QuickOverride.matchesAny(entry, remoteEntries)" in sync and "fun readRemoteEntries(" in sync,
        "SyncRepository.pushLocalRules 跳过覆盖条目（云端不产生同内容重复规则）",
        "覆盖条目会被当成新规则推送，云端出现同内容重复规则",
    )

    # J6. 中英文案
    for path in (STRINGS_ZH, STRINGS_EN):
        text = read(path) if path.exists() else ""
        missing = [
            key for key in (
                "quick_remote_overridden",
                "quick_remote_restore",
                "quick_remote_shortcut_title",
            )
            if f'name="{key}"' not in text
        ]
        check(
            not missing,
            f"{rel(path)}：远端快捷字母文案齐全",
            f"{rel(path)} 缺远端快捷字母文案：{missing}",
        )

    # J7. 文档
    check(
        "### 12.5" in doc and "快捷字母覆盖" in doc,
        "docs/quick-phrase-enhancement.md 补充 12.5「远端规则的查看与快捷字母覆盖」",
        "文档未说明远端规则查看 / 覆盖的数据落点与恢复默认行为",
    )


def check_pinyin_state_quick_chain() -> None:
    """K. 拼音态（输入法默认态）必须挂快捷短语引擎。

    缺陷回归：pinyinLookupEngine 曾经只有用户词库 + 拼音词表，导致用户设置的
    4 位快捷字母（如 ytyc → 雨天泳池）在默认拼音态下一个候选都没有。
    """
    container = read(APP_CONTAINER) if APP_CONTAINER.exists() else ""
    marker = "fun pinyinLookupEngine("
    if marker not in container:
        fail("AppContainer 中未找到 pinyinLookupEngine —— 拼音态候选来源缺失")
        return
    body = container.split(marker, 1)[1]
    end = body.find("\n    /**", 10)
    chunk = body if end == -1 else body[:end]
    check(
        "QuickLocator.buildQuickEngines(" in chunk and "includeLocal = enableQuick" in chunk,
        "拼音态 pinyinLookupEngine 也挂载快捷短语引擎（默认态下快捷字母可命中）",
        "拼音态 pinyinLookupEngine 未挂载快捷短语引擎，快捷字母在默认拼音态会静默失效",
    )

    locator = read(QUICK_LOCATOR) if QUICK_LOCATOR.exists() else ""
    composite_chunk = locator.split("fun buildComposite", 1)[1] if "fun buildComposite" in locator else ""
    check(
        "fun buildQuickEngines(" in locator and "buildQuickEngines(" in composite_chunk,
        "QuickLocator.buildQuickEngines 同时被五笔态 buildComposite 复用（两态规则一致）",
        "快捷规则构建未抽成 buildQuickEngines 或五笔态未复用",
    )


def main() -> int:
    print("=" * 62)
    print("快捷短语简写（触发码）规则校验")
    print("=" * 62)
    check_rows(parse_phrases())
    check_source_contract()
    check_user_trigger_chain()
    check_exact_trigger_ranking()
    check_remote_shortcut_chain()
    check_pinyin_state_quick_chain()
    check_dtwsl_phrase()
    check_space_long_press_triggers_voice()
    check_ai_key_shows_quick_phrases_first()
    check_ai_key_falls_back_to_llm_when_no_match()
    print("-" * 62)
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


def check_space_long_press_triggers_voice() -> None:
    """空格键按住 ≥1s 应触发语音输入（软键盘侧手势 + 服务侧接线）。"""
    keyboard = (ROOT / "app/src/main/java/com/ispeech/input/SoftKeyboardView.kt").read_text()
    service = (ROOT / "app/src/main/java/com/ispeech/input/IspeechImeService.kt").read_text()
    check(
        "DEFAULT_SPACE_LONG_PRESS_MS = 500L" in keyboard
        and "var spaceLongPressMs" in keyboard,
        "空格键长按：SoftKeyboardView 默认 500ms，阈值由服务侧按设置推入",
        "未找到 DEFAULT_SPACE_LONG_PRESS_MS = 500L 或 spaceLongPressMs 属性",
    )
    check(
        "voiceSpaceLongPressMs" in service,
        "空格键长按：服务侧从 SettingsStore 读取阈值并推给键盘",
        "IspeechImeService 未读取 voiceSpaceLongPressMs",
    )
    check(
        "private fun installSpaceGesture" in keyboard
        and "KeyAction.SPACE" in keyboard
        and "onSpaceLongPress?.invoke()" in keyboard,
        "空格键长按：SoftKeyboardView 为 SPACE 键安装长按手势",
        "未找到 installSpaceGesture 或未对 KeyAction.SPACE 生效",
    )
    check(
        "onKey?.invoke(key)" in keyboard,
        "空格键短按：抬手仍派发 onKey 上屏空格",
        "短按分支丢失，空格键会变成死键",
    )
    check(
        "keyboard.onSpaceLongPress = { onVoicePressDown() }" in service
        and "keyboard.onSpaceLongPressEnd = { onVoicePressUp() }" in service,
        "空格键长按：服务侧接到语音按下 / 抬起语义",
        "服务侧未接线 onSpaceLongPress / onSpaceLongPressEnd",
    )


def check_ai_key_shows_quick_phrases_first() -> None:
    """AI 键应先匹配快捷短语，命中即展示、不再调后端。"""
    text = (ROOT / "app/src/main/java/com/ispeech/input/IspeechImeService.kt").read_text()
    check(
        "private suspend fun matchQuickPhrases" in text
        and "QuickLocator.loadAll(" in text,
        "AI按键：startAiReply 走 matchQuickPhrases（QuickLocator.loadAll 真实 API）",
        "未找到 matchQuickPhrases / QuickLocator.loadAll 调用",
    )
    check(
        "val phrases = matchQuickPhrases(message)" in text
        and "if (phrases.isNotEmpty())" in text,
        "AI按键：先取快捷短语匹配结果并短路",
        "未找到 phrases 优先匹配与短路分支",
    )
    call_pos = text.find("val phrases = matchQuickPhrases(message)")
    llm_pos = text.find(".generateReply(auth, message)")
    check(
        call_pos != -1 and llm_pos != -1 and call_pos < llm_pos,
        "AI按键：快捷短语匹配发生在 LLM 调用之前",
        "短语匹配未前置于 generateReply",
    )
    check(
        "withContext(Dispatchers.IO)" in text.split("private suspend fun matchQuickPhrases")[1][:2000],
        "AI按键：规则加载在 IO 线程，不阻塞主线程",
        "matchQuickPhrases 未切到 Dispatchers.IO（主线程建库会 ANR）",
    )


def check_ai_key_falls_back_to_llm_when_no_match() -> None:
    """AI 键在无快捷短语匹配时应回退到 LLM 生成。"""
    text = (ROOT / "app/src/main/java/com/ispeech/input/IspeechImeService.kt").read_text()
    check(
        ".generateReply(auth, message)" in text and "CsBabyAuthStore.load(" in text,
        "AI按键：无短语命中时回退 csBabyClient.generateReply",
        "未找到 LLM 回退链路",
    )
    check(
        'codeOverride == "快捷短语"' in text and "commitAiReply(index: Int = 0)" in text,
        "AI按键：多条建议可逐条上屏，且快捷短语标记可被清理",
        "未找到多候选上屏或快捷短语状态清理",
    )


def _unescape_rule_text(text: str) -> str:
    """复刻 UserDictStore.unescapeLineText：\\n / \\r / \\t / \\\\ 还原，未知转义原样保留。"""
    if "\\" not in text:
        return text
    out: list[str] = []
    i = 0
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else None
        if ch == "\\" and nxt is not None:
            mapped = {"n": "\n", "r": "\r", "t": "\t", "\\": "\\"}.get(nxt)
            if mapped is not None:
                out.append(mapped)
                i += 2
                continue
            out.append(ch)
            i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _parse_rule_rows(path: Path) -> list[dict]:
    """解析规则文件为引擎口径的条目（content 已反转义，kind 已归一），与 QuickPhraseEngine 同构。"""
    rows: list[dict] = []
    if not path.exists():
        return rows
    for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = raw.split("\t")
        if len(fields) < 3:
            continue
        trigger = fields[0].strip()
        content = _unescape_rule_text(fields[2].strip())
        if not trigger or not content:
            continue
        label = fields[1].strip() or content
        if len(fields) > 5:
            kind = "QUICKAPPLY" if fields[5].strip().lower().startswith("a") else "PHRASE"
        else:
            kind = "PHRASE" if content == label else "QUICKAPPLY"
        try:
            weight = int(fields[4].strip()) if len(fields) > 4 else 100
        except ValueError:
            weight = 100
        rows.append(
            {
                "line": lineno,
                "trigger": trigger,
                "label": label,
                "content": content,
                "mode": fields[3].strip().lower() if len(fields) > 3 else "prefix",
                "weight": weight,
                "kind": kind,
            }
        )
    return rows


def _quick_lookup(rows: list[dict], needle: str, limit: int = 10, min_trigger_length: int = 2) -> list[dict]:
    """复刻 QuickPhraseEngine.lookup + CandidateRanking.comparator（regex 规则本仓库资产未使用）。"""
    order = _kind_order_map()
    needle = needle.lower()
    if not needle or limit <= 0:
        return []
    out: list[dict] = []
    scan_cap = max(limit, 64)
    for row in rows:
        trigger = row["trigger"].lower()
        mode = row["mode"]
        if mode in ("prefix", "contains") and len(needle) < min_trigger_length:
            continue
        if mode == "exact":
            hit = trigger == needle
        elif mode == "prefix":
            hit = trigger.startswith(needle) or needle.startswith(trigger)
        elif mode == "contains":
            hit = trigger in needle or needle in trigger
        else:
            hit = False
        if not hit:
            continue
        exact = trigger == needle
        if not exact and len(out) >= scan_cap:
            continue
        out.append(
            {
                "code": row["trigger"],
                "display": row["label"],
                "insertText": row["content"],
                "weight": row["weight"],
                "kind": row["kind"],
            }
        )
    out.sort(
        key=lambda c: (
            0 if c["code"].lower() == needle else 1,
            order.get(c["kind"], 9),
            -c["weight"],
            c["display"],
        )
    )
    return out[:limit]


def _ime_buffer_limits() -> tuple[int, int]:
    """从 IspeechImeService 读组合缓冲区上限：五笔态 / 拼音态。"""
    text = read(IME_SERVICE) if IME_SERVICE.exists() else ""
    code = re.search(r"MAX_CODE_LENGTH\s*=\s*(\d+)", text)
    pinyin = re.search(r"MAX_PINYIN_LENGTH\s*=\s*(\d+)", text)
    return (int(code.group(1)) if code else 4), (int(pinyin.group(1)) if pinyin else 12)


def check_dtwsl_phrase() -> None:
    """L. 内置 5 位触发码 dtwsl（泡池 / 充气城堡 / 泳池收费说明）。

    验收点：正文逐字一致（含换行 / emoji / 全半角括号 / `--` / 数字）、exact 命中排首位、
    两种输入态的缓冲区行为与文档一致、upsert 工具重复执行不增长条目。
    """
    # L1. 正文基准：尽情复制会丢空格 / 吞字符，所以正文以仓库内源文件为准逐字比对
    if not DTWSL_SOURCE.exists():
        fail(f"缺少正文源文件：{rel(DTWSL_SOURCE)}")
        return
    raw = read(DTWSL_SOURCE)
    golden = raw[:-1] if raw.endswith("\n") else raw
    digest = hashlib.sha256(golden.encode("utf-8")).hexdigest()[:12]
    ok(
        f"{rel(DTWSL_SOURCE)}：{len(golden.splitlines())} 行 / {len(golden)} 字，"
        f"UTF-16 {len(golden.encode('utf-16-le')) // 2} 单元，sha256={digest}"
    )

    # L2. 规则表里同 trigger 只能有一条（用户文件 / 内置资产不得重复）
    asset_rows = _parse_rule_rows(PHRASES)
    hits = [row for row in asset_rows if row["trigger"].lower() == DTWSL_TRIGGER]
    check(
        len(hits) == 1,
        f"quick_phrases.txt：{DTWSL_TRIGGER} 唯一一条，总数不重复",
        f"quick_phrases.txt 里 {DTWSL_TRIGGER} 出现 {len(hits)} 次（应恰好 1 条）",
    )
    if len(hits) != 1:
        return
    row = hits[0]

    sogou_hits = [r for r in _parse_rule_rows(SOGOU_PHRASES) if r["trigger"].lower() == DTWSL_TRIGGER]
    check(
        not sogou_hits,
        f"sogou_phrases.txt：没有同 trigger 的 {DTWSL_TRIGGER}（跨文件不重复）",
        f"sogou_phrases.txt 也定义了 {DTWSL_TRIGGER}，两个文件都会进候选，应删除其一",
    )

    # L3. 字段口径：label / exact / 应用档位 / 权重
    check(
        row["label"] == DTWSL_LABEL,
        f"label = {DTWSL_LABEL}（4 字，候选条放得下；覆盖面见文档 12.6）",
        f"label 应为 {DTWSL_LABEL}（还实际为 {row['label']!r}）",
    )
    check(
        row["mode"] == "exact",
        "mode = exact：整码命中，打字中途不会弹长文本",
        f"mode 应为 exact（实际 {row['mode']!r}）—— exact 命中才能拿到候选首位",
    )
    check(
        row["kind"] == "QUICKAPPLY",
        "kind = a（应用档位）：命中后排在五笔 / 拼音候选之前",
        f"kind 应为 a / QUICKAPPLY（实际 {row['kind']}）",
    )
    check(
        row["weight"] >= 100,
        f"weight = {row['weight']}（不输给既有 100 权重的内置条目）",
        f"weight 过低（{row['weight']}）：同码排序时会输给默认权重条目",
    )

    # L4. 正文逐字一致
    same = row["content"] == golden
    check(
        same,
        f"正文逐字一致：{len(row['content'])} 字 / {len(row['content'].splitlines())} 行、sha256={hashlib.sha256(row['content'].encode('utf-8')).hexdigest()[:12]}",
        "正文与源文件不一致（换行 / emoji / 全半角括号 / 空格有任何差异都会在这里暴露）",
    )
    if not same:
        return

    # L5. 正文结构：首行不是触发码、空行位置、首尾无多余空白
    lines = row["content"].split("\n")
    check(
        lines[0].startswith("🌹周六及节假日（不含末日）"),
        "首行从 🌹 开始：触发码 dtwsl 与分隔空格没有混进上屏内容",
        f"首行不以 🌹 开头：{lines[0][:20]!r}（触发码或分隔空格被当成正文了）",
    )
    check(
        [i for i, line in enumerate(lines) if line == ""] == [1, 8] and len(lines) == 11,
        "正文 11 行，空行正好在第 2 / 第 9 行（段落结构未被转义丢失）",
        f"正文行结构不对：{len(lines)} 行，空行下标 {[i for i, line in enumerate(lines) if line == '']}",
    )
    check(
        row["content"] == row["content"].strip(),
        "正文首尾无多余空白（上屏不会多出空格 / 空行）",
        "正文首尾没有多余空白",
    )

    # L6. 关键片段：金额 / 时长 / 【】 / -- / 全角标点 / emoji
    must = (
        "🌹周六及节假日（不含末日）",
        "泡池（仅限晴天）",
        "充气城堡（4小时）二选一免费1次",
        "（使用请提前1天预约，否则不可用）",
        "其它日期按需收费；",
        "【恒温泡池】付费项目默认不使用",
        "💰。恒温冲浪水疗泡池按需收费【不用无相关费用】",
        "--平台“须知”有详细 说明：",
        "150元/池水",
        "恒温功能开2小时+余温可用约4小时",
        "1、150元/池水",
        "2、若用请提前一天说明",
        "免费送1份中药",
        "瑶浴",
        "缓解疲劳🥰",
        "充气城堡：首2小时100元，之后50元/2小时",
        "【泳池】",
        "该季节泳池暂不开放",
    )
    missing = [token for token in must if token not in row["content"]]
    check(
        not missing,
        f"关键片段全部保留（{len(must)} 项：金额 / 时长 / 【】/ -- / 全角标点 / emoji）",
        f"正文少了这些片段：{missing}",
    )
    check(
        row["content"].count(" ") == 1 and row["content"].count("  ") == 0,
        "空格数量与原文一致：全文只有「有详细 说明」这 1 个半角空格，没有被吞 / 被补",
        f"空格数量不对：{row['content'].count(' ')} 个（原文 1 个）",
    )

    # L7. 落盘形态：一条规则一行，正文里不能有真实换行 / tab
    escaped_line = f"{DTWSL_TRIGGER}\t{DTWSL_LABEL}\t"
    raw_lines = [line for line in read(PHRASES).split("\n") if line.startswith(escaped_line)]
    check(
        len(raw_lines) == 1
        and "\n" not in raw_lines[0].replace("\\n", "")
        and "\t" not in raw_lines[0].split("\t")[2],
        "落盘为单行：正文换行已转义为字面量 \\n，没有把一条规则拆成多行",
        "落盘形态不对：正文里有真实换行 / tab，会把后续规则全部解析错位",
    )

    # L8. 候选命中：拼音态（默认）5 位完整累积 -> 精确命中排首位
    code_limit, pinyin_limit = _ime_buffer_limits()
    all_rows = asset_rows + _parse_rule_rows(SOGOU_PHRASES)
    buffer = ""
    for ch in DTWSL_TRIGGER:
        if len(buffer) >= pinyin_limit:
            buffer = ""
        buffer += ch
    pinyin_hits = _quick_lookup(all_rows, buffer)
    check(
        buffer == DTWSL_TRIGGER
        and bool(pinyin_hits)
        and pinyin_hits[0]["display"] == DTWSL_LABEL
        and pinyin_hits[0]["insertText"] == golden,
        f"拼音态（缓冲区 {pinyin_limit} 位）：输入 {DTWSL_TRIGGER} → 候选首位「{DTWSL_LABEL}」，上屏文本与源文件一致",
        f"拼音态输入 {DTWSL_TRIGGER} 未命中或未排首位：{[c['display'] for c in pinyin_hits[:3]]}",
    )

    partial_hits = _quick_lookup(all_rows, DTWSL_TRIGGER[:-1])
    check(
        all(hit["display"] != DTWSL_LABEL for hit in partial_hits),
        f"exact 模式：输入 {DTWSL_TRIGGER[:-1]}（少 1 位）不命中 —— 最短命中长度 = 整码 {len(DTWSL_TRIGGER)} 位",
        f"输入 {DTWSL_TRIGGER[:-1]} 就弹出了「{DTWSL_LABEL}」：exact 语义被改坏（打字中途会误弹长文本）",
    )

    # L9. 五笔态：缓冲区 4 位，第 5 个字母先上屏当前首选 -> 5 位码打不全（文档必须写明）
    wubi_buffer = ""
    for ch in DTWSL_TRIGGER:
        if len(wubi_buffer) >= code_limit:
            wubi_buffer = ""
        wubi_buffer += ch
    check(
        wubi_buffer != DTWSL_TRIGGER,
        f"五笔态（缓冲区 {code_limit} 位）：{DTWSL_TRIGGER} 打不全，需在拼音态输入（与文档 12.6 一致）",
        f"五笔态缓冲区上限变了（{code_limit} 位）：{DTWSL_TRIGGER} 现在能完整敲出，请同步文档 12.6 的说明",
    )

    # L10. 快捷短语面板：关键词搜索也包含触发码，面板里输入 dtwsl 同样能筛到
    search = read(PHRASE_SEARCH) if PHRASE_SEARCH.exists() else ""
    check(
        "PhraseHitField.TRIGGER" in search and "SCORE_FIELD_TRIGGER" in search,
        "PhraseSearch：触发码是搜索字段（面板输入 dtwsl 也能筛到）",
        "PhraseSearch 不再搜索触发码字段，面板里输入 dtwsl 会筛不到",
    )

    # L11. 写入器幂等：重复执行不新增、同 trigger 换内容原地更新、重复行被清理
    module = None
    if not UPSERT_TOOL.exists():
        fail(f"缺少写入器：{rel(UPSERT_TOOL)}")
    else:
        try:
            spec = importlib.util.spec_from_file_location("upsert_quick_phrase", UPSERT_TOOL)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)  # type: ignore[union-attr]
        except Exception as exc:  # noqa: BLE001 - 校验脚本只报告，不抛出
            fail(f"无法加载 {rel(UPSERT_TOOL)}：{exc}")
    if module is not None:
        tool_row = module.build_row(DTWSL_TRIGGER, DTWSL_LABEL, golden, "exact", 110, "a")
        asset_text = read(PHRASES)
        once, action, dupes = module.upsert_row(asset_text, DTWSL_TRIGGER, tool_row)
        twice, action2, _ = module.upsert_row(once, DTWSL_TRIGGER, tool_row)
        check(
            action == "unchanged" and dupes == 0 and once == asset_text and twice == once and action2 == "unchanged",
            "写入器：对当前规则表重复执行是 no-op（字节一致、无重复条目）",
            f"写入器对当前规则表不是幂等的：action={action} dupes={dupes} changed={once != asset_text}",
        )

        header = "\n".join(line for line in asset_text.split("\n") if line.startswith("#"))
        first, first_action, _ = module.upsert_row(header, DTWSL_TRIGGER, tool_row)
        second, second_action, _ = module.upsert_row(first, DTWSL_TRIGGER, tool_row)
        check(
            first_action == "inserted"
            and second_action == "unchanged"
            and second == first
            and module.count_rows(first) == 1
            and module.count_rows(second) == 1,
            "写入器：空表上连续执行两次，只新增 1 条（条目总数不增长）",
            "写入器重复执行会新增第二条同 trigger 规则",
        )

        changed_row = module.build_row(DTWSL_TRIGGER, DTWSL_LABEL, golden + "。", "exact", 110, "a")
        updated, update_action, _ = module.upsert_row(first, DTWSL_TRIGGER, changed_row)
        check(
            update_action == "updated" and module.count_rows(updated) == 1,
            "写入器：同 trigger 换正文时原地更新（不新增行）",
            "写入器把同 trigger 的新正文追加成了第二条规则",
        )

        duplicated = first + tool_row + "\n"
        cleaned, _, removed = module.upsert_row(duplicated, DTWSL_TRIGGER, tool_row)
        check(
            removed == 1 and module.count_rows(cleaned) == 1,
            "写入器：已存在的重复同 trigger 行会被清理（只留 1 条）",
            f"写入器没有清理重复行（removed={removed}，剩余 {module.count_rows(cleaned)} 条）",
        )

    # L12. 文档：5 位码在两种输入态的行为差异必须写明
    doc = read(DOC) if DOC.exists() else ""
    check(
        "### 12.6" in doc and DTWSL_TRIGGER in doc and "MAX_CODE_LENGTH" in doc,
        "docs/quick-phrase-enhancement.md 12.6：记录 dtwsl 落点、转义、两种输入态缓冲区差异",
        "文档缺少 12.6（5 位触发码 / dtwsl）说明：后来者会踩同一个缓冲区坑",
    )


if __name__ == "__main__":
    sys.exit(main())