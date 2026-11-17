#!/usr/bin/env python3
"""第四轮 §3「恒高 Δ」与 §4「截字」两张表的作废自查（VOCA-47 打回项 1 要求的自检动作）。

裁决 `VOCA-47-acceptance-ruling.md` §1.2 要求：交付帧必须按新口径重扫一遍候选区列，
**如果 chip 右侧没有墨迹，那两张表量的就是 chip 本身**，读数一律作废。
本脚本对第四轮留在 `shots/` 的三帧，同时算两套口径：

  * 旧口径 `old_ink_extent`：整条带宽（含左侧 chip）一起打 —— 第四轮 `ink_extent(x0=16..1064)`
    的复算；行的换算用当场 ime inset 顶边（440→1352、520→1172，= 2340 − 键盘高）。
  * 新口径 `band.analyze`：先定位 chip，再只在 chip 右侧按角标切候选项。

结论就是两行之差：旧口径复算出的墨迹行 / 第四轮记载行都对得上，但新口径
`cand_count` 不足 4（前两帧 = 0）→ 旧读数的墨迹全部来自 chip 盒，量的不是候选词，
§3 的 Δ 与 §4 的 ink_top/bot 因此作废，第五轮按 fs115.py 重测。

注：第四轮的墨迹窗口是「带高 ±1px」（其 `band_inner` = 124 @440 / 148 @520），
新口径按 `candidate_view.xml` 的 paddingTop/Bottom=4dp 收口 → 行号会有 10 来像素的系统差，
本脚本按各自口径复算，不做对齐。用法：python3 voidcheck.py
退出码：0 = 三帧全部低于闸门（作废结论成立）/ 22 = 有帧在新口径下确实出了 ≥4 条候选，
作废结论不成立，得回去复核裁决。
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import band
from PIL import Image

# (帧, 路径, dpi, 裁剪帧 0 行对应的全帧 y, 第四轮 §4 记载值)
FRAMES = [
    ("440/fs1.0", "shots/f440_1.0_c_cand.png", 440, 1352, "ink 1400..1441 (42px) top47/bot35"),
    ("440/fs1.15", "shots/f440_1.15_c_cand.png", 440, 1352, "ink 1397..1445 (49px) top44/bot31"),
    ("520/fs1.15", "shots/f520_1.15_c_cand.png", 520, 1172, "ink 1185..1284 (100px) top12/bot36"),
]
BAND_PX = {440: 126, 520: 149}          # candidate_view.xml layout_height=46dp
MIN_CAND = 4                            # 裁决 §6-1：< 4 条可见候选 = chip-only 伪装态


def old_ink_extent(png, band_px, x0=16, x1=1064, thr=45):
    """第四轮窗口：y = 带高 ±1px，x = 整条带宽。返回墨迹像素盒（裁剪帧坐标）。"""
    px = Image.open(png).convert("RGB").load()
    rows = range(1, band_px - 1)
    xs = [x for x in range(x0, min(x1, 1080))
          if sum(1 for y in rows if band.chd(px[x, y], band.BG) > thr) >= 2]
    if not xs:
        return None
    ys = [y for y in rows if any(band.chd(px[x, y], band.BG) > thr for x in xs)]
    return dict(left=min(xs), right=max(xs), top=min(ys), bot=max(ys), cols=len(xs))


if __name__ == "__main__":
    res = []
    for name, p, dpi, origin, claim in FRAMES:
        if not os.path.exists(p):
            print("MISSING\tframe=%s\tpath=%s" % (name, p))
            continue
        e = old_ink_extent(p, BAND_PX[dpi])
        a = band.analyze(p, top=0, band_px=BAND_PX[dpi], dpi=dpi)
        chip = a.get("chip") or {}
        # 旧口径的墨迹列最右能超出 chip 右沿多少 = 旧读数里真正属于候选词的部分
        past = 0
        if e and chip:
            past = max(0, e["right"] - chip["right"])
        print("FRAME\t%s\tdpi=%d\told_ink_rows=%d..%d(+origin %d)\told_ink_cols=%d..%d\tcols=%d\tclaim=%s"
              % (name, dpi, (e or {}).get("top", -1) + origin, (e or {}).get("bot", -1) + origin,
                 origin, (e or {}).get("left", -1), (e or {}).get("right", -1),
                 (e or {}).get("cols", 0), claim))
        print("NEW\t%s\tok=%s\treason=%s\tchip_right=%s\tx0=%s\tcand_count=%d\ting_rows_outside_chip_px=%d"
              % (name, a["ok"], a.get("reason") or "-", chip.get("right"),
                 a.get("first_item_left"), a["cand_count"], past))
        verdict = "VOID(量的=chip/退化态)" if a["cand_count"] < MIN_CAND else "OK"
        print("VERDICT\t%s\t%s\tcand_count=%d\tneed=%d" % (name, verdict, a["cand_count"], MIN_CAND))
        res.append((name, a["cand_count"], past, e is not None and chip and e["right"] <= chip["right"]))
    void = [r for r in res if r[1] < MIN_CAND]
    allchip = [r for r in res if r[3]]
    print("SUMMARY\tframes=%d\tbelow_gate(cand<4)=%d\tink_entirely_inside_chip=%s\t"
          "cand_counts=%s" % (len(res), len(void), "/".join(r[0] for r in allchip) or "-",
                              ",".join("%s:%d" % (r[0], r[1]) for r in res)))
    ok = len(void) == len(res) and len(res) == len(FRAMES)
    print("CONCLUSION\t%s" % ("旧 §3 Δ 与 §4 截字读数作废：没有一帧的可见候选达到 4 条，"
                              "其中墨迹完全落在 chip 盒内的帧按新口径一条候选都没有"
                              if ok else "有帧在新口径下出了 ≥4 条候选，作废结论需复核"))
    raise SystemExit(0 if ok else 22)
