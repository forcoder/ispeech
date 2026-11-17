#!/usr/bin/env python3
"""AC-3 两入口对照：组合中连按两次空格，软键与硬件键必须给同一个结果（且不出句号）。"""
import os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
sys.argv = ["ac2.py", "app-debug.apk", "440", "keyevent"]
import ac2

SPACE_XY = ac2.SPACE_XY


def run_case(kind):
    """组合串一律用字母键注入（两条路径只差「空格」这一键怎么走）。"""
    D.clear(30)
    time.sleep(0.4)
    D.type_str("zhongguo", gap=0.08)
    time.sleep(0.8)
    if kind == "key":
        D.shell("input keyevent 62 62")
    else:
        D.shell(" && ".join(["input tap %d %d" % SPACE_XY] * 2))
    time.sleep(0.9)
    host = D.focused_text() or ""
    print("AC3/%-4s host=%r len=%d hasPeriod=%s" % (kind, host, len(host), "。" in host), flush=True)
    return host


def main():
    if not D.host_ready(rounds=3).get("ok"):
        print("HOST_NOT_READY"); return
    st = ac2.set_switch(True)
    print("SWITCH state=%s raw=%s" % (st["state"], st["raw_hex"]))
    a = run_case("key")
    b = run_case("soft")
    print("PARITY %s" % ("same" if a == b else "DIFFERENT -> %r vs %r" % (a, b)))


main()
