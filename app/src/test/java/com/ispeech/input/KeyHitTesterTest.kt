package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KeyHitTester] 纯函数命中判定测试。
 *
 * 覆盖「整框可点」（四角内缩 1dp / 四边中点 / 中心 / hint 小字区 / 圆角外露角点），
 * 以及「缝按中线归属相邻键」（水平缝 + 垂直缝）+「扩展距离上限」。
 */
class KeyHitTesterTest {

    // 水平相邻两键：键框各 30 宽，缝 2dp（30..32），中线在 x = 31
    private val left = KeyHitRect("q", 0f, 0f, 30f, 50f)
    private val right = KeyHitRect("w", 32f, 0f, 62f, 50f)

    // 垂直相邻两键：上行底边 50，下行顶边 54，缝 4dp，中线在 y = 52
    private val upper = KeyHitRect("a", 0f, 0f, 30f, 50f)
    private val lower = KeyHitRect("s", 0f, 54f, 30f, 104f)

    @Test
    fun `键框内九点全部命中该键_四角内缩1四边中点中心`() {
        val key = KeyHitRect("q", 0f, 0f, 30f, 50f)
        val points = listOf(
            1f to 1f, 29f to 1f, 1f to 49f, 29f to 49f, // 四角内缩 1dp
            15f to 1f, 1f to 25f, 29f to 25f, 15f to 49f, // 四边中点
            15f to 25f, // 中心
        )
        for ((x, y) in points) {
            assertEquals("点($x,$y) 应命中该键", 0, KeyHitTester.hit(listOf(key), x, y))
        }
    }

    @Test
    fun `水平缝中线两侧各归左右键_中线归索引较小者`() {
        val keys = listOf(left, right)
        assertEquals(0, KeyHitTester.hit(keys, 30.5f, 25f)) // 中线左侧 → 左键
        assertEquals(1, KeyHitTester.hit(keys, 31.5f, 25f)) // 中线右侧 → 右键
        assertEquals(0, KeyHitTester.hit(keys, 31f, 25f))   // 恰好中线 → 归左键
    }

    @Test
    fun `垂直缝中线上下各归上下键`() {
        val keys = listOf(upper, lower)
        assertEquals(0, KeyHitTester.hit(keys, 15f, 51f)) // 中线之上 → 上键
        assertEquals(1, KeyHitTester.hit(keys, 15f, 53f)) // 中线之下 → 下键
    }

    @Test
    fun `hint小字区属于键框内点击命中该键`() {
        // hint 是键框内的叠层小字（右上角），其坐标仍在键框矩形内
        assertEquals(0, KeyHitTester.hit(listOf(left), 27f, 3f))
    }

    @Test
    fun `圆角外露的角落与边界点命中该键`() {
        // 视觉上圆角把角「削」掉，但命中矩形仍是完整键框：角点 / 边界点都可点
        assertEquals(0, KeyHitTester.hit(listOf(left), 0f, 0f))
        assertEquals(0, KeyHitTester.hit(listOf(left), 30f, 50f))
        assertEquals(0, KeyHitTester.hit(listOf(left), 0f, 25f)) // 左边中点
    }

    @Test
    fun `超出扩展距离上限视为未命中`() {
        assertEquals(-1, KeyHitTester.hit(listOf(left), 100f, 25f, maxExpand = 1f))
        // 不设上限时按最近键归属（渲染侧由 KeyRow 用 maxExpand 兜底防误触）
        assertEquals(0, KeyHitTester.hit(listOf(left), 100f, 25f))
    }
}