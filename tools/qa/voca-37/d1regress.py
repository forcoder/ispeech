#!/usr/bin/env python3
"""第七轮打回项 3：VOCA-465（`35cdbfa`）让 `onKeyDown` 在空缓冲时吃掉 KEYCODE_SPACE，
回归面必须补测 —— 接管条件是一条 9 项合取（`SpaceKeyPolicy.takesHardwareSpace`），
少排除一项就会把宿主自己需要的空格抢成句号。

六档（对应裁决 §三-3 的 ①~⑥）：
  ① off_parity      开关关闭态与**修复前逐字相同**：同一套注入序列跑在 `fa57a07` 与 `78343a4`
                    两个包上，宿主文本逐项比 —— 不采信注释里的「行为与修复前一致」。
  ② english_space / t9_space   英文态空格、T9 数字态空格（谓词的两条排除项，各打一发）
  ③ host_spaces     宿主自己需要空格的场景：搜索框 / 密码框 / 非本包默认时的宿主
  ④ space_longpress 空格长按 500ms 进语音在接管后是否还成立（`installSpaceGesture`）
  ⑤ key_repeat      `repeatCount>0` 不接管（按住不放是连发不是双击）
  ⑥ space_probe     `spaceProbe` 跨进程读成本：连按 20 次的 roundTripMs 分布（父单点名 INP）

用法：python3 d1regress.py <apk> <build-label> [cases...]
      build-label 进截图名与 PARITY 行（`fixed` / `base`），两遍跑完由 `--diff` 比。
每条 PASS/FAIL 只是**读数**，判定写在报告里；本脚本不调期望值。
退出码：0 全部档出数 / 31 宿主起不来 / 32 键位反推失败 / 33 开关置不上目标态
"""
import os, re, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
import ac2 as A          # 复用同一份 set_switch/switch_state（面板点击逻辑不重写第二遍）

APK = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("QA_APK", "")
LBL = sys.argv[2] if len(sys.argv) > 2 else "fix"
ONLY = sys.argv[3:]
TAG = "IspeechIme"
RC = dict(host_down=31, key_map_fail=32, switch_stuck=33)


def gate(reason, **kw):
    D.log("GATE", reason=reason, rc=RC.get(reason, 1), **kw)
    raise SystemExit(RC.get(reason, 1))


def logd():
    return D.shell("logcat -d -s %s:V" % TAG)


def logc():
    D.shell("logcat -c")


def cal():
    """空格/shift/退格键心从当场截图反推（写死像素换面板就失效）。"""
    p = D.shot("shots/r7_d1_%s_cal.png" % LBL)
    m, diag = D.key_map(png=p)
    if not diag.get("ok"):
        gate("key_map_fail", **{k: str(v) for k, v in diag.items()})
    D.log("KEYMAP", lbl=LBL, space=m[" "], shift=m["shift"], del_xy=m["del"], runs=diag["runs"],
          space_w=diag["space_band"][2], space_rows=diag["space_band"][1])
    m["space_w"] = diag["space_band"][2]      # 空格键帽像素宽 = 4 个单位（spaceKey weight=4）
    m["space_band"] = diag["space_band"][1]   # 「中/英」键面按同一行的行带来裁
    return m


def inject(times, gap=0.0):
    """注入 times 发空格。single=True（gap=0）走一次 adb 调用 → 两次几乎 0 间隔。"""
    t0 = time.time()
    if gap == 0:
        D.shell("input keyevent " + " ".join(["62"] * times))
    else:
        for i in range(times):
            if i:
                time.sleep(gap)
            D.shell("input keyevent 62")
    return round((time.time() - t0) * 1000, 1)


def probe(case, seq, host_before=None, extra=None, pre=None):
    """清宿主 →（可选 pre 把状态铺到「组合中」）→ 执行 seq → 读宿主真值 + 该窗口的 IME 日志行。
    `pre` 必须在 `logc()` 之前跑完：T9/组合态要在清空之后重新铺码，否则 probe 自己的
    `clear(30)` 会把刚打的编码删干净，量到的其实是空缓冲双击（上一轮就量错了这一档）。"""
    D.clear(30)
    time.sleep(0.5)
    if pre:
        pre()
        time.sleep(0.7)
    before = D.focused_text()
    logc()
    wall = seq()
    time.sleep(0.9)
    host = D.focused_text()
    lines = [l.strip() for l in logd().splitlines() if TAG in l and ("space" in l or "voice" in l)]
    kw = dict(case=case, lbl=LBL, wall_ms=wall, before=repr(before)[:20],
              host=repr(host)[:24], takeover=sum(1 for l in lines if "via=key" in l),
              log_lines=len(lines))
    if extra:
        kw.update(extra)
    kw.setdefault("ts", time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    D.log("PROBE", **kw)
    for l in lines[:6]:
        D.log("LOGLINE", case=case, lbl=LBL, line=l[:180])
    return host, lines


def edittexts():
    """现场抓一帧 UI 树，列出所有 EditText。`cat` 到陈旧文件会把基线读成 0，所以每次现捕。"""
    D.shell("uiautomator dump /sdcard/wd.xml")
    xml = D.shell("cat /sdcard/wd.xml")
    eds = []
    for n in re.finditer(r'<node[^>]*class="android\.widget\.EditText"[^>]*>', xml):
        bb = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n.group(0))
        if bb:
            eds.append(dict(bounds=tuple(int(v) for v in bb.groups()),
                            hint=(re.search(r'text="([^"]*)"', n.group(0)) or ["", ""])[1][:16]))
    return eds


