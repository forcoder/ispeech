#!/usr/bin/env python3
"""VOCA-173 复跑：zhi→之→空格 与 zhidao→指导老师，读宿主文本做判定。

不猜坐标：所有按键走 `input keyevent`，候选点选走 band.analyze 量到的首选高亮盒
（ITEM_BG 51% 铺底 = index 0）中心；宿主文本一律从 uiautomator dump 的
focused="true" EditText 读，不读 IME 自己的节点。
"""
import os, re, subprocess, sys, time

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
S = os.environ.get("QA_SERIAL", "emulator-5554")
IME = "com.ispeech.ime/com.ispeech.input.IspeechImeService"
HOST = "com.google.android.apps.messaging"
Q = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, Q)
import band

KC = {c: 29 + i for i, c in enumerate("abcdefghijklmnopqrstuvwxyz")}


def sh(args, timeout=60):
    return subprocess.run([ADB, "-s", S] + args, capture_output=True, text=True, timeout=timeout).stdout


def shell(cmd):
    return sh(["shell"] + cmd.split())


def ime_frame():
    out = shell("dumpsys window displays")
    m = re.search(r"InsetsSource id=\d+ type=ime frame=\[0,(\d+)\]\[(\d+),(\d+)\][^\n]*?visible=true", out)
    return (int(m.group(1)), int(m.group(3)), int(m.group(3)) - int(m.group(1))) if m else (None, None, None)


def host_text():
    """读 focused EditText 的文本；IME 候选条不走 uiautomator，所以这里只会拿到宿主输入框。"""
    shell("uiautomator dump /sdcard/hv.xml")
    x = shell("cat /sdcard/hv.xml")
    for n in re.finditer(r"<node[^>]*>", x):
        s = n.group(0)
        if 'focused="true"' in s and ("EditText" in s or "open_search_view_edit_text" in s):
            m = re.search(r'text="([^"]*)"', s)
            return m.group(1) if m else ""
    return None


def host_bounds():
    shell("uiautomator dump /sdcard/hb.xml")
    x = shell("cat /sdcard/hb.xml")
    for n in re.finditer(r"<node[^>]*>", x):
        s = n.group(0)
        if 'focused="true"' in s and "EditText" in s:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            if b:
                v = [int(g) for g in b.groups()]
                return ((v[0] + v[2]) // 2, (v[1] + v[3]) // 2)
    return None


def type_str(s, gap=0.10):
    for ch in s:
        code = KC.get(ch)
        if code is None:
            continue
        shell("input keyevent %d" % code)
        time.sleep(gap)


def shot(name):
    p = os.path.join(Q, name)
    with open(p, "wb") as f:
        f.write(subprocess.run([ADB, "-s", S, "exec-out", "screencap", "-p"],
                               capture_output=True).stdout)
    return p


def clear(n=30):
    shell("input keyevent KEYCODE_MOVE_END")
    for _ in range(n):
        shell("input keyevent 67")
    time.sleep(0.4)


def band_struct(tag=""):
    """一组像素读数：候选带里的候选项 + 首选高亮盒（决定点选坐标）。"""
    top, bot, h = ime_frame()
    if not h:
        return dict(ok=False, reason="no_ime_visible")
    p = shot("_band%s.png" % tag)
    dpi = 440
    m = re.search(r"Override density:\s*(\d+)", shell("wm density"))
    if m:
        dpi = int(m.group(1))
    # 候选带 = IME 顶边起 46dp（candidate_view 恒高）
    band_px = round(46 * dpi / 160.0)
    st = band.analyze(p, top, band_px, dpi, chip_required=False)
    st["png"] = p
    st["ime_top"] = top
    st["band_px"] = band_px
    return st


def tap_first_candidate(tag=""):
    """点首选候选：ITEM_BG 高亮盒中心（index==0 才有这块铺底）。"""
    st = band_struct("_c" + tag)
    if not st.get("ok"):
        return None, st
    first = st.get("first_item_fill")
    if not first:
        return None, st
    x = (first["left"] + first["right"]) // 2
    y = (first["top"] + first["bot"]) // 2
    shell("input tap %d %d" % (x, y))
    return (x, y), st


def run_case(name, code, expect_pick):
    """code: 拼音串；expect_pick: 点选后期望的宿主文本（点候选后立即读）。"""
    print("### CASE %s  code=%s" % (name, code), flush=True)
    clear()
    type_str(code)
    time.sleep(1.2)
    t_before = host_text()
    st = band_struct("_" + name)
    items = st.get("items") or []
    first = st.get("first_item_fill")
    print("  typed=%r  ime_top=%s band_px=%s  cand_count=%s  highlight=%s"
          % (t_before, st.get("ime_top"), st.get("band_px"), st.get("cand_count"), first), flush=True)
    print("  items=%d" % len(items), flush=True)
    for it in items[:6]:
        print("    idx=%s glyph_x=[%s,%s] right=%s" % (it.get("idx"), it.get("glyph_left"),
                                                       it.get("glyph_right"), it.get("item_right")), flush=True)
    if not first:
        print("  RESULT %s: FAIL(no_highlight_box)" % name, flush=True)
        return dict(name=name, ok=False, reason="no_highlight")
    x = (first["left"] + first["right"]) // 2
    y = (first["top"] + first["bot"]) // 2
    print("  tap_first=(%d,%d)" % (x, y), flush=True)
    shell("input tap %d %d" % (x, y))
    time.sleep(1.5)
    t_pick = host_text()
    print("  after_pick=%r" % (t_pick,), flush=True)
    return dict(name=name, ok=True, after_pick=t_pick, before=t_before,
                highlight=(x, y), cand_count=st.get("cand_count"), png=st.get("png"))


def wait_host():
    shell("settings put secure show_ime_with_hard_keyboard 1")
    for _ in range(6):
        top, bot, h = ime_frame()
        if h:
            return True
        shell("am start -a android.intent.action.SENDTO -d sms:10010")
        time.sleep(2.0)
        b = host_bounds()
        if b:
            shell("input tap %d %d" % b)
        time.sleep(1.5)
    return False


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd == "host":
        print("host_ready:", wait_host(), "ime_frame:", ime_frame())
        print("text:", repr(host_text()))
    elif cmd == "text":
        print(repr(host_text()))
    elif cmd == "zhi":
        print(run_case("zhi", "zhi", "之"))
    elif cmd == "zhidao":
        print(run_case("zhidao", "zhidao", "指导老师"))
    elif cmd == "frame":
        print(ime_frame())
    elif cmd == "tap":
        print(tap_first_candidate()[0])