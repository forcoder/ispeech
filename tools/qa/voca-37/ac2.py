#!/usr/bin/env python3
"""VOCA-37 AC-2 双击空格句号：把「开关态」与「键路径」两个维度拆开取证。

用法：python3 ac2.py <c524351 的 app-debug.apk> [440] [keyevent|soft]

为什么要重跑：上一轮只在软键路径拿到正例（ev_ac23c host='。'），硬件/注入键 3 组全是阴性
（ev_ac23 的 AC2_fast2x / AC2_gap250ms / AC2_triple），但那几行没记 double_space_period 的
实际开关态——而它默认 false（KeyboardSettings.kt:29）。开关态没标注的阴性结果不能算测过。

两条键路径**不**落到同一个 handleSpace()：
  soft     → 空格键坐标点击：SoftKeyboardView KeyAction.SPACE → handleSpace()（IspeechImeService.kt:458）
  keyevent → `input keyevent 62`：硬件/蓝牙/注入键，IspeechImeService.kt:591-597 先判
             `buffer.isEmpty() && !aiReplyActive && !associationActive` 就 `return false`，
             空缓冲时根本走不到 handleSpace() —— 本轮要证的就是这条差异。

注入键的坑：两次独立的 `adb shell input keyevent` 之间隔着一次 adb 往返，慢的时候能超出
300ms 判定窗口 —— 所以每组都打 wall_between_ms，另加单进程多键码（`input keyevent 62 62`，
两次注入几乎 0 间隔）作为对照组，把「路径不通」和「工装太慢」分开。
"""
import os, re, subprocess, sys, time

KEYS = {"ok": False}

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
import fs115 as F  # 复用 dex 身份校验 + IME 身份判定；它的 __main__ 不会在 import 时执行

APK = sys.argv[1]


def ours(ime):
    return str(ime).startswith("com.ispeech.ime/")


def ident(tag):
    ok = F.ident_once(APK)
    D.log("IDENT", end=tag, match=ok, ime=D.shell("settings get secure default_input_method").strip())
    return ok
PATH_KIND = sys.argv[3] if len(sys.argv) > 3 else "keyevent"
SPACE_XY = None             # 空格键中心：由 key_map() 当场反推（写死 532,2125 在 VOCA-184
                            # 收紧行内留白后就不成立了，软键路径会点到别处）
MODE_XY = (165, 2125)       # 底行「拼/英」切换键中心（kb2_grid.png 实测）
# 几何一律当场读（第四轮写死 1352 是 c524351 单包的 ime top；VOCA-184 把字母键留白从
# 4dp 收到 1dp 之后集成候选是 1388，写死会把「键盘在位」误判成「宿主没起来」）
GEAR_XY = (1005, 1552)      # 工具栏最后一键 ⚙（kb2_grid.png 实测；点它开输入法内配置面板）
PANEL_SCROLL = "input swipe 500 1900 500 1450 400"   # 面板列表滚到底
DS_SWITCH_XY = (955, 1855)  # 滚到底后「双击空格句号」那一行的 Switch 中心（panel2_grid.png 实测）
KEY = b"double_space_period"
DS_CANDIDATES = ("files/datastore/ispeech_settings.preferences_pb",
                 "datastore/ispeech_settings.preferences_pb")


def ds_blob():
    for p in DS_CANDIDATES:
        r = subprocess.run([D.ADB, "-s", D.S, "shell", "run-as com.ispeech.ime cat " + p],
                           capture_output=True)
        if r.stdout:
            return r.stdout, p
    return b"", "-"


def ds_snapshot():
    """把整份 DataStore 里已持久化的键都解出来 —— 面板是「滚动 + 坐标点击」驱动的，
    点偏了就会误伤相邻行（本轮就误开过 fuzzy_pinyin、把中文输入法点到 WUBI）。
    开关置位前后各打一行，误伤在同一份 stdout 里可见，不用事后猜。"""
    blob, used = ds_blob()
    out = {}
    for m in re.finditer(rb"([a-z_]{4,})\x12", blob):
        name = m.group(1).decode()
        tail = blob[m.end(): m.end() + 12]
        j = tail.find(b"\x08")
        if j >= 0 and len(tail) > j + 1 and tail[j + 1] in (0x00, 0x01):
            out[name] = bool(tail[j + 1])
            continue
        j = tail.find(b"\x2a")
        if j >= 0 and len(tail) > j + 1:
            n = tail[j + 1]
            out[name] = tail[j + 2: j + 2 + n].decode("utf8", "replace")
    return dict(file=used, bytes=len(blob), **out)


