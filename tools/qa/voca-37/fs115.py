#!/usr/bin/env python3
"""VOCA-47 第五轮重测工装：AC-1 恒高 + 截字 + spec-AC-10「至少完整显示 4 个候选」。

为什么重写（裁决 docs/requirements/VOCA-47-acceptance-ruling.md §1.2 / §6）：
旧版把 `ink_extent` 打在整个候选带宽度上（x 16..1064），而 `CandidateView.kt:76-77`
在候选列表为空时把 `scrollView` 置 GONE —— 于是「敲了码但一个候选都没出」的帧也有墨迹
（那是左侧 chip），旧报告的「恒高 Δ / 截字」其实一直在量 chip。
加上旧 `drive.tap_keys` 写死 440dpi 像素又不回读，520dpi 下把 zhongguo 打成 hogguo / khk，
候选条从头到尾就是空的。

本版三条硬规矩：
1. **闸门先于读数，闸门只守规格 `0e4eeb0:543` AC-10 的那三条 Given**：回读宿主文本 == 所敲串、
   chip 像素盒存在且在候选区左边、候选行在位（不是 chip-only）、池内候选 ≥ 8（用 `=` 键滚动 +
   互相关累计位移去重数出来）。任一不成立 → 只打 `GATE reason= rc=` 并以非零码退出，
   stdout 不出现 RESULT / INK / delta。**「可见候选 ≥ 4」不是闸门**（第六轮解耦，裁决 §9.2）：
   它是 AC-10a 的判据，写在闸门里就等于让 AC-10 只会「不给数」而永远不会「出 FAIL」。
2. **INK 行带 `cand_count` 与采样起点 `x0`（= chip 右沿 + 1）**，字身墨迹只在 chip 右边量，
   角标圆片按外形剔除（见 `band.py`）。
3. 键位一律从当场截图反推（`drive.key_map`），不写死像素。
4. 闸门全过后先打一行 `GATES`，把规格 `0e4eeb0:543` AC-10 的三条 Given 逐条点名
   （① chip==所敲串、② 候选行在位（`cand_count ≥ 1`）、③ 池 ≥8），
   再打 `RESULT`。

用法：python3 fs115.py <apk> <density> <fs1,fs2> [code1,code2] [label]

`label`（默认 `a`）进截图文件名：第五轮 B 包（集成候选）复跑时没带 label，把 A 包同名候选带裁图覆盖了——
文本证据不受影响，但帧必须能对上包，所以加了这一位（`r5_<label>_<dens>_fs<fs>_<code>_<kind>.png`）。
退出码：0 全部通过 / 10 回读不符 / 11 连 chip 盒都没画出来（no_chip_box）/ 12 chip 不在候选区左
        / 13 有 chip 但候选 0 条 = chip-only（Given ② 不成立）
        / 14 池内候选不足 8 / 15 滚动后未回原点 / 16 键位反推失败 / 17 IME 不是本包 / 18 宿主没起来
"""
import hashlib, os, subprocess, sys, time, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import band
import drive as D

LABEL = sys.argv[5] if len(sys.argv) > 5 else "a"

MIN_CAND = 1          # 规格 0e4eeb0:543 AC-10 Given ②「候选行在位（不是 chip-only）」的下界，
                      # 不是 AC-10a 的门限 —— ≥4 只进 AC10 判定行（裁决 §9.2）。
MIN_POOL = 8          # 规格 0e4eeb0:543 AC-10 Given ③：候选池 ≥ 8 条
NEED_FULL = 4         # spec-AC-10a 硬门限：字身完整可见 ≥ 4
SCROLL_STEPS = 8      # 池取证用 `=` 键档数（每档 SCROLL_STEP_DP=72）
SPEC_GIVEN = "0e4eeb0:543-544"   # AC-10 的三条 Given，闸门只守这三条（原 8324b03:525，第七轮随 PR#17 head 重锚）
RC = dict(readback_mismatch=10, no_chip_box=11, chip_not_left=12, chip_only=13,
          pool_low=14, not_at_origin=15, key_map_fail=16, ime_not_ours=17, host_not_ready=18)


