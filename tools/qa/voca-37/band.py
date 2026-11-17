#!/usr/bin/env python3
"""候选带像素取证库（VOCA-47 第五轮）：把「chip 墨迹」和「候选词字身」分开量。

为什么需要它：`CandidateView.kt:76-77` 在候选列表为空时把 `scrollView` 置 GONE，
所以「敲了码但一个候选都没出」的帧长得和组合态一样（左侧 chip 还在）。前四轮的
`ink_extent` 在整个带宽上取墨迹，量到的其实只有 chip，恒高与截字两条判据都没落到
候选词上。本模块只认三件事，且每件都给判定理由：

1. chip 盒（`#5B6CFF` 38% 透明度铺底 `(222,224,248)`）→ 采样起点 x0 = 首选高亮盒左沿；
2. 首选高亮盒（同色 51% 透明度 `(213,218,249)`，`CandidateView.kt` 里 index==0 才有）
   → 第一个候选项的左沿，也就是 chip 与候选区的分界；
3. 字身墨迹 = 与本包底色 `#F4F5F7`(244,245,247) 通道差 > thr 的像素（两种铺底色都不算墨迹）。

候选项的右沿取**角标圆片**的右沿（`BADGE_DP=14`，Gravity.TOP|END，实心主题色）——
项间净空 = paddingEnd 12dp + marginEnd 6dp + paddingStart 12dp ≈ 18dp，而项内「字身→角标」
几乎相连（角标定在项右端，压掉右侧 padding），所以按列空隙切分会把整条候选粘成一坨；
改用角标定界。角标的识别不能用主题色密度（选中项字身也是主题色），要用外形：
角标是 badge×badge 的实心连通块，笔画连通块要么盒形不对、要么像素数不够。
"""
from PIL import Image

BG = (244, 245, 247)            # candidate_view.xml:11 android:background="#F4F5F7"（:10 是 layout_height=46dp，:13 是 orientation）
CHIP_BG = (222, 224, 248)       # primaryColor alpha 38 over BG  (CandidateView ALPHA_CODE_BG)
ITEM_BG = (213, 218, 249)       # primaryColor alpha 51 over BG  (CandidateView ALPHA_ITEM_BG)
PRIMARY = (91, 108, 255)        # ispeech_primary

PARENT_PAD_DP = 4               # candidate_view.xml paddingTop/Bottom
PAD_START_DP = 8                # candidate_view.xml paddingStart
PAD_END_DP = 6                  # candidate_view.xml paddingEnd
CODE_PAD_DP = 6                 # candidate_view.xml chip paddingStart/End
MARGIN_END_DP = 6               # CandidateView.itemParams().marginEnd
ITEM_PAD_DP = 12                # CandidateView content setPadding 左右
BADGE_DP = 14                   # CandidateView BADGE_DP
CODE_TEXT_SP = 16               # candidate_view.xml chip textSize
CAND_TEXT_SP = 18               # CandidateView label textSize
W = 1080


def chd(a, b):
    return max(abs(a[0] - b[0]), abs(a[1] - b[1]), abs(a[2] - b[2]))


def _components(pts, w):
    seen = set()
    out = []
    for p in pts:
        if p in seen:
            continue
        stack = [p]
        cur = []
        while stack:
            q = stack.pop()
            if q in seen:
                continue
            seen.add(q)
            cur.append(q)
            x, y = q % w, q // w
            for n in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                m = n[1] * w + n[0]
                if 0 <= n[0] < w and m in pts and m not in seen:
                    stack.append(m)
        out.append(cur)
    return out


