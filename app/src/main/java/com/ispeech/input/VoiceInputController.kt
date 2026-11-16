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

    /**
     * 点语音键的前置判定：能进「识别中」返回 `null`，否则返回机器可判的 `reason`，
     * 由调用方据此打 `AsrTrace ev=blocked`（VOCA-310 打回项 2：拦下会话不许没有日志）。
     * 拦下的两种原因怎么分见 [RecognitionServices.blockedReason]。
     */
    fun probeBlockedReason(asr: AsrSettings): String? =
        RecognitionServices.blockedReason(isAvailable, probeUsableServices(asr))

    /**
     * 探测设备上的识别服务：返回**真服务**（受 [RecognitionServices.BIND_PERMISSION] 保护）数量，
     * 并打一行 `AsrTrace ev=bind`（候选数 / 真服务数 / 被过滤数 / 会用哪个组件）。
     *
     * 两个易错点都在这里，改动时请连带看 `tools/verify/check_crosscut.py` 的 8.2b：
     * 1. **action 必须用服务方声明的那个**（[RecognitionServices.SERVICE_INTERFACE]）。
     *    [RecognizerIntent.ACTION_RECOGNIZE_SPEECH] 是**调用方**发起识别用的 action，没有服务拿它
     *    声明 service filter，用它查永远是 0 条，于是语音键在真机上根本进不到「识别中」——
     *    且 `AndroidManifest` 的 `<queries>`（按前者声明）也匹配不上（VOCA-310 的原始症状）。
     * 2. **保护权限过滤不能丢**（[RecognitionServices.usable]）。[isAvailable] 只看「绑得上」，
     *    部分 ROM（实测华为 ALN-AL00）预置的桩服务 `com.huawei.vassistant…FakeRecognitionService`
     *    绑得上却永不回结果，表现是「说完不出字」；真服务才持有该保护权限，据此区分。
     *
     * 埋点必须在拦下会话**之前**打：只留 `ev=blocked reason=no_usable_service` 分不出「真没服务」
     * 和「查错了 action」，那正是本单最初的排查困境。
     */
    private fun probeUsableServices(asr: AsrSettings): Int {
        val candidates = queryRecognizers()
        val usable = RecognitionServices.usable(candidates)
        AsrTrace.i(
            null,
            AsrTrace.EV_BIND,
            "engine" to asr.engine.name,
            "services" to candidates.size,
            "usable" to usable.size,
            "dropped" to candidates.size - usable.size,
            "requested" to requestedComponent(asr),
            "first_usable" to usable.firstOrNull()?.component,
        )
        return usable.size
    }

    /** 设备上声明了 [RecognitionServices.SERVICE_INTERFACE] 的识别服务（查询失败返回空表）。 */
    private fun queryRecognizers(): List<RecognitionServices.Candidate> =
        runCatching {
            context.packageManager.queryIntentServices(
                Intent(RecognitionServices.SERVICE_INTERFACE),
                0,
            )
        }.getOrNull().orEmpty().mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            RecognitionServices.Candidate("${service.packageName}/${service.name}", service.permission)
        }

    /** 本次请求的组件：CUSTOM 用配置值，SYSTEM 交给框架选默认服务。 */
    private fun requestedComponent(asr: AsrSettings): String =
        if (asr.engine == AsrEngine.CUSTOM) {
            ComponentName.unflattenFromString(asr.serviceComponent)
                ?.let { "${it.packageName}/${it.className}" }
                ?: "invalid_component"
        } else {
            SYSTEM_DEFAULT
        }

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
        if (rec == null) {
            // 服务在（探测到了真服务）但识别器建不起来 = 绑定失败。归类清楚，
            // 不静默、也不让整个输入法崩在这一按上（VOCA-310 验收口径 1）。
            AsrTrace.w(
                null,
                AsrTrace.EV_BLOCKED,
                "reason" to RecognitionServices.REASON_SERVICE_BIND_FAILED,
                "engine" to asr.engine.name,
            )
            isListening = false
            recognizer = null
            // 回调在 post 里读：期间用户若已取消（[stop] 清空回调），这里就不该再弹提示
            post { onError?.invoke(context.getString(R.string.voice_service_unavailable)) }
            return
        }
        rec.setRecognitionListener(this)
        recognizer = rec
        isListening = true
        rec.startListening(buildIntent(asr))
    }

    /**
     * 按配置创建识别器：CUSTOM 引擎解析组件成功时用指定服务，否则回退系统默认。
     *
     * 两条路都失败时返回 `null`（**不抛**）——[SpeechRecognizer.createSpeechRecognizer] 在无默认
     * 识别服务 / 无权限时会抛 `RuntimeException`，让它冒出去就是输入法崩在「点语音键」上，
     * 且日志里没有任何痕迹。调用方据此给「服务存在但绑定失败」的明确归类。
     */
    private fun createRecognizer(asr: AsrSettings): SpeechRecognizer? {
        if (asr.engine == AsrEngine.CUSTOM) {
            val component = ComponentName.unflattenFromString(asr.serviceComponent)
            if (component != null) {
                runCatching { SpeechRecognizer.createSpeechRecognizer(context, component) }
                    .getOrNull()
                    ?.let { return it }
            }
        }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
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
        val rec = recognizer ?: return
        if (!isListening) return
        runCatching { rec.stopListening() }
            .onFailure {
                stop()
                return
            }
        // 兜底：个别识别服务松手后既不回 onResults 也不回 onError。按住-说话没有「再点一次」
        // 可供恢复，若不复位，语音键会一直卡在「收音中」——再按也不会重新开始。
        mainHandler.postDelayed(finishWatchdog, FINISH_TIMEOUT_MS)
    }

    /** 松手后等待结果的兜底超时：复位状态并按「没听清」提示用户重说。 */
    private val finishWatchdog = Runnable {
        if (!isListening) return@Runnable
        isListening = false
        recognizer?.destroy()
        recognizer = null
        val notify = onError
        clearCallbacks()
        notify?.invoke(context.getString(R.string.voice_no_match))
    }

    /** 释放识别器（输入法服务 onDestroy 调用）。 */
    fun destroy() {
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
        post { onPartial?.invoke(text) }
    }

    override fun onResults(results: Bundle?) {
        mainHandler.removeCallbacks(finishWatchdog)
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
        mainHandler.removeCallbacks(finishWatchdog)
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
        /** SYSTEM 引擎的埋点取值：默认识别服务由框架选定，我们不指定组件。 */
        const val SYSTEM_DEFAULT = "system_default"

        /** 松手后等待识别结果的上限（毫秒）：超时复位，避免语音键卡在「收音中」。 */
        const val FINISH_TIMEOUT_MS = 6000L
    }
}

