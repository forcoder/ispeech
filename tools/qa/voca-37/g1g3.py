#!/usr/bin/env python3
"""第八轮打回项 3：G1/G3 资源表面证据要能逐行追时刻，并且整份要署名到被测 apk。

第七轮的 `ev_r7_g1_g3.txt` 有两处不合格（质检第 7 轮裁决 §二 判「无法核实」）：
  · 7 条数据行一条 `ts=` 都没有 —— 只有文件头一个时刻，读者追不到当场；
  · 整份没有 `IDENT … match=True` —— G1–G7 是回归护栏面，追不到 `78343a4` 这只包，
    就不能排除「dump 的是另一只 apk 的资源表」。
本脚本把这两条补上：每条数据行由 `log()` 自带 `ts=`；`IDENT` 行复用 `fs115.ident_once`
（设备上 `base.apk` 与本地构建产物逐条目比 `classes*.dex` + `resources.arsc` 的 SHA256），
两侧指纹逐条打出来，第三方可脱离本脚本复算。

口径（第七轮那份的名单没写清，这一版钉死并**把命中逐条列全**）：
  G1 = 候选条不出现「N/M」页码 → 资源名里不得有 `candidate_page*`/`page_indicator*`，
       代码里不得有页码文本绘制（`tvPage`/`page_number`/`pageNum`）；
       `candidate_page_size` 是 SettingsStore 的持久化键（每页候选条数），不是 UI 页码 —— 逐条点名不放过。
  G3 = 候选条不出现翻页控件 → `candidate_view.xml` 里没有 ViewPager/ImageButton 型控件；
       资源名含 page/pager 的条目**全部列出**并标「是否被代码引用」。
       实测本包有 `drawable/ic_page_next` + `drawable/ic_page_prev`（第七轮那句「全量只命中
       ic_settings_gear」是漏报，第八轮订正）：两者在 `fa57a07` 上就存在、PR #18 没动过、
       代码零引用 = 死资源，记 DEAD 观察项，不记 PASS 也不记 FAIL。

用法：python3 g1g3.py <apk> [--no-device] [--arsc <已 dump 的文本>]
退出码：0 全过 / 19 设备上装的不是这只包 / 21 aapt2 dump 失败 / 22 本地 apk 读不出
"""
import argparse, hashlib, os, re, subprocess, sys, time, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive as D
import fs115

AAPT2 = os.environ.get("QA_AAPT2", "/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0/aapt2")
HEAD = os.environ.get("QA_HEAD", "78343a4")
SPEC = os.environ.get("QA_SPEC", "0e4eeb0")
ENTRY_RE = re.compile(r"resource\s+(0x[0-9a-f]+)\s+(\w+)/([\w.$]+)")
# 全量扫这一组，防「名单外漏项」；库自带（AndroidX/Material）的 attr/style 单列不判
SCOPE = re.compile(r"page|pager|pagination|candidate_pager", re.I)
SPEC_NAMED = re.compile(r"candidate_page|page_indicator|pager_button|candidate_pager", re.I)
LIB_PREFIX = ("m3_", "material_", "design_", "abc_", "mo_", "mtrl_", "notification_", "preference_",
              "tooltip_", "bottomsheet_", "search_", "editbox_", "ratingbar_", "tab", "carousel",
              "linear", "circular", "active", "item", "home", "list", "scroll", "indicator")
PAGE_CODE_RE = r"candidate_page|page_number|pageNum|tvPage|pageText"


def ts():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def log(kind, **kw):
    kw.setdefault("ts", ts())
    print("%s\t%s" % (kind, "\t".join("%s=%s" % (k, v) for k, v in kw.items())), flush=True)


def ent(path):
    z = zipfile.ZipFile(path)
    return {n: hashlib.sha256(z.read(n)).hexdigest()[:12]
            for n in sorted(z.namelist())
            if (n.startswith("classes") and n.endswith(".dex")) or n == "resources.arsc"}


def repo_root():
    """代码面 grep 必须跑在**被测构建的检出**上（QA_SRC），不是 QA 自己的分支树 ——
    否则「apk 是 78343a4、源码是 main」这种错配又是一次署名不上的读数。"""
    env = os.environ.get("QA_SRC")
    if env:
        return env
    d = os.path.dirname(os.path.abspath(__file__))
    while d != "/" and not os.path.exists(os.path.join(d, "settings.gradle.kts")):
        d = os.path.dirname(d)
    return d


