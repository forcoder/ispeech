#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_model_host.py —— ASR 离线模型托管源可用性探测（需要联网）

用法：

    python3 tools/verify/check_model_host.py                  # 探测 build.gradle.kts 里的默认源
    python3 tools/verify/check_model_host.py <url>            # 探测指定 URL
    python3 tools/verify/check_model_host.py <url> --sha256   # 额外整包下载并校验 sha256（约 78MB）
    python3 tools/verify/check_model_host.py --self-test      # 离线自测：本地伪造各类故障，不联网
    python3 tools/verify/check_model_host.py <url> --quiet    # 只输出 SUMMARY 与 RESULT

为什么不能只看状态码：

    2026-11 的事故中，源站（腾讯云「未备案」拦截）对模型路径返回
    `302 -> https://dnspod.qcloud.com/static/webblock.html`，跟随后是
    **200 + text/html + 16,780 B**。客户端只判断 `isSuccessful`，于是把这段 HTML
    当作模型字节写进了 `.part`。所以本脚本同时校验：

      1. 重定向链上没有任何一跳落到拦截页（dnspod.qcloud.com / webblock 路径）
      2. 最终状态码 == 200
      3. Content-Type 不是 text/*、json、xml（拦截页 / 错误页的典型特征）
      4. Content-Length == 77920048
      5. 响应体前 3 字节是 bzip2 魔数 `BZh`（200 + HTML 的兜底识别）
      6. `Range: bytes=16780-` 返回 206，且 Content-Range 里的总长与期望一致
      7. 可选：整包下载后 sha256 与官方值一致（--sha256）

    任一 FAIL -> 退出码 1，可直接接 CI / 定时任务告警；全通过 -> 0。
    连不上 / 超时也按 FAIL 处理（探测不到 = 不可用）。

不依赖 cwd：仓库根用 Path(__file__).resolve().parents[2] 定位（与同目录其它脚本一致）。
只用 Python 3 标准库。
"""

from __future__ import annotations

import argparse
import hashlib
import http.client
import re
import ssl
import sys
import threading
import time
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urljoin, urlsplit

ROOT = Path(__file__).resolve().parents[2]
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"

# ── 权威元数据（2026-11-09 实测，见 docs/model-host-runbook.md） ─────────────
EXPECTED_BYTES = 77_920_048
EXPECTED_SHA256 = "da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450"
MAGIC = b"BZh"  # bzip2 文件头 "BZh9"

# 构建配置里的默认源（build.gradle.kts 解析失败时的兜底，仅用于提示）
# 必须与 app/build.gradle.kts 的默认值保持一致，否则换源后会拿旧源误判。
FALLBACK_URL = (
    "https://delivery-service-smzk.oss-cn-guangzhou.aliyuncs.com/models/asr/"
    "sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2"
)

# 腾讯云「未备案」拦截页特征
BLOCKED_HOSTS = ("dnspod.qcloud.com",)
BLOCKED_PATH_MARKERS = ("webblock",)

REDIRECT_CODES = (301, 302, 303, 307, 308)
USER_AGENT = "ispeech-model-host-probe/1.0 (SRE; +https://github.com/k2-fsa/sherpa-onnx)"
MAX_HOPS = 5

_passed = 0
_failed = 0
_warned = 0


def _count(level: str) -> None:
    global _passed, _failed, _warned
    if level == "PASS":
        _passed += 1
    elif level == "FAIL":
        _failed += 1
    elif level == "WARN":
        _warned += 1


def report(level: str, message: str) -> None:
    print(f"{level}: {message}", flush=True)
    _count(level)


def make_sink(quiet: bool):
    """quiet 模式下不打印 PASS，WARN/FAIL 仍然可见。"""
    if not quiet:
        return report

    def sink(level: str, message: str) -> None:
        _count(level)
        if level != "PASS":
            print(f"{level}: {message}", flush=True)

    return sink


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


# ─────────────────────────────────────────────────────────────
# HTTP：手写重定向（需要逐跳看到 Location，才能识别拦截页）
# ─────────────────────────────────────────────────────────────


class ProbeError(Exception):
    """连接层失败（DNS / TCP / TLS / 超时 / 非法 URL）。"""


@dataclass
class Hop:
    url: str
    status: int
    location: str | None


@dataclass
class Response:
    url: str
    status: int
    headers: dict[str, str]
    body: bytes
    hops: list[Hop] = field(default_factory=list)


def _connect(url: str, timeout: float) -> tuple[http.client.HTTPConnection, str]:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https"):
        raise ProbeError(f"不支持的 scheme：{parts.scheme!r}（只支持 http/https）")
    if not parts.hostname:
        raise ProbeError(f"URL 缺少主机名：{url!r}")
    path = parts.path or "/"
    if parts.query:
        path += "?" + parts.query
    if parts.scheme == "https":
        conn = http.client.HTTPSConnection(
            parts.hostname,
            parts.port,
            timeout=timeout,
            context=ssl.create_default_context(),
        )
    else:
        conn = http.client.HTTPConnection(parts.hostname, parts.port, timeout=timeout)
    return conn, path


def http_get(
    url: str,
    headers: dict[str, str] | None = None,
    read_bytes: int = 0,
    timeout: float = 30.0,
    follow: bool = True,
) -> Response:
    """GET（不读 body 时直接关连接），手动跟随重定向并记录每一跳。

    `Accept-Encoding: identity` 是必须的：压缩编码会让 Content-Length 变成
    「压缩后长度」，长度断言与 Range 断言都会失真。
    """
    hops: list[Hop] = []
    cur = url
    for _ in range(MAX_HOPS + 1):
        conn, path = _connect(cur, timeout)
        req_headers = {
            "User-Agent": USER_AGENT,
            "Accept": "*/*",
            "Accept-Encoding": "identity",
            "Connection": "close",
        }
        if headers:
            req_headers.update(headers)
        try:
            conn.request("GET", path, headers=req_headers)
            resp = conn.getresponse()
            resp_headers = {k.lower(): v for k, v in resp.getheaders()}
            body = resp.read(read_bytes) if read_bytes > 0 else b""
            location = resp_headers.get("location")
            hops.append(Hop(url=cur, status=resp.status, location=location))
            if follow and resp.status in REDIRECT_CODES and location:
                cur = urljoin(cur, location)
                continue
            return Response(url=cur, status=resp.status, headers=resp_headers, body=body, hops=hops)
        except (OSError, http.client.HTTPException) as exc:
            raise ProbeError(f"{type(exc).__name__}: {exc}") from exc
        finally:
            conn.close()
    raise ProbeError(f"重定向超过 {MAX_HOPS} 次，疑似环路")


