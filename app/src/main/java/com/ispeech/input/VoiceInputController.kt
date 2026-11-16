package com.ispeech.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.ispeech.R
import com.ispeech.settings.store.AsrEngine
import com.ispeech.settings.store.AsrSettings

/**
 * 语音输入控制器：封装系统 [SpeechRecognizer]（零第三方依赖）。
 *
 * 用法：主线程调用 [start] 开始识别，识别文本经 [onPartial] / [onFinal] 上抛；
 * 用户再次点击语音键时用 [stop] 取消（丢弃随后到达的收尾结果）；
 * 输入法服务销毁时用 [destroy] 释放识别器。
 *
 * 识别引擎与参数来自 [AsrSettings]（由设置面板 / 设置页写入 DataStore，服务侧读取后传入）：
 * - [AsrEngine.SYSTEM]：系统默认识别服务；
 * - [AsrEngine.CUSTOM]：设备上指定的 [android.speech.RecognitionService] 组件，
 *   组件串无法解析时回退系统服务。
 *
 * 线程约束：
 * - [SpeechRecognizer.createSpeechRecognizer] 必须在主线程调用，所有监听回调一律切回
 *   主线程，调用方可直接更新候选条。
 *
 * @param context 建议传 applicationContext，避免持有 Activity / 输入法窗口造成泄漏。
 */
class VoiceInputController(private val context: Context) : RecognitionListener {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null

    /** 是否正在识别中（含「已停止收音、等待结果」阶段）。 */
    var isListening: Boolean = false
        private set

    /** 系统是否提供可用的语音识别服务。 */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * 系统里是否存在**真正可用**的语音识别服务。
     *
     * [isAvailable] 只判断「有没有服务能被绑定」，但部分 ROM（实测华为 ALN-AL00）只预置了一个桩
     * 服务（`com.huawei.vassistant…FakeRecognitionService`）：绑得上、却永远不返回结果，
     * 表现就是「说完不出字」。系统规范要求真正的识别服务以 [BIND_PERMISSION] 保护自己，
     * 桩服务没有该保护权限，据此可把它们区分出来。
     */
    fun hasUsableService(): Boolean = queryServices().any { usableService(it) }

    /** 设备上声明可响应 `ACTION_RECOGNIZE_SPEECH` 的服务（查询失败返回空列表）。 */
    private fun queryServices(): List<ResolveInfo> = runCatching {
        context.packageManager.queryIntentServices(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
            0,
        )
    }.getOrNull().orEmpty()

    private fun usableService(service: ResolveInfo): Boolean =
        service.serviceInfo?.permission == BIND_PERMISSION

    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /** 宿主应用包名（`EXTRA_CALLING_PACKAGE`），见 [buildIntent]。 */
    private var callingPackage: String? = null

    /** 最近一次 [start] 的设置，用于「识别器忙」时原地重建重试。 */
    private var lastAsr: AsrSettings? = null

    /** 本次会话是否已为 `ERROR_RECOGNIZER_BUSY` 重试过一次（避免无限重试）。 */
    private var retriedBusy: Boolean = false

    /** 本次会话的埋点 id（由服务侧下发；为空时 [start] 自行生成，保证日志永远可归组）。 */
    private var traceSession: String? = null

    /** 本次会话起点（[start]）时刻，所有回调行的 `ms=` 都由它算出。 */
    private var traceStartMs = 0L

    /** 本次会话是否已打过 `first_result`（只打首条，避免长句刷屏）。 */
    private var traceFirstResultLogged = false

    /** 服务侧 VAD 痕迹：`onBeginningOfSpeech` / `onEndOfSpeech` 是否到达过。 */
    private var traceVadBegan = false
    private var traceVadEnded = false

    /** VAD 字段：区分「服务没收到音频」与「收到音频但不出结果」两类卡住。 */
    private fun traceVad(): Array<Pair<String, Any?>> = arrayOf(
        "vad_began" to traceVadBegan,
        "vad_ended" to traceVadEnded,
    )

    /** 自会话起点算起的毫秒数（识别器「忙」重试不重置，仍是同一次会话）。 */
    private fun elapsedMs(): Long = (SystemClock.elapsedRealtime() - traceStartMs).coerceAtLeast(0L)

