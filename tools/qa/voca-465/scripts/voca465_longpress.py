#!/usr/bin/env python3
"""软键空格长按 500ms 仍进语音（判据 5：与双击判定共存）。"""
import os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
sys.argv = ["ac2.py", "app-debug.apk", "440", "keyevent"]
import ac2

if not D.host_ready(rounds=3).get("ok"):
    print("HOST_NOT_READY"); sys.exit(3)
print("SWITCH", ac2.set_switch(True)["state"])
D.clear(30); time.sleep(0.4)
x, y = ac2.SPACE_XY
# 长按：同点 800ms 滑动 = 按住不放 800ms（>500ms 阈值）
D.shell("input touchscreen swipe %d %d %d %d 800" % (x, y, x, y))
time.sleep(1.2)
D.shell("uiautomator dump /sdcard/wd.xml")
xml = D.shell("cat /sdcard/wd.xml")
import re
words = [w for w in ("聆听", "说话", "语音", "识别中", "正在录") if w in xml]
print("LONGPRESS soft space: voice_markers=%s" % words)
print("  focused=%r" % (D.focused_text(),))
D.shell("input keyevent 4")
