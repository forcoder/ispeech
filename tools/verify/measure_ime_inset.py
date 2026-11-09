#!/usr/bin/env python3
"""测量输入法窗口占屏高度，用于和搜狗五笔做同机对照。

用法：
    python3 tools/verify/measure_ime_inset.py                 # 测当前输入法
    python3 tools/verify/measure_ime_inset.py --compare       # 依次切 iSpeech / 搜狗 各测一次

数据来源：`adb shell dumpsys window displays` 里的 `InsetsSource type=ITYPE_IME`。
这是系统合成器给出的最终窗口矩形，比截图取色可靠（候选条与键盘底色同为 #F2F3F5，
截图分不出边界）。

前置：设备已连接、目标输入法已启用、屏幕上已有一个获得焦点的输入框且键盘已弹出。
任一前置不满足时脚本直接报错退出，不猜、不出假数据。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

ISPEECH = "com.ispeech.ime/com.ispeech.input.IspeechImeService"
SOGOU = "com.sohu.inputmethod.sogou/.SogouIME"

FRAME_RE = re.compile(r"InsetsSource type=ITYPE_IME frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args: str, serial: str | None = None) -> str:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"adb 失败：{' '.join(cmd)}\n{proc.stderr.strip()}")
    return proc.stdout


def pick_serial(explicit: str | None) -> str:
    if explicit:
        return explicit
    out = adb("devices")
    serials = [ln.split("\t")[0] for ln in out.splitlines()[1:] if "\tdevice" in ln]
    if not serials:
        sys.exit("没有已连接的设备（adb devices 为空）。接上手机并开启 USB 调试后重试。")
    if len(serials) > 1:
        sys.exit(f"检测到多台设备：{serials}，请用 --serial 指定一台。")
    return serials[0]


def ime_inset(serial: str) -> tuple[int, int, int]:
    """返回 (顶部 y, 底部 y, 高度 px)。键盘未弹出时报错。"""
    out = adb("shell", "dumpsys", "window", "displays", serial=serial)
    matches = FRAME_RE.findall(out)
    if not matches:
        sys.exit("没找到 ITYPE_IME 的 frame —— 键盘没弹出。先点一下输入框让键盘起来再测。")
    # 多显示器时取面积最大的那条（主屏）
    top, bottom = max(
        ((int(m[1]), int(m[3])) for m in matches),
        key=lambda tb: tb[1] - tb[0],
    )
    return top, bottom, bottom - top


def screen_height(serial: str) -> int:
    out = adb("shell", "wm", "size", serial=serial)
    m = re.search(r"(\d+)x(\d+)", out)
    if not m:
        sys.exit(f"读不出屏幕尺寸：{out!r}")
    return int(m.group(2))


def density(serial: str) -> int:
    out = adb("shell", "wm", "density", serial=serial)
    m = re.search(r"(\d+)", out)
    if not m:
        sys.exit(f"读不出屏幕密度：{out!r}")
    return int(m.group(1))


def current_ime(serial: str) -> str:
    return adb("shell", "settings", "get", "secure", "default_input_method", serial=serial).strip()


def report(serial: str, label: str, screen_h: int, dens: int) -> dict[str, float]:
    top, bottom, height = ime_inset(serial)
    dp = height * 160.0 / dens
    pct = height * 100.0 / screen_h
    print(f"{label}")
    print(f"  IME frame   [{top},{bottom}]  高度 {height} px = {dp:.1f} dp")
    print(f"  屏高        {screen_h} px         占屏 {pct:.1f} %")
    return {"height_px": height, "height_dp": dp, "pct": pct, "top": top}


def main() -> int:
    ap = argparse.ArgumentParser(description="测量输入法窗口占屏高度")
    ap.add_argument("--serial", help="设备序列号（默认唯一在线设备）")
    ap.add_argument("--compare", action="store_true", help="依次切到 iSpeech / 搜狗 各测一次")
    ap.add_argument("--target", help="只测指定 IME id，不切换（默认测当前输入法）")
    args = ap.parse_args()

    serial = pick_serial(args.serial)
    screen_h = screen_height(serial)
    dens = density(serial)
    print(f"设备 {serial}  屏高 {screen_h} px  density {dens} ({dens / 160:.2f}x)\n")

    if not args.compare:
        if args.target:
            adb("shell", "ime", "set", args.target, serial=serial)
        else:
            print(f"当前输入法：{current_ime(serial)}")
        report(serial, "当前输入法", screen_h, dens)
        return 0

    results: dict[str, dict[str, float]] = {}
    for ime_id, label in ((ISPEECH, "iSpeech"), (SOGOU, "搜狗五笔")):
        adb("shell", "ime", "set", ime_id, serial=serial)
        actual = current_ime(serial)
        if actual != ime_id:
            sys.exit(f"切换失败：期望 {ime_id}，实际 {actual}。该输入法可能未安装或未启用。")
        results[label] = report(serial, label, screen_h, dens)
        print()

    a, b = results["iSpeech"], results["搜狗五笔"]
    print("对照")
    print(f"  高度差   {a['height_px'] - b['height_px']:+.0f} px "
          f"({a['height_dp'] - b['height_dp']:+.1f} dp)")
    print(f"  占屏差   {a['pct'] - b['pct']:+.1f} 个百分点")
    if b["pct"]:
        print(f"  相对差   {(a['pct'] / b['pct'] - 1) * 100:+.1f} %")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())