    /**
     * 开始识别。
     *
     * 回调均在主线程触发；每次调用都会重建识别器，避免个别 ROM 复用旧会话的残留状态。
     *
     * @param asr 语音识别设置（引擎 / 语言 / 部分结果 / 离线优先 / 模型名）
     * @param callingPackage 宿主应用包名（输入法场景下用 `currentInputEditorInfo.packageName`）；
     *   为空时不写该 extra，识别服务按默认逻辑处理
     * @param sessionId 埋点会话 id（服务侧为一次语音输入生成）；为空时本控制器自行生成
     */
    fun start(
        asr: AsrSettings,
        callingPackage: String? = null,
        sessionId: String? = null,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        traceSession = sessionId ?: AsrTrace.newSessionId()
        traceStartMs = SystemClock.elapsedRealtime()
        traceFirstResultLogged = false
        traceVadBegan = false
        traceVadEnded = false
        if (!isAvailable) {
            AsrTrace.w(
                traceSession,
                AsrTrace.EV_BLOCKED,
                "stage" to "available",
                "engine" to asr.engine.name,
                "ms" to elapsedMs(),
            )
            post { onError(context.getString(R.string.voice_unavailable)) }
            return
        }
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onError = onError
        this.callingPackage = callingPackage
        this.lastAsr = asr
        this.retriedBusy = false
        logBind(asr)
        startListening(asr)
    }

