package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `◀ ▶` 长按连移的**停表**回归（VOCA-631 质检打回项 1）。
 *
 * 缺陷原样：光标到达边界那一刻，`renderCursorButtons()` 把该键 `isEnabled = false`；
 * Android 对 disabled 的 View 不再派发触摸事件，`ACTION_UP` 进不来，
 * 「抬手时 removeCallbacks」于是永远执行不到，每 90 ms 自我重排的循环终身上下不来。
 * 边界上每步是服务侧 no-op 看不出问题；用户**再输入一个字**后，泄漏的循环便自行倒着走。
 *
 * 这里用可推进的假时钟把「报工」这件事变成可数事实：循环一旦还排着队，
 * [FakeScheduler.advanceTimeBy] 就会继续触发 `onStep`，断言直接读次数戳穿。
 */
class CursorRepeaterTest {

    /** 假时钟：记录唯一在排的任务，`advanceTimeBy` 按虚拟时间逐拍推进。 */
    private class FakeScheduler : CursorRepeater.Scheduler {
        var task: Runnable? = null
        var dueAt: Long = -1
        var now: Long = 0

        override fun postDelayed(task: Runnable, delayMs: Long) {
            this.task = task
            dueAt = now + delayMs
        }

        override fun removeCallbacks(task: Runnable) {
            if (this.task === task) {
                this.task = null
                dueAt = -1
            }
        }

        /** 推进虚拟时间，沿途触发所有到期的那一拍（与真实 Handler 的自重排语义一致）。 */
        fun advanceTimeBy(ms: Long) {
            val target = now + ms
            while (true) {
                val t = task
                // 没有在排的任务，或下一拍还在 target 之后：推进到目标时刻后结束。
                if (t == null || dueAt < 0 || dueAt > target) {
                    now = target
                    return
                }
                now = dueAt
                // 先摘掉再执行：run() 内可能重排（新一轮）或干脆停止（不再排队）。
                // 若只把 dueAt 置 -1 而留下 task，下一轮会把已停止的任务当成「已到期」反复触发
                // ——这正是第一版假时钟把测试 JVM 卡死的死循环。
                task = null
                dueAt = -1
                t.run()
            }
        }
    }

    private val scheduler = FakeScheduler()
    private var steps = 0

    /** `stillActive` 的可变量：真机里它读 `view.isEnabled && isAttachedToWindow`。 */
    private var active = true

    private fun repeater() = CursorRepeater(
        delayMs = 380L,
        intervalMs = 90L,
        scheduler = scheduler,
        stillActive = { active },
        onStep = { steps++ },
    )

    // ── 正常路径：长按期间连移、抬手即停 ──

    @Test
    fun `长按 380ms 后开始连移，且 90ms 一拍`() {
        val r = repeater()
        r.start()
        scheduler.advanceTimeBy(300)
        assertEquals("380 ms 首拍之前不应有任何移动", 0, steps)
        scheduler.advanceTimeBy(80)
        assertEquals("380 ms 首拍应走 1 步", 1, steps)
        scheduler.advanceTimeBy(90)
        assertEquals("首拍之后每 90 ms 一步", 2, steps)

        val wasRepeating = r.stop()
        assertTrue("已在连移时抬手应报告 wasRepeating=true（调用方据此不补最后一步）", wasRepeating)
        scheduler.advanceTimeBy(1_000)
        assertEquals("抬手后不得再有步进", 2, steps)
    }

    @Test
    fun `未进入连移就抬手时返回 false 以便补一次点按`() {
        val r = repeater()
        r.start()
        scheduler.advanceTimeBy(200) // 还没到 380
        assertFalse(r.stop())
        assertEquals(0, steps)
        scheduler.advanceTimeBy(1_000)
        assertEquals("首拍等待期内取消，之后不得起连移", 0, steps)
    }

