#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""build_han_assets.py —— 从上游 OpenCC 生成 iSpeech 简繁映射资产

输入（按优先级）：
  1. `--st <path> --ts <path>` 指定的本地上游文件；
  2. 无参数时从 CDN 镜像下载（raw.githubusercontent.com 在部分网络环境不可达，
     默认用 jsDelivr 镜像，内容与上游仓库同路径同字节，脚本会校验 sha256）。

输出（覆盖写入，确定性——不含时间戳，同样输入必得同样字节）：
  app/src/main/assets/han_s2t.txt   简体 -> 繁体（源自 OpenCC STCharacters.txt）
  app/src/main/assets/han_t2s.txt   繁体 -> 简体（源自 OpenCC TSCharacters.txt）

裁剪口径（只保留与「常用字」相关的条目，控制体积）：
  - 只保留「简体侧字符属于 GB2312 汉字集（GB2312 0xB0A1-0xF7FE，共 6763 个
    常用汉字，含一级 3755 + 二级 3008）」的条目：
      * s2t：源字符（简体）必须属于 GB2312 汉字集；
      * t2s：任一目标字符（简体）属于 GB2312 汉字集即可（繁体源字符本身多半
        只存在于 GBK / Big5，不参与过滤，否则覆盖会归零）。
  - 剔除「首选项与源字符相同」的条目（转换后与原字符一致，对字符级转换无意义）。
  - 目标字符不过滤，保持上游原样（顺序即上游优先级，转换时取第一项）。

用法：
  python3 tools/gen/build_han_assets.py                    # 下载 + 生成 + 报告
  python3 tools/gen/build_han_assets.py --st a.txt --ts b.txt
  python3 tools/gen/build_han_assets.py --check            # 只校验已生成资产
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"

UPSTREAM_REPO = "https://github.com/BYVoid/OpenCC"
UPSTREAM_DIR = "data/dictionary"
LICENSE = "Apache-2.0"

# 上游文件 -> (资产文件名, 上游 sha256, 转换方向说明)
PLAN = {
    "STCharacters.txt": {
        "asset": "han_s2t.txt",
        "sha256": "a0ca1601c70648cf48b33c3c6210ccbecc5c7eead4b4c3daf76587ba2c03582b",
        "direction": "简体 -> 繁体 (s2t)",
        "source_side": "key",
    },
    "TSCharacters.txt": {
        "asset": "han_t2s.txt",
        "sha256": "9ff46a7d30e5765375eb13d33f2b03a34d298913caf2b120380679f33ae1642d",
        "direction": "繁体 -> 简体 (t2s)",
        "source_side": "value",
    },
}

MIRRORS = (
    "https://cdn.jsdelivr.net/gh/BYVoid/OpenCC@master/{dir}/{name}",
    "https://ghproxy.net/https://raw.githubusercontent.com/BYVoid/OpenCC/master/{dir}/{name}",
    "https://raw.githubusercontent.com/BYVoid/OpenCC/master/{dir}/{name}",
)

# 转换时实际使用的选项：首项（上游首项即默认首选）
PRIMARY_INDEX = 0


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def download(name: str) -> bytes:
    """按镜像顺序下载上游文件，返回字节；全部失败抛异常。"""
    last_error: Exception | None = None
    for template in MIRRORS:
        url = template.format(dir=UPSTREAM_DIR, name=name)
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                data = resp.read()
            if not data:
                raise RuntimeError("空响应")
            return data
        except Exception as exc:  # noqa: BLE001 —— 逐个镜像尝试，最后统一报错
            last_error = exc
            print(f"WARN: 下载失败 {url} -> {exc}", file=sys.stderr)
    raise RuntimeError(f"无法下载 {name}: {last_error}")


def gb2312_bytes(ch: str) -> bytes | None:
    """字符的 GB2312 双字节编码；不属于 GB2312 汉字集时返回 None。"""
    if len(ch) != 1:
        return None
    try:
        raw = ch.encode("gb2312")
    except UnicodeEncodeError:
        return None
    if len(raw) != 2:
        return None
    return raw


def is_common_hanzi(ch: str) -> bool:
    """是否属于 GB2312 汉字集（0xB0A1-0xF7FE，6763 个常用汉字）。"""
    raw = gb2312_bytes(ch)
    return raw is not None and 0xB0 <= raw[0] <= 0xF7 and 0xA1 <= raw[1] <= 0xFE


def is_level1_hanzi(ch: str) -> bool:
    """是否属于 GB2312 一级汉字（0xB0A1-0xD7F9，3755 个最常用字）。"""
    raw = gb2312_bytes(ch)
    return raw is not None and 0xB0 <= raw[0] <= 0xD7


def parse_upstream(text: str) -> list[tuple[str, list[str]]]:
    """解析 OpenCC 字典：`key<TAB>value1 value2 ...`，忽略空行与 # 注释。"""
    rows: list[tuple[str, list[str]]] = []
    for raw_line in text.split("\n"):
        line = raw_line.rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "\t" not in line:
            continue
        key, _, rest = line.partition("\t")
        values = [v for v in rest.split(" ") if v]
        if not key or not values:
            continue
        rows.append((key, values))
    return rows


