#!/usr/bin/env python3
"""VOCA-47 R7 / 打回项 1「规格重锚 0e4eeb0」：把报告里引用的规格行号从旧 head 逐条搬到新 head。

用法：
    python3 spec_ux_anchors.py                      # 默认 8324b03 → 0e4eeb0
    python3 spec_ux_anchors.py <old> <new> <path>

对每条：取旧 head 那一行的内容，在新 head 的文件里找同一内容 —— 找到就报新行号（match=True），
找不到就报最相似的候选与相似度（DRIFT），让第三人能 grep 原文核对。这不是「换个数字写」：
每条都要有内容证据，行号漂了不叫重锚，叫编数。
"""
import datetime
import difflib
import re
import subprocess
import sys

REPO = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip()
OLD = sys.argv[1] if len(sys.argv) > 1 else "8324b03"
NEW = sys.argv[2] if len(sys.argv) > 2 else "0e4eeb0"
PATH = sys.argv[3] if len(sys.argv) > 3 else "docs/keyboard-ux-spec.md"

# 报告 docs/qa/voca-37-stage3-regression.md 实际引用的规格锚（label, 旧行号）
ANCHORS = [
    ("AC-1", 413), ("AC-2", 427), ("AC-3", 440), ("AC-4", 453), ("AC-5", 467),
    ("AC-6", 480), ("AC-7", 490), ("AC-8", 505), ("AC-9", 509), ("AC-10", 519),
    ("AC-10-Given首行", 525), ("AC-10-采样区", 529),
    ("AC-11", 547), ("§5-无障碍表头", 583), ("§5-缺口行1", 589),
    ("§5-缺口行3", 593), ("§5-合规行", 594), ("§5-缺口行4", 595),
    ("§5-缺口行5", 597),
    # 第八轮打回项 5：pp 判据原本登记成 ("§6.5-pp", 599)，而 8324b03:599 是「门禁结论」那一行
    # （§5 无障碍表的收尾），相似度匹配把新 head 无障碍段里的一句相近措辞当成了 pp 门限，
    # 报告因此跟着抄错了行号。pp 的门限在 §6.1/§6.2，改成这两条内容锚。
    ("§6.1-pp门限B1", 37), ("§6.2-pp门限相对搜狗1pp", 609),
]

# 已被本工具显式作废的旧映射：旧行文字在新 head 被**改写**（不是行号漂移），内容匹配必然失配，
# 所以改成按锚点句现算新行号，输出 verdict=REPLACED + 命中模式 + 新旧原文，第三人可 grep 核对。
RETIRED = [
    ("§6.5-pp", 599, r"相对搜狗 ≤ 1 个百分点", "§6.2-pp门限相对搜狗1pp",
     "登记错了节：8324b03:599 是 §5 无障碍表的「门禁结论」行，不是 pp 门限；相似度 0.983 让匹配器"
     "放行了无障碍段里措辞相近的一句，报告 §5 与 ppsum.py 的 criterion= 一起被带偏（第八轮打回项 5）"),
    ("AC-10a", 540, r"^\| \*\*AC-10a\*\*", "同行改写",
     "判据由单值「≥4 条」改为按面板逻辑宽度分档（VOCA-464），行文字变了，行号也漂"),
    ("AC-10b", 541, r"^\| \*\*AC-10b\*\*", "同行改写",
     "旧写的两条出路（缩 chip 宽 / 放宽词长）经现算不可达，4 字以上可见性改由 AC-12 承接"),
    ("§5-缺口行2", 590, r"^\| 候选词可点高度", "同行改写",
     "门限从 48dp 订正为 46dp（VOCA-464），行文字变了"),
    ("§5-既有债行", 592, r"^\| 一屏 ≥4 候选", "同行改写",
     "「恒高三角」一行已裁：有一条边是假的，剩下的按几何分档"),
]


def show(ref):
    out = subprocess.run(["git", "show", f"{ref}:{PATH}"], cwd=REPO,
                         capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"git show {ref}:{PATH} 失败：{out.stderr.strip()}")
    return out.stdout.splitlines()


