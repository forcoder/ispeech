package com.ispeech

import android.app.Application
import com.ispeech.data.BundledCellDicts
import com.ispeech.di.AppContainer

/**
 * 智语（iSpeech）应用入口。
 * 统一在这里初始化全局依赖容器，供设置界面、输入法服务与同步逻辑复用。
 */
class IspeechApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 初始化统一依赖入口
        AppContainer.init(this)
        // 内置分类词库（搜狗细胞词库搬迁）在后台装配：读 assets + 解析 + 落盘，不能占主线程
        Thread({
            runCatching {
                BundledCellDicts.install(this, AppContainer.cellDict(this))
                AppContainer.invalidateEngine()
            }
        }, "ispeech-bundled-cell-dicts").apply { isDaemon = true }.start()
    }
}