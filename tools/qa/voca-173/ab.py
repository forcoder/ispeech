#!/usr/bin/env python3
"""VOCA-173 A/B 复跑：zhi→之→空格 与 zhidao→指导老师（+联想）两条。

判定口径（不靠截图猜）：
- 点选候选后是否出联想：logcat IspeechIme 的 `associate hit chars=N count=M`（M>0 = 出了）
  vs `associate miss ...`（= 联想链路被关）。
- 空格是否上屏联想词：宿主 EditText 文本 —— 在「之」之后按空格，若文本只多了 1 个空格字符
  就是「联想没接上」（B 的真实表现），若多了整词则联想上屏。
"""
import os, re, subprocess, sys, time

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
S = "emulator-5554"
Q = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, Q)
import band, run as R

CAT = "IspeechIme"


def logcat_clear():
    subprocess.run([ADB, "-s", S, "logcat", "-c"], capture_output=True)


def logcat_assoc():
    out = subprocess.run([ADB, "-s", S, "logcat", "-d", "-s", CAT],
                         capture_output=True, text=True).stdout
    hits = [ln for ln in out.splitlines() if "associate hit" in ln]
    miss = [ln for ln in out.splitlines() if "associate miss" in ln]
    return hits, miss


def ensure_host(msg="短信"):
    """拉起短信输入框并把 IME 真正挂上去。

    坑：`am start` 之后 `type=ime` inset 还残留上一轮的值，光看 frame 非空就会
    过早返回 True，此时键盘其实没挂到新 Activity 上，按键全落空（文本一直是占位符）。
    所以每次都**先点输入框**，再确认键盘真的可见（screencap 里候选/键区有白键帽）。
    """
    R.shell("settings put secure show_ime_with_hard_keyboard 1")
    for _ in range(3):
        R.shell("am force-stop com.google.android.apps.messaging")
        time.sleep(1)
        R.shell("am start -a android.intent.action.SENDTO -d sms:10010")
        time.sleep(4)
        b = R.host_bounds()
        if b:
            R.shell("input tap %d %d" % b)
        time.sleep(2.5)
        top, bot, h = R.ime_frame()
        if h and h > 700:          # 全键盘高度量级；系统弹窗的 IME inset 会明显更矮
            return True
    return False


def pick_first():
    """点首选候选（ITEM_BG 高亮盒中心），返回 (点击坐标, 候选数)。"""
    st = R.band_struct("_pick")
    first = st.get("first_item_fill")
    if not first:
        return None, st
    x = (first["left"] + first["right"]) // 2
    y = (first["top"] + first["bot"]) // 2
    R.shell("input tap %d %d" % (x, y))
    time.sleep(1.5)
    return (x, y), st


def case1(tag):
    """zhi → 点首选(之) → 空格。"""
    clear_ok = ensure_host()
    R.clear()
    R.type_str("zhi")
    time.sleep(1.2)
    before = R.host_text()
    logcat_clear()
    coord, st = pick_first()
    after_pick = R.host_text()
    time.sleep(0.6)
    hits, miss = logcat_assoc()
    # 空格：联想存活则应上屏整词；联想死则应只多一个空格
    R.shell("input keyevent 62")
    time.sleep(1.5)
    after_space = R.host_text() or ""
    delta = after_space[len(after_pick or ""):] if after_pick else ""
    print("[%s] case1 zhi→之→空格" % tag, flush=True)
    print("  host_ready=%s typed=%r pick=%r after_pick=%r" % (clear_ok, before, coord, after_pick), flush=True)
    print("  cand_count=%s highlight=%s" % (st.get("cand_count"), st.get("first_item_fill")), flush=True)
    print("  logcat_assoc_hit=%d miss=%d" % (len(hits), len(miss)), flush=True)
    for ln in hits + miss:
        print("    " + ln.strip(), flush=True)
    print("  after_space_delta=%r (len=%d)" % (delta, len(delta)), flush=True)
    verdict = "PASS(联想存活)" if hits else "FAIL(联想未出)"
    if hits and delta.strip() == "" and len(delta) <= 1:
        verdict += " 但空格未上屏联想词"
    print("  VERDICT %s" % verdict, flush=True)
    R.shot("voca173_%s_case1.png" % tag)
    return dict(after_pick=after_pick, delta=delta, hits=len(hits), miss=len(miss))


def case2(tag):
    """zhidao → 点首选(指导老师) → 读联想条首个以「师」开头的候选。"""
    ensure_host()
    R.clear()
    R.type_str("zhidao")
    time.sleep(1.5)
    before = R.host_text()
    logcat_clear()
    coord, st = pick_first()
    after_pick = R.host_text()
    time.sleep(0.6)
    hits, miss = logcat_assoc()
    # 联想条结构：再量一次，读首项宽度（用于后续 AC-1 点选）
    st2 = R.band_struct("_assoc")
    print("[%s] case2 zhidao→指导老师→联想" % tag, flush=True)
    print("  typed=%r pick=%r after_pick_tail=%r" % (before, coord, (after_pick or "")[-12:]), flush=True)
    print("  logcat_assoc_hit=%d miss=%d" % (len(hits), len(miss)), flush=True)
    for ln in hits + miss:
        print("    " + ln.strip(), flush=True)
    print("  assoc_band_cand_count=%s first=%s" % (st2.get("cand_count"), st2.get("first_item_fill")), flush=True)
    print("  VERDICT %s" % ("PASS(点选后联想存活)" if hits else "FAIL(点选后联想未出)"), flush=True)
    R.shot("voca173_%s_case2.png" % tag)
    return dict(after_pick_tail=(after_pick or "")[-12:], hits=len(hits))


if __name__ == "__main__":
    tag = sys.argv[1] if len(sys.argv) > 1 else "unknown"
    which = sys.argv[2] if len(sys.argv) > 2 else "all"
    print("=== TAG=%s ===" % tag, flush=True)
    print("version=%s sha=%s" % (
        R.shell("dumpsys package com.ispeech.ime | grep versionName | head -1").strip(),
        R.shell("pm path com.ispeech.ime").strip()), flush=True)
    if which in ("all", "1"):
        case1(tag)
    if which in ("all", "2"):
        case2(tag)