def gate(reason, **kw):
    # 被调用方常把诊断 dict 整个 ** 进来，而它自己就带一个 reason 键（如 key_map 的
    # no_row_with_10_keys）—— 直接展开会撞成 TypeError，闸门反而报不出闸门。
    if "reason" in kw:
        kw["diag_reason"] = kw.pop("reason")
    D.log("GATE", reason=reason, rc=RC.get(reason, 1), **kw)
    sys.stdout.flush()
    raise SystemExit(RC.get(reason, 1))


def cur_ime():
    return D.shell("settings get secure default_input_method").strip()


def ident_once(apk):
    """设备上装的包是不是这份构建产物：整包 hash 会漂（签名/对齐），逐条目比 dex + resources.arsc。"""
    p = os.path.join(D.Q, "_ident.apk")
    r = subprocess.run([D.ADB, "-s", D.S, "shell", "pm path com.ispeech.ime"], capture_output=True, text=True)
    if "package:" not in r.stdout:
        return False
    subprocess.run([D.ADB, "-s", D.S, "pull", r.stdout.strip().split("package:")[1], p], capture_output=True)

    def ent(path):
        z = zipfile.ZipFile(path)
        return {n: hashlib.sha256(z.read(n)).hexdigest()[:12] for n in sorted(z.namelist())
                if (n.startswith("classes") and n.endswith(".dex")) or n == "resources.arsc"}
    return ent(apk) == ent(p)


