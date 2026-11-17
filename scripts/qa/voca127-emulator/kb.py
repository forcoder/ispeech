"""Keyboard calibration + primitives for the VOCA-127 emulator run."""
import subprocess, time
import emu

SERIAL = "emulator-5554"


def shot(name):
    subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"],
                   stdout=open(name, "wb"), check=True)
    return name


def field_text():
    xml = emu.dump("f")
    for n in emu.nodes(xml):
        if n.get("class", "").endswith("EditText") and n.get("focused") == "true":
            return n.get("text") or ""
    return None


def new_contact():
    emu.adb("shell", "am", "start", "-a", "android.intent.action.INSERT",
            "-t", "vnd.android.cursor.dir/contact")
    time.sleep(2.5)
    xml = emu.dump("n")
    eds = emu.find(xml, cls="EditText")
    if not eds:
        return False
    emu.tap_node(eds[0])
    time.sleep(1.5)
    return True


def tap_pt(x, y):
    emu.tap(x, y)
    time.sleep(0.45)


if __name__ == "__main__":
    import sys
    what = sys.argv[1]
    if what == "calib":
        # probe a vertical grid at x=470 to identify row centres
        for y in range(1560, 2280, 40):
            new_contact()
            tap_pt(470, y)
            print("y=%4d -> %r" % (y, field_text()))
    elif what == "panel":
        new_contact()
        shot("p0.png")
        emu.tap(1006, 1504)
        time.sleep(1.5)
        shot("p1.png")
        print("panel opened -> p1.png")
