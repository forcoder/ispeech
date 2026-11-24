package com.ispeech.input

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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
}