def focus_host():
    """档与档之间宿主可能被上一档的对话框带走（Wi-Fi 弹框、密码框）。焦点不在宿主输入框时
    probe 读到的永远是空串，接管净效果就没地方落了 —— 所以每档开头把焦点要回来。"""
    b = D.node_bounds("open_search_view_edit_text")
    if b and D.ime_frame()[2]:
        D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
        time.sleep(1.0)
        route = "already_up"
    else:
        D.shell("am start -W -n com.android.settings/.Settings")
        time.sleep(1.6)
        b = D.node_bounds("open_search_view_edit_text") or D.node_bounds("搜索设置")
        if b:
            D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
            time.sleep(1.3)
        route = "restarted"
    D.log("FOCUS", lbl=LBL, route=route, node=bool(b), ime_h=D.ime_frame()[2],
          focused=repr(D.focused_text())[:20])
    return route


def want_switch(on):
    pre = A.switch_state()
    if (not on) and pre.get("src") == "datastore_file_absent":
        # 基线（fa57a07）没有这个开关：DataStore 文件里根本没这个键 → 默认就是关。
        # 此时绝不能走设置页试点：那一版没有这一行，点下去只会翻转别的开关。
        D.log("SWITCH", want="OFF", state=pre["state"], src=pre["src"],
              route="absent_means_default_off",
              note="该构建无「双击空格句号」开关（VOCA-46 之前）→ 默认关；不走设置页试点，避免误伤其它开关")
        if pre["state"]:
            gate("switch_stuck", want=False, got=pre)
        return pre
    s = A.set_switch(on)
    if s["state"] != on:
        gate("switch_stuck", want=on, got=s)
    D.log("SWITCH", want=("ON" if on else "OFF"), state=s["state"], src=s["src"], route=s["route"])
    return s


# ============================ ① 开关关闭态 = 修复前 ============================
def case_off_parity(m):
    want_switch(False)
    focus_host()
    seqs = [("1x", lambda: inject(1)), ("2x", lambda: inject(2)), ("3x", lambda: inject(3)),
            ("2x_gap600", lambda: inject(2, 0.6)), ("letter_then_2space", None)]
    for name, fn in seqs:
        if name == "letter_then_2space":
            def fn():
                D.type_str("ab", gap=0.1)
                time.sleep(0.4)
                return inject(2)
        probe("off_" + name, fn)


# ============================ ② 英文态 / T9 数字态 ============================
# 判据迭代了两次，都不许拿「像英文」当「是英文」：
#   第一版「敲 zhongguo+空格出拉丁」→ 错。那次点的是第三行最左的 ⇧（SoftKeyboardView:452
#   「Shift 键不再被改造成中/英」），一次性大写 + 大写字母拼不出候选 → 空格上屏原始编码，
#   键面仍是中文态。
#   第二版「16 个字母不敲空格直接上屏」（IspeechImeService:670 `mode == ENGLISH || upper`）
#   → 也不成立：五笔 maxComposeLength=4，编码打满就 commitTop() 把原始编码连着上屏，
#   16 个字母同样能整段落进宿主（实测 host='abcdefghijklmnop' 时其实还在中文态）。
# 现在只认键面本身的地面真值：modeSwitchLabel() 把**当前态**那个字画成 textColor
# #1F2329(L≈33)、另一态画成 hintColor #8A9099(L≈144)，键帽 specialBg #CFD4DB(L≈212)。
# 「暗像素落在左半还是右半」= 中文态还是英文态，阈值 90 离两边都远。
INK_THR = 90


SPECIAL_BG = (207, 212, 219)     # SoftKeyboardView:122 specialBg = #CFD4DB（符 / 中-英 / 123 三枚）