def switch_state():
    """读设备上 DataStore 的原始字节判开关态。文件/键不存在 = 从未写过 = 走代码默认 false。

    路径坑：DataStore 落在 `files/datastore/`（不是 `datastore/`），写错路径会把「已开」读成「默认关」。
    """
    blob, used = ds_blob()
    if not blob:
        return dict(state=False, src="datastore_file_absent", raw_hex="", file="-")
    i = blob.find(KEY)
    if i < 0:
        return dict(state=False, src="key_absent", raw_hex="", file=used)
    tail = blob[i + len(KEY): i + len(KEY) + 8]
    state = None
    for j in range(len(tail) - 1):
        if tail[j] == 0x08 and tail[j + 1] in (0x00, 0x01):   # ValueType.bool_value
            state = bool(tail[j + 1])
            break
    return dict(state=state, src="datastore", raw_hex=tail.hex(), file=used)


PANEL_ROW_Y = (1652, 1855, 2052, 1452, 1552, 1752, 1952)   # 面板可见行中心（100px 一档，实测）
SWITCH_X = 955


def _cycle_back(y, want_key, want_val, tries=6):
    """误伤到相邻行（选项行是「点一下换下一个值」）时，原地反复点回原值。"""
    for _ in range(tries):
        if ds_snapshot().get(want_key) == want_val:
            return True
        D.shell("input tap %d %d" % (SWITCH_X, y))
        time.sleep(0.8)
    return ds_snapshot().get(want_key) == want_val


def set_switch(on):
    """走输入法内配置面板（齿轮键）置开关，返回置完后的实测态（以 DataStore 为准，不看 UI 颜色）。

    不走 App 设置页：MainActivity → 键盘与输入 会崩（KeyboardViewModel.kt:35 在 init 里
    collect 一个声明在 init 之后的 MutableStateFlow → NPE，进程整个死掉）。

    行位置不能写死：面板列表滚到底后的内容跟数据态有关（清过数据后多了「识别引擎/识别语言」两行，
    「双击空格句号」从 y=1855 挪到 y=1652）。所以滚到底后逐行试点，**以 DataStore 的键差异判定点中了谁**：
    只有 double_space_period 变了才算成功；点歪了就把那一行点回原值再继续，全过程进 stdout。
    """
    cur = switch_state()
    if cur["state"] == on:
        cur["route"] = "already"
        return cur
    D.shell("input tap %d %d" % GEAR_XY)          # 齿轮 → 面板（IspeechImeService.kt:2071 openSettings）
    time.sleep(1.2)
    for _ in range(3):                             # 滚到底（多次无副作用）
        D.shell(PANEL_SCROLL)
        time.sleep(0.7)
    for y in PANEL_ROW_Y:
        before = ds_snapshot()
        D.shell("input tap %d %d" % (SWITCH_X, y))
        time.sleep(0.9)
        after = ds_snapshot()
        # 只比真实键：bytes 是文件长度，值翻转时它未必变，把它当差异会把命中的那一行误判成误伤
        keys = (set(before) | set(after)) - {"bytes", "file"}
        diff = {k: after.get(k) for k in keys if before.get(k) != after.get(k)}
        if list(diff) == ["double_space_period"]:
            D.log("TOUCH", y=y, diff=diff, verdict="hit_target")
            D.shell("input keyevent 4")            # BACK → closePanel()（IspeechImeService.kt:545-548）
            time.sleep(0.8)
            out = switch_state()
            out["route"] = "ime_panel@%d" % y
            return out
        if not diff:
            D.log("TOUCH", y=y, diff={}, verdict="no_change")
            continue
        # 点歪了：先还原误伤的那一行，再试下一行
        bad = [k for k in diff if k != "double_space_period"]
        restored = all(_cycle_back(y, k, before.get(k)) for k in bad)
        D.log("TOUCH", y=y, diff=diff, verdict="collateral", restored=restored)
    D.shell("input keyevent 4")
    time.sleep(0.8)
    out = switch_state()
    out["route"] = "ime_panel_failed"
    return out


def ensure_chinese_mode():
    """句号转换要求非英文态（SpaceKeyPolicy.decide 的 englishMode 分支），先确认再测。

    不能用「宿主文本是不是字母」判：拼音态下组合中的 zhongguo 同样以 composing 文本出现在宿主
    （cand_crop.png：候选条 zhongguo | 中国① 中国中铁② 中国之声③）。改成行为判据 ——
    空格里若缓冲有内容，链上 COMMIT_TOP 会先上屏首选（IspeechImeService.kt:591-597 → handleSpace），
    宿主出现汉字就是中文态，仍是字母+空格就是英文态。
    """
    for attempt in range(3):
        clear_host()
        D.type_str("zhongguo", gap=0.1)
        time.sleep(1.0)
        D.shell("input keyevent 62")
        time.sleep(0.9)
        host = D.focused_text()
        if re.search(r"[一-鿿]", host or ""):
            D.log("MODE", path=PATH_KIND, mode="chinese", host=repr(host)[:40], attempt=attempt + 1)
            clear_host()
            return True
        D.log("MODE", path=PATH_KIND, mode="english", host=repr(host)[:40], attempt=attempt + 1,
              action="tap_pinyin_toggle")
        D.shell("input tap %d %d" % MODE_XY)       # 底行「拼/英」
        time.sleep(1.0)
    D.log("MODE", path=PATH_KIND, mode="unresolved", host=repr(host)[:40])
    return False


