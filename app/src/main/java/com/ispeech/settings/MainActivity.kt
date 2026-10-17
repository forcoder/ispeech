package com.ispeech.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ispeech.settings.nav.IspeechNavGraph
import com.ispeech.ui.theme.IspeechTheme

/** 产品名（设置首页 logo 与关于页展示）。 */
const val ISPEECH_NAME = "智语 iSpeech"

/** 版本号（与 app/build.gradle.kts 的 versionName 保持一致）。 */
const val ISPEECH_VERSION = "1.5"

/**
 * 设置主界面入口。
 *
 * AndroidManifest 中声明 exported=true 并携带 MAIN / LAUNCHER intent-filter；
 * 输入法候选条右侧齿轮（见 com.ispeech.input.IspeechImeService）也拉起本 Activity。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IspeechTheme {
                IspeechNavGraph()
            }
        }
    }
}
