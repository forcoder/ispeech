#!/usr/bin/env python3
"""VOCA-37 §3 AC-4~AC-7 取证：硬件键 `-` / `=` 驱动候选条横滚的步长、方向、端点钳制。

键→方向不手工标注，从被测构建的代码里读出来，并逐行署名到每个读数上：
  IspeechImeService.kt:612-614  KEYCODE_MINUS → scrollCandidates(-1)，KEYCODE_EQUALS → scrollCandidates(+1)
  CandidateView.kt:90-92        scrollCandidates(steps) → smoothScrollBy(steps * dp(SCROLL_STEP_DP), 0)
  CandidateView.kt:199          SCROLL_STEP_DP = 72
位移口径见 drive.shift()：正 = 候选内容左移 = 视口向右（看后面的候选）。
所以期望 `=`(70) = +198px、`-`(69) = −198px；到端后由 ScrollView 钳制，不绕回。

err（互相关残差）> 5 的读数按 README 测量纪律 3 记 unverifiable，不进入步长结论。

用法：python3 ac47.py <apk> [dpi] [--fwd 70] [--rev 69] [--n 8] [--settle 0.75]
"""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
import fs115 as F            # 复用 ident() / ours() / cur_ime() / host_ready()：APK、DENS 直接读本脚本 argv
import paths as P            # 复用候选带底色 BG（本包 #F4F5F7），不在这里另立魔法值
from PIL import Image, ImageChops

ERR_MAX = 5.0
BG_TOL = 6            # 与 P.BG(#F4F5F7) 的逐通道容差
BG_MIN_PCT = 50.0     # 候选带底色命中率下限，低于此判「键盘没画在 inset 上」
BAND_BG = (2, 124)    # 取候选带内部，避开上下 1px 描边
BAND_OFF = (1, 125)          # 候选带取 ime_frame top+1 .. top+125（46dp@440dpi = 126px）
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
FWD = int(OPT.get("fwd", 70))   # KEYCODE_EQUALS
REV = int(OPT.get("rev", 69))   # KEYCODE_MINUS
N = int(OPT.get("n", 8))
SETTLE = float(OPT.get("settle", 0.75))
DENS = POS[1] if len(POS) > 1 else "440"
FR = ["_ac47_a.png", "_ac47_b.png"]


def name(kc):
    return "keyevent%d(%s)" % (kc, "EQUALS→scrollCandidates(+1)" if kc == 70 else
                               "MINUS→scrollCandidates(-1)" if kc == 69 else "CUSTOM")


def shot(i):
    return D.shot(FR[i % 2])


def shift(prev, cur):
    top, bot, h = D.ime_frame()
    if top is None:
        return None, None, None
    s, e = D.shift(prev, cur, top + BAND_OFF[0], top + BAND_OFF[1])
    return s, (None if e is None else round(e, 2)), h


def verdict(s, e):
    return "ok" if (s is not None and e is not None and e <= ERR_MAX) else "unverifiable(err>%s)" % ERR_MAX


def diff_bbox(a, b):
    ia, ib = Image.open(a).convert("RGB"), Image.open(b).convert("RGB")
    g = ImageChops.difference(ia, ib).convert("L").point(lambda v: 255 if v > 24 else 0)
    return g.getbbox()


def scrollable():
    """把 IME 置回干净可滚态：清宿主、打 shi（候选 >1 条，否则 :613 的 size<=1 直接 return false）。"""
    st = F.host_ready()
    D.log("HOST", **{k: v for k, v in st.items()})
    if not st.get("ok"):
        return False
    D.clear(22)
    time.sleep(0.5)
    D.type_str("shi", gap=0.09)
    time.sleep(1.3)
    top, bot, h = D.ime_frame()
    bg = band_bg(top)
    D.log("PREP", host=repr(D.focused_text()), frame=h, band_top=top, bg_match_pct=bg, ime=F.cur_ime())
    # 底色闸门：ime inset 报 988 不代表键盘画在那儿。别的 run 把键盘收掉后 inset 会残留，
    # 此时截屏该区域是宿主自己的像素，滚动键全部读 0。
    if D.focused_text().strip() != "shi" or top is None or bg < BG_MIN_PCT:
        return False
    # 可滚性探针：停在左端按一次 fwd 方向的键必须动一档。读 0 = 候选池 ≤1 条（CandidateView.kt:613
    # 的 size<=1 直接 return false）或缓冲根本没进 IME —— 这两种情况下整场 sweep 会恒 0，
    # 看起来像「到端钳制」，其实是前提不成立。
    a = shot(0)
    D.shell("input keyevent %d" % FWD)
    time.sleep(SETTLE)
    s, e, _ = shift(a, shot(1))
    D.log("PRE_PROBE", key=name(FWD), shift=s, err=e, verdict=verdict(s, e),
          note="左端起步按 fwd 必须动一档；0 且可判 = 前提不成立，不出数")
    D.shell("input keyevent %d" % REV)      # 回左端，维持 sweep 的起点前提
    time.sleep(SETTLE)
    return not (s is not None and e is not None and e <= ERR_MAX and abs(s) < 1)


