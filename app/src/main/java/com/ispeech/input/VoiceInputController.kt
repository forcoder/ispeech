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
import android.speech.RecognitionService
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
     * 表现就是「说完不出字」。桩服务的可辨识特征是它挂在语音助手接口上、由
     * `BIND_VOICE_INTERACTION` 保护，据此与真识别服务区分（判据见 [isUsableRecognitionService]）。
     *
     * 这条判定是语音键的前置门禁（[IspeechImeService] 拦住「点了没反应」的机器并给出指引），
     * 判错的方向必须是「放行 + 由 [AsrTiming] 的时限兜住」，而不是「一律拦死」。
     */
    fun hasUsableService(): Boolean = queryServices().any { usableService(it) }

    /**
     * 设备上的识别服务（查询失败返回空列表）。
     *
     * 真正的识别服务注册在 [RecognitionService.SERVICE_INTERFACE] 上；
     * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` 是**启动识别界面的 Activity** 用的 action，
     * 拿它查服务在实测机上是恒空的（API 34 google_apis：接口 action 命中 2 个服务，
     * 旧 action 命中 0 个），所以以接口 action 为准、旧 action 只作个别 ROM 的兜底。
     */
    private fun queryServices(): List<ResolveInfo> {
        val pm = context.packageManager
        val byInterface = runCatching {
            pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrNull().orEmpty()
        if (byInterface.isNotEmpty()) return byInterface
        return runCatching {
            pm.queryIntentServices(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)
        }.getOrNull().orEmpty()
    }

    private fun usableService(service: ResolveInfo): Boolean =
        isUsableRecognitionService(service.serviceInfo?.permission)

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

    /**
     * 本次会话是否收到过识别服务的**任何**回调（`onReadyForSpeech` 起算）——
     * [AsrTiming.FIRST_CALLBACK_TIMEOUT_MS] 的判据。
     *
     * 桩服务（实测华为 ALN-AL00 的 `FakeRecognitionService`）连一个回调都不给，
     * UI 于是停在「识别中」不动。用户没出声不算卡住：那种情况服务自己会回
     * `ERROR_SPEECH_TIMEOUT`，走 [onError] 而不是兜底超时。
     */
    private var traceCallbackSeen = false

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
        traceCallbackSeen = false
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
        // 会话硬上限：抬不起手（按住不放）或抬起事件被系统吃掉时，收尾（[finish]）永远不会被调用，
        // 于是也就没有任何超时挂得上 —— VOCA-231 那次「识别中」75 秒不复位就是这么来的。
        // 只在这里挂一次：「识别器忙」重建走 [startListening]，不重置这条，它量的是整次会话的长度。
        mainHandler.removeCallbacks(sessionWatchdog)
        mainHandler.postDelayed(sessionWatchdog, AsrTiming.SESSION_TIMEOUT_MS)
        startListening(asr)
    }

    /**
     * 识别服务探测埋点：把「设备上有哪些识别服务、几个可用、几个是语音助手挂出来的桩」打出来 ——
     * 华为 ALN-AL00 的 `FakeRecognitionService` 就是「绑得上、永不回结果」的典型，
     * 只靠 [isAvailable] 完全看不出来。`usable=0` 与 `stubs>0` 同时出现，才说明这台机器
     * 需要换引擎而不是催用户重试。
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
            "stubs" to services.count { !usableService(it) },
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
        traceCallbackSeen = false
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
            "watchdog" to "start+session+finish",
            "first_limit_ms" to AsrTiming.FIRST_CALLBACK_TIMEOUT_MS,
            "session_limit_ms" to AsrTiming.SESSION_TIMEOUT_MS,
            "ms" to elapsedMs(),
        )
        // 先挂表再开收音：桩服务（绑得上、一个回调都不回）到这里就是「有 start、再无一行」，
        // 没有这条时限的话 UI 会永远停在「识别中」。
        mainHandler.removeCallbacks(startWatchdog)
        mainHandler.postDelayed(startWatchdog, AsrTiming.FIRST_CALLBACK_TIMEOUT_MS)
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
        clearWatchdogs()
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
            "limit_ms" to AsrTiming.FINISH_TIMEOUT_MS,
            "first_result" to traceFirstResultLogged,
            "ms" to elapsedMs(),
            *traceVad(),
        )
        // 兜底：个别识别服务松手后既不回 onResults 也不回 onError。按住-说话没有「再点一次」
        // 可供恢复，若不复位，语音键会一直卡在「收音中」——再按也不会重新开始。
        // 收音阶段的两个时限到此交棒给收尾时限（会话已经不会再变长了）。
        mainHandler.removeCallbacks(startWatchdog)
        mainHandler.removeCallbacks(sessionWatchdog)
        mainHandler.removeCallbacks(finishWatchdog)
        mainHandler.postDelayed(finishWatchdog, AsrTiming.FINISH_TIMEOUT_MS)
    }

    /** 开始收音后的兜底：识别服务一个回调都不回（桩服务）→ 复位并给出可操作文案。 */
    private val startWatchdog = Runnable {
        if (!isListening || traceCallbackSeen) return@Runnable
        AsrTrace.w(
            traceSession,
            AsrTrace.EV_TIMEOUT,
            "stage" to AsrTiming.STAGE_START,
            "limit_ms" to AsrTiming.FIRST_CALLBACK_TIMEOUT_MS,
            "ms" to elapsedMs(),
            *traceVad(),
        )
        resetOnTimeout(AsrTiming.STAGE_START)
    }

    /**
     * 会话硬上限：到点仍在收音，就按「用户已经松手」处理。
     *
     * 走 [finish] 而不是直接取消 —— 用户可能真的说了话，`stopListening()` 后识别服务
     * 还会把最终结果回过来；收尾若仍无结果，由 [finishWatchdog] 兜住并复位。
     */
    private val sessionWatchdog = Runnable {
        if (!isListening) return@Runnable
        AsrTrace.w(
            traceSession,
            AsrTrace.EV_TIMEOUT,
            "stage" to AsrTiming.STAGE_SESSION,
            "limit_ms" to AsrTiming.SESSION_TIMEOUT_MS,
            "first_result" to traceFirstResultLogged,
            "ms" to elapsedMs(),
            *traceVad(),
        )
        finish()
    }

    /** 松手后等待结果的兜底超时：复位状态并按「没听清」提示用户重说。 */
    private val finishWatchdog = Runnable {
        if (!isListening) return@Runnable
        AsrTrace.w(
            traceSession,
            AsrTrace.EV_TIMEOUT,
            "stage" to AsrTiming.STAGE_FINISH,
            "limit_ms" to AsrTiming.FINISH_TIMEOUT_MS,
            "first_result" to traceFirstResultLogged,
            "ms" to elapsedMs(),
            *traceVad(),
        )
        resetOnTimeout(AsrTiming.STAGE_FINISH)
    }

    /**
     * 超时统一出口：销毁识别器、清回调、按阶段给出用户可读文案。
     *
     * 三条时限都必须走到这里 —— 「复位但什么都不说」等于把缺陷从「卡住」改成「静默失败」，
     * 门禁不允许（角色规程：禁止静默失败）。
     */
    private fun resetOnTimeout(stage: String) {
        clearWatchdogs()
        isListening = false
        recognizer?.destroy()
        recognizer = null
        val notify = onError
        clearCallbacks()
        notify?.invoke(context.getString(AsrTiming.messageFor(stage)))
    }

    /** 摘掉全部兜底时限（会话结束 / 销毁时调用，避免旧会话的表打到新会话上）。 */
    private fun clearWatchdogs() {
        mainHandler.removeCallbacks(startWatchdog)
        mainHandler.removeCallbacks(sessionWatchdog)
        mainHandler.removeCallbacks(finishWatchdog)
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
        clearWatchdogs()
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
        traceCallbackSeen = true
        val text = firstResult(partialResults) ?: return
        traceFirstResult("partial", text.length)
        post { onPartial?.invoke(text) }
    }

    override fun onResults(results: Bundle?) {
        clearWatchdogs()
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
        traceCallbackSeen = true
        mainHandler.removeCallbacks(finishWatchdog)
        mainHandler.removeCallbacks(startWatchdog)
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
        // 会话到此结束（不再重试）：会话硬上限也摘掉，别把这条已失败的消息再打一遍
        mainHandler.removeCallbacks(sessionWatchdog)
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

    override fun onReadyForSpeech(params: Bundle?) {
        // 「服务活着」的最早信号：有它就没必要触发 startWatchdog，哪怕用户还没出声
        traceCallbackSeen = true
    }

    override fun onBeginningOfSpeech() {
        traceCallbackSeen = true
        traceVadBegan = true
    }

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        traceCallbackSeen = true
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

    // 超时取值不在这里：全链路时限集中在 [AsrTiming]（VOCA-231，禁止散落魔法值）
}