def measure(dens, fs, code, apk):
    dpi = int(dens)
    den = dpi / 160.0
    band_px = int(round(46 * den))   # candidate_view.xml layout_height=46dp
    subprocess.run([D.ADB, "-s", D.S, "shell", "wm", "density", dens], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "system", "font_scale", fs],
                   capture_output=True)
    time.sleep(1.2)
    st = D.host_ready()
    if not st.get("ok"):
        st = D.setup_contacts(retries=3)
    if not st.get("ok"):
        gate("host_not_ready", **{k: str(v) for k, v in st.items()})
    D.log("HOST", dens=dens, fs=fs, code=code, **{k: v for k, v in st.items()})

    ime_a = cur_ime()
    if not ime_a.startswith("com.ispeech.ime/"):
        gate("ime_not_ours", current=ime_a)
    D.clear(30)
    time.sleep(0.6)
    top0, bot0, h0 = D.ime_frame()
    p_empty = D.shot("shots/r5_%s_%s_fs%s_%s_empty.png" % (LABEL, dens, fs, code))
    m, diag = D.key_map(png=p_empty)
    if not diag.get("ok"):
        gate("key_map_fail", **{k: str(v) for k, v in diag.items()})
    D.log("KEYMAP", dens=dens, fs=fs, bands=diag["bands"], runs=diag["runs"],
          del_xy=m["del"], space_xy=m[" "], o_x=m["o"][0], z_x=m["z"][0])

    t = D.tap_keys(code, gap=0.14, m=m)
    if not t["ok"]:
        gate("readback_mismatch", want=t["want"], got=t["got"])
    time.sleep(0.9)
    top1, bot1, h1 = D.ime_frame()
    ime_b = cur_ime()
    p_comp = D.shot("shots/r5_%s_%s_fs%s_%s_comp.png" % (LABEL, dens, fs, code))
    if not ime_b.startswith("com.ispeech.ime/"):
        gate("ime_not_ours", current=ime_b, stage="after")
    D.log("IDENT", dens=dens, fs=fs, match=ident_once(apk), ime=ime_b, h=h1)

    a = band.analyze(p_comp, top=top1, band_px=band_px, dpi=dpi)
    if not a["ok"]:
        # 连 chip 的填充盒都没找到（band.analyze 的 no_chip_fill）——帧不对，和「候选为空」是两件事
        gate("no_chip_box", dens=dens, fs=fs, code=code, detail=a["reason"])
    if not a["chip_gate"]:
        gate("chip_not_left", chip=a["chip"], x0=a["first_item_left"])
    # Given ②：候选行在位。cand_count==0 就是 chip-only 伪装态（第四轮「量错对象」那条），
    # 必须在这一档拦住，不能让 chip-only 帧走到 pool_low —— 那会把失效原因从「量错对象」
    # 改成「词库不够」，第三人照 §10 复跑必读错（裁决 §9.2-1）。
    if a["cand_count"] < MIN_CAND:
        gate("chip_only", cand_count=a["cand_count"], need=MIN_CAND, given="2", x0=a["first_item_left"])

    # 池内条数：`=` 逐档右滚，每档与上一档做候选区互相关求累计位移，
    # 再把各帧候选项投到内容坐标系去重 → 池内条数下界。
    prev, cum, frames, verified = p_comp, 0, [(0, a)], 0
    for i in range(SCROLL_STEPS):
        D.shell("input keyevent 70")
        time.sleep(0.75)
        p = D.shot("_scroll%d.png" % i)
        s, err = D.shift(prev, p, a["inner_top"], a["inner_bot"], x0=a["first_item_left"], x1=a["x_end"])
        if s is None or err > 5:
            D.log("SCROLL", step=i, shift=s, err=None if err is None else round(err, 2),
                  verdict="unverifiable")
        else:
            verified += 1 if s > 0 else 0
            cum += s
            D.log("SCROLL", step=i, shift=s, err=round(err, 2), cum=cum)
        frames.append((cum, band.analyze(p, top=top1, band_px=band_px, dpi=dpi)))
        prev = p
    for i in range(SCROLL_STEPS):
        D.shell("input keyevent 69")
        time.sleep(0.6)
    time.sleep(0.8)
    p_back = D.shot("_back.png")
    s0, e0 = D.shift(p_comp, p_back, a["inner_top"], a["inner_bot"], x0=a["first_item_left"], x1=a["x_end"])
    D.log("ORIGIN", shift_back=s0, err=None if e0 is None else round(e0, 2), tol=6)
    if s0 is None or abs(s0) > 6:
        gate("not_at_origin", shift_back=s0)
    pool = band.count_items_content_space(frames)
    offs_used = len({round(o / 40) for o, _ in frames})
    D.log("POOL", code=code, dens=dens, fs=fs, pool=pool, need=MIN_POOL,
          verified_steps=verified, offsets_sampled=offs_used, cum_px=cum)
    if pool < MIN_POOL:
        gate("pool_low", pool=pool, need=MIN_POOL, verified_steps=verified, cum_px=cum)

    # ===== 闸门全过，才开始出数 =====
    # 规格 0e4eeb0:543 的三条 Given 逐条落一行：让读证据的人不用自己拼「这几行合起来说明了什么」。
    # chip==所敲串 的口径 = 组合缓冲回读（`tap_keys` 已断言宿主文本 == 所敲串）：
    # chip 画的就是这份组合串（CandidateView 用 composing 文本绘制），没有独立的 chip 文本可读。
    # 候选行在位 的口径 = `cand_count ≥ 1`：`CandidateView.kt:76-77` 在列表为空时把 scrollView 置
    # GONE，GONE 的视图不产生任何像素，所以「chip 右侧有墨迹」即「VISIBLE」的充分证据。
    D.log("GATES", dens=dens, fs=fs, code=code, spec_anchor=SPEC_GIVEN,
          given1_chip_equals_code=(t["got"] == code), given1_readback=t["ok"],
          given2_candidate_row_present=a["cand_count"] >= MIN_CAND, cand_count=a["cand_count"],
          given3_pool_at_least=pool, need_pool=MIN_POOL, chip_box=a["chip"],
          chip_right=a["chip_right"], x0=a["first_item_left"])
    delta = (h1 - h0) if (h0 and h1) else None
    D.log("RESULT", fs=fs, dens=dens, code=code, empty=h0, comp=h1, delta=delta,
          band_px=band_px, band_inner=a["band_inner"], top1=top1, x0=a["first_item_left"],
          chip_right=a["chip_right"], cand_count=a["cand_count"], full_count=a["full_count"],
          pool_lower_bound=pool, badge_px=a["badge_px"], ime_before=ime_a, ime_after=ime_b)
    # 空态（无候选、无 chip）也照同一采样区量一次，作为「空态确实没墨迹」的对照
    e = band.analyze(p_empty, top=top0, band_px=band_px, dpi=dpi)
    D.log("INK", fs=fs, dens=dens, code=code, kind="empty", cand_count=e["cand_count"],
          x0=None if not e["ok"] else e["first_item_left"],
          detail=e.get("reason") or "band_ok")
    g = [it for it in a["items"] if it["ink_top"] is not None]
    if g:
        D.log("INK", fs=fs, dens=dens, code=code, kind="comp", cand_count=a["cand_count"],
              x0=a["first_item_left"], x_end=a["x_end"],
              ink_top=min(it["ink_top"] for it in g), ink_bot=max(it["ink_bot"] for it in g),
              inner_top=a["inner_top"], inner_bot=a["inner_bot"], band_inner=a["band_inner"],
              top_pad=min(it["ink_top"] for it in g) - a["inner_top"],
              bot_pad=a["inner_bot"] - 1 - max(it["ink_bot"] for it in g),
              tangent_items=sum(1 for it in g if it["tangent_top"] or it["tangent_bot"]))
    for it in a["items"]:
        D.log("CAND", code=code, dens=dens, fs=fs, idx=it["idx"], x0=it["item_left"], x1=it["item_right"],
              glyph=[it["glyph_left"], it["glyph_right"]],
              glyph_w_dp=round((it["glyph_right"] - it["glyph_left"] + 1) / den, 1),
              ink_top=it["ink_top"], ink_bot=it["ink_bot"], ink_h=it["ink_h"],
              pitch=it["pitch"], badge=it["badge"], badge_clipped=it["badge_clipped"],
              tangent_top=it["tangent_top"], tangent_bot=it["tangent_bot"],
              fully_visible=it["fully_visible"])
    ac10, ac10b = band.ac10_rows(a, den)
    # 每一帧都要出 verdict（裁决 §9.2-3：两档 × 两码 = 4 帧，一帧不落）。
    # AC-10a 的 verdict 以规格采样区口径为准，屏宽口径同行并列；两者结论不一致 → boundary=True 交洞察BA。
    D.log("AC10", code=code, dens=dens, fs=fs, given3_pool=pool,
          pool_need=MIN_POOL, kb_h=h1, empty_h=h0, delta=delta, **ac10)
    # AC-10b 只记录不判（规格 `0e4eeb0:561`）：同一批帧的逐项 x0/x1/字身 top/bot/pitch 由 CAND 行承载，
    # 这一行给「为什么不足 4 条」的归因，不足 4 条 ≠ 不给数。
    D.log("AC10b", code=code, dens=dens, fs=fs, **ac10b)
    D.shell("input keyevent 111")
    time.sleep(0.4)
    for tmp in ["_back.png"] + ["_scroll%d.png" % i for i in range(SCROLL_STEPS)]:
        try:
            os.remove(os.path.join(D.Q, tmp))
        except OSError:
            pass


