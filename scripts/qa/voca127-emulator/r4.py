"""VOCA-127 R4 — AC-3.8 开关关闭不写库（emulator-5554，五笔）。
开关本身在「设置 → 键盘与输入」页，该页有 P1 崩溃打不开，因此这里在 DataStore 层翻转
同一个 key（learning），验的是学习逻辑对开关值的响应；UI 路径另记为被 P1 阻塞。"""
import sys, time, re
sys.path.insert(0, '.')
import cases, emu, kb, run

def show_prefs():
    out = emu.adb('shell', 'run-as', 'com.ispeech.ime', 'cat',
                  'files/datastore/ispeech_settings.preferences_pb')
    return out

def learn_round(tag):
    emu.logcat_clear()
    run.type_code('klge', gap=0.08); run.pick(0)
    time.sleep(0.9)
    run.type_code('qtal', gap=0.08); run.pick(0)
    time.sleep(0.6)
    lg = cases.logs()
    print(f'[{tag}]', [re.sub(r'^[DIWE]/', '', l)[:100] for l in lg])
    d = emu.user_dict()
    print(f'     dict 含 klqt\\t喂猫 ? -> {"klqt\t喂猫" in d}')
    print(f'     dict: {d!r}')
    return lg, d

print('=== 1) 关开关：learning=false')
cases.reset(wubi=True, learning=False)
run.fresh_field(); run.ensure_editor(); cases.empty_field(focus=False)
print('   prefs bytes:', show_prefs().encode()[:1])
lg, d = learn_round('disabled')
assert 'reason=disabled' in '\n'.join(lg), '没出现 disabled 跳过'
assert "klqt\t喂猫" not in d, '关闭状态下仍写了词组'

print('\n=== 2) 重开开关：learning=true（不清词库，同机连续）')
cases.set_prefs(wubi=True, learning=True)
time.sleep(1.0)
run.fresh_field(); run.ensure_editor(); cases.empty_field(focus=False)
lg2, d2 = learn_round('enabled')
assert 'commit code=klqt text=喂猫' in '\n'.join(lg2), '重开后学不到 -> 判定失效'
print('\nAC-3.8 结论：关闭不写库 + 重开可学 = 两条都成立')