/**
 * 语音助手桩服务的保护权限：识别服务声明它，说明这个服务不是给转写用的（实测华为 ALN-AL00）。
 * 判据见 [isUsableRecognitionService]。
 */
internal const val STUB_RECOGNITION_PERMISSION = "android.permission.BIND_VOICE_INTERACTION"

/**
 * 「声明了 [permission] 的识别服务能不能用」——[VoiceInputController.hasUsableService] 的判据，
 * 抽成纯函数便于单测（这条判错了就是「点语音键没反应」，不该只能靠真机才发现）。
 *
 * 实测依据（2026-11-16，API 34 google_apis 镜像 / 华为 ALN-AL00）：
 * - 真识别服务（`com.google.android.as/.AiAiSpeechRecognitionService`、
 *   `com.google.android.tts/.GoogleTTSRecognitionService`）在接口 action 下命中，且 **permission=null**；
 * - 该镜像 `pm list permissions` 里既没有 `BIND_SPEECH_RECOGNITION_SERVICE`，也没有旧代码写的
 *   `BIND_RECOGNITION_SERVICE`（后者在系统里根本不存在）；
 * - 华为 ALN-AL00 的 `FakeRecognitionService` 声明 [STUB_RECOGNITION_PERMISSION]，
 *   实测唯一要拦的就是它。
 *
 * 旧实现要求「权限恰好等于 `android.permission.BIND_RECOGNITION_SERVICE`」：那个字符串不存在，
 * `usable` 于是恒为 0，语音键在**任何一台正常机器**上都被拦成 `blocked reason=no_usable_service`，
 * 压根进不到「识别中」（本单要复测的超时分支因此永远复测不到）。
 * 现在只拦「明确挂在语音助手权限上的桩」，其余一律放行：判错的代价从「功能永久不可用」
 * 降为「由 [AsrTiming] 的时限在 10 秒内复位并给出可读文案」——可恢复的错误才是可接受的错误。
 */
internal fun isUsableRecognitionService(permission: String?): Boolean =
    permission != STUB_RECOGNITION_PERMISSION
