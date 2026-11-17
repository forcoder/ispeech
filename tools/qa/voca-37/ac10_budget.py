#!/usr/bin/env python3
"""VOCA-464 裁决取证：从 AC-10 的 CAND / GATES 行现算「前 4 条完整可见」需要的逻辑宽度，
并据此判「四条出路里哪几条在几何上可达」。

输入 = `fs115.py` 的 stdout（含 `CAND` 与 `GATES` 行）。取证原文在 PR #25 head：

    git show 5e8b0f4:tools/qa/voca-37/evidence/ev_r6_ac10_decoupled.txt > /tmp/ev.txt
    python3 tools/qa/voca-37/ac10_budget.py /tmp/ev.txt

口径（都写死在这里，便于复核）：
· 项宽 = `x1 - x0 + 1`，自带项内左右 12dp padding 与项间 6dp margin（实测行里有）
· 被视口切掉的项不计入「完整数」，第 4 条按**同帧 2 字项的中位宽**补 —— 这正是 AC-10a
  「真实词库、2–3 字常用词为主」的口径，长词不进硬门限
· 所需逻辑宽度 = paddingStart 8dp + chip 盒 + Σ(4 项) + paddingEnd 6dp
  （根 padding 锚 `c524351` 的 candidate_view.xml:14-17；采样右界 = 屏宽 − paddingEnd）
· dp↔px = density/160；字号缩放由帧本身带入（帧就是 fs1.15 下截的）
· 恒高 46dp 与键区几何不参与：本脚本只答横向问题

不含产品代码，不改任何判据数据；只读证据文本。
"""
import argparse
import re
import sys

PAD_START_DP = 8.0     # candidate_view.xml 根 paddingStart
PAD_END_DP = 6.0       # 同 paddingEnd；也是采样区右界的内缩量
CHIP_PAD_DP = 12.0     # candidate_code 的 paddingStart + paddingEnd（6+6）
SHORT_ITEM_MAX_PX = 240  # 2 字项的宽上界（实测 2 字 225px、3 字 270px、4 字 353px）


def rows(path, prefix):
    out = []
    for line in open(path, encoding="utf-8"):
        if line.startswith(prefix):
            d = {}
            for part in line.rstrip("\n").split("\t")[1:]:
                k, sep, v = part.partition("=")
                if sep:
                    d[k.strip()] = v.strip()
            out.append(d)
    return out


def px2dp(value, dens):
    return value * 160.0 / dens


def load(path):
    """-> {(code, dens): {"chip":px, "left":px, "fs":str, idx:(项宽px, 是否完整)}}"""
    frames = {}
    for c in rows(path, "CAND"):
        f = frames.setdefault((c["code"], int(c["dens"])), {})
        f[int(c["idx"])] = (int(c["x1"]) - int(c["x0"]) + 1, c["fully_visible"] == "True")
    for g in rows(path, "GATES"):
        m = re.search(r"'left':\s*(\d+)[^}]*'right':\s*(\d+)", g.get("chip_box", ""))
        if not m:
            continue
        f = frames.setdefault((g["code"], int(g["dens"])), {})
        f["chip"] = int(m.group(2)) - int(m.group(1))
        f["left"] = int(m.group(1))
        f["fs"] = g["fs"]
    return frames


