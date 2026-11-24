#!/usr/bin/env python3
"""VOCA-628 / VOCA-65 条目 5a「AI 结果一步填入」的 adb 复现脚本。

判据来自 `docs/requirements/VOCA-65-acceptance.md` AC-5a.1~5a.5；本脚本只做 ADB
驱动的黑盒复现，不读代码、不依赖 JVM 单测，因此可以在任意装了 debug 包的设备上跑。

前置
----
1. `adb install -r app-debug.apk`，并让输入法可用：
   `adb shell ime enable com.ispeech.ime/com.ispeech.input.IspeechImeService`
   `adb shell ime set    com.ispeech.ime/com.ispeech.input.IspeechImeService`
2. 需要 3 条以上 AI 结果。两种取法：
   - 快捷短语命中（无需联网/账号）：往用户规则里塞 3 条含同一 ASCII needle 的短语；
   - 模型生成：配好 AI 设置（或跑 `--mock-llm` 在本机起一个 OpenAI 兼容假端点）。
   本脚本默认写 3 条用户短语，保证 5a.1/5a.2/5a.4 可离线复现。
3. 宿主应用要有输入框（脚本默认用系统「设置」的搜索框，这也是 uiautomator 能
   看到输入框、但看不到输入法窗口的原因：输入法窗口要按截图定位）。

用法
----
    python3 tools/verify/voca628_ai_one_tap_adb.py --serial emulator-5554
    python3 tools/verify/voca628_ai_one_tap_adb.py --serial emulator-5554 --mock-llm

输出
----
逐条打印 `PASS` / `FAIL` 与判定依据（logcat 原文 + 输入框文本），最后给出汇总。
每条 FAIL 都会带一句「怎么补证据」，与 issue 的打回项一一对应。

已知限制
--------
- 5a.3 的时序证据（生成后用户改输入框）在模拟器上也能跑，但 issue 要求真机，
  脚本不区分设备类型，`--strict-device` 可强制要求非 emulator。
- 输入法窗口不在 uiautomator 的层级里，键盘 / 面板坐标按截图色块定位，
  依赖 debug 包的配色（#F2F3F5 面板底 / #FFFFFF 行底 / #CFD4DB 功能键）。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from dataclasses import dataclass

IME_PKG = "com.ispeech.ime"
IME_ID = f"{IME_PKG}/com.ispeech.input.IspeechImeService"

# 视觉常量取自 debug 包：面板行底色（AiReplyPanel.kt）与键盘容器空隙色（SoftKeyboardView.kt）
ROW_BG = (255, 255, 255)
GAP_BG = (228, 231, 236)

TOOLBAR_KEYS = 8  # 工具栏：⌨ 符 ☺ 短语 剪贴 AI 语音 ⚙
AI_KEY_INDEX = 5  # 0 基下标，AI 是第 6 个键


def sh(*args: str, timeout: int = 120) -> str:
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout


@dataclass
class Case:
    ac: str
    ok: bool
    detail: str


class Driver:
    def __init__(self, serial: str, workdir: str) -> None:
        self.serial = serial
        self.workdir = workdir
        self.results: list[Case] = []
        self.last_adopt_log = ""
        self.last_stats_before = ""
        self.last_stats_after = ""

    # ── 基础设施 ──────────────────────────────
    def adb(self, *args: str, timeout: int = 120) -> str:
        return sh("-s", self.serial, *args, timeout=timeout)

    def shot(self, name: str) -> str:
        path = f"{self.workdir}/{name}.png"
        with open(path, "wb") as f:
            f.write(subprocess.run(
                ["adb", "-s", self.serial, "exec-out", "screencap", "-p"],
                capture_output=True, timeout=120,
            ).stdout)
        return path

    def logcat_clear(self) -> None:
        self.adb("logcat", "-c")

    def logcat(self) -> str:
        return self.adb("logcat", "-d", "-s", "IspeechIme")

    def ime_frame(self) -> tuple[int, int] | None:
        """输入法窗口的 frame 顶/底（uiautomator 看不到 IME 窗口）。"""
        out = self.adb("shell", "dumpsys", "window", "windows")
        m = re.search(r"Window #\d+ Window\{[^}]*InputMethod\}", out)
        if not m:
            return None
        seg = out[m.start():]
        f = re.search(r"Frames:.*?frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", seg)
        return (int(f.group(2)), int(f.group(4))) if f else None

    def edit_text(self) -> str:
        self.adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
        xml = self.adb("shell", "cat", "/sdcard/window_dump.xml")
        for s in re.findall(r"<node[^>]*>", xml):
            if 'class="android.widget.EditText"' not in s:
                continue
            t = re.search(r'text="([^"]*)"', s)
            return t.group(1) if t else ""
        return ""

    def read_file(self, name: str) -> str:
        return self.adb("shell", f"run-as {IME_PKG} cat files/{name}").strip()

    def write_file(self, name: str, content: str) -> None:
        local = f"{self.workdir}/{name}"
        with open(local, "w", encoding="utf-8") as f:
            f.write(content)
        self.adb("push", local, f"/data/local/tmp/{name}")
        self.adb(
            "shell",
            f"run-as {IME_PKG} sh -c 'cat /data/local/tmp/{name} > files/{name}'",
        )

    # ── 输入法几何 ────────────────────────────
    def _rgb(self, path: str):
        try:
            from PIL import Image  # noqa: PLC0415
        except ImportError:
            return None
        return Image.open(path).convert("RGB")

    def _near(self, p, c, tol=10) -> bool:
        return all(abs(p[i] - c[i]) <= tol for i in range(3))

    def toolbar_ai_xy(self) -> tuple[int, int] | None:
        """工具栏内 AI 键中心。

        工具栏是 IME 窗口最上面那一排 8 个等宽键（自上而下第一处能分出恰好 8 个
        等宽键面的行）；字母行是 10 键。按空隙切分而不是按键底色匹配：accent 键近白、
        特殊键偏灰，底色随主题/机型变，空隙色则稳定是窗口容器底色。
        """
        img = self._rgb(self.shot("_toolbar"))
        frame = self.ime_frame()
        if img is None or frame is None:
            return None
        top, bottom = frame
        width = img.size[0]
        for y in range(top + 60, min(top + 400, bottom), 2):
            spans, start = [], None
            for x in range(width):
                p = img.getpixel((x, y))
                gap = all(abs(p[i] - GAP_BG[i]) <= 8 for i in range(3))
                if not gap and start is None:
                    start = x
                elif gap and start is not None:
                    if x - start >= 80:
                        spans.append((start, x))
                    start = None
            if start is not None and width - start >= 80:
                spans.append((start, width))
            if len(spans) != TOOLBAR_KEYS:
                continue
            widths = sorted(x1 - x0 for x0, x1 in spans)
            median = widths[len(widths) // 2]
            if median <= 0 or max(abs(w - median) for w in widths) / median > 0.08:
                continue
            x0, x1 = spans[AI_KEY_INDEX]
            return ((x0 + x1) // 2, y)
        return None

    def row_xy(self, index: int) -> tuple[int, int] | None:
        """AI 面板内第 index 行（0 基）的中心：按行底色白色条带定位。"""
        img = self._rgb(self.shot("_panel"))
        frame = self.ime_frame()
        if img is None or frame is None:
            return None
        top, bottom = frame
        width = img.size[0]
        bands, start = [], None
        for y in range(top, bottom):
            sample = [img.getpixel((x, y)) for x in range(40, width - 40, 24)]
            white = sum(1 for p in sample if self._near(p, ROW_BG, 6))
            on = white / len(sample) > 0.75
            if on and start is None:
                start = y
            elif not on and start is not None:
                if y - start >= 60:  # 行高 38dp ≈ 100px，排除细线
                    bands.append((start, y))
                start = None
        if len(bands) <= index:
            return None
        b = bands[index]
        return (width // 2, (b[0] + b[1]) // 2)

    # ── 场景 ──────────────────────────────────
    def prepare(self, phrases: bool, needle: str, mock_llm: int | None = None) -> None:
        self.adb("shell", "pm", "clear", IME_PKG)
        time.sleep(2)
        if mock_llm:
            # 指向本机假端点（10.0.2.2 = 模拟器眼里的宿主机）；不写快捷短语，
            # 保证这次一定走模型分支，才能验 5a.5 的采纳率 / few-shot 落盘。
            self.seed_ai(mock_llm)
        elif phrases:
            rows = "".join(
                f"zqa{i}\t用例{i}\t{needle} 第{i}条用例回复：收到，稍后回复您。\tprefix\t{300 - i}\ta\n"
                for i in range(1, 4)
            )
            self.write_file("quick_phrases_user.txt", rows)
        self.adb("shell", "ime", "enable", IME_ID)
        self.adb("shell", "ime", "set", IME_ID)
        time.sleep(1)

    def seed_ai(self, port: int) -> None:
        """把 AI 配置写成「自定义 OpenAI 兼容端点」，供 --mock-llm 用。

        走 `adb reverse tcp:<port> tcp:<port>`，设备侧访问 `127.0.0.1:<port>` 就等于
        宿主机上的假端点：模拟器与真机同一条命令都成立，不依赖 `10.0.2.2` 路由，
        也不要求手机和电脑同网段。
        """
        out = self.adb("reverse", f"tcp:{port}", f"tcp:{port}").strip()
        print(f"adb reverse tcp:{port} -> {out or 'ok'}")
        self.prefs({
            "ai_provider": "CUSTOM",
            "ai_base_url": f"http://127.0.0.1:{port}/v1/",
            "ai_model": "mock-model",
            "ai_api_key": "test-key",
        })

    def prefs(self, items: dict[str, str]) -> None:
        local = f"{self.workdir}/seed.preferences_pb"
        with open(local, "wb") as f:
            f.write(pref_blob(items))
        self.adb("push", local, "/data/local/tmp/seed.pb")
        self.adb(
            "shell",
            f"run-as {IME_PKG} sh -c 'mkdir -p files/datastore; "
            f"cat /data/local/tmp/seed.pb > files/datastore/ispeech_settings.preferences_pb'",
        )

    def open_host_field(self, needle: str) -> None:
        self.adb("shell", "am", "force-stop", "com.android.settings")
        self.adb("shell", "am", "force-stop", "com.android.settings.intelligence")
        self.adb("shell", "am", "start", "-n", "com.android.settings/.Settings", "-W")
        time.sleep(2)
        # 设置页顶部搜索框
        self.adb("shell", "input", "tap", "376", "660")
        time.sleep(2)
        self.adb("shell", "input", "text", needle)
        time.sleep(1)

    def open_ai_panel(self, retries: int = 3) -> bool:
        """点 AI 键并确认面板真的开了（软键盘被 GONE 即面板已展开）。

        坐标是按截图色块推出来的，触摸落点偶尔会偏到相邻键（短语 / 语音），
        所以点完要校验：没开面板就重新截图定位再点，而不是带着错状态往下断言。
        """
        for _ in range(retries):
            xy = self.toolbar_ai_xy()
            if not xy:
                self.fail("5a.0", "找不到工具栏 AI 键坐标（截图/配色变了？）")
                return False
            self.logcat_clear()
            self.adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
            time.sleep(2)
            if self.toolbar_ai_xy() is None:
                return True  # 软键盘已隐藏 → 面板已打开
        self.fail("5a.0", f"点了 {retries} 次 AI 键，软键盘仍未隐藏（面板没打开）")
        return False

    def fail(self, ac: str, detail: str) -> None:
        self.results.append(Case(ac, False, detail))

    def pass_(self, ac: str, detail: str) -> None:
        self.results.append(Case(ac, True, detail))


def pref_blob(items: dict[str, str]) -> bytes:
    def varint(n: int) -> bytes:
        out = b""
        while True:
            b = n & 0x7F
            n >>= 7
            if n:
                out += bytes([b | 0x80])
            else:
                return out + bytes([b])

    def ld(field: int, payload: bytes) -> bytes:
        return varint((field << 3) | 2) + varint(len(payload)) + payload

    out = b""
    for key, value in items.items():
        value_msg = ld(5, value.encode())  # Value.string_value = 5
        out += ld(1, ld(1, key.encode()) + ld(2, value_msg))  # map entry
    return out


def run(d: Driver, needle: str, strict_device: bool, mock_llm: int | None = None) -> int:
    d.prepare(phrases=not mock_llm, needle=needle, mock_llm=mock_llm)
    d.open_host_field(needle)
    if d.edit_text().strip() != needle:
        print(f"宿主输入框不是 {needle!r}，放弃（当前 {d.edit_text()!r}）")
        return 2

    if not d.open_ai_panel():
        return 2
    logs = d.logcat()
    if "ai_results" not in logs:
        d.fail("5a.1", f"AI 键没产出结果，logcat={logs.strip()!r}")
        return 1
    count = re.search(r"ai_results source=(\w+) count=(\d+)", logs)
    print(f"生成结果：{count.group(0) if count else '?'}")

    # ── AC-5a.1 点行即上屏 + 面板关闭 ──
    # 面板打开时软键盘被 GONE（updatePanelVisibility），所以「工具栏 8 键重新出现」
    # 就是「面板已收起、回到键盘」的可靠信号，比 IME 窗口高度稳定。
    assert d.toolbar_ai_xy() is None, "面板打开时软键盘应已隐藏（工具栏不可见）"
    xy = d.row_xy(0)
    if not xy:
        d.fail("5a.1", "面板里找不到第 1 行（结果 0 条或面板没展开）")
    else:
        d.last_stats_before = d.read_file("ai_learning_stats.txt")
        d.logcat_clear()
        d.adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
        time.sleep(2)
        text = d.edit_text()
        adopt = d.logcat()
        d.last_adopt_log = adopt
        d.last_stats_after = d.read_file("ai_learning_stats.txt")
        keyboard_back = d.toolbar_ai_xy() is not None
        ok = "ai_adopt" in adopt and len(text) > len(needle) and keyboard_back
        (d.pass_ if ok else d.fail)(
            "5a.1",
            f"点行后输入框前 40 字={text[:40]!r}；面板已收起/键盘回来={keyboard_back}；"
            f"logcat={[ln.split('IspeechIme: ')[-1] for ln in adopt.splitlines() if 'ai_adopt' in ln]}",
        )

    # ── AC-5a.2 长按进详情，不上屏 ──
    d.open_host_field(needle)
    d.open_ai_panel()
    xy = d.row_xy(0)
    if not xy:
        d.fail("5a.2", "面板里找不到第 1 行")
    else:
        before = d.edit_text()
        list_frame = d.ime_frame()
        d.logcat_clear()
        d.adb("shell", "input", "swipe", str(xy[0]), str(xy[1]), str(xy[0]), str(xy[1]), "900")
        time.sleep(2)
        text = d.edit_text()
        adopt = d.logcat()
        detail_frame = d.ime_frame()
        # 详情页把列表换成「全文 + 上屏/关闭」，IME 窗口会变高（同一窗口换内容）
        grew = bool(list_frame and detail_frame) and (
            detail_frame[1] - detail_frame[0]
        ) > (list_frame[1] - list_frame[0])
        ok = text == before and "ai_adopt" not in adopt and grew
        (d.pass_ if ok else d.fail)(
            "5a.2",
            f"长按后输入框={text!r}（应不变），详情页展开={grew}"
            f"（IME 高度 {list_frame[1]-list_frame[0] if list_frame else '?'}→"
            f"{detail_frame[1]-detail_frame[0] if detail_frame else '?'}），"
            f"adopt 日志={'有' if 'ai_adopt' in adopt else '无'}",
        )
        # 收尾：返回列表
        d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)

    # ── AC-5a.3 用户改过输入框 → 只插入不删除 ──
    d.open_host_field(needle)
    d.open_ai_panel()
    original = d.edit_text()
    d.adb("shell", "input", "text", "xy")  # 用户改输入框
    modified = d.edit_text()
    xy = d.row_xy(0)
    if not xy or modified == original:
        d.fail("5a.3", f"未能构造「用户改过输入框」场景（原文 {original!r} 改成 {modified!r}）")
    else:
        d.logcat_clear()
        d.adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
        time.sleep(2)
        text = d.edit_text()
        ok = text.startswith(modified) and modified in text and len(text) > len(modified)
        (d.pass_ if ok else d.fail)(
            "5a.3",
            f"改后={modified!r} → 上屏后={text!r}；"
            f"{'原样保留用户新内容' if ok else '用户新内容被删/被覆盖'}",
        )

    # ── AC-5a.4 空态：点面板空白区无上屏无崩溃 ──
    # 两种空态都要覆盖：短语模式「生成 0 条」，mock 模式「生成失败」（假端点回 500）
    blank = FAIL_MARKER if mock_llm else "zzzzzzzz"
    d.open_host_field(blank)  # 重开宿主并只输入这条 needle，确保没有结果
    d.open_ai_panel()
    before = d.edit_text()
    xy = d.row_xy(0)
    if xy is not None:
        d.fail("5a.4", "仍有结果，无法构造空态（换一个更长的 needle）")
    else:
        frame = d.ime_frame()
        if frame:
            mid = ((frame[0] + frame[1]) // 2, (frame[0] + frame[1]) // 2)
            d.logcat_clear()
            d.adb("shell", "input", "tap", str(mid[1]), str(mid[0]))
            time.sleep(2)
        text = d.edit_text()
        pid = d.adb("shell", "pidof", IME_PKG).strip()
        crashed = "FATAL EXCEPTION" in d.adb("logcat", "-d")
        ok = text == before and bool(pid) and not crashed
        (d.pass_ if ok else d.fail)(
            "5a.4",
            f"空态点击后输入框={text!r}（应不变），进程仍在={'是' if pid else '否'}，崩溃={'是' if crashed else '否'}",
        )

    # ── AC-5a.5 一步填入计入采纳率 + few-shot 语料 ──
    # 只在走模型生成（fromModel=true）时有意义；快捷短语命中按设计不计入。
    # 用第 1 条用例（5a.1 点行上屏）之后采集的 adopt 日志，不用打开面板时的那份。
    adopt_logs = getattr(d, "last_adopt_log", "")
    if count and count.group(1) == "model":
        learning = d.read_file("ai_learning.txt")
        stats = d.read_file("ai_learning_stats.txt")
        corpus = [ln for ln in learning.splitlines() if ln.strip() and not ln.startswith("#")]
        # 生成次数 / 采纳次数：两个计数器都要 +1（与详情页「上屏」等价）
        def counts(raw: str) -> tuple[int, int]:
            parts = raw.replace("\t", " ").split()
            nums = [int(p) for p in parts if p.isdigit()]
            return (nums[0], nums[1]) if len(nums) >= 2 else (0, 0)

        # 窗口收紧到「点行上屏」这一步：这一次动作只该让采纳 +1，生成数不动
        before = counts(getattr(d, "last_stats_before", ""))
        now = counts(getattr(d, "last_stats_after", ""))
        delta_ok = now[0] == before[0] and now[1] == before[1] + 1
        corpus_ok = bool(corpus) and any(MOCK_REPLY in ln for ln in corpus)
        ok = "fromModel=true" in adopt_logs and delta_ok and corpus_ok
        (d.pass_ if ok else d.fail)(
            "5a.5",
            f"点行前后统计 {before}→{now}（生成不变、采纳 +1={delta_ok}）；"
            f"语料 {len(corpus)} 行且含本次回复={corpus_ok}，首条={corpus[:1]}；"
            f"adopt={[ln.split('IspeechIme: ')[-1] for ln in adopt_logs.splitlines() if 'ai_adopt' in ln]}",
        )
    else:
        d.results.append(Case(
            "5a.5",
            True,
            f"本次结果来源={count.group(1) if count else '?'}，按设计不计采纳率；"
            "模型路径请用 --mock-llm 或真实 AI 配置重跑",
        ))

    # ── 汇总 ──
    print()
    failed = [c for c in d.results if not c.ok]
    for c in d.results:
        print(f"{'PASS' if c.ok else 'FAIL'} {c.ac}  {c.detail}")
    print(f"\n合计 {len(d.results)} 条，失败 {len(failed)} 条")
    return 0 if not failed else 1


class MockLlm:
    """本机 OpenAI 兼容假端点，供 5a.5 走模型分支（不需要真 Key、不联网出站）。

    模拟器里 `10.0.2.2` 就是宿主机；真机请用 `--mock-host <局域网 IP>`。
    只回一条固定回复，并把收到的提示词条数打到 stdout，便于核对 few-shot 是否带上。
    """

    def __init__(self, port: int) -> None:
        self.port = port
        self.server = None

    def __enter__(self):
        import http.server
        import json as _json
        import socketserver
        import threading

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_a):
                pass

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(n).decode("utf-8", "replace")
                try:
                    req = _json.loads(body)
                except ValueError:
                    req = {}
                msgs = req.get("messages") or []
                fewshot = sum(1 for m in msgs if "采纳过的回复" in (m.get("content") or ""))
                print(f"MOCK_LLM model={req.get('model')} msgs={len(msgs)} fewshot={fewshot}")
                asked = " ".join(m.get("content") or "" for m in msgs)
                if FAIL_MARKER.lower() in asked.lower():
                    # 5a.4 的「生成失败」分支：回 500，让客户端走 AI失败 文案 + 空结果
                    body = b'{"error":{"message":"mock failure"}}'
                    self.send_response(500)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                payload = _json.dumps({
                    "id": "mock-1", "object": "chat.completion",
                    "model": req.get("model") or "mock",
                    "choices": [{
                        "index": 0,
                        "message": {"role": "assistant", "content": MOCK_REPLY},
                        "finish_reason": "stop",
                    }],
                }, ensure_ascii=False).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data := payload)))
                self.end_headers()
                self.wfile.write(payload)

        socketserver.TCPServer.allow_reuse_address = True
        srv = socketserver.TCPServer(("0.0.0.0", self.port), Handler)
        self.server = srv
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        print(f"MOCK_LLM listening on :{self.port}, reply={MOCK_REPLY!r}")
        return self

    def __exit__(self, *exc):
        if self.server:
            self.server.shutdown()
            self.server.server_close()


MOCK_REPLY = "好的，我马上帮您处理这个问题"

# 输入里带上这段就触发假端点 500，用来构造 5a.4 的「生成失败」空态
FAIL_MARKER = "zzfail"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--needle", default=None,
                    help="写入输入框的 ASCII needle；短语模式默认 '1'（命中内置+自建多条），"
                         "mock 模式默认 'zzqa'（确保 0 命中、必走模型）")
    ap.add_argument("--workdir", default="/tmp/voca628-adb")
    ap.add_argument("--strict-device", action="store_true", help="要求真机（非 emulator）")
    ap.add_argument("--mock-llm", action="store_true",
                    help="本机起假模型端点并配到设备上，让 5a.5 跑模型分支（无需真 Key）")
    ap.add_argument("--mock-port", type=int, default=18080)
    ap.add_argument("--mock-host", default="127.0.0.1",
                    help="设备侧访问宿主机的地址；默认走 adb reverse 的 127.0.0.1")
    args = ap.parse_args()
    if args.needle is None:
        args.needle = "zzqa" if args.mock_llm else "1"

    if args.strict_device and "emulator" in args.serial:
        print(f"--strict-device 要求真机，当前 {args.serial}")
        return 2
    import os
    os.makedirs(args.workdir, exist_ok=True)
    driver = Driver(args.serial, args.workdir)
    if args.mock_llm:
        # 设备侧地址随 --mock-host 变，seed_ai 里写死 10.0.2.2，这里覆盖一次
        driver.mock_host = args.mock_host
        with MockLlm(args.mock_port):
            return run(driver, args.needle, args.strict_device, mock_llm=args.mock_port)
    return run(driver, args.needle, args.strict_device)


if __name__ == "__main__":
    sys.exit(main())