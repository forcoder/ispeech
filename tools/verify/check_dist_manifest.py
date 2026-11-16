#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_dist_manifest.py —— dist/SHA256SUMS 与包字节一致性校验（出包门禁）

清单的唯一职责：让人用一条命令回答「我手上这份 APK 的字节，是不是仓库里那份」——
也就是 VOCA-167 里反复取证「用户机上装的是哪一包」的自助路径。清单一旦与字节漂移，
它给的就不是「不知道」而是「假答案」，比没有清单更坏，所以本脚本把它当契约校验。

三条不变量：
  1. 记的那个数 == 文件实际字节（等价 `cd dist && shasum -a 256 -c SHA256SUMS`）
  2. 穷尽覆盖：每个**该入清单**的 APK 都有一行 —— 漏记比记错更难发现，
     main 上 `dist/_archive/` 三个包从未进清单就是这么来的
  3. 无孤儿行：清单里不能指向不存在的文件

「该入清单」= `git ls-files --cached --others --exclude-standard -- dist` 里的 `*.apk`，
即「已跟踪的 + 会被 `git add -A` 扫进来的」。用 git 而不是裸扫目录，是为了把 `.gitignore`
已排除的本机残留（如 `dist/ispeech-debug-*.apk`）挡在门外，同时让**刚拷进 dist 还没入库**的
新包立刻要求一行记录 —— 出包流程里漏记就是靠这一条堵住的。

覆盖范围的边界（不是 bug，是 `.gitignore` 的既有政策）：被 gitignore 排除的包既不入册、
也不被要求入册，所以 `deploy.sh` 默认（debug）产物**不在清单里**。清单只能回答「仓库里那份
是不是这字节」，答不了手机上的 debug 包 —— 口径见 `docs/adr/VOCA-400-dist-checksum-manifest.md` D2。

用法：
    python3 tools/verify/check_dist_manifest.py            # 校验，任一 FAIL 退出码 1
    python3 tools/verify/check_dist_manifest.py --regen    # 按当前字节重写清单

清单是**生成物**，唯一写入路径是 `--regen`；不要手改，也不要 `>>` 追加
（追加会产生同一路径的两行，`shasum -c` 取后一行，前一行永久静默）。

