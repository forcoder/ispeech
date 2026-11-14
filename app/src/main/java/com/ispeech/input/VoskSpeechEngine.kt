package com.ispeech.input

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonParser
import com.ispeech.data.VoskModelStore
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧离线语音识别（Vosk）引擎。
 *
 * 与 [VoiceInputController]（系统 [android.speech.SpeechRecognizer]）并列：本类不依赖任何系统
 * 识别服务，模型来自 [VoskModelStore]（首次使用下载到 filesDir），完全离线。
 *
 * 交互是**按住说话式**：Vosk 不做端点检测（没有「说完自动结束」），所以
 * [start] 开始收音、[finish] 结束并把最终结果经 `onFinal` 上抛、[cancel] 丢弃结果。
 * 这正是语音键「再点一次结束」的语义。
 *
 * 线程约束：
 * - [Model] 加载与音频读取都在后台线程，监听回调一律切回主线程，调用方可直接操作 UI；
 * - [Model] 只在首次 [start] 时加载一次并缓存（加载要读几十 MB，不重复做）。
 */
class VoskSpeechEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 模型句柄；首次使用时在后台线程加载，之后复用。 */
    @Volatile
    private var model: Model? = null

    private var recognizer: Recognizer? = null
    private var audio: AudioRecord? = null
    private var worker: Thread? = null

    private val running = AtomicBoolean(false)

    /** 是否正在收音。 */
    @Volatile
    var isListening: Boolean = false
        private set

    /** 模型是否已下载就绪（未就绪时服务侧会先触发下载）。 */
    val isReady: Boolean
        get() = VoskModelStore.isReady(context)

    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /**
     * 开始收音。
     *
     * @param onPartial 中间结果（实时刷新候选条），主线程回调
     * @param onFinal 最终结果（用户结束说话时上抛一次），主线程回调
     * @param onError 失败提示（模型加载失败 / 录音失败），主线程回调
     */
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (isListening) return
        if (!isReady) {
            post { onError("语音模型未就绪") }
            return
        }
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onError = onError
        isListening = true
        // 模型加载很重（几十 MB），放后台线程；加载完再开始收音
        Thread({ runSession() }, "vosk-voice").also { worker = it }.start()
    }

    /** 结束说话并把最终结果上抛（`onFinal`）。 */
    fun finish() {
        // 只置停止标志：收尾（读 finalResult 并回调）由工作线程完成，避免跨线程抢 recognizer
        running.set(false)
    }

    /** 放弃本次识别（不回调结果）。 */
    fun cancel() {
        clearCallbacks()
        running.set(false)
        releaseAudio()
    }

    /** 释放资源（输入法服务 onDestroy 调用）。 */
    fun destroy() {
        clearCallbacks()
        running.set(false)
        releaseAudio()
        runCatching { recognizer?.close() }
        recognizer = null
        runCatching { model?.close() }
        model = null
    }

    private fun clearCallbacks() {
        onPartial = null
        onFinal = null
        onError = null
    }

    /** 后台线程主体：加载模型 → 建识别器 → 循环读音频 → 收尾上抛。 */
    @SuppressLint("MissingPermission") // RECORD_AUDIO 由调用方（服务）在 start 前校验
    private fun runSession() {
        try {
            val loaded = loadModel() ?: run {
                isListening = false
                post { onError?.invoke("语音模型加载失败") }
                return
            }
            val rec = Recognizer(loaded, SAMPLE_RATE.toFloat())
            recognizer = rec
            val record = createAudioRecord() ?: run {
                isListening = false
                runCatching { rec.close() }
                post { onError?.invoke("录音初始化失败，请检查麦克风占用") }
                return
            }
            audio = record
            record.startRecording()
            running.set(true)
            val buffer = ShortArray(BUFFER_SHORTS)
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                if (rec.acceptWaveForm(buffer, read)) {
                    val text = textOf(rec.result)
                    if (text.isNotBlank()) post { onPartial?.invoke(text) }
                } else {
                    val partial = textOfPartial(rec.partialResult)
                    if (partial.isNotBlank()) post { onPartial?.invoke(partial) }
                }
            }
            // 收尾：取最终结果（用户点「结束」时）
            val final = textOf(rec.finalResult)
            post {
                isListening = false
                if (final.isNotBlank()) onFinal?.invoke(final) else onError?.invoke("没听清，请再说一次")
            }
        } catch (e: Exception) {
            Log.w(TAG, "离线识别失败: ${e.message}")
            post {
                isListening = false
                onError?.invoke("离线识别失败：${e.message ?: "未知错误"}")
            }
        } finally {
            releaseAudio()
            runCatching { recognizer?.close() }
            recognizer = null
            worker = null
        }
    }

    /** 加载模型（缓存一次）。 */
    private fun loadModel(): Model? {
        model?.let { return it }
        synchronized(this) {
            model?.let { return it }
            return runCatching { Model(VoskModelStore.modelDir(context).absolutePath) }
                .onFailure { Log.w(TAG, "模型加载失败: ${it.message}") }
                .getOrNull()
                ?.also { model = it }
        }
    }

    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return null
        val size = maxOf(minBuffer, BUFFER_SHORTS * 2)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, size)
        }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }
        return record
    }

    private fun releaseAudio() {
        val record = audio ?: return
        audio = null
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
        runCatching { record.release() }
    }

    /** Vosk 结果 JSON 取 `text` 字段。 */
    private fun textOf(json: String): String = runCatching {
        JsonParser.parseString(json).asJsonObject.get("text")?.asString.orEmpty()
    }.getOrDefault("")

    /** Vosk 中间结果 JSON 取 `partial` 字段。 */
    private fun textOfPartial(json: String): String = runCatching {
        JsonParser.parseString(json).asJsonObject.get("partial")?.asString.orEmpty()
    }.getOrDefault("")

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        const val TAG = "VoskSpeechEngine"

        /** Vosk 中文小模型是 16kHz 单声道 16bit PCM。 */
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SHORTS = 4096
    }
}