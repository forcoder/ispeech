#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""r21_wiring_compare.py —— 档2 判定串接线的改前/改后对照（VOCA-37 / R21 第二件）。

要证的两件事，一件一个 section：

  ① **默认态不得漂移**。改前改后跑同一批已入库帧，`AC10` 行的每个判定字段必须逐字相同。
     理由：`evidence/ev_r8_ac10_*.txt` 里那些行是报告正文引用的读数，接线如果把默认态的
     输出改了一个字符，历史证据就对不上了。
  ② **翻布尔必须让判定动**。同一帧、同一输入，只把「已签字」置真：
     改前 `flipped=no_effect`（判定串是硬编码字面量，11-24 04:58Z 实测）
     → 改后 `flipped=verdict_moves`。

基线与帧都从 git 里现取，不在分支上留 PNG：`--baseline` 默认指向 PR #25 head
`1b7cf02`（该分支是 `band.py` 与 `shots/` 的唯一出处，本分支只带接线）。

    python3 tools/qa/voca-37/r21_wiring_compare.py
    python3 tools/qa/voca-37/r21_wiring_compare.py --baseline 1b7cf02 --keep

退出码：0 = 两节都符合预期；非 0 = 出现漂移或翻布尔无效（即接线没生效）。
"""
import argparse
import importlib.util
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
BASE_DIR = os.path.join(ROOT, "build", "r21compare")   # build/ 已被 gitignore
NEW_BAND = os.path.join(HERE, "band.py")

# 帧清单：(帧名, dpi, band_top, kb_h)。取值出处 = band.py 头部常量 + PR #25 的
# `evidence/ev_r5u_520.txt` / `ev_r5u_440.txt` 的 FRAME 行；面板一律 1080×2340。
FRAMES = [
    ("r5u_520_fs1.15_shijian_comp", 520, 1212, 1128),   # 332.3dp → 档2，full_count=3
    ("r5u_520_fs1.15_dashu_comp",   520, 1212, 1128),   # 332.3dp → 档2，full_count=3
    ("r5u_440_fs1.0_shijian_comp",  440, 1388,  952),   # 392.7dp → 档1，full_count=4
]
FIELDS = ["tier", "tier_need", "logic_w_dp", "full_count", "verdict", "need",
          "tier_verdict", "r21_signed"]


def git_show(rev, path, dest):
    """把基线版本里的文件取到 dest；取不到就返回 None（不冒充有基线）。"""
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    r = subprocess.run(["git", "show", "%s:%s" % (rev, path)],
                       cwd=ROOT, capture_output=True)
    if r.returncode != 0:
        return None
    with open(dest, "wb") as f:
        f.write(r.stdout)
    return dest


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    sys.modules[name] = m
    spec.loader.exec_module(m)
    return m


def row(mod, png, dpi):
    den = dpi / 160.0
    top = next(f[2] for f in FRAMES if f[0] == os.path.basename(png)[:-4])
    a = mod.analyze(png, top=top, band_px=int(round(46 * den)), dpi=dpi)
    if not a["ok"]:
        return {"GATE": a["reason"]}
    ac10, _ = mod.ac10_rows(a, den)
    return {k: ac10.get(k, "<absent>") for k in FIELDS}


def verdict_with(mod, png, dpi, signed):
    """只改「已签字」这一格，其余输入不动。AC-12 前置（新一代才有）另置真，
    这样观测到的差异只能来自 R21。"""
    den = dpi / 160.0
    top = next(f[2] for f in FRAMES if f[0] == os.path.basename(png)[:-4])
    old_r21 = getattr(mod, "R21_SIGNED", False)
    has_ac12 = hasattr(mod, "AC12_MEASURED")
    mod.R21_SIGNED = signed
    if has_ac12:
        mod.AC12_MEASURED = True
    try:
        a = mod.analyze(png, top=top, band_px=int(round(46 * den)), dpi=dpi)
        ac10, _ = mod.ac10_rows(a, den)
        return ac10["tier_verdict"]
    finally:
        mod.R21_SIGNED = old_r21
        if has_ac12:
            mod.AC12_MEASURED = False


def main():
    ap = argparse.ArgumentParser(description="档2 判定串接线：改前/改后对照")
    ap.add_argument("--baseline", default="1b7cf02", help="改前版本所在的 commit（默认 PR #25 head）")
    ap.add_argument("--keep", action="store_true", help="保留取出的帧与基线脚本（默认删）")
    args = ap.parse_args()

    base_band = os.path.join(BASE_DIR, "band_baseline.py")
    if git_show(args.baseline, "tools/qa/voca-37/band.py", base_band) is None:
        print("GATE reason=baseline_missing rev=%s rc=30" % args.baseline)
        print("# 该 commit 不在本仓库历史里（PR #25 分支未 fetch 时如此），基线不可得")
        return 30

    frames = {}
    for name, _dpi, _top, _kb in FRAMES:
        p = os.path.join(BASE_DIR, "shots", name + ".png")
        if not os.path.exists(p) and git_show(args.baseline,
                                              "tools/qa/voca-37/shots/%s.png" % name, p) is None:
            print("GATE reason=frame_missing frame=%s rc=20" % name)
            return 20
        frames[name] = p

    old = load("band_baseline", base_band)
    new = load("band_current", NEW_BAND)

    # ---- ① 默认态不漂移 ----
    print("# ① 默认态：同一批已入库帧，逐字段对照（R21_SIGNED=False / AC12_MEASURED=False）")
    diffs = 0
    for name, dpi, _top, _kb in FRAMES:
        png = frames[name]
        o, n = row(old, png, dpi), row(new, png, dpi)
        print("FRAME %s dpi=%d" % (name, dpi))
        for k in FIELDS:
            same = o.get(k) == n.get(k)
            diffs += 0 if same else 1
            print("  %-13s before=%-40s after=%-40s %s"
                  % (k, o.get(k), n.get(k), "SAME" if same else "DIFF"))
    print("SECTION1 field_diffs=%d verdict=%s" % (diffs, "NO_DRIFT" if diffs == 0 else "DRIFT"))

    # ---- ② 翻布尔必须让判定动 ----
    print()
    print("# ② 翻布尔：同一帧只改 R21_SIGNED（档2 帧，full_count=3 已达标）")
    name, dpi, _top, _kb = FRAMES[0]
    png = frames[name]
    o0, o1 = verdict_with(old, png, dpi, False), verdict_with(old, png, dpi, True)
    n0, n1 = verdict_with(new, png, dpi, False), verdict_with(new, png, dpi, True)
    print("FRAME %s dpi=%d" % (name, dpi))
    print("BEFORE signed=False -> %s" % o0)
    print("BEFORE signed=True  -> %s   flipped=%s"
          % (o1, "no_effect" if o0 == o1 else "verdict_moves"))
    print("AFTER  signed=False -> %s" % n0)
    print("AFTER  signed=True  -> %s   flipped=%s"
          % (n1, "no_effect" if n0 == n1 else "verdict_moves"))
    print("SECTION2 before=%s after=%s"
          % ("no_effect" if o0 == o1 else "verdict_moves",
             "no_effect" if n0 == n1 else "verdict_moves"))

    if not args.keep:
        for p in list(frames.values()) + [base_band]:
            try:
                os.remove(p)
            except OSError:
                pass
    ok = diffs == 0 and o0 == o1 and n0 != n1
    print("RESULT %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())