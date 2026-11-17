#!/usr/bin/env python3
"""VOCA-37 Stage-3 回归驱动：只用 adb，测量口径 = spec §8.1 ime inset + §8.4 列剖面互相关。"""
import subprocess, sys, time, re, os
from PIL import Image

ADB = os.environ.get("QA_ADB", "/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
# 第七轮打回项 2：工装原本只能跑 emulator-5554 + 1080×2340，换设备/换面板直接跑不通
# （ppwin 的 pp 分母、band 的采样右界、key_map 的列扫描都写死 1080）。序列号与面板
# 一律从设备现读，环境变量只作为显式覆盖。
S = os.environ.get("QA_SERIAL", "emulator-5554")
IME = "com.ispeech.ime/com.ispeech.input.IspeechImeService"
Q = os.path.dirname(os.path.abspath(__file__))
DEN = 2.75  # 440dpi -> px/dp（兜底值；实测路径一律用 live_den() 现读）


def panel_size():
    """当前生效的面板像素宽/高：Override 优先，没有 Override 才用 Physical。"""
    out = shell("wm size")
    ov = re.search(r"Override size:\s*(\d+)x(\d+)", out)
    ph = re.search(r"Physical size:\s*(\d+)x(\d+)", out)
    m = ov or ph
    if not m:
        return None, None
    return int(m.group(1)), int(m.group(2))


def set_panel(size):
    """size='1260x2720' 覆盖面板分辨率 / 'reset' 还原到物理分辨率。返回生效后的 (w,h)。"""
    shell("wm size %s" % size)
    time.sleep(2.5)
    return panel_size()


def live_density():
    out = shell("wm density")
    m = re.search(r"Override density:\s*(\d+)", out) or re.search(r"Physical density:\s*(\d+)", out)
    return int(m.group(1)) if m else 440


def live_den():
    """px/dp，按设备当前生效密度现算（520dpi → 3.25，不是 440 的 2.75）。"""
    return live_density() / 160.0


def logic_width():
    w, _ = panel_size()
    return round(w * 160.0 / live_density(), 1) if w else None


def orig_state():
    """机主原值快照。收尾必须还原到这三个，而不是硬编码回 440/1.0/1080x2340 ——
    共享 AVD 上 density/size 可能是别的 run 留下的 Override，只有「按原样写回」才算复原。"""
    return dict(density=shell("wm density").strip().replace("\n", " | "),
                size=shell("wm size").strip().replace("\n", " | "),
                font_scale=shell("settings get system font_scale").strip())


def restore_state(orig):
    """原本是 Override 就写回那个 Override，原本没有才 reset。
    font_scale 同理：机主没设过就删键，写 1.0 会让 `settings get` 从 null 变成有值。"""
    d = re.search(r"Override density:\s*(\d+)", orig.get("density") or "")
    shell("wm density %s" % d.group(1) if d else "wm density reset")
    s = re.search(r"Override size:\s*(\d+x\d+)", orig.get("size") or "")
    shell("wm size %s" % s.group(1) if s else "wm size reset")
    fs = (orig.get("font_scale") or "").strip()
    if fs in ("", "null"):
        shell("settings delete system font_scale")
    else:
        shell("settings put system font_scale %s" % fs)
    time.sleep(1.0)

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


def prof(path, y0, y1, step=2, x0=4, x1=1076):
    px = Image.open(path).convert("RGB").load()
    return [sum(sum(px[x, y]) / 3.0 for y in range(y0, y1)) / (y1 - y0)
            for x in range(x0, x1, step)]


def shift(a, b, y0, y1, step=2, x0=4, x1=1076):
    """候选带列剖面互相关：返回 (位移px, 残差)。正 = 内容左移 = 向右翻页。
    x0/x1 只取候选区（chip 右沿+1 起）时，静态 chip 不会把相关拉平。"""
    pa, pb = prof(a, y0, y1, step, x0, x1), prof(b, y0, y1, step, x0, x1)
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
    # 第七轮打回项 1：每行都要能被追到当场时刻，不然读者只能追到入库提交。
    kw.setdefault("ts", time.strftime("%Y-%m-%dT%H:%M:%S%z"))
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

# 键位坐标一律**从当场截图反推**，不写死像素。
# 历史教训：本工装曾写死 440dpi 的一排像素（ROW1/ROW2/ROW3/YROW），那套数既没按密度换算
# （520dpi 下整行错位），中心本身也和真实布局对不上（'o' 写成 918，实测键心 804）——
# 结果第四轮把 zhongguo 打成 hogguo / khk，候选条直接空掉，量到的「组合态」其实是 chip-only。
# 现在 key_map() 按白底键帽列段取键心，tap_keys() 敲完必须回读宿主文本校验，不一致就出不了数。
ROW_LETTERS = ["qwertyuiop", "asdfghjkl", "zxcvbnm"]


def key_map(png=None, frame=None, min_frac=0.4, min_w=28, merge_gap=8):
    """从截图反推键位 → ({ch:(x,y)}, 诊断 dict)。

    键帽 = 白底（min 通道 ≥250）在键带里占比 ≥min_frac 的连续列段；字母行按「段数 =
    10/9/7」认领（不靠行序：520dpi 下 `rows()` 会把一行拆成两段，段数才是稳定特征），
    退格取第三行最后一段右沿到屏幕右边的中点，空格取第三行之后最宽的白段（≥200px，
    灰色功能键不产生白段）。
    """
    top, bot, h = frame or ime_frame()
    if not h:
        return {}, dict(reason="no_ime_frame")
    p = png or shot("_kbd_map.png")
    raw = rows(p, top, bot)
    # 合并「同一行被笔画切断」的小间隙，但**不许把相邻两行并成一行**：VOCA-184 把字母键
    # 行内留白从 4dp 收到 1dp（d283706），行间隙只剩 ~5px，老的 merge_gap=8 会把 qwerty 行
    # 和 asdf 行粘成一个带，列段数就不再是 10/9/7，整套键位反推直接失效。
    max_row_px = int(52 * live_den())      # 单行键高上限 52dp（MAX_KEY_HEIGHT_DP 同口径），按现读密度换算
    bands = []
    for a, b in raw:
        if bands and a - bands[-1][1] <= merge_gap and b - bands[-1][0] <= max_row_px:
            bands[-1] = (bands[-1][0], b)
        else:
            bands.append((a, b))
    img = Image.open(p).convert("RGB")
    W_img = img.size[0]                    # 列扫描按截图实际宽度，不再写死 1080
    px = img.load()

    def runs_in(a, b):
        ys = list(range(a + 4, b - 4))
        n = float(len(ys))
        frac = [sum(1 for y in ys if min(px[x, y]) >= 250) / n for x in range(W_img)]
        out, x = [], 0
        while x < W_img:
            if frac[x] >= min_frac:
                x2 = x
                while x2 + 1 < W_img and frac[x2 + 1] >= min_frac:
                    x2 += 1
                if x2 - x + 1 >= min_w:
                    out.append((x, x2, (x + x2) // 2))
                x = x2 + 1
            else:
                x += 1
        return out

    per = [runs_in(a, b) for a, b in bands]
    diag = dict(bands_raw=len(raw), bands=[(a, b) for a, b in bands], runs=[len(r) for r in per])
    row_at = {}
    for i, letters in enumerate(ROW_LETTERS):
        hit = [j for j, r in enumerate(per) if len(r) == len(letters) and (not row_at or j > max(row_at.values()))]
        if not hit:
            diag["reason"] = "no_row_with_%d_keys" % len(letters)
            return {}, diag
        row_at[i] = hit[0]
    m = {}
    for i, letters in enumerate(ROW_LETTERS):
        j = row_at[i]
        yc = (bands[j][0] + bands[j][1]) // 2
        for ch, (l, r, c) in zip(letters, per[j]):
            m[ch] = (c, yc)
    j3 = row_at[2]
    pitch = (per[j3][-1][2] - per[j3][0][2]) / 6.0
    m["del"] = (int(round((per[j3][-1][1] + W_img) / 2.0)), (bands[j3][0] + bands[j3][1]) // 2)
    m["shift"] = (int(round(per[j3][0][0] - pitch / 2.0)), (bands[j3][0] + bands[j3][1]) // 2)
    diag["del_pitch_px"] = round(pitch, 1)
    diag["screen_w_px"] = W_img
    # 空格下限原来写死 200px = 440dpi 下的 72.7dp；换面板必须按现读密度换算，
    # 否则 520dpi 下 200px 只剩 61.5dp，会把功能键误认成空格。
    space_min_px = int(round(72.7 * live_den()))
    cands = [(r[1] - r[0] + 1, j, r) for j in range(j3 + 1, len(per)) for r in per[j]]
    if not cands or max(cands)[0] < space_min_px:
        diag["reason"] = "no_space_key"
        diag["space_min_px"] = space_min_px
        diag["widest_run_px"] = max(cands)[0] if cands else None
        return {}, diag
    w, j, r = max(cands)
    m[" "] = (r[2], (bands[j][0] + bands[j][1]) // 2)
    diag["space_band"] = (j, bands[j], w)
    diag["ok"] = True
    return m, diag


def tap_keys(s, gap=0.12, settle=0.45, m=None):
    """逐键点按 + 宿主回读校验：回读 ≠ 所敲串就返回 ok=False，调用方走闸门拒绝出数。"""
    m = m if m is not None else key_map()[0]
    miss = [c for c in s if c not in m]
    if miss:
        return dict(ok=False, want=s, got=None, reason="no_key_xy", missing="".join(miss))
    for ch in s:
        shell("input tap %d %d" % m[ch])
        time.sleep(gap)
    time.sleep(settle)
    got = focused_text()
    return dict(ok=got == s, want=s, got=got)


def hold_key(ms, ch="del", m=None):
    """长按某个软键 ms 毫秒：同点 swipe（down…hold…up），一次手势。"""
    m = m if m is not None else key_map()[0]
    if ch not in m:
        return None
    x, y = m[ch]
    shell("input swipe %d %d %d %d %d" % (x, y, x, y, int(ms)))
    return (x, y)


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
