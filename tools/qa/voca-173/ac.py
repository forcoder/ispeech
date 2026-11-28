#!/usr/bin/env python3
"""VOCA-173 AC-1 / AC-2 复跑（合并版：origin/main + PR #48 的 patch，versionCode 7）。

AC-1：zhidao → 点首选「指导老师」→ 点联想条首项 → 期望「指导老师专」（不是「指导老师师专」）
AC-2：zhi → 点首选「之」→ 点联想条第 2 项 → 期望「之一」（不是「之之」）

判定只读宿主 focused EditText 的文本；联想条是否出现读 logcat 的 associate hit/miss。
用法：python3 ac.py <tag>
"""
import os
import sys
import time

Q = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, Q)
import ab
import run as R


def tap_item(idx, tag):
    """点联想条第 idx 项（0 基）：候选条自绘，坐标取自 band 量到的项徽标区间。"""
    st = R.band_struct("_assoc_%s_%d" % (tag, idx))
    items = st.get("items") or []
    if len(items) <= idx:
        return None, st
    it = items[idx]
    x = (it["item_left"] + it["item_right"]) // 2
    y = st["ime_top"] + st["band_px"] // 2
    R.shell("input tap %d %d" % (x, y))
    time.sleep(1.6)
    return (x, y), st


def clear_until_empty(max_rounds=8):
    """宿主框清空到只剩占位符：上一轮上屏的联想长词会残留，只按固定 40 次退格不够。"""
    for _ in range(max_rounds):
        t = R.host_text()
        if not t or t == "短信":
            return True
        R.clear(len(t) + 5)
        time.sleep(0.5)
    return (R.host_text() or "") in ("", "短信")


def run_case(name, code, idx, expect, banned, tag):
    print("### %s code=%s assoc_idx=%d expect=%r" % (name, code, idx, expect), flush=True)
    ok_host = ab.ensure_host()
    cleared = clear_until_empty()
    R.type_str(code)
    time.sleep(1.6)
    typed = R.host_text()
    ab.logcat_clear()
    picked, st = ab.pick_first()
    after_pick = R.host_text() or ""
    time.sleep(0.8)
    hits, miss = ab.logcat_assoc()
    print("  host_ready=%s typed=%r pick_first=%r after_pick=%r" % (ok_host, typed, picked, after_pick), flush=True)
    print("  logcat_assoc_hit=%d miss=%d" % (len(hits), len(miss)), flush=True)
    for ln in hits + miss:
        print("    " + ln.strip(), flush=True)
    coord, st2 = tap_item(idx, tag)
    after = R.host_text() or ""
    print("  tap_assoc=%r assoc_cand_count=%s after=%r" % (coord, st2.get("cand_count"), after), flush=True)
    delta = after[len(after_pick):] if after.startswith(after_pick) else after
    verdict = "PASS" if (expect in after and banned not in after) else "FAIL"
    print("  delta=%r expect=%r banned=%r" % (delta, expect, banned), flush=True)
    print("  VERDICT %s %s" % (name, verdict), flush=True)
    R.shot("voca173_merge_%s.png" % name.lower().replace("-", ""))
    return dict(name=name, after=after, delta=delta, verdict=verdict,
                hits=len(hits), assoc=st2.get("cand_count"))


if __name__ == "__main__":
    tag = sys.argv[1] if len(sys.argv) > 1 else "mergeC"
    print("=== TAG=%s ===" % tag, flush=True)
    print("version=%s" % R.shell("dumpsys package com.ispeech.ime | grep versionName | head -1").strip(), flush=True)
    run_case("AC-1", "zhidao", 0, "指导老师专", "指导老师师", tag)
    run_case("AC-2", "zhi", 1, "之一", "之之", tag)