def stream_sha256(url: str, timeout: float = 30.0) -> tuple[int, str]:
    """整包流式下载并计算 sha256（不落盘）。返回 (字节数, 十六进制摘要)。"""
    cur = url
    for _ in range(MAX_HOPS + 1):
        conn, path = _connect(cur, timeout)
        try:
            conn.request(
                "GET",
                path,
                headers={
                    "User-Agent": USER_AGENT,
                    "Accept": "*/*",
                    "Accept-Encoding": "identity",
                    "Connection": "close",
                },
            )
            resp = conn.getresponse()
            if resp.status in REDIRECT_CODES:
                location = resp.getheader("Location")
                if location:
                    cur = urljoin(cur, location)
                    continue
            if resp.status != 200:
                raise ProbeError(f"整包下载返回 HTTP {resp.status}")
            digest = hashlib.sha256()
            total = 0
            while True:
                chunk = resp.read(1 << 16)
                if not chunk:
                    break
                digest.update(chunk)
                total += len(chunk)
            return total, digest.hexdigest()
        except (OSError, http.client.HTTPException) as exc:
            raise ProbeError(f"{type(exc).__name__}: {exc}") from exc
        finally:
            conn.close()
    raise ProbeError(f"重定向超过 {MAX_HOPS} 次，疑似环路")


# ─────────────────────────────────────────────────────────────
# 判定
# ─────────────────────────────────────────────────────────────


