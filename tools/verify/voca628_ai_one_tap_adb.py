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
3. 宿主应用要有输入框：脚本先试系统「设置」首页搜索框，没有就退到 Chrome 地址栏，
   输入框坐标按 uiautomator 层级定位（uiautomator 看得到宿主输入框、看不到输入法窗口，
   所以键盘 / 面板只能按截图定位）。

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
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass

IME_PKG = "com.ispeech.ime"
IME_ID = f"{IME_PKG}/com.ispeech.input.IspeechImeService"

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
        self.keyboard_baseline = 0
        self.last_pick_text = ""
        self.row_offset = None
        self.ai_key_xy = None
        self.needle = ""
        # 流式抓 logcat 到文件：模拟器上 GMS / Chrome 的噪声能在 3 秒内把 2 MiB 环形
        # 缓冲刷掉几十轮，`logcat -d -s IspeechIme` 经常什么都读不到，于是「真的上屏了」
        # 会被误判成「没产生结果」。边产生边落盘就不会丢。
        self.logcat_path = f"{workdir}/logcat.txt"
        self._logcat_proc = None
        self._logcat_mark = 0

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

    def start_logcat(self) -> None:
        """开始把设备日志持续写进 [logcat_path]。"""
        self.stop_logcat()
        with open(self.logcat_path, "wb") as f:
            self._logcat_proc = subprocess.Popen(
                ["adb", "-s", self.serial, "logcat", "-v", "time"],
                stdout=f, stderr=subprocess.DEVNULL,
            )
        time.sleep(1.5)
        self._logcat_mark = 0
        self.logcat_clear()

    def stop_logcat(self) -> None:
        if self._logcat_proc and self._logcat_proc.poll() is None:
            self._logcat_proc.terminate()
            try:
                self._logcat_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._logcat_proc.kill()
        self._logcat_proc = None

    def logcat_clear(self) -> None:
        """把「从这里往后看」的位置挪到文件末尾（等价 logcat -c，但不打环）。"""
        try:
            self._logcat_mark = os.path.getsize(self.logcat_path)
        except OSError:
            self._logcat_mark = 0

    def logcat(self) -> str:
        """[logcat_clear] 之后新增的日志。"""
        try:
            with open(self.logcat_path, "r", encoding="utf-8", errors="replace") as f:
                f.seek(self._logcat_mark)
                return f.read()
        except OSError:
            return ""

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
        """把文本写进应用私有 filesDir。

        `pm clear` 之后 `files/` 目录本身不存在（应用还没建过），所以先 mkdir -p，
        否则 `cat > files/x` 直接以「No such file or directory」失败，且 run-as 的
        报错被 subprocess 吞进 stdout 里看不见 —— 曾经因此让「短语一条都没命中」看起来
        像产品缺陷。
        """
        local = f"{self.workdir}/{name}"
        with open(local, "w", encoding="utf-8") as f:
            f.write(content)
        self.adb("push", local, f"/data/local/tmp/{name}")
        self.adb(
            "shell",
            f"run-as {IME_PKG} sh -c 'mkdir -p files && cat /data/local/tmp/{name} > files/{name}'",
        )

    # ── 输入法几何 ────────────────────────────
    def _rgb(self, path: str):
        """读截图；没有 Pillow 时返回 None（调用方给安装提示）。"""
        try:
            from PIL import Image  # noqa: PLC0415
        except ImportError:
            return None
        return Image.open(path).convert("RGB")

    def density(self) -> float:
        """屏幕缩放比（dp → px）。`wm density` 同样可能打印物理 + 覆盖两行。"""
        out = self.adb("shell", "wm", "density")
        nums = re.findall(r"(\d+)", out)
        return (int(nums[-1]) if nums else 440) / 160.0

    def panel_top(self) -> int | None:
        f = self.ime_frame()
        return f[0] if f else None

    def tap_result(self, y: int) -> None:
        """点面板里 y 这一行（x 取屏幕 1/4 处，避开展开详情用的行尾「…」）。"""
        self.adb("shell", "input", "tap", str(self.screen_width() // 4), str(y))

    def find_result_y(self) -> int | None:
        """自校准：在面板内自上而下试几个候选纵坐标，谁点出了 `ai_adopt` 就是结果行。

        为什么不按颜色 / 布局常量算：结果行的实际像素高度随设备密度换算（本机 38dp
        实测 94px，按 3.25 换算会算成 123px），行底色又会随按下态 / 主题变；写死任何
        一个都会在换机型后落到行外。这里用「点了到底有没有上屏」这个产品行为本身来
        定位 —— 拿到了 `ai_adopt` 才算找到，定位与断言是同一条证据，不会自欺。

        代价是要先点一次（会真的上屏一条），所以调用方应在 5a.1 里做这件事，
        把结果留到后面的用例复用。
        """
        top = self.panel_top()
        if top is None:
            return None
        for off in (110, 100, 130, 90, 150, 80, 170, 200, 230):
            y = top + off
            if y >= self.ime_frame()[1]:
                continue
            self.logcat_clear()
            self.tap_result(y)
            time.sleep(2.2)
            if "ai_adopt" in self.logcat():
                self.row_offset = y - top
                return y
            # 没上屏：若面板已被点掉（误触空白 / 关闭），重新打开再试
            if not self.keyboard_shown():
                continue
            if self.panel_top() is None:
                return None
        return None

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

    def find_edit_bounds(self) -> tuple[int, int] | None:
        """宿主应用里第一个 EditText 的中心（坐标从 uiautomator 层级读，不写死）。

        不同系统版本的「设置」页差异很大：Android 14 的部分 AOSP 构建首页没有搜索框，
        直接写死 (376,660) 会点到空白处。这里按层级找，找不到就让调用方换宿主。
        """
        self.adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
        xml = self.adb("shell", "cat", "/sdcard/window_dump.xml")
        for s in re.findall(r"<node[^>]*>", xml):
            if 'class="android.widget.EditText"' not in s:
                continue
            m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            if not m:
                continue
            x0, y0, x1, y1 = (int(v) for v in m.groups())
            if x1 - x0 < 40 or y1 - y0 < 20:
                continue
            return ((x0 + x1) // 2, (y0 + y1) // 2)
        return None

    # 宿主候选，按「启动快、必有输入框」排序。不同 AOSP 版本差异很大：Android 14 的
    # 设置首页没有搜索框，Chrome 会弹「未连接到互联网」/ ANR，所以不能只赌一家。
    HOSTS = (
        ("com.android.settings", "com.android.settings/.Settings"),
        ("com.android.chrome",
         "-a android.intent.action.VIEW -d about:blank"),
        ("com.google.android.apps.messaging",
         "com.google.android.apps.messaging/.ui.ConversationListActivity"),
        ("com.google.android.contacts",
         "com.google.android.contacts/com.android.contacts.activities.PeopleActivity"),
    )

    def open_host_field(self, needle: str) -> None:
        """开宿主应用 → 点输入框 → 输入 needle，全程按层级定位，不写死坐标。"""
        self.needle = needle
        xy = None
        for pkg, launch in self.HOSTS:
            self.adb("shell", "am", "force-stop", pkg)
            parts = ["shell", "am", "start"] + launch.split() + ["-W"]
            self.adb(*parts)
            time.sleep(2.5)
            xy = self.find_edit_bounds()
            if xy is not None:
                break
        if xy is None:
            raise SystemExit(
                "宿主里找不到可输入的 EditText（试过 " +
                ", ".join(p for p, _ in self.HOSTS) + "）；"
                "请手动打开一个带输入框的应用后重跑，或用 --host 指定"
            )
        self.adb("shell", "input", "tap", str(xy[0]), str(xy[1]))
        time.sleep(2)
        # 先清空再输入：宿主（Chrome 地址栏）里已有文本时 `input text` 是「追加」，
        # 实测会得到 `about:blankzzqa`，于是调用方那句 `edit_text() == needle` 永远不成立。
        self.adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        self.adb("shell", "input", "keyevent", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        self.adb("shell", "input", "keyevent", "KEYCODE_DEL")
        time.sleep(0.4)
        self.adb("shell", "input", "text", needle)
        time.sleep(1)

    def screen_width(self) -> int:
        """当前显示宽度（px）。

        `wm size` 在有 `override size` 时会打印两行（物理 / 覆盖），模拟器实际用的是
        后一行；只读第一行会拿到物理宽度（本机 1080 而非 1260），按它算出来的
        「AI 键中心」会偏到相邻的剪贴板键上 —— 面板确实开了，但一条结果都没有。
        """
        out = self.adb("shell", "wm", "size")
        sizes = re.findall(r"(\d+)x(\d+)", out)
        return int(sizes[-1][0]) if sizes else 1080

    def keyboard_shown(self) -> bool:
        """键盘（含工具栏 / 字母行）是否还在 —— 用「相对基准高度」判，不用绝对像素。

        IME 窗口高度 = 当前可见内容高度：键盘态最高（工具 + 页码条 + 字母行），
        面板态只剩面板（本机 1200 → 430~590px）。绝对阈值会随宿主输入区大小 / 分辨率
        漂移（本机 853px 的键盘态就曾被误判成面板态），所以基准取「打开面板前」实测的
        键盘高度，之后只要矮于它的 80% 就算键盘已收起。
        """
        f = self.ime_frame()
        if not f:
            return False
        height = f[1] - f[0]
        return height >= self.keyboard_baseline * 0.8

    def open_ai_panel(self, retries: int = 2) -> bool:
        """点工具栏 AI 键，直到 `ai_results` 日志出现（= 面板真的出了结果）。

        为什么不信「键盘收起」：空面板（未登录 / 生成失败）也会收起键盘，用高度判据会
        把「面板开了但 0 条」和「面板开了有结果」混为一谈，于是 5a.1 在空面板上失败时
        看起来像「点行不上屏」。这里直接用 `ai_results` 这个产品自身的结构化日志做判据，
        和 setOnClickListener 的实际效果强绑定。

        AI 键的绝对坐标随「候选条行数 / 页码条 / 设备密度」浮动，所以横纵都不写死：
        候选扫描点是「工具栏行 × 等分列」，谁点出 `ai_results` 谁就是 AI 键，命中后
        记进 `self.ai_key_xy` 供本轮后续复用。
        """
        frame = self.ime_frame()
        if not frame:
            self.fail("5a.0", "拿不到 IME 窗口 frame（输入法没起来？）")
            return False
        top, bottom = frame
        # 基准 = 键盘态高度（此时面板未开）。多点几次取最大值，避免候选条动画中途取样。
        baseline = 0
        for _ in range(2):
            baseline = max(baseline, (self.ime_frame() or (0, 0))[1] - (self.ime_frame() or (0, 0))[0])
            time.sleep(0.4)
        self.keyboard_baseline = baseline

        if self.ai_key_xy and self._try_tap_ai(*self.ai_key_xy):
            return True

        w = self.screen_width()
        # 工具栏在候选行之下、字母行之上；高度比例 0.17~0.30 覆盖 1~2 行候选条。
        # 候选顺序：先试最可能的「8 键等宽 × 第 6 个」（实测命中率最高的组合），
        # 再在少数几个纵坐标上扫，最后才换 7/9 键布局。总点击次数封顶 20，避免在
        # 找不到键的设备上空转十几分钟（每点一次要等 2s 落日志）。
        primary_x = w * 11 // 16
        candidates = [(primary_x, top + int((bottom - top) * f))
                      for f in (0.22, 0.20, 0.24, 0.18, 0.26)]
        for i in (5, 4, 6, 3, 7):
            candidates.append((w * i // 8, candidates[0][1]))
        for i in (5, 4):
            candidates.append((w * i // 7, candidates[1][1]))
        for x, y in candidates:
            if self._try_tap_ai(x, y):
                self.ai_key_xy = (x, y)
                return True
        self.fail("5a.0", f"点了 {retries} 轮仍没找到能产出 ai_results 的 AI 键（面板没展开）")
        return False

    def _try_tap_ai(self, x: int, y: int) -> bool:
        """点一次 (x,y)，看有没有 `ai_results`；没命中就把宿主输入框复位后返回 False。"""
        self.logcat_clear()
        self.adb("shell", "input", "tap", str(x), str(y))
        time.sleep(2.0)
        if "ai_results" in self.logcat():
            return True
        # 点歪了会往宿主里打字 / 收键盘，复位一次再继续扫
        if self.edit_text().strip() != self.needle:
            self.open_host_field(self.needle)
        elif not self.keyboard_shown():
            self.open_host_field(self.needle)
        return False

    def panel_fingerprint(self, tag: str) -> dict:
        """面板区几何 / 颜色指纹，用来区分「列表态」与「详情态」。

        详情页底部固定有「上屏」（`#5B6CFF` 主色）+「关闭」两个按钮，列表态一次都不出现
        主色；这是比 IME 高度稳的判据——面板是 `WRAP_CONTENT`，3 条列表（3*38+4dp）与
        详情（10*38dp）的高度差会被设备密度吃掉（实测模拟器 831→831，为 0）。
        """
        path = self.shot(tag)
        frame = self.ime_frame()
        if not frame:
            return {"primary_px": 0, "white_rows": 0, "h": 0, "path": path}
        try:
            from PIL import Image  # noqa: PLC0415
        except ImportError:
            return {"primary_px": 0, "white_rows": 0, "h": frame[1] - frame[0], "path": path}
        im = Image.open(path).convert("RGB")
        px = im.load()
        w, h = im.size
        top, bot = frame[0], min(frame[1], h)
        primary = 0
        white_rows = 0
        for y in range(top, bot):
            cnt = sum(1 for x in range(0, w, 4) if px[x, y] == (255, 255, 255))
            if cnt > w // 8:
                white_rows += 1
            for x in range(0, w, 2):
                if px[x, y] == (91, 108, 255):
                    primary += 1
        return {"primary_px": primary, "white_rows": white_rows, "h": bot - top, "path": path}

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
    d.start_logcat()
    try:
        rc = _run(d, needle, strict_device, mock_llm)
    finally:
        d.stop_logcat()
    report(d, rc)
    return rc


def report(d: Driver, rc: int) -> None:
    """统一在退出前把已有的逐条结果打出来。

    早退路径（宿主输入框不对 / AI 键没找到）以前直接 `return 2`，结果一条都不打印，
    在外面看就是「脚本静默退出、什么都没干」——这也让上一轮的失败看起来像脚本没跑。
    """
    if not d.results:
        print("未产出任何用例结果（脚本在 5a.0 前置阶段就停了，看上面那行原因）")
        return
    print()
    for c in d.results:
        print(f"{'PASS' if c.ok else 'FAIL'} {c.ac}  {c.detail}")
    failed = [c for c in d.results if not c.ok]
    print(f"\n合计 {len(d.results)} 条，失败 {len(failed)} 条")


def _run(d: Driver, needle: str, strict_device: bool, mock_llm: int | None = None) -> int:
    d.prepare(phrases=not mock_llm, needle=needle, mock_llm=mock_llm)
    d.open_host_field(needle)
    got = d.edit_text().strip()
    if got != needle:
        print(f"宿主输入框不是 {needle!r}，放弃（当前 {got!r}）")
        return 2

    if not d.open_ai_panel():
        return 2
    logs = d.logcat()
    if "ai_results" not in logs:
        d.fail("5a.1", f"AI 键没产出结果，logcat={logs.strip()[-400:]!r}")
        return 1
    count = re.search(r"ai_results source=(\w+) count=(\d+)", logs)
    print(f"生成结果：{count.group(0) if count else '?'}")
    source = count.group(1) if count else "?"

    # ── 定位结果行（自校准：点出 ai_adopt 的 y 就是结果行） ──
    # 面板行的实际像素位置随设备密度 / 候选条行数浮动，颜色也会随主题变，所以不写死，
    # 用「点下去有没有真的上屏」来定位；拿到的 y 会复用给 5a.2 的长按。
    d.last_stats_before = d.read_file("ai_learning_stats.txt")
    row_y = d.find_result_y()
    if row_y is None:
        d.fail("5a.1", "面板里点不中结果行（找不到一行能触发 ai_adopt）")
        return 1
    adopt_log = [ln.strip() for ln in d.logcat().splitlines() if "ai_adopt" in ln]
    text_after_pick = d.edit_text()
    keyboard_back = d.keyboard_shown()
    ok = bool(adopt_log) and len(text_after_pick) > len(needle) and keyboard_back
    (d.pass_ if ok else d.fail)(
        "5a.1",
        f"点行后输入框={text_after_pick[:40]!r}；面板已收起/键盘回来={keyboard_back}；"
        f"logcat={adopt_log}",
    )
    d.last_adopt_log = d.logcat()
    d.last_pick_text = text_after_pick

    # ── AC-5a.2 进详情页，不上屏 ──
    # AC 原文「长按第 1 行（或点行尾「…」）」是等价入口：`AiReplyPanel.buildRow` 里
    # 长按与 `textButton(ELLIPSIS)` 都绑 `showDetail(index)`。模拟器的 `input swipe`
    # 同点 900ms 不触发 `setOnLongClickListener`（实测差异 bbox 只有一个光标闪烁区），
    # 所以这里走「…」分支取证，长按分支单独如实记录、不记为 PASS。
    d.open_host_field(needle)
    d.open_ai_panel()
    before = d.edit_text()
    d.logcat_clear()
    if d.row_offset is None:
        d.fail("5a.2", "拿不到结果行坐标（5a.1 未定位成功）")
    else:
        y = d.panel_top() + d.row_offset
        list_fp = d.panel_fingerprint("5a2_list_before")
        # 行尾「…」：按钮有 10dp 左右内边距，取右侧 16dp 处稳妥落点
        dens = d.density()
        more_x = d.screen_width() - int(16 * dens)
        d.adb("shell", "input", "tap", str(more_x), str(y))
        time.sleep(2.5)
        text = d.edit_text()
        adopt = d.logcat()
        detail_fp = d.panel_fingerprint("5a2_detail_after")
        # 详情态判据：底部出现主色「上屏」按钮（列表态没有主色块），且高度 / 白块布局变化。
        # 不用「IME 高度变大」当唯一判据：面板是 WRAP_CONTENT，3 条 vs 10 行差 111px 会被
        # 设备密度吃掉；而 #5B6CFF 主色按钮在列表态一次都不出现，是详情页特有的。
        expanded = detail_fp["primary_px"] > list_fp["primary_px"] + 100
        ok = text == before and "ai_adopt" not in adopt and expanded
        (d.pass_ if ok else d.fail)(
            "5a.2",
            f"点行尾「…」后输入框={text!r}（应不变），详情页展开={expanded}"
            f"（主色像素 {list_fp['primary_px']}→{detail_fp['primary_px']}，"
            f"白块行 {list_fp['white_rows']}→{detail_fp['white_rows']}，"
            f"IME 高度 {list_fp['h']}→{detail_fp['h']}），"
            f"adopt 日志={'有' if 'ai_adopt' in adopt else '无'}",
        )
        d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.5)

        # 长按分支：如实探测，不记为 PASS（模拟器上 input swipe 不触发长按监听）
        d.open_host_field(needle)
        d.open_ai_panel()
        if d.row_offset is not None:
            y2 = d.panel_top() + d.row_offset
            lp_before = d.panel_fingerprint("5a2_longpress_before")
            d.adb("shell", "input", "swipe", str(d.screen_width() // 4), str(y2),
                  str(d.screen_width() // 4), str(y2), "900")
            time.sleep(2.5)
            lp_after = d.panel_fingerprint("5a2_longpress_after")
            lp_ok = lp_after["primary_px"] > lp_before["primary_px"] + 100
            print(f"NOTE 5a.2 长按分支：input swipe 900ms 后详情展开={lp_ok}"
                  f"（主色像素 {lp_before['primary_px']}→{lp_after['primary_px']}）"
                  f"；该分支需真机，模拟器不作为通过依据")
            if not lp_ok:
                d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
                time.sleep(1.0)

    # ── AC-5a.3 用户改过输入框 → 只插入不删除 ──
    d.open_host_field(needle)
    d.open_ai_panel()
    original = d.edit_text()
    d.adb("shell", "input", "text", "xy")
    modified = d.edit_text()
    if modified == original:
        d.fail("5a.3", f"未能构造「用户改过输入框」场景（原文 {original!r} 改成 {modified!r}）")
    else:
        d.logcat_clear()
        d.tap_result(d.panel_top() + d.row_offset)
        time.sleep(2.5)
        text = d.edit_text()
        ok = text.startswith(modified) and modified in text and len(text) > len(modified)
        (d.pass_ if ok else d.fail)(
            "5a.3",
            f"改后={modified!r} → 上屏后={text!r}；"
            f"{'原样保留用户新内容' if ok else '用户新内容被删/被覆盖'}",
        )

    # ── AC-5a.4 空态：点面板空白区无上屏无崩溃 ──
    # 两种空态都覆盖：短语模式「生成 0 条」，mock 模式「生成失败」（假端点回 500）
    blank = FAIL_MARKER if mock_llm else "zzzznohit"
    d.open_host_field(blank)
    d.open_ai_panel()
    before = d.edit_text()
    top2 = d.panel_top()
    if top2 is None:
        d.fail("5a.4", "空态下没打开面板")
    else:
        # 空面板里没有任何结果行，点面板中部空白
        mid_y = top2 + (d.ime_frame()[1] - top2) // 2
        d.logcat_clear()
        d.tap_result(mid_y)
        time.sleep(2.5)
        text = d.edit_text()
        pid = d.adb("shell", "pidof", IME_PKG).strip()
        crashed = "FATAL EXCEPTION" in d.logcat()
        adopt = "ai_adopt" in d.logcat()
        ok = text == before and bool(pid) and not crashed and not adopt
        (d.pass_ if ok else d.fail)(
            "5a.4",
            f"空态点击后输入框={text!r}（应不变），进程仍在={'是' if pid else '否'}，"
            f"崩溃={'是' if crashed else '否'}，误上屏={adopt}",
        )

    # ── AC-5a.5 一步填入计入采纳率 + few-shot 语料 ──
    # 只在走模型生成（fromModel=true）时有意义；快捷短语命中按设计不计入。
    if source == "model":
        adopt_logs = getattr(d, "last_adopt_log", "")
        learning = d.read_file("ai_learning.txt")
        stats = d.read_file("ai_learning_stats.txt")
        corpus = [ln for ln in learning.splitlines() if ln.strip() and not ln.startswith("#")]

        def counts(raw: str) -> tuple[int, int]:
            nums = [int(p) for p in raw.replace("\t", " ").split() if p.isdigit()]
            return (nums[0], nums[1]) if len(nums) >= 2 else (0, 0)

        before_s = counts(getattr(d, "last_stats_before", ""))
        now_s = counts(stats)
        delta_ok = now_s[1] >= max(1, before_s[1] + 1) and now_s[0] >= 1
        corpus_ok = bool(corpus) and any(MOCK_REPLY in ln for ln in corpus)
        ok = "fromModel=true" in adopt_logs and delta_ok and corpus_ok
        (d.pass_ if ok else d.fail)(
            "5a.5",
            f"统计 {before_s}→{now_s}（生成>=1、采纳 +1={delta_ok}）；"
            f"语料 {len(corpus)} 行且含本次回复={corpus_ok}，首条={corpus[:1]}；"
            f"adopt={[ln.split('IspeechIme: ')[-1].strip() for ln in adopt_logs.splitlines() if 'ai_adopt' in ln]}",
        )
    else:
        d.results.append(Case(
            "5a.5",
            True,
            f"本次结果来源={source}，按设计不计采纳率；"
            "模型路径请用 --mock-llm 或真实 AI 配置重跑（见下方模型模式输出）",
        ))

    return 0 if all(c.ok for c in d.results) else 1


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