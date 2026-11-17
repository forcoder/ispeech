#!/usr/bin/env python3
"""第七轮打回项 2(b)：把 pp 的判定从「人手写在证据文件里的 SUMMARY」变成工具算出来的 SUMMARY。

输入 = ppwin.py 在同一份 stdout 里跑出的两侧 FRAME/PANEL 行（`run_r7e.sh` 的第 2 步）。
本脚本不碰设备，只做算术，所以任何一份历史 pp 证据都能离线重算 —— 判据只有一份：
规格 `0e4eeb0:679`（iSpeech 不比对照高过 1pp）与 `band.TIER_SPLIT_DP`（档界 371.0dp）。

用法：python3 ppsum.py <ppwin 的 stdout 文件> [--panel-px 2720]
退出码：0 两侧都齐 / 40 缺一侧 / 41 FRAME 读不出
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import band

PANEL_RE = re.compile(r"PANEL\t.*?panel=(\d+)x(\d+).*?logic_w_dp=([\d.]+)")
FRAME_RE = re.compile(r"FRAME\ttag=([A-Za-z]+)_(\d+)_([\d.]+)\t.*?h=(\d+)\tpp=([\d.]+)")


def ts():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def log(kind, **kw):
    kw.setdefault("ts", ts())
    print(kind + "\t" + "\t".join("%s=%s" % (k, v) for k, v in kw.items()), flush=True)


def rows(path):
    out = {}
    panel = None
    dens = None
    for ln in open(path, encoding="utf-8", errors="replace"):
        m = PANEL_RE.search(ln)
        if m and panel is None:
            panel = (int(m.group(1)), int(m.group(2)))
        d = re.search(r"dens=(\d+)", ln)
        if d and dens is None:
            dens = int(d.group(1))
        f = FRAME_RE.search(ln)
        if f:
            who, dpi, fs, h, pp = f.group(1).lower(), int(f.group(2)), f.group(3), int(f.group(4)), float(f.group(5))
            out.setdefault((who, dpi, fs), {"h": h, "pp": pp})
    return out, panel, dens


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    path = sys.argv[1]
    got, panel, dens = rows(path)
    if not got:
        raise SystemExit(41)
    log("SRC", file=os.path.basename(path), frames=len(got),
        panel="%sx%s" % panel if panel else "unknown", dens_read=dens)
    sogou = {k[1:]: v for k, v in got.items() if k[0] == "sogou"}
    isp = {k[1:]: v for k, v in got.items() if k[0] == "ispeech"}
    if not sogou or not isp:
        log("GATE", reason="one_side_missing", sogou=len(sogou), ispeech=len(isp), rc=40)
        raise SystemExit(40)
    for key in sorted(set(sogou) & set(isp), reverse=True):
        dpi, fs = key
        s, i = sogou[key], isp[key]
        # 逻辑宽按**该档自己的 dpi** 算，不是读进来时的 dens_now —— 这正是上一版被指出的坑
        lw = round(panel[0] / (dpi / 160.0), 1) if panel else None
        tier = "档1" if lw is None or lw >= band.TIER_SPLIT_DP else "档2"
        dpp = round(i["pp"] - s["pp"], 2)
        verdict = "达标（判据 pp ≤ 对照+1.0pp）" if dpp <= 1.0 + 1e-9 else "不达标"
        log("PPSUM", tier_dpi=dpi, fs=fs, panel="%sx%s" % panel,
            logic_w_dp=lw, tier=tier, tier_split_dp=band.TIER_SPLIT_DP,
            r21_signed=band.R21_SIGNED,
            sogou_h=s["h"], sogou_pp=s["pp"], ispeech_h=i["h"], ispeech_pp=i["pp"],
            delta_px=i["h"] - s["h"], delta_pp="%+.2f" % dpp, verdict=verdict,
            criterion="规格 0e4eeb0:679 / band.TIER_SPLIT_DP")
    h = panel[1] if panel else 0
    dpx = [i["h"] - s["h"] for k, s in sogou.items() for i in [isp[k]] if k in isp]
    if h and dpx:
        d = dpx[0]
        log("PPSUM_SENSITIVITY", formula="delta_pp = delta_px/H*100 = %d/H" % d,
            at_H_2340="%+.2f" % (d / 2340.0 * 100), at_H_this="%+.2f" % (d / float(h) * 100),
            at_H_3200="%+.2f" % (d / 3200.0 * 100),
            note="面板越矮，同样的绝对差摊成越大的 pp —— pp 的定稿权在真机几何")


if __name__ == "__main__":
    main()
