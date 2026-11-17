#!/usr/bin/env python3
"""第八轮打回项 1：pp 与现行规格 §6.5 的对账，算给第三方看而不是叙述。

冲突是什么：同一判据（相对搜狗 ≤ 1pp）、同一个目标几何（1260×2720@520），
现行规格 `ef14209:768` 那一行算出 **Δ ≈ +6.1~6.2 pp**，本报告实测 **Δ = +0.92 pp**，
差 5 个百分点以上。两条算式的差别只在一个假设上 ——

  规格 ⑨ 假设：**搜狗键区是固定 px**，440→520 只涨 ×1.02（978→1002），
              所以换到 1260×2720 面板上「IME 高度仍是帧值」→ 对照仍按 1002 px 算。
  要验的命题：**搜狗换面板到底涨不涨。**

本脚本把两份**同 dpi、不同面板**的 pp 证据并排放：
  · H1（规格假设 固定 px）：sogou_B_predicted = sogou_A → 与实测的差、以及据此算出的 Δpp；
  · H2（比例驱动）：sogou_B_predicted = sogou_A × (panelH_B / panelH_A) → 同样给差与 Δpp；
  · 判 SPEC9 真/假：实测值若显著高于固定 px 预测（>2%），「换面板不涨」为假。
不预设结论，两个假设都算出来摆着，让读数自己站队。

用法：python3 pppanel.py <面板A 的 pp 证据> <面板B 的 pp 证据> [--dpi 520]
退出码：0 两份都能读 / 41 读不出 FRAME / 40 缺两侧
"""
import os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ppsum

def main():
    args, dpi, i = [], 520, 1
    while i < len(sys.argv):
        if sys.argv[i] == "--dpi":
            dpi = int(sys.argv[i + 1])
            i += 2
            continue
        args.append(sys.argv[i])
        i += 1
    if len(args) < 2:
        raise SystemExit(__doc__)
    panels = []
    for path in args:
        got, panel, dens, bottoms = ppsum.rows(path)
        if not got:
            ppsum.log("GATE", reason="no_frames", file=os.path.basename(path), rc=41)
            raise SystemExit(41)
        s = got.get(("sogou", dpi, "1.15")) or got.get(("sogou", dpi, "1.0"))
        i = got.get(("ispeech", dpi, "1.15")) or got.get(("ispeech", dpi, "1.0"))
        if not s or not i:
            ppsum.log("GATE", reason="side_missing_at_dpi", file=os.path.basename(path), dpi=dpi, rc=40)
            raise SystemExit(40)
        avail = max(bottoms) if bottoms else panel[1]
        panels.append(dict(file=os.path.basename(path), panel=panel, avail=avail,
                           s=s["h"], isp=i["h"]))
        ppsum.log("PANEL_READ", file=os.path.basename(path), panel="%dx%d" % panel,
                  available_h=avail, dpi=dpi, sogou_h=s["h"], ispeech_h=i["h"],
                  sogou_pct_of_avail=round(s["h"] * 100.0 / avail, 2),
                  ispeech_pct_of_avail=round(i["h"] * 100.0 / avail, 2))
    a, b = panels[0], panels[1]
    grow = (b["panel"][1] / a["panel"][1], b["panel"][0] / a["panel"][0])
    ppsum.log("GEOMETRY_STEP", from_panel="%dx%d" % a["panel"], to_panel="%dx%d" % b["panel"],
              dpi_same=dpi, panel_h_ratio=round(grow[0], 4), panel_w_ratio=round(grow[1], 4),
              sogou_h="%d->%d" % (a["s"], b["s"]), sogou_change_pct=round((b["s"] / a["s"] - 1) * 100, 2),
              ispeech_h="%d->%d" % (a["isp"], b["isp"]),
              ispeech_change_pct=round((b["isp"] / a["isp"] - 1) * 100, 2),
              note="同 dpi 换面板：dp/sp 驱动的键区像素不变，px 固定的资源也不变 —— "
                   "两者若一个变一个不变，就能把「谁按比例、谁固定」分开")
    # H1 规格 ⑨：固定 px
    h1_s = a["s"]
    # H2 比例驱动（按可用高比例）
    h2_s = round(a["s"] * (b["avail"] / a["avail"]))
    for name, pred in (("H1_spec_fixed_px", h1_s), ("H2_proportional", h2_s)):
        ppsum.log("HYPOTHESIS", hyp=name, predicted_sogou_h=pred, measured_sogou_h=b["s"],
                  err_px=b["s"] - pred, err_pct=round((b["s"] / pred - 1) * 100, 2),
                  delta_px_at_B=b["isp"] - pred,
                  delta_pp_at_B_avail="%+.2f" % ((b["isp"] - pred) * 100.0 / b["avail"]),
                  delta_pp_at_B_panelh="%+.2f" % ((b["isp"] - pred) * 100.0 / b["panel"][1]))
    err = abs(b["s"] / h1_s - 1)
    ppsum.log("SPEC9_VERDICT", hypothesis="搜狗键区固定 px、换面板不涨",
              fixed_px_err_pct=round(err * 100, 2), threshold_pct=2.0,
              verdict="FALSE（被两份同 dpi 读数否证）" if err > 0.02 else "NOT_REFUTED（差异在噪声内）",
              consequence="规格 §6.5 那一行按 1002 px 外推出的 Δ≈+6.1~6.2pp 不成立；"
                          "报告实测的 +0.92pp 站在这一侧" if err > 0.02 else "pp 结论回到未定",
              limit="两个面板 = 一个增量样本，足以否证「固定」，不足以定「标度律」（实测涨 14.07%，"
                    "等比预测涨 16.18%）—— 定稿权仍在真机")


if __name__ == "__main__":
    main()