def norm(s):
    return re.sub(r"\s+", " ", s.strip()).lower()


def P(line):
    """每行尾部补 ts=（抄 ppwin.py / ppsum.py 的现成口径），追不到时刻的行不算证据。"""
    stamp = datetime.datetime.now().astimezone().strftime("%Y-%m-%dT%H:%M:%S%z")
    for chunk in line.split("\n"):
        print(f"{chunk}\tts={stamp}" if chunk.strip() else chunk)


def main():
    old_lines, new_lines = show(OLD), show(NEW)
    P(f"# spec_ux_anchors.py · 规格重锚 {OLD} → {NEW} · {PATH}")
    P(f"# 旧文件 {len(old_lines)} 行 · 新文件 {len(new_lines)} 行 · "
          f"新增规格修订记录（VOCA-464 分档 + AC-12）会让 540 以后的行号整体下移")
    drifted = missed = 0
    for label, ln in ANCHORS:
        if ln > len(old_lines):
            P(f"ANCHOR\tlabel={label}\told={OLD}:{ln}\tverdict=MISS\treason=old_line_past_eof")
            missed += 1
            continue
        text = old_lines[ln - 1]
        key = norm(text)
        hits = [i + 1 for i, l in enumerate(new_lines) if norm(l) == key and key]
        if hits:
            P(f"ANCHOR\tlabel={label}\told={OLD}:{ln}\tnew={NEW}:{hits[0]}\tmatch=True"
                  f"\tmulti={len(hits) > 1}\tsnip={text.strip()[:60]}")
            continue
        sims = sorted(((difflib.SequenceMatcher(None, key, norm(l)).ratio(), i + 1, l)
                       for i, l in enumerate(new_lines) if norm(l)), reverse=True)
        best, bl, bltext = sims[0]
        if best >= 0.80:
            drifted += 1
            P(f"ANCHOR\tlabel={label}\told={OLD}:{ln}\tnew={NEW}:{bl}\tmatch=False\tverdict=DRIFT"
                  f"\tsim={best:.3f}\n\told_text={text.strip()[:80]}\n\tnew_text={bltext.strip()[:80]}")
        else:
            missed += 1
            P(f"ANCHOR\tlabel={label}\told={OLD}:{ln}\tnew=NONE\tverdict=MISS\tsim={best:.3f}"
                  f"\tnearest={NEW}:{bl}\n\told_text={text.strip()[:80]}\n\tnew_text={bltext.strip()[:80]}")
    for label, ln, pat, by, why in RETIRED:
        hits = [i + 1 for i, l in enumerate(new_lines) if re.search(pat, l)]
        old_text = old_lines[ln - 1].strip()[:80] if ln <= len(old_lines) else ""
        new_text = new_lines[hits[0] - 1].strip()[:80] if hits else ""
        P(f"ANCHOR\tlabel={label}\told={OLD}:{ln}\tpattern={pat}\t"
              f"new={NEW}:{hits[0] if hits else 'NONE'}\thits={len(hits)}\tverdict=REPLACED"
              f"\treplaced_by={by}\n\treason={why}\n\told_text={old_text}\n\tnew_text={new_text}")
    # 新 head 才有、报告必须改口引用的三条
    for label, pat in [("AC-12", r"^### AC-12"), ("AC-10a-分档行", r"档1|档2"),
                       ("§2.1-让步顺序", r"让步顺序"), ("§9.14-未取证几何", r"未取证.*几何|几何.*未取证")]:
        hits = [i + 1 for i, l in enumerate(new_lines) if re.search(pat, l)]
        P(f"NEWONLY\tlabel={label}\tnew={NEW}:{hits[0] if hits else 'NONE'}\thits={len(hits)}"
              f"\tsnip={(new_lines[hits[0] - 1].strip()[:70]) if hits else ''}")
    P(f"SUMMARY\t共 {len(ANCHORS)} 条 · 内容原样命中 {len(ANCHORS) - drifted - missed} · DRIFT {drifted} · MISS {missed}"
          f"\tretired_replaced={len(RETIRED)}")
    return 0 if missed == 0 else 3


if __name__ == "__main__":
    sys.exit(main())