    @Test
    fun `重复 start 不会叠两个计时器`() {
        val r = repeater()
        r.start()
        r.start()
        r.start()
        scheduler.advanceTimeBy(1_000)
        // 只有一个计时器：380 首拍，之后 470/560/650/740/830/920 各一拍，1010 超出 → 共 7 步
        assertEquals("重复 start 叠了多个计时器（步进数应恰为 7）", 7, steps)
    }

    // ── 缺陷本体：到边界被禁用（ACTION_UP 不再来）必须自己停 ──

    @Test
    fun `连移最后一步走到边界时当拍即停，不排下一拍`() {
        // 这是真机上的真实顺序：onStep() 让服务移光标 → render() 把键 disable，
        // 于是「本步之后」键已不可用。停表必须发生在 run() 内部，不能等 ACTION_UP。
        steps = 0
        val repeater = CursorRepeater(
            delayMs = 380L,
            intervalMs = 90L,
            scheduler = scheduler,
            stillActive = { active },
            onStep = {
                steps++
                if (steps == 2) active = false // 第 2 步到达边界 → render 禁用该键
            },
        )
        repeater.start()
        scheduler.advanceTimeBy(380) // 第 1 步，仍可用
        assertEquals(1, steps)
        scheduler.advanceTimeBy(90) // 第 2 步：走到边界，当拍即停
        assertEquals(2, steps)
        scheduler.advanceTimeBy(5_000)
        assertEquals("走到边界后的同一拍内就必须停表（原缺陷在此继续走）", 2, steps)
        assertFalse("已停表", repeater.isArmed)
    }

    @Test
    fun `到达边界被禁用后循环必须停下，不再自我重排`() {
        val r = repeater()
        r.start()
        // 前两拍正常走；第三拍时「光标到边界」→ 键被禁用（stillActive 变 false）
        active = true
        scheduler.advanceTimeBy(380)   // 第 1 步
        scheduler.advanceTimeBy(90)    // 第 2 步
        assertEquals(2, steps)

        active = false // 到边界：键 isEnabled=false，手指仍按着，ACTION_UP 不会来
        scheduler.advanceTimeBy(2_000)

        assertEquals("被禁用后不得再走任何一步（原缺陷：每 90 ms 继续走）", 2, steps)
        assertFalse("停表后 isArmed 必须为 false", r.isArmed)
        assertFalse("停表后 isRepeating 必须为 false", r.isRepeating)
    }

    @Test
    fun `被禁用发生在首拍等待期内时不得起连移`() {
        val r = repeater()
        active = true
        r.start()
        active = false // 380 ms 未到就被禁用
        scheduler.advanceTimeBy(1_000)
        assertEquals("首拍前已被禁用，不应有任何移动", 0, steps)
        assertFalse(r.isArmed)
    }

    @Test
    fun `停在边界后用户再输入一个字也不会自行倒走`() {
        // 复刻打回项描述的完整场景：长按 ◀ 到开头 → 抬手事件丢失（禁用）→ 再输入 → 光标不得自己动
        val r = repeater()
        r.start()
        scheduler.advanceTimeBy(380) // 走到边界前的一步
        val stepsAtEdge = steps
        active = false               // 到达边界，键被 disable；ACTION_UP 丢失
        scheduler.advanceTimeBy(5_000)
        assertEquals("边界上不得继续步进", stepsAtEdge, steps)

        // 用户再输入一个字，光标离开边界、键重新可用 —— 若循环泄漏，此刻它就会以 90 ms/格倒走
        active = true
        scheduler.advanceTimeBy(5_000)
        assertEquals("重新可用后也不得自走（泄漏的循环会在这里继续步进）", stepsAtEdge, steps)
    }

    // ── 与面板生命周期联动：隐藏 / 脱离窗口 ──

    @Test
    fun `stop 之后即使 stillActive 再变 true 也不恢复`() {
        val r = repeater()
        r.start()
        scheduler.advanceTimeBy(380)
        assertEquals(1, steps)
        r.stop()               // 面板隐藏 / 脱离窗口
        active = true
        scheduler.advanceTimeBy(5_000)
        assertEquals("stop 是终态，不得因 stillActive 变回 true 而复活", 1, steps)
    }
}