def _largest_box(pts, w, min_n=40):
    cs = _components(pts, w)
    if not cs:
        return None
    c = max(cs, key=len)
    if len(c) < min_n:
        return None
    xs = [p % w for p in c]
    ys = [p // w for p in c]
    return dict(left=min(xs), right=max(xs), top=min(ys), bot=max(ys), n=len(c))


def analyze(png, top, band_px, dpi, thr=45, fill_tol=8, tangent_gap=1):
    """返回一帧组合态的候选带结构。top = ime inset 顶边（dumpsys type=ime frame）。

    字段：x0（候选区采样起点）、x_end（采样右界）、items（逐候选读数）、
    cand_count（可见候选项数，含被屏幕右边界切掉的最后一条）、
    full_count（字身完整可见的候选项数）。
    """
    den = dpi / 160.0
    badge = max(6, round(BADGE_DP * den))
    margin = round(MARGIN_END_DP * den)
    inner_top = top + round(PARENT_PAD_DP * den)
    inner_bot = top + band_px - round(PARENT_PAD_DP * den)   # 开区间下界
    x_end = W - round(PAD_END_DP * den)
    rows = list(range(inner_top, inner_bot))
    px = Image.open(png).convert("RGB").load()

    chip_pts, item_pts = set(), set()
    ink_cnt = {}       # 每列墨迹像素数
    for x in range(0, W):
        ink = 0
        for y in rows:
            c = px[x, y]
            if chd(c, BG) > thr:
                ink += 1
            if chd(c, CHIP_BG) <= fill_tol and chd(c, ITEM_BG) > fill_tol:
                chip_pts.add(y * W + x)
            elif chd(c, ITEM_BG) <= fill_tol and chd(c, CHIP_BG) > fill_tol:
                item_pts.add(y * W + x)
        ink_cnt[x] = ink

    chip = _largest_box(chip_pts, W)
    first = _largest_box(item_pts, W)              # 首选高亮盒（index==0 才有，右滚后会滚出屏幕）
    # 采样起点按裁决口径 = chip 右沿 + 1。**不能**取高亮盒左沿：高亮只画在候选列表的第 0 项上，
    # 一旦 `=` 右滚，第 0 项滚出屏幕，就没有 ITEM_BG 了（池取证要用同一口径量滚后的帧）。
    if not chip:
        return dict(ok=False, reason="no_chip_fill", chip=None, first_item_fill=first,
                    inner_top=inner_top, inner_bot=inner_bot, band_inner=inner_bot - inner_top,
                    x_end=x_end, badge_px=badge, cand_count=0, full_count=0,
                    full_count_screen=0, items=[])
    x0 = chip["right"] + 1

    # 角标 = 直径 BADGE_DP 的实心主题色圆片（Gravity.TOP|END），所以「角标右沿 = 项右沿」。
    # 不能用「主题色像素密度」挑它：首选字身也是主题色（`CandidateView` 选中项 textColor=primary），
    # 竖笔画的列密度能追平圆片。改用连通块外形：圆片是**外接盒 = badge×badge 的实心块**
    # （440dpi 实测 38×38、n≈1030；字身笔画块 n≤704 且盒形不对），按外形筛即可。
    prim_pts = {y * W + x for y in rows for x in range(x0, min(W, x_end + badge + 2))
                if chd(px[x, y], PRIMARY) <= 70}
    badges = []
    for c in _components(prim_pts, W):
        xs = [q % W for q in c]
        ys = [q // W for q in c]
        l, r, t, b = min(xs), max(xs), min(ys), max(ys)
        w, h = r - l + 1, b - t + 1
        if abs(w - badge) <= 0.15 * badge and abs(h - badge) <= 0.15 * badge and len(c) >= 0.5 * badge * badge:
            badges.append([l, r])
    badges.sort()

    bounds = [b[1] for b in badges]                # 每项右沿
    ink_x = [x for x in range(x0, W) if ink_cnt[x] >= 2]
    items = []
    prev_right = x0 - 1
    idx = 0
    for right in bounds:
        seg = [x for x in ink_x if prev_right < x <= right]
        if not seg:
            prev_right = right
            continue
        # 字身列 = 该段里非角标的墨迹列（角标盒左沿之后不算字身）
        bx0 = badges[[b[1] for b in badges].index(right)][0]
        glyph_x = [x for x in seg if x < bx0] or seg
        gtop = gbot = None
        for y in rows:
            if any(chd(px[x, y], BG) > thr for x in glyph_x):
                if gtop is None:
                    gtop = y
                gbot = y
        items.append(dict(idx=idx, item_left=prev_right + 1, item_right=right,
                          badge=[bx0, right], glyph_left=min(glyph_x), glyph_right=max(glyph_x),
                          ink_top=gtop, ink_bot=gbot,
                          ink_h=None if gtop is None else gbot - gtop + 1,
                          badge_clipped=right > x_end - 1,
                          tangent_top=gtop is not None and gtop <= inner_top + tangent_gap - 1,
                          tangent_bot=gbot is not None and gbot >= inner_bot - tangent_gap,
                          ellipsize=False))
        prev_right = right
        idx += 1
    # 最后一个角标之后仍有墨迹 → 被屏幕右边界切掉的候选项（可见但不完整）
    tail = [x for x in ink_x if x > prev_right]
    if tail:
        gtop = gbot = None
        for y in rows:
            if any(chd(px[x, y], BG) > thr for x in tail):
                if gtop is None:
                    gtop = y
                gbot = y
        items.append(dict(idx=idx, item_left=prev_right + 1, item_right=max(tail),
                          badge=None, glyph_left=min(tail), glyph_right=max(tail),
                          ink_top=gtop, ink_bot=gbot,
                          ink_h=None if gtop is None else gbot - gtop + 1,
                          pitch=None, badge_clipped=True,
                          tangent_top=gtop is not None and gtop <= inner_top + tangent_gap - 1,
                          tangent_bot=gbot is not None and gbot >= inner_bot - tangent_gap,
                          ellipsize=False))
    for i, it in enumerate(items):
        it["fully_visible"] = (not it["badge_clipped"]) and it["ink_top"] is not None \
            and not it["tangent_top"] and not it["tangent_bot"]
        # 第二个口径：右界取**屏幕右边**（1080）而不是规格采样区（1080 − paddingEnd）。
        # 只有「角标在位」的项才可能在这个口径下算完整 —— 尾巴项的右沿是屏幕边界不是项边界，
        # 内容仍然被切断。两口径结论不一致的那一档由调用方标 boundary=True 交洞察BA 复核。
        it["complete_screen"] = it["badge"] is not None and it["item_right"] < W \
            and it["ink_top"] is not None and not it["tangent_top"] and not it["tangent_bot"]
        prev = items[i - 1] if i else None
        # pitch 只在两个角标都在位时才有意义：没角标的尾巴项右沿是屏幕边界，不是项边界
        it["pitch"] = None if not prev or not prev["badge"] or not it["badge"] \
            else it["item_right"] - prev["item_right"]

    return dict(ok=True, chip=chip, chip_right=chip["right"], first_item_fill=first,
                chip_gate=(not first) or first["left"] == chip["right"] + 1,
                first_item_left=x0, x_end=x_end, inner_top=inner_top, inner_bot=inner_bot,
                band_inner=inner_bot - inner_top, badge_px=badge, margin_px=margin,
                cand_count=len(items), full_count=sum(1 for it in items if it["fully_visible"]),
                full_count_screen=sum(1 for it in items if it["complete_screen"]),
                items=items)


def pitch_stats(a, den):
    """逐项 pitch 的中位数与最大/最小，给 AC-10b 入账用。"""
    ps = [it["pitch"] for it in a["items"] if it["pitch"]]
    if not ps:
        return dict(n=0)
    ps.sort()
    return dict(n=len(ps), min=ps[0], med=ps[len(ps) // 2], max=ps[-1],
                med_dp=round(ps[len(ps) // 2] / den, 1))


NEED_FULL = 4          # spec-AC-10a 硬门限（不是闸门，裁决 §9.2-2）
GIVEN_POOL = 8         # AC-10 Given ③


def ac10_rows(a, den, need=NEED_FULL):
    """AC-10a 的 verdict + AC-10b 的逐项记录，两个口径同行出（裁决 §9.2-3 / 风险 3(a)）。

    放这里而不是写在 fs115.py 里：设备跑与离线复算必须共用同一个判定式，
    否则「同一帧两次跑出不同 verdict」就成了读不出来的分歧。
    两个口径：
      · `full_count`   —— 右界 = 规格采样区 `x_end = 1080 − paddingEnd`（`:529` 写死），verdict 以它为准；
      · `full_count_screen` —— 右界 = 屏幕右边 1080。内缩那 6dp 会把落在里面的完整候选判成不完整，
        两口径不一致的那一档打 `boundary=True` 交洞察BA 复核，**不许测试自己挑一个**。
    """
    spec_v = "PASS" if a["full_count"] >= need else "FAIL"
    scr_v = "PASS" if a["full_count_screen"] >= need else "FAIL"
    clipped = [it["idx"] for it in a["items"] if not it["fully_visible"]]
    if a["full_count"] >= need:
        reason = "meets_threshold"
    elif clipped:
        reason = "viewport_clip"          # 渲染出来的项里有被切的
    else:
        reason = "long_word_budget"       # 渲染项全完整，但一屏只放得下 cand_count < need 条
    gw = sorted((it["glyph_right"] - it["glyph_left"] + 1) / den
                for it in a["items"] if it["ink_top"] is not None)
    ac10 = dict(full_count=a["full_count"], verdict=spec_v, need=need,
                gauge="spec_sampling_x_end=%d" % a["x_end"],
                full_count_screen=a["full_count_screen"], verdict_screen=scr_v,
                gauge_screen="x_end=%d" % W,
                boundary=(spec_v != scr_v), cand_count=a["cand_count"],
                pitch=pitch_stats(a, den),
                glyph_w_dp_med=round(gw[len(gw) // 2], 1) if gw else None)
    ac10b = dict(cand_count=a["cand_count"], full_count=a["full_count"], need=need,
                 recorded=True, reason=reason, clipped_idx=clipped,
                 pitch=pitch_stats(a, den))
    return ac10, ac10b


def count_items_content_space(frames, tol=20, min_new_offset=40):
    """多帧（每帧带累计 scrollX 偏移）里的候选项投到内容坐标系去重 → 池内条数下界。

    frames: [(offset_px, analyze() 结果), ...]，offset = 该内容相对首帧左端的累计位移。
    按「候选项右沿（= 角标右沿）」聚簇去重；tol 取半个最小项宽（1 字词 @440dpi 约 131px）。
    同一档位（偏移相差 < min_new_offset）只取第一帧：实测 `=` 按下去但内容没动时，
    候选池可能刚好异步补了词（haizi 那组因此从 5 条被数成 9 条），同档多帧会把「重排」
    当成「新露出的候选」，所以按档位收样而不是按帧收样。
    """
    seen, used_off = [], []
    for off, a in sorted(frames, key=lambda t: t[0]):
        if any(abs(off - u) < min_new_offset for u in used_off):
            continue
        used_off.append(off)
        for it in a.get("items", []):
            cx = it["item_right"] + off
            if not any(abs(cx - s) <= tol for s in seen):
                seen.append(cx)
    return len(sorted(seen))
