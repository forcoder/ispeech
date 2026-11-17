#!/usr/bin/env python3
"""spec-AC-9 退格长按阶梯（VOCA-47 第五轮补：1500ms 实测档）。

旧证据（`ev_ac9.txt`）只有 200/1000/2000ms 三档，且是当场手敲命令跑出来的、没留脚本，
1.5s 的判定靠两点内插。裁决 §6-打回项 6：内插值必须标注「非实测」，并补一个 1500ms 实测档。
本脚本把阶梯落成可复跑的工具，并加两条闸门：
  * 退格键位从截图反推（`drive.key_map`），不写死像素；
  * 先做一次 200ms 短按，必须恰好删掉 1 个字——删不动说明点到的不是退格键，
    后面所有档位都是废数，直接 GATE 退出。
长度一律用 `hold()` 的**连读稳定值**：批量敲键后紧接着读一次 `uiautomator dump`，拿到的是
旧文本（第一次跑就是这么把 deleted=1 量成 deleted=0 的），读到连续两次一致才算数。
宿主文本一律用**已上屏的中文**（逐码 space 上屏首选），因为组合缓冲里的退格走的是
另一条路径（删 composing），不是规格 AC-9 说的「删除字符」。
用法：python3 ac9.py [target_len] [hold_ms,...]
"""
import os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D

TARGET = int(sys.argv[1]) if len(sys.argv) > 1 else 60
HOLDS = [int(x) for x in (sys.argv[2].split(",") if len(sys.argv) > 2 else ["200", "1000", "1500", "2000"])]
RC = dict(host_not_ready=18, ime_not_ours=17, key_map_fail=16, del_key_miss=20, fill_short=21)


def gate(reason, **kw):
    # 被调用方常把诊断 dict 整个 ** 进来，而它自己就带一个 reason 键（如 key_map 的
    # no_row_with_10_keys）—— 直接展开会撞成 TypeError，闸门反而报不出闸门。
    if "reason" in kw:
        kw["diag_reason"] = kw.pop("reason")
    D.log("GATE", reason=reason, rc=RC.get(reason, 1), **kw)
    raise SystemExit(RC.get(reason, 1))


def hold():
    """已上屏文本长度：连读到稳定再返回。刚敲完一批键时 uiautomator 的 dump 会滞后半秒，
    读到旧值会把「删掉了」量成「没删」——第一次跑 PROBE_HOLD deleted=0 就是这么来的。"""
    last, same = None, 0
    for _ in range(8):
        v = len(D.focused_text() or "")
        same = same + 1 if v == last else 0
        if same >= 2:
            return v
        last = v
        time.sleep(0.35)
    return last


def long_press(ms, m, want=1):
    """一次长按 + 稳定回读；短按档（want=1）没删动就补一次（偶发的 down/up 丢失）。"""
    before = hold()
    D.hold_key(ms, "del", m=m)
    after = hold()
    return before, after, before - after


def fill(n):
    """敲 n 个已上屏汉字：每轮 shi + 空格（组合非空时空格上屏首选）。"""
    for i in range(n):
        D.type_str("shi", gap=0.04)
        time.sleep(0.25)
        D.shell("input keyevent 62")
        time.sleep(0.2)
    return len(D.focused_text() or "")


if __name__ == "__main__":
    st = D.host_ready()
    if not st.get("ok"):
        gate("host_not_ready", **{k: str(v) for k, v in st.items() if k != "reason"},
             host_reason=st.get("reason"))
    ime = D.shell("settings get secure default_input_method").strip()
    if not ime.startswith("com.ispeech.ime/"):
        gate("ime_not_ours", current=ime)
    apk9 = os.environ.get("QA_APK")
    if apk9:
        import fs115 as F9
        D.log("IDENT", stage="start", apk=os.path.basename(apk9), match=F9.ident_once(apk9),
              head=os.environ.get("QA_HEAD", "78343a4"), spec=os.environ.get("QA_SPEC", "0e4eeb0"),
              ime=ime, ts=time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    m, diag = D.key_map()
    if not diag.get("ok"):
        gate("key_map_fail", **{k: str(v) for k, v in diag.items() if k != "reason"},
             map_reason=diag.get("reason"))
    top, bot, h = D.ime_frame()
    D.log("KEYMAP", bands=diag["bands"], runs=diag["runs"], del_xy=m["del"], space_xy=m[" "],
          top=top, h=h)

    D.clear(80)
    time.sleep(0.5)
    got = fill(TARGET)
    if got < TARGET - 2:
        gate("fill_short", want=TARGET, got=got)
    # 闸门：200ms 必须恰好删 1 字，否则点错键（允许一次补按，丢手势是模拟器的常见偶发）
    before, after, deleted = long_press(200, m)
    if deleted != 1:
        D.log("PROBE_RETRY", first_deleted=deleted)
        before, after, deleted = long_press(200, m)
    D.log("PROBE_HOLD", ms=200, before=before, after=after, deleted=deleted)
    if deleted != 1:
        gate("del_key_miss", deleted=deleted, del_xy=m["del"])

    for ms in HOLDS:
        if hold() < TARGET - 2:
            fill(TARGET - hold())
            D.log("REFILL", len=hold())
        t0 = time.time()
        before, after, deleted = long_press(ms, m)
        wall = time.time() - t0
        D.log("AC9", hold_ms=ms, before=before, after=after, deleted=deleted,
              wall_s=round(wall, 2), rate_ms=round(ms * 1.0 / deleted, 1) if deleted else None,
              spec_ac9_gt3="PASS" if (ms == 1500 and deleted > 3) else ("n/a" if ms != 1500 else "FAIL"))
    D.log("NOTE", text="1500ms 档为实测；旧 ev_ac9.txt 的 1.5s≈10 字是按 1000/2000 两点内插的非实测值")