输出沿用 tools/verify 约定：每行 `PASS` / `FAIL` / `WARN`，末尾 `SUMMARY: ...`。
不联网、不依赖 cwd、不改源码（除 `--regen` 明确改写清单本身）。
"""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / "dist"
MANIFEST = DIST / "SHA256SUMS"

LINE_RE = re.compile(r"^([0-9a-f]{64})  (.+)$")
# GitHub 单文件 100 MB 硬限制（GH001 拒推），超过只 WARN：产物治理归 VOCA-292
SIZE_WARN_BYTES = 100 * 1024 * 1024

_passed = 0
_failed = 0
_warned = 0


def report(level: str, message: str) -> None:
    global _passed, _failed, _warned
    print(f"{level}: {message}")
    if level == "PASS":
        _passed += 1
    elif level == "FAIL":
        _failed += 1
    elif level == "WARN":
        _warned += 1


def check(ok: bool, message: str, warn: bool = False) -> bool:
    report("PASS" if ok else ("WARN" if warn else "FAIL"), message)
    return ok


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def run_git(args: list[str]) -> str | None:
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT)] + args,
            capture_output=True, text=True, check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return out.stdout


def required_apks() -> list[str]:
    """该入清单的包：已跟踪 + 未跟踪但未被 ignore（相对 dist/ 的路径）。"""
    stdout = run_git(
        ["ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "dist"]
    )
    if stdout is None:  # 非 git 环境（git archive 解出来的包）退回目录扫描
        return sorted(
            str(p.relative_to(DIST)) for p in DIST.rglob("*.apk")
        )
    paths = [p for p in stdout.split("\0") if p.endswith(".apk")]
    return sorted(p[len("dist/"):] if p.startswith("dist/") else p for p in paths)


def tracked_apks() -> set[str] | None:
    """已跟踪的包；无 git 元数据时返回 None —— 那时分不清跟踪与否，不能判 FAIL。"""
    stdout = run_git(["ls-files", "-z", "--cached", "--", "dist"])
    if stdout is None:
        return None
    return {p[len("dist/"):] if p.startswith("dist/") else p
            for p in stdout.split("\0") if p.endswith(".apk")}


def parse_manifest() -> tuple[list[tuple[str, str]], list[str]]:
    entries: list[tuple[str, str]] = []
    errors: list[str] = []
    text = MANIFEST.read_text(encoding="utf-8")
    for lineno, raw in enumerate(text.splitlines(), start=1):
        if not raw.strip():
            errors.append(f"第 {lineno} 行：空行")
            continue
        m = LINE_RE.match(raw)
        if not m:
            errors.append(f"第 {lineno} 行：格式非法（期望 `<64位小写sha256><两个空格><路径>`）")
            continue
        sha, path = m.group(1), m.group(2)
        if path.startswith("/") or ".." in Path(path).parts:
            errors.append(f"第 {lineno} 行：路径越出 dist/（{path}）")
            continue
        entries.append((sha, path))
    return entries, errors


def check_manifest() -> int:
    print(f"== check_dist_manifest.py == 仓库根：{ROOT}")

    if not MANIFEST.is_file():
        check(False, f"{MANIFEST.relative_to(ROOT)} 存在")
        print("SUMMARY: passed=0 failed=1 warned=0")
        return 1
    check(True, f"{MANIFEST.relative_to(ROOT)} 存在")

    entries, errors = parse_manifest()
    check(not errors, f"每行格式合法、路径不越出 dist/（问题 {len(errors)} 处）"
          + "".join(f"\n      {e}" for e in errors[:10]))

    dupes = sorted({p for _, p in entries if [q for _, q in entries].count(p) > 1})
    check(not dupes, f"无重复路径记录（重复: {dupes or '无'}）")

    by_path = {p: s for s, p in entries}

    missing = [p for p in by_path if not (DIST / p).is_file()]
    check(not missing, f"清单里每条都指向实际存在的文件（缺文件: {missing or '无'}）")

    present = [p for p in by_path if (DIST / p).is_file()]
    mismatched = [p for p in present if sha256_of(DIST / p) != by_path[p]]
    check(not mismatched,
          "每条记录的 sha256 == 文件实际字节"
          + ("".join(
                 f"\n      {p}: 清单 {by_path[p][:16]}… != 实际 {sha256_of(DIST / p)[:16]}…"
                 for p in mismatched) or "（对读通过）"))

    uncovered = [p for p in required_apks() if p not in by_path]
    check(not uncovered,
          f"穷尽覆盖：dist 下每个 APK 都在清单里（漏记 {len(uncovered)} 个: {uncovered or '无'}）")

    ordered = [p for _, p in entries] == sorted(p for _, p in entries)
    check(ordered, "清单按路径升序（--regen 的输出顺序）", warn=True)

    tracked = tracked_apks()
    for p in sorted(set(required_apks()) | set(present)):
        apk = DIST / p
        if not apk.is_file():
            continue
        size = apk.stat().st_size
        if size <= SIZE_WARN_BYTES:
            continue
        # 未跟踪 + 超 100 MB：记录进清单也推不上去（GH001），等于凭空造一行假记录
        untracked_oversize = tracked is not None and p not in tracked
        check(not untracked_oversize,
              f"{p} 体积 {size:,} B 超 GitHub 单文件 100 MB 硬限"
              + ("（未跟踪）：先进不了仓库，清单记了就是假答案 —— 把它移出 dist/ 再 --regen"
                 if untracked_oversize else "（已跟踪），含它的分支推不上去（产物治理见 VOCA-292）"),
              warn=not untracked_oversize)

    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    return 1 if _failed else 0


def regen() -> int:
    if not DIST.is_dir():
        print(f"FAIL: {DIST} 不存在")
        return 1
    apks = required_apks()
    if not apks:
        print("FAIL: dist 下没有找到任何应入清单的 APK，拒绝写出空清单")
        return 1
    lines = [f"{sha256_of(DIST / p)}  {p}" for p in apks]
    new = "\n".join(lines) + "\n"
    old = MANIFEST.read_text(encoding="utf-8") if MANIFEST.is_file() else ""
    MANIFEST.write_text(new, encoding="utf-8")
    print(f"已按当前字节重写 {MANIFEST.relative_to(ROOT)}（{len(apks)} 条）")
    for p in apks:
        print(f"  {sha256_of(DIST / p)}  {p}  {(DIST / p).stat().st_size:,} B")
    if old and old != new:
        print("（原清单已替换；差异请随本次出包提交一起评审）")
    return check_manifest()


def self_test() -> int:
    """在临时目录里伪造 8 种仓库状态，验证判定逻辑**双向**准确。

    门禁自己必须是可验的：只测「好状态放行」等于没测 —— 一个恒真的检查器照样让
    清单与字节漂移过去。改判定逻辑后先跑这个。
    """
    import shutil
    import tempfile

    global _passed, _failed
    _passed = _failed = 0
    script = Path(__file__).resolve()

    def build(root: Path, apks: dict[str, bytes], manifest_lines: list[str]) -> None:
        for rel_path, data in apks.items():
            f = root / "dist" / rel_path
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(data)
        (root / "dist" / "SHA256SUMS").write_text("\n".join(manifest_lines) + "\n", encoding="utf-8")

    def run_check(root: Path) -> int:
        # 必须跑副本：脚本用 __file__ 的 parents[2] 定位仓库根，跑原件会去校验真仓库
        return subprocess.run(
            [sys.executable, str(root / "tools" / "verify" / script.name)], cwd=root,
            capture_output=True, text=True,
        ).returncode

    def scenario(name: str, apks: dict[str, bytes], manifest_lines: list[str], expect_ok: bool,
                 untracked: dict[str, int] | None = None) -> None:
        tmp = Path(tempfile.mkdtemp(prefix="distsum-selftest-"))
        try:
            (tmp / "tools" / "verify").mkdir(parents=True)
            shutil.copy(script, tmp / "tools" / "verify" / script.name)
            subprocess.run(["git", "init", "-q", "."], cwd=tmp, capture_output=True)
            build(tmp, apks, manifest_lines)
            subprocess.run(["git", "add", "-A"], cwd=tmp, capture_output=True)
            # git add 之后再放：模拟「baseline 从工作目录扫进来的未跟踪大二进制」
            for rel_path, size in (untracked or {}).items():
                f = tmp / "dist" / rel_path
                with f.open("wb") as fh:
                    fh.truncate(size)
            rc = run_check(tmp)
            check((rc == 0) == expect_ok,
                  f"[self-test] {name}：期望 {'PASS' if expect_ok else 'FAIL'}，实得 rc={rc}")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def lines_for(root_apks: dict[str, bytes]) -> list[str]:
        return [f"{hashlib.sha256(d).hexdigest()}  {p}" for p, d in sorted(root_apks.items())]

    print("== check_dist_manifest.py --self-test ==")
    good = {"a.apk": b"AAA", "_archive/b.apk": b"BBB"}
    scenario("好状态：清单与字节一致且穷尽", good, lines_for(good), True)

    tampered = dict(good)
    bad_lines = lines_for(good)
    c, p = bad_lines[0][:64], bad_lines[0][66:]
    flipped = ("0" if c[0] != "0" else "1") + c[1:]
    bad_lines[0] = f"{flipped}  {p}"
    scenario("篡改一位 hex（等价 shasum -c 的 FAILED）", tampered, bad_lines, False)

    extra = dict(good)
    extra["new.apk"] = b"NNN"
    scenario("dist 下多出一个没记录的 APK", extra, lines_for(good), False)

    scenario("清单指向不存在的文件（孤儿行）", good,
             lines_for(good) + [f"{'0' * 64}  ghost.apk"], False)

    dupe = lines_for(good) + [lines_for(good)[0].replace(lines_for(good)[0][:64], "f" * 64)]
    scenario("同一路径两行（`>>` 追加的后果）", good, dupe, False)

    scenario("格式非法（缺一个空格）", good, [lines_for(good)[0].replace("  ", " ", 1),
                                            lines_for(good)[1]], False)

    # 未跟踪的超限包按实际字节入册：其余检查全过，只有体积规则能挡住它
    h = hashlib.sha256()
    for _ in range(101):
        h.update(b"\0" * (1024 * 1024))
    scenario("未跟踪且超 100 MB 的包（记了也推不上去）", good,
             lines_for(good) + [f"{h.hexdigest()}  big.apk"], False,
             untracked={"big.apk": 101 * 1024 * 1024})

    tmp = Path(tempfile.mkdtemp(prefix="distsum-selftest-nogit-"))
    try:
        (tmp / "tools" / "verify").mkdir(parents=True)
        shutil.copy(script, tmp / "tools" / "verify" / script.name)
        build(tmp, good, lines_for(good))
        rc = run_check(tmp)
        check(rc == 0, f"[self-test] 无 git 元数据（git archive 解包）时退回目录扫描：实得 rc={rc}")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print(f"SUMMARY: passed={_passed} failed={_failed} warned=0")
    return 1 if _failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="dist/SHA256SUMS 一致性校验 / 重生成")
    ap.add_argument("--regen", action="store_true",
                    help="按当前包字节重生成清单，然后校验")
    ap.add_argument("--self-test", action="store_true",
                    help="在临时目录伪造 8 种状态，验证判定逻辑双向准确（不动仓库）")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    return regen() if args.regen else check_manifest()


if __name__ == "__main__":
    sys.exit(main())
