"""VOCA-127 R3 — AC-3.12 前/后快照 + AC-3.7 学后 index 0（emulator-5554，五笔）。"""
import sys, time, re
sys.path.insert(0, '.')
import cases, emu, kb, run

print('=== 0) 干净机：pm clear + 注入 WUBI/learning=true')
cases.reset()
run.fresh_field()
run.ensure_editor()
cases.empty_field(focus=False)
print('   dict@start:', repr(emu.user_dict()))

print('\n=== 1) AC-3.12 学习前快照：新输入框打整码 klqt')
run.type_code('klqt', gap=0.08)
time.sleep(1.0)
kb.shot('R3_before.png')
print('   buffer in field:', repr(kb.field_text()))
emu.adb('shell', 'input', 'keyevent', '111'); time.sleep(0.6)   # 清缓冲
run.backspace(6)

print('\n=== 2) 学「喂猫」：klge->喂 , 1s 内 qtal->猫')
emu.logcat_clear()
run.type_code('klge', gap=0.08); run.pick(0)
time.sleep(0.9)
run.type_code('qtal', gap=0.08); run.pick(0)
time.sleep(0.6)
lg = cases.logs()
print('   ', [re.sub(r'^[DIWE]/', '', l)[:90] for l in lg])
print('   dict:', repr(emu.user_dict()))

print('\n=== 3) AC-3.12 学习后快照：另开新输入框打 klqt')
emu.adb('shell', 'input', 'keyevent', '4'); time.sleep(1.0)      # 退出表单
run.fresh_field()
run.ensure_editor()
cases.empty_field(focus=False)
run.type_code('klqt', gap=0.08)
time.sleep(1.0)
kb.shot('R3_after.png')
print('   buffer in field:', repr(kb.field_text()))

print('\n=== 4) AC-3.7：点 index 0（数字键 1）应上屏「喂猫」')
run.pick(0)
time.sleep(0.6)
print('   field after pick:', repr(kb.field_text()))
kb.shot('R3_picked.png')

print('\n=== 5) 交叉校验：候选条序列（uiautomator 不含 IME 窗口，用截图判定）')
print('   dict:', repr(emu.user_dict()))
