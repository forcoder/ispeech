#!/usr/bin/env python3
"""Minimal uiautomator driver for the ispeech IME (VOCA-127 emulator run)."""
import subprocess, sys, time, re, json

SERIAL = "emulator-5554"


def adb(*args, **kw):
    cmd = ["adb", "-s", SERIAL] + list(args)
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=kw.get("timeout", 60))
    return (r.stdout or "") + (r.stderr or "")


def dump(tag="h"):
    adb("shell", "uiautomator", "dump", f"/sdcard/{tag}.xml")
    xml = adb("shell", "cat", f"/sdcard/{tag}.xml")
    return xml


def nodes(xml):
    """Return list of node dicts parsed from uiautomator XML (regex keeps it dependency-free)."""
    out = []
    for m in re.finditer(r"<node\b([^>]*)/?>", xml):
        attrs = dict(re.findall(r'([\w:-]+)="([^"]*)"', m.group(1)))
        b = attrs.get("bounds", "")
        mm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if mm:
            x1, y1, x2, y2 = map(int, mm.groups())
            attrs["_c"] = ((x1 + x2) // 2, (y1 + y2) // 2)
            attrs["_b"] = (x1, y1, x2, y2)
        out.append(attrs)
    return out


def find(xml, text=None, desc=None, cls=None, rid=None, pkg=None):
    hits = []
    for n in nodes(xml):
        if text is not None and n.get("text") != text:
            continue
        if desc is not None and n.get("content-desc") != desc:
            continue
        if cls is not None and cls not in n.get("class", ""):
            continue
        if rid is not None and not n.get("resource-id", "").endswith(rid):
            continue
        if pkg is not None and n.get("package") != pkg:
            continue
        hits.append(n)
    return hits


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def tap_node(n):
    x, y = n["_c"]
    tap(x, y)


def wait_until(fn, secs=10, step=0.4):
    end = time.time() + secs
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return None


def logcat_dump():
    return adb("shell", "logcat", "-d", "-v", "brief")


def logcat_clear():
    adb("shell", "logcat", "-c")


def user_dict():
    return adb("shell", "run-as", "com.ispeech.ime", "cat", "files/user_dict.txt")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "dump"
    if cmd == "dump":
        print(dump())
    elif cmd == "keys":
        xml = dump()
        for n in nodes(xml):
            if n.get("package") == "com.ispeech.ime":
                print(json.dumps({k: n.get(k) for k in ("class", "text", "content-desc", "resource-id", "bounds")}, ensure_ascii=False))
    elif cmd == "dict":
        print(repr(user_dict()))
