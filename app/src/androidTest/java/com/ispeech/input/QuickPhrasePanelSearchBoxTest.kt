package com.ispeech.input

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VOCA-627（D-2）搜索框渲染面的**设备侧**断言。
 *
 * 为什么要有仪器化测试：本单的核心约束（不抢 Android 焦点、不吞父行触摸、光标自绘）
 * 全部是 Android 框架行为，JVM 单测（`SearchCaretTest`）碰不到；而「看截图数像素」
 * 在共用模拟器上会让 Chrome / 联系人等 App 抢前台而不可复现。
 * 这里直接把面板 inflate 进真实 Activity 的窗口，读 View 的真实属性与 `dispatchTouchEvent`
 * 的真实返回值——不依赖前台 App、不依赖截图、不受设备忙闲影响。
 *
 * 覆盖：D2-1（caret 存在且随文本重算）、D2-3（点文本区不吞事件 / 不弹键盘）、
 * D2-4（字母进面板、不进宿主）、D2-5（空词 cursor=0）、D2-6（长按不出选区）、
 * 以及开工判据 §1/§2 的三个标志位本身。
 */
@RunWith(AndroidJUnit4::class)
class QuickPhrasePanelSearchBoxTest {

    private lateinit var panel: QuickPhrasePanel

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // 面板要求在主线程构建 / 布局
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            panel = QuickPhrasePanel(ctx)
            // 给一个确定的自定义尺寸，避免依赖宿主窗口大小
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST)
            )
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        }
    }

    // ── 视图定位（面板内部字段全是 private，按「文本控件 / 独立 caret」的特征找） ──

    private fun searchText(): EditText {
        var found: EditText? = null
        fun walk(v: View) {
            if (v is EditText) found = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { walk(panel) }
        return assertNotNull("面板里应有一个 EditText 搜索框", found).let { found!! }
    }

    /** 自绘 caret：搜索框容器里除文本控件之外的、宽高都很小的那个 View。 */
    private fun caretView(): View {
        val text = searchText()
        val parent = text.parent as ViewGroup
        var caret: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child !== text && child.width in 1..16) {
                    caret = child
                }
            }
        }
        return assertNotNull("搜索框容器里应有独立的自绘 caret 视图", caret).let { caret!! }
    }

    private fun searchRow(): LinearLayout {
        val text = searchText()
        // 结构：panel(QuickPhrasePanel) → searchRow(LinearLayout) → CaretField → searchText
        // text.parent = CaretField，它的 parent 才是 searchRow（早先这里多取了一层，拿到的是 panel 本身）。
        return text.parent.parent as LinearLayout
    }

    /** 在主线程写关键词。`render()` 内部的 `runOnMain` 走 `View.post`；View 未 attach 到窗口时
     *  那条 post 会进 `HandlerActionQueue` 而不是立刻执行，测试里必须自己在主线程调用，
     *  否则读到的是「还没渲染」的旧状态。生产环境面板已 attach，post 会正常执行。 */
    private fun setKeywordOnMain(keyword: String, cursor: Int? = null) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (cursor == null) panel.setKeyword(keyword) else panel.setKeyword(keyword, cursor)
        }
    }

    private fun relayout() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.AT_MOST)
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
    }

    // ── 开工判据 §1：四件套 + 零 requestFocus ──

    @Test
    fun search_box_is_display_only() {
        val text = searchText()
        // inputType / isSingleLine 会把 clickable / longClickable 强行改回 true
        // （TextView.fixFocusableAndClickableSettings），所以这里断言的是「最终态」。
        assertFalse("isFocusable 必须为 false（否则系统把它当输入目标）", text.isFocusable)
        assertFalse("isFocusableInTouchMode 必须为 false", text.isFocusableInTouchMode)
        assertFalse("isCursorVisible 必须为 false（光标自绘）", text.isCursorVisible)
        assertFalse("isClickable 必须为 false", text.isClickable)
        assertFalse("isLongClickable 必须为 false：否则 View.onTouchEvent 把 LONG_CLICKABLE " +
                "也算可点、吞掉 ACTION_DOWN，父行的点击回调在文本区失效", text.isLongClickable)
        assertNull("movementMethod 必须为 null：它非空时 setKeyListener 会把上面几个标志翻回 true",
            text.movementMethod)
    }

    @Test
    fun no_child_holds_android_focus() {
        var focused: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun walk(v: View) {
                if (v.isFocused) focused = v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(panel)
        }
        assertNull("面板是 IME 自己的窗口，任何子控件持焦都会让系统当成新的输入目标", focused)
    }

    // ── D2-3 / D2-6：触摸事件不能被搜索框吞掉 ──

    @Test
    fun tap_on_text_area_is_not_consumed() {
        val text = searchText()
        relayout()
        val down = MotionEvent.obtain(
            SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            MotionEvent.ACTION_DOWN, 10f, (text.height / 2f), 0
        )
        val consumed = InstrumentationRegistry.getInstrumentation().let { inst ->
            var result = false
            inst.runOnMainSync { result = text.dispatchTouchEvent(down) }
            result
        }
        down.recycle()
        assertFalse(
            "ACTION_DOWN 被搜索框吞了：父行 searchRow 的『点搜索框回到输入态』在文本区（weight=1f）" +
                    "会整段失效，这是相对 main 的退步（D2-6）", consumed
        )
    }

    @Test
    fun search_row_click_enters_input_state() {
        // 父行的点击回调 = requestSearchFocus()：只置 isSelected + 起光标闪烁，绝不 requestFocus。
        // 这里锁定「点搜索框 = 进入输入态」这条语义本身；
        // 事件能否到达父行由下面 text_area_does_not_intercept_touch_* 两例负责。
        val row = searchRow()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { row.isSelected = false }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { row.performClick() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue("父行 performClick 后没有进入输入态：requestFocusSearch 没接上", row.isSelected)
        }
    }

    @Test
    fun text_area_does_not_intercept_touch_at_any_vertical_offset() {
        // 走查一行内多个落点（不是只看边缘）：只要有一处返回 true，
        // 父行在这些位置就收不到点击。D2-3 要求整行都能回到输入态。
        val text = searchText()
        relayout()
        val consumedAt = mutableListOf<Int>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (frac in intArrayOf(1, 2, 3, 4, 5)) {
                val y = text.height * frac / 6f
                val ev = MotionEvent.obtain(
                    SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_DOWN, text.width / 2f, y, 0
                )
                if (text.dispatchTouchEvent(ev)) consumedAt += frac
                ev.recycle()
            }
        }
        assertTrue("搜索框在这些纵向落点吞掉了 ACTION_DOWN：$consumedAt（1..5 是从上到下的六等分）",
            consumedAt.isEmpty())
    }

    @Test
    fun long_press_does_not_start_selection() {
        val text = searchText()
        assertFalse("长按可达就会弹系统选区手柄 / 文本工具栏（OEM 上还有粘贴建议），" +
                "面板里必须关掉", text.isLongClickable)
        // EditText 的文本选择由 movementMethod 驱动；摘掉它就没有选区入口。
        assertNull("movementMethod 非空时 EditText 会长按出选区", text.movementMethod)
    }

    // ── D2-5：空搜索框光标在索引 0 ──

    @Test
    fun empty_search_caret_index_is_zero() {
        assertEquals("空词时光标必须在索引 0", 0, panel.cursor())
        assertEquals("空词时搜索框文本应为空", "", panel.keyword())
        val caret = caretView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            panel.requestSearchFocus()          // 面板的「输入态」= 描边 + 光标闪烁
        }
        SystemClock.sleep(120)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals("requestSearchFocus 后 caret 应可见", View.VISIBLE, caret.visibility)
        }
    }

    // ── D2-1 / D2-2：caret 随文本长度重算，不恒贴末尾 ──

    @Test
    fun caret_follows_cursor_not_text_end() {
        setKeywordOnMain("abcd")
        relayout()
        val caret = caretView()
        val atEnd = IntArray(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { atEnd[0] = caret.left }

        // 把光标挪到中间：前缀变短 → caret 必须左移，证明它读的是 cursorIndex 而不是恒贴末尾
        setKeywordOnMain("abcd", 1)
        relayout()
        val atMiddle = IntArray(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { atMiddle[0] = caret.left }

        assertTrue("光标从末尾移到索引 1 后 caret 必须左移（实测末尾 x=${atEnd[0]}，中间 x=${atMiddle[0]}）",
            atMiddle[0] < atEnd[0])
        assertEquals("cursor() 必须返回刚写入的索引", 1, panel.cursor())
    }

    @Test
    fun cursor_is_clamped() {
        setKeywordOnMain("abc", 99)
        assertEquals("超出长度应夹到 length", 3, panel.cursor())
        setKeywordOnMain("abc", -5)
        assertEquals("负数应夹到 0", 0, panel.cursor())
        setKeywordOnMain("", 4)
        assertEquals("空词恒为 0", 0, panel.cursor())
    }

    // ── D2-4 / D2-6 回归：字母进面板、✕ 清空回到空态 ──

    @Test
    fun set_keyword_then_clear_returns_to_empty() {
        setKeywordOnMain("abc")
        relayout()
        assertEquals("abc", panel.keyword())
        assertEquals(3, panel.cursor())
        assertEquals("文本控件应展示关键词", "abc", searchText().text.toString())

        setKeywordOnMain("")
        relayout()
        assertEquals("清空后关键词为空", "", panel.keyword())
        assertEquals("清空后光标回到 0（AC-1.6 无退步）", 0, panel.cursor())
        assertEquals("清空后文本控件应为空", "", searchText().text.toString())
    }

    @Test
    fun search_box_does_not_own_android_selection() {
        val text = searchText()
        setKeywordOnMain("abcd", 2)
        relayout()
        // D2 只恢复渲染面：位置在 panel.cursor() 里，不靠系统的 selectionStart。
        // 控件不持焦、无 movementMethod，所以系统侧根本没有选区——`selectionStart == -1`
        // 正是「位置不来自 Android 选区」的直接证据，也保证不会画出系统光标。
        assertEquals("面板侧 cursor 是唯一真值", 2, panel.cursor())
        assertEquals("控件不持焦时系统不维护选区，selectionStart 应为 -1（位置不来自系统）",
            -1, text.selectionStart)
        assertEquals("也不存在选区终点", -1, text.selectionEnd)
        assertNotNull("caret 是独立 View，必须能找到", caretView())
    }

    // ── 条目 1（VOCA-631）：`◀ ▶` 控件、40dp 可点、禁用态、点击回调 ──

    /**
     * 搜索行里的 `◀` / `▶`：按 contentDescription 区分（启用态下的描述是它们的名字）。
     *
     * **必须在主线程调用**（`runOnMainSync` 不可嵌套，`Instrumentation` 会直接抛）。
     */
    private fun findCursorButton(desc: String): View? {
        var found: View? = null
        fun walk(v: View) {
            if (v.contentDescription?.toString() == desc) found = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(panel)
        return found
    }

    @Test
    fun cursor_buttons_and_clear_meet_40dp_touch_target() {
        val min = (40 * panel.resources.displayMetrics.density).toInt()
        // ✕ 在空词时 GONE，先给个词让它可见并参与布局
        setKeywordOnMain("abcd")
        relayout()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (desc in listOf("光标左移", "光标右移", "清空搜索词")) {
                val v = findCursorButton(desc) ?: findCursorButton("已到开头")
                    ?: findCursorButton("已到结尾") ?: error("搜索行里找不到「$desc」控件")
                assertTrue(
                    "「$desc」可点宽度 ${v.width}px 应 ≥ ${min}px（40dp，AC-1.8 / 规格 §3.4）",
                    v.width >= min
                )
                assertTrue(
                    "「$desc」可点高度 ${v.height}px 应 ≥ ${min}px（40dp）",
                    v.height >= min
                )
            }
        }
    }

    @Test
    fun cursor_buttons_disable_at_edges() {
        // 光标在 0：◀ 禁用、▶ 可用；描述变成「已到开头」
        setKeywordOnMain("abcd", 0)
        relayout()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val leftAtStart = findCursorButton("已到开头")
            assertNotNull("光标在 0 时 ◀ 应进禁用态并改读「已到开头」（R1-17）", leftAtStart)
            assertFalse("光标在 0 时 ◀ 必须不可点", leftAtStart!!.isEnabled)
            assertNotNull("光标在 0 时 ▶ 仍应可用（描述为「光标右移」）", findCursorButton("光标右移"))
        }

        // 光标在末尾：反过来
        setKeywordOnMain("abcd", 4)
        relayout()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val rightAtEnd = findCursorButton("已到结尾")
            assertNotNull("光标在末尾时 ▶ 应进禁用态并改读「已到结尾」", rightAtEnd)
            assertFalse("光标在末尾时 ▶ 必须不可点", rightAtEnd!!.isEnabled)
            assertNotNull("光标在末尾时 ◀ 仍应可用", findCursorButton("光标左移"))
        }
    }

    @Test
    fun cursor_button_click_reports_to_service() {
        // 面板只上报「要移」，位置仍由服务侧持有（R1-15）。这里断言回调真的被触发一次。
        var leftCalls = 0
        var rightCalls = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            panel.onMoveCursorLeft = { leftCalls++ }
            panel.onMoveCursorRight = { rightCalls++ }
        }
        setKeywordOnMain("abcd", 2)
        relayout()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            findCursorButton("光标左移")?.performClick()
            findCursorButton("光标右移")?.performClick()
        }
        assertEquals("◀ 点一次应上报一次左移", 1, leftCalls)
        assertEquals("▶ 点一次应上报一次右移", 1, rightCalls)
        // 面板自己不改 cursor（服务是事实源）：位置仍是写入时的 2
        assertEquals("面板不得自行改光标，位置仍应由服务决定", 2, panel.cursor())
    }

    @Test
    fun caret_stays_inside_codepoint_boundary_for_emoji() {
        // 光标停在 emoji 之后（码点 2）：caret x 必须落在 emoji 之后、文本末尾之前，
        // 不能因为按码元 take() 截出半个代理对而左跳（R1-1）。
        setKeywordOnMain("a😀d", 3)
        relayout()
        val caret = caretView()
        val atEnd = IntArray(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { atEnd[0] = caret.left }

        setKeywordOnMain("a😀d", 2)
        relayout()
        val afterEmoji = IntArray(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { afterEmoji[0] = caret.left }

        assertTrue("emoji 之后的 caret x=${afterEmoji[0]} 必须严格小于末尾 x=${atEnd[0]}",
            afterEmoji[0] < atEnd[0])
        assertTrue("emoji 是 1 个码点，caret 不能落回 0 位置", afterEmoji[0] > 0)
    }

    // ── VOCA-631 打回项 1：长按连移到边界后必须停表（真实 View + 真实窗口 + 真实触摸派发） ──

    /**
     * 到边界后按键被 `disable`，Android 不再把它的事件交给 `OnTouchListener`，
     * `ACTION_UP` 于是永远进不来。这里把面板**挂进真实 Activity 的窗口**（`isAttachedToWindow`
     * 为真，与生产一致），派发 DOWN → 长按越过 380 ms 首拍 → 派发 UP，然后确认抬手之后
     * 不再有任何新的移动回调。
     *
     * 为什么必须挂窗口：`stillActive` 判的是 `view.isEnabled && view.isAttachedToWindow`。
     * 面板不挂窗口时它恒为 false，连移根本起不来 —— 那样这条用例会**空转通过**，
     * 对缺陷没有任何鉴别力（本轮实测：未挂窗口时把停表逻辑整个删掉，用例依然 OK）。
     *
     * 与 `CursorRepeaterTest` 的分工：那边用假时钟证明**算法**停表；这边证明**接线**停表
     * ——`applyCursorButtonState` 的禁用回调确实接到了 repeater，且真实 Handler 也不漏拍。
     */
    @Test
    fun long_press_stops_repeating_after_reaching_edge_and_lifting() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch(EmptyHostActivity::class.java)
        try {
            val leftCalls = java.util.concurrent.atomic.AtomicInteger(0)
            lateinit var hosted: QuickPhrasePanel
            scenario.onActivity { activity ->
                hosted = QuickPhrasePanel(activity)
                activity.setContentView(hosted)
                // 复刻真实链路：面板只上报「要左移」，服务侧改 (keyword, cursor) 再写回，
                // 面板 render 后在边界把该键 disable（这正是打回项 1 成立的前提）。
                hosted.onMoveCursorLeft = {
                    leftCalls.incrementAndGet()
                    hosted.setKeyword(hosted.keyword(), (hosted.cursor() - 1).coerceAtLeast(0))
                }
                // 光标在 2：长按 ◀ 两步就到 0，之后键被 disable 而手指还没抬起。
                hosted.setKeyword("abcd", 2)
            }
            inst.waitForIdleSync()
            scenario.onActivity { it.findViewById<View>(android.R.id.content).requestLayout() }
            inst.waitForIdleSync()

            // 找到 ◀ 键（初始可用 → 描述是「光标左移」）
            fun findLeft(): View? {
                var found: View? = null
                fun walk(v: View) {
                    val d = v.contentDescription?.toString()
                    if (d == "光标左移" || d == "已到开头") found = v
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                scenario.onActivity { walk(it.findViewById(android.R.id.content)) }
                return found
            }

            val left = findLeft()
            assertNotNull("挂进窗口后应能定位 ◀ 键", left)
            assertTrue("前提：面板已经真的挂上窗口（否则本用例会空转通过）",
                left!!.isAttachedToWindow)

            // 真实触摸：按下去，别松手
            scenario.onActivity {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
                left.dispatchTouchEvent(down)
                down.recycle()
            }
            // 越过 380 ms 首拍 + 若干 90 ms 拍；面板挂窗口后仍应停在边界，不得自己走回来
            SystemClock.sleep(900)
            val beforeUp = leftCalls.get()
            assertTrue("长按应至少触发一次移动（否则连移没起来，用例空转）：$beforeUp", beforeUp >= 1)

            // 抬手 —— 关键：光标已在边界，键此时可能已 disable，Android 未必还会把 UP 派进来，
            // 这正是缺陷成立的条件；无论如何都尝试补一次 UP。
            scenario.onActivity {
                val now = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 20f, 20f, 0)
                left.dispatchTouchEvent(up)
                up.recycle()
            }
            val afterUp = leftCalls.get()
            SystemClock.sleep(800)
            assertEquals(
                "抬手后 ◀ 不得再上报任何左移（原缺陷：泄漏的 90 ms 循环会继续走）",
                afterUp, leftCalls.get()
            )
        } finally {
            scenario.close()
        }
    }
}
