package com.ispeech.data

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * FunASR 模型「解压 → 产物校验 → 原子换入 → 最后写就绪标记」的存储一致性核心。
 *
 * 只依赖 `java.io.File` 与 commons-compress，不碰 Android / 网络 / `Log`，因此
 * `app/src/test`（无 Robolectric）可以用真临时目录 + 真 `.tar.bz2` 覆盖每一条纹路；
 * 联网动作由调用方以 [download] 闭包注入，测试据此断言「自愈走本地整包时零网络请求」。
 *
 * 为什么要有这个文件（VOCA-496 / QA 缺陷 D1）：原先 `isReady()` 拿模型自带的 `tokens.txt`
 * 当唯一哨兵，而它在包里排在 `model.int8.onnx` 之前（QA 实测「哨兵已存在 + onnx 不完整」
 * 共 35 帧 ≈ 7 秒），解压窗口内被杀进程 / 断电 / 存储写满后半截模型会被永久判「就绪」，
 * 语音静默失效且不自愈。现在哨兵是**专用完成标记**，只在全部条目写出、长度逐条对得上、
 * 必需产物齐备之后才写，并且随临时目录一起 rename 到位。
 */
internal object AsrModelInstall {

    /** 模型子目录名（交给 `OfflineRecognizer` 的目录）。 */
    const val MODEL_DIR_NAME = "model"

    /** 与模型目录同级的临时解压目录：全部校验通过后才 rename 成 [MODEL_DIR_NAME]。 */
    const val STAGING_DIR_NAME = "model.staging"

    /** 就绪标记文件名前缀，后接模型身份（见 [modelId]）。 */
    const val READY_MARKER_PREFIX = ".ready-"

    /** 识别器必需的两类产物（对照 `FunAsrSpeechEngine.loadRecognizer`，不硬编码 `.onnx` 变体名）。 */
    private const val TOKENS_FILE_NAME = "tokens.txt"

    private const val COPY_BUFFER_BYTES = 64 * 1024

    /**
     * 模型身份：压缩包权威字节 + 摘要前缀。
     *
     * 它同时是就绪标记文件名的一部分，所以 `EXPECTED_BYTES` / `EXPECTED_SHA256`
     * 任一改动（换模型版本）都会让旧标记的名字对不上 ⇒ **自动失效并重下**，不需要迁移代码。
     */
    fun modelId(
        expectedBytes: Long = AsrDownloadPolicy.EXPECTED_BYTES,
        expectedSha256: String = AsrDownloadPolicy.EXPECTED_SHA256,
    ): String = "b$expectedBytes-s${expectedSha256.take(12)}"

    /**
     * 就绪判定：一次专用标记的文件判断（两次 stat，微秒级）。
     *
     * **刻意不校验模型本体字节 / 摘要** —— 那要在每次冷启动读 81.8 MB 算 SHA-256，
     * AC-4「完整模型二次启动不重复下载」的体验前提就是这里必须 O(1)。完整性由写入侧
     * （[extractAtomically] + 标记最后写 + 原子 rename）保证，缺标记即视为不就绪并走自愈。
     */
    fun isReady(paths: AsrModelPaths, modelId: String): Boolean {
        val marker = paths.readyMarker(modelId)
        // length > 0 挡住「标记被创建但内容没写进去」这一类残缺，代价仍是一次 stat
        return marker.isFile && marker.length() > 0L
    }

