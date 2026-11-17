#!/usr/bin/env python3
"""长按 / 单击空格在开关两态下的上屏长度：隔离「两下空格」是不是键路径接管带来的。"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
sys.argv = ["ac2.py", "app-debug.apk", "440", "keyevent"]
import ac2


def press(cmd, label):
    D.clear(30)
    time.sleep(0.4)
    D.shell(cmd)
    time.sleep(0.8)
    host = D.focused_text() or ""
    print("%-28s host=%r len=%d spaces=%d" % (label, host, len(host), host.count(" ")), flush=True)
    return host


def main():
    if not D.host_ready(rounds=3).get("ok"):
        print("HOST_NOT_READY"); return
    for on in (True, False):
        st = ac2.set_switch(on)
        print("== switch=%s (state=%s raw=%s) ==" % (on, st["state"], st["raw_hex"]))
        press("input keyevent 62", "single keyevent 62")
        press("input keyevent --longpress 62", "longpress 62")
        press("input keyevent --duration 700 62", "duration 700 62")
        press("input keyevent 62 62", "2x single injection")


main()
