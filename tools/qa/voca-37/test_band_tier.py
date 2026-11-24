#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""test_band_tier.py —— 档2 判定式接线单测（VOCA-37 / R21 第二件）。

防的就是 2026-11-24 04:58Z 实测到的那件事：`band.py:227 R21_SIGNED=False` 翻成 True
之后 `tier_verdict` 一个字符都不变（`flipped=no_effect`），因为判定串是
`band.py:264` 的硬编码字面量。单测把「翻布尔 ⇒ 判定必须变」钉成断言，
以后谁再把判定式写回字面量，这里会红。

跑法（纯标准库，不需要 adb / Pillow / pytest）：

    python3 tools/qa/voca-37/test_band_tier.py

退出码 0 = 全绿；非 0 = 有用例失败。
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import band


class TierVerdictWiring(unittest.TestCase):
    """档2 判定串必须由 R21_SIGNED / AC12_MEASURED 派生，不许是常量。"""

    def v(self, tier=2, full=3, need=3, r21=None, ac12=None):
        return band.tier_verdict_of(tier, full, need, r21_signed=r21,
                                    ac12_measured=ac12)

    def test_defaults_reproduce_pr25_literal(self):
        """两个前置都未满足时，判定串必须与 PR #25 已入库证据逐字一致。

        `evidence/ev_r8_ac10_1080.txt:76` 的原文是
        `tier_verdict=NOT_SIGNED(AC12_unmeasured+R21_open)`。报告引用它，
        所以这串不能因为「接线」而改写 —— 改了等于让历史证据对不上。
        """
        self.assertEqual(self.v(r21=False, ac12=False),
                         "NOT_SIGNED(AC12_unmeasured+R21_open)")

    def test_module_defaults_are_unsigned(self):
        """模块级真值默认必须是「未签 / 未取证」，agent 不得代批。"""
        self.assertFalse(band.R21_SIGNED)
        self.assertFalse(band.AC12_MEASURED)

    def test_flip_r21_alone_changes_verdict(self):
        """只翻 R21：判定串必须掉一个缺项（04:58Z 的 no_effect 反例）。"""
        before = self.v(r21=False, ac12=False)
        after = self.v(r21=True, ac12=False)
        self.assertNotEqual(before, after, "翻 R21_SIGNED 后 tier_verdict 没变 —— 又接回字面量了")
        self.assertEqual(after, "NOT_SIGNED(AC12_unmeasured)")

    def test_flip_ac12_alone_changes_verdict(self):
        """只翻 AC-12：判定串必须掉另一个缺项。"""
        before = self.v(r21=False, ac12=False)
        after = self.v(r21=False, ac12=True)
        self.assertNotEqual(before, after, "翻 AC12_MEASURED 后 tier_verdict 没变")
        self.assertEqual(after, "NOT_SIGNED(R21_open)")

    def test_both_satisfied_is_pass(self):
        """两个前置都满足，档2 达标才落到 PASS。"""
        self.assertEqual(self.v(r21=True, ac12=True), "PASS")

    def test_tier2_below_need_stays_fail(self):
        """签字不能把不足的条数变成够：档2 只有 2 条时，全签也是 FAIL。"""
        for r21, ac12 in ((False, False), (True, False), (False, True), (True, True)):
            with self.subTest(r21=r21, ac12=ac12):
                self.assertEqual(self.v(full=2, need=3, r21=r21, ac12=ac12), "FAIL")

    def test_tier1_ignores_signature(self):
        """档1（逻辑宽 ≥371dp）不吃这两条让步：≥4 即 PASS，与签字态无关。"""
        for r21, ac12 in ((False, False), (True, True)):
            with self.subTest(r21=r21, ac12=ac12):
                self.assertEqual(self.v(tier=1, full=4, need=4, r21=r21, ac12=ac12), "PASS")
                self.assertEqual(self.v(tier=1, full=3, need=4, r21=r21, ac12=ac12), "FAIL")

    def test_missing_order_is_stable(self):
        """缺项顺序固定 AC12 → R21，报告引用才能逐字对得上。"""
        only_ac12 = self.v(r21=True, ac12=False)
        only_r21 = self.v(r21=False, ac12=True)
        self.assertTrue(only_ac12.startswith("NOT_SIGNED(AC12"))
        self.assertTrue(only_r21.startswith("NOT_SIGNED(R21"))


class Ac10RowsIntegration(unittest.TestCase):
    """`ac10_rows` 必须真的走 `tier_verdict_of`，而不是自己在内部拼串。"""

    def _frame(self, screen_w, dpi, full, full_screen=None, cand=None):
        return dict(ok=True, chip=dict(right=10), chip_right=10, first_item_fill=None,
                    chip_gate=True, first_item_left=11, x_end=screen_w - 6, inner_top=0,
                    inner_bot=100, band_inner=100, badge_px=10, margin_px=6,
                    screen_w=screen_w, dpi=dpi, cand_count=cand or full,
                    full_count=full,
                    full_count_screen=full if full_screen is None else full_screen,
                    items=[])

    def test_tier2_row_carries_signature_state(self):
        """窄面板帧（1080px@520dpi = 332.3dp → 档2，3 条达标）必须打 NOT_SIGNED。"""
        a = self._frame(1080, 520, 3)
        ac10, ac10b = band.ac10_rows(a, 520 / 160.0)
        self.assertEqual(ac10["tier"], 2)
        self.assertEqual(ac10["tier_need"], 3)
        self.assertEqual(ac10["tier_verdict"], "NOT_SIGNED(AC12_unmeasured+R21_open)")
        self.assertFalse(ac10["r21_signed"])

    def test_tier1_row_passes_regardless(self):
        """目标真机几何 1260px@520dpi = 387.7dp → 档1，4 条即 PASS。"""
        a = self._frame(1260, 520, 4)
        ac10, _ = band.ac10_rows(a, 520 / 160.0)
        self.assertEqual(ac10["tier"], 1)
        self.assertEqual(ac10["tier_verdict"], "PASS")

    def test_global_flip_moves_tier2_row(self):
        """改模块级真值（= 签字动作）必须让档2 的行跟着动，无需再改判定式。

        这就是「签字不是开关」的反向证明：只改这一行，`ac10_rows` 的输出必须变。
        用 try/finally 还原全局，避免污染同一进程里的其他用例。
        """
        a = self._frame(1080, 520, 3)
        before, _ = band.ac10_rows(a, 520 / 160.0)
        try:
            band.AC12_MEASURED = True
            band.R21_SIGNED = True
            after, _ = band.ac10_rows(a, 520 / 160.0)
        finally:
            band.AC12_MEASURED = False
            band.R21_SIGNED = False
        self.assertEqual(before["tier_verdict"], "NOT_SIGNED(AC12_unmeasured+R21_open)")
        self.assertEqual(after["tier_verdict"], "PASS")
        self.assertTrue(after["r21_signed"])


if __name__ == "__main__":
    unittest.main(verbosity=2)