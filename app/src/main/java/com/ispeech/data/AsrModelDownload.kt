package com.ispeech.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/*
 * ASR 模型压缩包下载的**唯一**实现：FunASR 的 `.tar.bz2` 与 Vosk 的 `.zip` 走同一套判定与落盘流程。
 *
 * 从 [com.ispeech.data.FunAsrModelStore] 原样抽出（判定口径与失败类别均未改变），两条链路共用是为了避免
 * 出现第二份会各自漂移的下载策略——VOCA-34 修掉的三条缺陷（无 `Range` / 只看状态码 /
 * 无摘要校验）正是「同一段逻辑抄两份、只修一份」的结果。
 *
 * 流程：`<file>.part` 落盘 → 已有断点带 `Range` 续传 → [AsrDownloadPolicy] 逐段判定
 * → 长度与 SHA-256 双双通过才改名成正式文件。任何一次判定失败都删除 `.part`，
 * 下一次从 0 重来（不会卡在 416 死循环）。
 */

/** 单个模型包的权威元数据：地址 + 包文件名 + 整包字节数 + SHA-256（缺一不可，别靠猜）。 */
internal data class AsrModelArtifact(
    val url: String,
    val fileName: String,
    val expectedBytes: Long,
    val expectedSha256: String,
    /** logcat 标签：各链路沿用自己的类名，排障口径不因共用下载器而改变。 */
    val logTag: String,
)

/** 带可定位类别的下载失败（[category] 会原样写进 logcat）。 */
internal class AsrDownloadException(
    val category: String,
    detail: String,
) : Exception("$category: $detail")

/** 计算文件的 SHA-256（小写十六进制）。 */
internal fun sha256Hex(file: File): String {
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

internal object AsrModelDownloader {

    /** 单次下载的最大尝试次数（含首次）。 */
    private const val MAX_ATTEMPTS = 3

    /** 重试退避基数（毫秒）：第 n 次失败后等 n × 基数。 */
    private const val RETRY_BACKOFF_MS = 2_000L

    /** 进度回调统一投递到主线程（下载循环在 IO 线程，不能直接碰 UI）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 下载模型压缩包到 `dir/fileName`，带**断点续传**与自动重试。
     *
     * 写入 `<fileName>.part`：已存在就带 `Range` 续传；只有完整下载并通过大小 / 摘要校验后
     * 才改名为正式文件名。校验失败会删除 `.part`，因此下一次重试从 0 开始，不会卡在 416 循环。
     */
    suspend fun download(
        dir: File,
        artifact: AsrModelArtifact,
        client: OkHttpClient,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, artifact.fileName)
        val part = File(dir, "${artifact.fileName}.part")

        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                fetch(part, artifact, client, onProgress)

                val sizePlan = AsrDownloadPolicy.planForCompletedFile(
                    bytes = part.length(),
                    expectedBytes = artifact.expectedBytes,
                    expectedSha256 = artifact.expectedSha256,
                )
                if (sizePlan.action == AsrDownloadPolicy.Action.REJECT) {
                    part.delete()
                    throw AsrDownloadException(
                        sizePlan.category ?: "SIZE_MISMATCH",
                        sizePlan.detail,
                    )
                }

                val digestPlan = AsrDownloadPolicy.planForDigest(
                    actualSha256 = sha256Hex(part),
                    expectedSha256 = artifact.expectedSha256,
                )
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
                Log.w(artifact.logTag, "下载失败（第 ${attempt + 1}/${MAX_ATTEMPTS} 次）: ${e.message}")
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
    private fun fetch(
        part: File,
        artifact: AsrModelArtifact,
        client: OkHttpClient,
        onProgress: (Int) -> Unit,
    ) {
        val existing = if (part.isFile) part.length() else 0L
        val builder = Request.Builder().url(artifact.url)
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body
            val contentLength = body?.contentLength() ?: -1L
            val plan = AsrDownloadPolicy.planForResponse(
                code = response.code,
                contentType = response.header("Content-Type"),
                contentLength = contentLength,
                existingBytes = existing,
                expectedBytes = artifact.expectedBytes,
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
                        Log.i(artifact.logTag, "断点续传：从 ${startAt / 1024}KB 继续，共 ${total / 1024}KB")
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
}

/**
 * 下载决策的纯逻辑（不依赖 Android / 网络），供单测直接断言。
 *
 * 输入「响应状态 / Content-Type / Content-Length / 已下载字节数」，输出「该怎么写」或「该以哪类错误失败」。
 * 从 [FunAsrModelStore] 抽出，是为了让 `app/src/test` 在没有 Android 运行时的情况下也能覆盖判定分支。
 *
 * 未显式传参时的默认值是 FunASR 包的权威元数据（历史上就如此）；其它模型包必须传入自己的
 * `expectedBytes` / `expectedSha256`，不要借用默认值。
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
