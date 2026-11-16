#!/usr/bin/env python3
"""VOCA-37 补测：④上屏/联想后偏移归零(G2) ③T9 态 -/= 滚动 双击空格窗口。"""
import os, re, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
from PIL import Image
import paths as P

APK = sys.argv[1] if len(sys.argv) > 1 else ""


def caps(png, y0, y1, thr=0.30, white=250):
    """返回 [y0,y1) 内的键帽行与每行的键中心 x。"""
    px = Image.open(png).convert("RGB").load()
    frac = []
    for y in range(y0, y1):
        c = sum(1 for x in range(8, 1076, 12) if min(px[x, y]) >= white)
        frac.append((y, c / len(range(8, 1076, 12))))
    bands, run = [], None
    for y, f in frac:
        if f > thr:
            run = y if run is None else run
        elif run is not None:
            bands.append((run, y)); run = None
    out = []
    for a, b in bands:
        yy = (a + b) // 2
        keys, start, prev = [], None, False
        for x in range(4, 1078):
            on = min(px[x, yy]) >= white
            if on and not prev:
                start = x
            elif not on and prev and x - start > 40:
                keys.append((start + x) // 2)
            prev = on
        out.append(dict(y=(a, b), cy=yy, xs=keys))
    return out


def main():
    r = subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-g", APK], capture_output=True, text=True, timeout=900)
    D.log("INSTALL", out=r.stdout.strip()[-40:], rc=r.returncode)
    subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "enable", D.IME], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "set", D.IME], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "secure", "autofill_service", "null"], capture_output=True)
    if not P.ident("start"):
        sys.exit(3)
    st = D.setup_contacts(retries=6)
    D.log("HOST", **st)
    if not st.get("ok"):
        sys.exit(2)
    D.clear(24); time.sleep(0.5)
    D.tap_keys("zhongguo", gap=0.11); time.sleep(1.3)
    top, h = P.band_top()
    base = D.shot("q4_base.png")
    ink, _ = P.band_ink(top)
    D.log("Q4_state", top=top, kb_h=h, band_ink=ink, host=repr(D.focused_text())[:50])
    for _ in range(4):
        D.shell("input keyevent 69"); time.sleep(0.4)
    moved = D.shot("q4_minus4.png")
    s1, e1 = P.scroll_px(base, moved, top)
    subprocess.run([D.ADB, "-s", D.S, "shell", "input", "tap", "532", "2125"], capture_output=True)
    time.sleep(1.5)
    after = D.shot("q4_after_commit.png")
    s2, e2 = P.scroll_px(moved, after, top)
    s3, e3 = P.scroll_px(base, after, top)
    D.log("Q4_reset", minus4_shift=round(s1, 1), err1=round(e1, 1),
          commit_shift=round(s2, 1), err2=round(e2, 1), base_vs_after=round(s3, 1), err3=round(e3, 1),
          host=repr(D.focused_text())[:50], kb_h=P.band_top()[1])
    # 联想态：上屏后候选条是联想词，再右移 2 档后直接点候选，看偏移是否再次归零
    time.sleep(0.6)
    l1 = D.shot("q4_assoc_a.png")
    D.shell("input keyevent 69"); time.sleep(0.4); D.shell("input keyevent 69"); time.sleep(0.4)
    l2 = D.shot("q4_assoc_b.png")
    sa, ea = P.scroll_px(l1, l2, P.band_top()[0])
    D.log("Q4_assoc_scroll", shift2=round(sa, 1), err=round(ea, 1), ink=P.band_ink(P.band_top()[0])[0])

    # ③ T9：123 → 数字页底行 9键
    D.shell("input tap 851 2125"); time.sleep(1.1)
    p = D.shot("q3_numbers.png")
    rows = caps(p, 2040, 2200)
    D.log("Q3_numrow", bands=[(b["y"], b["cy"], b["xs"]) for b in rows][-2:])
    if rows:
        xs = rows[-1]["xs"]
        if len(xs) >= 2:
            D.shell("input tap %d %d" % (xs[1], rows[-1]["cy"])); time.sleep(1.3)
    p2 = D.shot("q3_t9.png")
    top2, h2 = P.band_top()
    grid = caps(p2, top2 + 130 if top2 else 1600, 2330, thr=0.25)
    D.log("Q3_t9", kb_h=h2, rows=[(b["cy"], len(b["xs"])) for b in grid])
    if len(grid) >= 3:
        pos = {"9": (2, 2), "4": (1, 0), "6": (1, 2)}
        for d in ("9", "4", "6", "6", "4"):
            rr, cc = pos[d]
            if rr < len(grid) and cc < len(grid[rr]["xs"]):
                D.shell("input tap %d %d" % (grid[rr]["xs"][cc], grid[rr]["cy"])); time.sleep(0.4)
        time.sleep(1.2)
        t3, h3 = P.band_top()
        b1 = D.shot("q3_t9_base.png")
        D.log("Q3_t9_cand", kb_h=h3, top=t3, ink=P.band_ink(t3)[0] if t3 else None, host=repr(D.focused_text())[:40])
        for k in ("70", "70"):
            D.shell("input keyevent " + k); time.sleep(0.45)
        b2 = D.shot("q3_t9_eq2.png")
        ss, ee = P.scroll_px(b1, b2, t3) if t3 else (None, None)
        D.log("Q3_t9_scroll", shift2=round(ss, 1) if ss is not None else None, err=round(ee, 1) if ee is not None else None)

    # 双击空格：单次 adb shell 内串两条 tap，压缩间隔；同时抓 logcat 时间戳
    D.clear(24); time.sleep(0.5)
    subprocess.run([D.ADB, "-s", D.S, "logcat", "-c"], capture_output=True)
    t0 = time.time()
    subprocess.run([D.ADB, "-s", D.S, "shell", "input tap 532 2125; input tap 532 2125"], capture_output=True)
    wall = round(time.time() - t0, 2)
    time.sleep(1.2)
    log = subprocess.run([D.ADB, "-s", D.S, "logcat", "-d", "-v", "time"], capture_output=True, text=True).stdout
    hits = [l for l in log.splitlines() if "space" in l.lower() or "double" in l.lower() or "DoubleSpace" in l][:12]
    D.log("Q_DOUBLE", wall_secs=wall, host=repr(D.focused_text())[:60], kb_h=P.band_top()[1])
    for l in hits:
        print("LOG\t" + l[:170])
    P.ident("end")


if __name__ == "__main__":
    main()
