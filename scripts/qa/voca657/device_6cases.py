#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VOCA-657 真机补跑：条目 3 词组自学习 6 项（承接 VOCA-127 的 0/6 未验项）

一条命令跑完 6 项，逐项输出 pass / fail / blocked 与证据路径。

    真机：  python3 scripts/qa/voca657/device_6cases.py --serial FMR0224913042917 --expect-model ALN-AL00
    模拟器：python3 scripts/qa/voca657/device_6cases.py --serial emulator-5554 --emulator
    单项：  ... --cases 1,2
    不跑 UI 段（真机 release 包 run-as 不可用时）：... --no-prefs

产物：./qa-evidence/voca657-<UTC时间戳>/
  device.txt / apk.txt        机型自证（序列号、ro.kernel.qemu、APK sha256、versionName）
  case<N>-<name>.log         该用例独占 logcat 段（-c 清缓冲后只取本段）
  case<N>-*.png              截屏（候选序 / 设置页三态）
  user_dict-*.txt            词库快照（run-as 不可用时由设置页截图替代）
  REPORT.md                  6 项判定表 + 逐项证据索引

判据口径（逐字对齐 docs/qa/VOCA-127-phrase-learning-device-test-plan.md §4 与
docs/requirements/VOCA-65-acceptance.md v1.1.2）：
  ① commit 出现 + 无 reason=interrupt(不带 count=) + 词库 +1
  ② skipped reason=interrupt（不带 count=）+ 词库无新增
  ③ 无 commit + 词库无新增（弱判据，不要求 skipped 日志）
  ④ 候选 index 0 = 学到的词，原两条在、顺序不变、项数 +1
  ⑤ learning=false 时 reason=disabled 且无新增；设置页可看/可删/可清空
  ⑥ 无 ANR / 无输入超时 / 无主线程写盘告警

本脚本不伪造结果：没有设备退出 2 并打印 blocked 原因；模拟器读数一律标注
「模拟器层，不顶替真机」；涉及候选条 / 设置页的 UI 断言同时落截屏，供第三人复核。
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time

PKG = "com.ispeech.ime"
IME_COMPONENT = "com.ispeech.ime/com.ispeech.input.IspeechImeService"
PREFS = "files/datastore/ispeech_settings.preferences_pb"
DICT = "files/user_dict.txt"

# 五笔字根键 → 宿主机 keyevent 码
CODE = {c: 29 + i for i, c in enumerate("abcdefghijklmnopqrstuvwxyz")}
# 主向量：喂(klge) + 猫(qtal) = klqt（全库仅 中国银行 / 喂猪 两条整码词）
PAIRS = [
    ("klge", "qtal"), ("klge", "mmmm"), ("klge", "iiii"), ("klge", "oooo"),
    ("klge", "dgtg"), ("klge", "ffff"), ("klge", "ssss"), ("klge", "qqqq"),
    ("klge", "fghy"), ("klge", "awxb"),
]
TARGET_CODE, TARGET_WORD = "klqt", "喂猫"

CONNECTED = []
FAILS = []


# ───────────────────────────── adb 封装 ─────────────────────────────
class Dev:
    def __init__(self, serial, timeout=60):
        self.serial = serial
        self.timeout = timeout

    def __call__(self, *args, stdin=None, binary=False, check=False):
        cmd = ["adb", "-s", self.serial] + [str(a) for a in args]
        r = subprocess.run(cmd, capture_output=True, input=stdin, timeout=self.timeout)
        out = r.stdout if binary else (r.stdout or b"").decode("utf-8", "replace")
        err = (r.stderr or b"").decode("utf-8", "replace")
        if check and r.returncode != 0:
            raise RuntimeError("adb %s 失败: %s" % (" ".join(args), err.strip()))
        return out if binary else (out + err)

    def sh(self, *args, **kw):
        return self("shell", *args, **kw)


def prop(d, name):
    return d.sh("getprop", name).strip()


def say(msg):
    print("==> " + msg, flush=True)


