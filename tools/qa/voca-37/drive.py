#!/usr/bin/env python3
"""VOCA-37 Stage-3 回归驱动：只用 adb，测量口径 = spec §8.1 ime inset + §8.4 列剖面互相关。"""
import subprocess, sys, time, re, os
from PIL import Image

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
S = "emulator-5554"
IME = "com.ispeech.ime/com.ispeech.input.IspeechImeService"
Q = os.path.dirname(os.path.abspath(__file__))
DEN = 2.75  # 440dpi -> px/dp

KC = {c: 29 + i for i, c in enumerate("abcdefghijklmnopqrstuvwxyz")}
KC.update({str(i): (11 if i == 0 else 8 + i) for i in range(10)})
KC.update({" ": 62, "-": 69, "=": 70, "del": 67, "enter": 66, "back": 4, "tab": 61,
           "shift": 59, " ": 62})


def sh(args):
    return subprocess.run([ADB, "-s", S] + args, capture_output=True, text=True).stdout


def shell(cmd):
    return sh(["shell"] + cmd.split())


def ime_frame():
    out = shell("dumpsys window displays")
    m = re.search(r"InsetsSource id=\d+ type=ime frame=\[0,(\d+)\]\[(\d+),(\d+)\][^\n]*?visible=true", out)
    return (int(m.group(1)), int(m.group(3)), int(m.group(3)) - int(m.group(1))) if m else (None, None, None)


def type_str(s, gap=0.06):
    for ch in s:
        code = KC.get(ch.lower()) if ch != " " else 62
        if code is None:
            continue
        shell("input keyevent %d" % code)
        time.sleep(gap)


def clear(n=25):
    shell("input keyevent KEYCODE_MOVE_END")
    for _ in range(n):
        shell("input keyevent 67")
    time.sleep(0.3)


def host_ready(rounds=4, settle=2.0):
    """配置变更（wm density / font_scale）或重装 IME 后，showSoftInput 请求常被 WindowManager 丢掉：
    `mServedInputConnection` 已经绑到 open_search_view_edit_text，但 `type=ime` inset 还是 visible=false。
    所以每轮把两个 Settings 包都 force-stop 重新起，并对输入框连点几次补 show 请求。
    实测：复用上一个 density 下残留的 SearchActivity 时，ensure_host 6 次重试全 FAIL。"""
    shell("settings put secure autofill_service null")
    last = ""
    for r in range(rounds):
        shell("am force-stop com.android.settings")
        shell("am force-stop com.google.android.settings.intelligence")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("ime enable %s" % IME)
        shell("ime set %s" % IME)
        shell("am start -W -n com.android.settings/.Settings")
        time.sleep(settle)
        for taps in range(1, 5):
            b = node_bounds("open_search_view_edit_text") or node_bounds("搜索设置")
            if b:
                shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
            time.sleep(1.2)
            top, bot, h = ime_frame()
            if h:
                return dict(ok=True, round=r + 1, taps=taps, top=top, h=h)
            last = re.sub(r"\s+", " ", shell("dumpsys input_method | grep mCurMethodId")).strip()[:70]
        shell("am force-stop com.ispeech.ime")
        time.sleep(1.0)
    return dict(ok=False, rounds=rounds, last=last)


def ensure_host(retries=4):
    """拉起 Settings 搜索页宿主输入框，等 IME 可见。"""
    shell("settings put secure autofill_service null")
    for i in range(retries):
        t0 = time.time()
        shell("ime enable %s" % IME)
        shell("ime set %s" % IME)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("am start -n com.android.settings/.Settings")
        time.sleep(2.2)
        b = node_bounds("open_search_view_edit_text") or node_bounds("搜索设置")
        if b:
            shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
        for _ in range(6):
            time.sleep(0.8)
            top, bot, h = ime_frame()
            if h:
                return dict(ok=True, attempt=i + 1, secs=round(time.time() - t0, 1), top=top, h=h)
        shell("am force-stop com.ispeech.ime")
    return dict(ok=False)


def node_bounds(needle):
    shell("uiautomator dump /sdcard/wd.xml")
    x = shell("cat /sdcard/wd.xml")
    for n in re.finditer(r"<node[^>]*>", x):
        s = n.group(0)
        if needle in s:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            if b:
                return tuple(int(v) for v in b.groups())
    return None


def focused_text():
    shell("uiautomator dump /sdcard/wd.xml")
    x = shell("cat /sdcard/wd.xml")
    n = re.search(r'<node[^>]*focused="true"[^>]*>', x)
    if not n:
        return "<NONE>"
    t = re.search(r'text="([^"]*)"', n.group(0))
    return t.group(1) if t else ""


def shot(name):
    p = os.path.join(Q, name)
    with open(p, "wb") as f:
        f.write(subprocess.run([ADB, "-s", S, "exec-out", "screencap", "-p"],
                               capture_output=True).stdout)
    return p


