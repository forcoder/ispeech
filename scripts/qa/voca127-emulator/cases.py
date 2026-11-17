"""VOCA-127 emulator case set (AC-3.4 / 3.7 / 3.8 / 3.11 / 3.12 + F1 probe)."""
import sys, time, re, json
sys.path.insert(0, '.')
import emu, kb, run

A = emu.adb
PREFS = 'files/datastore/ispeech_settings.preferences_pb'

def entry(key, val):
    k = key.encode()
    e = b'\x0a' + bytes([len(k)]) + k + b'\x12' + bytes([len(val)]) + val
    return b'\x0a' + bytes([len(e)]) + e
def sval(s):
    b = s.encode(); return b'\x2a' + bytes([len(b)]) + b
def bval(b): return b'\x08' + bytes([1 if b else 0])

def set_prefs(wubi=True, learning=True):
    doc = b''
    if wubi: doc += entry('input_method_mode', sval('WUBI'))
    doc += entry('learning', bval(learning))
    open('prefs_case.bin', 'wb').write(doc)
    A('shell', 'am', 'force-stop', 'com.ispeech.ime')
    import subprocess
    with open('prefs_case.bin', 'rb') as f:
        subprocess.run(['adb', '-s', 'emulator-5554', 'shell',
                        f"run-as com.ispeech.ime sh -c 'mkdir -p files/datastore && cat > {PREFS}'"],
                       stdin=f, check=True)

def reset(wubi=True, learning=True):
    """pm clear -> inject prefs -> re-select our IME; app restarts with an empty user_dict."""
    A('shell','pm','clear','com.ispeech.ime'); time.sleep(1.5)
    set_prefs(wubi, learning)
    A('shell','ime','enable','com.ispeech.ime/com.ispeech.input.IspeechImeService')
    A('shell','ime','set','com.ispeech.ime/com.ispeech.input.IspeechImeService')
    return A('shell','dumpsys','input_method')

FIELD = (534, 391)  # first-name EditText centre in the contact INSERT form

def empty_field(dels=60, focus=True):
    if focus:
        emu.tap(*FIELD)
        time.sleep(0.9)
    A('shell', f"for i in $(seq {dels}); do input keyevent 67; done")
    time.sleep(0.4)
    return kb.field_text()

def logs(pattern='phrase_learn|phrase_remove'):
    return [l for l in emu.logcat_dump().splitlines() if re.search(pattern, l)]

def hop(code, wait_after=1.0):
    run.type_code(code); run.pick(0); time.sleep(wait_after)

def crop_bar(src, dst, y0=1330, y1=1480):
    import pngrows
    w,h,ch,buf = pngrows.read_png(src)
    rowlen = w*ch
    out = buf[y0*rowlen:y1*rowlen]
    pngrows.write_png(dst, w, y1-y0, ch, out)
