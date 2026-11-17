"""VOCA-127 emulator case driver (五笔 phrase self-learning)."""
import sys, time, os
sys.path.insert(0, '.')
import emu, kb

CODE = {'a':29,'b':30,'c':31,'d':32,'e':33,'f':34,'g':35,'h':36,'i':37,'j':38,
        'k':39,'l':40,'m':41,'n':42,'o':43,'p':44,'q':45,'r':46,'s':47,'t':48,
        'u':49,'v':50,'w':51,'x':52,'y':53,'z':54}

def fresh_field(retry=6):
    """Relaunch contact INSERT, dismiss dialogs, focus the name field."""
    emu.adb('shell','am','force-stop','com.google.android.contacts')
    emu.adb('shell','am','force-stop','com.android.contacts')
    time.sleep(0.8)
    emu.adb('shell','am','start','-a','android.intent.action.INSERT',
            '-t','vnd.android.cursor.dir/contact')
    time.sleep(2.5)
    for _ in range(retry):
        xml = emu.dump('n')
        cancel = [n for n in emu.nodes(xml) if n.get('text') in ('Cancel', 'Discard')]
        if cancel:
            emu.tap_node(cancel[0]); time.sleep(1.2); continue
        eds = [n for n in emu.nodes(xml) if n.get('class','').endswith('EditText')]
        if eds:
            emu.tap_node(eds[0]); time.sleep(1.6)
            return eds[0]['_b']
        time.sleep(1.5)
    raise RuntimeError('no EditText on the contact form')

def type_code(s, gap=0.08, settle=0.4):
    for ch in s:
        emu.adb('shell','input','keyevent',str(CODE[ch]))
        time.sleep(gap)
    time.sleep(settle)

def pick(idx=0):
    emu.adb('shell','input','keyevent',str(8+idx))
    time.sleep(0.35)

def backspace(n=1):
    for _ in range(n):
        emu.adb('shell','input','keyevent','67'); time.sleep(0.25)

def bar(name):
    p = name if name.endswith('.png') else name + '.png'
    kb.shot(p)
    return p

def state(tag):
    print(f'--- {tag}')
    print('   field :', repr(kb.field_text()))
    print('   dict  :', repr(emu.user_dict()[-200:]))

def clear_field(n=120):
    for _ in range(n):
        emu.adb('shell','input','keyevent','67')
    time.sleep(1.2)
    return kb.field_text()


def dismiss_dialog():
    xml = emu.dump('d')
    hit = [n for n in emu.nodes(xml) if n.get('text') in ('Cancel', 'Discard')]
    if hit:
        emu.tap_node(hit[0]); time.sleep(1.2); return True
    return False


def ensure_editor(retry=4):
    """Focus the contact name field via dump-based tapping; never tap blind coordinates."""
    for _ in range(retry):
        if dismiss_dialog():
            continue
        xml = emu.dump('e')
        eds = [n for n in emu.nodes(xml) if n.get('class', '').endswith('EditText')
               and n.get('text', '') in ('', 'First name')]
        if not eds:
            eds = [n for n in emu.nodes(xml) if n.get('class', '').endswith('EditText')]
        if eds:
            emu.tap_node(eds[0]); time.sleep(1.6)
            if kb.field_text() is not None:
                return eds[0]['_b']
        else:
            fresh_field()
        time.sleep(1.0)
    raise RuntimeError('cannot focus an empty EditText')
