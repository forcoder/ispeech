package com.ispeech.data

import android.content.Context
import android.util.Log
import com.ispeech.BuildConfig
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 端侧语音识别模型（Vosk 中文小模型）的本地存储与下载。
 *
 * 模型约 42MB，**不打进 APK**：首次使用离线识别时下载压缩包，解压到 `filesDir/vosk/model`。
 * 这样既不把大二进制提交进版本库，也不让安装包体积翻倍。
 *
 * 就绪判定用 [marker]（模型自带的 `conf/model.conf`）当哨兵：只有**完整解压**后它才会出现，
 * 避免「下到一半 / 解压中断」被误判为可用。
 *
 * 下载与判定与 FunASR 链路共用 [AsrModelDownloader] / [AsrDownloadPolicy]：`.part` 落盘 +
 * `Range` 续传 + 状态码 / `Content-Type` / 长度 / SHA-256 四重判定，任一项不过即删除 `.part` 重来。
 * 解压前逐条校验条目路径是否落在模型目录内（zip-slip 防护），越界即拒绝并清理已解出的内容。
 */
object VoskModelStore {

    private const val TAG = "VoskModelStore"

    /**
     * 模型下载地址（**配置项**，值来自 `app/build.gradle.kts` 的 `VOSK_MODEL_URL`）。
     *
     * 打包时可用 `./gradlew assembleRelease -PvoskModelUrl=https://...` 覆盖，无需改代码；
     * 默认仍是 Vosk 官方源（是否镜像到自有 OSS 属外部资源变更，另行拍板）。
     */
    val MODEL_URL: String
        get() = BuildConfig.VOSK_MODEL_URL

    /** 压缩包文件名（与 [modelDir] 同级，落在 filesDir/vosk 下）。 */
    private const val ARCHIVE_NAME = "model.zip"

    /**
     * 权威包体字节数：2026-11-18 本机对默认源 `curl -sSI` 与整包下载双重实测
     * （`Content-Type: application/zip`、`Accept-Ranges: bytes`）。
     */
    internal const val EXPECTED_BYTES = 43_898_754L

    /** 权威 SHA-256：2026-11-18 本机整包下载实测（`unzip -t` 无错误后才固定）。 */
    internal const val EXPECTED_SHA256 =
        "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"

    /** 模型根目录：filesDir/vosk */
    private fun root(context: Context): File = File(context.filesDir, "vosk")

    /** 解压后的模型目录：filesDir/vosk/model（交给 org.vosk.Model 的路径）。 */
    fun modelDir(context: Context): File = File(root(context), "model")

    /** 完整性哨兵：模型完整解压后才存在。 */
    private fun marker(context: Context): File = File(modelDir(context), "conf/model.conf")

    /** 模型是否已就绪。 */
    fun isReady(context: Context): Boolean = marker(context).isFile

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // 读超时是「两个数据块之间」的间隔，不是整包时长：大文件慢速下载不会被误杀
            .readTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 本模型的权威元数据（地址每次取 [MODEL_URL]，保证 `-P` 覆盖生效）。 */
    private fun artifact(): AsrModelArtifact = AsrModelArtifact(
        url = MODEL_URL,
        fileName = ARCHIVE_NAME,
        expectedBytes = EXPECTED_BYTES,
        expectedSha256 = EXPECTED_SHA256,
        logTag = TAG,
    )

    /**
     * 确保模型可用：已就绪直接返回 true；否则下载并解压。
     *
     * @param onProgress 下载进度（0-100），在主线程回调，供候选条展示
     * @return 成功与否；网络 / 校验 / 解压失败返回 false（不抛异常，失败原因记日志）
     */
    suspend fun ensureModel(context: Context, onProgress: (Int) -> Unit): Boolean {
        val appContext = context.applicationContext
        if (isReady(appContext)) return true
        return runCatching {
            val zip = download(appContext, onProgress)
            extract(zip, appContext)
            zip.delete()
            isReady(appContext)
        }.getOrElse { e ->
            if (e is AsrDownloadException) {
                // 类别可一眼定位：HTTP_<code> / NOT_MODEL_PAYLOAD / SIZE_MISMATCH / DIGEST_MISMATCH / ARCHIVE_ENTRY_ESCAPE
                Log.w(TAG, "语音模型准备失败[${e.category}]: ${e.message}")
            } else {
                Log.w(TAG, "语音模型准备失败: ${e.message}")
            }
            false
        }
    }

    /** 下载模型压缩包到 `filesDir/vosk/model.zip`（`.part` 续传 + 长度 / 摘要校验 + 自动重试）。 */
    private suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        AsrModelDownloader.download(root(context), artifact(), client, onProgress)

    /** 解压到 [modelDir]，失败（含 zip-slip 拒绝）时清掉半个模型。 */
    private fun extract(zip: File, context: Context) = extractZip(zip, modelDir(context))

    /**
     * 解压 [zip] 到 [dest]，去掉压缩包顶层目录（`vosk-model-small-cn-0.22/`）。
     *
     * 解压前先清空目标目录：避免上次失败的残留与本次混在一起，出现「半个模型」。
     * 每个条目都按 canonical 路径校验是否仍在 [dest] 之内，越界（`../` 之类的 zip-slip）
     * 立即以 `ARCHIVE_ENTRY_ESCAPE` 拒绝；一个条目都没有（HTML 拦截页 / 半截包被塞进来）
     * 以 `NOT_ZIP_ARCHIVE` 拒绝。两种失败都会删掉 [dest]，不留半个模型。
     */
    internal fun extractZip(zip: File, dest: File) {
        dest.deleteRecursively()
        dest.mkdirs()
        val canonicalDest = dest.canonicalPath
        var entries = 0
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val slash = entry.name.indexOf('/')
                    // 顶层目录本身（如 "vosk-model-small-cn-0.22/"）直接跳过
                    if (slash <= 0) {
                        zis.closeEntry()
                        continue
                    }
                    val relative = entry.name.substring(slash + 1)
                    if (relative.isEmpty()) {
                        zis.closeEntry()
                        continue
                    }
                    val outFile = File(dest, relative)
                    val canonicalOut = outFile.canonicalPath
                    if (canonicalOut != canonicalDest &&
                        !canonicalOut.startsWith(canonicalDest + File.separator)
                    ) {
                        throw AsrDownloadException(
                            "ARCHIVE_ENTRY_ESCAPE",
                            "条目 ${entry.name} 解压到模型目录之外（$canonicalOut），已拒绝并清理",
                        )
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    entries++
                    zis.closeEntry()
                }
            }
            if (entries == 0) {
                throw AsrDownloadException(
                    "NOT_ZIP_ARCHIVE",
                    "${zip.name} 里没有可解压的模型条目（不是 zip / 空包 / 已被截断）",
                )
            }
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw e
        }
    }
}
