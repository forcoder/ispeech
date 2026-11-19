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
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

    /** 单次下载的最大尝试次数（含首次）。 */
    private const val MAX_ATTEMPTS = 3

    /** 重试退避基数（毫秒）：第 n 次失败后等 n × 基数。 */
    private const val RETRY_BACKOFF_MS = 2_000L

    /** 进度回调统一投递到主线程（下载循环在 IO 线程，不能直接碰 UI）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

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

    /** 带可定位类别的下载失败（[category] 会原样写进 logcat）。 */
    private class AsrDownloadException(
        val category: String,
        detail: String,
    ) : Exception("$category: $detail")

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
                    sha256Of = ::sha256,
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

    /**
     * 下载模型压缩包，带**断点续传**与自动重试。
     *
     * 写入 `<ARCHIVE_NAME>.part`：已存在就带 `Range` 续传；只有完整下载并通过大小 / 摘要校验后
     * 才改名为正式文件名。校验失败会删除 `.part`，因此下一次重试从 0 开始，不会卡在 416 循环。
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

                    val sizePlan = AsrDownloadPolicy.planForCompletedFile(part.length())
                    if (sizePlan.action == AsrDownloadPolicy.Action.REJECT) {
                        part.delete()
                        throw AsrDownloadException(
                            sizePlan.category ?: "SIZE_MISMATCH",
                            sizePlan.detail,
                        )
                    }

                    val digestPlan = AsrDownloadPolicy.planForDigest(sha256(part))
                    if (digestPlan.action == AsrDownloadPolicy.Action.REJECT) {
                        part.delete()
                        throw AsrDownloadException(
                            digestPlan.category ?: "DIGEST_MISMATCH",
                            digestPlan.detail,
                        )
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
                        // 退避后重试；合法断点保留，被判非法时上面已删除 `.part`
                        delay(RETRY_BACKOFF_MS * (attempt + 1))
                    }
                }
            }
            throw lastError ?: IllegalStateException("下载失败")
        }

    /**
     * 单次下载（阻塞，运行在 IO 线程）。
     *
     * 写入前先由 [AsrDownloadPolicy.planForResponse] 判定：
     * - 非模型响应（`text/html`）→ 拒绝，不写 `.part`，并清理历史坏 `.part`；
     * - 首次请求 / 服务端忽略 Range 回 200 → 校验 `Content-Length`，从头写（截断旧 `.part`）；
     * - 续传回 206 → 追加写；回 416 → 删除 `.part` 后从头重试；
     * - 只有百分比变化时才回调，避免高频刷 UI。
     */
    private fun fetch(part: File, onProgress: (Int) -> Unit) {
        val existing = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(MODEL_URL)
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body
            val contentLength = body?.contentLength() ?: -1L
            val plan = AsrDownloadPolicy.planForResponse(
                code = response.code,
                contentType = response.header("Content-Type"),
                contentLength = contentLength,
                existingBytes = existing,
            )

            when (plan.action) {
                AsrDownloadPolicy.Action.REJECT -> {
                    // 自愈：把历史垃圾 `.part` 一并清掉，下一次从 0 开始
                    part.delete()
                    throw AsrDownloadException(
                        plan.category ?: "HTTP_${response.code}",
                        plan.detail,
                    )
                }

                AsrDownloadPolicy.Action.RETRY_FROM_START -> {
                    part.delete()
                    throw AsrDownloadException(
                        plan.category ?: "HTTP_${response.code}",
                        plan.detail,
                    )
                }

                AsrDownloadPolicy.Action.APPEND,
                AsrDownloadPolicy.Action.WRITE_FROM_START,
                AsrDownloadPolicy.Action.ACCEPT,
                -> {
                    if (body == null) error("响应为空")

                    val appending = plan.action == AsrDownloadPolicy.Action.APPEND
                    val startAt = plan.startAt
                    // 206 的 contentLength 是「剩余部分」长度，总长要把已下载的加回来
                    val total = if (contentLength > 0) contentLength + startAt else -1L
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
        }
    }

    /** 计算文件的 SHA-256（小写十六进制）。 */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/**
 * 下载决策的纯逻辑（不依赖 Android / 网络），供单测直接断言。
 *
 * 输入「响应状态 / Content-Type / Content-Length / 已下载字节数」，输出「该怎么写」或「该以哪类错误失败」。
 * 从 [FunAsrModelStore] 抽出，是为了让 `app/src/test` 在没有 Android 运行时的情况下也能覆盖判定分支。
 */
internal object AsrDownloadPolicy {

    /** 官方 Release 的权威字节数与 SHA-256（本机实测，勿猜）。 */
    const val EXPECTED_BYTES = 77_920_048L
    const val EXPECTED_SHA256 =
        "da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450"

    /** 没有摘要时的回落下限（有摘要时以摘要为准）。 */
    const val MIN_ARCHIVE_BYTES = 60L * 1024 * 1024

    enum class Action {
        /** 从头写（截断旧 `.part`）。 */
        WRITE_FROM_START,

        /** 从 [Plan.startAt] 追加写。 */
        APPEND,

        /** 删除 `.part`，下一次从 0 重试（例如 416）。 */
        RETRY_FROM_START,

        /** 本次失败（[Plan.category] 给出可定位类别）。 */
        REJECT,

        /** 校验通过。 */
        ACCEPT,
    }

    data class Plan(
        val action: Action,
        val startAt: Long = 0L,
        val category: String? = null,
        val detail: String = "",
    )

    /** `Content-Type` 是否是被拦截页 / 错误页常见的 HTML。 */
    fun isHtml(contentType: String?): Boolean =
        contentType?.lowercase()?.contains("text/html") == true

    /**
     * 写入前的响应判定。
     *
     * @param code HTTP 状态码
     * @param contentType 响应 `Content-Type`（可空）
     * @param contentLength 响应 `Content-Length`；未知时为 -1
     * @param existingBytes 现有 `.part` 字节数（0 表示首次请求）
     */
    fun planForResponse(
        code: Int,
        contentType: String?,
        contentLength: Long,
        existingBytes: Long,
        expectedBytes: Long = EXPECTED_BYTES,
    ): Plan {
        // 416：本地 `.part` 不是服务端认可的断点，删掉后从 0 重来，避免永久 416 循环
        if (code == 416 && existingBytes > 0) {
            return Plan(
                Action.RETRY_FROM_START,
                category = "HTTP_416",
                detail = "Range 请求不可满足，丢弃 .part 后从头重试",
            )
        }
        if (code !in 200..299) {
            return Plan(Action.REJECT, category = "HTTP_$code", detail = "HTTP $code")
        }
        if (isHtml(contentType)) {
            return Plan(
                Action.REJECT,
                category = "NOT_MODEL_PAYLOAD",
                detail = "Content-Type=$contentType（疑似拦截页 / 错误页，不是模型包）",
            )
        }

        if (code == 206 && existingBytes > 0) {
            return Plan(Action.APPEND, startAt = existingBytes)
        }

        // 首次请求，或服务端忽略 Range 回了 200：Content-Length 必须是整包长度
        if (expectedBytes > 0 && contentLength >= 0 && contentLength != expectedBytes) {
            return Plan(
                Action.REJECT,
                category = "SIZE_MISMATCH",
                detail = "Content-Length=$contentLength，预期 $expectedBytes",
            )
        }
        return Plan(Action.WRITE_FROM_START)
    }

    /**
     * 下载完成后的完整性判定。
     *
     * 有摘要时以摘要为准（这里只按权威总长快速失败），无摘要时回落到 [MIN_ARCHIVE_BYTES]。
     */
    fun planForCompletedFile(
        bytes: Long,
        expectedBytes: Long = EXPECTED_BYTES,
        expectedSha256: String = EXPECTED_SHA256,
    ): Plan {
        if (expectedSha256.isNotBlank()) {
            if (expectedBytes > 0 && bytes != expectedBytes) {
                return Plan(
                    Action.REJECT,
                    category = "SIZE_MISMATCH",
                    detail = "下载完成 $bytes 字节，预期 $expectedBytes",
                )
            }
            return Plan(Action.ACCEPT)
        }
        if (bytes < MIN_ARCHIVE_BYTES) {
            return Plan(
                Action.REJECT,
                category = "SIZE_MISMATCH",
                detail = "下载完成 $bytes 字节，小于下限 $MIN_ARCHIVE_BYTES",
            )
        }
        return Plan(Action.ACCEPT)
    }

    /** 摘要判定：配置了期望摘要就必须完全一致；未配置则跳过（由长度兜底）。 */
    fun planForDigest(
        actualSha256: String?,
        expectedSha256: String = EXPECTED_SHA256,
    ): Plan {
        if (expectedSha256.isBlank()) return Plan(Action.ACCEPT)
        val actual = actualSha256?.lowercase()
        val expected = expectedSha256.lowercase()
        return if (actual == expected) {
            Plan(Action.ACCEPT)
        } else {
            Plan(
                Action.REJECT,
                category = "DIGEST_MISMATCH",
                detail = "sha256=$actual，预期 $expected",
            )
        }
    }
}
