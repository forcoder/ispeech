#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_ac12_frame.py —— AC-12 测量工装的「帧有效性闸门」单测（VOCA-37）。

背景（2026-11-24 在 emulator-5554 上实测）：`ac12_overflow.find_bar()` 只按
「整行 ≥60% 为候选条底色」找最长连续行段，不校验高度。当 IME 面板整块都是
`#F4F5F7` 而键盘键面没画出来时，它把「整屏」当成候选条，然后照常产出 verdict：
- 若线索区与左侧都无墨迹 → `NO_CONTENT_ANYWHERE`
- 若面板里恰好有别的墨迹落在左侧三倍带内 → `FADE_PRESENT`，即**假的达标**

第二条最危险：`band.AC12_MEASURED` 的达标出数源就是 `verdict=FADE_PRESENT`，
一个空白面板能让 AC-12 被误判达标。本单测把「不合尺寸的条带必须走 ok=False、
不得产出 verdict」钉成断言。

跑法（标准库 + Pillow；不需要 adb、不需要设备）：

    python3 tools/qa/voca-37/test_ac12_frame.py

退出码 0 = 全绿；非 0 = 有用例失败。
"""
import os
import sys
import unittest

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ac12_overflow as A

BG = A.BAR_BG
WHITE = (255, 255, 255)
# 目标机几何：1260×2720 @520 → 3.25 px/dp
W, H, DPI = 1260, 2720, 520
DEN = DPI / 160.0
BAR_PX = int(round(46 * DEN))   # 46dp 候选条 = 150px


def render(path, paint):
    """画一张 W×H 的图，paint(px, w, h) 负责填内容。"""
    im = Image.new("RGB", (W, H), BG)
    paint(im.load(), W, H)
    im.save(path)
    return path


def blank_panel(px, w, h):
    """面板整块底色（键面没画出来）—— 2026-11-24 AVD 上真实出现过的帧。"""
    return


def healthy_frame(px, w, h):
    """健康帧：顶部一条 46dp 候选带，下面全是白色键面。"""
    for y in range(BAR_PX, h):
        for x in range(w):
            px[x, y] = WHITE
    # chip 与候选字身：条带内左侧有墨迹
    for y in range(20, BAR_PX - 20):
        for x in range(16, 260):
            px[x, y] = (91, 108, 255)


class FrameGate(unittest.TestCase):
    def setUp(self):
        self.tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build_test_ac12")
        os.makedirs(self.tmp, exist_ok=True)

    def tearDown(self):
        for n in os.listdir(self.tmp):
            os.remove(os.path.join(self.tmp, n))
        os.rmdir(self.tmp)

    def test_blank_panel_yields_no_verdict(self):
        """整屏底色的帧必须 ok=False，绝不产出 verdict。"""
        p = render(os.path.join(self.tmp, "blank.png"), blank_panel)
        r = A.measure(p)
        self.assertFalse(r["ok"], r)
        self.assertNotIn("verdict", r)

    def test_blank_panel_cannot_fake_fade_present(self):
        """空白面板 + 右侧一点墨迹：旧版实测报 FADE_PRESENT（假达标），现在必须被拦住。

        这是 2026-11-24 在 emulator-5554 上真实取到的帧形状：面板上下被状态栏/导航栏
        夹出 158..2564 的整块底色，find_bar 把它当候选条；只要那块里有墨迹落在
        线索区左边 3 倍带内，旧版就给 FADE_PRESENT —— 也就是 AC-12 被判达标。
        """
        def panel_with_ink(px, w, h):
            for y in range(0, 158):
                for x in range(w):
                    px[x, y] = WHITE
            for y in range(2564, h):
                for x in range(w):
                    px[x, y] = (0, 0, 0)
            for y in range(300, 900):
                for x in range(1130, 1200):
                    px[x, y] = (91, 108, 255)
        p = render(os.path.join(self.tmp, "fake.png"), panel_with_ink)
        r = A.measure(p)
        self.assertFalse(r["ok"], r)
        self.assertNotIn("verdict", r)

    def test_healthy_frame_still_measures(self):
        """回归护栏：正常帧的读数不能被闸门误伤。"""
        p = render(os.path.join(self.tmp, "ok.png"), healthy_frame)
        r = A.measure(p)
        self.assertTrue(r["ok"], r)
        self.assertIn(r["verdict"], ("FADE_PRESENT", "FADE_ABSENT", "NO_CONTENT_ANYWHERE"))
        self.assertEqual(r["band_y"][0], 0)
        self.assertAlmostEqual(r["band_dp"], 46.0, delta=1.0)


    def _glyph_rows(self, px, w, h, blend_fade=False):
        """画一条「有候选」的候选带：稀疏字身笔画铺满整行，右沿可选叠加 12dp 渐变。

        笔画只占约 1/3 宽度，纯底色仍有 ~2/3 —— 与 2026-11-24 实测帧一致（有候选的行
        纯底色 40–56%）。实心块会把纯底色压到 6%，那不是真实帧的形状。
        """
        for y in range(BAR_PX, h):
            for x in range(w):
                px[x, y] = WHITE
        x_end = w - int(round(6 * DEN))
        fade = int(round(12 * DEN))
        for x0 in range(16, x_end, 46):
            for y in range(20, BAR_PX - 20):
                for x in range(x0, min(x0 + 22, x_end)):
                    px[x, y] = (91, 108, 255)
        # 最后一个候选被视口右界切掉：内容一直铺到 x_end（真实帧就是这样，
        # 否则「溢出」根本不存在，渐变也无从谈起）
        for y in range(20, BAR_PX - 20):
            for x in range(max(16, x_end - fade - 16), x_end):
                px[x, y] = (91, 108, 255)
        if blend_fade:
            for i in range(fade):
                a = i / float(fade - 1)
                x = x_end - fade + i
                for y in range(20, BAR_PX - 20):
                    src = px[x, y]
                    px[x, y] = tuple(int(round(src[c] * (1 - a) + BG[c] * a)) for c in range(3))

    def test_gradient_fade_counts_as_present(self):
        """12 dp 渐变必须判 FADE_PRESENT —— 旧判据要求「线索区一个非底色像素都没有」，
        与渐变本身相反，真有渐变时恒报 FADE_ABSENT（2026-11-24 emulator-5554 实测）。"""
        p = render(os.path.join(self.tmp, "faded.png"),
                   lambda px, w, h: self._glyph_rows(px, w, h, blend_fade=True))
        r = A.measure(p)
        self.assertTrue(r["ok"], r)
        self.assertEqual("FADE_PRESENT", r["verdict"], r)
        self.assertGreaterEqual(r["fade_head_diff"], A.FADE_MIN_INK)
        self.assertLessEqual(r["fade_tail_min_diff"], A.FADE_MAX_CLEAR)

    def test_flat_right_edge_is_absent(self):
        """右沿没有渐变就是 FADE_ABSENT —— 内容被切掉却无线索，正是 AC-12 要修的病。"""
        p = render(os.path.join(self.tmp, "flat.png"),
                   lambda px, w, h: self._glyph_rows(px, w, h, blend_fade=False))
        r = A.measure(p)
        self.assertTrue(r["ok"], r)
        self.assertEqual("FADE_ABSENT", r["verdict"], r)

    def test_band_detected_when_candidates_fill_the_row(self):
        """一行里纯底色只占约 2/3 时仍必须量出 46dp —— 旧阈值 0.60 会切成假条带。"""
        p = render(os.path.join(self.tmp, "crowded.png"),
                   lambda px, w, h: self._glyph_rows(px, w, h, blend_fade=False))
        r = A.measure(p)
        self.assertTrue(r["ok"], r)
        self.assertAlmostEqual(r["band_dp"], 46.0, delta=2.0)

    def test_band_height_bounds_are_dp_based(self):
        """闸门按 dp 判，不按像素判 —— 换密度不该改变同一张 46dp 条带的判定。"""
        self.assertLess(A.MIN_BAND_DP, 46)
        self.assertGreater(A.MAX_BAND_DP, 46)
        self.assertGreater(A.MAX_BAND_PANEL_FRAC, 0.0)
        self.assertLess(A.MAX_BAND_PANEL_FRAC, 0.5)


if __name__ == "__main__":
    unittest.main(verbosity=2)