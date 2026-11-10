package com.ispeech.input

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ispeech.data.FunAsrModelStore
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * FunASR 离线语音识别引擎（基于 sherpa-onnx 运行时 + FunASR 导出的 Paraformer 模型）。
 *
 * 模型来自 [FunAsrModelStore]（首次使用下载到 filesDir），完全离线、不联网、不依赖任何
 * 系统 [android.speech.RecognitionService]（与 [VoskSpeechEngine] 并列的另一条离线链路）。
 *
 * **交互是按住说话式**：Paraformer 是非流式模型，本身不支持边说边出字。为了给出
 * 「文字随说话增长」的观感，这里做**分段解码**：收音期间每约 [DECODE_INTERVAL_SEC] 秒，
 * 用「到目前为止的全部音频」跑一次整段解码，把结果经 `onPartial` 推给面板（伪实时）。
 *
 * 分段解码的代价是 CPU（每次都要重算整段），因此：
 * - 解码跑在**独立的临时 stream** 上，不碰正在收音的主 stream（sherpa-onnx 的 stream 不是线程安全的）；
 * - 同时最多只跑一个分段解码任务（[partialRunning] 闸门），收音结束时等它收尾再出最终结果；
 * - 只在用户开启了这段话时才解，长语音会明显耗电 —— 这是伪实时的固有代价。
 *
 * 线程约束：
 * - [OfflineRecognizer] 加载、音频读取、分段解码都在后台线程，回调一律切回主线程；
 * - 识别器只在首次 [start] 时加载一次并缓存（加载要读几十 MB，不重复做）。
 *
 * **运行标志的所有权**：`running` 只由 [start] 置 `true`、由 [finish] / [cancel] / [destroy] 置
 * `false`，工作线程**绝不**写它（见 [runSession] 的注释）——否则会吞掉加载期间到达的停止请求。
 */
class FunAsrSpeechEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 识别器句柄；首次使用时在后台线程加载，之后复用。 */
    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private var stream: OfflineStream? = null
    private var audio: AudioRecord? = null
    private var worker: Thread? = null

    private val running = AtomicBoolean(false)

    /** 分段解码闸门：同时只允许一个伪实时解码任务。 */
    private val partialRunning = AtomicBoolean(false)

    /** 收音期间累积的全部音频（分段解码要重喂整段）。 */
    private val chunks = ArrayList<FloatArray>()

    /** 是否正在收音。 */
    @Volatile
    var isListening: Boolean = false
        private set

    /** 模型是否已下载就绪（未就绪时服务侧会先触发下载）。 */
    val isReady: Boolean
        get() = FunAsrModelStore.isReady(context)

    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onLevel: ((Float) -> Unit)? = null

    /**
     * 开始收音。
     *
     * @param onPartial 伪实时结果（分段解码，约 1.5 秒一次），主线程回调
     * @param onFinal 最终结果（用户松手时上抛一次），主线程回调
     * @param onError 失败提示（模型加载失败 / 录音失败），主线程回调
     * @param onLevel 音量（0..1），驱动面板波形，主线程回调
     */
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        onLevel: ((Float) -> Unit)? = null,
    ) {
        if (isListening) return
        if (!isReady) {
            post { onError("语音模型未就绪") }
            return
        }
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onError = onError
        this.onLevel = onLevel
        chunks.clear()
        isListening = true
        // 运行标志必须在起线程「之前」置位，且此后只由 finish / cancel 关闭。
        //
        // 模型加载（几十 MB）+ 录音初始化合计可能 1 秒以上，用户完全可能在这之前就松手：
        // 那时 finish() 已经把标志置回 false。若还像以前那样等工作线程走到录音循环再无条件
        // `running.set(true)`，这次「停止」就被覆盖掉了 —— 收音永远不结束（面板一直停在
        // 「正在聆听」），isListening 也一直为 true，下一次按下会被本方法开头的
        // `if (isListening) return` 直接忽略，语音就彻底出不来了。
        running.set(true)
        Thread({ runSession() }, "funasr-voice").also { worker = it }.start()
    }

    /** 结束说话：置停止标志，收尾（解码并回调）由工作线程完成。 */
    fun finish() {
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
        runCatching { stream?.release() }
        stream = null
        runCatching { recognizer?.release() }
        recognizer = null
        chunks.clear()
    }

    private fun clearCallbacks() {
        onPartial = null
        onFinal = null
        onError = null
        onLevel = null
    }

    /** 后台线程主体：加载识别器 → 建流 → 循环读音频 → 解码上抛。 */
    @SuppressLint("MissingPermission") // RECORD_AUDIO 由调用方（服务）在 start 前校验
    private fun runSession() {
        try {
            val loaded = loadRecognizer() ?: run {
                isListening = false
                post { onError?.invoke("语音模型加载失败") }
                return
            }
            val session = loaded.createStream()
            stream = session
            val record = createAudioRecord() ?: run {
                isListening = false
                runCatching { session.release() }
                stream = null
                post { onError?.invoke("录音初始化失败，请检查麦克风占用") }
                return
            }
            audio = record
            // 注意：这里**不能**再做 running.set(true)。加载期间用户可能已经松手，
            // finish() 早已把标志置 false，此时重写会吞掉停止请求（见 [start] 的注释）。
            record.startRecording()

            val buffer = ShortArray(BUFFER_SHORTS)
            var samplesSinceDecode = 0
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                // sherpa-onnx 需要 float32 归一化 PCM
                val f32 = FloatArray(read)
                var sumSquares = 0.0
                for (i in 0 until read) {
                    val v = buffer[i] / 32768f
                    f32[i] = v
                    sumSquares += (v * v).toDouble()
                }
                session.acceptWaveform(f32, SAMPLE_RATE)
                chunks.add(f32)
                samplesSinceDecode += read

                // 音量 → 波形
                val rms = sqrt(sumSquares / read).toFloat()
                val level = (rms * LEVEL_GAIN).coerceIn(0f, 1f)
                post { onLevel?.invoke(level) }

                // 伪实时：每隔约 1.5 秒做一次整段解码
                if (samplesSinceDecode >= SAMPLE_RATE * DECODE_INTERVAL_SEC) {
                    samplesSinceDecode = 0
                    startPartialDecode(loaded)
                }
            }

            // 收尾：等最后一次分段解码结束，避免与它抢识别器
            awaitPartial()
            loaded.decode(session)
            val text = loaded.getResult(session).text
            post {
                isListening = false
                if (text.isNotBlank()) onFinal?.invoke(text.trim()) else onError?.invoke("没听清，请再说一次")
            }
        } catch (e: Exception) {
            Log.w(TAG, "FunASR 离线识别失败: ${e.message}")
            post {
                isListening = false
                onError?.invoke("FunASR 离线识别失败：${e.message ?: "未知错误"}")
            }
        } finally {
            releaseAudio()
            runCatching { stream?.release() }
            stream = null
            chunks.clear()
            worker = null
        }
    }

    /**
     * 分段解码：用「到目前为止的全部音频」在**临时 stream** 上跑一次整段解码。
     *
     * 不碰主 stream（正在被收音线程写入），因此不会与收音冲突；
     * [partialRunning] 保证同一时刻只有一个解码任务在跑。
     */
    private fun startPartialDecode(loaded: OfflineRecognizer) {
        if (!partialRunning.compareAndSet(false, true)) return
        val snapshot = concatChunks()
        if (snapshot.isEmpty()) {
            partialRunning.set(false)
            return
        }
        Thread({
            try {
                val tmp = loaded.createStream()
                try {
                    tmp.acceptWaveform(snapshot, SAMPLE_RATE)
                    loaded.decode(tmp)
                    val text = loaded.getResult(tmp).text.trim()
                    if (text.isNotBlank()) post { onPartial?.invoke(text) }
                } finally {
                    runCatching { tmp.release() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "分段解码失败: ${e.message}")
            } finally {
                partialRunning.set(false)
            }
        }, "funasr-partial").start()
    }

    /** 等最后一次分段解码收尾（最多 2 秒，超时就直接往下走）。 */
    private fun awaitPartial() {
        val deadline = System.currentTimeMillis() + 2000
        while (partialRunning.get() && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(20) }
        }
    }

    /** 把已累积的音频拼成一段（分段解码要重喂整段）。 */
    private fun concatChunks(): FloatArray {
        var total = 0
        chunks.forEach { total += it.size }
        val out = FloatArray(total)
        var offset = 0
        chunks.forEach {
            System.arraycopy(it, 0, out, offset, it.size)
            offset += it.size
        }
        return out
    }

    /** 加载识别器（缓存一次）。模型文件按目录扫描，不硬编码 `.onnx` 变体名。 */
    private fun loadRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        synchronized(this) {
            recognizer?.let { return it }
            return runCatching {
                val dir = FunAsrModelStore.modelDir(context)
                val modelFile = findFirst(dir) { it.extension.equals("onnx", ignoreCase = true) }
                    ?: error("模型目录缺少 .onnx 文件")
                val tokensFile = findFirst(dir) { it.name == "tokens.txt" }
                    ?: error("模型目录缺少 tokens.txt")
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(SAMPLE_RATE, FEATURE_DIM, 0.0f),
                    modelConfig = OfflineModelConfig(
                        paraformer = OfflineParaformerModelConfig(model = modelFile.absolutePath),
                        tokens = tokensFile.absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "paraformer",
                    ),
                    decodingMethod = "greedy_search",
                )
                // 模型来自文件系统而非 assets，assetManager 传 null
                OfflineRecognizer(null, config)
            }
                .onFailure { Log.w(TAG, "模型加载失败: ${it.message}") }
                .getOrNull()
                ?.also { recognizer = it }
        }
    }

    /** 递归查找第一个满足条件的文件（模型压缩包内的子目录层级不固定）。 */
    private fun findFirst(dir: File, predicate: (File) -> Boolean): File? {
        val children = dir.listFiles() ?: return null
        children.forEach { child ->
            if (child.isFile && predicate(child)) return child
        }
        children.forEach { child ->
            if (child.isDirectory) findFirst(child, predicate)?.let { return it }
        }
        return null
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

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        const val TAG = "FunAsrSpeechEngine"

        /** Paraformer 中文模型是 16kHz 单声道 16bit PCM，特征维度 80。 */
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SHORTS = 4096

        /** 分段解码间隔（秒）：越小越「实时」，CPU 与耗电越高。 */
        const val DECODE_INTERVAL_SEC = 1.5

        /** RMS → 波形高度的放大系数（正常说话 RMS 约 0.02~0.15）。 */
        const val LEVEL_GAIN = 8f
    }
}