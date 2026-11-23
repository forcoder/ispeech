#!/usr/bin/env python3
"""VOCA-37 补测 v3：邮箱字段守卫 + ④偏移归零 + ③T9 -/= + 双击空格。"""
import os, re, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
from PIL import Image
import paths as P
import paths2 as P2

APK = sys.argv[1] if len(sys.argv) > 1 else ""
EMAIL_Y_HINT = "电子邮件"


def dismiss_dialog():
    """关掉联系人页残留的「更改尚未保存」弹窗，否则会吃掉整屏焦点。"""
    for _ in range(3):
        D.shell("uiautomator dump /sdcard/wd.xml"); x = D.shell("cat /sdcard/wd.xml")
        m = re.search(r'text="舍弃"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
        if not m:
            return True
        v = [int(g) for g in m.groups()]
        D.shell("input tap %d %d" % ((v[0] + v[2]) // 2, (v[1] + v[3]) // 2)); time.sleep(1.5)
    return False


def focus_email():
    """在联系人新建页里挑「电子邮件」输入框：优先标签匹配，否则取 y 最大的 EditText。"""
    dismiss_dialog()
    for i in range(4):
        dismiss_dialog()
        D.shell("uiautomator dump /sdcard/wd.xml")
        x = D.shell("cat /sdcard/wd.xml")
        cands = []
        for n in re.finditer(r'<node[^>]*class="android.widget.EditText"[^>]*>', x):
            s = n.group(0)
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            if not b:
                continue
            v = [int(g) for g in b.groups()]
            txt = (re.search(r'text="([^"]*)"', s) or [None, ""])[1]
            cands.append((v, txt))
        if not cands:
            time.sleep(1.0); continue
        tgt = None
        for v, txt in cands:
            if EMAIL_Y_HINT in txt or "邮箱" in txt:
                tgt = v; break
        if tgt is None:
            tgt = max(cands, key=lambda c: c[0][1])[0]
        D.shell("input tap %d %d" % ((tgt[0] + tgt[2]) // 2, (tgt[1] + tgt[3]) // 2))
        time.sleep(1.6)
        D.shell("input keyevent KEYCODE_ESCAPE")   # 关掉可能残留的面板态
        time.sleep(0.8)
        top, h = P.band_top()
        if not h:
            continue
        # 守卫：有候选时硬件 '-' 必须进 IME（宿主文本里不能出现 '-'）
        D.clear(20); D.tap_keys("zhong", gap=0.12); time.sleep(1.2)
        D.shell("input keyevent 69"); time.sleep(0.6)
        t = D.focused_text()
        D.clear(24)
        if "-" not in t:
            D.log("FOCUS", ok=True, field=tgt, host=repr(t)[:30], top=top, kb_h=h)
            return True
        D.log("FOCUS", ok=False, field=tgt, host=repr(t)[:40], why="host consumed '-'")
        D.shell("input keyevent 4")
    return False


def main():
    r = subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-g", APK], capture_output=True, text=True, timeout=900)
    D.log("INSTALL", out=r.stdout.strip()[-30:], rc=r.returncode)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "secure", "autofill_service", "null"], capture_output=True)
    if not P.ident("start"):
        sys.exit(3)
    subprocess.run([D.ADB, "-s", D.S, "shell", "am", "force-stop", "com.ispeech.ime"], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "am", "start", "-a", "android.intent.action.INSERT",
                    "-t", "vnd.android.cursor.dir/contact"], capture_output=True)
    time.sleep(2.5)
    subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "set", D.IME], capture_output=True)
    time.sleep(1.0)
    if not focus_email():
        D.log("GATE", blocked="拿不到「-/= 进 IME」的宿主字段，④③ 无法在本轮取证")
        sys.exit(2)
    # ── ④ 上屏后偏移归零 ──
    D.clear(22); time.sleep(0.5)
    D.tap_keys("zhongguo", gap=0.11); time.sleep(1.4)
    top, h = P.band_top()
    base = D.shot("v3_base.png")
    ink, _ = P.band_ink(top)
    for _ in range(4):                      # '=' = 右移 4 档（'-' 在左端是 no-op）
        D.shell("input keyevent 70"); time.sleep(0.45)
    moved = D.shot("v3_eq4.png")
    s1, e1 = P.scroll_px(base, moved, top)
    subprocess.run([D.ADB, "-s", D.S, "shell", "input", "tap", "532", "2125"], capture_output=True)
    time.sleep(1.6)
    after = D.shot("v3_after.png")
    s2, e2 = P.scroll_px(moved, after, top)
    s3, e3 = P.scroll_px(base, after, top)
    D.log("V4_reset", ink=ink, eq4=round(s1, 1), err1=round(e1, 1),
          commit=round(s2, 1), err2=round(e2, 1), base_vs_after=round(s3, 1), err3=round(e3, 1),
          host=repr(D.focused_text())[:40], kb_h=P.band_top()[1])
    # ── ③ T9 ──
    D.clear(22); time.sleep(0.4)
    D.shell("input tap 874 2125"); time.sleep(1.2)          # 123
    p = D.shot("v3_numbers.png")
    D.shell("input tap 286 2125"); time.sleep(1.4)          # 9键
    p2 = D.shot("v3_t9.png")
    top2, h2 = P.band_top()
    grid = P2.caps(p2, (top2 or 1352) + 220, 2330, thr=0.22)
    D.log("V3_T9", kb_h=h2, top=top2, bands=[(b["cy"], b["xs"]) for b in grid])
    if len(grid) >= 3:
        pos = {"9": (2, 2), "4": (1, 0), "6": (1, 2)}
        for d in ("9", "4", "6", "6", "4"):
            rr, cc = pos[d]
            if rr < len(grid) and cc < len(grid[rr]["xs"]):
                D.shell("input tap %d %d" % (grid[rr]["xs"][cc], grid[rr]["cy"])); time.sleep(0.45)
        time.sleep(1.3)
        t3, h3 = P.band_top()
        b1 = D.shot("v3_t9_base.png")
        D.log("V3_T9_cand", kb_h=h3, ink=P.band_ink(t3)[0] if t3 else None, host=repr(D.focused_text())[:40])
        for _ in range(2):                  # '=' = 右移 2 档
            D.shell("input keyevent 70"); time.sleep(0.45)
        b2 = D.shot("v3_t9_eq2.png")
        ss, ee = P.scroll_px(b1, b2, t3)
        D.log("V3_T9_scroll", shift2=round(ss, 1), err=round(ee, 1))
    # ── 双击空格 ──
    D.clear(22); time.sleep(0.4)
    subprocess.run([D.ADB, "-s", D.S, "shell", "input", "tap", "532", "2125",
                    "&&", "input", "tap", "532", "2125"], capture_output=True)
    time.sleep(1.2)
    D.log("V_DOUBLE_fast", host=repr(D.focused_text())[:40])
    D.clear(22); time.sleep(0.4)
    subprocess.run([D.ADB, "-s", D.S, "shell", "input", "tap", "532", "2125"], capture_output=True)
    time.sleep(0.6)
    subprocess.run([D.ADB, "-s", D.S, "shell", "input", "tap", "532", "2125"], capture_output=True)
    time.sleep(1.2)
    D.log("V_DOUBLE_slow_600ms", host=repr(D.focused_text())[:40])
    P.ident("end")


if __name__ == "__main__":
    main()
