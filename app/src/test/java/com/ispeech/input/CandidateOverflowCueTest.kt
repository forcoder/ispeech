package com.ispeech.input

import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-12「候选溢出可见性」回归测试（规格 docs/keyboard-ux-spec.md AC-12）。
 *
 * 立条理由：档2 放开「只完整显示 3 条」的前提是「后面还有东西」看得见。VOCA-185 去掉
 * 翻页器与齿轮后，横滚没有任何可视线索，被切掉的第 4 条只剩一个字的三分之一，用户
 * 会以为没有更多候选而直接打下一码。
 *
 * CandidateView 本身依赖 Android 框架（HorizontalScrollView / 无障碍节点），无法在
 * 纯 JVM 单测里实例化，所以把可判的部分锁在两层：
 * ① 判定规则 [CandidateOverflowCue] 的纯函数语义；
 * ② 布局与接入的源码结构（线索必须是叠在滚动区上的兄弟层、不占横向预算）。
 */
class CandidateOverflowCueTest {

    @Test
    fun `有溢出时显示线索`() {
        assertTrue(CandidateOverflowCue.shouldShow(scrollable = true, canScrollForward = true))
    }

    @Test
    fun `无溢出时线索不得出现`() {
        // 规格：无溢出不许常驻一个假的「更多」
        assertFalse(CandidateOverflowCue.shouldShow(scrollable = true, canScrollForward = false))
    }

    @Test
    fun `候选区不在位时即便还能滚也不显示`() {
        // chip-only 态 / 空态：scrollView 已 GONE，此时任何线索都是噪音
        assertFalse(CandidateOverflowCue.shouldShow(scrollable = false, canScrollForward = true))
        assertFalse(CandidateOverflowCue.shouldShow(scrollable = false, canScrollForward = false))
    }

    @Test
    fun `遮罩宽度落在规格给的 8 到 12 dp 区间`() {
        assertTrue(
            "AC-12 实现口径 ① 给的是 8–12 dp，当前 ${CandidateOverflowCue.FADE_WIDTH_DP} dp",
            CandidateOverflowCue.FADE_WIDTH_DP in 8..12,
        )
    }

    @Test
    fun `渐变从全透明过渡到候选条底色`() {
        // 全透明起点 + 全不透明终点：中间没有断层，右侧与 #F4F5F7 无缝衔接
        assertEquals(0x00, CandidateOverflowCue.GRADIENT_HORIZONTAL_START_ALPHA)
        assertEquals(0xFF, CandidateOverflowCue.GRADIENT_HORIZONTAL_END_ALPHA)
    }

    // ── 源码结构断言：CandidateView 改回「线索塞进候选区」或「新增常驻按钮」都会失败 ──

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/com/ispeech/input/CandidateView.kt").isFile }
            ?: error("找不到仓库根，工作目录 ${File(".").absolutePath}")
    }

    private fun source(relative: String): String =
        File(repoRoot, relative).also {
            assertTrue("缺少 $relative，测试口径需要更新", it.isFile)
        }.readText()

    private val layout by lazy { source("app/src/main/res/layout/candidate_view.xml") }
    private val viewSource by lazy { source("app/src/main/java/com/ispeech/input/CandidateView.kt") }

    @Test
    fun `溢出线索在候选条布局里声明且默认隐藏`() {
        val cue = layout.substringAfter("candidate_overflow_cue")
        assertTrue("线索必须在候选条布局里声明", cue.isNotEmpty())
        assertTrue(
            "线索默认 hidden，只在 refreshOverflowCue 里按溢出事实显示",
            cue.substringBefore("/>").contains("android:visibility=\"gone\""),
        )
    }

    @Test
    fun `线索叠在候选区上 不进入横向布局`() {
        // 线索必须是「滚动区的兄弟层、在同一 FrameLayout 里靠 end 叠放」；
        // 若被塞进 candidate_items 或另起一行，它就吃掉横向预算，AC-10a 的账要重算
        val frame = layout.substringAfter("<FrameLayout").substringBefore("</FrameLayout>")
        assertTrue("线索必须在 candidate_scroll 的兄弟 FrameLayout 里", frame.contains("candidate_scroll"))
        assertTrue("线索必须叠在这一层", frame.contains("candidate_overflow_cue"))
        assertTrue(
            "线索靠右叠放，不能占一列宽度",
            frame.substringAfter("candidate_overflow_cue").substringBefore("/>")
                .contains("android:layout_gravity=\"end\""),
        )
        assertFalse(
            "线索不得进入候选内容容器（会被当成第 N 个候选参与横排）",
            layout.substringAfter("candidate_items").substringBefore("/>")
                .contains("candidate_overflow_cue"),
        )
    }

    @Test
    fun `线索不新增可聚焦元素 读屏走滚动动作`() {
        val cue = layout.substringAfter("candidate_overflow_cue").substringBefore("/>")
        assertTrue(
            "线索是纯视觉层：读屏路径走滚动容器的 ACTION_SCROLL_FORWARD，不新增节点",
            cue.contains("android:importantForAccessibility=\"no\"") &&
                cue.contains("android:focusable=\"false\"") &&
                cue.contains("android:clickable=\"false\""),
        )
        assertTrue(
            "无障碍委托必须挂在真正接收滚动的 HorizontalScrollView 上",
            viewSource.contains("scrollView.accessibilityDelegate") &&
                viewSource.contains("AccessibilityNodeInfo.ACTION_SCROLL_FORWARD"),
        )
    }

    @Test
    fun `线索随滚动位置与布局变化重判 不是一次性开关`() {
        assertTrue(
            "滚动后要重判：滚到最右端「后面还有」不再成立",
            viewSource.contains("setOnScrollChangeListener") && viewSource.contains("refreshOverflowCue()"),
        )
        assertTrue(
            "布局后要重判：面板宽 / 字号变化会改变可达条数",
            viewSource.substringAfter("override fun onLayout").substringBefore("override fun")
                .contains("refreshOverflowCue()"),
        )
    }

    @Test
    fun `候选条底色只有一处口径 渐变要跟它一致`() {
        // 遮罩右端若用别的色，右沿会出现一条更深的竖边，看起来像新控件而不是「内容被盖住」
        assertTrue(
            "渐变底色必须取自与 candidate_view.xml 同值的 BAR_BG 令牌",
            viewSource.contains("BAR_BG = Color.parseColor(\"#F4F5F7\")"),
        )
        assertTrue(
            "布局里候选条底色也是 #F4F5F7",
            layout.contains("android:background=\"#F4F5F7\""),
        )
    }
}