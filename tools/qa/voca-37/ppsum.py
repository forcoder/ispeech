#!/usr/bin/env python3
"""第七轮打回项 2(b)：把 pp 的判定从「人手写在证据文件里的 SUMMARY」变成工具算出来的 SUMMARY。

输入 = ppwin.py 在同一份 stdout 里跑出的两侧 FRAME/PANEL/RAW 行（`run_r7e.sh` 的第 2 步）。
本脚本不碰设备，只做算术，所以任何一份历史 pp 证据都能离线重算 —— 判据只有一份：
规格 `0e4eeb0:48` + `0e4eeb0:689`「相对搜狗 ≤ 1 个百分点」（现行规格 `ef14209` 同两句在 `:49`/`:711`；
第八轮打回项 5 订正 —— 旧版这里写的 `0e4eeb0` 第 679 行是 §5 无障碍的「门禁结论」句，全文不含 pp，
是错锚）与 `band.TIER_SPLIT_DP`（档界 371.0dp）。

**分母按规格钉死的口径来**（`ef14209:767` §6.5 表格「分母（口径钉死）＝可用高，不是物理高」）：
`可用高 = ime inset 帧底所在的那条线`，从同一份证据的 `RAW … InsetsSource type=ime frame=[l,t][r,b]`
里取 `b`。取不到 RAW 就退回物理面板高并把 `available_h_source=` 标成 fallback，不冒充量过。

用法：python3 ppsum.py <ppwin 的 stdout 文件> [--available-h N]
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
# 可用高 = ime inset 帧底（规格 §6.5 口径钉死那一句），不是 wm size 的面板高
FRAME_BOTTOM_RE = re.compile(r"frame=\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]")


def ts():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def log(kind, **kw):
    kw.setdefault("ts", ts())
    print(kind + "\t" + "\t".join("%s=%s" % (k, v) for k, v in kw.items()), flush=True)


def rows(path):
    out = {}
    panel = None
    dens = None
    bottoms = []
    for ln in open(path, encoding="utf-8", errors="replace"):
        m = PANEL_RE.search(ln)
        if m and panel is None:
            panel = (int(m.group(1)), int(m.group(2)))
        d = re.search(r"dens=(\d+)", ln)
        if d and dens is None:
            dens = int(d.group(1))
        if "type=ime" in ln and "frame=" in ln:
            f = FRAME_BOTTOM_RE.search(ln)
            if f:
                bottoms.append(int(f.group(4)))
        f = FRAME_RE.search(ln)
        if f:
            who, dpi, fs, h, pp = f.group(1).lower(), int(f.group(2)), f.group(3), int(f.group(4)), float(f.group(5))
            out.setdefault((who, dpi, fs), {"h": h, "pp": pp})
    return out, panel, dens, bottoms


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    path = sys.argv[1]
    override = int(sys.argv[sys.argv.index("--available-h") + 1]) if "--available-h" in sys.argv else None
    got, panel, dens, bottoms = rows(path)
    if not got:
        raise SystemExit(41)
    log("SRC", file=os.path.basename(path), frames=len(got),
        panel="%sx%s" % panel if panel else "unknown", dens_read=dens)
    sogou = {k[1:]: v for k, v in got.items() if k[0] == "sogou"}
    isp = {k[1:]: v for k, v in got.items() if k[0] == "ispeech"}
    if not sogou or not isp:
        log("GATE", reason="one_side_missing", sogou=len(sogou), ispeech=len(isp), rc=40)
        raise SystemExit(40)
    ph = panel[1] if panel else 0
    if override is not None:
        avail, asrc = override, "cli_override"
    elif bottoms:
        avail, asrc = max(bottoms), "ime_inset_frame_bottom"      # 规格钉死的口径
    else:
        avail, asrc = ph, "fallback_panel_h_no_raw_ime_line"      # 没 RAW 就不冒充量过
    log("DENOM", panel_h=ph, available_h=avail, source=asrc, raw_ime_frames=len(bottoms),
        available_equals_panel_h=(avail == ph),
        note="分母=可用高（ime inset 帧底），规格 §6.5 口径钉死那句；两者相等时是本帧无系统栏占位，"
             "巧合相等也要点名，不能不写")
    for key in sorted(set(sogou) & set(isp), reverse=True):
        dpi, fs = key
        s, i = sogou[key], isp[key]
        # 逻辑宽按**该档自己的 dpi** 算，不是读进来时的 dens_now —— 这正是上一版被指出的坑
        lw = round(panel[0] / (dpi / 160.0), 1) if panel else None
        tier = "档1" if lw is None or lw >= band.TIER_SPLIT_DP else "档2"
        dpp = round((i["h"] - s["h"]) * 100.0 / avail, 2) if avail else round(i["pp"] - s["pp"], 2)
        verdict = "达标（判据 pp ≤ 对照+1.0pp）" if dpp <= 1.0 + 1e-9 else "不达标"
        log("PPSUM", tier_dpi=dpi, fs=fs, panel="%sx%s" % panel,
            logic_w_dp=lw, tier=tier, tier_split_dp=band.TIER_SPLIT_DP,
            r21_signed=band.R21_SIGNED,
            available_h=avail, available_h_source=asrc,
            sogou_h=s["h"], sogou_pp_avail=round(s["h"] * 100.0 / avail, 2), sogou_pp_panelh=s["pp"],
            ispeech_h=i["h"], ispeech_pp_avail=round(i["h"] * 100.0 / avail, 2), ispeech_pp_panelh=i["pp"],
            delta_px=i["h"] - s["h"], delta_pp="%+.2f" % dpp, verdict=verdict,
            criterion="规格 0e4eeb0:48 + :689（现行 ef14209:49 + :711）『相对搜狗 ≤ 1 个百分点』 / band.TIER_SPLIT_DP")
        # 敏感度假设：同一个 Δpx 摊到不同可用高上是多少 pp。**逐档一行**，
        # 不是一行代表整个文件 —— 一份文件里多个档的 Δpx 不同，取 dpx[0] 就是抽签。
        d = i["h"] - s["h"]
        log("PPSUM_SENSITIVITY", tier_dpi=dpi, fs=fs, formula="delta_pp = delta_px/H*100 = %d/H" % d,
            at_H_2340="%+.2f" % (d / 2340.0 * 100), at_H_this="%+.2f" % (d / float(avail) * 100),
            at_H_2660="%+.2f" % (d / 2660.0 * 100),
            at_H_3200="%+.2f" % (d / 3200.0 * 100),
            flip_H_at_1pp=int(d * 100) if d > 0 else None,
            flip_note=("可用高 < %d px 就翻成不达标（Δpp > +1.00）" % int(d * 100)) if d > 0
                      else "Δpx ≤ 0：本包不比对照高，任何分母都达标",
            note="面板越矮，同样的绝对差摊成越大的 pp —— pp 的定稿权在真机几何")


if __name__ == "__main__":
    main()
