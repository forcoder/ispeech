#!/usr/bin/env python3
"""打回项 1（第六轮）：占屏对照物**同 run 窗口**重测。

第五轮报告 §2 写「本轮所有对照值均为当场重测」，但 §5 表里搜狗那两列的 978/1002 出自首测窗口
03:40–05:20（`git log --diff-filter=A` 追到 `43e1718`），iSpeech 那两列出自 15:05–17:25 ——
两列不同窗，而共享 AVD 当天被别的 run 换包 3 次。本脚本把两侧读数拉到**同一个时间窗**内：

  1. `--ime sogou` 或 `--ime ispeech`（两个 IME 用同一套宿主、同一套取数口径，只换输入法）；
  2. 逐档 `wm density` + `font_scale` → 起宿主 → 等 `type=ime` inset visible → 读 frame；
  3. 每行都带 `ts=`（本地时间）与 `ime_id=` 当场回读，窗口首尾再打一行 `WINDOW`；
  4. 只测高度，不判 pp —— pp 由 `pp=` 字段（h/2340）现算，报告里两列相减。

用法：
  python3 ppwin.py --ime sogou    --tiers 440:1.0,440:1.15,520:1.15
  python3 ppwin.py --ime ispeech  --apk /path/app-debug.apk --tiers 440:1.0,440:1.15,520:1.15

退出码：0 全部档出数 / 30 某档键盘没起来（该档不打数，stdout 只有一行 GATE）
"""
import argparse, hashlib, os, re, subprocess, sys, time, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D

W, H = 1080, 2340
IME_ID = dict(sogou="com.sohu.inputmethod.sogou/.SogouIME", ispeech=D.IME)
TAG = dict(sogou="SOGOU", ispeech="ISPEECH")
# 「组合态」的敲码口径：两侧都敲同一串，避免拿搜狗的空闲态比 iSpeech 的组合态
CODE = "shijian"


def ts():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def log(kind, **kw):
    print(kind + "\t" + "\t".join("%s=%s" % (k, v) for k, v in kw.items()), flush=True)


def raw_ime_lines():
    out = D.shell("dumpsys window displays")
    return [ln.strip() for ln in out.splitlines() if "InsetsSource" in ln and "type=ime" in ln]


def ident(apk):
    """装的是不是这份构建产物：逐条目比 dex + resources.arsc（整包 hash 会漂）。"""
    p = os.path.join(D.Q, "_ident.apk")
    r = subprocess.run([D.ADB, "-s", D.S, "shell", "pm path com.ispeech.ime"], capture_output=True, text=True)
    if "package:" not in r.stdout:
        return False
    subprocess.run([D.ADB, "-s", D.S, "pull", r.stdout.strip().split("package:")[1], p],
                   capture_output=True)

    def ent(path):
        z = zipfile.ZipFile(path)
        return {n: hashlib.sha256(z.read(n)).hexdigest()[:12] for n in sorted(z.namelist())
                if (n.startswith("classes") and n.endswith(".dex")) or n == "resources.arsc"}
    return ent(apk) == ent(p)


def bring_up(ime_id, dens, fs):
    subprocess.run([D.ADB, "-s", D.S, "shell", "wm", "density", dens], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "system", "font_scale", fs],
                   capture_output=True)
    time.sleep(1.2)
    D.shell("settings put secure autofill_service null")
    for r in range(4):
        D.shell("am force-stop com.android.settings")
        D.shell("am force-stop com.google.android.settings.intelligence")
        D.shell("input keyevent KEYCODE_WAKEUP")
        D.shell("wm dismiss-keyguard")
        D.shell("ime enable %s" % ime_id)
        D.shell("ime set %s" % ime_id)
        D.shell("am start -W -n com.android.settings/.Settings")
        time.sleep(2.4)
        for taps in range(1, 6):
            b = D.node_bounds("open_search_view_edit_text") or D.node_bounds("搜索设置")
            if b:
                D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
            time.sleep(1.3)
            cur = D.shell("settings get secure default_input_method").strip()
            top, bot, h = D.ime_frame()
            if h and cur.startswith(ime_id.split("/")[0]):
                return dict(ok=True, round=r + 1, taps=taps, top=top, h=h, cur=cur)
        # 搜狗第一次被切为默认输入法时会弹引导页/隐私页，键盘不显示：点掉「同意/跳过」再试
        for needle in ("同意", "跳过", "以后再说", "取消"):
            b = D.node_bounds(needle)
            if b:
                D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
                time.sleep(1.0)
    cur = D.shell("settings get secure default_input_method").strip()
    top, bot, h = D.ime_frame()
    return dict(ok=False, rounds=4, cur=cur, top=top, h=h)


def measure(ime_key, dens, fs, apk):
    ime_id = IME_ID[ime_key]
    st = bring_up(ime_id, dens, fs)
    if not st.get("ok"):
        log("GATE", ime=ime_key, reason="keyboard_not_shown", rc=30, dens=dens, fs=fs,
            ts=ts(), current=st.get("cur"), h=st.get("h"))
        return 30
    host0 = D.focused_text()
    # 组合态：两侧都敲同一串字母（搜狗/本包都吃硬件键），敲完再量，避免拿空闲态比组合态
    for ch in CODE:
        D.shell("input keyevent %d" % D.KC[ch])
        time.sleep(0.12)
    time.sleep(1.0)
    top_e, bot_e, h_e = D.ime_frame()
    p = D.shot("shots/r6_%s_%s_fs%s.png" % (TAG[ime_key].lower(), dens, fs))
    top, bot, h = D.ime_frame()
    cur = D.shell("settings get secure default_input_method").strip()
    tag = "%s_%s_%s" % (TAG[ime_key], dens, fs)
    log("FRAME", tag=tag, ime=cur, top=top, h=h, pp=round(h * 100.0 / H, 2),
        comp_top=top, comp_h=h, ts=ts(), dens=dens, fs=fs, host=repr(host0)[:24], shot=os.path.basename(p))
    if ime_key == "ispeech":
        log("IDENT", tag=tag, match=ident(apk) if apk else None, apk_sha=apk)
    for ln in raw_ime_lines():
        log("RAW", tag=tag, line=ln[:220])
    D.shell("input keyevent 111")  # ESC：收掉组合，别把字留在宿主里污染下一档
    return 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--ime", required=True, choices=sorted(IME_ID))
    ap.add_argument("--tiers", default="440:1.0,440:1.15,520:1.15")
    ap.add_argument("--apk", default=None)
    a = ap.parse_args()
    if a.ime == "ispeech":
        if not a.apk:
            raise SystemExit("ispeech 需要 --apk（身份闸门要用）")
        subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-d", "-g", a.apk], capture_output=True, timeout=900)
        subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "enable", D.IME], capture_output=True)
    log("WINDOW", phase="open", ime=a.ime, ts=ts(), serial=D.S)
    rc = 0
    for t in a.tiers.split(","):
        dens, fs = t.split(":")
        rc |= measure(a.ime, dens, fs, a.apk)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "system", "font_scale", "1.0"],
                   capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "wm", "density", "reset"], capture_output=True)
    log("WINDOW", phase="close", ime=a.ime, ts=ts(), rc=rc)
    raise SystemExit(rc)