def short(url: str, limit: int = 120) -> str:
    """日志用短 URL：去掉 query（GitHub 的签名串很长且含 sig/jwt），超长截断。"""
    parts = urlsplit(url)
    base = f"{parts.scheme}://{parts.netloc}{parts.path}"
    if parts.query:
        base += "?…"
    return base if len(base) <= limit else base[: limit - 1] + "…"


def is_blocked(url: str) -> bool:
    """拦截页判定：主机命中黑名单，或路径里带 webblock 之类标记。"""
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    if any(host == b or host.endswith("." + b) for b in BLOCKED_HOSTS):
        return True
    lowered_path = parts.path.lower()
    return any(marker in lowered_path for marker in BLOCKED_PATH_MARKERS)


def _is_textual_content_type(ct: str) -> bool:
    if not ct:
        return False
    if ct.startswith("text/"):
        return True
    return ct in ("application/json", "application/xml") or ct.endswith(
        ("+json", "+xml")
    )


def _parse_content_range(value: str) -> int | None:
    """`bytes 16780-77920047/77920048` -> 77920048（`*` 或格式不符返回 None）。"""
    m = re.match(r"\s*bytes\s+\d+-\d+/(\d+)\s*$", value or "")
    return int(m.group(1)) if m else None


def probe(
    url: str,
    expect_bytes: int = EXPECTED_BYTES,
    expect_sha256: str = EXPECTED_SHA256,
    timeout: float = 30.0,
    deep: bool = False,
    sink=report,
) -> bool:
    """对 url 跑完整断言，返回 True 表示全部通过。"""
    ok = True

    def fail(message: str) -> None:
        nonlocal ok
        ok = False
        sink("FAIL", message)

    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        fail(f"URL 不合法：{url!r}（需要 http(s)://host/path）")
        return False

    if parts.scheme == "http":
        sink(
            "WARN",
            f"源使用明文 HTTP（{parts.hostname}）：传输可被中间人替换，"
            f"完整性只能靠客户端 sha256 兜底（见 VOCA-34）；建议升级 HTTPS",
        )

    # 1) 主请求：只读 16 字节用于魔数判定，其余直接断开
    try:
        main = http_get(url, read_bytes=16, timeout=timeout)
    except ProbeError as exc:
        fail(f"无法访问 {url}：{exc}")
        return False

    chain = " -> ".join(f"{h.status} {short(h.url)}" for h in main.hops)
    if len(main.hops) > 1:
        sink("PASS", f"重定向 {len(main.hops) - 1} 次，最终 {short(main.url)}")
    else:
        sink("PASS", "无重定向")

    # 2) 拦截页：任何一跳落到 dnspod / webblock 都直接判死（其余断言已无意义）
    blocked = [h.url for h in main.hops if is_blocked(h.url)] or (
        [main.url] if is_blocked(main.url) else []
    )
    if blocked:
        fail(f"被重定向到拦截页：{short(blocked[0])}（链路：{chain}）")
        return False

    # 3) 最终状态码
    if main.status != 200:
        fail(f"最终状态码 {main.status}，期望 200（链路：{chain}）")
        return False
    sink("PASS", "最终状态码 200")

    # 4) Content-Type
    content_type = (main.headers.get("content-type") or "").split(";")[0].strip().lower()
    if not content_type:
        sink("WARN", "未返回 Content-Type：无法从头部识别类型，只能靠包体魔数判定")
    elif _is_textual_content_type(content_type):
        fail(
            f"Content-Type={content_type}：是文本响应，不是模型包 "
            f"（200 + HTML 的隐性故障就是这样发生的）"
        )
    else:
        sink("PASS", f"Content-Type={content_type}")

    # 5) Content-Length
    length_raw = main.headers.get("content-length")
    if length_raw is None:
        fail("未返回 Content-Length：无法证明拿到的是完整包（客户端也无法判断是否下完）")
    else:
        try:
            length = int(length_raw)
        except ValueError:
            fail(f"Content-Length 不是整数：{length_raw!r}")
        else:
            if length != expect_bytes:
                fail(
                    f"Content-Length={length}，期望 {expect_bytes}"
                    f"（差 {length - expect_bytes:+d} 字节：源上不是同一个包 / 被截断）"
                )
            else:
                sink("PASS", f"Content-Length={length}（与期望一致）")

    # 6) 包体魔数：状态码 / 头都能骗人，字节不会
    if main.body[: len(MAGIC)] == MAGIC:
        sink("PASS", f"包体魔数 {MAGIC!r}（bzip2）")
    else:
        head = main.body[:32]
        fail(
            f"包体开头不是 bzip2 魔数 {MAGIC!r}，实际 {head!r}"
            f"（200 + HTML / 错误页的隐性故障）"
        )

    # 7) Range：客户端断点续传依赖它
    try:
        ranged = http_get(
            url, headers={"Range": "bytes=16780-"}, read_bytes=0, timeout=timeout
        )
    except ProbeError as exc:
        fail(f"Range 请求失败：{exc}")
    else:
        if ranged.status != 206:
            fail(
                f"Range: bytes=16780- 返回 {ranged.status}，期望 206"
                f"（不支持/不理 Range 的源会让客户端续传逻辑退化或 416）"
            )
        elif is_blocked(ranged.url):
            fail(f"Range 请求被重定向到拦截页：{ranged.url}")
        else:
            sink("PASS", "Range: bytes=16780- -> 206")
        total = _parse_content_range(ranged.headers.get("content-range", ""))
        if total is None:
            fail(
                "Range 响应缺少可解析的 Content-Range"
                f"（实际 {ranged.headers.get('content-range', '')!r}）：无法确认整包长度"
            )
        elif total != expect_bytes:
            fail(f"Content-Range 总长 {total}，期望 {expect_bytes}")
        else:
            sink("PASS", f"Content-Range 总长 {total}（与期望一致）")

    # 8) 可选：整包 sha256
    if deep:
        started = time.time()
        try:
            size, digest = stream_sha256(url, timeout=timeout)
        except ProbeError as exc:
            fail(f"整包下载失败：{exc}")
        else:
            elapsed = time.time() - started
            speed = size / elapsed / 1024 if elapsed > 0 else 0
            if size != expect_bytes:
                fail(f"整包下载 {size} 字节，期望 {expect_bytes}")
            else:
                sink("PASS", f"整包下载 {size} 字节（{elapsed:.1f}s，{speed:.0f} KB/s）")
            if digest == expect_sha256:
                sink("PASS", f"sha256={digest}")
            else:
                fail(f"sha256={digest}，期望 {expect_sha256}（内容被替换或版本不符）")

    return ok