def band_bg(top):
    """候选带里有多少采样点落在本包底色 #F4F5F7 附近 → 判定键盘是否真画在 inset 上。"""
    if top is None:
        return 0.0
    px = Image.open(D.shot("_bg.png")).convert("RGB").load()
    hit = tot = 0
    for y in range(top + BAND_BG[0], top + BAND_BG[1], 3):
        for x in range(0, 1080, 4):
            q = px[x, y]
            if max(abs(q[0] - P.BG[0]), abs(q[1] - P.BG[1]), abs(q[2] - P.BG[2])) <= BG_TOL:
                hit += 1
            tot += 1
    return round(100.0 * hit / tot, 1) if tot else 0.0


def sweep(key, n, tag="AC4_STEP"):
    """连按 key n 次，逐次给 shift/err/cum；连续 3 次位移为 0 判定到端。"""
    prev, cum, plateaus, clamp_at = shot(0), 0.0, 0, None
    for i in range(1, n + 1):
        D.shell("input keyevent %d" % key)
        time.sleep(SETTLE)
        cur = shot(i)
        s, e, h = shift(prev, cur)
        ok = s is not None and e is not None and e <= ERR_MAX
        cum += (s if ok else 0)
        D.log(tag, i=i, key=name(key), shift=s, err=e, cum=round(cum), verdict=verdict(s, e))
        plateaus = plateaus + 1 if (ok and abs(s) < 1) else 0
        if plateaus == 3 and clamp_at is None:
            clamp_at = i - 2
            D.log("CLAMP_AT", press=clamp_at, cum_px=round(cum), key=name(key),
                  note="连续 3 次位移为 0 = 到端钳制（不绕回）")
        prev = cur
    return cum, clamp_at, prev


def tail(prev, n=5):
    """端点后静置采样：只该有光标闪，画面不该继续动。"""
    identical = 0
    for i in range(1, n + 1):
        cur = shot(i)
        bb = diff_bbox(prev, cur)
        D.log("AC6_TAIL", i=i, diff_bbox=bb)
        identical += 1 if bb is None else 0
        prev = cur
    return identical


if __name__ == "__main__":
    apk = sys.argv[1]
    rr = subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-d", "-g", apk], capture_output=True, text=True, timeout=900)
    D.log("INSTALL", out=rr.stdout.strip()[-60:], rc=rr.returncode)
    if not F.ident("start"):
        sys.exit(2)
    if not F.ours(F.cur_ime()):
        D.log("SKIP", reason="current_ime_not_ispeech", current=F.cur_ime(), hint="adb shell ime set " + D.IME)
        sys.exit(3)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "secure", "autofill_service", "null"], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "wm", "density", DENS], capture_output=True)
    time.sleep(1.0)
    D.log("KEYMAP", fwd=name(FWD), rev=name(REV), step_dp=72, step_px_expect=round(72 * int(DENS) / 160),
          source="IspeechImeService.kt:612-614 + CandidateView.kt:90-92,199",
          sign_conv="drive.shift(): 正=候选内容左移=视口向右=看后面的候选")
    if not scrollable():
        D.log("GATE", blocked="前提不成立：缓冲不是 'shi'，或左端按 fwd 无位移（键↔方向对调 / 候选池≤1 条 / 键盘没画在 inset 上）", host=repr(D.focused_text()))
        sys.exit(4)
    base = shot(0)
    top, bot, h = D.ime_frame()
    D.log("START", frame=h, top=top, band="[%d,%d]" % (top + BAND_OFF[0], top + BAND_OFF[1]), host=repr(D.focused_text()))

    cum, rclamp, prev = sweep(FWD, N * 5, tag="AC4_STEP")
    D.log("CLAMP_TAIL", press=rclamp, cum_px=round(cum), reached_by=name(FWD),
          end="右端（scrollX 到最大，看的是最后一批候选）")
    D.log("AC6_TAIL_SUMMARY", identical_pairs=tail(prev, 5))

    prev = shot(0)
    D.shell("input keyevent %d" % REV)
    time.sleep(SETTLE)
    cur = shot(1)
    s, e, _ = shift(prev, cur)
    D.log("AC5_REV", key=name(REV), shift=s, err=e, verdict=verdict(s, e))

    cum2, lclamp, prev = sweep(REV, N * 5 + 4, tag="AC5_REV_STEP")
    D.log("LEFT_CLAMP_AT", press=lclamp, cum_from_right=round(cum2), key=name(REV),
          end="左端（scrollX=0，回到首批候选）")
    D.log("LEFT_CLAMP_TAIL", identical_pairs=tail(prev, 3))

    prev = shot(0)
    D.shell("input keyevent %d" % FWD)
    time.sleep(SETTLE)
    cur = shot(1)
    s, e, _ = shift(prev, cur)
    D.log("AC7_FROM_LEFT", key=name(FWD), shift=s, err=e, verdict=verdict(s, e),
          note="端点起步可动：停在左端后按 +1 方向的键应立即一档")
    D.log("HOSTTEXT", v=repr(D.focused_text()), note="全程滚动键不得污染宿主文本")
    D.log("SUMMARY", right_end_reached_by=name(FWD), right_end_cum_px=round(cum),
          left_end_reached_by=name(REV), left_clamp_press=lclamp,
          fwd_step_expect=round(72 * int(DENS) / 160), rev_step_expect=-round(72 * int(DENS) / 160))
    F.ident("end")