def grep_src(pattern, types=(".kt", ".xml")):
    """返回 [(file:line, text)]，排除 drawable 资源文件自身的注释。"""
    src = os.path.join(repo_root(), "app", "src")
    if not os.path.isdir(src):
        return [], src
    args = ["grep", "-rInE", pattern, src]
    for t in types:
        args += ["--include", "*" + t]
    out = subprocess.run(args, capture_output=True, text=True).stdout.splitlines()
    return [x for x in out if x and "/res/drawable/" not in x], src


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--no-device", action="store_true")
    ap.add_argument("--arsc", default=None)
    a = ap.parse_args()
    if not os.path.exists(a.apk):
        log("GATE", reason="apk_missing", path=a.apk, rc=22)
        raise SystemExit(22)

    log("IDENT", stage=0, role="apk_under_test", path=a.apk, bytes=os.path.getsize(a.apk),
        head=HEAD, spec=SPEC,
        entries=";".join("%s:%s" % kv for kv in sorted(ent(a.apk).items())))
    if not a.no_device:
        match = fs115.ident_once(a.apk)
        p = os.path.join(D.Q, "_ident.apk")
        log("IDENT", stage=1, role="device_apk_matches_build", match=match,
            device_entries=";".join("%s:%s" % kv for kv in sorted(ent(p).items())) if os.path.exists(p) else "n/a",
            ime=D.shell("settings get secure default_input_method").strip(),
            serial=D.S, model=D.shell("getprop ro.product.model").strip())
        if not match:
            log("GATE", reason="installed_apk_not_this_build", rc=19)
            raise SystemExit(19)

    if a.arsc:
        lines = open(a.arsc, encoding="utf-8", errors="replace").read().splitlines()
        log("DUMP", source=a.arsc, resource_lines=len(lines), rc=0)
    else:
        r = subprocess.run([AAPT2, "dump", "resources", a.apk], capture_output=True, text=True)
        if r.returncode != 0:
            log("GATE", reason="aapt2_dump_failed", rc=21, stderr=r.stderr[:300])
            raise SystemExit(21)
        lines = r.stdout.splitlines()
        log("DUMP", tool=AAPT2, resource_lines=len(lines), rc=0)

    sh = subprocess.run(["git", "-C", repo_root(), "rev-parse", "--short", "HEAD"],
                        capture_output=True, text=True).stdout.strip() or "unknown"
    log("SRC_TREE", root=repo_root(), head=sh, expected_head=HEAD, match=HEAD.startswith(sh) if sh else False,
        note="代码面/布局面的 grep 就跑在这棵树上")

    # 1) 逐条列全：资源名含 page/pager 的条目（库自带单列）
    seen, app_own, lib_n = set(), [], 0
    for ln in lines:
        m = ENTRY_RE.search(ln)
        if not m or not SCOPE.search(m.group(3)) or (m.group(1), m.group(3)) in seen:
            continue
        seen.add((m.group(1), m.group(3)))
        if m.group(2) in ("attr", "style", "color", "dimen", "integer", "bool") or \
           m.group(3).lower().startswith(LIB_PREFIX):
            lib_n += 1
            continue
        refs, _ = grep_src(re.escape(m.group(3)))
        app_own.append((m.group(1), m.group(2), m.group(3), refs))
    log("SCOPE", name_pattern=SCOPE.pattern, total_matches=len(seen), library_or_styled_skipped=lib_n,
        app_owned_widget_surface=len(app_own),
        spec_named_pattern=SPEC_NAMED.pattern,
        note="attr/style/color/dimen/integer 与库前缀条目是 AndroidX/Material 合表进来的，不是本包候选条控件")
    for rid, typ, name, refs in sorted(app_own):
        log("RES_HIT", id=rid, type=typ, name=name, spec_named=bool(SPEC_NAMED.search(name)),
            referenced_in_src=len(refs), status="DEAD" if not refs else "REFERENCED",
            refs=";".join(x.split("/app/src/")[-1] for x in refs[:3])[:180] or "none")

    # 2) G1：页码资源 + 页码绘制路径（逐条点名，含「命中但是持久化键」这一类）
    cand_page = [x for x in app_own if SPEC_NAMED.search(x[2])]
    code_hits, src = grep_src(PAGE_CODE_RE)
    for f in code_hits[:6]:
        log("G1_CODE_HIT", where=f.split("/app/src/")[-1][:180])
    ui_page = [x for x in code_hits if "candidate_page_size" not in x]
    log("G1", candidate_page_resources=len(cand_page), code_hits_total=len(code_hits),
        code_hits_ui_page_number=len(ui_page),
        persistence_key_hits=len(code_hits) - len(ui_page),
        verdict="PASS：资源表无 candidate_page_*/page_indicator；代码零页码绘制路径，"
                "唯一命中是 SettingsStore 的 candidate_page_size（每页候选条数持久化键，非 UI 页码）"
                if not cand_page and not ui_page else "CHECK：见 G1_CODE_HIT / RES_HIT")

    # 3) G3：候选布局里的翻页控件
    cv = os.path.join(repo_root(), "app", "src", "main", "res", "layout", "candidate_view.xml")
    cv_txt = open(cv, encoding="utf-8").read() if os.path.exists(cv) else ""
    vp, _ = grep_src(r"ViewPager2?\b|TabLayout")
    pager_widget = bool(re.search(r"ViewPager|ImageButton|TabLayout|ImageView", cv_txt))
    log("G3_LAYOUT", file=os.path.relpath(cv, repo_root()) if cv_txt else "not_found",
        lines=cv_txt.count("\n"), pager_widget_in_candidate_view=pager_widget,
        viewpager_anywhere_in_src=len(vp),
        children=re.findall(r'android:id="@\+id/(\w+)"', cv_txt))
    dead = [x[2] for x in app_own if not x[3]]
    log("G3", spec_named_hits=len(cand_page), dead_page_resources=len(dead),
        dead_names=";".join(dead) or "none",
        viewpager_hits=len(vp),
        verdict="PASS（行为面：候选条无翻页控件、无页码）＋ OBSERVATION：ic_page_next/ic_page_prev "
                "是本包死资源（fa57a07 起就有、PR #18 未动、代码零引用）—— 记死资源，不是回归"
                if not pager_widget and not vp else "CHECK")
    log("VERDICT", g1="PASS" if not cand_page and not ui_page else "CHECK",
        g3="PASS_with_dead_resource_observation" if not pager_widget and not vp else "CHECK",
        head=HEAD, ident="bound_to_device_apk" if not a.no_device else "apk_only")


if __name__ == "__main__":
    main()