# ─────────────────────────────────────────────────────────────
# 离线自测：本地伪造「正常源 / 200+HTML / 假二进制 / 拦截跳转 / 截断 / 无 Range」
# ─────────────────────────────────────────────────────────────


FIXTURE = b"BZh9" + bytes(range(256)) * 78  # 19972 字节（> 16780，Range 用例才有意义），开头是 bzip2 魔数


class _SelfTestHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 静音
        pass

    def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802 (http.server 约定)
        path = urlsplit(self.path).path
        if path == "/ok.bin":
            rng = self.headers.get("Range", "")
            m = re.match(r"bytes=(\d+)-$", rng or "")
            if m:
                start = int(m.group(1))
                if start >= len(FIXTURE):
                    self.send_response(416)
                    self.send_header("Content-Length", "0")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    return
                body = FIXTURE[start:]
                self._send(
                    206,
                    body,
                    "application/octet-stream",
                    {"Content-Range": f"bytes {start}-{len(FIXTURE) - 1}/{len(FIXTURE)}"},
                )
            else:
                self._send(200, FIXTURE, "application/octet-stream")
        elif path == "/html":
            self._send(200, b"<html><body>blocked</body></html>", "text/html; charset=utf-8")
        elif path == "/fake-bin":
            body = b"<!DOCTYPE html><html>blocked</html>"
            self._send(200, body, "application/octet-stream")
        elif path == "/redirect-block":
            self.send_response(302)
            self.send_header("Location", "/static/webblock.html")
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()
        elif path == "/static/webblock.html":
            self._send(200, b"<html>blocked</html>", "text/html; charset=utf-8")
        elif path == "/truncated":
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(FIXTURE) * 2))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(FIXTURE)
        elif path == "/norange":
            # 不认 Range：永远 200 + 整包
            self._send(200, FIXTURE, "application/octet-stream")
        else:
            self._send(404, b"not found", "text/plain")