def special_runs(png, a, b):
    """底行里 specialBg 键帽的列段：在带顶 19px 处横扫（避开字身），返回 [(left,right,width)]。
    不拿权重反推坐标 —— 实测键间有 ~0.3u 的净空，权重式会把中/英算偏 60px 打到隔壁键上。"""
    from PIL import Image
    px = Image.open(png).convert("RGB").load()
    W = Image.open(png).size[0]
    y = a + 19
    hit = [px[x, y] and (max(abs(px[x, y][i] - SPECIAL_BG[i]) for i in range(3)) <= 6)
           for x in range(W)]
    out, x = [], 0
    while x < W:
        if hit[x]:
            x2 = x
            while x2 + 1 < W and hit[x2 + 1]:
                x2 += 1
            if x2 - x + 1 >= 20:
                out.append((x, x2, x2 - x + 1))
            x = x2 + 1
        else:
            x += 1
    return out


def mode_key_box(m, png):
    """「中/英」键面矩形 = 底行里宽度最接近 1.8u 的那枚 specialBg 键帽（u = 空格键帽宽 ÷ 4）。
    认不出来（没有 1.8u ±0.35u 的段）就返回 None，调用方记 unreachable，不猜坐标。"""
    a, b = m["space_band"]
    u = m["space_w"] / 4.0
    runs = special_runs(png, a, b)
    best = min(runs, key=lambda r: abs(r[2] - 1.8 * u)) if runs else None
    if not best or abs(best[2] - 1.8 * u) > 0.35 * u:
        D.log("MODEBOX", lbl=LBL, verdict="no_1.8u_cap", runs=str(runs)[:120],
              unit_px=round(u, 1), want_px=round(1.8 * u, 1))
        return None
    D.log("MODEBOX", lbl=LBL, verdict="ok", cap=str(best), unit_px=round(u, 1),
          want_px=round(1.8 * u, 1), all_caps=str(runs)[:120])
    return (best[0] + 3, a + 6, best[1] - 2, b - 6)


def mode_ink(m, tag):
    """-> 是否英文态（键面分色读数）：当前态那个字是 textColor #1F2329(L≈33)，
    另一态是 hintColor #8A9099(L≈144)，阈值 90 离两边都远。左右 thirds 的暗像素数都落进日志。"""
    from PIL import Image
    p = D.shot("shots/r7_d1_%s_ink_%s.png" % (LBL, tag))
    box = mode_key_box(m, p)
    if not box:
        return None
    im = Image.open(p).convert("L").crop(box)
    w, h = im.size
    px = im.load()
    thirds = [sum(1 for x in range(int(w * t / 3), int(w * (t + 1) / 3)) for y in range(h)
                  if px[x, y] < INK_THR) for t in range(3)]
    eng = thirds[2] > thirds[0]
    D.log("MODEINK", lbl=LBL, sig=tag, png=os.path.basename(p), box=str(box),
          ink_zh=thirds[0], ink_sep=thirds[1], ink_en=thirds[2], thr=INK_THR, english=eng)
    return eng


MODEXY = [None]      # 验到的中/英键心，出英文态时按同一坐标切回来


