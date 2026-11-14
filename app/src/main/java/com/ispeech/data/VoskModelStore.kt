package com.ispeech.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
 */
object VoskModelStore {

    private const val TAG = "VoskModelStore"

    /** 模型下载地址（Vosk 官方中文小模型，约 42MB）。 */
    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"

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
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 确保模型可用：已就绪直接返回 true；否则下载并解压。
     *
     * @param onProgress 下载进度（0-100），在主线程回调，供候选条展示
     * @return 成功与否；网络 / 解压失败返回 false（不抛异常，失败原因记日志）
     */
    suspend fun ensureModel(context: Context, onProgress: (Int) -> Unit): Boolean {
        val appContext = context.applicationContext
        if (isReady(appContext)) return true
        return runCatching {
            val zip = download(appContext, onProgress)
            extract(zip, appContext)
            zip.delete()
            isReady(appContext)
        }.getOrElse {
            Log.w(TAG, "语音模型准备失败: ${it.message}")
            false
        }
    }

    /** 下载模型压缩包到 filesDir/vosk/model.zip。 */
    private suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = root(context).apply { mkdirs() }
            val target = File(dir, "model.zip")
            val request = Request.Builder().url(MODEL_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("响应为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    withContext(Dispatchers.Main) { onProgress(pct) }
                                }
                            }
                        }
                    }
                }
            }
            target
        }

    /**
     * 解压到 [modelDir]，去掉压缩包顶层目录（`vosk-model-small-cn-0.22/`）。
     *
     * 解压前先清空目标目录：避免上次失败的残留与本次混在一起，出现「半个模型」。
     */
    private fun extract(zip: File, context: Context) {
        val dest = modelDir(context)
        dest.deleteRecursively()
        dest.mkdirs()
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
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }
}