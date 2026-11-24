package com.ispeech.input

import kotlin.math.sqrt

/**
 * 按键命中矩形：纯几何描述，与 android.graphics / View 解耦，可在 JVM 单测中直接运行。
 *
 * @param keyId  键标识（测试与调试用；渲染侧用列表下标对应子 View）
 * @param left / top / right / bottom  键框局部坐标边界（含边界，四角 / 四边 / hint 小字区都可点）
 */
data class KeyHitRect(
    val keyId: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** 点是否落在键框内（含边界）：四角、四边、hint 小字所在的键框区域一律可点。 */
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    /** 点到键框的最近距离（点在内为 0）。 */
    fun distance(x: Float, y: Float): Float {
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * 按键命中判定（纯函数）。
 *
 * 规则：
 * 1. 触点在某键框内（含边界四角 / 四边，含 hint 小字区）→ 命中该键；
 * 2. 否则按「到各键框的最近距离」把缝隙 / 圆角外露 / 边缘归属到相邻键——
 *    两条相邻键框之间，中线两侧严格各归一边，不产生「点了没反应」的死区；
 * 3. [maxExpand] 限制扩展距离：超出该距离视为未命中（默认不限制，返回最近键）。
 *
 * @param keys 参与判定的键框（顺序即下标，渲染侧与其子 View 一一对应）
 * @param x / y 触点坐标（与键框同一坐标系）
 * @param maxExpand 允许的扩展命中距离上限（渲染侧传「键缝半宽」级别，通常 1dp）
 * @return 命中的键框下标；未命中返回 -1
 */
object KeyHitTester {
    fun hit(
        keys: List<KeyHitRect>,
        x: Float,
        y: Float,
        maxExpand: Float = Float.POSITIVE_INFINITY,
    ): Int {
        if (keys.isEmpty()) return -1

        val contained = keys.indexOfFirst { it.contains(x, y) }
        if (contained >= 0) return contained

        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in keys.indices) {
            val d = keys[i].distance(x, y)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        if (best < 0) return -1
        return if (bestDist <= maxExpand) best else -1
    }
}