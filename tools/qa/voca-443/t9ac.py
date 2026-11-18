#!/usr/bin/env python3
"""VOCA-443 取证：规格 §9.8 ③④ —— T9 态 `-`/`=` 横滚等价性（AC-a1）+ 上屏后偏移复位（AC-a2）。

被测面：main `fa57a07` 的产品代码（本分支基线 aa4e236 未改产品代码，只加了 dist 产物）。
口径：规格 `28c1cac` §2.3 G5 / AC-4（`=`→+198±6px、`-`→−198±6px @440dpi，72dp/档），
位移 = drive.shift() 列剖面互相关（正 = 内容左移 = 视口向右），残差 err ≤ 5 才可判。

与 VOCA-47 工装的三处必要差异（都在闸门里自证，不靠口头声明）：
1. T9 组合态 chip 恒 GONE：renderCandidateView() 的 code = statusText ?: buffer
   （IspeechImeService.kt:1131-1135），数字串在 t9Digits 不在 buffer。所以「chip 回读」
   在 T9 态的等价物 = 宿主组合文本回读（refreshT9Candidates 用同一个 t9Digits 同时写
   setComposingText 与 codeOverride，IspeechImeService.kt:849-850）。闸门断言
   focused_text == 所敲数字串，不成立非零退出。
2. T9 数字键是软键（handleSoftKey 的 T9 拦截，IspeechImeService.kt:434-441），硬件数字键
   1-9 走 onKeyDown:590 的选候选分支、不进 T9 缓冲 —— 所以数字串必须点软键注入，
   键位从当场截图的键帽列段反推（键帽 = 非键盘底色 #E4E7EC 的连通列段），不写死像素。
3. 候选池 ≥ 8 的计数沿用 band.count_items_content_space（`=` 扫到右端钳制，逐档
   analyze 后按项右沿投到内容坐标系去重），T9 帧用 chip_required=False。

AC-a2 判据（本单第一步交付的可判口径，Then 按 issue 原文「偏移=0」测，UX 裁定另走）：
  上屏后候选带偏移 = 0 的判定 = 左边界探针：按一次 `-` 候选带逐像素不动（到界钳制，
  规格 G3）且再按一次 `=` 立即 +198±6（不是卡死）。候选池 ≤1 时探针无效（`-` 会放行
  给宿主，IspeechImeService.kt:602），改静态判据：首选高亮盒左沿 == 候选区左沿。

闸门退出码（前提不成立拒绝出数，沿用 VOCA-47 第四轮形状）：
  rc=10 host_not_ready   宿主输入框起不来
  rc=11 frame_none       读不到 type=ime frame
  rc=12 readback         宿主组合文本 != 所敲串（T9 缓冲没进去 / 键位点错）
  rc=13 bg_low           候选带底色命中率 < 50%（键盘没画在 inset 上）
  rc=14 pool_low         候选池 < 8
  rc=15 nav_fail         布局导航失败（键帽列段数与布局定义不符）
  rc=16 not_at_origin    池取证后回不到左端
  rc=17 ime_not_ours     当前 IME 不是 com.ispeech.ime
  rc=19 ident            设备上装的包不是这份构建产物
行为判定（PASS/FAIL）走 RESULT 行，rc=0 —— 闸门拦「量不到」，不拦「量出来是坏」。

用法：python3 t9ac.py <apk> [--digits 74] [--k 5] [--dpi 440] [--leg t9|wubi|all]
"""
import hashlib, os, subprocess, sys, time, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
import band as B
from PIL import Image, ImageChops

A = sys.argv[1:]
POS = [a for a in A if not a.startswith("--")]
OPT, i = {}, 0
while i < len(A):
    if A[i].startswith("--"):
        if "=" in A[i]:
            k, v = A[i][2:].split("=", 1)
        else:
            k, v = A[i][2:], (A[i + 1] if i + 1 < len(A) and not A[i + 1].startswith("--") else "1")
            i += 1
        OPT[k] = v
    i += 1
