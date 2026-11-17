#!/usr/bin/env python3
"""VOCA-37 §9.8 五条未验证路径 + 回归面取证（模拟器；第七轮被测面 `78343a4`）。

每个路径独立 try，输出 TAB 分隔的结构化行，便于贴进报告。
"""
import os, re, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
from PIL import Image

APK = sys.argv[1] if len(sys.argv) > 1 else ""


def ident(tag):
    import hashlib, zipfile
    r = subprocess.run([D.ADB, "-s", D.S, "shell", "pm path com.ispeech.ime"], capture_output=True, text=True)
    if "package:" not in r.stdout:
        D.log("IDENT", name=tag, match="NO_APK"); return False
    remote = r.stdout.strip().split("package:")[1]
    lp = f"{D.Q}/_id_{tag}.apk"
    subprocess.run([D.ADB, "-s", D.S, "pull", remote, lp], capture_output=True)
    def ent(path):
        z = zipfile.ZipFile(path)
        return {n: hashlib.sha256(z.read(n)).hexdigest()[:12] for n in sorted(z.namelist())
                if (n.startswith("classes") and n.endswith(".dex")) or n == "resources.arsc"}
    ok = ent(APK) == ent(lp)
    D.log("IDENT", name=tag, t=time.strftime("%H:%M:%S"), match=ok,
          ime=D.shell("settings get secure default_input_method").strip())
    return ok


BG = (244, 245, 247)
TB_Y = 1535
TB = {"switch": 67, "sym": 202, "emoji": 337, "phrase": 472, "clip": 607, "ai": 742, "voice": 877, "set": 1012}


def band_top():
    top, bot, h = D.ime_frame()
    return top, h


def band_ink(top, y0off=1, y1off=125):
    """候选带内是否有文字墨迹（候选词）→ 返回 (有无, 命中列数)。"""
    p = D.shot("_ink.png")
    px = Image.open(p).convert("RGB").load()
    best = 0
    for y in range(top + 30, top + 110):
        c = 0
        for x in range(16, 1064, 2):
            q = px[x, y]
            if max(abs(q[0] - BG[0]), abs(q[1] - BG[1]), abs(q[2] - BG[2])) > 45:
                c += 1
        best = max(best, c)
    return best, p


def scroll_px(a, b, top):
    return D.shift(a, b, top + 1, top + 125)


def host_text():
    return D.focused_text()


# ── ① 0 候选编码：键盘高度不塌、不跳 ─────────────────────────
def p1():
    D.clear(22)
    time.sleep(0.6)
    top0, h0 = band_top()
    D.tap_keys("qvxzj", gap=0.13)
    time.sleep(1.2)
    top1, h1 = band_top()
    ink, p = band_ink(top1)
    D.log("P1_zero_cand", empty_h=h0, comp_h=h1, delta=(h1 - h0) if h0 and h1 else None,
          cand_ink_cols=ink, host=repr(host_text())[:40], shot=os.path.basename(p))


# ── ④ 上屏 / 联想后偏移归零（G2 scrollTo(0,0)）───────────────
def p4():
    D.clear(22); time.sleep(0.5)
    D.tap_keys("zhongguo", gap=0.11); time.sleep(1.2)
    base = D.shot("p4_base.png")
    for _ in range(4):
        D.shell("input keyevent 70"); time.sleep(0.35)   # '=' = 右移（IspeechImeService.kt:620-622）；起点在左端，'-' 会原地不动
    moved = D.shot("p4_moved.png")
    top, h = band_top()
    s1, e1 = scroll_px(base, moved, top)
    D.shell("input tap 532 2125"); time.sleep(1.4)       # 空格上屏
    after = D.shot("p4_after.png")
    s2, e2 = scroll_px(moved, after, top)
    s0, e0 = scroll_px(base, after, top)
    D.shell("input keyevent 4"); time.sleep(0.4)
    D.log("P4_offset_reset", scroll_after_eq4=round(s1, 1), err=round(e1, 1),
          back_to_base=round(s0, 1), err0=round(e0, 1), after_commit_delta=round(s2, 1), err2=round(e2, 1),
          host=repr(host_text())[:40])


# ── ⑤ 面板打开时候选条还在不在层级里 ────────────────────────
def p5():
    for name, x in (("phrase", TB["phrase"]), ("clip", TB["clip"]), ("set", TB["set"])):
        try:
            D.shell("input tap %d %d" % (x, TB_Y)); time.sleep(1.3)
            top, h = band_top()
            ink, p = band_ink(top) if top else (None, None)
            p2 = D.shot("p5_%s.png" % name)
            D.shell("input tap %d %d" % (x, TB_Y)); time.sleep(0.9)   # 再点一次收起
            D.log("P5_panel", name=name, kb_h=h, top=top, band_ink_cols=ink, shot=os.path.basename(p2))
        except Exception as e:
            D.log("P5_panel", name=name, err=repr(e))


