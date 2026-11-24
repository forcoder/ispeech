#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ac12_overflow.py —— AC-12「候选溢出可见性」现场测量工装。

规格：`docs/keyboard-ux-spec.md` AC-12（PR #17 head `732da2e`）。
被测：候选条右沿 12 dp 渐变遮罩在「有溢出 / 无溢出」两态下的显隐。

测量口径
--------
- 候选条带 = 整行 ≥60% 像素为候选条底色 `#F4F5F7` 的最长连续行段。
- 线索区 = 面板宽 − `paddingEnd` 6 dp 往左的 12 dp（`CandidateOverflowCue.FADE_WIDTH_DP`）。
- 判据：
    FADE_PRESENT        线索区内 0 个非底色像素，且紧邻左侧仍有墨迹
                        （内容被盖住 ⇒ 「后面还有」看得见，且线索不占横向预算）
    FADE_ABSENT         线索区内仍有墨迹 ⇒ 遮罩没画出来
    NO_CONTENT_ANYWHERE 两侧都空 ⇒ 无溢出（此时线索本就必须隐藏，另判）

用法
----
    python3 tools/qa/voca-37/ac12_overflow.py --out build/ac12
    python3 tools/qa/voca-37/ac12_overflow.py --panel 1260x2720 --density 520 \
        --font-scale 1.15 --out build/ac12-520

依赖：adb 已连一台设备、Python3 + Pillow。不联网、不写仓库源码。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time

from PIL import Image

IME = "com.ispeech.ime/com.ispeech.input.IspeechImeService"
HOST = "com.android.settings/.Settings"
BAR_BG = (0xF4, 0xF5, 0xF7)
KEY_WHITE = (0xFF, 0xFF, 0xFF)

# 规格 AC-12 实现口径 ①：视口右沿 8–12 dp 渐变遮罩
FADE_WIDTH_DP = 12
# 候选条 paddingEnd（candidate_view.xml）
PAD_END_DP = 6

OUT = "build/ac12"


def adb(*args: str) -> bytes:
    return subprocess.run(["adb", *args], capture_output=True).stdout


def shell(cmd: str) -> str:
    return adb("shell", *cmd.split()).decode("utf-8", "replace")


def density() -> float:
    out = shell("wm density")
    m = re.search(r"Override density:\s*(\d+)", out) or re.search(r"Physical density:\s*(\d+)", out)
    if not m:
        raise SystemExit("读不到设备密度：adb shell wm density 无输出")
    return int(m.group(1)) / 160.0


def ime_visible() -> bool:
    out = shell("dumpsys window displays")
    return "type=ime frame=[0," in out and "visible=true" in out


def shot(path: str) -> str:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p"))
    return path


def find_bar(px, w: int, h: int):
    """候选条带：整行 ≥60% 为 BAR_BG 的最长连续行段。"""
    prev, bands = None, []
    step = 4
    xs = list(range(0, w, step))
    for y in range(h):
        frac = sum(1 for x in xs if px[x, y] == BAR_BG) / len(xs)
        if frac > 0.60 and prev is None:
            prev = y
        elif frac <= 0.60 and prev is not None:
            if y - prev > 10:
                bands.append((prev, y))
            prev = None
    return max(bands, key=lambda b: b[1] - b[0]) if bands else None


def keys_drawn(px, w: int, h: int) -> bool:
    """键盘键面真的画出来了（而不是一块空白的 #F4F5F7 面板）。"""
    return sum(1 for y in range(1500, min(2150, h), 10) for x in range(0, w, 10) if px[x, y] == KEY_WHITE) > 200


def show_ime(rounds: int = 6) -> bool:
    """把 IME 拉起来。Settings 搜索框是最轻的宿主输入框，但 showSoftInput 请求在
    配置变更 / 重装后常被 WindowManager 丢掉（`mInputShown=true` 而 inset 不可见），
    所以每轮强制重启宿主并连点几次补 show 请求。"""
    for _ in range(rounds):
        shell("am force-stop com.google.android.settings.intelligence")
        shell("am force-stop com.android.settings")
        shell(f"ime enable {IME}")
        shell(f"ime set {IME}")
        shell(f"am start -n {HOST}")
        time.sleep(3)
        for x, y in ((313, 703), (600, 224)):
            shell(f"input tap {x} {y}")
            time.sleep(1.2)
        for _ in range(3):
            shell("input tap 600 224")
            time.sleep(1.0)
        if not ime_visible():
            continue
        time.sleep(1.2)
        p = shot(os.path.join(OUT, "_probe.png"))
        im = Image.open(p).convert("RGB")
        if keys_drawn(im.load(), im.size[0], im.size[1]):
            return True
    return False


def measure(path: str) -> dict:
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()
    d = density()
    fade = round(FADE_WIDTH_DP * d)
    x_end = w - round(PAD_END_DP * d)
    band = find_bar(px, w, h)
    if not band:
        return dict(ok=False, why="no_bar", w=w, h=h, density_px_dp=d)
    y0, y1 = band

    def ink(a: int, b: int) -> int:
        return sum(
            1
            for y in range(y0 + 2, y1 - 2)
            for x in range(a, b)
            if any(abs(px[x, y][i] - BAR_BG[i]) > 10 for i in range(3))
        )

    in_fade = ink(x_end - fade, x_end)
    left = ink(x_end - 3 * fade, x_end - fade)
    verdict = (
        "FADE_PRESENT"
        if in_fade == 0 and left > 0
        else "NO_CONTENT_ANYWHERE"
        if in_fade == 0 and left == 0
        else "FADE_ABSENT"
    )
    return dict(
        ok=True,
        w=w,
        h=h,
        density_px_dp=round(d, 2),
        fade_px=fade,
        x_end=x_end,
        band_y=(y0, y1),
        band_dp=round((y1 - y0) / d, 1),
        ink_in_fade=in_fade,
        ink_left=left,
        verdict=verdict,
    )


def main() -> int:
    global OUT
    ap = argparse.ArgumentParser(description="AC-12 候选溢出可见性现场测量")
    ap.add_argument("--out", default="build/ac12", help="截图输出目录（默认 build/ac12，已被 gitignore）")
    ap.add_argument("--panel", help="覆盖面板，如 1260x2720；不给则用设备当前值")
    ap.add_argument("--density", type=int, help="覆盖密度，如 520")
    ap.add_argument("--font-scale", help="覆盖 font_scale，如 1.15")
    args = ap.parse_args()
    OUT = args.out

    if args.panel:
        shell(f"wm size {args.panel}")
        time.sleep(2.0)
    if args.density:
        shell(f"wm density {args.density}")
        time.sleep(1.5)
    if args.font_scale:
        shell(f"settings put system font_scale {args.font_scale}")
        time.sleep(1.0)

    # 冷启动输入法服务：mode 回到设置里的默认中文输入法，避免上一次英文态的残留
    shell(f"am force-stop {IME}")
    time.sleep(2)

    if not show_ime():
        print("FAIL: IME 未能稳定画出键盘（宿主 showSoftInput 请求被 WindowManager 丢弃）")
        return 2

    p = shot(os.path.join(OUT, "state.png"))
    res = measure(p)
    for k, v in res.items():
        print(f"{k}={v}")
    return 0 if res.get("ok") and res.get("verdict") != "FADE_ABSENT" else 1


if __name__ == "__main__":
    sys.exit(main())