if __name__ == "__main__":
    apk = sys.argv[1]
    dens_list = sys.argv[2].split(",")
    fs_list = sys.argv[3].split(",")
    codes = (sys.argv[4].split(",") if len(sys.argv) > 4 else ["shijian", "dashu"])
    panel = sys.argv[6] if len(sys.argv) > 6 else None   # 第七轮打回项 2：可指定 1260x2720
    orig = D.orig_state()
    if panel:
        D.set_panel(panel)
    pw, ph = D.panel_size()
    D.log("PANEL", panel="%dx%d" % (pw, ph), requested=panel or "device_default",
          logic_w_dp=D.logic_width(), dens_now=D.live_density(),
          tier="档1" if D.logic_width() >= band.TIER_SPLIT_DP else "档2",
          orig_size=orig["size"], orig_density=orig["density"], orig_font_scale=orig["font_scale"])
    subprocess.run([D.ADB, "-s", D.S, "install", "-r", "-d", "-g", apk], capture_output=True, timeout=900)
    subprocess.run([D.ADB, "-s", D.S, "shell", "ime", "enable", D.IME], capture_output=True)
    subprocess.run([D.ADB, "-s", D.S, "shell", "settings", "put", "secure", "autofill_service", "null"],
                   capture_output=True)
    if not ident_once(apk):
        D.log("GATE", reason="installed_apk_not_this_build", rc=19)
        raise SystemExit(19)
    try:
        for dens in dens_list:
            for fs in fs_list:
                for code in codes:
                    measure(dens, fs, code, apk)
    finally:
        # 机主原值复原，不硬编码 1.0/440：中途 gate() 抛 SystemExit 也要走到这里
        D.restore_state(orig)
        D.log("PANEL", panel="restored", size_now="%dx%d" % D.panel_size(),
              density_now=D.shell("wm density").strip().replace("\n", " | "),
              font_scale_now=D.shell("settings get system font_scale").strip())