def run_self_test() -> int:
    """在本地起一个 HTTP 服务，验证判定逻辑对各类故障都判红、对正常源判绿。"""
    server = ThreadingHTTPServer(("127.0.0.1", 0), _SelfTestHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    expect_sha = hashlib.sha256(FIXTURE).hexdigest()
    cases = [
        ("/ok.bin", True, "正常源（200 / CL 一致 / bzip2 / 206）"),
        ("/html", False, "200 + text/html 拦截页"),
        ("/fake-bin", False, "200 + octet-stream 但包体是 HTML"),
        ("/redirect-block", False, "302 -> webblock 拦截页"),
        ("/truncated", False, "Content-Length 与期望不符"),
        ("/norange", False, "不支持 Range（回 200）"),
    ]

    bad = 0
    print(f"== self-test == 本地假源 http://127.0.0.1:{port}")
    try:
        for path, should_pass, label in cases:
            collected: list[tuple[str, str]] = []

            def sink(level: str, message: str, _acc=collected) -> None:
                _acc.append((level, message))

            got = probe(
                f"http://127.0.0.1:{port}{path}",
                expect_bytes=len(FIXTURE),
                expect_sha256=expect_sha,
                timeout=5.0,
                deep=(path == "/ok.bin"),
                sink=sink,
            )
            verdict = "PASS" if got == should_pass else "FAIL"
            if verdict == "FAIL":
                bad += 1
                for level, message in collected:
                    print(f"    {level}: {message}")
            print(
                f"{verdict}: self-test {path} -> 判定 {'可用' if got else '不可用'}"
                f"（期望 {'可用' if should_pass else '不可用'}）：{label}"
            )
    finally:
        server.shutdown()
        server.server_close()

    print(f"SUMMARY: self-test cases={len(cases)} unexpected={bad}")
    return 1 if bad else 0


# ─────────────────────────────────────────────────────────────
# 入口
# ─────────────────────────────────────────────────────────────


def gradle_default_url() -> str | None:
    """从 app/build.gradle.kts 解析 `asrModelUrl` 的默认值。"""
    if not GRADLE_FILE.is_file():
        return None
    text = GRADLE_FILE.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"asrModelUrl: String\s*=.*?\?:\s*\"([^\"]+)\"", text, re.S)
    return m.group(1) if m else None


def main() -> int:
    parser = argparse.ArgumentParser(
        description="ASR 离线模型托管源可用性探测（退出码 0=可用，1=不可用）",
    )
    parser.add_argument("url", nargs="?", help="模型 URL；省略时取 build.gradle.kts 的默认源")
    parser.add_argument("--sha256", dest="deep", action="store_true", help="整包下载并校验 sha256（约 78MB）")
    parser.add_argument("--timeout", type=float, default=30.0, help="单次请求超时秒数（默认 30）")
    parser.add_argument("--expect-bytes", type=int, default=EXPECTED_BYTES, help="期望字节数")
    parser.add_argument("--self-test", action="store_true", help="离线自测（本地伪造各类故障）")
    parser.add_argument("--quiet", action="store_true", help="只输出 SUMMARY / RESULT")
    args = parser.parse_args()

    if args.self_test:
        return run_self_test()

    url = args.url or gradle_default_url() or FALLBACK_URL
    source = "命令行参数" if args.url else f"{rel(GRADLE_FILE)} 的默认源"
    print(f"== check_model_host.py == 探测 {url}（来源：{source}）")

    sink = make_sink(args.quiet)
    ok = probe(
        url,
        expect_bytes=args.expect_bytes,
        timeout=args.timeout,
        deep=args.deep,
        sink=sink,
    )
    print(f"SUMMARY: passed={_passed} failed={_failed} warned={_warned}")
    if ok and _failed == 0:
        print("RESULT: 源可用（exit 0）")
        return 0
    print("RESULT: 源不可用（exit 1）—— 处置见 docs/model-host-runbook.md")
    return 1


if __name__ == "__main__":
    sys.exit(main())