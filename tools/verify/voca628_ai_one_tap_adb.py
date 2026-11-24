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
逐条打印 `PASS` / `FAIL` / `SKIP` 与判定依据（logcat 原文 + 输入框文本 + 截图读数），
最后给出汇总。三档的含义：

- `PASS`：拿到了本条 AC 要求的实测证据；
- `SKIP`：前置不满足，本次没取证 —— 快捷短语模式下的 5a.5（按设计不计采纳率）、
  非真机上的 5a.3（issue 明文要求真机，`--strict-device` 下才算 PASS）。
  SKIP 不进失败数，也不冒充通过；
- `FAIL`：拿到了证据但不符合 AC。`5a.0`（AI 面板开不出来）是硬门，失败即返回非 0、
  后续用例一律不执行 —— 否则整批读数都建在一个开不出来的面板上，没有意义。

退出码：0 = 无真失败；1 = 有 FAIL；2 = 前置整体不可用（宿主输入框 / AI 面板）。

已知限制
--------
- 5a.3 的时序证据（生成后用户改输入框）在模拟器上也能跑出读数，但只记作补充证据、
  判 `SKIP`；真机到位后加 `--strict-device` 才判 `PASS` / `FAIL`。
- 输入法窗口不在 uiautomator 的层级里，键盘 / 面板坐标按截图色块定位，
  依赖 debug 包的配色（#F2F3F5 面板底 / #FFFFFF 行底 / #5B6CFF 主色）。
