package com.ispeech.engine

import android.content.Context
import java.io.InputStream

/** 五笔词典定位：内置 assets/wubi86.dict。 */
object WubiLocator {
    /** 词典文件名（assets 目录下）。 */
    const val FILE_NAME = "wubi86.dict"

    /** 打开内置五笔词典，失败（文件缺失等）返回 null。 */
    fun openDict(context: Context): InputStream? = try {
        context.assets.open(FILE_NAME)
    } catch (e: Exception) {
        null
    }
}
