#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ac12_overflow.py —— AC-12「候选溢出可见性」现场测量工装。

规格：`docs/keyboard-ux-spec.md` AC-12（PR #17 head `732da2e`）。
被测：候选条右沿 12 dp 渐变遮罩在「有溢出 / 无溢出」两态下的显隐。

测量口径
--------
- 候选条带 = 整行 ≥`BAR_ROW_FRAC`(0.40) 像素为候选条底色 `#F4F5F7` 的最长连续行段。
  不能取 0.60：有候选的行纯底色只占 40–56%，会把健康行切成 8.3–20 dp 假条带。
- 线索区 = 面板宽 − `paddingEnd` 6 dp 往左的 12 dp（`CandidateOverflowCue.FADE_WIDTH_DP`）。
- 量法：按列取「与底色的最大通道差」（`col_diff`），把 12 dp 线索区按 1/4 切成头和尾。
- 判据（量的是渐变斜坡，不是「有没有墨迹」）：
    FADE_PRESENT        头部列差 ≥`FADE_MIN_INK`(30) 且尾部最小列差 ≤`FADE_MAX_CLEAR`(12)
                        （左端仍露内容、右端被底色盖死 ⇒ 斜坡在，且线索不占横向预算）
    FADE_ABSENT         线索区有墨迹但收不成斜坡 ⇒ 遮罩没画出来（或压根不是渐变）
    NO_CONTENT_ANYWHERE 线索区最大列差 ≤`INK_CHANNEL_TOL` 且左侧三倍带内无墨迹
                        ⇒ 无溢出（此时线索本就必须隐藏，另判）
  旧判据要求「线索区内 0 个非底色像素」才判 FADE_PRESENT，与渐变相反，真有渐变时
  恒判 FADE_ABSENT（2026-11-24 emulator-5554 实测修好的包仍是旧判据 FAIL）。

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

# 候选条带的合理高度（candidate_view.xml layout_height=46dp，上下各 4dp padding 已在内）。
# 给到 24–160 dp 的宽区间：厂商字体放大、角度行高差异都在里面，但「整块键盘面板」进不来。
MIN_BAND_DP = 24
MAX_BAND_DP = 160
# 条带占面板高度的上限。候选条永远是面板顶部的一条，不可能占掉四分之一屏还多。
MAX_BAND_PANEL_FRAC = 0.25

# 一行里有候选时纯底色只占 40–56%：chip + 候选字身 + 角标把其余部分吃掉。
# 阈值给 0.60 会把这种健康行判成「不是候选条」，实测在 emulator-5554 上得到
# 13.8 dp / 20 dp 的假条带；给 0.40 才能拿到完整的 46 dp（2026-11-24 实测 46.2 dp）。
# 不能改成「底色族 + 容差」来提升覆盖率：键面白 (240,240,243) 与 #F4F5F7 只差 5，
# 任何能装下 chip 铺底（差 31）的容差都会把整块键面算进候选条，量到 719..837 dp。
# 减到 0.40 也不会让「整块 #F4F5F7 面板」混进来 —— 那种帧的高度守不住 24..160 dp 的闸门。
BAR_ROW_FRAC = 0.40

# 墨迹判据：与底色单通道差超过这个值才算「有内容」。抗锯齿边缘在 3–8，
# chip 铺底 31、字身 100+，取 10 能把三者分开。
INK_CHANNEL_TOL = 10
# 渐变判据：线索左端（x_end - fade）必须仍有可见内容，右端（x_end - 1）必须被底色盖死。
# 2026-11-24 emulator-5554 实测：修好的包 210→3，未修的基线包 5→5（平）或 213→213（全墨迹）。
FADE_MIN_INK = 30
FADE_MAX_CLEAR = 12

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


def _is_bar_bg(c) -> bool:
    """候选条底色：只认纯 `#F4F5F7`。

    不用「容差族」覆盖 chip 铺底 (222,224,248)：键面白 (240,240,243) 与底色只差 5，
    任何大到能装下 chip 铺底（差 31）的容差都会把整块键面算进候选条 —— 实测条带
    从 46 dp 涨到 719..837 dp。覆盖不足改由 [BAR_ROW_FRAC] 处理。
    """
    return c == BAR_BG


