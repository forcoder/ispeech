#!/usr/bin/env python3
"""推送形态自证：本地提交 ↔ 远端提交，逐路径比 blob（第八轮打回项 4(a) 的引用口径）。

用法：python3 pushform.py [--local REF] [--pushed REF] [--base-local SHA] [--base-pushed SHA]
输出到 stdout（每数据行带 ts=），入库时改名 ev_r<N>_pushed_shas*.txt。
"""
import argparse
import subprocess
import sys
import time

EXCL = {
    "dist/ispeech-release-1.5-20261115-1004.apk",
    "tools/verify/__pycache__/check_crosscut.cpython-315.pyc",
}
BRANCH = "refs/heads/test/voca-37-stage3-regression"


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True).stdout


def ts():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def row(kind, **kw):
    parts = [kind] + ["%s=%s" % (k, v) for k, v in kw.items()] + ["ts=%s" % ts()]
    print("\t".join(parts), flush=True)


def tree(ref):
    d = {}
    for line in sh("git", "ls-tree", "-r", "--full-tree", ref).splitlines():
        meta, path = line.split("\t", 1)
        mode, _type, blob = meta.split()
        if path not in EXCL:
            d[path] = (mode, blob)
    return d


def names(base, head):
    return sorted(p for p in sh("git", "diff", "--name-only", base, head).splitlines() if p not in EXCL)


def full(ref):
    return sh("git", "rev-parse", ref).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--local", default="HEAD")
    ap.add_argument("--pushed", required=True)
    ap.add_argument("--base-local", default="1a2727a")
    ap.add_argument("--base-pushed", default="8d334ae")
    ap.add_argument("--max-blob", action="store_true")
    a = ap.parse_args()

    local, pushed = full(a.local), full(a.pushed)
    remote_lines = sh("git", "ls-remote", "origin", BRANCH).splitlines()
    remote = remote_lines[0].split()[0] if remote_lines else "UNAVAILABLE"

    print("# pushform.py · 「本地形态 ↔ 推送形态」逐路径 blob 比对（推送形态刻意排除 %d 个路径）" % len(EXCL))
    print("# 生成命令: %s" % " ".join(["python3 pushform.py"] + sys.argv[1:]))
    print("# 远端分支 head（git ls-remote origin %s 现算）=%s" % (BRANCH, remote))
    for p in sorted(EXCL):
        print("#   排除: %s" % p)
    print("# 固有界限：本文件所在的提交无法自证自己，核对比照下面「现算核对命令」由第三人自己跑。")

    A, B = tree(local), tree(pushed)
    ident = sum(1 for k in A if k in B and A[k] == B[k])
    differing = [k for k in A if k in B and A[k] != B[k]]
    mismatch = sorted(set(A) ^ set(B))
    row("PUSHFORM", local=local[:7], pushed=pushed[:7],
        parent_of_pushed=sh("git", "rev-parse", "--short", pushed + "^").strip(),
        blobs_compared=len(A), blob_identical=ident, differing=len(differing),
        path_mismatch=len(mismatch), excluded_paths=len(EXCL))
    nl, np_ = names(a.base_local, local), names(a.base_pushed, pushed)
    row("CUMULATIVE", local_range="%s..%s" % (a.base_local, local[:7]),
        pushed_range="%s..%s" % (a.base_pushed, pushed[:7]),
        local_files=len(nl), pushed_files=len(np_), name_lists_equal=(nl == np_))
    row("REMOTE_HEAD", branch=BRANCH.split("/")[-1], sha=remote, matches_pushed=(remote == pushed))
    if a.max_blob:
        biggest = max((int(sh("git", "cat-file", "-s", v[1]).strip() or 0), k) for k, v in B.items())
        row("MAX_BLOB", pushed_bytes=biggest[0], path=biggest[1])
    ok = not differing and not mismatch and remote == pushed
    row("SUMMARY", pairs=1, blobs_identical=ident, differing=len(differing),
        missing=len(mismatch), pr=25, rc=0 if ok else 41)
    print("# 现算核对命令（逐字复制，第三方自己跑；不要求信上面任何一行）：")
    print("#   git ls-remote origin %s" % BRANCH)
    print("#   diff <(git ls-tree -r %s | grep -vE 'dist/ispeech-release-1.5-20261115-1004.apk|tools/verify/__pycache__') \\\\" % local)
    print("#        <(git ls-tree -r %s | grep -vE 'dist/ispeech-release-1.5-20261115-1004.apk|tools/verify/__pycache__')   # 必须空输出" % pushed)
    print("#   diff <(git diff --name-only %s %s | grep -vE '^dist/|__pycache__') \\\\" % (a.base_local, local))
    print("#        <(git diff --name-only %s %s | grep -vE '^dist/|__pycache__')   # 两份清单必须逐行相同" % (a.base_pushed, pushed))
    return 0 if ok else 41


if __name__ == "__main__":
    sys.exit(main())