    /**
     * 准备模型：就绪即返回；否则按 [AsrModelRepairPolicy] 决定「本地整包重新解压」还是「重新下载」。
     *
     * 全程阻塞 IO，调用方需已在 IO 线程（`ensureModel` 已包 `withContext(Dispatchers.IO)`）；
     * 声明成 `suspend` 只为把可续传下载的重试退避（`delay`）交给 [download] 闭包。
     *
     * @param sha256Of 摘要算法（由 Android 侧注入，核心层不实现 IO 摘要，测试可用轻量实现）
     * @param download 唯一的联网入口：把完整压缩包落到 [AsrModelPaths.archive] 并返回该文件
     * @return 模型是否就绪（标记在位）
     * @throws AsrModelInstallException 解压 / 产物校验 / 换入失败（[AsrModelInstallException.category] 可定位）
     */
    suspend fun prepare(
        paths: AsrModelPaths,
        modelId: String,
        expectedBytes: Long = AsrDownloadPolicy.EXPECTED_BYTES,
        expectedSha256: String = AsrDownloadPolicy.EXPECTED_SHA256,
        sha256Of: (File) -> String,
        download: suspend () -> File,
        log: (AsrModelLog) -> Unit,
    ): Boolean {
        if (isReady(paths, modelId)) return true

        val archive = paths.archive
        val archiveBytes = if (archive.isFile) archive.length() else 0L
        // 摘要只在「标记缺失且本地有包」时才算一次，用于判定本地包能不能直接复用
        val archiveDigest = if (archiveBytes > 0L) runCatching { sha256Of(archive) }.getOrNull() else null

        val plan = AsrModelRepairPolicy.planFor(
            markerReady = false,
            archiveBytes = archiveBytes,
            archiveDigest = archiveDigest,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
        )

        when (plan.action) {
            AsrModelRepairPolicy.Action.READY -> return true

            AsrModelRepairPolicy.Action.RE_EXTRACT -> {
                // 自愈：D1 被杀场景下 77.9 MB 整包就在本地，重新解压即可，不该再走一次网络
                log(AsrModelLog(AsrModelLog.Level.INFO, plan.category, plan.detail))
                val healed = runCatching { extractAtomically(paths, modelId, log) }
                if (healed.isSuccess) {
                    archive.delete()
                    return isReady(paths, modelId)
                }
                log(
                    AsrModelLog(
                        AsrModelLog.Level.WARN,
                        "SELF_HEAL_FALLBACK_DOWNLOAD",
                        "本地整包重新解压仍失败，退回重新下载：${healed.exceptionOrNull()?.message}",
                    ),
                )
            }

            // 重下原因各一条：ARCHIVE_MISSING / SIZE_MISMATCH / DIGEST_MISMATCH
            AsrModelRepairPolicy.Action.RE_DOWNLOAD ->
                log(AsrModelLog(AsrModelLog.Level.WARN, plan.category, plan.detail))
        }

        download()
        extractAtomically(paths, modelId, log)
        archive.delete()
        return isReady(paths, modelId)
    }