/**
 * 设备上识别服务的判定规则（与 `PackageManager` 查询解耦，因此可在 JVM 单测里跑）。
 *
 * 立项目的（VOCA-310）：枚举识别服务时用错了 action——拿**调用方**发起识别用的
 * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` 去查**服务方**声明的 filter，命中集合天然为空，
 * 真机上语音键于是永远被判「无可用服务」，连「识别中」都进不到。查询本身只能在真机验，
 * 但「用哪个 action、按哪个权限筛」这两条判定是纯字符串规则，抽出来才能在单测与静态门禁里守住。
 */
internal object RecognitionServices {

    /**
     * 识别服务**自己**在 manifest 里声明的 action
     * （即 `android.speech.RecognitionService.SERVICE_INTERFACE`，与 `AndroidManifest` 的
     * `<queries>` 声明同一个值）。
     *
     * 它和调用方发起识别用的 `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
     * （`android.speech.action.RECOGNIZE_SPEECH`）不是一回事：枚举服务只能用前者，
     * 发起识别用后者。
     */
    const val SERVICE_INTERFACE = "android.speech.RecognitionService"

    /** 真识别服务必须要求调用方持有的保护权限（`android.Manifest.permission.BIND_RECOGNITION_SERVICE`）。 */
    const val BIND_PERMISSION = "android.permission.BIND_RECOGNITION_SERVICE"

    /**
     * `queryIntentServices` 结果里与判定有关的两列。
     *
     * @param component `包名/类名`
     * @param permission 该 service 声明的 `android:permission`，未声明为 `null`
     */
    data class Candidate(val component: String, val permission: String?)

    /**
     * 真服务清单：只有以 [BIND_PERMISSION] 自保护的服务才可能正常回结果。
     *
     * 丢了这一层，华为 ROM 的桩服务 `com.huawei.vassistant…FakeRecognitionService`
     * （绑得上、永不返回结果）就会被当成可用服务放进来，退化成「说完不出字」。
     */
    fun usable(candidates: List<Candidate>): List<Candidate> =
        candidates.filter { it.permission == BIND_PERMISSION }

    /**
     * 「点了语音键却没进『识别中』」的机器可判原因，进 `AsrTrace ev=blocked reason=`。
     *
     * 字面量是取证契约的一部分（`docs/voice-input.md` 的命令按它 grep），改名等于把线上日志读断，
     * 所以取值由 `tools/verify/check_crosscut.py` 8.2b 与单测双向钉住。
     */
    const val REASON_NO_RECOGNITION_AVAILABLE = "no_recognition_available"
    const val REASON_NO_USABLE_SERVICE = "no_usable_service"
    const val REASON_SERVICE_BIND_FAILED = "service_bind_failed"

    /**
     * 前置判定：系统侧有没有可绑定的识别服务 + 枚举到的真服务数量 → 拦下会话的原因（`null` = 放行）。
     *
     * 抽成纯函数是因为这一按到底该不该拦、该记哪个 reason 是纯判定，而真机查询只能在设备上跑；
     * 「服务可绑定 / 不可绑定」两态于是能在 JVM 单测里守住（VOCA-310）。
     */
    fun blockedReason(recognitionAvailable: Boolean, usableServices: Int): String? = when {
        !recognitionAvailable -> REASON_NO_RECOGNITION_AVAILABLE
        usableServices == 0 -> REASON_NO_USABLE_SERVICE
        else -> null
    }
}
