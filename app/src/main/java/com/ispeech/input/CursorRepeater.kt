package com.ispeech.input

/**
 * `◀ ▶` 长按连移的定时器内核（纯 Kotlin，无 Android 依赖，JVM 可直测）。
 *
 * 为什么单独抽出来：这段逻辑有三个「停不下来」的真实入口，用 Android 的 `View.postDelayed`
 * 只能靠真机长按去碰，而其中最容易漏的一条在模拟器上也未必立刻复现——
 * 光标**到达边界**的那一刻，`renderCursorButtons()` 会把该键 `isEnabled = false`，
 * 而 Android 对 disabled 的 View **不再派发触摸事件**：`ACTION_UP` 根本进不来，
 * 「抬手时 removeCallbacks」那条路整条失效。于是每 90 ms 自我重排的循环终身不停；
 * 在边界上每步都是服务侧 no-op 看不出来，但用户**再输入一个字**（光标离开边界）后，
 * 泄漏的循环就以 90 ms/格自行倒着走 —— 正是条目 1 要消灭的「光标乱跑」。
 *
 * 因此这里把「该不该继续」做成注入的 [stillActive] 谓词，每一步都自查，而不只依赖抬手事件；
 * 定时委托给 [Scheduler]，生产用主线程 `Handler`，测试用可手动推进的假时钟，
 * 使「到边界必须停表」成为一条确定性断言而不是真机手感。
 *
 * 用法：`ACTION_DOWN` → [start]；`ACTION_UP` → [stop]，返回 `true` 表示已在连移、无需补最后一步。
 */
class CursorRepeater(
    private val delayMs: Long,
    private val intervalMs: Long,
    private val scheduler: Scheduler,
    private val stillActive: () -> Boolean,
    private val onStep: () -> Unit
) : Runnable {

    /** 定时器抽象：生产实现包 `Handler`，测试实现是假时钟（见 `CursorRepeaterTest`）。 */
    interface Scheduler {
        fun postDelayed(task: Runnable, delayMs: Long)
        fun removeCallbacks(task: Runnable)
    }

    /** 已进入连移（= 本次按下已不止一次点按）；抬手时据此决定要不要补最后一步。 */
    var isRepeating: Boolean = false
        private set

    /** 计时器已排进调度器（含 380 ms 的首拍等待期）。 */
    var isArmed: Boolean = false
        private set

    /** 按下：重新计时（重复调用不会叠两个任务）。 */
    fun start() {
        stop()
        isArmed = true
        scheduler.postDelayed(this, delayMs)
    }

    /**
     * 停表，返回停之前是否已在连移。
     *
     * 三条停止路径共用它：抬手 / 取消、键被禁用（到边界）、面板隐藏或脱离窗口。
     */
    fun stop(): Boolean {
        val wasRepeating = isRepeating
        scheduler.removeCallbacks(this)
        isArmed = false
        isRepeating = false
        return wasRepeating
    }

    override fun run() {
        isArmed = false
        // 兜底自查：首拍等待期内键已被禁用 / 面板已隐藏，就不再起连移。
        if (!stillActive()) {
            isRepeating = false
            return
        }
        isRepeating = true
        onStep()
        // 这一步刚好走到边界（render 已把键 disable）：不排下一拍，循环就此结束。
        if (!stillActive()) {
            isRepeating = false
            return
        }
        isArmed = true
        scheduler.postDelayed(this, intervalMs)
    }
}