def trim(rows: list[tuple[str, list[str]]], source_side: str) -> list[tuple[str, list[str]]]:
    """按「简体侧属于 GB2312 常用汉字集」+「首选项 != 源字符」裁剪。"""
    kept: list[tuple[str, list[str]]] = []
    for key, values in rows:
        if source_side == "key":
            if not is_common_hanzi(key):
                continue
        elif not any(is_common_hanzi(v) for v in values):
            continue
        if values[PRIMARY_INDEX] == key:
            continue  # 首选项与原字符相同：等价于不做转换
        kept.append((key, values))
    return kept


def render(
    rows: list[tuple[str, list[str]]],
    source_side: str,
    direction: str,
    upstream_name: str,
    upstream_sha: str,
) -> bytes:
    """生成资产字节：`#` 头（来源 / 许可 / sha256 / 口径）+ 数据行。"""
    # 简体侧一级（3755 最常用字）覆盖情况，仅作可读性参考，不参与转换逻辑
    level1 = sum(
        1
        for key, values in rows
        if is_level1_hanzi(key if source_side == "key" else values[PRIMARY_INDEX])
    )
    header = [
        "# 智语 iSpeech 简繁字符映射（由 tools/gen/build_han_assets.py 自动生成，请勿手改）",
        f"# 方向: {direction}",
        f"# 上游: {UPSTREAM_REPO}  {UPSTREAM_DIR}/{upstream_name}",
        f"# 上游 sha256: {upstream_sha}",
        f"# 许可: {LICENSE}",
        "# 裁剪口径: 简体侧字符属于 GB2312 汉字集(0xB0A1-0xF7FE, 6763 个常用汉字);",
        "#           剔除首选项与源字符相同的条目; 目标字符保持上游原样(顺序即优先级)",
        f"# 条目数: {len(rows)}（简体侧属于 GB2312 一级 3755 常用字的条目: {level1}）",
        "# 格式: key<TAB>value1 value2 ...（转换时取第一项）",
    ]
    body = "".join(key + "\t" + " ".join(values) + "\n" for key, values in rows)
    return ("\n".join(header) + "\n" + body).encode("utf-8")


def load_upstream(name: str, local: Path | None, expect_sha: str) -> list[tuple[str, list[str]]]:
    if local is not None:
        raw = local.read_bytes()
        origin = str(local)
    else:
        raw = download(name)
        origin = "download"
    got = sha256_bytes(raw)
    if got != expect_sha:
        print(f"WARN: {name} sha256 与脚本记录不一致（{origin}）：{got} != {expect_sha}", file=sys.stderr)
    rows = parse_upstream(raw.decode("utf-8"))
    print(f"  上游 {name}: {len(rows)} 条, sha256={got}")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 / 校验 iSpeech 简繁映射资产")
    parser.add_argument("--st", help="本地 STCharacters.txt 路径（不给则联网下载）")
    parser.add_argument("--ts", help="本地 TSCharacters.txt 路径（不给则联网下载）")
    parser.add_argument("--check", action="store_true", help="只校验已生成资产，不联网、不写文件")
    args = parser.parse_args()

    print(f"== build_han_assets.py == 仓库根：{ROOT}")

    if args.check:
        failed = False
        for name, meta in PLAN.items():
            path = ASSETS / meta["asset"]
            if not path.exists():
                print(f"FAIL: {meta['asset']} 不存在")
                failed = True
                continue
            rows = parse_upstream(path.read_text(encoding="utf-8"))
            seen: set[str] = set()
            problems: list[str] = []
            for key, values in rows:
                if key in seen:
                    problems.append(f"重复键 {key}")
                seen.add(key)
                if values[PRIMARY_INDEX] == key:
                    problems.append(f"首选项等于源字符 {key}")
                if meta["source_side"] == "key" and not is_common_hanzi(key):
                    problems.append(f"源字符不在 GB2312 汉字集 {key}")
                if meta["source_side"] == "value" and not any(is_common_hanzi(v) for v in values):
                    problems.append(f"目标字符均不在 GB2312 汉字集 {key}")
            if not rows:
                problems.append("没有任何条目")
            ok = not problems
            print(
                f"{'PASS' if ok else 'FAIL'}: {meta['asset']} 数据自洽"
                f"（{len(rows)} 条, {path.stat().st_size} 字节, sha256={sha256_file(path)}）"
            )
            for problem in problems[:5]:
                print(f"        - {problem}")
            failed = failed or not ok
        return 1 if failed else 0

    wrote: list[tuple[Path, int, str]] = []
    for name, meta in PLAN.items():
        local = None
        if name.startswith("ST") and args.st:
            local = Path(args.st)
        elif name.startswith("TS") and args.ts:
            local = Path(args.ts)
        rows = trim(load_upstream(name, local, meta["sha256"]), meta["source_side"])
        data = render(rows, meta["source_side"], meta["direction"], name, meta["sha256"])
        path = ASSETS / meta["asset"]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        wrote.append((path, len(rows), sha256_bytes(data)))

    print("生成结果：")
    for path, count, digest in wrote:
        print(f"  {path.relative_to(ROOT)}  {count} 条  {path.stat().st_size} 字节  sha256={digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
