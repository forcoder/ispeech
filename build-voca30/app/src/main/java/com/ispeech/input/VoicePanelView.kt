package com.ispeech.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.ispeech.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * 语音输入面板（搜狗风格）：收音期间占据键盘区域，显示实时文字与音量波形。
 *
 * 形态对齐搜狗 Android 版：浅色面板 + 居中大字（实时识别结果）+ 下方波形律动
 * + 底部操作提示。本视图**不持有任何识别状态**：文字与音量都由
 * [com.ispeech.input.IspeechImeService] 从识别引擎推入（与其它面板「视图不管系统调用」的分工一致）。
 *
 * 手势（按下 / 抬起 / 上滑取消）由 [SoftKeyboardView] 的语音键捕获后上抛给服务，
 * 面板只根据服务推来的状态渲染，自身不处理触摸。
 */
class VoicePanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    // ── 颜色（与其它面板保持同一套）──
    private val panelBg = Color.parseColor("#F4F5F7")
    private val textPrimary = Color.parseColor("#1A1A1A")
    private val textSecondary = Color.parseColor("#777777")
    private val accentColor = Color.parseColor("#5B6CFF")
    private val dangerColor = Color.parseColor("#E5484D")

    /** 实时文字。空串时回落到「正在聆听」提示文案。 */
    private val resultView: TextView = TextView(context).apply {
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textPrimary)
        gravity = Gravity.CENTER
        maxLines = 3
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(20), 0, dp(20), 0)
    }

    /** 音量波形。 */
    private val waveView: WaveView = WaveView(context)

    /** 底部提示：常态「松开上屏」，上滑进入取消区时变红提示「松开取消」。 */
    private val hintView: TextView = TextView(context).apply {
        textSize = 12f
        setTextColor(textSecondary)
        gravity = Gravity.CENTER
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(panelBg)
        setPadding(0, dp(14), 0, dp(10))
        addView(resultView, matchWrap())
        addView(waveView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(8)
        })
        addView(hintView, matchWrap().apply { topMargin = dp(6) })
        reset()
    }

    /** 进入面板：清空上一次的文字，回到「正在聆听」。 */
    fun showPanel() {
        reset()
        visibility = VISIBLE
    }

    fun hidePanel() {
        visibility = GONE
        waveView.setLevel(0f)
    }

    /** 清空文字与波形，回到初始态。 */
    fun reset() {
        resultView.text = context.getString(R.string.voice_panel_listening)
        resultView.setTextColor(textPrimary)
        hintView.text = context.getString(R.string.voice_panel_hint)
        hintView.setTextColor(textSecondary)
        waveView.setLevel(0f)
    }

    /** 更新实时识别文字（空串时显示「正在聆听」）。 */
    fun setListeningText(text: String) {
        resultView.text = text.ifBlank { context.getString(R.string.voice_panel_listening) }
        resultView.setTextColor(textPrimary)
    }

    /** 显示一条终态提示（如「没听清，请再说一次」）。 */
    fun setStatusText(text: String) {
        resultView.text = text
        resultView.setTextColor(textSecondary)
    }

    /** 音量（0..1），驱动波形高度。 */
    fun setLevel(level: Float) {
        waveView.setLevel(level)
    }

    /** 是否处于「上滑取消」待生效状态。 */
    fun setCancelArmed(armed: Boolean) {
        if (armed) {
            hintView.text = context.getString(R.string.voice_panel_hint_cancel)
            hintView.setTextColor(dangerColor)
            waveView.setBarColor(dangerColor)
        } else {
            hintView.text = context.getString(R.string.voice_panel_hint)
            hintView.setTextColor(textSecondary)
            waveView.setBarColor(accentColor)
        }
    }


    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    /**
     * 音量波形：一组竖条，高度随音量律动。
     *
     * 每根条的高度 = 音量 × 中间高两侧低的包络 × 行波起伏，并对上一帧做插值平滑，
     * 避免音量抖动时波形「闪」。相位每帧推进，形成左右流动的观感。
     */
    private class WaveView(context: Context) : View(context) {

        private val barCount = 21
        private val minBarRatio = 0.06f
        private val levels = FloatArray(barCount)
        private var targetLevel = 0f
        private var phase = 0f
        private var barColor = Color.parseColor("#5B6CFF")

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = barColor
        }

        fun setLevel(level: Float) {
            targetLevel = level.coerceIn(0f, 1f)
            if (isShown) postInvalidateOnAnimation() else invalidate()
        }

        fun setBarColor(color: Int) {
            barColor = color
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            phase += 0.14f
            val slot = w / barCount
            val barWidth = slot * 0.46f
            val centerIndex = (barCount - 1) / 2f

            for (i in 0 until barCount) {
                // 中间高、两侧低的包络，让波形呈纺锤形（与搜狗一致）
                val distance = abs(i - centerIndex) / centerIndex
                val envelope = 0.30f + 0.70f * (1f - distance * distance)
                // 行波：相位随条序号偏移，形成流动感
                val ripple = 0.55f + 0.45f * sin(phase + i * 0.55f)
                val target = targetLevel * envelope * ripple
                levels[i] += (target - levels[i]) * 0.30f

                val ratio = max(minBarRatio, levels[i])
                val barHeight = h * ratio
                val left = i * slot + (slot - barWidth) / 2f
                val radius = barWidth / 2f
                canvas.drawRoundRect(
                    left,
                    (h - barHeight) / 2f,
                    left + barWidth,
                    (h + barHeight) / 2f,
                    radius,
                    radius,
                    paint,
                )
            }

            // 音量未归零或仍在收音时持续重绘；静止后停帧，避免白白耗电
            if (isShown && (targetLevel > 0.01f || levels.any { it > 0.02f })) {
                postInvalidateOnAnimation()
            }
        }
    }
}