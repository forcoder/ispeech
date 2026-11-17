#!/usr/bin/env python3
"""VOCA-37 §8.4 候选带竖滑取证：把「有没有上屏」和「有没有横滚」分成两件事量。

起点 y、位移 dy、duration、x 全部参数化；每个 case 单独一行，几何写进日志，不靠叙述。

  --mode inband  起点 = 候选带上沿 + (--start) 偏移（默认 38px，落在带内第 1 行文字上）
  --mode above   起点 = 候选带上沿 − (--start)（即起点在键盘之外的宿主内容区），向下滑回带内
  --mode abs     起点 = --start 的绝对 y

横滚用 drive.shift() 的列剖面互相关，err > 5 记 unverifiable（README 测量纪律 3）。
注意：一旦这次手势把候选上屏，候选池会因 CandidateView.kt:81 的 scrollTo(0,0) 整体重置，
before/after 两帧比的是不同内容，横滚读数天然不可判 —— 所以 hroll 族用 --pre 先按 `=` 滚离左端，
再看这次竖滑是否在不触发上屏的前提下改动水平偏移。

用法：python3 vswipe.py <apk> [dpi] [--x 300] [--dx 0] [--start 38] [--dy 20,40,60,80,110,150,200] [--dur 120]
      python3 vswipe.py <apk> 440 --family hroll --pre 4 --dy 60,110 --dx 0      # 纯竖滑（滚动态）
      python3 vswipe.py <apk> 440 --family hroll --pre 4 --dy 110 --dx 160        # 斜滑（滚动态）
      python3 vswipe.py <apk> 440 --mode above --start 215 --dy 215               # 起点在带上沿外 215px
"""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
import fs115 as F
from PIL import Image, ImageChops

ERR_MAX = 5.0
BAND = (1, 125)
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
X = int(OPT.get("x", 300))
DX = int(OPT.get("dx", 0))     # 水平分量：>0 即斜滑
START = int(OPT.get("start", 38))
MODE = OPT.get("mode", "above" if START > 125 else "inband")
DYS = [int(v) for v in OPT.get("dy", "20,40,60,80,110,150,200").split(",")]
DUR = int(OPT.get("dur", 120))
PRE = int(OPT.get("pre", 4))
FAMILY = OPT.get("family", "commit")
DENS = POS[1] if len(POS) > 1 else "440"
FR = ["_vs_a.png", "_vs_b.png"]


def shot(i):
    return D.shot(FR[i % 2])


def band_geom():
    top, bot, h = D.ime_frame()
    return top, h


def start_y(top):
    if MODE == "above":
        return top - START
    if MODE == "abs":
        return START
    return top + BAND[0] + START


def reset():
    """清宿主 → 打 shi（缓冲态，候选条在）→ 回左端。"""
    st = D.host_ready()
    if not st.get("ok"):
        D.log("HOST", **{k: v for k, v in st.items()})
        return False
    D.clear(22)
    time.sleep(0.45)
    D.type_str("shi", gap=0.09)
    time.sleep(1.2)
    return D.focused_text().strip() == "shi"


def hshift(a, b, top):
    if top is None:
        return None, None, "no_ime_frame(键盘这次手势后不在位)"
    s, e = D.shift(a, b, top + BAND[0], top + BAND[1])
    e = None if e is None else round(e, 2)
    return s, e, ("ok" if (s is not None and e is not None and e <= ERR_MAX) else "unverifiable(err>%s)" % ERR_MAX)


def one(dy, dur, idx):
    if not reset():
        D.log("GATE", case="dy=%d" % dy, blocked="缓冲不是 'shi'，键没进 IME", host=repr(D.focused_text()))
        return
    top, h = band_geom()
    if top is None:
        D.log("GATE", case="dy=%d" % dy, blocked="swipe 前键盘不在位")
        return
    sy = start_y(top)
    before = shot(0)
    tag = "%s_dy%d_dx%d_dur%d_x%d_%s%s" % (FAMILY, dy, DX, dur, X, MODE, START)
    D.shell("input swipe %d %d %d %d %d" % (X, sy, X + DX, sy + dy, dur))
    time.sleep(1.1)
    after = shot(1)
    t2, h2 = band_geom()
    hb = repr(D.focused_text())
    s, e, v = hshift(before, after, t2)
    D.log("VSWIPE", case=tag, start_y=sy, end_y=sy + dy, dy=dy, dx=DX, dur=dur, x=X, band_top=t2, band_h=h2,
          before="'shi'", after=hb, ime_gone=(t2 is None),
          committed=(hb != "'shi'" and t2 is not None and hb != "'<NONE>'"),
          reading=("键盘被这一下手势收掉了，宿主焦点也没了 → 不能读成上屏" if t2 is None else "以宿主文本差判定"),
          h_shift=s, h_err=e, h_verdict=v)


