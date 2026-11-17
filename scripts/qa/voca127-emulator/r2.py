"""VOCA-127 R2 — AC-3.4 ②/③ + F1 探测（emulator-5554，五笔态）。"""
import sys, time, re
sys.path.insert(0, '.')
import cases, emu, kb, run

PHRASE_KEY = (472, 1502)      # SoftKeyboardView 工具条第 4 键 = KeyAction.PANEL_QUICK_PHRASE
SECOND = {'门': 'uyhn', '窗': 'pwtq', '车': 'lgnh', '船': 'temk', '桥': 'stdj',
          '田': 'llll', '园': 'lfqv', '米': 'oyty', '茶': 'awsu', '盐': 'fhlf',
          '酒': 'isgg', '饭': 'qnrc', '肉': 'mwwi', '蛋': 'nhju', '日': 'jjjj',
          '月': 'eeee', '石': 'dgtg'}

def step(tag, a, b, action):
    """两跳拼一个词组；action() 在两跳之间执行（不做任何宿主动作时传 None）。"""
    emu.logcat_clear()
    run.type_code(a, gap=0.08); run.pick(0)
    t = time.time()
    if action:
        action()
    dt = time.time() - t
    run.type_code(b, gap=0.08); run.pick(0)
    time.sleep(0.5)
    lg = cases.logs(); txt = '\n'.join(lg)
    exp = a[:2] + b[:2]
    r = dict(tag=tag, expect=exp,
             commit=exp in txt,
             int_n=len([l for l in lg if 'reason=interrupt' in l and 'count=' not in l]),
             int_c=len([l for l in lg if 'reason=interrupt' in l and 'count=' in l]),
             skips=sorted(set(re.findall(r'skipped reason=(\w+)', txt))),
             gap=round(dt, 2), field=kb.field_text())
    print(f"[{tag}] expect={exp} commit={r['commit']} interrupt(nocount)={r['int_n']} "
          f"interrupt(count)={r['int_c']} skips={r['skips']} gap={r['gap']}s")
    for l in lg: print('     ', re.sub(r'^[DIWE]/', '', l)[:120])
    return r, lg

def move_cursor():
    emu.adb('shell', 'input', 'keyevent', '122')   # KEYCODE_MOVE_HOME：真实光标移动
    time.sleep(0.2)

def open_close_phrase_panel():
    emu.tap(*PHRASE_KEY); time.sleep(1.4)
    kb.shot('R2_panel_open.png')
    emu.adb('shell', 'input', 'keyevent', '111')   # ESCAPE 关面板（IspeechImeService:599）
    time.sleep(0.9)
    run.ensure_editor()                            # 焦点回宿主输入框

results = []
cases.reset()
run.fresh_field()
run.ensure_editor()
cases.empty_field(focus=False)
print('mode check — 打 klqt 看候选（五笔整码应出 中国银行/喂猪）：')
run.type_code('klqt')
kb.shot('R2_mode_check.png')
print('   field:', repr(kb.field_text()))
run.backspace(4)

print('\n===== AC-3.4 ② 首字上屏后 >=500ms 移动宿主光标 =====')
r, _ = step('4.2-1', 'klge', SECOND['门'], lambda: (time.sleep(0.8), move_cursor()))
results.append(r)
print('\n===== AC-3.4 ③ 首字上屏后开/关快捷短语面板 =====')
r, _ = step('4.3-1', 'klge', SECOND['窗'], open_close_phrase_panel)
results.append(r)

print('\n===== F1 A 组：上屏后 <300ms 移动光标（5 次，只记录）=====')
for ch in ['车', '船', '桥', '田', '园']:
    r, _ = step(f'F1A-{ch}', 'klge', SECOND[ch], move_cursor)
    results.append(r)

print('\n===== F1 B 组：上屏后 500ms~1s 移动光标（5 次，漏判即升级缺陷）=====')
for ch in ['米', '茶', '盐', '酒', '饭']:
    r, _ = step(f'F1B-{ch}', 'klge', SECOND[ch], lambda c=ch: (time.sleep(0.6), move_cursor()))
    results.append(r)

print('\n===== 汇总 =====')
for r in results:
    print(f"{r['tag']:8s} expect={r['expect']:6s} commit={str(r['commit']):5s} "
          f"int_n={r['int_n']} int_c={r['int_c']} skips={r['skips']} gap={r['gap']}")
print('=== user_dict ===')
print(emu.user_dict())
kb.shot('R2_end.png')