def budget(f, dens, panel_w):
    """-> (所需逻辑宽度 dp, 第 4 条右沿 px, 各项宽 px, chip dp, 2 字项 dp)"""
    idxs = sorted(k for k in f if isinstance(k, int))
    full = [f[k] for k in idxs if f[k][1]]
    short = sorted(w for w, _ in full if w <= SHORT_ITEM_MAX_PX)
    fourth = short[len(short) // 2] if short else max(w for w, _ in f_pairs(f))
    need_px = f["chip"] + sum(w for w, _ in full) + (4 - len(full)) * fourth
    chip_dp = px2dp(f["chip"], dens)
    return (PAD_START_DP + px2dp(need_px, dens) + PAD_END_DP,
            f["left"] + need_px,
            [w for w, _ in f_pairs(f)],
            chip_dp,
            px2dp(short[len(short) // 2] if short else fourth, dens))


def f_pairs(f):
    return [(k, v) for k, v in f.items() if isinstance(k, int)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("evidence")
    ap.add_argument("--panel-w", type=int, default=1080, help="证据帧所在面板的像素宽")
    ap.add_argument("--tier", default="1080x2400:520", help="出路可达性所判的「被测档」几何")
    ap.add_argument("--geom", default="1080x2340:440,1080x2400:480,1080x2400:520,"
                                      "1080x2400:560,1260x2720:520,1440x3200:560")
    args = ap.parse_args()

    frames = load(args.evidence)
    print("# AC-10 横向预算现算（取证：%s，面板宽 %dpx）" % (args.evidence, args.panel_w))

    budgets = {}
    for key in sorted(frames):
        code, dens = key
        f = frames[key]
        need_dp, edge_px, widths, chip_dp, item2_dp = budget(f, dens, args.panel_w)
        budgets[key] = need_dp
        print("\n## %s @%ddpi fs=%s" % (code, dens, f.get("fs")))
        for k, _ in sorted(f_pairs(f)):
            w, ok = f[k]
            print("   idx%d  项宽 %4dpx = %5.1fdp  %s" %
                  (k, w, px2dp(w, dens), "完整" if ok else "被视口切掉（不进完整数）"))
        missing = 4 - sum(1 for k, _ in f_pairs(f) if f[k][1])
        print("   chip 盒 %4dpx = %5.1fdp ｜ 需补 %d 条（按 2 字项中位 %.1fdp 计）"
              % (f["chip"], chip_dp, missing, item2_dp))
        pe = int(round(PAD_END_DP * dens / 160.0))
        print("   → 第 4 条完整可见的右沿 = %dpx ｜ 距屏宽 %d 缺 %dpx ｜ 距采样右界 %d 缺 %dpx"
              % (edge_px, args.panel_w, max(0, edge_px - args.panel_w),
                 args.panel_w - pe, max(0, edge_px + pe - args.panel_w)))
        print("   → 所需逻辑宽度（含根 padding 8+6dp）= %.1f dp" % need_dp)

    long_frames = {k for k, f in frames.items()
                   if any(f[i][0] > SHORT_ITEM_MAX_PX + 40 for i in f
                          if isinstance(i, int) and f[i][1])}
    hard = [v for k, v in budgets.items() if k not in long_frames]
    t_hard = max(hard)
    print("\n## 分档下限：档1（要求完整 ≥4）= %.1f dp → 规格取 **%d dp**" % (t_hard, int(t_hard) + 1))
    print("   长词帧（含 ≥4 字词条，%s）= %.1f dp，按 AC-10b 只记录、不进硬门限"
          % (sorted("%s@%d" % k for k in long_frames), max([v for k, v in budgets.items() if k in long_frames] or [0])))

    print("\n## 判档：逻辑宽度 = 屏宽 px ÷ (density/160)；下限已含根 padding 8+6dp")
    print("   %-16s %9s %9s %10s %9s %s" % ("几何", "逻辑宽dp", "逻辑高dp", "余量dp", "AC-10a", "说明"))
    margins = {}
    for geom in args.geom.split(","):
        size, _, dens = geom.partition(":")
        w_px, _, h_px = size.partition("x")
        d = float(dens) / 160.0
        w_dp, h_dp = int(w_px) / d, int(h_px) / d
        m = w_dp - t_hard
        margins[geom] = m
        print("   %-16s %9.1f %9.1f %+10.1f %9s %s" %
              (geom, w_dp, h_dp, m, "档1 ≥4" if m >= 0 else "档2 ≥3",
              "目标真机 ALN-AL00" if geom == "1260x2720:520" else
              ("第六轮被测档" if geom == args.tier else "")))

    tier_dens = int(args.tier.partition(":")[2])
    tier_frames = {k: v for k, v in frames.items() if k[1] == tier_dens}
    if not tier_frames:
        print("\n## 出路可达性：证据里没有 %s 档，跳过" % args.tier)
        return 0
    gap = -margins.get(args.tier, 0.0)
    print("\n## 四条出路的可达性（被测档 %s，缺 %.1f dp；全部由上面的预算现算）"
          % (args.tier, gap))

    # ① 给 chip 最有利的取法：所有帧里能省下的最大宽度，代价是组合码可读位数下降
    best = None
    for (code, dens), f in tier_frames.items():
        chip_dp = px2dp(f["chip"], dens)
        per_letter = (chip_dp - CHIP_PAD_DP) / len(code)
        floor = CHIP_PAD_DP + 4 * per_letter          # 4 位 = 五笔满码
        if best is None or chip_dp - floor > best[0]:
            best = (chip_dp - floor, chip_dp, floor, per_letter, code)
    save, chip_dp, chip_floor, per_letter, code = best
    print("   ① 缩 chip：最有利的帧 = %s（chip %.1f dp / %d 位，扣掉 12dp 内 padding 后 %.2f dp/字母）"
          % (code, chip_dp, len(code), per_letter))
    print("      保住 4 位满码的下限 = %.1f dp → 最多省 %.1f dp < 缺 %.1f dp → %s"
          % (chip_floor, save, gap, "仍不可达" if save < gap else "可达"))
    print("      要补齐 %.1f dp 得把 chip 压到 %.1f dp（文本 %.1f dp ≈ %.1f 位）→ 组合码截断，不可接受"
          % (gap, chip_dp - gap, chip_dp - gap - CHIP_PAD_DP, (chip_dp - gap - CHIP_PAD_DP) / per_letter))

    _, _, _, _, item2_dp = budget(tier_frames[max(tier_frames)], tier_dens, args.panel_w)
    all_short = PAD_START_DP + px2dp(max(f["chip"] for f in tier_frames.values()), tier_dens) \
        + 4 * item2_dp + PAD_END_DP
    avail = t_hard + margins[args.tier]
    print("   ② 放宽词长到全 2 字：需 %.1f dp，可用 %.1f dp → %s"
          % (all_short, avail, "仍不可达" if all_short > avail else "可达"))
    print("   ③ 判据分档（档2 = 完整 ≥3 + 溢出可见）：不需要额外宽度 → 可达，属判据让步，须人类签字")
    print("   ④ 动恒高 46dp：横向增益 0 dp（高度不换宽度）→ 不可达，且同时打破 AC-1 与 pp")
    return 0


if __name__ == "__main__":
    sys.exit(main())