APK = POS[0]
DIGITS = OPT.get("digits", "74")
K = int(OPT.get("k", 5))
DPI = int(OPT.get("dpi", 440))
LEG = OPT.get("leg", "all")
HEAD = "aa4e236"          # 被测分支基线；产品代码 == main fa57a07
DEN = DPI / 160.0
STEP_PX = round(72 * DEN)          # 198 @440dpi
TOL_PX = 6
ERR_MAX = 5.0
BAND_PX = round(46 * DEN)          # candidate_view.xml layout_height=46dp
BAND_OFF = (1, BAND_PX - 1)        # 与 ac47.py 同口径：避开上下描边
BG_MIN_PCT = 50.0
MIN_POOL = 8
ROOT_BG = (228, 231, 236)          # SoftKeyboardView init: #E4E7EC
KEY_H_PX = round(52 * DEN)         # DEFAULT_KEY_HEIGHT_DP=52（main 未合 PR#18 的 46）
RC = dict(host_not_ready=10, frame_none=11, readback=12, bg_low=13, pool_low=14,
          nav_fail=15, not_at_origin=16, ime_not_ours=17, ident=19)


def gate(reason, **kw):
    if "reason" in kw:
        kw["diag_reason"] = kw.pop("reason")
    D.log("GATE", reason=reason, rc=RC.get(reason, 1), **kw)
    sys.stdout.flush()
    raise SystemExit(RC.get(reason, 1))


def cur_ime():
    return D.shell("settings get secure default_input_method").strip()


def ident_once(apk):
    p = os.path.join(D.Q, "_ident.apk")
    r = subprocess.run([D.ADB, "-s", D.S, "shell", "pm", "path", "com.ispeech.ime"],
                       capture_output=True, text=True)
    if "package:" not in r.stdout:
        return False
    subprocess.run([D.ADB, "-s", D.S, "pull", r.stdout.strip().split("package:")[1], p],
                   capture_output=True)

    def ent(path):
        z = zipfile.ZipFile(path)
        return {n: hashlib.sha256(z.read(n)).hexdigest()[:12] for n in sorted(z.namelist())
                if (n.startswith("classes") and n.endswith(".dex")) or n == "resources.arsc"}
    return ent(apk) == ent(p)


def shot(name):
    return D.shot(name)


def band_shift(pa, pb, x0):
    """候选带互相关，x0 起采（避开 chip / 首选高亮盒左侧的静态区）。"""
    top, bot, h = D.ime_frame()
    if top is None:
        return None, None
    s, e = D.shift(pa, pb, top + BAND_OFF[0], top + BAND_OFF[1],
                   x0=x0, x1=Image.open(pa).size[0] - 4)
    return s, (None if e is None else round(e, 2))


def verdict(s, e, expect):
    if s is None or e is None or e > ERR_MAX:
        return "unverifiable(err>%s)" % ERR_MAX
    return "PASS" if abs(s - expect) <= TOL_PX else "FAIL"


def analyze(png, chip_required):
    top, bot, h = D.ime_frame()
    if top is None:
        return None
    return B.analyze(png, top, BAND_PX + 1, DPI, chip_required=chip_required)


def band_bg_pct(png):
    top, bot, h = D.ime_frame()
    if top is None:
        return 0.0
    px = Image.open(png).convert("RGB").load()
    W = Image.open(png).size[0]
    hit = tot = 0
    for y in range(top + 2, top + BAND_PX - 2, 3):
        for x in range(0, W, 4):
            if B.chd(px[x, y], B.BG) <= 6:
                hit += 1
            tot += 1
    return round(100.0 * hit / tot, 1) if tot else 0.0


def diff_bbox(pa, pb):
    ia, ib = Image.open(pa).convert("RGB"), Image.open(pb).convert("RGB")
    top, bot, h = D.ime_frame()
    if top is None:
        return "no_frame"
    ca = ia.crop((0, top + BAND_OFF[0], ia.size[0], top + BAND_OFF[1]))
    cb = ib.crop((0, top + BAND_OFF[0], ib.size[0], top + BAND_OFF[1]))
    g = ImageChops.difference(ca, cb).convert("L").point(lambda v: 255 if v > 24 else 0)
    return g.getbbox()


