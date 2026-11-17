"""VOCA-127 R1b — AC-3.4 ① clean run: 10 组两字连拼，中途零光标动作，逐组隔离日志。"""
import sys, time, re
sys.path.insert(0, '.')
import cases, emu, kb, run

VEC = [('klge', 'mmmm'), ('klge', 'iiii'), ('klge', 'oooo'), ('klge', 'dgtg'),
       ('klge', 'ffff'), ('klge', 'ssss'), ('klge', 'qqqq'), ('klge', 'fghy'),
       ('klge', 'awxb'), ('klge', 'scfy')]

cases.reset()
run.fresh_field()                        # pm clear 杀掉了宿主 Activity，必须重新拉起
cases.empty_field(focus=False)
print('dict@start:', repr(emu.user_dict()))
tot = {'commit': 0, 'int_n': 0, 'int_c': 0, 'other': {}}
for i, (a, b) in enumerate(VEC, 1):
    emu.logcat_clear()
    t = time.time()
    run.type_code(a, gap=0.10)
    run.pick(0)
    t1 = time.time()
    time.sleep(0.8)                      # hop1 -> hop2 之间不做任何宿主操作
    run.type_code(b, gap=0.10)
    run.pick(0)
    time.sleep(0.5)
    lg = cases.logs()
    txt = '\n'.join(lg)
    n_int = len([l for l in lg if 'reason=interrupt' in l and 'count=' not in l])
    c_int = len([l for l in lg if 'reason=interrupt' in l and 'count=' in l])
    com = len(re.findall(r'commit', txt))
    others = sorted(set(re.findall(r'skipped reason=(\w+)', txt)) - {'interrupt'})
    exp = a[:2] + b[:2]
    hit = exp in txt
    tot['commit'] += com; tot['hit'] = tot.get('hit', 0) + (1 if hit else 0)
    tot['int_n'] += n_int; tot['int_c'] += c_int
    for o in others: tot['other'][o] = tot['other'].get(o, 0) + 1
    print(f'r{i} {a}+{b} expect={exp} hit={hit} commit={com} interrupt(no count)={n_int} interrupt(count)={c_int} other={others} '
          f'hop2@{time.time()-t1:.1f}s '
          f'field={kb.field_text()!r} [{time.time()-t:.1f}s]')
    for l in lg: print('     ', re.sub(r'^[DIWE]/', '', l)[:110])
print('=== TOTALS', tot)
print('=== user_dict ==='); print(emu.user_dict())
anr = [l for l in emu.logcat_dump().splitlines()
       if 'ANR in com.ispeech.ime' in l or 'Input dispatching timed out' in l]
print('=== ANR lines:', anr)
kb.shot('R1b_end.png')