def to_english(m, max_taps=3):
    """按地面真值进英文态：读键面分色，不是英文态就点「中/英」再读。
    每一发点击都当场验落点 —— 翻到英文态才算数；连点 3 次还翻不动就记 unreachable。"""
    prev, flips, last = None, 0, None
    for i in range(max_taps + 1):
        cur = mode_ink(m, "read%d" % i)
        if cur is None:
            D.log("GATE", reason="mode_key_box_unresolved", rc=0, case="english_space",
                  note="底行认不出 1.8u 的 specialBg 键帽，英文态判据落不了地")
            return False
        if cur:
            D.log("MODEKEY", lbl=LBL, verdict="reached_english" if i else "already_english",
                  reads=i, ink_flips=flips)
            return True
        if last is not None:
            flips += cur != last
        last = cur
        if i == max_taps:
            break
        bx = mode_key_box(m, D.shot("shots/r7_d1_%s_pre_tap%d.png" % (LBL, i)))
        if not bx:
            D.log("GATE", reason="mode_key_box_unresolved", rc=0, case="english_space")
            return False
        MODEXY[0] = ((bx[0] + bx[2]) // 2, m[" "][1])
        D.log("MODEKEY", lbl=LBL, tap_to=str(MODEXY[0]), reads=i + 1)
        D.shell("input tap %d %d" % MODEXY[0])
        time.sleep(1.2)
    D.log("GATE", reason="english_mode_unreachable", rc=0, case="english_space",
          tried_taps=max_taps, ink_flips=flips,
          note="点满 3 次「中/英」键面分色仍不出「英」字为深色")
    return False


def case_english(m):
    want_switch(True)
    focus_host()
    if not to_english(m):
        return
    host, lines = probe("english_2x_space", lambda: inject(2))
    # 同一档内闭环复验：探针跑完之后键面还得是英文态，否则「英文态出句号」这句结论不成立
    still = mode_ink(m, "after_probe")
    D.log("ENGLISH_CHECK", lbl=LBL, still_english=still,
          host=repr(host)[:24], takeover=sum(1 for l in lines if "via=key" in l))
    # 出英文态，别把状态留给下一档：按同一个键心切回去，并验确实切回中文态
    if MODEXY[0]:
        D.shell("input tap %d %d" % MODEXY[0])
        time.sleep(1.2)
    D.log("MODE", lbl=LBL, mode="still_english" if mode_ink(m, "exit") else "back_to_chinese")


def bottom_row_sharp(png, a, b):
    """底行的「白键帽最宽段 = 空格」与「空格左/右两侧的 specialBg 键帽」。
    页与页之间单位宽度不同（字母页 10.8 单位、数字页 8.5 单位），写死权重会把 123 算到
    「。」键上（上一轮实测 runs 没变 = 根本没进数字页），所以一律按当场像素认领。"""
    from PIL import Image
    img = Image.open(png).convert("RGB")
    px = img.load()
    W = img.size[0]
    y = a + 19
    whites, c = [], 0
    for x in range(W):
        w = min(px[x, y]) >= 245
        if w and c == 0:
            c = x
        elif not w and c:
            if x - c >= 30:
                whites.append((c, x - 1))
            c = 0
    if c:
        whites.append((c, W - 1))
    if not whites:
        return None
    sp = max(whites, key=lambda r: r[1] - r[0])
    caps = special_runs(png, a, b)
    left = [r for r in caps if r[1] < sp[0]]
    right = [r for r in caps if r[0] > sp[1]]
    return dict(space=sp, caps=caps, left_cap=left[-1] if left else None,
                right_cap=right[0] if right else None)


def case_t9(m):
    """T9 数字态：字母页底行「123」（空格右侧那枚 specialBg 键帽）→ 数字页底行「9键」
    （数字页里空格左侧那枚）→ 敲数字 → 空格。每步都按键面像素认领并回读键面证它到位：
    进了数字页，字母页独有的 1.8u「中/英」键帽应当消失；进了 T9，字母行应当整排消失。
    任一步没到位就如实记 unreachable + 尝试路径，不拿 JVM 单测冒充设备实测。"""
    want_switch(True)
    cy = m[" "][1]
    p = D.shot("shots/r7_d1_%s_t9_cal.png" % LBL)
    sh = bottom_row_sharp(p, *m["space_band"])
    if not sh or not sh["right_cap"]:
        D.log("GATE", reason="t9_cap_row_unresolved", rc=0, case="t9_space",
              note="底行认不出空格右侧的 specialBg 键帽（123）", detail=str(sh)[:120])
        return
    x123 = (sh["right_cap"][0] + sh["right_cap"][1]) // 2
    D.log("T9GEO", lbl=LBL, space_cap=str(sh["space"]), caps=str(sh["caps"])[:110],
          x123=x123, step1="空格右侧键帽 %s" % (sh["right_cap"],))
    D.shell("input tap %d %d" % (x123, cy))
    time.sleep(1.3)
    p1 = D.shot("shots/r7_d1_%s_num.png" % LBL)
    sh1 = bottom_row_sharp(p1, *m["space_band"])
    on_num = bool(sh1) and sh1["space"][1] - sh1["space"][0] > m["space_w"] * 1.2
    D.log("T9STEP", lbl=LBL, step="123", tap_xy=(x123, cy), space_cap=sh1 and str(sh1["space"]),
          caps=sh1 and str(sh1["caps"])[:110], unit_up_px=round(m["space_w"] / 4.0, 1),
          verdict="number_page（空格键帽变宽 = 底行单位从 10.8 换成 8.5）" if on_num else "not_number_page")
    if not on_num:
        D.log("GATE", reason="t9_number_page_not_reached", rc=0, case="t9_space",
              tried="123@x%d（空格右侧键帽）" % x123,
              fallback="JVM 单测 SpaceKeyPolicyTest 的 t9Pending 断言，非设备实测")
        D.shell("input keyevent 4")
        return
    if not sh1["left_cap"]:
        D.log("GATE", reason="t9_9jian_cap_missing", rc=0, case="t9_space", caps=str(sh1["caps"]))
        return
    x9 = (sh1["left_cap"][0] + sh1["left_cap"][1]) // 2
    D.shell("input tap %d %d" % (x9, cy))
    time.sleep(1.3)
    p2 = D.shot("shots/r7_d1_%s_t9.png" % LBL)
    runs_t9 = D.key_map(png=p2)[1].get("runs")
    on_t9 = bool(runs_t9) and max(runs_t9[:3]) <= 5
    D.log("T9STEP", lbl=LBL, step="9键", tap_xy=(x9, cy), left_cap=str(sh1["left_cap"]),
          runs_after=runs_t9, verdict="t9_grid" if on_t9 else "not_t9")
    if not on_t9:
        D.log("GATE", reason="t9_layout_unreachable_on_emulator", rc=0, case="t9_space",
              tried="123@x%d → 9键@x%d(同一底行 y=%d)" % (x123, x9, cy), runs=runs_t9,
              fallback="JVM 单测 SpaceKeyPolicyTest 的 t9Pending 断言，非设备实测")
        D.shell("input keyevent 4")
        D.shell("input keyevent 4")
        return
    # 三档分开测，顺序不能省：probe() 自己会清宿主，编码必须在清空之后重新铺，
    # 否则「组合中」那档量到的还是空缓冲双击（上一轮就是把这两档读成了同一个东西）。
    probe("t9_empty_then_2x_space", lambda: inject(2),
          extra=dict(t9_entry="123→9键 空缓冲"))
    # 组合中再打空格：第一发必须走 COMMIT_T9（把编码上屏），不能被当成双击句号
    probe("t9_composing_then_2x_space", lambda: inject(2),
          extra=dict(t9_entry="123→9键 组合中"),
          pre=lambda: D.shell("input keyevent 7"))
    probe("t9_composing_then_1x_space", lambda: inject(1),
          extra=dict(t9_entry="123→9键 组合中 单击"),
          pre=lambda: D.shell("input keyevent 7"))
    D.shell("input keyevent 4")


# ============================ ③ 宿主自己要空格 ============================
def case_host_spaces(m):
    want_switch(True)
    focus_host()
    probe("host_search_2x_space", lambda: inject(2))
    # 密码框：设置 → Wi-Fi → 添加网络。安全性默认「无」时框里只有 SSID 一个输入框，
    # 必须先把安全性选成 WPA，密码框才会出现（上一轮把 SSID 当成了密码框）。
    D.shell("am start -W -a android.settings.WIFI_SETTINGS")
    time.sleep(2.4)
    opened = False
    for needle in ("添加网络", "添加 WPA3 个人化网络", "Scan", "搜索"):
        b = D.node_bounds(needle)
        D.log("PWSTEP", lbl=LBL, needle=needle, hit=bool(b))
        if b:
            D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
            time.sleep(1.9)
            opened = True
            break
    if not opened:
        D.log("GATE", reason="add_network_dialog_unreachable", rc=0, case="host_password")
        return
    # 弹框里现成的第一个输入框（网络名称 SSID）就是「另一种宿主」：它和设置页搜索框不是
    # 同一个窗口实现，接管得各测一遍才说得上「宿主自己要不要空格」。
    eds0 = edittexts()
    if eds0:
        b = eds0[0]["bounds"]
        D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
        time.sleep(1.4)
        probe("host_dialog_ssid_2x_space", lambda: inject(2),
              extra=dict(host_kind="dialog_edittext", hint=eds0[0]["hint"]))
    # uiautomator 不暴露 inputType，所以密码框的认法 = 选完 WPA 之后**新出现**的那个 EditText，
    # 再用「打了字但宿主读不到明文」证它确实是掩码字段。基线必须现捕：`cat` 到一个陈旧的
    # /sdcard/wd.xml（上一次 dump 正在动画里没写成）会把基线读成 0，「新出现一个框」就无从判定。
    before_eds = edittexts()
    for needle in ("安全性", "安全", "Security", "类型"):
        b = D.node_bounds(needle)
        D.log("PWSEC", lbl=LBL, row=needle, hit=bool(b))
        if b:
            D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
            time.sleep(1.6)
            break
    picked_sec = None
    for opt in ("WPA2-Personal", "WPA2 个人", "WPA3-Personal", "WPA3 个人", "WPA-PSK", "WPA2"):
        b = D.node_bounds(opt)
        if b:
            picked_sec = opt
            D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
            time.sleep(1.7)
            break
    eds = edittexts()
    known = [e["bounds"] for e in before_eds]
    fresh = [e for e in eds if e["bounds"] not in known]
    D.log("PWEDITTEXTS", lbl=LBL, n=len(eds), n_before_sec_pick=len(before_eds),
          fresh_bounds=[e["bounds"] for e in fresh], sec_option=picked_sec,
          detail=str(eds)[:220])
    if not fresh:
        D.log("GATE", reason="password_host_unreachable", rc=0, case="host_password",
              note="选过安全性之后没有新出现的 EditText（仍是 %d 个）；密码框待复现，不冒测" % len(eds),
              tried="添加网络 → 安全性 → %s" % (picked_sec or "树里没有 WPA 选项"))
        D.shell("input keyevent 4")
        return
    tgt = fresh[0]
    b = tgt["bounds"]
    D.shell("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    time.sleep(1.5)
    D.clear(12)
    D.type_str("ab", gap=0.15)
    time.sleep(0.9)
    read = D.focused_text() or ""
    masked = read in ("", "••") or bool(read) and read[0] not in "abAB"
    D.log("PWFOCUS", lbl=LBL, picked="newly_appeared_edittext", hint=tgt["hint"], typed="ab",
          host_read=repr(read)[:16], masked_by_read=masked)
    probe("host_password_2x_space", lambda: inject(2),
          extra=dict(host_kind="password" if masked else "dialog_edittext_unmasked"))
    D.shell("input keyevent 4")
    D.shell("input keyevent 4")


# ============================ ④ 空格长按进语音 ============================
def band_diff(pa, pb, y0, y1, thr=24, min_px=5):
    """IME 面板窗口内做逐行像素差分，返回变化最大的几条行带。
    语音态没有任何日志（`toggleVoskVoice` 只改 `statusText`/`codeOverride` 再重绘），
    所以「长按进语音」的唯一地面真值就是键面本身变了 —— 整屏平均亮度会把键帽预览
    的高光也算成变化，这里按行带定位改动落在候选栏还是键区。"""
    from PIL import Image, ImageChops, ImageStat
    a, b = Image.open(pa).convert("L"), Image.open(pb).convert("L")
    W = min(a.size[0], b.size[0])
    y1 = min(y1, a.size[1], b.size[1])
    d = ImageChops.difference(a.crop((0, y0, W, y1)), b.crop((0, y0, W, y1)))
    px = d.load()
    h = y1 - y0
    rows = []
    for y in range(h):
        n = 0
        for x in range(0, W, 3):
            if px[x, y] > thr:
                n += 1
        rows.append(n)
    bands, y = [], 0
    while y < h:
        if rows[y] > min_px:
            z = y
            while z < h and rows[z] > min_px:
                z += 1
            bands.append((y0 + y, y0 + z, max(rows[y:z])))
            y = z
        else:
            y += 1
    bands.sort(key=lambda t: -(t[1] - t[0]) * t[2])
    return dict(changed_rows=sum(1 for r in rows if r > min_px),
                bands=bands[:4],
                mean_abs_luma=round(float(ImageStat.Stat(d).mean[0]), 2))


def case_longpress(m):
    want_switch(True)
    x, y = m[" "]
    top0, bot0, h0 = D.ime_frame()
    p0 = D.shot("shots/r7_d1_%s_lp_before.png" % LBL)
    logc()
    D.shell("input swipe %d %d %d %d 900" % (x, y, x, y))     # 同点长按 900ms > 500ms 阈值
    time.sleep(0.8)
    p1 = D.shot("shots/r7_d1_%s_lp_after.png" % LBL)
    top1, bot1, h1 = D.ime_frame()
    lines = [l.strip() for l in logd().splitlines() if TAG in l]
    voice = [l for l in subprocess.run([D.ADB, "-s", D.S, "logcat", "-d"], capture_output=True,
                                       text=True).stdout.splitlines()
             if re.search(r"voice|语音|asr|ASR", l, re.I) and "ispeech" in l.lower()]
    bd = band_diff(p0, p1, top0 or 1352, bot0 or 10 ** 9)
    commit_lines = [l for l in lines if "space via=" in l]
    D.log("LONGPRESS", lbl=LBL, hold_ms=900, space_xy=(x, y), h_before=h0, h_after=h1,
          frame_top_before=top0, frame_top_after=top1,
          band_changed_rows=bd["changed_rows"], band_top_bands=bd["bands"],
          ime_panel_mean_abs_luma=bd["mean_abs_luma"],
          ime_log_lines=len(lines), space_commit_lines=len(commit_lines),
          voice_log_lines=len(voice),
          verdict="ui_changed" if bd["changed_rows"] > 12 else "no_visible_change")
    for l in voice[:5]:
        D.log("VOICELOGLINE", lbl=LBL, line=l[:170])
    D.log("HOST_AFTER_LP", lbl=LBL, host=repr(D.focused_text())[:24])
    D.shell("input keyevent 4")       # 收掉可能的语音浮层
    time.sleep(1.0)
    # 同一条键上的接管：连点两下空格（软键路径，`via=soft`）必须出句号，
    # 出完之后立刻长按 —— 手势与接管共用同一个键面，接管不能把语音入口吃掉。
    focus_host()
    probe("soft_double_tap_before_lp", lambda: soft_tap_space(m, 2, gap=0.12),
          extra=dict(key_face="space", via="soft"))
    top2, bot2, h2 = D.ime_frame()
    p2 = D.shot("shots/r7_d1_%s_lp2_before.png" % LBL)
    logc()
    D.shell("input swipe %d %d %d %d 900" % (x, y, x, y))
    time.sleep(0.8)
    p3 = D.shot("shots/r7_d1_%s_lp2_after.png" % LBL)
    voice2 = [l for l in subprocess.run([D.ADB, "-s", D.S, "logcat", "-d"], capture_output=True,
                                        text=True).stdout.splitlines()
              if re.search(r"voice|语音|asr|ASR", l, re.I) and "ispeech" in l.lower()]
    bd2 = band_diff(p2, p3, top2 or 1352, bot2 or 10 ** 9)
    lines2 = [l.strip() for l in logd().splitlines() if TAG in l]
    D.log("LONGPRESS_AFTER_TAKEOVER", lbl=LBL, hold_ms=900,
          band_changed_rows=bd2["changed_rows"], band_top_bands=bd2["bands"],
          ime_panel_mean_abs_luma=bd2["mean_abs_luma"],
          space_commit_lines=sum(1 for l in lines2 if "space via=" in l),
          voice_log_lines=len(voice2), h_after=D.ime_frame()[2],
          verdict="ui_changed" if bd2["changed_rows"] > 12 else "no_visible_change")
    for l in voice2[:5]:
        D.log("VOICELOGLINE2", lbl=LBL, line=l[:170])
    # 接管之后这条键还得能正常打空格：单点一下必须是普通空格，不能接着出句号
    probe("soft_single_tap_after_takeover", lambda: soft_tap_space(m, 1),
          extra=dict(key_face="space", via="soft"))
    D.shell("input keyevent 4")
    time.sleep(1.0)


def soft_tap_space(m, times, gap=0.12):
    """点 times 下软键盘空格键面（`via=soft` 那条路径），返回墙钟毫秒。"""
    x, y = m[" "]
    t0 = time.time()
    for i in range(times):
        if i:
            time.sleep(gap)
        D.shell("input tap %d %d" % (x, y))
    return round((time.time() - t0) * 1000, 1)


# ============================ ⑤ repeatCount>0 ============================
def case_repeat(m):
    """`event.repeatCount > 0` 不接管（IspeechImeService.kt:829 → SpaceKeyPolicy.kt:117）。

    先量清楚「这台机器有没有办法造出自动重复」：repeatCount 由 InputReader 在内核
    EV_KEY value=2（HIKEY）时生成，`input keyevent` 只注入 repeatCount=0 的独立按键，
    `--repeat` 在这个版本的 shell 里根本没有这个选项。所以设备侧要么给出真重复事件，
    要么老实挂 GATE，不能把「adb 双击没出句号」当成 keyRepeat 的证据。
    """
    want_switch(True)
    focus_host()
    caps = D.shell("getevent -p")
    devs = []
    for seg in caps.split("add device ")[1:]:
        lines = seg.splitlines()
        name = next((l.split('"')[1] for l in lines[:3] if '"' in l), "?")
        keyline = next((l for l in lines if "KEY (0001):" in l), "")
        codes = keyline.split(":", 1)[1].split() if keyline else []
        devs.append(dict(name=name, has_space="0039" in codes, ev_rep="REP (0014):" in seg))
    usage = D.shell("input")
    opt_line = next((l.strip() for l in usage.splitlines() if "keyevent" in l), "")
    D.log("REPEATCAP", lbl=LBL, devices=devs,
          devices_with_space_key=sum(1 for d in devs if d["has_space"]),
          devices_with_evrep=sum(1 for d in devs if d["ev_rep"]),
          keyevent_options=opt_line[:110])
    # 行为读数：`--repeat 2` 与 `--longpress` 各打一发，看宿主收到几发空格
    probe("repeat_flag_repeat2", lambda: D.shell("input keyevent --repeat 2 62"))
    probe("repeat_flag_longpress", lambda: D.shell("input keyevent --longpress 62"))
    # 阳性对照：同一注入方式下的普通双击必须出句号，否则这一档的阴性说明不了任何事
    probe("repeat_control_plain_2x", lambda: inject(2))
    D.log("GATE", reason="repeatcount_not_injectable_on_emulator", rc=0, case="key_repeat",
          note="设备侧造不出 repeatCount>0：无 EV_KEY/EV_REP 键盘节点，keyevent 无 --repeat 选项；"
               "上面两条 flag 探针是「退化成独立注入」的记录，不是 keyRepeat 的证据",
          fallback="JVM 单测 SpaceKeyPolicyTest.`硬件键长按的自动重复不算双击`"
                   "（takes(repeat = true) 断言 assertFalse）+ 源码锚点 IspeechImeService.kt:829",
          needs="真机/外接键盘按住空格不放，补一次设备实测")


# ============================ ⑥ spaceProbe 分布 ============================
def case_probe(m):
    want_switch(True)
    focus_host()
    rt, gaps, hits = [], [], 0
    logc()
    for i in range(20):
        D.clear(10)
        D.shell("input keyevent 62 62")
        time.sleep(0.45)
    txt = logd()
    for l in txt.splitlines():
        mm = re.search(r"roundTripMs=(\d+)", l)
        if mm:
            rt.append(int(mm.group(1)))
        ma = re.search(r"agoMs=(\d+)", l)
        if ma:
            gaps.append(int(ma.group(1)))
        if "via=key" in l:
            hits += 1
    host = D.focused_text()
    D.log("SPACEPROBE", lbl=LBL, presses=20, probes=len(rt), takeover_lines=hits,
          roundTripMs_min=min(rt) if rt else None, roundTripMs_med=sorted(rt)[len(rt) // 2] if rt else None,
          roundTripMs_p95=sorted(rt)[int(len(rt) * 0.95) - 1] if rt else None,
          roundTripMs_max=max(rt) if rt else None,
          agoMs_med=sorted(gaps)[len(gaps) // 2] if gaps else None,
          final_host=repr(host)[:24],
          note="roundTripMs=getTextBeforeCursor 单次跨进程读；agoMs=距上一次空格上屏")
    for l in [x for x in txt.splitlines() if "spaceProbe" in x][:8]:
        D.log("PROBELOGLINE", lbl=LBL, line=l.strip()[:170])


CASES = dict(off_parity=case_off_parity, english_space=case_english, t9_space=case_t9,
             host_spaces=case_host_spaces, space_longpress=case_longpress,
             key_repeat=case_repeat, space_probe=case_probe)

def diff_parity(fbase, ffix):
    """① 的判定式：同一套注入序列在 base(`fa57a07`) 与 fixed(`78343a4`) 上的宿主文本逐项比。
    只认原文里的 `PROBE case=off_*` 行，不看注释、不看提交信息。"""
    def read(f):
        out = {}
        for line in open(f, encoding="utf-8"):
            if not line.startswith("PROBE\t"):
                continue
            kv = dict(x.split("=", 1) for x in line.rstrip("\n").split("\t")[1:] if "=" in x)
            if kv.get("case", "").startswith("off_"):
                out[kv["case"]] = (kv.get("host"), kv.get("takeover"))
        return out
    a, b = read(fbase), read(ffix)
    for k in sorted(set(a) | set(b)):
        D.log("PARITY", case=k, base=a.get(k), fixed=b.get(k), identical=(a.get(k) == b.get(k)))
    D.log("PARITY_SUMMARY", base_cases=len(a), fixed_cases=len(b),
          all_identical=bool(a) and bool(b) and a == b,
          note="takeover 计数在 base 上恒 0（base 没有 via= 字段），比的是 host 列")


if __name__ == "__main__":
    if "--diff" in sys.argv:
        i = sys.argv.index("--diff")
        diff_parity(sys.argv[i + 1], sys.argv[i + 2])
        raise SystemExit(0)
    if not APK:
        raise SystemExit("需要 apk（IDENT 闸门要用）")
    orig = D.orig_state()
    subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-d", "-g", APK], capture_output=True, timeout=900)
    D.shell("ime enable %s" % D.IME)
    D.shell("ime set %s" % D.IME)
    st = D.host_ready(rounds=4)
    if not st.get("ok"):
        st = D.setup_contacts(retries=3)
    if not st.get("ok"):
        gate("host_down", **{k: str(v) for k, v in st.items()})
    ok_build = A.F.ident_once(APK) if hasattr(A, "F") else None
    D.log("IDENT", stage="start", apk=os.path.basename(APK), match=ok_build,
          head=("fa57a07" if LBL == "base" else os.environ.get("QA_HEAD", "78343a4")),
          spec=os.environ.get("QA_SPEC", "0e4eeb0"),
          ts=time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    if ok_build is not True:
        gate("installed_apk_not_this_build", rc=19, stage="start")
    D.log("HEAD", lbl=LBL, apk=APK, ident=ok_build,
          ime=D.shell("settings get secure default_input_method").strip(),
          panel="%dx%d" % D.panel_size(), dens=D.live_density(),
          build_label=LBL, ts=time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    m = cal()
    for name in (ONLY or list(CASES)):
        D.log("CASE", name=name, lbl=LBL, ts=time.strftime("%Y-%m-%dT%H:%M:%S%z"))
        CASES[name](m)
    D.restore_state(orig)
    D.log("TAIL", lbl=LBL, switch_final=A.switch_state()["state"],
          font_scale_now=D.shell("settings get system font_scale").strip())