# ── 键帽几何：从当场截图反推，不写死像素 ──────────────────────────────

def key_rows(png, y_start=None):
    """键盘区里「非底色覆盖率 > 50%」的横向带 = 键行。
    高度窗 [0.75, 1.35]×键高：滤掉页码条（22dp）与工具栏（34dp）；空态候选条恒高 46dp
    落在窗内，由调用方按列段数（整条无键隙 = 1 段）再滤；近黑像素（手势导航条）
    不算键帽，否则屏幕底黑 bar 会被当成最后一行键。"""
    img = Image.open(png).convert("RGB")
    W, H = img.size
    px = img.load()
    top, bot, h = D.ime_frame()
    if top is None:
        return []
    y0 = y_start if y_start is not None else top
    bot = min(H, bot or H)
    frac = []
    for y in range(y0, bot):
        n = sum(1 for x in range(8, W - 8, 4)
                if B.chd(px[x, y], ROOT_BG) > 12 and max(px[x, y]) > 60)
        frac.append(n / len(range(8, W - 8, 4)))
    bands, s = [], None
    for i, f in enumerate(frac):
        y = y0 + i
        if f > 0.5 and s is None:
            s = y
        elif f <= 0.5 and s is not None:
            bands.append((s, y)); s = None
    if s is not None:
        bands.append((s, bot))
    merged = []
    for a, b in bands:
        if merged and a - merged[-1][1] <= 8:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))
    return [(a, b) for a, b in merged
            if 0.75 * KEY_H_PX <= b - a <= 1.35 * KEY_H_PX]


