#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_voca310_gate.py —— 门禁的门禁：8.2b 采集口径的变异对照 + 措辞一致性

`check_crosscut.py` 8.2b 声称「点了语音键没进识别中，一定留下可判归类」。这句声称有两半，
历来只靠人肉在临时副本里造变异验证（VOCA-310 打回项 2 / 4、条件项 C1 / C1′、质检需澄清 ③），
换个人跑就漂移：条数对不上、标签读法不唯一、口径文案越写越宽。本脚本把那些验证钉成代码。

A. 变异对照表（ORACLE）：每只变异 = 一处「哪个文件 + 哪句原文 + 改成什么」的可判文字。
   打上变异跑副本里的 `check_crosscut.py`，核对 **FAIL 条数**与**被点名的断言**。
   - `caught`：必须拦住（条数与断言名都对上才算）
   - `escaped`：实测走得出（该变异带来的新增 `failed=0`，且函数名没被任何断言点名），
     并把这一点写进口径文案
   条数变少 = 采集口径变松；条数变多 / 排除项变拦住 = 有人收紧了正则，排除句与覆盖清单随即陈旧。
   两个方向的漂移都由同一条命令发现，不需要再靠口头对照。

B. 措辞 ↔ 实测一致：三处覆盖面声明（门禁文档头 8.2b 段、出口集合断言自身的标题与 FAIL 文案、
   `docs/voice-input.md` §二 那段）必须与本表同集合：
   - 不许出现无限定全称：`无论…都 FAIL`；`不存在第三处未断言的出口` 必须写成
     「采集范围内 …（带限定名）」
   - 每个 `escaped` 写法必须在带「不在采集范围」的那句里被点名；每个 `caught` 写法必须出现在
     覆盖清单里、且不许出现在排除句里
   （VOCA-310 条件项 C1′：采集只覆盖写成 `AsrTrace.w/i/line(` 或 `Log.x(AsrTrace.TAG, …)` 的
   **带限定名**调用点；别名发射 N3、TAG 存变量 N2、TAG 写成字面串 N1 三种实测走得出。）

M-B 的两读法（VOCA-310 质检需澄清 ③）已拆成两行可判文字：`M-B` 特指改**常量取值**（2 条 FAIL），
`M-B2` 特指把**枚举调用点**写成裸串（1 条 FAIL）——条数不同是两只变异，不是同一只变异读得出两种结果。

本脚本只读源码、只写系统临时目录里的副本，不改仓库、不编译、不联网。探针（`qa*Exit`）只存在于
副本，仓库里没有任何 `Log.w("AsrTrace", …)` 之类的逃逸写法。

用法：
    python3 tools/verify/check_voca310_gate.py                  # 全跑（含 pristine 对照）
    python3 tools/verify/check_voca310_gate.py --list            # 只打对照表
    python3 tools/verify/check_voca310_gate.py --only M-B,M-B2   # 只跑指定几只
    python3 tools/verify/check_voca310_gate.py --keep            # 保留变异副本并打印路径
    python3 tools/verify/check_voca310_gate.py --emit DIR        # 8 只探针全打进 DIR 副本，
                                                                 # 给 gradle 编一遍证明编得过