    /**
     * 原子解压：解到 [AsrModelPaths.stagingDir]，逐条核对 tar 声明长度与实写字节，
     * 必需产物齐备后**最后写就绪标记**，再把整个临时目录 rename 到位。
     *
     * 任何异常都清掉临时目录并原样抛出 ⇒ 模型主目录要么完整（含标记），要么维持原样，
     * 不会出现「半个模型 + 哨兵已就绪」。
     */
    fun extractAtomically(
        paths: AsrModelPaths,
        modelId: String,
        log: (AsrModelLog) -> Unit,
    ) {
        val staging = paths.stagingDir
        staging.deleteRecursively()
        if (!staging.mkdirs()) {
            throw AsrModelInstallException("STAGING_UNAVAILABLE", "无法创建临时解压目录：${staging.path}")
        }

        var entries = 0
        var totalBytes = 0L
        var onnxName = ""
        var onnxBytes = 0L
        var tokensBytes = 0L

        try {
            paths.archive.inputStream().buffered(COPY_BUFFER_BYTES).use { raw ->
                BZip2CompressorInputStream(raw).use { bz2 ->
                    TarArchiveInputStream(bz2).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            val relative = stripTopDir(entry.name) ?: continue
                            val outFile = safeChild(staging, relative)
                                ?: throw AsrModelInstallException(
                                    "EXTRACT_UNSAFE_ENTRY",
                                    "tar 条目路径越界，已拒绝写出：${entry.name}",
                                )
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                                continue
                            }
                            outFile.parentFile?.mkdirs()
                            val written = copy(tar, outFile)
                            if (written != entry.size) {
                                throw AsrModelInstallException(
                                    "EXTRACT_SHORT_WRITE",
                                    "${outFile.name} 实写 $written 字节，tar 声明 ${entry.size} 字节" +
                                        "（存储写满或包体截断）",
                                )
                            }
                            entries++
                            totalBytes += written
                            when {
                                outFile.name == TOKENS_FILE_NAME -> tokensBytes = written
                                outFile.extension.equals("onnx", ignoreCase = true) -> {
                                    onnxName = outFile.name
                                    onnxBytes = written
                                }
                            }
                        }
                    }
                }
            }

            // 解压产物校验：识别器真正读的就是这两类文件，缺任一类都不能算「就绪」
            if (onnxBytes <= 0L) {
                throw AsrModelInstallException(
                    "ARTIFACT_MISSING_ONNX",
                    "解压产物缺 *.onnx 模型本体：已写出 $entries 条目 / $totalBytes 字节、tokens=$tokensBytes 字节",
                )
            }
            if (tokensBytes <= 0L) {
                throw AsrModelInstallException(
                    "ARTIFACT_MISSING_TOKENS",
                    "解压产物缺 $TOKENS_FILE_NAME：onnx=$onnxName:${onnxBytes}B、共 $entries 条目 / $totalBytes 字节",
                )
            }

            val marker = File(staging, "$READY_MARKER_PREFIX$modelId")
            writeText(
                marker,
                buildString {
                    append("model=").append(modelId).append('\n')
                    append("archive_bytes=").append(paths.archive.length()).append('\n')
                    append("entries=").append(entries).append('\n')
                    append("total_bytes=").append(totalBytes).append('\n')
                    append("onnx=$onnxName:$onnxBytes").append('\n')
                    append("tokens=$TOKENS_FILE_NAME:$tokensBytes").append('\n')
                },
            )

            swapIntoPlace(staging, paths.modelDir)
        } catch (e: Throwable) {
            // 任何一步失败都清掉临时目录：模型主目录要么完整（含标记），要么维持原样
            staging.deleteRecursively()
            val failure = when (e) {
                is AsrModelInstallException -> e
                else -> AsrModelInstallException(
                    "EXTRACT_STREAM_FAILED",
                    "解压读流中断（包体截断 / 存储写满）：${e.message ?: e.javaClass.simpleName}",
                ).also { it.initCause(e) }
            }
            log(
                AsrModelLog(
                    AsrModelLog.Level.WARN,
                    failure.category,
                    "${failure.detail}（模型目录 ${paths.modelDir.path} 未被改动）",
                ),
            )
            throw failure
        }
    }

    /** 结构化日志的唯一渲染口径（Android 层与单测看到同一行文本）。 */
    fun render(event: AsrModelLog): String = "funasr category=${event.category} ${event.detail}"

    /** 去掉压缩包顶层目录（`sherpa-onnx-paraformer-zh-small-.../`），与既有口径一致。 */
    private fun stripTopDir(name: String): String? {
        if (name.startsWith('/')) return null
        val slash = name.indexOf('/')
        if (slash <= 0) return null
        val relative = name.substring(slash + 1)
        return relative.ifEmpty { null }
    }

    /** 逐段拼接并拒绝 `..` / 绝对路径：防止 tar 条目写出模型目录之外（zip-slip）。 */
    private fun safeChild(dest: File, relative: String): File? {
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        return segments.fold(dest) { acc, segment -> File(acc, segment) }
    }

    /** 主目录要么完整要么不存在：先清掉旧的半截残留，再把临时目录整体 rename 到位。 */
    private fun swapIntoPlace(staging: File, modelDir: File) {
        if (modelDir.exists() && !modelDir.deleteRecursively()) {
            throw AsrModelInstallException("MODEL_DIR_BUSY", "无法清空旧模型目录：${modelDir.path}")
        }
        if (!staging.renameTo(modelDir)) {
            throw AsrModelInstallException(
                "RENAME_FAILED",
                "临时目录换入失败：${staging.path} → ${modelDir.path}（重试会重新解压）",
            )
        }
    }

    private fun copy(input: java.io.InputStream, out: File): Long {
        FileOutputStream(out).use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var written = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                written += n
            }
            output.flush()
            return written
        }
    }

    private fun writeText(file: File, text: String) {
        FileOutputStream(file).use { it.write(text.toByteArray()) }
    }
}

