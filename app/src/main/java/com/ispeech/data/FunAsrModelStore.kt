package com.ispeech.data

import android.content.Context
import android.util.Log
import com.ispeech.BuildConfig
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * FunASR 离线模型（sherpa-onnx 的 Paraformer-zh-small）的本地存储与下载。
 *
 * 模型约 74MB，**不打进 APK**：首次使用离线识别时下载压缩包，解压到 `filesDir/funasr/model`。
 * 这样既不把大二进制提交进版本库，也不让安装包体积翻倍。
 *
 * **下载支持断点续传**：中途失败 / 被杀进程后再进来，会带着 `Range: bytes=<已下载>-` 继续，
 * 而不是从 0 重来。源站不支持 Range（返回 200）时自动退化为整包重下，不会写坏文件。
 *
 * **只接受真正的模型包**：写入前先校验状态码 / `Content-Type` / `Content-Length`，
 * 下完再校验大小与 SHA-256。托管站返回 HTTP 200 的 HTML 拦截页时不会污染 `.part`。
 * 任何一次判定失败都会删除 `.part`，下一次重试从 0 开始（避免「垃圾 `.part` → 416 死循环」）。
 *
 * 就绪判定用 [marker]（模型自带的 `tokens.txt`）当哨兵：只有**完整解压**后它才会出现，
 * 避免「下到一半 / 解压中断」被误判为可用。
 *
 * 解压用 commons-compress：官方只提供 `.tar.bz2`，而 Android 既没有 `tar` 可执行文件，
 * JDK 也不支持 bzip2，必须走第三方解压库。
 *
 * 下载与判定逻辑在 [AsrModelDownloader] / [AsrDownloadPolicy]，与 Vosk 链路共用（勿在本文件另写一份）。
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

    /** 本模型的权威元数据（地址每次取 [MODEL_URL]，保证 `-P` 覆盖生效）。 */
    private fun artifact(): AsrModelArtifact = AsrModelArtifact(
        url = MODEL_URL,
        fileName = ARCHIVE_NAME,
        expectedBytes = AsrDownloadPolicy.EXPECTED_BYTES,
        expectedSha256 = AsrDownloadPolicy.EXPECTED_SHA256,
        logTag = TAG,
    )

    /**
     * 确保模型可用：已就绪直接返回 true；否则下载（可续传）并解压。
     *
     * @param onProgress 下载进度（0-100），在主线程回调，供候选条展示
     * @return 成功与否；网络 / 校验 / 解压失败返回 false（不抛异常，失败原因记日志）
     */
    suspend fun ensureModel(context: Context, onProgress: (Int) -> Unit): Boolean {
        val appContext = context.applicationContext
        if (isReady(appContext)) return true
        return runCatching {
            val archive = download(appContext, onProgress)
            extract(archive, appContext)
            archive.delete()
            isReady(appContext)
        }.getOrElse { e ->
            if (e is AsrDownloadException) {
                // 类别可一眼定位：HTTP_<code> / NOT_MODEL_PAYLOAD / SIZE_MISMATCH / DIGEST_MISMATCH
                Log.w(TAG, "模型下载失败[${e.category}]: ${e.message}")
            } else {
                Log.w(TAG, "FunASR 模型准备失败: ${e.message}")
            }
            false
        }
    }

    /** 下载模型压缩包（`.part` 续传 + 长度 / 摘要校验 + 自动重试），见 [AsrModelDownloader]。 */
    private suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        AsrModelDownloader.download(root(context), artifact(), client, onProgress)

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