输出与 `check_crosscut.py` 同格式：逐行 `PASS` / `FAIL` / `WARN`，末尾 `SUMMARY: ...`；
任一 FAIL 退出码 1。
"""

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE_REL = "tools/verify/check_crosscut.py"
GATE_SRC = ROOT / GATE_REL
DOCS_REL = "docs/voice-input.md"
# 只用于 WARN：passed 数变了说明 8.2b 增删过断言，不是本脚本的失败判据
CROSSCUT_BASE_PASSED = 171

SERVICE = "app/src/main/java/com/ispeech/input/IspeechImeService.kt"
CONTROLLER = "app/src/main/java/com/ispeech/input/VoiceInputController.kt"

# 副本只需要门禁会读到的东西：源码、资源、清单、被交叉引用的另外两只脚本、docs
COPY_ITEMS = ("app/src", "app/build.gradle.kts", "tools/verify", "docs", "build.gradle.kts",
              "settings.gradle.kts", "gradle.properties")
COPY_IGNORE = shutil.ignore_patterns("build", ".gradle", "__pycache__", "*.apk", ".DS_Store")

CAUGHT = "caught"
ESCAPED = "escaped"

# B 类断言的口径用词：覆盖清单用词 = caught 项的 claim，排除句用词 = escaped 项的 claim。
# 改口径文案必须同步改这里，两边不一致就是本脚本要拦的东西。
ORACLE = [
    # ── 拦得住的发射器写法 ──
    {
        "id": "K1", "kind": CAUGHT, "claim": "事件名写成裸串", "expect_failed": 2,
        "what": "新出口走封装、ev 直接写 \"blocked\"（打回项 4 的 M-Q）",
        "titles": ["第二个实参不是 AsrTrace.EV_* 常量引用", "ev=blocked 的实际出口"],
    },
    {
        "id": "K2", "kind": CAUGHT, "claim": "绕本地", "expect_failed": 1,
        "what": "新出口 ev 绕一道本地 val（M-Q2）",
        "titles": ["第二个实参不是 AsrTrace.EV_* 常量引用"],
    },
    {
        "id": "K3", "kind": CAUGHT, "claim": "点号折下一行", "expect_failed": 2,
        "what": "`AsrTrace` 换行 `.w(`，ev 写裸串（条件项 C1 写法二 / M-Q8）",
        "titles": ["第二个实参不是 AsrTrace.EV_* 常量引用", "ev=blocked 的实际出口"],
    },
    {
        "id": "K4", "kind": CAUGHT, "claim": "绕过封装", "expect_failed": 3,
        "what": "不走封装，直接 `Log.w(AsrTrace.TAG, AsrTrace.line(null, \"blocked\", …))`（C1 写法一 / M-Q4）",
        "titles": ["绕过 AsrTrace.w / i / line 直接拿 AsrTrace.TAG",
                   "第二个实参不是 AsrTrace.EV_* 常量引用", "ev=blocked 的实际出口"],
    },
    {
        "id": "K5", "kind": CAUGHT, "claim": "绕过封装", "expect_failed": 1,
        "what": "不走封装、日志行整个手拼（C1 写法三 / M-Q9）",
        "titles": ["绕过 AsrTrace.w / i / line 直接拿 AsrTrace.TAG"],
    },
    # ── 实测走得出的发射器写法：口径文案必须点名 ──
    {
        "id": "N1", "kind": ESCAPED, "claim": "TAG 写成字面串", "expect_failed": 0,
        "what": "`Log.w(\"AsrTrace\", \"…ev=blocked…\")`：TAG 不走常量引用（C1′ 探针 B）",
    },
    {
        "id": "N2", "kind": ESCAPED, "claim": "TAG 存进变量", "expect_failed": 0,
        "what": "`val tag = AsrTrace.TAG` 之后 `Log.w(tag, …)`（C1′ 探针 C）",
    },
    {
        "id": "N3", "kind": ESCAPED, "claim": "别名发射", "expect_failed": 0,
        "site": "qaN3AliasExit",
        "what": "`import com.ispeech.input.AsrTrace as ASR` + `ASR.w(null, ASR.EV_BLOCKED, …)`："
                "引用常量、走封装、不换行，仍走得出（C1′ 探针 D）",
        # 别名 import 会把 `AsrTrace` 这个简单名在本文件里遮掉（实测：把这条 import 加进
        # IspeechImeService.kt 后全文 9 处 `Unresolved reference: AsrTrace`），所以这种写法只会
        # 长在单独的文件里 —— 这也是它比前两种更容易绕过去的缘故：不是刻意藏，是天然不在同一文件。
        "new": [("app/src/main/java/com/ispeech/input/QaAliasProbe.kt",
                 "package com.ispeech.input\n\nimport com.ispeech.input.AsrTrace as ASR\n\n"
                 "private fun qaN3AliasExit() {\n"
                 '    ASR.w(null, ASR.EV_BLOCKED, "reason" to "hand_built")\n}\n')],
    },
    # ── 判定与埋点本体：验收口径 2 要求的三个方向 ──
    {
        "id": "M-A", "kind": CAUGHT, "claim": None, "expect_failed": 2,
        "what": "枚举调用点退回调用方 action（本单原缺陷）",
        "edits": [(CONTROLLER, "Intent(RecognitionServices.SERVICE_INTERFACE)",
                   "Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)")],
        "titles": ["queryIntentServices 没用 RecognitionServices.SERVICE_INTERFACE",
                   "仍拿 RecognizerIntent.ACTION_RECOGNIZE_SPEECH"],
    },
    {
        "id": "M-B", "kind": CAUGHT, "claim": None, "expect_failed": 2,
        "what": "钉字读法一：改 `RecognitionServices.SERVICE_INTERFACE` 的**常量取值**为调用方 action"
                "（调用点仍引用常量）",
        "edits": [(CONTROLLER, 'const val SERVICE_INTERFACE = "android.speech.RecognitionService"',
                   'const val SERVICE_INTERFACE = "android.speech.action.RECOGNIZE_SPEECH"')],
        "titles": ["SERVICE_INTERFACE 取值是", "<queries> action 名单"],
    },
    {
        "id": "M-B2", "kind": CAUGHT, "claim": None, "expect_failed": 1,
        "what": "钉字读法二：常量保留，把**枚举调用点**写成调用方 action 的裸串",
        "edits": [(CONTROLLER, "Intent(RecognitionServices.SERVICE_INTERFACE)",
                   'Intent("android.speech.action.RECOGNIZE_SPEECH")')],
        "titles": ["queryIntentServices 没用 RecognitionServices.SERVICE_INTERFACE"],
    },
    {
        "id": "M-H", "kind": CAUGHT, "claim": None, "expect_failed": 1,
        "what": "删掉 usable() 的 BIND_RECOGNITION_SERVICE 过滤（桩服务会被重新放进来）",
        "edits": [(CONTROLLER, "candidates.filter { it.permission == BIND_PERMISSION }", "candidates")],
        "titles": ["不再按 BIND_PERMISSION 过滤"],
    },
    {
        "id": "M-C1", "kind": CAUGHT, "claim": None, "expect_failed": 1,
        "what": "删掉探测拦下分支的 traceVoiceBlocked(blocked) 调用",
        "edits": [(SERVICE, "            traceVoiceBlocked(blocked)\n", "")],
        "titles": ["不再调用 traceVoiceBlocked(blocked)"],
    },
    {
        "id": "M-C2", "kind": CAUGHT, "claim": None, "expect_failed": 1,
        "what": "删掉 controller == null 分支的归类打点",
        "edits": [(SERVICE, "            traceVoiceBlocked(RecognitionServices.REASON_NO_RECOGNITION_AVAILABLE)\n", "")],
        "titles": ["controller == null 分支不再调用"],
    },
    {
        "id": "M-I", "kind": CAUGHT, "claim": None, "expect_failed": 2,
        "what": "删掉 startListening 里绑定失败的 ev=blocked 打点",
        "edits": [(CONTROLLER,
                   "            AsrTrace.w(\n                null,\n                AsrTrace.EV_BLOCKED,\n"
                   '                "reason" to RecognitionServices.REASON_SERVICE_BIND_FAILED,\n'
                   '                "engine" to asr.engine.name,\n            )\n', "")],
        "titles": ["startListening() 不再打 AsrTrace.EV_BLOCKED", "ev=blocked 的实际出口"],
    },
    {
        "id": "M-D", "kind": CAUGHT, "claim": None, "expect_failed": 2,
        "what": "删掉服务侧统一入口 traceVoiceBlocked 里的打点",
        "edits": [(SERVICE,
                   "        AsrTrace.w(\n            null,\n            AsrTrace.EV_BLOCKED,\n", "")],
        "titles": ["traceVoiceBlocked() 不再打 AsrTrace ev=blocked", "ev=blocked 的实际出口"],
    },
    {
        "id": "M-E", "kind": CAUGHT, "claim": None, "expect_failed": 1,
        "what": "reason 字面量漂移（docs 的 grep 口径与历史日志读不到）",
        "edits": [(CONTROLLER, 'REASON_NO_USABLE_SERVICE = "no_usable_service"',
                   'REASON_NO_USABLE_SERVICE = "no_usable"')],
        "titles": ["REASON_* 常量取值变了"],
    },
]

# 探针函数：只进副本，不进仓库。写法即「怎么绕」，与 --only 的手动复现一一对应。
PROBES = {
    "K1": 'private fun qaK1BareEvExit() {\n    AsrTrace.w(null, "blocked", "reason" to "hand_built")\n}\n',
    "K2": 'private fun qaK2LocalEvExit() {\n    val ev = "blocked"\n'
          '    AsrTrace.w(null, ev, "reason" to "hand_built")\n}\n',
    "K3": 'private fun qaK3FoldedDotExit() {\n    AsrTrace\n        .w(null, "blocked", "reason" to "hand_built")\n}\n',
    "K4": 'private fun qaK4RawTagExit() {\n'
          '    Log.w(AsrTrace.TAG, AsrTrace.line(null, "blocked", 0L, emptyList()))\n}\n',
    "K5": 'private fun qaK5RawBuiltLineExit() {\n'
          '    Log.w(AsrTrace.TAG, "session=- ev=blocked reason=hand_built")\n}\n',
    "N1": 'private fun qaN1LiteralTagExit() {\n'
          '    Log.w("AsrTrace", "session=- ev=blocked reason=hand_built")\n}\n',
    "N2": 'private fun qaN2TagInVariableExit() {\n    val tag = AsrTrace.TAG\n'
          '    Log.w(tag, "session=- ev=blocked reason=hand_built")\n}\n',
}

BAN_UNIVERSAL = re.compile(r"无论")
EXCLUDE_MARKER = "不在采集范围"

_passed = _failed = _warned = 0


def site_of(entry: dict) -> str:
    """发射器探针的函数名：门禁点名的就是 (文件, 函数名) 这个 site。"""
    if "site" in entry:
        return entry["site"]
    m = re.search(r"private fun (\w+)\(", PROBES.get(entry["id"], ""))
    return m.group(1) if m else ""


def report(level: str, message: str) -> None:
    global _passed, _failed, _warned
    print(f"{level}: {message}")
    if level == "PASS":
        _passed += 1
    elif level == "FAIL":
        _failed += 1
    elif level == "WARN":
        _warned += 1


def check(cond: bool, ok: str, fail: str) -> None:
    report("PASS" if cond else "FAIL", ok if cond else fail)


def edits_for(entry: dict) -> list:
    """一只变异的补丁；没写 edits 的发射器探针 = 把 PROBES[id] 追加到服务文件末尾。"""
    if "edits" in entry:
        return entry["edits"]
    if "new" in entry:
        return []
    return [(SERVICE, None, None)]


def apply_edits(root: Path, entry: dict) -> None:
    probe = PROBES.get(entry["id"])
    for rel, content in entry.get("new", []):
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    for rel, before, after in edits_for(entry):
        path = root / rel
        text = path.read_text(encoding="utf-8")
        if before is None:  # 追加为顶层函数：不进门禁按函数取的任何一个 region
            if probe is None:
                raise SystemExit(f"{entry['id']} 要追加探针却没有 PROBES 条目")
            path.write_text(text + "\n" + probe, encoding="utf-8")
            continue
        hits = text.count(before)
        if hits != 1:
            raise SystemExit(f"变异配方失效：{rel} 里 {before!r} 命中 {hits} 次（应为 1）"
                             f" —— 源码或 docs 改了，先更新本表，再谈门禁口径")
        text = text.replace(before, after, 1)
        path.write_text(text, encoding="utf-8")


def make_copy(dst: Path) -> Path:
    for item in COPY_ITEMS:
        src = ROOT / item
        if not src.exists():
            raise SystemExit(f"副本缺少 {item}：仓库结构变了，同步 COPY_ITEMS")
        target = dst / item
        target.parent.mkdir(parents=True, exist_ok=True)
        if src.is_dir():
            shutil.copytree(src, target, ignore=COPY_IGNORE)
        else:
            shutil.copy2(src, target)
    return dst


def run_gate(root: Path):
    proc = subprocess.run([sys.executable, str(root / GATE_REL)], capture_output=True, text=True)
    m = re.search(r"SUMMARY: passed=(\d+) failed=(\d+) warned=(\d+)", proc.stdout)
    if not m:
        raise SystemExit(f"副本门禁没有 SUMMARY 输出（退出码 {proc.returncode}）：\n{proc.stdout}{proc.stderr}")
    fails = [ln for ln in proc.stdout.splitlines() if ln.startswith("FAIL: ")]
    return int(m.group(1)), int(m.group(2)), fails


def paragraph_containing(text: str, marker: str) -> str:
    """markdown 段落级：返回包含 marker 的那一段（到空行为止）。"""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if marker in line:
            start = i
            while start > 0 and lines[start - 1].strip():
                start -= 1
            end = i
            while end + 1 < len(lines) and lines[end + 1].strip():
                end += 1
            return "\n".join(lines[start:end + 1])
    return ""


def sentence_containing(text: str, marker: str) -> str:
    for sentence in re.split(r"[。；\n]", text):
        if marker in sentence:
            return sentence
    return ""


def claim_surfaces() -> dict:
    """三处声称了采集范围的文字；取不到就说明那段被改写或删除，B 类断言会点名。

    取回来的块会压掉换行与缩进：文档头那段是硬包 Chinese 文本，句子会跨物理行，
    不压就没法按句判断「覆盖清单」与「排除句」分别写了什么。
    """
    def unwrap(text: str) -> str:
        return re.sub(r"\n\s*", "", text)

    gate_src = GATE_SRC.read_text(encoding="utf-8")
    docs = (ROOT / DOCS_REL).read_text(encoding="utf-8")
    m = re.search(r"8\.2b「识别服务枚举」专项.*?(?=\n *\d+\. )", gate_src, re.S)
    header = unwrap(m.group(0)) if m else ""
    m = re.search(r"check\(\s*\n\s*blocked_sites == \{.*?\n {4}\)\n", gate_src, re.S)
    outlet = unwrap(m.group(0)) if m else ""
    docs_para = paragraph_containing(docs, "出口集合按**发射器**采集")
    return {"check_crosscut.py 文档头 8.2b 段": header,
            "check_crosscut.py 出口集合断言（标题 + FAIL 文案）": outlet,
            "docs/voice-input.md §二 前置判定段": docs_para}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="VOCA-310 8.2b 采集口径的变异对照与措辞一致性")
    parser.add_argument("--list", action="store_true", help="只打印对照表")
    parser.add_argument("--only", help="逗号分隔的变异 id 子集")
    parser.add_argument("--keep", action="store_true", help="保留变异副本目录并打印路径")
    parser.add_argument("--emit", metavar="DIR",
                        help="把「全部探针一次打完」的副本写到 DIR，供 gradle 编一遍证明探针不是理论写法"
                             "（DIR 会先被清空；不写仓库）")
    args = parser.parse_args(argv)

    if args.list:
        for e in ORACLE:
            print(f"{e['id']:<6}{e['kind']:<8}failed={e['expect_failed']:<2} {e['what']}")
        return 0

    wanted = None
    if args.only:
        wanted = {s.strip() for s in args.only.split(",") if s.strip()}
        unknown = wanted - {e["id"] for e in ORACLE}
        if unknown:
            report("FAIL", f"--only 没有这些变异：{sorted(unknown)}；可用 {sorted(e['id'] for e in ORACLE)}")
            return finish()

    if args.emit:
        out = Path(args.emit).resolve()
        if out == ROOT or ROOT in out.parents:
            raise SystemExit(f"--emit 不能指向仓库内：{out}")
        if out.exists():
            shutil.rmtree(out)
        out.mkdir(parents=True)
        make_copy(out)
        shutil.copy2(ROOT / "gradlew", out / "gradlew")
        shutil.copytree(ROOT / "gradle", out / "gradle")
        probes = [e for e in ORACLE if "edits" not in e]  # 删除类变异会让副本编不过，不参与
        for entry in probes:
            apply_edits(out, entry)
        print(f"探针副本已写到 {out}（{len(probes)} 只发射器写法全部打上，N3 是独立文件 QaAliasProbe.kt）")
        print(f"编一遍：JAVA_HOME=<jdk17> ANDROID_HOME=<sdk> ./gradlew -p {out} --offline :app:compileDebugKotlin")
        return 0

    base = Path(tempfile.mkdtemp(prefix="voca310-gate-"))
    try:
        make_copy(base)
        passed0, failed0, fails0 = run_gate(base)
        check(failed0 == 0, f"pristine 副本门禁绿（passed={passed0} failed=0）",
              f"pristine 副本门禁就有 {failed0} 条 FAIL，变异对照的前提没了：{[ln[:80] for ln in fails0]}")
        if passed0 != CROSSCUT_BASE_PASSED:
            report("WARN", f"pristine passed={passed0} != 记录基线 {CROSSCUT_BASE_PASSED}："
                           f"8.2b 增删过断言，同步 docs 的「共 N 向」与本表期望条数")

        for entry in ORACLE:
            if wanted and entry["id"] not in wanted:
                continue
            work = base.parent / f"{base.name}-{entry['id']}"
            if work.exists():
                shutil.rmtree(work)
            shutil.copytree(base, work)
            apply_edits(work, entry)
            _, failed, fails = run_gate(work)
            delta = failed - failed0  # 变异自身带来的 FAIL，pristine 不绿时读数仍然对
            entry["measured"] = delta
            titles = entry.get("titles", [])
            hit = [t for t in titles if any(t in ln for ln in fails)]
            if entry["kind"] == CAUGHT:
                check(delta == entry["expect_failed"] and len(hit) == len(titles),
                      f"{entry['id']} 拦住：{entry['what']} → 新增 failed={delta}，点名断言 "
                      f"{len(hit)}/{len(titles)} 条",
                      f"{entry['id']} 期望新增 failed={entry['expect_failed']} + 断言名 {titles}，"
                      f"实测新增 {delta}（总 {failed} - pristine {failed0}）、命中 {hit}、"
                      f"缺 {sorted(set(titles) - set(hit))}；实际 FAIL 行 {[ln[:72] for ln in fails]}")
            else:
                # 光看条数不够：pristine 已经不绿时，delta 会把「探针其实被采集到了」盖住。
                # 所以再直接问一次——门禁有没有在任何 FAIL 行里点过这个函数名。
                probe_name = site_of(entry)
                seen = [ln[:72] for ln in fails if probe_name and probe_name in ln]
                check(delta == 0 and not seen and not titles,
                      f"{entry['id']} 走得出：{entry['what']} → 新增 failed={delta}，"
                      f"`{probe_name}` 没被任何断言采集到（口径文案必须点名它，见 B 类断言）",
                      f"{entry['id']} 本表记为「走得出」，实测新增 failed={delta}（总 {failed} - "
                      f"pristine {failed0}）、`{probe_name}` 被点名 {len(seen)} 处：{seen} —— "
                      f"采集正则收紧了，覆盖清单与排除句要一起改")
            if not args.keep:
                shutil.rmtree(work)
        if args.keep:
            report("WARN", f"变异副本留在 {base.parent}/<{base.name}>-<id>")

        # ── B：覆盖面声明必须与本表同集合 ──
        surfaces = claim_surfaces()
        escaped_claims = sorted({e["claim"] for e in ORACLE if e["kind"] == ESCAPED and e["claim"]})
        caught_claims = sorted({e["claim"] for e in ORACLE if e["kind"] == CAUGHT and e["claim"]})
        for name, text in surfaces.items():
            check(bool(text), f"{name}：取到了覆盖面声明",
                  f"{name}：没找到声称采集范围的文字 —— 那段被改写或删掉了，先把它加回来，"
                  f"否则「口径与变异一致」没人守")
            if not text:
                continue
            check(not BAN_UNIVERSAL.search(text), f"{name}：没有无限定全称（不含「无论」）",
                  f"{name} 还剩「无论…都 FAIL」式全称：采集只认带限定名的两种调用点，"
                  f"N1/N2/N3 实测走得出，全称与对照表不一致（VOCA-310 条件项 C1′）")
        outlet_name = [n for n in surfaces if "出口集合断言" in n][0]
        if surfaces[outlet_name]:
            check("带限定名" in surfaces[outlet_name] and "采集范围内" in surfaces[outlet_name],
                  "出口集合断言的标题已限定（采集范围内 + 带限定名）",
                  "出口集合断言仍是无限定的「main 里不存在第三处未断言的出口」：别名发射、TAG 存变量、"
                  "TAG 写成字面串三种走得出，标题要限定成「采集范围内（带限定名调用点）」")
        for name in ("check_crosscut.py 文档头 8.2b 段", "docs/voice-input.md §二 前置判定段"):
            text = surfaces.get(name, "")
            if not text:
                continue
            exclude = sentence_containing(text, EXCLUDE_MARKER)
            missing = [c for c in escaped_claims if c not in exclude]
            stale = [c for c in caught_claims if c in exclude]
            absent = [c for c in caught_claims if c not in text]
            check(bool(exclude) and not missing and not stale,
                  f"{name}：排除句点名 {len(escaped_claims)} 种走得出的写法，且没留已被拦住的",
                  f"{name}："
                  + (f"没有「{EXCLUDE_MARKER}」那句限定 " if not exclude else "")
                  + (f"排除句漏点名 {missing} " if missing else "")
                  + (f"排除句里还留着已被拦住的 {stale} " if stale else "")
                  + "—— 走得出的写法必须写出来，别让人以为覆盖面是全称")
            check(not absent, f"{name}：覆盖清单点了 {len(caught_claims)} 种拦得住的写法",
                  f"{name} 的覆盖清单缺 {absent} —— 这些写法实测会被拦住，口径里要写明")
    finally:
        if not args.keep:
            shutil.rmtree(base, ignore_errors=True)
        else:
            report("WARN", f"pristine 副本留在 {base}")
    return finish()


def finish() -> int:
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
