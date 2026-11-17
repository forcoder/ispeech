#!/usr/bin/env python3
"""VOCA-47 取证工具：按规格 §8.1 / §8.3 / §8.4 的手工口径取数。

子命令：
  frame            InsetsSource type=ime 的 frame 高度（占屏口径）+ mImeHeight 对账
  text             uiautomator 里 focused 节点（宿主 EditText）的 text 真值
  tap-text <串>    按 text / content-desc 找宿主节点并点其中心
  shot <png>       exec-out screencap
  bands <png>      §8.3 整行均匀度 → 组件带边界
  shift <a> <b> <y0> <y1>   §8.4 列剖面互相关 → 位移 px + 残差
  ink <png> <y0> <y1>        候选带内文字墨迹的上下边界（判截字）
"""
import subprocess, sys, re, os

ADB = "/opt/homebrew/share/android-commandlinetools/platform-tools/adb"
SERIAL = os.environ.get("QA_SERIAL", "emulator-5554")
W, H = 1080, 2340


def sh(args, binary=False):
    r = subprocess.run([ADB, "-s", SERIAL] + args,
                       capture_output=True)
    if r.returncode != 0:
        raise SystemExit("adb failed: %s\n%s" % (args, r.stderr.decode("utf8", "replace")))
    return r.stdout if binary else r.stdout.decode("utf8", "replace")


def shell(cmd, binary=False):
    return sh(["shell", cmd], binary)


def dump(path="/sdcard/wdump.xml"):
    shell("uiautomator dump %s" % path)
    return shell("cat %s" % path)


def gray(px):
    return (px[0] * 299 + px[1] * 587 + px[2] * 114) // 1000


def profile(img, y0, y1, step=2):
    px = img.load()
    ys = list(range(y0, y1))
    return [(gray(px[x, y]) for y in ys) and sum(gray(px[x, y]) for y in ys) // len(ys)
            for x in range(0, W, step)]


def node_bounds(needle, xml=None):
    x = xml or dump()
    for node in re.finditer(r'<node[^>]*>', x):
        s = node.group(0)
        if needle in s:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            return tuple(map(int, b.groups())), s
    return None, None


def cmd_host():
    """拉起宿主输入框（设置搜索页 EditText）并确认键盘已弹，重试 3 次。"""
    ime = "com.ispeech.ime/com.ispeech.input.IspeechImeService"
    for attempt in range(3):
        shell("ime enable %s" % ime)
        shell("ime set %s" % ime)
        shell("am start -n com.android.settings/.Settings")
        import time
        time.sleep(2.5)
        b, _ = node_bounds("搜索设置")
        if not b:
            b, _ = node_bounds("search_action_bar")
        if b:
            shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
            time.sleep(2)
        for _ in range(3):
            f = frame_height()
            if f and f > 0:
                print("HOST\tready\tframe=%d\tattempt=%d" % (f, attempt + 1))
                return
            b, _ = node_bounds("open_search_view_edit_text")
            if b:
                shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
            time.sleep(1.5)
    raise SystemExit("HOST\tFAILED")


def frame_height():
    out = shell("dumpsys window displays")
    m = re.findall(r"InsetsSource id=\d+ type=ime frame=\[0,(\d+)\]\[(\d+),(\d+)\]", out)
    return int(m[-1][1]) - int(m[-1][0]) if m else None


def cmd_frame():
    out = shell("dumpsys window displays")
    m = re.findall(r"InsetsSource id=\d+ type=ime frame=\[0,(\d+)\]\[(\d+),(\d+)\]", out)
    mi = re.search(r"mImeHeight=(\d+)", out)
    if not m:
        print("FRAME\t-1\tno ime inset (键盘没弹?)")
        return
    top, bot = int(m[-1][1]), int(m[-1][2])
    print("FRAME\t%d\ttop=%d bottom=%d\tmImeHeight=%s" % (bot - top, top, bot,
                                                          mi.group(1) if mi else "-"))



