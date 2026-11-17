#!/usr/bin/env python3
"""VOCA-465 键路径补充取证：AC-3（组合中空格上屏首选）、自动重复、spaceProbe 时间戳。"""
import os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D

S = D.S
ADB = D.ADB


def logcat_dump():
    r = subprocess.run([ADB, "-s", S, "logcat", "-d", "-v", "epoch", "-s", "IspeechIme:V"],
                       capture_output=True, text=True)
    return r.stdout


def run(cmd):
    return subprocess.run([ADB, "-s", S, "shell"] + cmd.split(), capture_output=True, text=True).stdout


def case(name, actions):
    print("CASE %s" % name, flush=True)
    actions()


def probe_burst(times, single=True, gap=0.0):
    D.clear(30)
    time.sleep(0.4)
    run("logcat -c")
    time.sleep(0.2)
    t0 = time.time()
    if single:
        D.shell("input keyevent " + " ".join(["62"] * times))
    else:
        for i in range(times):
            if gap:
                time.sleep(gap)
            D.shell("input keyevent 62")
    waited = max(0.0, 1.0 - (time.time() - t0))
    time.sleep(waited)
    logs = [l for l in logcat_dump().splitlines() if re.search(r"space( |$)|spaceProbe", l)]
    return logs, D.focused_text()


def show(tag, logs, host):
    print("  host=%r" % host)
    for l in logs:
        print("   ", l.strip())


def main():
    if not D.host_ready(rounds=3).get("ok"):
        print("HOST_NOT_READY"); sys.exit(3)
    sys.argv = ["ac2.py", "app-debug.apk", "440", "keyevent"]
    import ac2
    st = ac2.set_switch(True)
    print("SWITCH want=ON state=%s src=%s raw=%s route=%s" % (st["state"], st["src"], st["raw_hex"], st["route"]))

    # ── AC-3：硬件键 + 组合中，第一次空格上屏首选，不得出句号 ──
    D.clear(30); time.sleep(0.4)
    D.type_str("zhongguo", gap=0.08)
    time.sleep(0.8)
    D.shell("input keyevent 62 62")
    time.sleep(0.9)
    print("AC3 keyevent composing: host=%r" % D.focused_text())

    # ── 自动重复（长按空格）不该出句号 ──
    D.clear(30); time.sleep(0.5)
    run("logcat -c")
    D.shell("input keyevent --longpress 62")
    time.sleep(0.9)
    lp = D.focused_text()
    print("LONGPRESS keyevent: host=%r len=%d hasPeriod=%s" % (lp, len(lp or ""), "。" in (lp or "")))
    show("longpress logs", [l for l in logcat_dump().splitlines() if "space" in l], lp)

    # ── spaceProbe：连按空格时跨进程读取的时间戳间隔 ──
    for times, single, gap, label in ((2, True, 0.0, "2x_single_injection"),
                                      (2, False, 0.0, "2x_two_adb_calls"),
                                      (3, True, 0.0, "3x_single_injection"),
                                      (2, False, 0.5, "2x_gap500ms")):
        logs, host = probe_burst(times, single, gap)
        print("PROBE %s" % label)
        show(label, logs, host)

    # ── 开关关闭时硬件键两连发不得出句号（判据 4）──
    print("DONE")


main()