- 结果行与行尾「…」每轮重开面板后重新自扫（[Driver.find_result_rows]），不复用别处
  算出的坐标 —— 「…」字形只有 24px 宽，按 dp 猜落点会点到行主体上去。
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
    #: 前置不满足（例如快捷短语模式天然不产生采纳统计）时记为 SKIP。
    #: 以前这种情况给的是 `ok=True`，正文却写「按设计不计采纳率」——空断言冒充 PASS，
    #: 质检按「PASS 必须有统计 +1 与语料行」打回，所以这里单列一档，不进失败数也不假装通过。
    skipped: bool = False


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
        self.row_scan: list[tuple[int, int, int]] = []
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
        """定位第一行结果，并验证「点它真的会上屏」。

        两步，先看像素再验证行为：
        1. [find_result_rows] 自扫白底行带，取第一条的行中心 —— 不依赖密度换算或
           布局常量，换机型 / 换主题都不用改；
        2. 在该行左侧（屏幕 1/4 处，避开行尾「…」）真点一次，拿到 `ai_adopt` 才算
           定位成功。定位与断言是同一条证据，不会自欺。
        第 2 步拿到的那次上屏就是 5a.1 的实测数据，调用方直接复用。

        备选：截图扫描没找到行（面板配色变了 / Pillow 缺失）时，才退到旧的「试几个
        候选纵坐标」办法，避免整套脚本直接失效。
        """
        top = self.panel_top()
        if top is None:
            return None
        rows = self.find_result_rows("rows_scan")
        candidates = [yc for yc, _xc, _y1 in rows]
        if not candidates:
            candidates = [top + off for off in (110, 100, 130, 90, 150, 80, 170, 200, 230)]
        for y in candidates:
            if y >= (self.ime_frame() or (0, 0))[1]:
                continue
            self.logcat_clear()
            self.tap_result(y)
            time.sleep(2.2)
            if "ai_adopt" in self.logcat():
                self.row_scan = rows
                return y
            # 没上屏：面板可能已被点掉，重开后继续下一个候选
            if not self.keyboard_shown():
                self.open_host_field(self.needle)
                self.open_ai_panel()
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

    def panel_open(self) -> bool:
        """面板是否已展开（不论有没有结果）。判据：IME 窗口明显矮于键盘态。

        键盘态 = 工具 + 页码条 + 字母行（本机 1201px）；面板态只剩面板
        （有结果 1078px，空态 429px）。空面板（未登录 / 生成失败 / 输入为空）
        **也会**收起键盘，所以「矮了」只能说明面板开了，不能说明有结果 ——
        这正是上一轮把空态误报成「AI 键没找到」的地方。
        """
        f = self.ime_frame()
        if not f or not self.keyboard_baseline:
            return False
        return (f[1] - f[0]) < self.keyboard_baseline * 0.8

    def open_ai_panel(self, retries: int = 2, expect_results: bool = True) -> bool:
        """点工具栏 AI 键，直到面板展开（`expect_results` 时还要有 `ai_results` 日志）。

        两个判据分工明确，别混用：
        - 「面板开没开」→ [panel_open]（IME 高度）；空态也算开，5a.4 就靠它取证；
        - 「有没有结果」→ `ai_results` 日志（只有真的拿到结果才打）。
        以前只认 `ai_results`，于是空态面板被当成「AI 键点不中」，5a.0 连报 FAIL，
        还把后面的用例一并带偏（质检 14:45Z 打回项 3）。

        AI 键的绝对坐标随「候选条行数 / 页码条 / 设备密度」浮动，所以横纵都不写死：
        先复用本轮已命中过的坐标，再按「工具栏行 × 等分列」扫描，命中后记进
        `self.ai_key_xy` 供后续复用。总点击数封顶 20，避免找不到键时空转。
        """
        frame = self.ime_frame()
        if not frame:
            self.fail("5a.0", "拿不到 IME 窗口 frame（输入法没起来？）")
            return False
        top, bottom = frame
        # 基准 = 键盘态高度（此时面板未开）。多点几次取最大值，避免候选条动画中途取样。
        baseline = 0
        for _ in range(2):
            f = self.ime_frame() or (0, 0)
            baseline = max(baseline, f[1] - f[0])
            time.sleep(0.4)
        self.keyboard_baseline = baseline

        if self.ai_key_xy and self._try_tap_ai(*self.ai_key_xy, expect_results=expect_results):
            return True

        w = self.screen_width()
        # 工具栏在候选行之下、字母行之上；高度比例 0.17~0.30 覆盖 1~2 行候选条。
        # 候选顺序：先试最可能的「8 键等宽 × 第 6 个」（实测命中率最高的组合），
        # 再在少数几个纵坐标上扫，最后才换 7/9 键布局。
        primary_x = w * 11 // 16
        candidates = [(primary_x, top + int((bottom - top) * frac))
                      for frac in (0.22, 0.20, 0.24, 0.18, 0.26)]
        for i in (5, 4, 6, 3, 7):
            candidates.append((w * i // 8, candidates[0][1]))
        for i in (5, 4):
            candidates.append((w * i // 7, candidates[1][1]))
        for x, y in candidates:
            if self._try_tap_ai(x, y, expect_results=expect_results):
                self.ai_key_xy = (x, y)
                return True
        what = "面板没展开" if expect_results else "空态面板没展开"
        self.fail("5a.0", f"点了 {retries} 轮仍没找到 AI 键（{what}）")
        return False

    def _try_tap_ai(self, x: int, y: int, expect_results: bool = True) -> bool:
        """点一次 (x,y)，判断 AI 键是否命中；没命中就把宿主输入框复位后返回 False。

        `expect_results=False`（5a.4 空态）时以「面板已展开」为准：空面板没有
        `ai_results`，只认日志会永远判不中。
        """
        self.logcat_clear()
        self.adb("shell", "input", "tap", str(x), str(y))
        time.sleep(2.0)
        if expect_results:
            if "ai_results" in self.logcat():
                return True
        elif self.panel_open():
            return True
        # 点歪了会往宿主里打字 / 收键盘，复位一次再继续扫
        if self.edit_text().strip() != self.needle:
            self.open_host_field(self.needle)
        elif not self.keyboard_shown():
            self.open_host_field(self.needle)
        return False

    def panel_fingerprint(self, tag: str) -> dict:
        """面板区几何 / 颜色指纹，用来区分「列表态」与「详情态」。

        三个判据合起来看，任一单独都不可靠：
        - `lower_primary`：主色（#5B6CFF）在 IME 窗口**下三分之一**的像素数。详情页底部
          「上屏」按钮是主色文字，列表态下三分之一全是白行 / 面板灰底，实测模拟器
          列表 0 → 详情 167。不能用全窗口主色总数当判据：工具栏 AI 键本身就是主色，
          列表 / 详情都占 5800 上下，差值只有一两百，和噪声同量级（上一轮按
          `primary_px > list+100` 判，恰好落在噪声边缘）。
        - `white_rows`：白底结果行的像素行数，列表态每行一条、详情态只剩正文块，
          实测 597 → 122。
        - `h`：IME 窗口高度，列表 1078 → 详情 1795（详情是 10 行高 + 按钮）。
        """
        path = self.shot(tag)
        frame = self.ime_frame()
        if not frame:
            return {"primary_px": 0, "lower_primary": 0, "white_rows": 0, "h": 0, "path": path}
        try:
            from PIL import Image  # noqa: PLC0415
        except ImportError:
            return {"primary_px": 0, "lower_primary": 0, "white_rows": 0,
                    "h": frame[1] - frame[0], "path": path}
        im = Image.open(path).convert("RGB")
        px = im.load()
        w, h = im.size
        top, bot = frame[0], min(frame[1], h)
        lower_from = top + int((bot - top) * 0.66)
        primary = 0
        lower_primary = 0
        white_rows = 0
        for y in range(top, bot):
            cnt = sum(1 for x in range(0, w, 4) if px[x, y] == (255, 255, 255))
            if cnt > w // 8:
                white_rows += 1
            for x in range(0, w, 2):
                if px[x, y] == (91, 108, 255):
                    primary += 1
                    if y >= lower_from:
                        lower_primary += 1
        return {"primary_px": primary, "lower_primary": lower_primary,
                "white_rows": white_rows, "h": bot - top, "path": path}

    def find_result_rows(self, tag: str = "rows") -> list[tuple[int, int, int]]:
        """自扫面板里的白底结果行，返回 [(行中心 y, 「…」中点 x, 行底 y)]。

        不再按 `W − 16dp` 猜「…」的落点：实测该式算出 x=1208，而「…」字形只占
        x[1182,1206]，差 2px 就点回行主体 → 触发一步上屏、面板关闭，被误判成
        「详情没展开」（质检 14:45Z 打回项 1 的现场）。这里改成客观量：
        1. 在 IME 窗口内逐行统计纯白（#FFFFFF）像素，连成白底行带 —— 每带的中心
           就是结果行中心，行高由截图自己说话，不依赖密度换算；
        2. 在该行带内、屏幕右侧 210px 范围找「有暗像素」的列，取首末列中点作为
           「…」的 x（「…」是该行唯一的暗色内容，正文预览止于左侧）。
        找不到白底行（空态 / 面板没开）时返回空列表，由调用方如实判 FAIL / SKIP。
        """
        path = self.shot(tag)
        frame = self.ime_frame()
        try:
            from PIL import Image  # noqa: PLC0415
        except ImportError:
            return []
        if not frame:
            return []
        im = Image.open(path).convert("RGB")
        px = im.load()
        w, h = im.size
        top, bot = frame[0], min(frame[1], h)
        bands: list[tuple[int, int]] = []
        band: list[int] | None = None
        for y in range(top, bot):
            cnt = sum(1 for x in range(0, w, 4) if px[x, y] == (255, 255, 255))
            if cnt > w // 8:
                band = [y, y] if band is None else [band[0], y]
            elif band is not None:
                bands.append((band[0], band[1]))
                band = None
        if band is not None:
            bands.append((band[0], band[1]))

        rows: list[tuple[int, int, int]] = []
        # 行带矮于 20px 的是正文里的白边 / 分隔线，不是结果行。
        for y0, y1 in bands:
            if y1 - y0 < 20:
                continue
            yc = (y0 + y1) // 2
            cols = [x for x in range(max(0, w - 210), w)
                    if any(min(px[x, y]) < 240 for y in range(y0, y1 + 1, 2))]
            if not cols:
                continue
            rows.append((yc, (min(cols) + max(cols)) // 2, y1))
        return rows

    def fail(self, ac: str, detail: str) -> None:
        self.results.append(Case(ac, False, detail))

    def pass_(self, ac: str, detail: str) -> None:
        self.results.append(Case(ac, True, detail))

    def skip(self, ac: str, detail: str) -> None:
        """前置不满足、本次无法取证：既不算通过也不算失败，汇总里单独列出。"""
        self.results.append(Case(ac, True, detail, skipped=True))


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
        mark = "SKIP" if c.skipped else ("PASS" if c.ok else "FAIL")
        print(f"{mark} {c.ac}  {c.detail}")
    failed = [c for c in d.results if not c.ok and not c.skipped]
    skipped = [c for c in d.results if c.skipped]
    tail = f"合计 {len(d.results)} 条，失败 {len(failed)} 条"
    if skipped:
        # 不把 SKIP 混进「通过」里：质检的判据是「PASS 必须有实测证据」，
        # 空断言给 PASS 会被打回，所以这里显式报出来有几条没取证。
        tail += f"，跳过 {len(skipped)} 条（前置不满足，未取证：" + \
                "、".join(c.ac for c in skipped) + "）"
    print("\n" + tail)


def _run(d: Driver, needle: str, strict_device: bool, mock_llm: int | None = None) -> int:
    d.prepare(phrases=not mock_llm, needle=needle, mock_llm=mock_llm)
    d.open_host_field(needle)
    got = d.edit_text().strip()
    if got != needle:
        print(f"宿主输入框不是 {needle!r}，放弃（当前 {got!r}）")
        return 2

    # ── AC-5a.0 前置：AI 面板必须能开 ──
    # 打回项 3：这一条以前 FAIL 了还继续往下判，后面每条都建在开不出来的面板上，
    # 结果整批读数都是垃圾。现在它是硬门：开不出来就立刻返回非 0，不再出声判后面的用例。
    if not d.open_ai_panel():
        print("5a.0 前置失败：AI 面板没能展开，后续用例全部未执行（硬停）")
        return 2
    logs = d.logcat()
    if "ai_results" not in logs:
        d.fail("5a.0", f"面板开了但没有 ai_results，logcat={logs.strip()[-300:]!r}")
        return 1
    count = re.search(r"ai_results source=(\w+) count=(\d+)", logs)
    print(f"生成结果：{count.group(0) if count else '?'}")
    source = count.group(1) if count else "?"
    d.pass_("5a.0", f"{count.group(0) if count else 'ai_results 存在'}（面板展开且产出结果）")

    # ── AC-5a.1 点行即上屏、面板关闭、不进详情 ──
    # 结果行坐标由截图自扫（[find_result_rows]），第一次点击同时就是 5a.1 的证据。
    d.last_stats_before = d.read_file("ai_learning_stats.txt")
    row_y = d.find_result_y()
    if row_y is None:
        d.fail("5a.1", "面板里点不中结果行（自扫白底行带没找到可上屏的一行）")
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

    # ── AC-5a.2 详情页入口（长按 / 行尾「…」），不上屏 ──
    # AC 原文是「长按第 1 行（或点行尾「…」）」，两者等价（`AiReplyPanel.buildRow` 里
    # 长按与 `textButton(ELLIPSIS)` 都绑 `showDetail(index)`）。模拟器的 `input swipe`
    # 同点 900ms 不触发 `setOnLongClickListener`，所以主证据走「…」分支，长按分支
    # 单独 NOTE。
    # 打回项 1：「…」落点不再按 `W − 16dp` 算（实测算出 1208，字形只占 1182~1206，
    # 差 2px 就点回行主体 → 一步上屏 → 被误判成「详情没展开」）。改为每轮重开面板后
    # 重新自扫行带 + 在该行右侧找暗列中点。
    d.open_host_field(needle)
    d.open_ai_panel()
    before = d.edit_text()
    d.logcat_clear()
    rows = d.find_result_rows("5a2_rows")
    if not rows:
        d.fail("5a.2", "面板里没扫到白底结果行，无法定位「…」入口")
    else:
        yc, more_x, _y1 = rows[0]
        list_fp = d.panel_fingerprint("5a2_list_before")
        d.adb("shell", "input", "tap", str(more_x), str(yc))
        time.sleep(2.5)
        text = d.edit_text()
        adopt = d.logcat()
        detail_fp = d.panel_fingerprint("5a2_detail_after")
        # 详情态判据（三路取其二，见 [panel_fingerprint] 的说明）：
        # 底部主色「上屏」按钮出现 + 白底行带消失 / 高度增大。
        lower_ok = detail_fp["lower_primary"] > max(40, list_fp["lower_primary"] + 30)
        layout_ok = (detail_fp["h"] > list_fp["h"] * 1.2
                     or detail_fp["white_rows"] < list_fp["white_rows"] * 0.6)
        expanded = lower_ok and layout_ok
        ok = text == before and "ai_adopt" not in adopt and expanded
        (d.pass_ if ok else d.fail)(
            "5a.2",
            f"点行尾「…」({more_x},{yc}) 后输入框={text!r}（应不变），"
            f"详情页展开={expanded}（下三分之一主色 {list_fp['lower_primary']}→"
            f"{detail_fp['lower_primary']}，白块行 {list_fp['white_rows']}→"
            f"{detail_fp['white_rows']}，IME 高度 {list_fp['h']}→{detail_fp['h']}），"
            f"adopt 日志={'有' if 'ai_adopt' in adopt else '无'}",
        )
        d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.5)

        # 长按分支：如实探测、不计 PASS（模拟器 input swipe 不触发长按监听，需真机）
        d.open_host_field(needle)
        d.open_ai_panel()
        lp_rows = d.find_result_rows("5a2_lp_rows")
        if lp_rows:
            lp_y = lp_rows[0][0]
            lp_before = d.panel_fingerprint("5a2_longpress_before")
            d.adb("shell", "input", "swipe", str(d.screen_width() // 4), str(lp_y),
                  str(d.screen_width() // 4), str(lp_y), "900")
            time.sleep(2.5)
            lp_after = d.panel_fingerprint("5a2_longpress_after")
            lp_ok = lp_after["lower_primary"] > max(40, lp_before["lower_primary"] + 30)
            print(f"NOTE 5a.2 长按分支：input swipe 900ms 后详情展开={lp_ok}"
                  f"（下三分之一主色 {lp_before['lower_primary']}→{lp_after['lower_primary']}）"
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
    rows = d.find_result_rows("5a3_rows")
    if modified == original:
        d.fail("5a.3", f"未能构造「用户改过输入框」场景（原文 {original!r} 改成 {modified!r}）")
    elif not rows:
        d.fail("5a.3", "面板里没扫到白底结果行，无法点击上屏")
    else:
        d.logcat_clear()
        d.tap_result(rows[0][0])
        time.sleep(2.5)
        text = d.edit_text()
        adopt = [ln.strip() for ln in d.logcat().splitlines() if "ai_adopt" in ln]
        ok = text.startswith(modified) and modified in text and len(text) > len(modified)
        detail = (
            f"改后={modified!r} → 上屏后={text!r}；"
            f"{'原样保留用户新内容' if ok else '用户新内容被删/被覆盖'}；"
            f"guard 路径={'ai_adopt 已打' if adopt else '无 ai_adopt'}"
        )
        # issue 明文要求「5a.3 必须真机验」：模拟器上的读数只作为补充证据，不再是 PASS。
        if strict_device:
            (d.pass_ if ok else d.fail)("5a.3", "真机 " + detail)
        else:
            note = "模拟器读数（issue 要求真机，本条不计 PASS）"
            (d.fail if not ok else d.skip)("5a.3", note + "：" + detail)

    # ── AC-5a.4 空态：点面板空白区无上屏无崩溃 ──
    # 两种空态都覆盖：短语模式「生成 0 条」，mock 模式「生成失败」（假端点回 500）。
    # 空态面板没有 ai_results，所以这里用 expect_results=False（只看面板是否展开）。
    blank = FAIL_MARKER if mock_llm else "zzzznohit"
    d.open_host_field(blank)
    opened = d.open_ai_panel(expect_results=False)
    before = d.edit_text()
    top2 = d.panel_top()
    if not opened or top2 is None:
        d.fail("5a.4", "空态下没打开面板")
    else:
        rows = d.find_result_rows("5a4_rows")
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
            f"空态点击后输入框={text!r}（应不变），白底结果行={len(rows)}（应为 0），"
            f"进程仍在={'是' if pid else '否'}，崩溃={'是' if crashed else '否'}，"
            f"误上屏={adopt}",
        )

    # ── AC-5a.5 一步填入计入采纳率 + few-shot 语料 ──
    # 只在走模型生成（fromModel=true）时有意义；快捷短语命中按设计不计入。
    # 打回项 2：以前这种情况给 PASS、正文却写「按设计不计采纳率」——空断言冒充通过。
    # 现在给 SKIP，只有真拿到统计 +1 与语料行才给 PASS。
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
        d.skip(
            "5a.5",
            f"本次结果来源={source}，按设计不计采纳率 / 不写语料，本条未取证；"
            "模型路径请用 --mock-llm 或真实 AI 配置重跑",
        )

    # 只有「真失败」才让脚本返回非 0；SKIP 是前置不满足、未取证，不冒充通过也不判红。
    return 1 if any(not c.ok and not c.skipped for c in d.results) else 0


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