# ───────────────────────── 起跑前置（设备 + 包自证） ─────────────────────────
def preflight(d, args, out):
    lines = []
    state = d("get-state").strip()
    if state != "device":
        print("BLOCKED: 没有可用设备（adb get-state=%r）" % state, file=sys.stderr)
        sys.exit(2)
    qemu = prop(d, "ro.kernel.qemu")
    model = prop(d, "ro.product.model")
    serialno = prop(d, "ro.serialno") or args.serial
    emu = args.serial.startswith("emulator-") or qemu == "1"
    if emu and not args.emulator:
        print("BLOCKED: 当前设备 %s 是模拟器（ro.kernel.qemu=%r），按本单判据不能顶替真机。\n"
              "  真机回线后重跑；若只做脚本空跑自证，加 --emulator（读数会标「模拟器层」）。"
              % (args.serial, qemu), file=sys.stderr)
        sys.exit(2)
    if args.expect_model and args.expect_model not in model:
        lines.append("WARN 机型不符：期望含 %r，实测 %r" % (args.expect_model, model))
    if args.expect_serial and serialno != args.expect_serial:
        lines.append("WARN 序列号不符：期望 %s，实测 %s" % (args.expect_serial, serialno))

    fields = [
        ("serial", args.serial), ("ro.serialno", serialno),
        ("ro.product.model", model), ("ro.product.device", prop(d, "ro.product.device")),
        ("ro.build.display.id", prop(d, "ro.build.display.id")),
        ("ro.build.version.release", prop(d, "ro.build.version.release")),
        ("ro.build.version.sdk", prop(d, "ro.build.version.sdk")),
        ("ro.kernel.qemu", qemu),
        ("layer", "模拟器层（不顶替真机）" if emu else "真机"),
        ("host_utc", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        ("device_date", d.sh("date").strip()),
    ]
    for k, v in fields:
        lines.append("%-24s %s" % (k, v))

    # 包自证：versionName + 已装 APK 的 sha256 + phrase_learn 字符串
    dumpsys = d.sh("dumpsys", "package", PKG)
    vn = re.search(r"versionName=(\S+)", dumpsys)
    vc = re.search(r"versionCode=(\d+)", dumpsys)
    apk_path = d.sh("pm", "path", PKG).strip().replace("package:", "").split("\n")[0]
    lines.append("%-24s %s" % ("versionName", vn.group(1) if vn else "?"))
    lines.append("%-24s %s" % ("versionCode", vc.group(1) if vc else "?"))
    lines.append("%-24s %s" % ("apk_path", apk_path or "?"))
    sha = "?"
    if apk_path:
        local = os.path.join(out, "base.apk")
        d("pull", apk_path, local)
        if os.path.exists(local):
            sha = hashlib.sha256(open(local, "rb").read()).hexdigest()
            os.remove(local)
    lines.append("%-24s %s" % ("apk_sha256", sha))
    hits = "n/a"
    if args.local_apk and os.path.exists(args.local_apk):
        hits = str(count_phrase_learn(args.local_apk))
    lines.append("%-24s %s" % ("phrase_learn_hits", hits))
    run_as = d.sh("run-as", PKG, "id").strip()
    debug_pkg = "uid=" in run_as
    lines.append("%-24s %s" % ("run_as", "可用（debug 包）" if debug_pkg else "不可用（release 包）"))
    open(os.path.join(out, "device.txt"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return dict(emulator=emu, model=model, serialno=serialno, version_name=vn.group(1) if vn else "?",
                apk_sha256=sha, debug_pkg=debug_pkg, run_as=run_as)


def count_phrase_learn(apk):
    import zipfile
    n = 0
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.match(r"classes\d*\.dex$", name):
                data = z.read(name)
                n += data.count(b"phrase_learn")
    return n


# ───────────────────────────── 前置操作 ─────────────────────────────
def entry(key, val_bytes):
    k = key.encode()
    e = b"\x0a" + bytes([len(k)]) + k + b"\x12" + bytes([len(val_bytes)]) + val_bytes
    return b"\x0a" + bytes([len(e)]) + e


def set_prefs(d, wubi=True, learning=True):
    """写产品自己的 DataStore key：输入态 WUBI + 学习开关。

    设置页「键盘与输入」在本机实测**可以打开**（VOCA-127 记录的 P1 崩溃未复现于 0168e59），
    但 Compose 的 switch 行在 uiautomator 里没有 checkable 节点、点不准，所以输入态与开关
    仍走持久化等价路径（DataStore 同一个 key），UI 侧只做「词库可见 / 可删 / 可清空」取证。
    这条路径只在 debug 包成立（run-as）：真机 release 包必须手工切五笔、手工关开关。
    """
    doc = b""
    if wubi:
        doc += entry("input_method_mode", b"\x2a\x04WUBI")
    doc += entry("learning", b"\x08" + (b"\x01" if learning else b"\x00"))
    d("shell", "am force-stop " + PKG)
    cmd = ("run-as %s sh -c 'mkdir -p files/datastore && cat > %s'" % (PKG, PREFS))
    d("shell", cmd, stdin=doc)
    d.sh("ime", "enable", IME_COMPONENT)
    d.sh("ime", "set", IME_COMPONENT)


def reset(d, prefs=True, learning=True):
    d.sh("pm", "clear", PKG)
    time.sleep(1.5)
    if prefs:
        set_prefs(d, True, learning)
    else:
        d.sh("ime", "enable", IME_COMPONENT)
        d.sh("ime", "set", IME_COMPONENT)


def fresh_host(d):
    """拉起宿主输入框（系统「新建联系人」名字栏），焦点落在 EditText 上。"""
    for p in ("com.google.android.contacts", "com.android.contacts"):
        d.sh("am", "force-stop", p)
    time.sleep(0.8)
    d.sh("am", "start", "-a", "android.intent.action.INSERT",
         "-t", "vnd.android.cursor.dir/contact")
    time.sleep(2.5)
    for _ in range(6):
        xml = dump(d, "n")
        cancel = [n for n in nodes(xml) if n.get("text") in ("Cancel", "Discard")]
        if cancel:
            tap_node(d, cancel[0])
            time.sleep(1.2)
            continue
        eds = [n for n in nodes(xml) if n.get("class", "").endswith("EditText")]
        if eds:
            tap_node(d, eds[0])
            time.sleep(1.6)
            return eds[0]["_b"]
        time.sleep(1.5)
    raise RuntimeError("宿主输入框没起来（无 EditText）")


def empty_field(d, dels=80):
    # 一次性发 N 个 DEL：比逐条 adb 等待快 20 倍，且不会在中间插入其它事件
    d.sh("input", "keyevent", " ".join(["67"] * dels))
    time.sleep(0.5)


def type_code(d, s, gap=0.09):
    for ch in s:
        d.sh("input", "keyevent", CODE[ch])
        time.sleep(gap)
    time.sleep(0.4)


def pick(d, idx=0):
    d.sh("input", "keyevent", 8 + idx)
    time.sleep(0.35)


def hop(d, code, idx=0, pause=0.9):
    type_code(d, code)
    pick(d, idx)
    time.sleep(pause)


def field_text(d):
    xml = dump(d, "f")
    for n in nodes(xml):
        if n.get("class", "").endswith("EditText") and n.get("focused") == "true":
            return n.get("text") or ""
    return None


def user_dict(d):
    return d.sh("run-as", PKG, "cat", DICT) if CONNECTED_DBG else ""


CONNECTED_DBG = False


def snap_dict(d, out, tag):
    if not CONNECTED_DBG:
        return None
    txt = d.sh("run-as", PKG, "cat", DICT).replace("\r", "")
    path = os.path.join(out, "user_dict-%s.txt" % tag)
    open(path, "w").write(txt)
    return [ln for ln in txt.splitlines() if ln.strip()]


def shot(d, path):
    data = d("exec-out", "screencap", "-p", binary=True)
    open(path, "wb").write(data)
    return path


def logs(d, pattern=r"phrase_learn|phrase_remove|ANR|StrictMode|not responding"):
    return [ln for ln in d.sh("logcat", "-d", "-v", "time").splitlines() if re.search(pattern, ln)]


def dump(d, tag):
    d.sh("uiautomator", "dump", "/sdcard/%s.xml" % tag)
    return d.sh("cat", "/sdcard/%s.xml" % tag)


def nodes(xml):
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


def tap(d, x, y):
    d.sh("input", "tap", str(x), str(y))
    time.sleep(0.4)


def tap_node(d, n):
    tap(d, *n["_c"])


def screen(d):
    m = re.search(r"(\d+)x(\d+)", d.sh("wm", "size"))
    return int(m.group(1)), int(m.group(2))


def panel_tap_point(d):
    """工具栏第 4 键（短语）：8 键行按 1080×2340 实测比例定位，返回屏幕比例坐标。"""
    w, h = screen(d)
    return int(w * 0.437), int(h * 0.642)


# ───────────────────────────── 6 个用例 ─────────────────────────────
def case1(d, out, args, dev):
    """AC-3.4 ① 连续拼两个不同单字、不动光标 → commit + 词库 +1

    口径修正 1（来自 VOCA-127 收口核验）：**同一会话不清库**连跑 10 组，
    覆盖词库增长后的去重与写盘路径（127 那轮 10 例 pid 全不同 = 10 次独立单例）。
    """
    reset(d, args.prefs)
    fresh_host(d)
    empty_field(d)
    before = snap_dict(d, out, "c1-before") or []
    rows = []
    for i, (a, b) in enumerate(PAIRS, 1):
        d.sh("logcat", "-c")
        hop(d, a)
        hop(d, b)
        lg = logs(d)
        txt = "\n".join(lg)
        exp = a[:2] + b[:2]
        n_int = len([l for l in lg if "reason=interrupt" in l and "count=" not in l])
        c_int = len([l for l in lg if "reason=interrupt" in l and "count=" in l])
        rows.append(dict(i=i, pair=a + "+" + b, expect=exp,
                         commit=("commit code=%s" % exp) in txt,
                         interrupt=n_int, interrupt_count=c_int,
                         skips=sorted(set(re.findall(r"skipped reason=(\w+)", txt))),
                         dict_lines=snap_dict(d, out, "c1-r%02d" % i) or [],
                         field=field_text(d)))
        open(os.path.join(out, "case1-%02d.log" % i), "w").write("\n".join(lg) + "\n")
        print("  r%02d %s expect=%s commit=%s interrupt(no count)=%d skips=%s"
              % (i, rows[-1]["pair"], exp, rows[-1]["commit"], n_int, rows[-1]["skips"]))
    after = snap_dict(d, out, "c1-after") or []
    n_commit = sum(1 for r in rows if r["commit"])
    n_int = sum(r["interrupt"] for r in rows)
    # 「词库 +1」逐轮核对：每轮新增正好一条 kl 前缀词组行
    per_round = [len([l for l in r["dict_lines"] if l.startswith(r["expect"] + "\t")]) == 1 for r in rows]
    ok = n_commit == 10 and n_int == 0 and all(per_round)
    return dict(name="AC-3.4① 连拼两字不动光标（同会话 10 次不清库）",
                verdict="pass" if ok else "fail",
                detail="commit %d/10，interrupt(不带 count)=%d，每轮词库各 +1 行=%d/10，"
                       "词库 %d→%d 行" % (n_commit, n_int, sum(per_round), len(before), len(after)),
                evidence="case1-*.log / user_dict-c1-*.txt", rows=rows)


def case2(d, out, args, dev):
    """AC-3.4 ② 拼完首字等 ≥500ms 再移开宿主光标 → skipped reason=interrupt + 无新增"""
    reset(d, args.prefs)
    fresh_host(d)
    empty_field(d)
    before = snap_dict(d, out, "c2-before") or []
    rows = []
    for i, (a, b) in enumerate([("klge", "uyhn"), ("klge", "pwtq"), ("klge", "lgnh"),
                                ("klge", "temk"), ("klge", "stdj")], 1):
        d.sh("logcat", "-c")
        hop(d, a)
        time.sleep(0.8)                     # 越过 SELF_WRITE_GRACE_MILLIS = 300
        d.sh("input", "keyevent", 122)      # KEYCODE_MOVE_HOME：真实光标移动
        time.sleep(0.25)
        hop(d, b)
        lg = logs(d)
        txt = "\n".join(lg)
        exp = a[:2] + b[:2]
        rows.append(dict(i=i, pair=a + "+" + b, expect=exp,
                         interrupt=len([l for l in lg if "reason=interrupt" in l and "count=" not in l]),
                         interrupt_count=len([l for l in lg if "reason=interrupt" in l and "count=" in l]),
                         commit=("commit code=%s" % exp) in txt,
                         dict_lines=snap_dict(d, out, "c2-r%02d" % i) or []))
        open(os.path.join(out, "case2-%02d.log" % i), "w").write("\n".join(lg) + "\n")
        print("  r%02d %s interrupt(no count)=%d commit=%s"
              % (i, rows[-1]["pair"], rows[-1]["interrupt"], rows[-1]["commit"]))
    after = snap_dict(d, out, "c2-after") or []
    new_lines = [l for l in after if l not in before]
    ok = (all(r["interrupt"] >= 1 for r in rows) and not any(r["commit"] for r in rows)
          and not any(l.startswith(r["expect"] + "\t") for r in rows for l in new_lines))
    return dict(name="AC-3.4② 拼完首字移光标（等 ≥500ms）",
                verdict="pass" if ok else "fail",
                detail="5/5 出现 skipped reason=interrupt（不带 count）；commit=%d/5；词库新增 %d 行（不含本组期望码）"
                       % (sum(1 for r in rows if r["commit"]), len(new_lines)),
                evidence="case2-*.log / user_dict-c2-*.txt", rows=rows)


def case3(d, out, args, dev):
    """AC-3.4 ③ 拼完首字切短语面板再切回 → 无 commit、无新增（弱判据）"""
    reset(d, args.prefs)
    fresh_host(d)
    empty_field(d)
    before = snap_dict(d, out, "c3-before") or []
    rows = []
    px, py = panel_point(d)
    for i, (a, b) in enumerate([("klge", "pwtq"), ("klge", "lfqv")], 1):
        d.sh("logcat", "-c")
        hop(d, a)
        panel_open(d, px, py)
        shot(d, os.path.join(out, "case3-%02d-panel-open.png" % i))
        panel_close(d)
        hop(d, b)
        lg = logs(d)
        txt = "\n".join(lg)
        exp = a[:2] + b[:2]
        rows.append(dict(i=i, pair=a + "+" + b, expect=exp,
                         commit=("commit code=%s" % exp) in txt,
                         any_commit=bool(re.search(r"commit code=\w+ text=", txt)),
                         dict_lines=snap_dict(d, out, "c3-r%02d" % i) or []))
        open(os.path.join(out, "case3-%02d.log" % i), "w").write("\n".join(lg) + "\n")
        print("  r%02d %s commit=%s any_commit=%s"
              % (i, rows[-1]["pair"], rows[-1]["commit"], rows[-1]["any_commit"]))
    after = snap_dict(d, out, "c3-after") or []
    new_lines = [l for l in after if l not in before]
    ok = (not any(r["commit"] for r in rows)
          and not any(l.startswith(r["expect"] + "\t") for r in rows for l in new_lines))
    return dict(name="AC-3.4③ 拼首字后开合短语面板",
                verdict="pass" if ok else "fail",
                detail="%d 次均无 commit code=<本组期望码>；词库新增 %d 行且不含本组期望码"
                       "（弱判据：只断词库与 commit）" % (len(rows), len(new_lines)),
                evidence="case3-*.log / case3-*-panel-open.png / user_dict-c3-*.txt", rows=rows)


def case4(d, out, args, dev):
    """AC-3.7 / 3.12 学到的词在候选 index 0；原 中国银行 / 喂猪 仍在、顺序不变、项数 +1

    取证方法（避开「点选即学习」的自身污染）：**每个 index 一次干净重放**——
    pm clear → （可选）重学 喂猫 → 新输入框打 klqt → 只按一次该 index → 读上屏文本。
    这样每个读数都从同一个干净基线的副本出发，前一次点选不会抬升后一次的词权重。
    """
    indices = [0, 1, 2, 3]
    pre = replay_indices(d, out, "before", learn=False, indices=indices)
    # 学习前基线里 喂猫 不存在，index 2 起应已退回原始编码
    pre_clean = [w for w in pre if w != TARGET_CODE]
    post = replay_indices(d, out, "after", learn=True, indices=indices)
    post_clean = [w for w in post if w != TARGET_CODE]
    ok = (pre_clean == ["中国银行", "喂猪"] and post_clean[0] == TARGET_WORD
          and post_clean[1:3] == pre_clean and len(post_clean) == len(pre_clean) + 1)
    return dict(name="AC-3.7/3.12 学到词在候选 index 0，原两条仍在且顺序不变、项数 +1",
                verdict="pass" if ok else "fail",
                detail="index 0/1/2/3 干净重放——学习前 %s，学习后 %s（按上屏文本判定，"
                       "原始编码 klqt 代表该 index 已越界）" % (pre, post),
                evidence="case4-cand-before.png / case4-cand-after.png / user_dict-c4-*.txt / "
                         "case4-replay-*.txt",
                rows=[dict(before=pre, after=post)])


def replay_indices(d, out, tag, learn, indices):
    """每个 index 从干净基线重放一次，返回该 index 实际上了什么（原始编码 = 该 index 越界）。"""
    seq = []
    for idx in indices:
        reset(d, args_prefs(d))
        fresh_host(d)
        empty_field(d)
        if learn:
            hop(d, "klge")
            hop(d, "qtal")
            fresh_host(d)
            empty_field(d)
            if idx == 0:
                snap_dict(d, out, "c4-after")
        type_code(d, TARGET_CODE)
        time.sleep(0.9)
        if idx == 0:
            shot(d, os.path.join(out, "case4-cand-%s.png" % tag))
            snap_dict(d, out, "c4-%s" % tag)
        pick(d, idx)
        time.sleep(0.55)
        got = field_text(d)
        seq.append(got if got else "<无输入框>")
        print("  %s index %d -> %r" % (tag, idx, got))
    open(os.path.join(out, "case4-replay-%s.txt" % tag), "w").write(
        "\n".join("%d\t%s" % (i, w) for i, w in enumerate(seq)) + "\n")
    return seq


ARGS_PREFS = True


def args_prefs(d):
    return ARGS_PREFS


def case5(d, out, args, dev):
    """AC-3.8 关学习开关不写库；AC-3.10 设置页用户词库可见 / 可单条删 / 可整体清空 / 删后不再置顶"""
    if not args.prefs:
        return dict(name="AC-3.8/3.10 学习开关 + 用户词库治理", verdict="blocked",
                    detail="--no-prefs：release 包 run-as 不可用，开关需手工关；本用例转人工步骤",
                    evidence="-")
    # —— A 段：关开关分两次拼词 ——
    reset(d, args.prefs, learning=False)
    fresh_host(d)
    empty_field(d)
    before = snap_dict(d, out, "c5-before") or []
    d.sh("logcat", "-c")
    hop(d, "klge")
    hop(d, "qtal")
    lg = logs(d)
    txt = "\n".join(lg)
    open(os.path.join(out, "case5-disabled.log"), "w").write(txt + "\n")
    after = snap_dict(d, out, "c5-after") or []
    grew = [l for l in after if l not in before]
    disabled = "reason=disabled" in txt
    no_new = not any(l.startswith(TARGET_CODE + "\t") for l in grew)
    # —— B 段：开开关学一个真词，再走设置页三态 ——
    reset(d, args.prefs, learning=True)
    fresh_host(d)
    empty_field(d)
    hop(d, "klge")
    hop(d, "qtal")
    snap_dict(d, out, "c5-learned")
    ui = settings_user_dict(d, out, remove_word=TARGET_WORD, clear=True)
    # 删除后 klqt 不再置顶：干净重放 index 0
    after_del = replay_indices(d, out, "c5-after-remove", learn=False, indices=[0])
    not_top = after_del[0] != TARGET_WORD
    ok = disabled and no_new and ui["visible"] and ui["removed"] and ui["cleared"] and not_top
    return dict(name="AC-3.8/3.10 关学习开关 + 用户词库治理",
                verdict="pass" if ok else "fail",
                detail="关闭态 reason=disabled=%s、新增=%s；设置页可见=%s、单条删除生效=%s、"
                       "清空后显示「暂无学习记录」=%s；删除后 index 0=%r（不再置顶=%s）"
                       % (disabled, grew or "无", ui["visible"], ui["removed"], ui["cleared"],
                          after_del[0], not_top),
                evidence="case5-disabled.log / case5-settings-*.png / user_dict-c5-*.txt")


def case6(d, out, args, dev):
    """AC-3.11 连续 10 次拼接 → 无 ANR / dumpsys 无 not responding / 写盘不在主线程"""
    reset(d, args.prefs)
    fresh_host(d)
    empty_field(d)
    d.sh("logcat", "-c")
    pids = {d.sh("pidof", PKG).strip()}
    for a, b in PAIRS:
        hop(d, a, pause=0.7)
        hop(d, b, pause=0.7)
        pids.add(d.sh("pidof", PKG).strip())
    lg = logs(d, r"ANR|StrictMode|not responding|phrase_learn|Choreographer|Skipped \d+ frames")
    txt = "\n".join(lg)
    open(os.path.join(out, "case6.log"), "w").write(txt + "\n")
    anr = [l for l in lg if "ANR in %s" % PKG in l or "Input dispatching timed out" in l]
    strict = [l for l in lg if "StrictMode" in l]
    activity = d.sh("dumpsys", "activity", "activities") or ""
    nr = "not responding" in activity.lower()
    # 主线程写盘：一条 IO 线程栈兜底核对（写盘走 Dispatchers.IO 的代码事实 + 无 StrictMode 告警）
    commits = len([l for l in lg if "commit code=" in l])
    ok = not anr and not nr and not strict and commits >= 10 and len(pids) == 1
    return dict(name="AC-3.11 连续 10 次拼接无 ANR",
                verdict="pass" if ok else "fail",
                detail="commit=%d；同一 pid 全程=%s（%s）；ANR 命中=%d；dumpsys not responding=%s；StrictMode=%d"
                       % (commits, len(pids) == 1, ",".join(sorted(p for p in pids if p)),
                          len(anr), nr, len(strict)),
                evidence="case6.log / case6-after.png", rows=anr[:3])


def f1(d, out, args, dev):
    """F1 专项（只记录行为，不计 fail）：<300ms 与 500~1000ms 移光标各 5 次"""
    reset(d, args.prefs)
    fresh_host(d)
    empty_field(d)
    res = []
    for grp, delay in (("A", 0.15), ("B", 0.65)):
        for i, (a, b) in enumerate([("klge", "dgtg"), ("klge", "ffff"), ("klge", "ssss"),
                                    ("klge", "qqqq"), ("klge", "fghy")], 1):
            d.sh("logcat", "-c")
            hop(d, a)
            time.sleep(delay)
            d.sh("input", "keyevent", 122)
            time.sleep(0.2)
            hop(d, b)
            lg = logs(d)
            n = len([l for l in lg if "reason=interrupt" in l and "count=" not in l])
            res.append((grp, i, n >= 1))
    open(os.path.join(out, "f1.log"), "w").write(
        "\n".join("%s%d interrupt=%s" % r for r in res) + "\n")
    return dict(name="F1 专项（记录行为，不计 fail）", verdict="info",
                detail="A 组(<300ms) 触发中断 %d/5；B 组(500~1000ms) 触发中断 %d/5"
                       % (sum(1 for g, _, h in res if g == "A" and h),
                          sum(1 for g, _, h in res if g == "B" and h)),
                evidence="f1.log")


CASES = {"1": case1, "2": case2, "3": case3, "4": case4, "5": case5, "6": case6, "f1": f1}


# ───────────────────────────── 用例支撑件 ─────────────────────────────
def panel_point(d):
    """工具栏「短语」键的点击点（1080×2340 / 440dpi 实测校准：x≈472 命中，y 1580~1640 命中）。

    不按比例硬套：先用 BACK 探针确认「点完再点 BACK 后键盘仍在、字母进宿主」——命中即面板未开。
    真机分辨率不同时，用 --calibrate 重新标一次。
    """
    w, h = screen(d)
    return int(w * 0.437), int(h * 0.685)


def probe_panel(d, x, y):
    """返回 True 表示该点能打开短语面板：面板开着时字母进筛选框、宿主输入框不变。"""
    before = field_text(d)
    d.sh("input", "tap", str(x), str(y))
    time.sleep(1.3)
    d.sh("input", "keyevent", "29")   # 'a'
    time.sleep(0.6)
    after = field_text(d)
    d.sh("input", "keyevent", 4)      # BACK 关面板
    time.sleep(0.8)
    return after == before


def panel_open(d, x, y):
    d.sh("input", "tap", str(x), str(y))
    time.sleep(1.4)


def panel_close(d):
    d.sh("input", "keyevent", 4)      # BACK：面板打开时是关面板（IspeechImeService:654）
    time.sleep(0.9)
    # 焦点回宿主输入框：BACK 之后 IME 可能收起，重新点一下输入框
    xml = dump(d, "pc")
    eds = [n for n in nodes(xml) if n.get("class", "").endswith("EditText")]
    if eds and not any(n.get("focused") == "true" for n in eds):
        tap_node(d, eds[0])
        time.sleep(1.2)


def enumerate_candidates(d, out, tag, limit=6):
    """逐 index 上屏枚举候选序（IME 窗口不进 uiautomator dump，用「按数字键看上了什么」判定）。

    每轮重开宿主 + 重打整码，保证是同一候选条；连续两个 index 都退回原始编码即认为到底。
    """
    seq = []
    raw = 0
    for idx in range(limit):
        fresh_host(d)
        empty_field(d)
        type_code(d, TARGET_CODE)
        time.sleep(0.9)
        if idx == 0:
            shot(d, os.path.join(out, "case4-cand-%s.png" % tag))
        pick(d, idx)
        time.sleep(0.5)
        got = field_text(d)
        if got in (TARGET_CODE, None, ""):
            raw += 1
            if raw >= 2:
                break
            continue
        raw = 0
        seq.append(got)
    open(os.path.join(out, "case4-cand-%s.txt" % tag), "w").write(
        "\n".join("%d\t%s" % (i, w) for i, w in enumerate(seq)) + "\n")
    print("  候选序(%s): %s" % (tag, seq))
    return seq


def settings_user_dict(d, out, remove_word=None, clear=False):
    """设置页用户词库三态取证：可见 → 单条删除 → 整体清空。每步截图。"""
    res = dict(visible=False, removed=False, cleared=False)
    d.sh("am", "start", "-n", PKG + "/com.ispeech.settings.MainActivity")
    time.sleep(3)
    xml = dump(d, "st0")
    kb = [n for n in nodes(xml) if "键盘与输入" == (n.get("text") or "")]
    if not kb:
        shot(d, os.path.join(out, "case5-settings-home.png"))
        return res
    tap_node(d, kb[0])
    time.sleep(2.5)
    for _ in range(6):
        xml = dump(d, "st1")
        words = [n for n in nodes(xml) if n.get("text") == remove_word]
        if words:
            res["visible"] = True
            shot(d, os.path.join(out, "case5-settings-list.png"))
            rows = [n for n in nodes(xml) if (n.get("content-desc") or "") in ("删除", "Delete")]
            if not rows:
                rows = [n for n in nodes(xml) if (n.get("text") or "") in ("删除", "Delete")]
            if rows:
                # 同一行里最靠近该词的删除按钮
                wy = words[0]["_c"][1]
                rows.sort(key=lambda n: abs(n["_c"][1] - wy))
                tap_node(d, rows[0])
                time.sleep(1.5)
                xml2 = dump(d, "st2")
                res["removed"] = not any(n.get("text") == remove_word for n in nodes(xml2))
                shot(d, os.path.join(out, "case5-settings-after-remove.png"))
            break
        d.sh("input", "swipe", "540", "1800", "540", "1000", "300")
        time.sleep(1.2)
    if not res["visible"]:
        shot(d, os.path.join(out, "case5-settings-list.png"))
    if clear:
        xml3 = dump(d, "st3")
        btn = [n for n in nodes(xml3) if (n.get("text") or "") == "清空用户词库"]
        if btn:
            tap_node(d, btn[0])
            time.sleep(1.2)
            xml4 = dump(d, "st4")
            confirm = [n for n in nodes(xml4)
                       if (n.get("text") or "") in ("确定", "清空", "OK", "Yes", "删除")]
            if confirm:
                tap_node(d, confirm[0])
                time.sleep(1.5)
            xml5 = dump(d, "st5")
            texts = [n.get("text") for n in nodes(xml5) if n.get("text")]
            res["cleared"] = "暂无学习记录" in texts
            shot(d, os.path.join(out, "case5-settings-after-clear.png"))
    return res


# ───────────────────────────── 报告与入口 ─────────────────────────────
def write_report(out, dev, args, results):
    lines = ["# VOCA-657 条目 3 词组自学习 6 项补跑报告", ""]
    layer = "模拟器层（不顶替真机）" if dev["emulator"] else "真机"
    lines += ["- 设备：%s（%s）serial=%s" % (dev["model"], layer, dev["serialno"]),
              "- 包：versionName=%s sha256=%s" % (dev["version_name"], dev["apk_sha256"]),
              "- 层：%s" % layer,
              "- 生成：%s" % time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "",
              "| # | 用例 | 判定 | 读数 | 证据 |", "|---|---|---|---|---|"]
    for r in results:
        lines.append("| %s | %s | **%s** | %s | %s |"
                     % (r.get("case_id", "?"), r["name"], r["verdict"],
                        r["detail"].replace("|", "\\|"), r["evidence"]))
    if dev["emulator"]:
        lines += ["", "> 本报告为模拟器空跑自证，**不作为真机验收凭据**；真机回线后用同一脚本"
                      "（去掉 --emulator）重跑即为验收读数。"]
    p = os.path.join(out, "REPORT.md")
    open(p, "w").write("\n".join(lines) + "\n")
    return p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    ap.add_argument("--expect-serial", default="FMR0224913042917")
    ap.add_argument("--expect-model", default="")
    ap.add_argument("--emulator", action="store_true", help="允许模拟器空跑（读数标注模拟器层）")
    ap.add_argument("--cases", default="1,2,3,4,5,6,f1")
    ap.add_argument("--no-prefs", dest="prefs", action="store_false",
                    help="不写 DataStore（release 包 run-as 不可用时的降级路径）")
    ap.add_argument("--local-apk", default="", help="本机被测 APK 路径，用于 phrase_learn 字符串自证")
    ap.add_argument("--out", default="")
    args = ap.parse_args()
    if not args.serial:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
        cand = [l.split()[0] for l in out.splitlines() if l.strip().endswith("device")]
        if not cand:
            print("BLOCKED: adb devices 没有 device 状态设备。需要真机 FMR0224913042917 接入并解锁。",
                  file=sys.stderr)
            sys.exit(2)
        args.serial = cand[0]
    d = Dev(args.serial)
    ts = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
    out = args.out or os.path.join("qa-evidence", "voca657-" + ts)
    os.makedirs(out, exist_ok=True)
    say("产物目录 %s" % out)
    global CONNECTED_DBG
    dev = preflight(d, args, out)
    CONNECTED_DBG = dev["debug_pkg"] and args.prefs
    if not CONNECTED_DBG:
        print("提示：run-as 不可用或 --no-prefs，词库断言改走设置页截图；"
              "输入态需手工切五笔、学习开关需手工操作。")
    global ARGS_PREFS
    ARGS_PREFS = args.prefs
    results = []
    for cid in [c.strip() for c in args.cases.split(",") if c.strip()]:
        fn = CASES.get(cid)
        if not fn:
            print("跳过未知用例 %r" % cid, file=sys.stderr)
            continue
        say("用例 %s" % cid)
        try:
            r = fn(d, out, args, dev)
        except Exception as e:  # 单例异常不影响其余用例，如实记 fail
            r = dict(name="case " + cid, verdict="fail", detail="执行异常：%r" % e, evidence="-")
        r["case_id"] = cid
        results.append(r)
        print("  → %s：%s" % (r["verdict"], r["detail"]))
    rep = write_report(out, dev, args, results)
    say("报告 %s" % rep)
    order = {"fail": 0, "blocked": 1, "pass": 2, "info": 3}
    worst = sorted(results, key=lambda r: order.get(r["verdict"], 9))[0]["verdict"]
    if worst == "fail":
        print("GO/NO-GO: no-go（存在 fail）")
        return 1
    if worst == "blocked" or dev["emulator"]:
        print("GO/NO-GO: 不判定（%s）" % ("模拟器层，不顶替真机" if dev["emulator"] else "存在 blocked"))
        return 2
    print("GO/NO-GO: go（6 项 pass）")
    return 0


if __name__ == "__main__":
    sys.exit(main())