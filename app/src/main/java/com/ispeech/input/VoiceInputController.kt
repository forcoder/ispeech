package com.ispeech.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /** 宿主应用包名（`EXTRA_CALLING_PACKAGE`），见 [buildIntent]。 */
    private var callingPackage: String? = null

    /** 最近一次 [start] 的设置，用于「识别器忙」时原地重建重试。 */
    private var lastAsr: AsrSettings? = null

    /** 本次会话是否已为 `ERROR_RECOGNIZER_BUSY` 重试过一次（避免无限重试）。 */
    private var retriedBusy: Boolean = false

    /**
     * 开始识别。
     *
     * 回调均在主线程触发；每次调用都会重建识别器，避免个别 ROM 复用旧会话的残留状态。
     *
     * @param asr 语音识别设置（引擎 / 语言 / 部分结果 / 离线优先 / 模型名）
     * @param callingPackage 宿主应用包名（输入法场景下用 `currentInputEditorInfo.packageName`）；
     *   为空时不写该 extra，识别服务按默认逻辑处理
     */
    fun start(
        asr: AsrSettings,
        callingPackage: String? = null,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isAvailable) {
            post { onError(context.getString(R.string.voice_unavailable)) }
            return
        }
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onError = onError
        this.callingPackage = callingPackage
        this.lastAsr = asr
        this.retriedBusy = false
        startListening(asr)
    }

    /** 建（重建）识别器并开始收音；[start] 与「识别器忙」重试共用同一条路径。 */
    private fun startListening(asr: AsrSettings) {
        recognizer?.destroy()
        val rec = createRecognizer(asr)
        rec.setRecognitionListener(this)
        recognizer = rec
        isListening = true
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
        isListening = false
        clearCallbacks()
        recognizer?.destroy()
        recognizer = null
    }

    /** 释放识别器（输入法服务 onDestroy 调用）。 */
    fun destroy() {
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
        post { onPartial?.invoke(text) }
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val text = firstResult(results)
        post {
            if (text != null) {
                onFinal?.invoke(text)
            } else {
                onError?.invoke(context.getString(R.string.voice_no_match))
            }
        }
    }

    override fun onError(error: Int) {
        isListening = false
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

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    /** 取识别结果首条（[SpeechRecognizer.RESULTS_RECOGNITION]），空串视为无结果。 */
    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    /** 常见错误码 → 中文提示。 */
    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> context.getString(R.string.voice_no_match)

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            context.getString(R.string.voice_need_permission)

        else -> context.getString(R.string.voice_error)
    }

    /** 保证回调在主线程执行。 */
    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }
}
