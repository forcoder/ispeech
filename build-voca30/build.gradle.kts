// 顶层构建脚本：统一声明插件版本，子模块按需应用
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
