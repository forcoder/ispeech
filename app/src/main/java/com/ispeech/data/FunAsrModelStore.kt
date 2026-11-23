package com.ispeech.data

import android.content.Context
import android.util.Log
import com.ispeech.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
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
 * **只接受真正的模型包**：写入前先校验状态码 / `Content-Type` / `Content-Length`，
 * 下完再校验大小与 SHA-256。托管站返回 HTTP 200 的 HTML 拦截页时不会污染 `.part`。
 * 任何一次判定失败都会删除 `.part`，下一次重试从 0 开始（避免「垃圾 `.part` → 416 死循环」）。
 *
 * 就绪判定用**专用完成标记**（`model/.ready-<模型身份>`，见 [AsrModelInstall]）：它只在全部 tar 条目
 * 写出、逐条长度核对通过、必需产物齐备之后才写，并随临时目录一起原子 rename 到位。
 * 不再拿模型自带的 `tokens.txt` 当哨兵 —— 它在包里排在 `model.int8.onnx` 之前，
 * 「解压中途被杀 ⇒ 半截模型永久判就绪」正是 D1（VOCA-496）。
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
    fun modelDir(context: Context): File = paths(context).modelDir

    /** 存储层视角的目录布局（模型目录 / 临时目录 / 压缩包）。 */
    private fun paths(context: Context): AsrModelPaths = AsrModelPaths(root(context), ARCHIVE_NAME)

    /** 完整性哨兵：专用完成标记，解压 + 产物校验全部通过后**最后**写，随目录原子换入。 */
    private fun marker(context: Context): File = paths(context).readyMarker(AsrModelInstall.modelId())

    /**
     * 模型是否已下载就绪：只读完成标记，一次文件判断（O(1)）。
     *
     * 冷启动绝不对 81.8 MB 的 `model.int8.onnx` 算摘要 —— AC-4「完整模型二次启动不重复下载」
     * 靠的就是这条热路径足够便宜；换模型版本时标记名字变化，旧标记自动失效。
     */
    fun isReady(context: Context): Boolean =
        AsrModelInstall.isReady(paths(context), AsrModelInstall.modelId())

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
     * 确保模型可用：已就绪直接返回 true；否则先试**本地整包零流量自愈**，
     * 整包缺失 / 摘要不符才下载（可续传），再原子解压 + 校验 + 写完成标记。
     *
     * @param onProgress 下载进度（0-100），在主线程回调，供候选条展示
     * @return 成功与否；网络 / 校验 / 解压失败返回 false（不抛异常，失败原因按 category 记日志）
     */
    suspend fun ensureModel(context: Context, onProgress: (Int) -> Unit): Boolean {
        val appContext = context.applicationContext
        val modelPaths = paths(appContext)
        val modelId = AsrModelInstall.modelId()
        if (AsrModelInstall.isReady(modelPaths, modelId)) return true
        return withContext(Dispatchers.IO) {
            runCatching {
                AsrModelInstall.prepare(
                    paths = modelPaths,
                    modelId = modelId,
                    sha256Of = ::sha256Hex,
                    download = { download(appContext, onProgress) },
                    log = { event -> logEvent(event) },
                )
            }.getOrElse { e ->
                // 一条结构化日志说清「哪一步失败 + 缺的是哪个完成标记」，消除 D1 的静默失败
                val event = when (e) {
                    // 类别可一眼定位：HTTP_<code> / NOT_MODEL_PAYLOAD / SIZE_MISMATCH / DIGEST_MISMATCH
                    is AsrDownloadException ->
                        AsrModelLog(AsrModelLog.Level.WARN, e.category, "模型下载失败：${e.message}")

                    // 解压 / 产物校验 / 换入失败：EXTRACT_SHORT_WRITE / ARTIFACT_MISSING_* / RENAME_FAILED …
                    is AsrModelInstallException ->
                        AsrModelLog(AsrModelLog.Level.WARN, e.category, "模型解压失败：${e.detail}")

                    else -> AsrModelLog(AsrModelLog.Level.WARN, "PREPARE_FAILED", "FunASR 模型准备失败：${e.message}")
                }
                Log.w(TAG, AsrModelInstall.render(event) + " 期望完成标记=${marker(appContext).name}")
                false
            }
        }
    }

    /** 自愈 / 重下原因等结构化事件（category + 实际/期望字节），统一走 [AsrModelInstall.render]。 */
    private fun logEvent(event: AsrModelLog) {
        val text = AsrModelInstall.render(event)
        if (event.level == AsrModelLog.Level.INFO) Log.i(TAG, text) else Log.w(TAG, text)
    }

    /** 下载模型压缩包（`.part` 续传 + 长度 / 摘要校验 + 自动重试），见 [AsrModelDownloader]。 */
    private suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        AsrModelDownloader.download(root(context), artifact(), client, onProgress)
}
