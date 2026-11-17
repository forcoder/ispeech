#!/usr/bin/env python3
"""§3.1 D2 复跑工装：设置页「键盘与输入」卡片点击时序 + 崩溃判定。

为什么要脚本化（第五轮）：前两次当场手敲命令各踩一个坑——awk 把 bounds 里的 `][`
吃掉算出 y=425711（点到屏幕外，等于没点，FATAL=0 是假阴性），换 python 又因为正则分组
数不对直接抛异常。闸门型取证不能靠当场手敲，必须留一个可复跑的脚本。

时序（与第四轮 `ev_crash_kbsettings.txt` 一致，顺序不能换）：
  ① 起宿主并弹键盘（让 DataStore 进 IME 进程缓存——冷进程不复现，这是该缺陷的触发条件）
  ② 打开 App 设置页 MainActivity
  ③ 用 uiautomator 找「键盘与输入」卡片中心并点击（不写死像素：密度换了卡片位置就变）
  ④ 读 crash buffer + FATAL 计数 + IME pid 存活 + 当前页面
用法：python3 d2check.py [density]
退出码：0 = 未崩（修复成立）/ 1 = 崩了（附栈）/ 2 = 前置条件没起来（卡片没找到等），
        只有 0/1 才是 D2 的判定，2 必须重跑。
"""
import os, re, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D

DENS = sys.argv[1] if len(sys.argv) > 1 else None
CARD_TEXT = "键盘与输入"


def card_center():
    D.shell("uiautomator dump /sdcard/u.xml")
    xml = D.shell("cat /sdcard/u.xml")
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if CARD_TEXT in m.group(1):
            x1, y1, x2, y2 = (int(g) for g in m.groups()[1:])
            return ((x1 + x2) // 2, (y1 + y2) // 2, "[%d,%d][%d,%d]" % (x1, y1, x2, y2))
    return None


def fatal_lines():
    """只统计**本包进程**的 FATAL 块。共享 AVD 上别的 run 崩的是别的包，
    不加过滤会把别人的崩溃算到这条判据头上。"""
    out = D.shell("logcat -d -b crash") + "\n" + D.shell("logcat -d")
    blocks, cur, keep = [], [], False
    for ln in out.splitlines():
        if "FATAL EXCEPTION" in ln:
            cur, keep = [ln], False
        elif cur and len(cur) < 14:
            cur.append(ln)
            if "Process: com.ispeech.ime" in ln:
                keep = True
        elif cur and (not ln.strip() or "FATAL EXCEPTION" not in ln):
            if keep:
                blocks.extend(cur)
            cur, keep = [], False
    if cur and keep:
        blocks.extend(cur)
    return "\n".join(blocks)


def our_ime():
    """共享 AVD 上别的 run 会把默认输入法抢走（本轮 17:02 就发生了一次：Gboard 顶上，
    833px 几何 + pidof 为空，整条 D2 时序跑在别人家键盘上却报出 fatal_count=0）。
    D2 的判定必须以「被测 IME 在位」为前提，所以两端都要读。"""
    return D.shell("settings get secure default_input_method").strip()


if __name__ == "__main__":
    if DENS:
        D.shell("wm density %s" % DENS)
        D.shell("settings put system font_scale 1.0")
        time.sleep(1.0)
    st = D.host_ready()
    D.log("HOST", **{k: str(v) for k, v in st.items()})
    if not st.get("ok"):
        D.log("GATE", reason="host_not_ready", rc=2)
        raise SystemExit(2)
    ime0 = our_ime()
    D.log("IME_START", ime=ime0)
    if not ime0.startswith("com.ispeech.ime/"):
        D.log("GATE", reason="ime_not_ours", rc=17, current=ime0)
        raise SystemExit(17)
    # 判定窗口从清缓冲开始，否则读到的是上一个 run 的崩溃
    D.shell("logcat -c"); D.shell("logcat -b crash -c")
    # ⚠ 不要 force-stop com.ispeech.ime：杀掉当前选中的输入法进程会让系统把默认 IME
    #     回落到 Gboard（本轮三次 rc=17 全卡在这一步之后），D2 的时序就跑到了别人家键盘上。
    #     触发条件本来就只是「pm clear 后先弹一次键盘把 DataStore 读进进程」。
    D.shell("pm clear com.ispeech.ime")
    time.sleep(1.5)
    D.shell("ime set com.ispeech.ime/com.ispeech.input.IspeechImeService")
    time.sleep(1.0)
    # pm clear 之后宿主侧的焦点/输入会话已经作废，必须重新起一次宿主并确认键盘真的弹出来
    st2 = D.host_ready()
    D.log("HOST2", **{k: str(v) for k, v in st2.items()})
    # ① 弹键盘（冷进程先跑一遍，让 IME 进程带 DataStore 缓存活起来）
    # ① 弹键盘（pm clear 后先跑一遍，让 IME 进程带 DataStore 缓存活起来）
    for attempt in range(3):
        D.type_str("shi", gap=0.05)
        time.sleep(1.0)
        top, bot, h = D.ime_frame()
        pid1 = D.shell("pidof com.ispeech.ime").strip()
        ime_mid = our_ime()
        if h and pid1 and ime_mid.startswith("com.ispeech.ime/"):
            break
        D.log("WARM_RETRY", attempt=attempt, h=h, pid=pid1 or "empty", ime=ime_mid)
        D.shell("input keyevent 111")
        time.sleep(1.0)
    D.log("FRAME_1", top=top, h=h, ime=ime_mid, pid=pid1)
    if not ime_mid.startswith("com.ispeech.ime/") or not pid1:
        D.log("GATE", reason="ime_not_ours", rc=17, current=ime_mid, pid=pid1 or "empty")
        raise SystemExit(17)
    if not h:
        # 键盘没弹出来 = DataStore 没进 IME 进程缓存 = D2 的触发前提不成立。
        # 这时候 fatal_count=0 是「没测到」而不是「修好了」，必须拒绝出数。
        D.log("GATE", reason="keyboard_not_shown", rc=19, h=h)
        raise SystemExit(19)
    D.shell("input keyevent 111")
    time.sleep(0.6)
    # ② 设置页
    D.shell("am start -n com.ispeech.ime/com.ispeech.settings.MainActivity")
    time.sleep(3.0)
    # ③ 点卡片
    c = card_center()
    if not c:
        D.log("GATE", reason="card_not_found", rc=2)
        raise SystemExit(2)
    x, y, bounds = c
    D.log("CARD", bounds=bounds, tap="%d,%d" % (x, y))
    D.shell("input tap %d %d" % (x, y))
    time.sleep(5.0)
    # ④ 判定
    pid2 = D.shell("pidof com.ispeech.ime").strip()
    page = D.shell("dumpsys activity activities | grep -m1 topResumedActivity").strip()
    crash = fatal_lines()
    n = len(re.findall(r"FATAL EXCEPTION", crash))
    kb = len(re.findall(r"KeyboardViewModel", crash))
    D.log("VERDICT", fatal_count=n, keyboardviewmodel_frames=kb,
          ime_end=our_ime(), pid_after=pid2 or "empty",
          page=page[:120],
          d2="FIXED (no FATAL)" if n == 0 else "STILL_CRASHING")
    if n:
        for ln in crash.splitlines():
            if "FATAL" in ln or "KeyboardViewModel" in ln or "SettingsStore" in ln or "Process:" in ln:
                D.log("STACK", line=ln.strip()[:180])
    raise SystemExit(0 if n == 0 else 1)