def cells(png, a, b):
    """一行键里「非底色列段」= 键帽（键间距 4dp×2 = 22px@440，足够切分）。"""
    img = Image.open(png).convert("RGB")
    W = img.size[0]
    px = img.load()
    ys = list(range(a + 6, b - 6))
    frac = [sum(1 for y in ys if B.chd(px[x, y], ROOT_BG) > 12) / len(ys) for x in range(W)]
    out, x = [], 0
    while x < W:
        if frac[x] >= 0.6:
            x2 = x
            while x2 + 1 < W and frac[x2 + 1] >= 0.6:
                x2 += 1
            if x2 - x + 1 >= 24:
                out.append((x, x2, (x + x2) // 2))
            x = x2 + 1
        else:
            x += 1
    return out


def tap_cell(png, row_idx_from_bottom, cell_idx, expect_cells, label):
    """按「倒数第几行 + 第几个键帽」点按；列段数不符 = 导航到了错误的页，闸门拒绝。"""
    top, bot, h = D.ime_frame()
    if top is None:
        gate("frame_none", at=label)
    rows = key_rows(png)
    if len(rows) < row_idx_from_bottom + 1:
        gate("nav_fail", at=label, rows=len(rows), need=row_idx_from_bottom + 1,
             bands=[(a, b) for a, b in rows])
    a, b = rows[-(row_idx_from_bottom + 1)]
    cs = cells(png, a, b)
    if len(cs) != expect_cells:
        gate("nav_fail", at=label, cells=len(cs), expect=expect_cells,
             row=(a, b), segs=[(l, r) for l, r, c in cs])
    x, y = cs[cell_idx][2], (a + b) // 2
    D.shell("input tap %d %d" % (x, y))
    time.sleep(0.7)
    D.log("NAV", at=label, tap=(x, y), row=(a, b), cells=len(cs))
    return x, y


def t9_digit_map(png):
    """T9 数字页 4 行 × 4 列 → 数字键心。行序 = DIGIT_GRID 定义（T9Layouts.kt）。"""
    top, bot, h = D.ime_frame()
    rows = key_rows(png)
    # 空态候选条恒高 46dp，落在键行高度窗内；它整条无键隙 = 1 个列段，键行 ≥3 段
    rows = [r for r in rows if len(cells(png, r[0], r[1])) >= 3]
    if len(rows) != 4:
        gate("nav_fail", at="t9_grid", rows=len(rows), expect=4,
             bands=[(a, b) for a, b in rows])
    grid = [["1", "2", "3", "del"], ["4", "5", "6", "enter"],
            ["7", "8", "9", "emoji"], ["*", "0", "#", "mode"]]
    m = {}
    for ri, (a, b) in enumerate(rows):
        cs = cells(png, a, b)
        if len(cs) != 4:
            gate("nav_fail", at="t9_grid_row%d" % ri, cells=len(cs), expect=4)
        for ci, ch in enumerate(grid[ri]):
            m[ch] = (cs[ci][2], (a + b) // 2)
    return m


def enter_t9():
    """字母页 → 123（底行第 6 键）→ 数字页 → 9键（底行第 2 键）→ T9 数字页。"""
    p = shot("_t9_nav0.png")
    km, diag = D.key_map(p)
    if not diag.get("ok"):
        gate("nav_fail", at="letter_key_map", **{k: str(v) for k, v in diag.items()})
    # 底行 = 含空格键心的行；7 键：符 / 中/英 / ， / 空格 / 。 / 123 / ↵
    top, bot, h = D.ime_frame()
    rows = key_rows(p)
    sp = km[" "]
    brow = [i for i, (a, b) in enumerate(rows) if a <= sp[1] <= b]
    if not brow:
        gate("nav_fail", at="letter_bottom_row", space=sp, rows=len(rows))
    a, b = rows[brow[0]]
    cs = cells(p, a, b)
    if len(cs) != 7:
        gate("nav_fail", at="letter_bottom", cells=len(cs), expect=7)
    x, y = cs[5][2], (a + b) // 2
    D.shell("input tap %d %d" % (x, y))
    time.sleep(0.7)
    D.log("NAV", at="to_number", tap=(x, y), cells=len(cs))
    # 数字页底行 4 键：ABC / 9键 / 空格 / ↵
    tap_cell(shot("_t9_nav1.png"), 0, 1, 4, "to_t9")
    m = t9_digit_map(shot("_t9_nav2.png"))
    D.log("T9_MAP", **{k: str(v) for k, v in m.items()})
    return m


def back_to_letter():
    """T9 → *（符号1）→ 符号页底行 ABC（第 2 键）→ 字母页。T9 无直达 ABC（docs §6.3）。"""
    m = t9_digit_map(shot("_t9_back0.png"))
    D.shell("input tap %d %d" % m["*"])
    time.sleep(0.7)
    tap_cell(shot("_t9_back1.png"), 0, 1, 4, "to_letter")
    km, diag = D.key_map(shot("_t9_back2.png"))
    if not diag.get("ok"):
        gate("nav_fail", at="letter_restore", **{k: str(v) for k, v in diag.items()})
    D.log("NAV", at="letter_restored", ok=True)


# ── 测量原语 ──────────────────────────────────────────────────────────

def press(kc, settle=0.5):
    D.shell("input keyevent %d" % kc)
    time.sleep(settle)


def sweep_to_clamp(kc, x0, chip_required, max_n=40, tag="SWEEP"):
    """按 kc 直到连续 3 次零位移（到界钳制）。返回 (累计位移, 帧列表[(offset, analyze)], 末帧)。"""
    prev = shot("_t9_s0.png")
    cum, plateaus, frames = 0, 0, []
    a = analyze(prev, chip_required)
    if a and a.get("ok"):
        frames.append((0, a))
    for i in range(1, max_n + 1):
        press(kc)
        cur = shot("_t9_s%d.png" % (i % 2))
        s, e = band_shift(prev, cur, x0)
        ok = s is not None and e is not None and e <= ERR_MAX
        if ok:
            cum += s
        D.log(tag, i=i, key=kc, shift=s, err=e, cum=cum,
              verdict="ok" if ok else "unverifiable")
        plateaus = plateaus + 1 if (ok and abs(s) < 1) else 0
        if ok and abs(s) >= 1:
            a = analyze(cur, chip_required)
            if a and a.get("ok"):
                frames.append((cum, a))
        if plateaus >= 3:
            D.log("CLAMP", key=kc, press=i, cum=cum)
            break
        prev = cur
    return cum, frames, prev


def origin_probe(x0, chip_required, tag):
    """左边界探针：'-' 不动（逐像素）且 '=' 动 +198 → 偏移=0 可判。
    返回 dict；池 ≤1 时探针无效，退静态判据（首选高亮盒左沿 == 候选区左沿）。"""
    a = analyze(shot("_t9_op0.png"), chip_required)
    n = a.get("cand_count", 0) if a and a.get("ok") else 0
    if n < 2:
        left = a.get("first_item_left") if a and a.get("ok") else None
        expect = round(B.PAD_START_DP * DEN)
        at_left = left is not None and abs(left - expect) <= 8
        D.log(tag, probe="static", cand_count=n, first_item_left=left,
              expect_left=expect, at_left=at_left,
              note="池<=1 时 '-' 放行给宿主（IspeechImeService.kt:602），探针无效，用静态判据")
        return dict(probe="static", at_left=at_left, cand_count=n)
    pa = shot("_t9_op_a.png")
    press(69)
    pb = shot("_t9_op_b.png")
    s, e = band_shift(pa, pb, x0)
    bb = diff_bbox(pa, pb)
    minus_idle = (s is not None and e is not None and e <= ERR_MAX and abs(s) < 1) or bb is None
    press(70)
    pc = shot("_t9_op_c.png")
    s2, e2 = band_shift(pb, pc, x0)
    plus_moves = s2 is not None and e2 is not None and e2 <= ERR_MAX and abs(s2 - STEP_PX) <= TOL_PX
    press(69)   # 回到探针前位置
    D.log(tag, probe="boundary", cand_count=n, minus_shift=s, minus_err=e, diff_bbox=bb,
          minus_idle=minus_idle, plus_shift=s2, plus_err=e2, plus_moves=plus_moves,
          at_left=minus_idle and plus_moves)
    return dict(probe="boundary", at_left=minus_idle and plus_moves, cand_count=n,
                minus_shift=s, plus_shift=s2)


def tap_first_visible(tag):
    """点选当前视口最左可见候选（滚到第 K 档后 = 第 K+1 条附近的项）。"""
    p = shot("_t9_pick.png")
    a = analyze(p, chip_required=False)
    if not a or not a.get("ok") or not a.get("items"):
        gate("readback", at=tag, reason_detail="no_visible_item",
             analyze_ok=bool(a and a.get("ok")))
    it = a["items"][0]
    W = Image.open(p).size[0]
    x = (it["item_left"] + min(it["item_right"], W - 1)) // 2
    top, bot, h = D.ime_frame()
    y = top + BAND_PX // 2
    D.shell("input tap %d %d" % (x, y))
    time.sleep(0.9)
    D.log(tag, tap=(x, y), item_left=it["item_left"], item_right=it["item_right"])
    return x, y


def host_clear(m=None):
    # T9 态清缓冲只能走软键 del（handleBackspace :674-677 弹 t9Digits）：
    # 硬件 DEL 因 buffer 恒空在 :568 放行给宿主，只删宿主文本不清 t9Digits；
    # 硬件 ESC 在 :575 早退（条件不含 t9Digits），handleEscape 的清空分支到不了。
    # 残留 t9Digits 会让下一轮敲码拼成 '7474'（run7 实测）。
    if m and "del" in m:
        for _ in range(len(DIGITS) + 2):
            D.shell("input tap %d %d" % m["del"])
            time.sleep(0.15)
        time.sleep(0.5)
    D.shell("input keyevent KEYCODE_ESCAPE")
    time.sleep(0.3)
    D.clear(30)
    time.sleep(0.4)


# ── AC-a1：T9 态横滚等价性 ────────────────────────────────────────────

def ac_a1(m):
    for ch in DIGITS:
        D.shell("input tap %d %d" % m[ch])
        time.sleep(0.18)
    time.sleep(1.6)   # lookupT9 在 Dispatchers.Default 异步查词
    host = D.focused_text()
    p = shot("_t9_gate.png")
    top, bot, h = D.ime_frame()
    bg = band_bg_pct(p)
    D.log("PREP", leg="t9", host=repr(host), want=DIGITS, frame=h, bg_match_pct=bg,
          ime=cur_ime(), note="T9 chip 恒 GONE，回读口径=宿主组合文本（见模块 docstring）")
    if top is None:
        gate("frame_none", host=repr(host))
    if host.strip() != DIGITS:
        gate("readback", host=repr(host), want=DIGITS, bg_match_pct=bg)
    if bg < BG_MIN_PCT:
        gate("bg_low", bg_match_pct=bg, bg_min_pct=BG_MIN_PCT)
    a = analyze(p, chip_required=False)
    if not a or not a.get("ok") or a.get("cand_count", 0) < 1:
        gate("readback", host=repr(host), detail="chip_only_or_empty",
             cand_count=a.get("cand_count") if a else None)
    x0 = a["x0"]
    # §9-11 挂账顺手取证：IME 窗口能否被 uiautomator dump（规格 §9.9 说不能）
    D.shell("uiautomator dump /sdcard/wd443.xml")
    xml = D.shell("cat /sdcard/wd443.xml")
    D.log("NODEPROBE", issue="§9-11", ime_window_dumpable="candidate_scroll" in xml,
          scrollable_nodes=xml.count('scrollable="true"'), xml_len=len(xml),
          note="挂载层运行时判定需 IME 节点可 dump；此处只记录能不能")
    # 池取证：扫到右端 → 内容坐标去重计数 → 回左端
    cum, frames, _ = sweep_to_clamp(70, x0, False, tag="POOL_FWD")
    pool = B.count_items_content_space(frames)
    D.log("POOL", leg="t9", digits=DIGITS, pool_count=pool, need=MIN_POOL,
          scroll_range_px=cum, frames=len(frames))
    if pool < MIN_POOL:
        gate("pool_low", pool_count=pool, need=MIN_POOL, scroll_range_px=cum)
    back, _, _ = sweep_to_clamp(69, x0, False, tag="POOL_BACK")
    if abs(back + cum) > 10:
        gate("not_at_origin", fwd_cum=cum, back_cum=back)
    D.log("GATES", leg="t9", readback=True, band_bg=True, pool_ge_8=True, at_origin=True)
    # 单档步长：= ×3（累计=3×单档±10），再 - ×3 镜像回左端
    prev = shot("_t9_a0.png")
    steps = []
    for i in range(1, 4):
        press(70)
        cur = shot("_t9_a%d.png" % i)
        s, e = band_shift(prev, cur, x0)
        steps.append(s)
        D.log("AC_A1_FWD", i=i, key="keyevent70(EQUALS)", shift=s, err=e,
              expect=STEP_PX, verdict=verdict(s, e, STEP_PX))
        prev = cur
    cum3 = sum(s for s in steps if s is not None)
    single = steps[0]
    cum_ok = single is not None and abs(cum3 - 3 * single) <= 10
    D.log("AC_A1_CUM", steps=steps, cum=cum3, expect3x=3 * (single or 0),
          verdict="PASS" if cum_ok else "FAIL")
    revs = []
    for i in range(1, 4):
        press(69)
        cur = shot("_t9_r%d.png" % i)
        s, e = band_shift(prev, cur, x0)
        revs.append(s)
        D.log("AC_A1_REV", i=i, key="keyevent69(MINUS)", shift=s, err=e,
              expect=-STEP_PX, verdict=verdict(s, e, -STEP_PX))
        prev = cur
    host_after = D.focused_text()
    fwd_pass = all(s is not None and abs(s - STEP_PX) <= TOL_PX for s in steps)
    rev_pass = all(s is not None and abs(s + STEP_PX) <= TOL_PX for s in revs)
    swallowed = all(s is not None and abs(s) < 1 for s in steps)
    D.log("HOSTTEXT", leg="t9", v=repr(host_after), unchanged=host_after.strip() == DIGITS)
    D.log("RESULT", ac="AC-a1", leg="t9", digits=DIGITS,
          fwd_steps=steps, rev_steps=revs, cum3=cum3,
          verdict="FAIL(key_swallowed)" if swallowed else
                  ("PASS" if (fwd_pass and rev_pass and cum_ok and host_after.strip() == DIGITS)
                   else "FAIL"),
          note="FAIL(key_swallowed)=T9 态该键被吞的实测结论；err>5 的步另标 unverifiable")
    return dict(fwd=steps, rev=revs, host=host_after)


# ── AC-a2：上屏后偏移复位 ─────────────────────────────────────────────

def ac_a2_t9(m):
    host_clear(m)
    for ch in DIGITS:
        D.shell("input tap %d %d" % m[ch])
        time.sleep(0.18)
    time.sleep(1.6)
    host = D.focused_text()
    if host.strip() != DIGITS:
        gate("readback", at="ac_a2_t9_prep", host=repr(host), want=DIGITS)
    a = analyze(shot("_t9_a2prep.png"), chip_required=False)
    x0 = a["x0"]
    off = 0
    prev = shot("_t9_k0.png")
    for i in range(1, K + 1):
        press(70)
        cur = shot("_t9_k%d.png" % (i % 2))
        s, e = band_shift(prev, cur, x0)
        if s is not None and e is not None and e <= ERR_MAX:
            off += s
        D.log("AC_A2_SCROLL", i=i, shift=s, err=e, offset=off)
        prev = cur
    if off < STEP_PX:
        gate("readback", at="ac_a2_scroll", offset=off, detail="滚不动，前提不成立")
    tap_first_visible("AC_A2_PICK")
    committed = D.focused_text()
    ok_commit = committed.strip() != "" and committed.strip() != DIGITS
    D.log("AC_A2_COMMIT", leg="t9", host=repr(committed), verdict="PASS" if ok_commit else "FAIL",
          note="上屏词 != 组合串且非空 = 点选确实上屏")
    post = analyze(shot("_t9_post.png"), chip_required=False)
    n_post = post.get("cand_count", 0) if post and post.get("ok") else 0
    D.log("AC_A2_POST", leg="t9", assoc_cand_count=n_post,
          chip_visible=bool(post and post.get("chip")),
          note="commit→startAssociation；联想态 chip 同样 GONE（code=statusText?:buffer）")
    probe = origin_probe(post["x0"] if post and post.get("ok") else round(B.PAD_START_DP * DEN),
                         False, "AC_A2_OFFSET_T9")
    # 再打下一个字：首屏必须从最左开始
    D.shell("input tap %d %d" % m["2"])
    time.sleep(1.4)
    host2 = D.focused_text()
    a2 = analyze(shot("_t9_next.png"), chip_required=False)
    x02 = a2["x0"] if a2 and a2.get("ok") else round(B.PAD_START_DP * DEN)
    probe2 = origin_probe(x02, False, "AC_A2_NEXT_T9")
    # 上屏词留在框内，新组合文本追加在词尾 → 回读判 endswith（全等会把合法上屏判成失败）
    D.log("AC_A2_NEXT", leg="t9", host=repr(host2), want="…2",
          readback=host2.strip().endswith("2"))
    D.log("RESULT", ac="AC-a2", leg="t9", offset_before_pick=off, committed=repr(committed),
          at_left_after_commit=probe["at_left"], at_left_next_char=probe2["at_left"],
          verdict="PASS" if (ok_commit and probe["at_left"] and probe2["at_left"]
                             and host2.strip().endswith("2")) else "FAIL")
    host_clear(m)
    return probe


def ac_a2_wubi():
    host_clear()
    D.type_str("g", gap=0.09)
    time.sleep(1.3)
    host = D.focused_text()
    p = shot("_w_gate.png")
    a = analyze(p, chip_required=True)
    if host.strip() != "g":
        gate("readback", at="ac_a2_wubi_prep", host=repr(host), want="g")
    if not a or not a.get("ok"):
        gate("readback", at="ac_a2_wubi_band", detail=a.get("reason") if a else "no_frame")
    x0 = a["x0"]
    cum, frames, _ = sweep_to_clamp(70, x0, True, tag="W_POOL_FWD")
    pool = B.count_items_content_space(frames)
    D.log("POOL", leg="wubi", code="g", pool_count=pool, need=MIN_POOL, scroll_range_px=cum)
    if pool < MIN_POOL:
        gate("pool_low", pool_count=pool, need=MIN_POOL, leg="wubi")
    back, _, _ = sweep_to_clamp(69, x0, True, tag="W_POOL_BACK")
    if abs(back + cum) > 10:
        gate("not_at_origin", fwd_cum=cum, back_cum=back, leg="wubi")
    off = 0
    prev = shot("_w_k0.png")
    for i in range(1, K + 1):
        press(70)
        cur = shot("_w_k%d.png" % (i % 2))
        s, e = band_shift(prev, cur, x0)
        if s is not None and e is not None and e <= ERR_MAX:
            off += s
        D.log("AC_A2_SCROLL", leg="wubi", i=i, shift=s, err=e, offset=off)
        prev = cur
    tap_first_visible("AC_A2_PICK_W")
    committed = D.focused_text()
    ok_commit = committed.strip() != "" and committed.strip() != "g"
    D.log("AC_A2_COMMIT", leg="wubi", host=repr(committed), verdict="PASS" if ok_commit else "FAIL")
    post = analyze(shot("_w_post.png"), chip_required=False)
    n_post = post.get("cand_count", 0) if post and post.get("ok") else 0
    probe = origin_probe(post["x0"] if post and post.get("ok") else x0, False, "AC_A2_OFFSET_WUBI")
    D.type_str("h", gap=0.09)
    time.sleep(1.2)
    host2 = D.focused_text()
    a2 = analyze(shot("_w_next.png"), chip_required=True)
    probe2 = origin_probe(a2["x0"] if a2 and a2.get("ok") else x0, True, "AC_A2_NEXT_WUBI")
    D.log("AC_A2_NEXT", leg="wubi", host=repr(host2), want="…h", readback=host2.strip().endswith("h"))
    D.log("RESULT", ac="AC-a2", leg="wubi", offset_before_pick=off, committed=repr(committed),
          at_left_after_commit=probe["at_left"], at_left_next_char=probe2["at_left"],
          verdict="PASS" if (ok_commit and probe["at_left"] and probe2["at_left"]
                             and host2.strip().endswith("h")) else "FAIL")
    host_clear()
    return probe


if __name__ == "__main__":
    orig = D.orig_state()
    rr = subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-d", "-g", APK],
                        capture_output=True, text=True, timeout=900)
    D.log("INSTALL", out=rr.stdout.strip()[-60:], rc=rr.returncode)
    if not ident_once(APK):
        gate("ident", stage="start", apk=os.path.basename(APK))
    D.log("IDENT", stage="start", apk=os.path.basename(APK), match=True, head=HEAD,
          product_baseline="fa57a07", ime=cur_ime(),
          apk_sha256=hashlib.sha256(open(APK, "rb").read()).hexdigest())
    if not cur_ime().startswith("com.ispeech.ime/"):
        D.shell("ime enable %s" % D.IME)
        D.shell("ime set %s" % D.IME)
        time.sleep(1.0)
    if not cur_ime().startswith("com.ispeech.ime/"):
        gate("ime_not_ours", current=cur_ime())
    D.shell("wm density %d" % DPI)
    time.sleep(1.5)
    st = D.host_ready()
    D.log("HOST", **st)
    if not st.get("ok"):
        gate("host_not_ready", **st)
    D.log("KEYMAP", step_dp=72, step_px_expect=STEP_PX, tol_px=TOL_PX, dpi=DPI,
          source="IspeechImeService.kt:601-605 + CandidateView.kt:90-92,199（main fa57a07 行号）",
          sign_conv="drive.shift(): 正=内容左移=视口向右")
    try:
        if LEG in ("t9", "all"):
            m = enter_t9()
            ac_a1(m)
            ac_a2_t9(m)
        if LEG in ("wubi", "all"):
            if LEG == "all":
                back_to_letter()
            ac_a2_wubi()
    finally:
        host_clear()
        D.restore_state(orig)
        D.log("RESTORE", **{k: v.replace("\n", " | ") for k, v in orig.items()})
    D.log("IDENT", stage="end", match=ident_once(APK), head=HEAD, ime=cur_ime())
    D.log("DONE", legs=LEG, digits=DIGITS, k=K)