    /**
     * 识别服务探测埋点：把「设备上有哪些识别服务、其中几个真正受
     * [BIND_PERMISSION] 保护」打出来 —— 华为 ALN-AL00 的 `FakeRecognitionService`
     * 就是「绑得上、永不回结果」的典型，只靠 [isAvailable] 完全看不出来。
     */
    private fun logBind(asr: AsrSettings) {
        val services = queryServices()
        AsrTrace.i(
            traceSession,
            AsrTrace.EV_BIND,
            "engine" to asr.engine.name,
            "requested" to if (asr.engine == AsrEngine.CUSTOM) asr.serviceComponent.ifBlank { "system_default" } else "system_default",
            "services" to services.size,
            "usable" to services.count { usableService(it) },
            "first" to services.firstOrNull()?.let { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" },
            "ms" to elapsedMs(),
        )
    }

    /** 建（重建）识别器并开始收音；[start] 与「识别器忙」重试共用同一条路径。 */
    private fun startListening(asr: AsrSettings) {
        recognizer?.destroy()
        val rec = createRecognizer(asr)
        rec.setRecognitionListener(this)
        recognizer = rec
        isListening = true
        AsrTrace.i(
            traceSession,
            AsrTrace.EV_START,
            "engine" to asr.engine.name,
            "lang" to asr.language.tag,
            "partial" to asr.partialResults,
            "offline" to asr.preferOffline,
            "model" to asr.model,
            "calling" to callingPackage,
            "busy_retry" to retriedBusy,
            "watchdog" to "finish_only",
            "ms" to elapsedMs(),
        )
        rec.startListening(buildIntent(asr))
    }

    /** 按配置创建识别器：CUSTOM 引擎解析组件成功时用指定服务，否则回退系统默认。 */
    private fun createRecognizer(asr: AsrSettings): SpeechRecognizer {
        if (asr.engine == AsrEngine.CUSTOM) {
            val component = ComponentName.unflattenFromString(asr.serviceComponent)
            if (component != null) {
                runCatching { SpeechRecognizer.createSpeechRecognizer(context, component) }
                    .getOrNull()
                    ?.let { return it }
                // 回退不是小事：指定服务起不来时识别走的是系统默认服务，
                // 不打出来的话日志里的引擎名会和实际生效的服务不一致。
                AsrTrace.w(
                    traceSession,
                    AsrTrace.EV_RETRY,
                    "stage" to "create_recognizer",
                    "reason" to "custom_service_failed",
                    "component" to asr.serviceComponent,
                    "fallback" to "system_default",
                    "ms" to elapsedMs(),
                )
            } else {
                AsrTrace.w(
                    traceSession,
                    AsrTrace.EV_BLOCKED,
                    "stage" to "custom_component",
                    "reason" to "unparsable",
                    "component" to asr.serviceComponent,
                    "ms" to elapsedMs(),
                )
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    /** 组装识别 intent：语言、部分结果、离线优先均来自配置。 */
    private fun buildIntent(asr: AsrSettings): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, asr.partialResults)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, asr.language.tag)
            // 宿主应用包名：输入法不是宿主，部分 ROM 的识别服务据此归属录音会话，
            // 缺失时会直接回 ERROR_CLIENT（表现为「点语音键没反应」）
            if (!callingPackage.isNullOrBlank()) {
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
            }
            if (asr.preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // 模型名走自定义 extra：非标准常量，仅识别服务主动读取时生效
            if (asr.model.isNotBlank()) putExtra(AsrSettings.ASR_MODEL_EXTRA, asr.model)
        }

    /**
     * 取消识别：先清掉回调，忽略随后可能到达的收尾结果。
     *
     * 同时销毁识别器——只 `stopListening()` 的话系统侧仍占着麦克风，
     * 下一次 [start] 容易拿到 `ERROR_RECOGNIZER_BUSY`（表现就是「语音时好时坏」）。
     */
    fun stop() {
        AsrTrace.i(
            traceSession,
            AsrTrace.EV_CANCEL,
            "stage" to "stop",
            "listening" to isListening,
            "ms" to elapsedMs(),
        )
        mainHandler.removeCallbacks(finishWatchdog)
        isListening = false
        clearCallbacks()
        recognizer?.destroy()
        recognizer = null
    }

    /**
     * 结束收音并等待结果（「按住说话」松手上屏）。
     *
     * 与 [stop] 的区别：这里**保留回调**，收尾结果仍会经 `onFinal` 上抛；
     * [stop] 是用户主动取消，回调清空、结果丢弃。识别器不销毁——`stopListening()`
     * 之后系统仍会回调 `onResults` / `onError`，服务侧据此上屏或提示。
     */
    fun finish() {
        val rec = recognizer
        if (rec == null || !isListening) {
            // 静默返回是「松手没反应」的一种成因：收尾根本没被触发，兜底超时也就没挂上。
            AsrTrace.w(
                traceSession,
                AsrTrace.EV_BLOCKED,
                "stage" to "finish",
                "reason" to if (rec == null) "no_recognizer" else "not_listening",
                "ms" to elapsedMs(),
            )
            return
        }
        runCatching { rec.stopListening() }
            .onFailure {
                AsrTrace.w(
                    traceSession,
                    AsrTrace.EV_ERROR,
                    "stage" to "stop_listening",
                    "reason" to it.javaClass.simpleName,
                    "ms" to elapsedMs(),
                )
                stop()
                return
            }
        AsrTrace.i(
            traceSession,
            AsrTrace.EV_END,
            "limit_ms" to FINISH_TIMEOUT_MS,
            "first_result" to traceFirstResultLogged,
            "ms" to elapsedMs(),
            *traceVad(),
        )
        // 兜底：个别识别服务松手后既不回 onResults 也不回 onError。按住-说话没有「再点一次」
        // 可供恢复，若不复位，语音键会一直卡在「收音中」——再按也不会重新开始。
        mainHandler.postDelayed(finishWatchdog, FINISH_TIMEOUT_MS)
    }

    /** 松手后等待结果的兜底超时：复位状态并按「没听清」提示用户重说。 */
    private val finishWatchdog = Runnable {
        if (!isListening) return@Runnable
        AsrTrace.w(
            traceSession,
            AsrTrace.EV_TIMEOUT,
            "limit_ms" to FINISH_TIMEOUT_MS,
            "stage" to "finish",
            "first_result" to traceFirstResultLogged,
            "ms" to elapsedMs(),
            *traceVad(),
        )
        isListening = false
        recognizer?.destroy()
        recognizer = null
        val notify = onError
        clearCallbacks()
        notify?.invoke(context.getString(R.string.voice_no_match))
    }

    /** 释放识别器（输入法服务 onDestroy 调用）。 */
    fun destroy() {
        if (isListening) {
            // 收音中被销毁 = 会话永远等不到结果，日志上必须留下痕迹
            AsrTrace.w(
                traceSession,
                AsrTrace.EV_CANCEL,
                "stage" to "destroy",
                "first_result" to traceFirstResultLogged,
                "ms" to elapsedMs(),
            )
        }
        mainHandler.removeCallbacks(finishWatchdog)
        isListening = false
        clearCallbacks()
        recognizer?.destroy()
        recognizer = null
    }

    private fun clearCallbacks() {
        onPartial = null
        onFinal = null
        onError = null
    }

    // ── RecognitionListener ──

    override fun onPartialResults(partialResults: Bundle?) {
        val text = firstResult(partialResults) ?: return
        traceFirstResult("partial", text.length)
        post { onPartial?.invoke(text) }
    }

    override fun onResults(results: Bundle?) {
        mainHandler.removeCallbacks(finishWatchdog)
        isListening = false
        val text = firstResult(results)
        AsrTrace.i(
            traceSession,
            if (text == null) AsrTrace.EV_EMPTY else AsrTrace.EV_RESULT,
            "kind" to "final",
            "chars" to (text?.length ?: 0),
            "ms" to elapsedMs(),
            *traceVad(),
        )
        post {
            if (text != null) {
                onFinal?.invoke(text)
            } else {
                onError?.invoke(context.getString(R.string.voice_no_match))
            }
        }
    }

    /** 首条回调文本只打一行：`first_result` 有 → 服务在回话；没有 → 卡绑定 / 卡收音。 */
    private fun traceFirstResult(kind: String, chars: Int) {
        if (traceFirstResultLogged) return
        traceFirstResultLogged = true
        AsrTrace.i(
            traceSession,
            AsrTrace.EV_FIRST_RESULT,
            "kind" to kind,
            "chars" to chars,
            "ms" to elapsedMs(),
            *traceVad(),
        )
    }

    override fun onError(error: Int) {
        mainHandler.removeCallbacks(finishWatchdog)
        isListening = false
        AsrTrace.w(
            traceSession,
            AsrTrace.EV_ERROR,
            "code" to error,
            "name" to errorName(error),
            "ms" to elapsedMs(),
            "first_result" to traceFirstResultLogged,
            *traceVad(),
        )
        // 「识别器忙」：上一次会话在系统侧尚未完全释放，重建识别器原地重试一次；
        // 不重试的话用户看到的就是「点了语音键没反应」，只能反复点。
        val asr = lastAsr
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !retriedBusy && asr != null) {
            retriedBusy = true
            post {
                // 期间用户已取消（回调被清空）：不要偷偷重新开麦
                if (onPartial == null && onFinal == null && onError == null) return@post
                startListening(asr)
            }
            return
        }
        val message = errorMessage(error)
        post { onError?.invoke(message) }
    }

    /** SDK 错误码 → 名称（logcat 里按名字 grep，比裸数字好用）。 */
    private fun errorName(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "ERROR_$error"
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() {
        traceVadBegan = true
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        traceVadEnded = true
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    /** 取识别结果首条（[SpeechRecognizer.RESULTS_RECOGNITION]），空串视为无结果。 */
    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    /** 常见错误码 → 中文提示（带具体错误码的兜底便于排查）。 */
    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> context.getString(R.string.voice_no_match)

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            context.getString(R.string.voice_need_permission)

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        -> context.getString(R.string.voice_network_error)

        SpeechRecognizer.ERROR_AUDIO ->
            context.getString(R.string.voice_audio_error)

        // ERROR_CLIENT：识别服务拒不处理本次请求；桩服务（无真实引擎）通常就是它，
        // 直接指向「换识别服务」的处置方式，比笼统的「识别失败」可操作得多。
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        -> context.getString(R.string.voice_service_unavailable)

        else -> context.getString(R.string.voice_error_code, error)
    }

    /** 保证回调在主线程执行。 */
    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        /** 真正的 RecognitionService 必须以该权限自保护（[SpeechRecognizer] 依赖它区分桩服务）。 */
        const val BIND_PERMISSION = "android.permission.BIND_RECOGNITION_SERVICE"

        /** 松手后等待识别结果的上限（毫秒）：超时复位，避免语音键卡在「收音中」。 */
        const val FINISH_TIMEOUT_MS = 6000L
    }
}
