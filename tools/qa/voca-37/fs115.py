#!/usr/bin/env python3
"""VOCA-37 §字体缩放取证：恒高 46dp + font_scale 1.15 → 候选带是被压死(截字)还是涨高。
每一步都校验设备上安装的 APK 就是 c524351 的构建产物，防止共享模拟器被别的 run 换包。"""
import hashlib, os, subprocess, sys, time, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
from PIL import Image

APK = sys.argv[1] if len(sys.argv) > 1 else ""
DENS = sys.argv[2] if len(sys.argv) > 2 else "440"
CAND_DP = 46

def ident(name):
    p = f"{D.Q}/_ident_{name}.apk"
    r = subprocess.run([D.ADB, "-s", D.S, "shell", "pm path com.ispeech.ime"], capture_output=True, text=True)
    if "package:" not in r.stdout:
        D.log("IDENT", name=name, match="NO_APK")
        return False
    subprocess.run([D.ADB, "-s", D.S, "pull", r.stdout.strip().split("package:")[1], p], capture_output=True)
    def ent(path):
        z = zipfile.ZipFile(path)
        return {n: hashlib.sha256(z.read(n)).hexdigest()[:12] for n in sorted(z.namelist())
                if (n.startswith("classes") and n.endswith(".dex")) or n == "resources.arsc"}
    ok = ent(APK) == ent(p)
    top, bot, h = D.ime_frame()
    D.log("IDENT", name=name, t=time.strftime("%H:%M:%S"), match=ok,
          ime=D.shell("settings get secure default_input_method").strip(), pid=D.shell("pidof com.ispeech.ime").strip(), h=h)
    return ok

def ink_extent(png, y0, y1, x0=16, x1=1064, thr=45):
    """候选带内墨迹的上下留白（px），背景自适应。留白<=1 → 文字贴住带边，有裁切风险。"""
    px = Image.open(png).convert("RGB").load()
    bg = px[(x0 + x1) // 2, y0 + 2]
    hits = []
    for y in range(y0, y1):
        cnt = 0
        for x in range(x0, x1, 2):
            p = px[x, y]
            if max(abs(p[0] - bg[0]), abs(p[1] - bg[1]), abs(p[2] - bg[2])) > thr:
                cnt += 1
        if cnt >= 2:
            hits.append(y)
    if not hits:
        return None
    return dict(bg=bg, top_pad=hits[0] - y0, bot_pad=y1 - 1 - hits[-1], ink_h=hits[-1] - hits[0] + 1,
                band=y1 - y0, ink_top=hits[0], ink_bot=hits[-1])

def run(fs):
    subprocess.run([D.ADB, "-s", D.S, "shell", "wm", "density", DENS], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "system", "font_scale", fs], capture_output=True)
    time.sleep(1.0)
    st = D.ensure_host(retries=6)
    if not st.get("ok"):
        subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "set", D.IME], capture_output=True)
        st = D.setup_contacts(retries=4)
    if not st.get("ok"):
        D.log("RESULT", fs=fs, host="FAIL", **{k: v for k, v in st.items()})
        return
    D.clear(22)
    time.sleep(0.7)
    top0, bot0, h0 = D.ime_frame()
    p0 = D.shot("f%s_%s_e.png" % (DENS, fs))
    D.tap_keys("zhongguo", gap=0.14)
    time.sleep(1.3)
    top1, bot1, h1 = D.ime_frame()
    p1 = D.shot("f%s_%s_c.png" % (DENS, fs))
    dpi = int(DENS)
    band = int(round(CAND_DP * dpi / 160))
    d = (h1 - h0) if (h0 and h1) else None
    D.log("RESULT", fs=fs, dens=DENS, empty=h0, comp=h1, delta=d, band_px=band, top1=top1)
    for tag, p in (("empty", p0), ("comp", p1)):
        if top1 is None:
            continue
        r = ink_extent(p, top1 + 1, top1 + band - 1)
        D.log("INK", fs=fs, kind=tag, **(r or {"none": True}), band_inner=band - 2)
    # 候选词整体是否完整：量候选带下方是否有第二行内容（涨高会把键区下推）
    if top1 is not None:
        Image.open(p1).convert("RGB").crop((0, top1, 1080, top1 + int(round(56 * dpi / 160)))).save(p1[:-4] + "_cand.png")
    D.shell("input keyevent 111")
    time.sleep(0.5)

if __name__ == "__main__":
    r = subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-g", APK], capture_output=True, text=True, timeout=900)
    D.log("INSTALL", out=r.stdout.strip()[-60:], rc=r.returncode)
    if not ident("start"):
        sys.exit(2)
    subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "enable", D.IME], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "set", D.IME], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "secure", "autofill_service", "null"], capture_output=True)
    for fs in sys.argv[3].split(",") if len(sys.argv) > 3 else ["1.0", "1.15"]:
        run(fs)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "system", "font_scale", "1.0"], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "wm", "density", "reset"], capture_output=True)
    ident("end")