def cmd_text():
    x = dump()
    node = re.search(r'<node[^>]*focused="true"[^>]*>', x)
    if not node:
        print("TEXT\t<NONE>")
        return
    t = re.search(r'text="([^"]*)"', node.group(0))
    rid = re.search(r'resource-id="([^"]*)"', node.group(0))
    pkg = re.search(r'package="([^"]*)"', node.group(0))
    print("TEXT\t%r\tid=%s\tpkg=%s" % (t.group(1) if t else None,
                                       rid.group(1) if rid else "-", pkg.group(1) if pkg else "-"))


def cmd_tap_text(needle):
    x = dump()
    for node in re.finditer(r'<node[^>]*>', x):
        s = node.group(0)
        if needle in s:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            x0, y0, x1, y1 = map(int, b.groups())
            shell("input tap %d %d" % ((x0 + x1) // 2, (y0 + y1) // 2))
            print("TAPPED\t%s\t%d,%d" % (needle, (x0 + x1) // 2, (y0 + y1) // 2))
            return
    raise SystemExit("node not found: %s" % needle)


def cmd_shot(path):
    data = sh(["exec-out", "screencap", "-p"], binary=True)
    open(path, "wb").write(data)
    print("SHOT\t%s\t%d bytes" % (path, len(data)))


def cmd_bands(path):
    from PIL import Image
    img = Image.open(path).convert("RGB")
    px = img.load()
    rows = []
    for y in range(0, H):
        vals = [px[x, y] for x in range(30, W - 30, (W - 60) // 12)]
        rows.append(len(set(vals)) > 1)
    out, y = [], 0
    while y < H:
        y2 = y
        while y2 < H and rows[y2] == rows[y]:
            y2 += 1
        out.append((y, y2, rows[y]))
        y = y2
    for y0, y1, content in out:
        if y1 - y0 >= 8:
            print("BAND\t%s\t[%d,%d)\t%d px\t%.1f dp" % ("content" if content else "bg",
                                                         y0, y1, y1 - y0, (y1 - y0) / 2.75))


def cmd_shift(a, b, y0, y1):
    from PIL import Image
    ia = Image.open(a).convert("RGB")
    ib = Image.open(b).convert("RGB")
    pa, pb = profile(ia, int(y0), int(y1)), profile(ib, int(y0), int(y1))
    n, step = len(pa), 2
    best, best_s = None, None
    lo, hi = max(1, n - 600 // step), n
    for s in range(-600 // step, 600 // step + 1):
        i0, i1 = max(0, -s), min(n, n - s)
        if i1 - i0 < 100:
            continue
        err = sum(abs(pb[i] - pa[i + s]) for i in range(i0, i1)) / (i1 - i0)
        if best is None or err < best:
            best, best_s = err, s
    print("SHIFT\t%d px\tresidual=%.2f\t(loose=%s)" % (best_s * step, best, lo < n))


def cmd_ink(path, y0, y1):
    """候选带内非背景像素（墨迹）的行范围 —— 判截字。"""
    from PIL import Image
    img = Image.open(path).convert("RGB")
    px = img.load()
    # 背景 = 带上众数色
    from collections import Counter
    c = Counter(px[x, (int(y0) + int(y1)) // 2] for x in range(0, W, 3))
    bg = c.most_common(1)[0][0]
    ink_rows = [y for y in range(int(y0), int(y1))
                if any(px[x, y] != bg for x in range(0, W, 2))]
    if not ink_rows:
        print("INK\t(no ink in band)")
        return
    print("INK\ttop=%d bottom=%d\th=%d px / %.1f dp\tband=[%d,%d) touch_top=%s touch_bottom=%s"
          % (ink_rows[0], ink_rows[-1], ink_rows[-1] - ink_rows[0] + 1,
             (ink_rows[-1] - ink_rows[0] + 1) / 2.75, int(y0), int(y1),
             ink_rows[0] == int(y0), ink_rows[-1] == int(y1) - 1))


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    c, a = sys.argv[1], sys.argv[2:]
    {"frame": cmd_frame, "host": cmd_host, "text": cmd_text, "tap-text": cmd_tap_text, "shot": cmd_shot,
     "bands": cmd_bands, "shift": cmd_shift, "ink": cmd_ink}[c](*a)


main()
