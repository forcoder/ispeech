package com.ispeech.data

import android.content.Context
import android.util.Log

/**
 * 首启装配：把 assets 里随包分发的分类词库装进 [CellDictStore]。
 *
 * 对齐搜狗「细胞词库随输入法内置」的行为——用户装上输入法就有基础词库，
 * 不必先手动导入。安装幂等：同一份词库（同名同正文）落到同一 id，重复安装按覆盖处理，
 * 因此每次启动都可安全重跑。
 *
 * **必须在后台线程调用**：要读 assets、解析词条并落盘，放主线程会卡首帧（见主线程红线）。
 */
object BundledCellDicts {

    private const val TAG = "BundledCellDicts"

    /** 清单文件：`<assets 文件名> <TAB> <显示名>`，`#` 开头为注释。 */
    private const val MANIFEST = "cell_dicts_manifest.txt"

    /**
     * 读清单并安装尚未安装的内置词库，返回本次新装入的词条数。
     *
     * 任何 IO / 解析异常都只记日志，不抛出——内置词库装不上不应影响输入法启动。
     */
    fun install(context: Context, store: CellDictStore): Int {
        val app = context.applicationContext
        val existing = store.list().map { it.id }.toHashSet()
        var installed = 0
        runCatching {
            app.assets.open(MANIFEST).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val fields = line.split('\t')
                    if (fields.size < 2) continue
                    val assetName = fields[0].trim()
                    val displayName = fields[1].trim().ifEmpty { assetName }
                    if (assetName.isEmpty()) continue

                    val content = runCatching {
                        app.assets.open(assetName).bufferedReader(Charsets.UTF_8).readText()
                    }.getOrNull() ?: continue

                    val id = cellDictId(displayName, content)
                    if (id in existing) continue

                    val result = store.add(displayName, content)
                    existing.add(result.id)
                    installed += result.entries
                    Log.i(TAG, "已安装内置词库「${result.name}」：${result.entries} 条（分类 ${result.category}）")
                }
            }
        }.onFailure { Log.w(TAG, "安装内置词库失败", it) }
        return installed
    }
}