def rows(path, y0=1352, y1=2340):
    px = Image.open(path).convert("RGB").load()
    xs = list(range(8, 1076, 12))
    frac = [sum(1 for x in xs if min(px[x, y]) >= 250) / len(xs) for y in range(y0, y1)]
    out, s = [], None
    for i, f in enumerate(frac):
        y = y0 + i
        if f > 0.35 and s is None:
            s = y
        elif f <= 0.35 and s is not None:
            out.append((s, y)); s = None
    if s is not None:
        out.append((s, y1))
    return out


def prof(path, y0, y1, step=2):
    px = Image.open(path).convert("RGB").load()
    band = [[sum(sum(px[x, y]) / 3.0 for y in range(y0, y1)) / (y1 - y0)
             for x in range(4, 1076, step)] for _ in (0,)]
    return band[0]


def shift(a, b, y0, y1, step=2):
    """候选带列剖面互相关：返回 (位移px, 残差)。正 = 内容左移 = 向右翻页。"""
    pa, pb = prof(a, y0, y1), prof(b, y0, y1)
    n = len(pa)
    best = (None, None)
    for s in range(-600, 601):
        err = [abs(pa[i] - pb[i + s]) for i in range(n) if 0 <= i + s < n]
        if len(err) < 100:
            continue
        e = sum(err) / len(err)
        if best[0] is None or e < best[1]:
            best = (s, e)
    return (-best[0] * step, best[1]) if best[0] is not None else (None, None)


def log(tag, **kw):
    print("%s\t%s" % (tag, "\t".join("%s=%s" % (k, v) for k, v in kw.items())), flush=True)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "setup":
        log("SETUP", **ensure_host())
        log("FRAME", *ime_frame()[:1], h=ime_frame()[2]) if False else None
        top, bot, h = ime_frame()
        log("FRAME", top=top, h=h)
    elif cmd == "host":
        log("HOST_READY", **host_ready())
    elif cmd == "frame":
        top, bot, h = ime_frame()
        log("FRAME", top=top, h=h, ime=ime_frame())
    elif cmd == "type":
        type_str(sys.argv[2]); time.sleep(float(sys.argv[3]) if len(sys.argv) > 3 else 0.8)
    elif cmd == "clear":
        clear()
    elif cmd == "text":
        log("HOSTTEXT", v=repr(focused_text()))
    elif cmd == "shot":
        log("SHOT", p=shot(sys.argv[2]))
    elif cmd == "rows":
        p = shot(sys.argv[2])
        rs = rows(p)
        log("ROWS", img=sys.argv[2], n=len(rs),
            hs=",".join("%d(%.1fdp)" % (b - a, (b - a) / DEN) for a, b in rs),
            pitch=",".join(str(rs[i + 1][0] - rs[i][0]) for i in range(len(rs) - 1)))
    elif cmd == "shift":
        s, e = shift(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]))
        log("SHIFT", a=sys.argv[2], b=sys.argv[3], shift=s, err=round(e, 2))

ROW1 = {c: 54 + i*108 for i, c in enumerate("qwertyuiop")}
ROW2 = {c: 60 + i*120 for i, c in enumerate("asdfghjkl")}
ROW3 = {c: 162 + i*108 for i, c in enumerate("zxcvbnm")}
YROW = {"q": 1679, "a": 1827, "z": 1975, " ": 2125}

def tap_key(ch):
    if ch == " ":
        shell("input tap 532 2125"); return
    for table, y in ((ROW1, YROW["q"]), (ROW2, YROW["a"]), (ROW3, YROW["z"])):
        if ch in table:
            shell("input tap %d %d" % (table[ch], y)); return

def tap_keys(s, gap=0.12):
    for ch in s:
        tap_key(ch); time.sleep(gap)

def setup_contacts(retries=3):
    import re as _re
    shell("settings put secure autofill_service null")
    shell("am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact")
    time.sleep(2.5)
    for i in range(retries):
        shell("uiautomator dump /sdcard/wd.xml"); x = shell("cat /sdcard/wd.xml")
        tgt = None
        for n in _re.finditer(r'<node[^>]*class="android.widget.EditText"[^>]*>', x):
            s = n.group(0)
            if "电子邮件" in s or "电话" in s:
                b = _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
                tgt = tuple(int(v) for v in b.groups()); break
        if tgt:
            shell("input tap %d %d" % ((tgt[0]+tgt[2])//2, (tgt[1]+tgt[3])//2)); time.sleep(1.8)
            top, bot, h = ime_frame()
            if h:
                return dict(ok=True, field=tgt, top=top, h=h)
        shell("input keyevent 4"); time.sleep(1.0)
    return dict(ok=False)