/** 模型目录布局（与 `FunAsrModelStore` 的 `filesDir/funasr` 一致）。 */
internal class AsrModelPaths(
    val root: File,
    archiveName: String,
) {
    val modelDir: File = File(root, AsrModelInstall.MODEL_DIR_NAME)
    val stagingDir: File = File(root, AsrModelInstall.STAGING_DIR_NAME)
    val archive: File = File(root, archiveName)

    /** 专用完成标记：名字含模型身份，内容记实际字节，只在一切校验通过后最后写。 */
    fun readyMarker(modelId: String): File = File(modelDir, AsrModelInstall.READY_MARKER_PREFIX + modelId)
}

/** 一条可定位的就绪 / 自愈 / 失败事件（[category] 机器可识别，[detail] 人类可读）。 */
internal data class AsrModelLog(
    val level: Level,
    val category: String,
    val detail: String,
) {
    enum class Level { INFO, WARN }
}

/** 解压 / 产物校验 / 换入失败：[category] 直接进 logcat，便于按关键字定位。 */
internal class AsrModelInstallException(
    val category: String,
    val detail: String,
) : Exception("$category: $detail")

/**
 * 就绪 / 自愈 / 重下三段式决策（纯逻辑，不碰文件系统与网络），供单测逐分支断言。
 *
 * 「自愈优先用本地整包」是这条判据的落点：标记缺失而整包在位且大小 / 摘要都判得过时，
 * 只重新解压即可，零流量；只有整包缺失或大小、摘要不符才重新下载。
 */
internal object AsrModelRepairPolicy {

    enum class Action {
        /** 标记在位，什么都不做（冷启动热路径）。 */
        READY,

        /** 本地整包可用：只重新解压，不联网。 */
        RE_EXTRACT,

        /** 整包缺失或校验不过：重新下载。 */
        RE_DOWNLOAD,
    }

    data class Plan(val action: Action, val category: String, val detail: String)

    fun planFor(
        markerReady: Boolean,
        archiveBytes: Long,
        archiveDigest: String?,
        expectedBytes: Long = AsrDownloadPolicy.EXPECTED_BYTES,
        expectedSha256: String = AsrDownloadPolicy.EXPECTED_SHA256,
    ): Plan {
        if (markerReady) {
            return Plan(Action.READY, "READY", "完成标记在位")
        }
        if (archiveBytes <= 0L) {
            return Plan(
                Action.RE_DOWNLOAD,
                "ARCHIVE_MISSING",
                "就绪标记缺失且本地无整包，需重新下载（预期 $expectedBytes 字节）",
            )
        }
        // 复用下载侧同一套权威判定，不放宽：大小与摘要任一不符即认为本地包不可信
        val sizePlan = AsrDownloadPolicy.planForCompletedFile(archiveBytes, expectedBytes, expectedSha256)
        if (sizePlan.action == AsrDownloadPolicy.Action.REJECT) {
            return Plan(
                Action.RE_DOWNLOAD,
                sizePlan.category ?: "SIZE_MISMATCH",
                "本地整包 $archiveBytes 字节未通过大小判定，需重新下载：${sizePlan.detail}",
            )
        }
        val digestPlan = AsrModelRepairPolicy.digestPlan(archiveDigest, expectedSha256)
        if (digestPlan.action == AsrDownloadPolicy.Action.REJECT) {
            return Plan(
                Action.RE_DOWNLOAD,
                digestPlan.category ?: "DIGEST_MISMATCH",
                "本地整包摘要未通过，需重新下载：${digestPlan.detail}",
            )
        }
        return Plan(
            Action.RE_EXTRACT,
            "SELF_HEAL_RE_EXTRACT",
            "就绪标记缺失，本地整包 $archiveBytes 字节校验通过 ⇒ 只重新解压，零流量",
        )
    }

    /** 摘要判定：拿不到摘要（读盘失败）按不符处理，交给重下兜底。 */
    private fun digestPlan(archiveDigest: String?, expectedSha256: String) =
        AsrDownloadPolicy.planForDigest(
            actualSha256 = archiveDigest ?: "unreadable",
            expectedSha256 = expectedSha256,
        )
}