# ── ③ T9：九宫格态下 -/= 是否仍能滚候选 ─────────────────────
def t9_grid(top):
    """在截图里找九宫格栅格：键帽行 + 列分隔，返回 (row,col)->(x,y)。"""
    p = D.shot("_t9.png")
    px = Image.open(p).convert("RGB").load()
    rows = []
    y = top + 240
    while y < 2330:
        c = 0
        for x in range(16, 1064, 2):
            q = px[x, y]
            if min(q) >= 250:
                c += 1
        rows.append((y, c / ((1064 - 16) / 2)))
        y += 1
    bands = []
    run = None
    for yy, f in rows:
        if f > 0.30:
            run = yy if run is None else run
        elif run is not None:
            bands.append((run, yy)); run = None
    if not bands:
        return None, p
    centers = [(a + b) // 2 for a, b in bands][:4]
    cols = []
    yy = centers[min(1, len(centers) - 1)]
    prev = False; start = None
    for x in range(8, 1076):
        q = px[x, yy]
        on = min(q) >= 250
        if on and not prev:
            start = x
        elif not on and prev:
            if x - start > 60:
                cols.append((start, x))
        prev = on
    if not cols:
        return None, p
    cx = [(a + b) // 2 for a, b in cols][:4]
    return (centers, cx), p


def p3():
    D.shell("input tap 851 2125"); time.sleep(1.0)      # 123 → 数字页
    D.shell("input tap 240 2125"); time.sleep(1.2)      # 数字页底行第 2 键 = 9键
    top, h = band_top()
    D.shell("input keyevent KEYCODE_DEL")               # 清掉误触
    grid, p = t9_grid(top)
    if not grid:
        D.log("P3_t9", ok=False, why="grid_not_found", kb_h=h, shot=os.path.basename(p)); return
    centers, cx = grid
    D.log("P3_t9", ok=True, kb_h=h, rows=centers, cols=cx)
    pos = {"2": (0, 0), "3": (0, 1), "4": (1, 0), "5": (1, 1), "6": (1, 2), "7": (2, 0), "8": (2, 1), "9": (2, 2)}
    for d in ("9", "4", "6", "6", "4"):                 # 94664 = zhong
        r, c = pos[d]
        if r < len(centers) and c < len(cx):
            D.shell("input tap %d %d" % (cx[c], centers[r])); time.sleep(0.35)
    time.sleep(1.2)
    base = D.shot("p3_t9_base.png")
    t2, h2 = band_top()
    D.shell("input keyevent 70"); time.sleep(0.4); D.shell("input keyevent 70"); time.sleep(0.4)   # '=' 右移 2 档
    moved = D.shot("p3_t9_eq2.png")
    s, e = scroll_px(base, moved, t2)
    D.log("P3_t9_scroll", step2=round(s, 1), err=round(e, 1), kb_h=h2, code=repr(host_text())[:30])


# ── 回归面：双击空格 vs 长按语音；快捷短语面板里打字进搜索框 ──
def reg_double_space():
    D.clear(22); time.sleep(0.4)
    t0 = time.time()
    D.shell("input tap 532 2125"); D.shell("input tap 532 2125")
    time.sleep(1.0)
    top, h = band_top()
    ink, p = band_ink(top) if top else (0, None)
    D.log("REG_double_space", secs=round(time.time() - t0, 2), host=repr(host_text())[:40], kb_h=h, band_ink=ink)


def reg_phrase_search():
    D.shell("input tap %d %d" % (TB["phrase"], TB_Y)); time.sleep(1.3)
    before = D.shot("reg_phr_before.png")
    D.tap_keys("hai", gap=0.15); time.sleep(1.0)
    after = D.shot("reg_phr_after.png")
    top, h = band_top()
    D.log("REG_phrase_letters", kb_h=h, shots=(os.path.basename(before), os.path.basename(after)), host=repr(host_text())[:40])
    D.shell("input tap %d %d" % (TB["phrase"], TB_Y)); time.sleep(0.9)


if __name__ == "__main__":
    r = subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-g", APK], capture_output=True, text=True, timeout=900)
    D.log("INSTALL", out=r.stdout.strip()[-50:], rc=r.returncode)
    subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "enable", D.IME], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "set", D.IME], capture_output=True)
    if not ident("start"):
        sys.exit(3)
    st = D.setup_contacts(retries=6)
    D.log("HOST_CONTACTS", **st)
    if not st.get("ok"):
        st = D.ensure_host(retries=6)
        D.log("HOST_SETTINGS", **st)
    if not st.get("ok"):
        sys.exit(2)
    D.shell("settings put secure autofill_service null")
    for fn in (p1, p5):
        try:
            fn()
        except Exception as e:
            D.log("ERR", fn=fn.__name__, err=repr(e))
    ident("end")
    st3 = D.setup_contacts(retries=4)
    D.log("HOST_CONTACTS2", **st3)
    for fn in (p4, p3, reg_double_space, reg_phrase_search):
        try:
            fn()
        except Exception as e:
            D.log("ERR", fn=fn.__name__, err=repr(e))
    ident("end")
