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
    B. trigger 只能是小写 a-z；超过 4 位（IME 组合缓冲区 MAX_CODE_LENGTH）时告警
       —— 完整码永远打不出来，只能靠 prefix 命中，拿不到「精确码优先」的首位加成；
    C. trigger 全局唯一（大小写不敏感）；
    D. **1 字母 trigger 必须是 exact / regex 模式**（否则静默失效）；
    E. weight 为整数、kind 为 p/a、mode 为四种之一；
    F. 无重复的 (trigger, content)；
    G. 源码侧的排序契约未被改坏（QUICKAPPLY 在 WUBI 之前 + 精确码优先 + 守卫存在）；
    H. 用户自定义快捷字母全链路：设置页 2–4 位小写校验 / 中英文案 / 面板角标 / 保存即生效；
    I. 需求场景验收：输入 ``ycsf`` 时快捷短语排在候选第 1 位（与 Kotlin 排序同构）；
    J. 远端规则的查看与快捷字母覆盖：远端进列表 / 覆盖写用户文件 / 排序胜出 / 恢复默认。

用法：``python3 tools/verify/check_quick_trigger.py``
"""

from __future__ import annotations

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
        if len(trig) > 4:
            warn(
                f"{tag} trigger {trig!r} 超过 4 位 —— IME 组合缓冲区上限 MAX_CODE_LENGTH=4，"
                f"完整码打不出来，只能靠 prefix 命中，拿不到「精确码优先」的首位加成；建议缩到 ≤4 位"
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


if __name__ == "__main__":
    sys.exit(main())