#!/usr/bin/env python3
"""成对比较同一链路的两份证据（ev_r7_* ↔ ev_r8_*）：剥掉 ts= 后按 (tag, 主键) 对齐，
逐字段现算差异，用来把「复跑逐位一致」这句话落到字段级。用法：
    python3 rerun_diff.py evidence/ev_r7_fs115.txt evidence/ev_r8_fs115.txt [...]
判定量零差异 => rc 0；有差异字段 => 逐条点名后 rc 0（差异是否进判据由人读 JUDGE_TAGS）。
"""
import collections
import sys
import time

# 用来对齐行的主键；不同链路的 tag 用同一组键，缺字段就跳过
KEYS = ("code", "dens", "fs", "idx", "panel", "tier", "step", "stage", "tag", "phase", "kind")
# 进判据的 tag：这些 tag 里出现差异才算翻了结论
JUDGE_TAGS = ("RESULT", "INK", "CAND", "AC10", "AC10b", "FRAME", "VERDICT", "PPSUM", "RES_HIT")
# 明确不进判据的字段（时刻之外的抖动）
NOISE_FIELDS = ("chip_box", "err", "shot")
# 入口/参数快照字段：不同轮次的跑序会让它们不同，与读数无关，但必须点名不能吞掉
SNAPSHOT_FIELDS = ("requested", "orig_size", "orig_density", "orig_font_scale")


def stamp():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def log(kind, **kw):
    kw.setdefault("ts", stamp())
    print("%s\t%s" % (kind, "\t".join("%s=%s" % (k, v) for k, v in kw.items())), flush=True)


def load(path):
    rows = collections.defaultdict(list)
    for ln in open(path, encoding="utf-8"):
        if ln.startswith("#") or not ln.strip():
            continue
        parts = ln.rstrip("\n").split("\t")
        f = {}
        for kv in parts[1:]:
            if kv.startswith("ts="):
                continue
            k, _, v = kv.partition("=")
            f[k] = v
        rows[parts[0]].append(f)
    return rows


def key_of(tag, f):
    return tuple("%s=%s" % (k, f[k]) for k in KEYS if k in f)


def pair(old, new):
    A, B = load(old), load(new)
    ta = sum(len(v) for v in A.values())
    tb = sum(len(v) for v in B.values())
    judge_diff = {}
    noise_diff = {}
    snap_diff = {}
    only_a = collections.Counter()
    only_b = collections.Counter()
    for tag in sorted(set(A) | set(B)):
        ra = {}
        rb = {}
        for f in A.get(tag, []):
            ra.setdefault(key_of(tag, f), f)
        for f in B.get(tag, []):
            rb.setdefault(key_of(tag, f), f)
        shared = set(ra) & set(rb)
        only_a[tag] = len(set(ra) - shared)
        only_b[tag] = len(set(rb) - shared)
        for s in shared:
            for k in set(ra[s]) | set(rb[s]):
                if ra[s].get(k) == rb[s].get(k):
                    continue
                if k in SNAPSHOT_FIELDS:
                    dst = snap_diff
                elif k in NOISE_FIELDS:
                    dst = noise_diff
                else:
                    dst = judge_diff
                dst.setdefault(k, [0, None, None])
                dst[k][0] += 1
                if dst[k][1] is None:
                    dst[k][1] = str(ra[s].get(k))[:70]
                    dst[k][2] = str(rb[s].get(k))[:70]
    log("PAIR", old=old.split("/")[-1], new=new.split("/")[-1], rows="%d↔%d" % (ta, tb),
        judge_diff_fields=sorted(judge_diff.items(), key=lambda kv: -kv[1][0]) or "NONE",
        noise_diff_fields=sorted(noise_diff.items(), key=lambda kv: -kv[1][0]) or "NONE",
        snapshot_diff_fields=sorted(snap_diff.items(), key=lambda kv: -kv[1][0]) or "NONE",
        only_old={k: v for k, v in only_a.items() if v} or "NONE",
        only_new={k: v for k, v in only_b.items() if v} or "NONE",
        judge_tags=list(JUDGE_TAGS))
    for k, (n, a, b) in sorted(judge_diff.items()):
        log("JUDGE_DIFF", field=k, rows=n, old=a, new=b)
    for k, (n, a, b) in sorted(noise_diff.items()):
        log("NOISE_DIFF", field=k, rows=n, sample_old=a, sample_new=b)
    for k, (n, a, b) in sorted(snap_diff.items()):
        log("SNAPSHOT_DIFF", field=k, rows=n, old=a, new=b,
            note="入口快照/传参差异，跑序不同就会不同，不进判据但必须点名")
    return not judge_diff


def main(argv):
    files = argv[1:]
    if len(files) % 2:
        log("GATE", reason="need_even_args", rc=40)
        return 40
    ok = True
    for i in range(0, len(files), 2):
        ok &= pair(files[i], files[i + 1])
    log("SUMMARY", pairs=len(files) // 2, judgement_identical=ok,
        note="judge_diff_fields=NONE ⇒ 复跑没有翻结论；NOISE_DIFF 是像素计数/标定抖动，SNAPSHOT_DIFF 是入口快照")
    return 0 if ok else 42


if __name__ == "__main__":
    sys.exit(main(sys.argv))