if __name__ == "__main__":
    apk = sys.argv[1]
    rr = subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-d", "-g", apk], capture_output=True, text=True, timeout=900)
    D.log("INSTALL", out=rr.stdout.strip()[-60:], rc=rr.returncode)
    if not F.ident_once(apk):
        D.log("GATE", reason="installed_apk_not_this_build", rc=19, stage="start")
        sys.exit(19)
    D.log("IDENT", stage="start", apk=os.path.basename(apk), match=True, head="78343a4")
    if not F.cur_ime().startswith("com.ispeech.ime/"):
        D.log("SKIP", reason="current_ime_not_ispeech", current=F.cur_ime(), hint="adb shell ime set " + D.IME)
        sys.exit(3)
    subprocess.run([D.ADB, "-s", D.S, "shell", "wm", "density", DENS], capture_output=True)
    time.sleep(1.0)
    if not reset():
        D.log("GATE", blocked="宿主起不来或缓冲不落在 IME", host=repr(D.focused_text()))
        sys.exit(4)
    top, h = band_geom()
    D.log("GEOM", dens=DENS, frame=h, band_top=top,
          band=None if top is None else "[%d,%d]" % (top + BAND[0], top + BAND[1]),
          family=FAMILY, mode=MODE, start_offset=START, x=X, dx=DX, dur=DUR)

    if FAMILY == "hroll":
        for dy in DYS:
            if not reset():
                continue
            top, h = band_geom()
            if top is None:
                D.log("GATE", case="hroll_dy%d" % dy, blocked="swipe 前键盘不在位")
                continue
            for _ in range(PRE):                      # 先滚离左端，内容稳定后再做竖滑
                D.shell("input keyevent 70"); time.sleep(0.7)
            sy = start_y(top)
            base = shot(0)
            D.shell("input swipe %d %d %d %d %d" % (X, sy, X + DX, sy + dy, DUR))
            time.sleep(1.1)
            after = shot(1)
            t2, h2 = band_geom()
            hb = repr(D.focused_text())
            s, e, v = hshift(base, after, t2)
            D.log("VSWIPE", case="hroll_dy%d_dx%d_dur%d_x%d_pre%d" % (dy, DX, DUR, X, PRE),
                  start_y=sy, end_y=sy + dy, dy=dy, dx=DX, dur=DUR, x=X,
                  pre_eq_presses=PRE, pre_shift_expect=round(PRE * 72 * int(DENS) / 160),
                  band_top=t2, before="'shi'", after=hb, ime_gone=(t2 is None),
                  committed=(hb != "'shi'" and t2 is not None and hb != "'<NONE>'"), h_shift=s, h_err=e, h_verdict=v)
    else:
        for dy in DYS:
            one(dy, DUR, dy)
        # 对照：真点候选（无位移，纯 tap）—— 证明「上屏」走的是点击路径
        if reset() and band_geom()[0] is not None:
            top, h = band_geom()
            before = shot(0)
            D.shell("input tap %d %d" % (X, top + 60))
            time.sleep(1.1)
            hb = repr(D.focused_text())
            s, e, v = hshift(before, shot(1), band_geom()[0])
            D.log("VSWIPE", case="control_tap_x%d" % X, start_y=top + 60, dy=0, dur=0, x=X,
                  before="'shi'", after=hb, committed=(hb != "'shi'"), h_shift=s, h_err=e, h_verdict=v)
    D.log("IDENT", stage="end", match=F.ident_once(apk), head="78343a4")
