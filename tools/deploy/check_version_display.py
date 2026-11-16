#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_version_display.py —— 出包门禁：应用内展示的版本号 == 包管理器的 versionName

为什么需要这一条（VOCA-233 / v1.6 验收缺陷 D-2）：
版本展示原先取 `MainActivity.kt` 里的 `const val ISPEECH_VERSION = "1.5"`，
出包时只改 `app/build.gradle.kts` 的 versionName，常量不会跟着走 —— 装上 1.6 后
主页仍显示「版本 v1.5」、「关于智语」仍显示「版本 1.5」。既有的出包门禁只查
`aapt2 dump badging`（包管理器侧）与 dex 字符串，测不到用户实际看到的那一行，
于是 v1.6 带着错文案过了门禁，「凭版本号分辨包」在用户可见面失效。

本脚本在**已安装**的包上做端到端断言：

    dumpsys package 的 versionName  ==  主页展示的版本  ==  关于页展示的版本

用法（需 adb + 一台已装智语的设备 / 模拟器）：

    python3 tools/deploy/check_version_display.py                 # 自动选唯一在线设备
    python3 tools/deploy/check_version_display.py -s emulator-5554
    python3 tools/deploy/check_version_display.py --wait 4        # 慢设备加长进页面等待

输出：每条检查一行 `PASS` / `FAIL` / `WARN`，末尾 `SUMMARY: ...`；任一 FAIL 退出码 1。
导航失败（机型 / 语言 / 无障碍差异）报 WARN 不阻断；数值不一致报 FAIL。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.ispeech.ime"
MAIN_ACTIVITY = "com.ispeech.ime/com.ispeech.settings.MainActivity"
DUMP_PATH = "/sdcard/ispeech_version_dump.xml"
ROOT = Path(__file__).resolve().parents[2]
RES_DIRS = (ROOT / "app" / "src" / "main" / "res" / "values",
            ROOT / "app" / "src" / "main" / "res" / "values-en")
# 「关于智语」入口按资源 id 取文案：设备语言不是中文时也能点进去，改文案不用改脚本
ABOUT_ENTRY_KEYS = ("about_title", "settings_about_desc")

_passed = 0
_failed = 0
_warned = 0


def report(level: str, message: str) -> None:
    global _passed, _failed, _warned
    print(f"{level}: {message}")
    if level == "PASS":
        _passed += 1
    elif level == "FAIL":
        _failed += 1
    elif level == "WARN":
        _warned += 1


def check(cond: bool, ok: str, fail: str) -> None:
    report("PASS" if cond else "FAIL", ok if cond else fail)


def resolve_adb() -> str:
    for base in (
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        "/opt/homebrew/share/android-commandlinetools",
        str(Path.home() / "Library/Android/sdk"),
    ):
        if not base:
            continue
        cand = Path(base) / "platform-tools" / "adb"
        if cand.exists():
            return str(cand)
    found = subprocess.run(["bash", "-lc", "command -v adb"], capture_output=True, text=True)
    if found.returncode == 0 and found.stdout.strip():
        return found.stdout.strip()
    sys.exit("FAIL: 找不到 adb（设置 ANDROID_HOME 或把 platform-tools 加入 PATH）")


class Device:
    def __init__(self, adb: str, serial: str | None) -> None:
        self.adb = adb
        self.serial = serial

    def shell(self, cmd: str, timeout: int = 60) -> str:
        res = subprocess.run(
            self.base() + ["shell", cmd], capture_output=True, text=True, timeout=timeout
        )
        return res.stdout.replace("\r", "")

    def base(self) -> list[str]:
        return [self.adb] + (["-s", self.serial] if self.serial else [])

    def pick_serial(self) -> None:
        if self.serial:
            return
        res = subprocess.run([self.adb, "devices"], capture_output=True, text=True, timeout=30)
        online = [ln.split()[0] for ln in res.stdout.splitlines()[1:]
                  if ln.strip().endswith(" device")]
        if len(online) != 1:
            sys.exit(f"FAIL: 需要恰好一台在线设备，当前 {online or '无'}；请用 -s <serial> 指定")
        self.serial = online[0]

    def screen_size(self) -> tuple[int, int]:
        m = re.search(r"(\d+)x(\d+)", self.shell("wm size"))
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)


def about_entry_hints() -> tuple[str, ...]:
    """首页「关于智语」卡片的标题与副标题（中英各一份）。"""
    hints = []
    for directory in RES_DIRS:
        path = directory / "strings.xml"
        if not path.exists():
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for el in root.iter("string"):
            if el.get("name") in ABOUT_ENTRY_KEYS and (el.text or "").strip():
                hints.append(el.text.strip())
    return tuple(dict.fromkeys(hints))


