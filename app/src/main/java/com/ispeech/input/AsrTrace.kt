package com.ispeech.input

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * ASR（语音识别）链路业务埋点：把「选了哪个引擎 → 识别服务/模型是否就绪 → 开始收音 →
 * 首个结果 → 结束 / 出错 / 超时」逐段落成一行结构化日志。
 *
 * 立项目的（VOCA-231）：真机上语音键卡在「语音识别中…」长时间不复位，而 logcat 里
 * **一条业务打点都没有**，从日志无法判定卡在哪一层。既有 `Log.w` 全在 `catch` 分支里，
 * 而「卡住」本身不抛异常 —— 不报错就等于没证据。
 *
 * 输出形态（单 TAG + `key=value` + 空格分隔，可直接 `grep` / `awk` 统计）：
 *
 * ```
 * I/AsrTrace: session=v3 ev=start t=104512 engine=SYSTEM lang=zh-CN partial=true ms=0
 * ```
 *
 * 隐私：只记长度与状态，**绝不**记录识别文本内容、输入框内容或宿主编辑字段。
 */
object AsrTrace {

    /** 全链路共用一个 TAG，一条命令即可抓整段会话。 */
    const val TAG = "AsrTrace"

    /** 缺省值占位：键恒定出现，便于按列解析。 */
    private const val NIL = "-"

    // ── 事件名：跨文件以字符串引用，拼错编译期发现不了，故集中定义 ──

    /** 服务侧决定开始一次语音会话（携带最终生效的引擎）。 */
    const val EV_BEGIN = "begin"

    /** 一次被拦下的开始请求（未授权 / 无可用服务 / 模型未就绪 / 已在收音）。 */
    const val EV_BLOCKED = "blocked"

    /** 识别服务探测结果：绑得上 ≠ 能用（桩服务能绑不回结果）。 */
    const val EV_BIND = "bind"

    /** 引擎侧识别器就绪、已下发 startListening。 */
    const val EV_START = "start"

    /** 按住说话手势抬起（按下由 [EV_BEGIN] 的 `via=press_down` 表示；只有按下没有抬起 = 收尾没被触发）。 */
    const val EV_PRESS = "press"

    /** 本次会话第一条回调文本（partial 或 final 都算，用来区分「完全没回调」与「回调不出最终结果」）。 */
    const val EV_FIRST_RESULT = "first_result"

    /** 结束收音（stopListening），进入「等收尾结果」阶段。 */
    const val EV_END = "end"

    /** 拿到最终结果，会话闭环。 */
    const val EV_RESULT = "result"

    /** 回调了但没有可用文本（等价于「没听清」）。 */
    const val EV_EMPTY = "empty"

    /** 结果已上屏。 */
    const val EV_COMMIT = "commit"

    /** 失败：识别服务错误码、引擎异常或前置校验不通过。 */
    const val EV_ERROR = "error"

    /** 兜底超时触发并复位状态。 */
    const val EV_TIMEOUT = "timeout"

    /** 识别器忙：原地重建重试一次。 */
    const val EV_RETRY = "retry"

    /** 用户取消 / 手势被打断，结果丢弃。 */
    const val EV_CANCEL = "cancel"

    /** UI 侧语音态复位（`voiceActive` / 候选条状态从「识别中」切走）。 */
    const val EV_UI_RESET = "ui_reset"

    private val sessionSeq = AtomicLong()

    /** 新会话 id：`v` + 单调递增；每行自带的 `t=`（开机毫秒）提供跨会话全局时序。 */
    fun newSessionId(): String = "v" + sessionSeq.incrementAndGet()

    /** 常规埋点（info 级），时间戳取 [SystemClock.elapsedRealtime]。 */
    fun i(sessionId: String?, event: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, line(sessionId, event, SystemClock.elapsedRealtime(), fields.toList()))
    }

    /** 异常埋点（warn 级）：超时、失败、被丢弃的会话，`grep ' W AsrTrace'` 单独可见。 */
    fun w(sessionId: String?, event: String, vararg fields: Pair<String, Any?>) {
        Log.w(TAG, line(sessionId, event, SystemClock.elapsedRealtime(), fields.toList()))
    }

    /** 纯函数：拼一行日志。单测覆盖这里，避免依赖 android 运行时。 */
    fun line(
        sessionId: String?,
        event: String,
        uptimeMs: Long,
        fields: List<Pair<String, Any?>>,
    ): String {
        val sb = StringBuilder(96)
        sb.append("session=").append(sessionId ?: NIL)
        sb.append(" ev=").append(event)
        sb.append(" t=").append(uptimeMs)
        fields.forEach { (key, value) ->
            sb.append(' ').append(key).append('=').append(sanitize(value))
        }
        return sb.toString()
    }

    /** 值里的空格 / 换行会破坏 `key=value` 的按列解析，统一压成 `_`。 */
    private fun sanitize(value: Any?): String = when (value) {
        null -> NIL
        is String -> if (value.isBlank()) NIL else value.trim().replace(WHITESPACE, "_")
        else -> value.toString()
    }

    private val WHITESPACE = Regex("\\s+")
}
