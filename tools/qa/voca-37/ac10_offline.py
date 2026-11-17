#!/usr/bin/env python3
"""第六轮（打回项 2/3）：对**已交付的帧**离线重算 AC-10，不占设备、不需要 adb。

为什么要有这个脚本：第五轮的闸门把 `MIN_CAND` 定成 4，等于把 AC-10a 的**结论**当成了
**前置条件**（裁决 §9.2），于是 `dashu@520dpi·fs1.15` 那一帧停在
`GATE reason=cand_low rc=13`，规格 AC-10b 要求「只记录不判」的逐项读数在报告里是空的。
闸门解耦后同一批帧必须能重新出数 —— 而且必须与设备当场跑**同形**，否则第三人拿到两份
不同形状的读数。所以判定式只有一份实现：`band.ac10_rows`，本脚本与 `fs115.py` 共用它。

第二条用处：本脚本是「读数 ↔ 帧」的绑定证明。报告 §3.1 引用的每一个数字都必须能用
交付在 PR 里的 PNG 重算出来，而不是只在设备 stdout 里出现过一次。

口径边界（不许越，越了就是冒充重测）：
  · Given ①「chip 文本 == 所敲串」在离线帧上**读不出来** —— chip 是像素，没有独立可读文本；
    它在设备跑里的证据是 `fs115.py` 的宿主回读断言（`drive.tap_keys` → `focused_text()`，
    不符则 `GATE reason=readback_mismatch rc=10`，排在一切读数之前）。离线只引那份出处。
  · Given ③「候选池 ≥ 8」同理：离线没有 `=` 键滚动序列，量不出位移 →
    `given3_pool_at_least=carried_from_device`。第五轮该档被旧闸门挡在池取证之前，
    **这个数在第五轮交付里确实不存在**；第六轮已在同密度设备当场补到
    （`ev_r6_ac10_decoupled.txt` 的 `POOL code=dashu … pool=13`），本脚本只标出处不冒称。
  · Given ②「候选行在位」= `cand_count ≥ 1`，这个**能**从帧里读（`CandidateView.kt:76-77`
    在列表为空时把 scrollView 置 GONE，GONE 不产生像素），所以离线照判。
  · A 包（`c524351`）440 档的组合态帧已被 B 包同名覆盖（§13.5 那起事故），所以 440 只有
    `r5u_*` = B 包；520 档 A/B 两包帧都在，正好做交叉验证。

用法：python3 ac10_offline.py [帧名片段…]     # 不带参数 = 跑内置的第六轮交付帧清单
退出码恒 0（本脚本不出门禁结论，出门禁看 `AC10 verdict=`）；帧缺文件 → GATE + rc=20。
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import band
import drive as D

SPEC_GIVEN = "8324b03:525"     # AC-10 的三条 Given，与 fs115.py 同锚
NEED_POOL = 8
# 候选带顶边 = 2340 − 键盘总高（各包 kb_h 见对应 evidence 文件的 RESULT/FRAME 行）
#   A 包 c524351：440→988→1352｜520→1168→1172      B 包 cffc0cd：440→952→1388｜520→1128→1212
PKG = {"a6": dict(top={"520": 1172}, kb_h={"520": 1168}, apk="c524351",
                  src="ev_r6_ac10_decoupled.txt"),
       "r5u": dict(top={"440": 1388, "520": 1212}, kb_h={"440": 952, "520": 1128}, apk="cffc0cd",
                   src="ev_r5u_520.txt / ev_r5u_440.txt")}
FRAMES = ["a6_520_fs1.15_shijian", "a6_520_fs1.15_dashu",
          "r5u_440_fs1.0_shijian", "r5u_440_fs1.0_dashu",
          "r5u_440_fs1.15_shijian", "r5u_440_fs1.15_dashu",
          "r5u_520_fs1.15_shijian", "r5u_520_fs1.15_dashu"]


def one(name):
    pkt, dens, fspart, code = name.split("_")
    fs = fspart[2:]
    p = PKG[pkt]
    # A 包那两帧是 fs115.py 的 label 机制带前缀 r5_ 存的文件名（第六轮才补上 label）
    base = "r5_%s" % name if pkt == "a6" else name
    png = os.path.join(D.Q, "shots", "%s_comp.png" % base)
    if not os.path.exists(png):
        D.log("GATE", frame=name, reason="frame_not_in_pr", rc=20)
        return 20
    den = int(dens) / 160.0
    a = band.analyze(png, top=p["top"][dens], band_px=int(round(46 * den)), dpi=int(dens))
    if not a["ok"]:
        D.log("GATE", code=code, dens=dens, fs=fs, frame=name, reason=a["reason"], rc=11,
              note="no_chip_box_offline")
        return 11
    D.log("FRAME", code=code, dens=dens, fs=fs, frame=name, pkg=p["apk"], png=os.path.basename(png),
          kb_h=p["kb_h"][dens], band_top=p["top"][dens], given1_and_given3_source=p["src"])
    D.log("GATES", dens=dens, fs=fs, code=code, spec_anchor=SPEC_GIVEN, frame=name,
          given1_chip_equals_code="carried_from_device", given1_readback="see_" + p["src"],
          given2_candidate_row_present=a["cand_count"] >= 1, cand_count=a["cand_count"],
          given3_pool_at_least="carried_from_device", need_pool=NEED_POOL,
          chip_box_ok=True, chip_right=a["chip_right"], x0=a["first_item_left"])
    g = [it for it in a["items"] if it["ink_top"] is not None]
    if g:
        D.log("INK", dens=dens, fs=fs, code=code, kind="comp", frame=name, cand_count=a["cand_count"],
              x0=a["first_item_left"], x_end=a["x_end"],
              ink_top=min(it["ink_top"] for it in g), ink_bot=max(it["ink_bot"] for it in g),
              inner_top=a["inner_top"], inner_bot=a["inner_bot"], band_inner=a["band_inner"],
              top_pad=min(it["ink_top"] for it in g) - a["inner_top"],
              bot_pad=a["inner_bot"] - 1 - max(it["ink_bot"] for it in g),
              tangent_items=sum(1 for it in g if it["tangent_top"] or it["tangent_bot"]))
    for it in a["items"]:
        D.log("CAND", code=code, dens=dens, fs=fs, frame=name, idx=it["idx"], x0=it["item_left"],
              x1=it["item_right"], glyph=[it["glyph_left"], it["glyph_right"]],
              glyph_w_dp=round((it["glyph_right"] - it["glyph_left"] + 1) / den, 1),
              ink_top=it["ink_top"], ink_bot=it["ink_bot"], ink_h=it["ink_h"], pitch=it["pitch"],
              badge=it["badge"], badge_clipped=it["badge_clipped"],
              tangent_top=it["tangent_top"], tangent_bot=it["tangent_bot"],
              fully_visible=it["fully_visible"], complete_screen=it["complete_screen"])
    ac10, ac10b = band.ac10_rows(a, den)
    D.log("AC10", code=code, dens=dens, fs=fs, frame=name, offline=True, **ac10)
    D.log("AC10b", code=code, dens=dens, fs=fs, frame=name, offline=True, **ac10b)
    return 0


if __name__ == "__main__":
    want = sys.argv[1:]
    rc = 0
    for n in FRAMES:
        if want and not any(w in n for w in want):
            continue
        rc = max(rc, one(n))
    raise SystemExit(rc)