def installed_version(dev: Device) -> str:
    m = re.search(r"versionName=(\S+)", dev.shell(f"dumpsys package {PKG}"))
    return m.group(1) if m else ""


def dump_nodes(dev: Device) -> list[tuple[str, tuple[int, int, int, int]]]:
    """返回当前界面的 (文本, bounds) 列表；uiautomator 抖动时重试一次。"""
    for _ in range(2):
        dev.shell(f"rm -f {DUMP_PATH}")
        dev.shell(f"uiautomator dump {DUMP_PATH}", timeout=90)
        raw = dev.shell(f"cat {DUMP_PATH}")
        if "<node" in raw:
            break
        time.sleep(1.0)
    else:
        return []
    nodes = []
    for tag in re.findall(r"<node\b[^>]*>", raw):
        text_m = re.search(r'\btext="([^"]*)"', tag)
        bounds_m = re.search(r'\bbounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        text = (text_m.group(1) if text_m else "").strip()
        if text and bounds_m:
            nodes.append((text, tuple(int(g) for g in bounds_m.groups())))
    return nodes


def shown_version(nodes: list[tuple[str, tuple[int, int, int, int]]]) -> str:
    for text, _ in nodes:
        m = re.search(r"(?:版本|Version)\s*v?([0-9][0-9A-Za-z.\-]*)", text)
        if m:
            return m.group(1)
    return ""


def on_screen(bounds: tuple[int, int, int, int], size: tuple[int, int]) -> bool:
    _, _, x2, y2 = bounds
    return x2 <= size[0] and y2 <= size[1] and y2 > 0


def find_entry(nodes: list, hints: tuple[str, ...], size: tuple[int, int]):
    for text, bounds in nodes:
        if any(hint in text for hint in hints) and on_screen(bounds, size):
            return bounds
    return None


def open_home(dev: Device, wait: float) -> list:
    dev.shell(f"am start -n {MAIN_ACTIVITY}")
    time.sleep(wait)
    return dump_nodes(dev)


def find_about_entry(dev: Device, wait: float) -> tuple[int, int] | None:
    """首页卡片可能排在屏外（Compose 不渲染未可见项），逐屏下滚找入口。"""
    hints = about_entry_hints()
    w, h = dev.screen_size()
    for _ in range(5):
        bounds = find_entry(dump_nodes(dev), hints, (w, h))
        if bounds:
            return (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
        dev.shell(f"input swipe {w // 2} {int(h * 0.7)} {w // 2} {int(h * 0.35)} 400")
        time.sleep(wait)
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description="出包门禁：应用内展示版本 == dumpsys versionName")
    ap.add_argument("-s", "--serial", help="adb 设备序列号（缺省自动选唯一在线设备）")
    ap.add_argument("--wait", type=float, default=3.0, help="进页面后的等待秒数（默认 3）")
    args = ap.parse_args()

    dev = Device(resolve_adb(), args.serial)
    dev.pick_serial()

    pkg_ver = installed_version(dev)
    check(
        bool(pkg_ver),
        f"包管理器版本（{dev.serial}）：versionName={pkg_ver}",
        f"{PKG} 未安装或 dumpsys 无 versionName，请先安装被测包",
    )
    if not pkg_ver:
        print("SUMMARY: passed=0 failed=1 warned=0")
        return 1

    home_nodes = open_home(dev, args.wait)
    home_ver = shown_version(home_nodes)
    if home_ver:
        check(
            home_ver == pkg_ver,
            f"主页展示版本 = {home_ver}（与包一致）",
            f"主页展示「{home_ver}」但包是 {pkg_ver} —— 版本展示没有走 BuildConfig.VERSION_NAME",
        )
    else:
        report("WARN", "主页未取到「版本 …」文本（uiautomator 没拿到 UI 层级，该项未判定）")

    entry = find_about_entry(dev, args.wait)
    if entry is None:
        report("WARN", f"未能从首页定位「关于智语」入口（期望文案 {list(about_entry_hints())}），该项未判定")
    else:
        dev.shell(f"input tap {entry[0]} {entry[1]}")
        time.sleep(args.wait)
        about_ver = shown_version(dump_nodes(dev))
        if about_ver:
            check(
                about_ver == pkg_ver,
                f"「关于智语」页展示版本 = {about_ver}（与包一致）",
                f"关于页展示「{about_ver}」但包是 {pkg_ver} —— 版本展示没有走 BuildConfig.VERSION_NAME",
            )
        else:
            report("WARN", "关于页未取到版本文本，该项未判定")

    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


if __name__ == "__main__":
    sys.exit(main())