def find_bar(px, w: int, h: int):
    """候选条带：整行 ≥BAR_ROW_FRAC 为候选条底色的最长连续行段。"""
    prev, bands = None, []
    step = 4
    xs = list(range(0, w, step))
    for y in range(h):
        frac = sum(1 for x in xs if _is_bar_bg(px[x, y])) / len(xs)
        if frac > BAR_ROW_FRAC and prev is None:
            prev = y
        elif frac <= BAR_ROW_FRAC and prev is not None:
            if y - prev > 10:
                bands.append((prev, y))
            prev = None
    if prev is not None and h - prev > 10:
        bands.append((prev, h))
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
    # 帧有效性闸门：面板整块都是 #F4F5F7 时 find_bar 会把「整屏」当成候选条，
    # 于是线索区里既没墨迹、左边也没墨迹 → NO_CONTENT_ANYWHERE，或者更糟：
    # 键盘没画出来但面板底色相同，被判成 FADE_PRESENT（假的达标）。
    # 候选条只可能是面板顶部的一条 46dp，所以这里先否掉「不像候选条」的帧，
    # 让这类帧走 ok=False，而不是让它产出 verdict。
    bar_h = y1 - y0
    if not (MIN_BAND_DP * d <= bar_h <= MAX_BAND_DP * d):
        return dict(ok=False, why="bar_height_implausible", w=w, h=h, density_px_dp=d,
                    band_y=(y0, y1), band_dp=round(bar_h / d, 1),
                    need_dp="%d..%d" % (MIN_BAND_DP, MAX_BAND_DP))
    if bar_h > MAX_BAND_PANEL_FRAC * h:
        return dict(ok=False, why="bar_covers_panel", w=w, h=h, density_px_dp=d,
                    band_y=(y0, y1), band_dp=round(bar_h / d, 1),
                    panel_frac=round(bar_h / float(h), 3),
                    max_frac=MAX_BAND_PANEL_FRAC)

    def ink(a: int, b: int) -> int:
        return sum(
            1
            for y in range(y0 + 2, y1 - 2)
            for x in range(a, b)
            if any(abs(px[x, y][i] - BAR_BG[i]) > INK_CHANNEL_TOL for i in range(3))
        )

    def col_diff(x: int) -> int:
        """该列上与候选条底色的最大通道差（列内取最大，不受单像素抗锯齿影响）。"""
        return max(
            (max(abs(px[x, y][i] - BAR_BG[i]) for i in range(3)) for y in range(y0 + 2, y1 - 2)),
            default=0,
        )

    # 线索是 12 dp 渐变：左端仍露内容、右端被底色盖死，列差从大到小收成 0。
    # 旧判据要求「线索区一个非底色像素都没有」，正好与渐变相反 —— 真有渐变时
    # 恒判 FADE_ABSENT（2026-11-24 在 emulator-5554 上实测：修好的包列差
    # 210→0，旧判据仍报 FADE_ABSENT）。改成量「有没有这个斜坡」：
    # 线索区最左四分之一仍有可见内容，且最右四分之一里存在被底色盖死的列。
    q = max(1, fade // 4)
    first = max(col_diff(x) for x in range(x_end - fade, x_end - fade + q))
    tail_min = min(col_diff(x) for x in range(x_end - q, x_end))
    in_fade = ink(x_end - fade, x_end)
    left = ink(x_end - 3 * fade, x_end - fade)
    verdict = (
        "FADE_PRESENT"
        if first >= FADE_MIN_INK and tail_min <= FADE_MAX_CLEAR
        else "NO_CONTENT_ANYWHERE"
        if max(col_diff(x) for x in range(x_end - fade, x_end)) <= INK_CHANNEL_TOL and left == 0
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
        fade_head_diff=first,
        fade_tail_min_diff=tail_min,
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