def host_ready():
    return D.host_ready(rounds=3)


def clear_host(n=25):
    D.clear(n)
    time.sleep(0.5)


def group(case, times, gap=0.0, single=False):
    """single=True 且 keyevent 路径：一次 adb 调用注入 times 个空格（两次注入几乎 0 间隔）。
    否则逐次调用/逐次软键点击，wall_between_ms = 相邻两次注入的墙钟间隔。"""
    if not ours(F.cur_ime()):
        D.log("SKIP", path=PATH_KIND, case=case, reason="ime_not_ours", current=F.cur_ime())
        return
    top, bot, h = D.ime_frame()
    if not h:
        st = host_ready()
        D.log("HOST", case=case, **{k: v for k, v in st.items()})
        top, bot, h = D.ime_frame()
    if SPACE_XY is None or not KEYS.get("ok"):
        # 键盘几何没标定成功 = 空格键坐标不成立，这组数据不能要
        D.log("SKIP", path=PATH_KIND, case=case, reason="key_map_not_calibrated", top=top, h=h)
        return
    clear_host()
    before = D.focused_text()
    ts = []
    if single and PATH_KIND == "keyevent":
        D.shell("input keyevent " + " ".join(["62"] * times))
        wall = "0(single_injection)"
    elif single and PATH_KIND == "soft":
        # 软键的「几乎 0 间隔」对照：同一次 adb shell 里串 times 条 tap
        D.shell(" && ".join(["input tap %d %d" % SPACE_XY] * times))
        wall = "0(single_shell)"
    else:
        for i in range(times):
            if i and gap:
                time.sleep(gap)
            D.shell("input keyevent 62") if PATH_KIND == "keyevent" else D.shell("input tap %d %d" % SPACE_XY)
            ts.append(time.time())
        wall = round((ts[-1] - ts[0]) * 1000, 1)
    time.sleep(0.9)
    host = D.focused_text()
    sw = switch_state()
    D.log("AC2", path=PATH_KIND, case=case, times=times,
          wall_between_ms=wall, before=repr(before), host=repr(host),
          switch_state=sw["state"], switch_src=sw["src"])


if __name__ == "__main__":
    r = subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-d", "-g", APK], capture_output=True, text=True, timeout=900)
    D.log("INSTALL", out=r.stdout.strip()[-60:], rc=r.returncode)
    D.log("PKG", path_kind=PATH_KIND, pkg_ime=D.shell("settings get secure default_input_method").strip())
    if not ident("start"):
        sys.exit(2)
    st = host_ready()
    D.log("HOST", **{k: v for k, v in st.items()})
    if not st.get("ok"):
        D.log("SKIP", path=PATH_KIND, reason="host_not_ready")
        sys.exit(3)
    top, bot, h = D.ime_frame()
    m, diag = D.key_map()
    KEYS["ok"] = bool(diag.get("ok"))
    if KEYS["ok"]:
        SPACE_XY = m[" "]
    D.log("FRAME", top=top, h=h, keymap_ok=KEYS["ok"], runs=diag.get("runs"),
          space_xy=SPACE_XY, gear_xy=GEAR_XY)
    if not KEYS["ok"]:
        D.log("SKIP", path=PATH_KIND, reason="key_map_fail", diag=str(diag)[:120])
        sys.exit(3)
    if not ensure_chinese_mode():
        D.log("SKIP", path=PATH_KIND, reason="not_chinese_mode")
        sys.exit(4)

    for on in (True, False):
        D.log("DS_BEFORE", **{k: v for k, v in ds_snapshot().items()})
        s = set_switch(on)
        D.log("DS_AFTER", **{k: v for k, v in ds_snapshot().items()})
        D.log("SWITCH", want=("ON" if on else "OFF"), state=s["state"], src=s["src"],
              raw_hex=s["raw_hex"], file=s["file"], route=s["route"],
              default_when_absent="false", key="double_space_period")
        if s["state"] != on:
            D.log("SKIP", path=PATH_KIND, reason="switch_not_set", want=on, got=s)
            continue
        # 面板开合会走 closePanel/openPanel → lastSpaceCommitAt 清零，不影响下一组；宿主仍在位
        if not host_ready().get("ok"):
            D.log("SKIP", path=PATH_KIND, reason="host_lost_after_switch", want=on)
            continue
        if on:
            group("2x_single_injection", 2, single=True)
            group("2x_two_adb_calls", 2)
            group("2x_gap500ms_control", 2, gap=0.5)
            group("3x_single_injection", 3, single=True)
        else:
            group("2x_single_injection", 2, single=True)
    D.log("FINAL_SWITCH", **{k: v for k, v in switch_state().items()})
    ident("end")
