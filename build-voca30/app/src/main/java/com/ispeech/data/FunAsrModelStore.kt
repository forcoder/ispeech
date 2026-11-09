package com.ispeech.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ispeech.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * FunASR 离线模型（sherpa-onnx 的 Paraformer-zh-small）的本地存储与下载。
 *
 * 模型约 74MB，**不打进 APK**：首次使用离线识别时下载压缩包，解压到 `filesDir/funasr/model`。
 * 这样既不把大二进制提交进版本库，也不让安装包体积翻倍。
 *
 * **下载支持断点续传**：中途失败 / 被杀进程后再进来，会带着 `Range: bytes=<已下载>-` 继续，
 * 而不是从 0 重来。源站不支持 Range（返回 200）时自动退化为整包重下，不会写坏文件。
 *
 * 就绪判定用 [marker]（模型自带的 `tokens.txt`）当哨兵：只有**完整解压**后它才会出现，
 * 避免「下到一半 / 解压中断」被误判为可用。
 *
 * 解压用 commons-compress：官方只提供 `.tar.bz2`，而 Android 既没有 `tar` 可执行文件，
 * JDK 也不支持 bzip2，必须走第三方解压库。
 */
object FunAsrModelStore {

    private const val TAG = "FunAsrModelStore"

    /**
     * 模型下载地址（**配置项**，值来自 `app/build.gradle.kts` 的 `ASR_MODEL_URL`）。
     *
     * 默认指向自有服务器的 nginx 静态目录；打包时可用
     * `./gradlew assembleRelease -PasrModelUrl=https://...` 覆盖，无需改代码。
     * 原包来自 sherpa-onnx 官方 Releases 的 `sherpa-onnx-paraformer-zh-small-2024-03-09.tar.bz2`
     * （GitHub 对国内链路过慢，故镜像到自有服务器）。
     */
    val MODEL_URL: String
        get() = BuildConfig.ASR_MODEL_URL

    /** 压缩包文件名（与 [modelDir] 同级，落在 filesDir/funasr 下）。 */
    private const val ARCHIVE_NAME = "model.tar.bz2"

    /** 整个模型压缩包的最小合理字节数：小于它说明下到一半，按未完成处理。 */
    private const val MIN_ARCHIVE_BYTES = 60L * 1024 * 1024

    /** 单次下载的最大尝试次数（含首次）。 */
    private const val MAX_ATTEMPTS = 3

    /** 重试退避基数（毫秒）：第 n 次失败后等 n × 基数。 */
    private const val RETRY_BACKOFF_MS = 2_000L

    /** 进度回调统一投递到主线程（下载循环在 IO 线程，不能直接碰 UI）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 模型根目录：filesDir/funasr */
    private fun root(context: Context): File = File(context.filesDir, "funasr")

    /** 解压后的模型目录：filesDir/funasr/model（交给 OfflineRecognizer 的路径）。 */
    fun modelDir(context: Context): File = File(root(context), "model")

    /** 完整性哨兵：模型完整解压后才存在。 */
    private fun marker(context: Context): File = File(modelDir(context), "tokens.txt")

    /** 模型是否已下载就绪。 */
    fun isReady(context: Context): Boolean = marker(context).isFile

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // 读超时是「两个数据块之间」的间隔，不是整包时长：大文件慢速下载不会被误杀
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 确保模型可用：已就绪直接返回 true；否则下载（可续传）并解压。
     *
     * @param onProgress 下载进度（0-100），在主线程回调，供候选条展示
     * @return 成功与否；网络 / 解压失败返回 false（不抛异常，失败原因记日志）
     */
    suspend fun ensureModel(context: Context, onProgress: (Int) -> Unit): Boolean {
        val appContext = context.applicationContext
        if (isReady(appContext)) return true
        return runCatching {
            val archive = download(appContext, onProgress)
            extract(archive, appContext)
            archive.delete()
            isReady(appContext)
        }.getOrElse {
            Log.w(TAG, "FunASR 模型准备失败: ${it.message}")
            false
        }
    }

    /**
     * 下载模型压缩包，带**断点续传**与自动重试。
     *
     * 写入 `<ARCHIVE_NAME>.part`：已存在就带 `Range` 续传；只有完整下载后才改名为正式文件名，
     * 因此中断留下的 `.part` 下次能直接续上，而不会被当成完整包。
     */
    private suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = root(context).apply { mkdirs() }
            val target = File(dir, ARCHIVE_NAME)
            val part = File(dir, "$ARCHIVE_NAME.part")

            var lastError: Exception? = null
            repeat(MAX_ATTEMPTS) { attempt ->
                try {
                    fetch(part, onProgress)
                    if (part.length() < MIN_ARCHIVE_BYTES) {
                        error("文件不完整：${part.length()} 字节")
                    }
                    if (target.exists() && !target.delete()) error("无法覆盖旧压缩包")
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    return@withContext target
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "下载失败（第 ${attempt + 1}/${MAX_ATTEMPTS} 次）: ${e.message}")
                    if (attempt < MAX_ATTEMPTS - 1) {
                        // 退避后重试；.part 保留，下一次从断点继续
                        delay(RETRY_BACKOFF_MS * (attempt + 1))
                    }
                }
            }
            throw lastError ?: IllegalStateException("下载失败")
        }

    /**
     * 单次下载（阻塞，运行在 IO 线程）。
     *
     * - `.part` 已有内容 → 发 `Range: bytes=<size>-`；服务器回 206 则**追加**写；
     * - 服务器回 200（不支持 Range）→ 从头写，避免旧片段与整包拼接成坏文件；
     * - 只有百分比变化时才回调，避免高频刷 UI。
     */
    private fun fetch(part: File, onProgress: (Int) -> Unit) {
        val existing = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(MODEL_URL)
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("响应为空")

            val appending = response.code == 206 && existing > 0
            val startAt = if (appending) existing else 0L
            val length = body.contentLength()
            // 206 的 contentLength 是「剩余部分」长度，总长要把已下载的加回来
            val total = if (length > 0) length + startAt else -1L
            if (appending) {
                Log.i(TAG, "断点续传：从 ${startAt / 1024}KB 继续，共 ${total / 1024}KB")
            }

            body.byteStream().use { input ->
                FileOutputStream(part, appending).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = startAt
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                mainHandler.post { onProgress(pct) }
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * 解压 `.tar.bz2` 到 [modelDir]，去掉压缩包顶层目录（`sherpa-onnx-paraformer-zh-small-.../`）。
     *
     * 解压前先清空目标目录：避免上次失败的残留与本次混在一起，出现「半个模型」。
     */
    private fun extract(archive: File, context: Context) {
        val dest = modelDir(context)
        dest.deleteRecursively()
        dest.mkdirs()
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz2 ->
            TarArchiveInputStream(bz2).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    // 只保留顶层目录之后的相对路径，去掉包名目录本身
                    val slash = entry.name.indexOf('/')
                    if (slash > 0) {
                        val relative = entry.name.substring(slash + 1)
                        if (relative.isNotEmpty()) {
                            val outFile = File(dest, relative)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { tar.copyTo(it) }
                            }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }
}