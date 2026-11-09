package com.ispeech.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.ispeech.settings.nav.IspeechNavGraph
import com.ispeech.ui.theme.IspeechTheme

/** 产品名（设置首页 logo 与关于页展示）。 */
const val ISPEECH_NAME = "智语 iSpeech"

/** 版本号（与 app/build.gradle.kts 的 versionName 保持一致）。 */
const val ISPEECH_VERSION = "1.5"

/**
 * 「申请麦克风权限」extra 键名。
 *
 * 输入法服务（com.ispeech.input.IspeechImeService）在未授予 RECORD_AUDIO 时，
 * 以本键为 true 拉起设置页，设置页据此触发系统权限申请对话框。
 */
const val REQUEST_MIC_EXTRA = "com.ispeech.extra.REQUEST_MIC"

/**
 * 设置主界面入口。
 *
 * AndroidManifest 中声明 exported=true 并携带 MAIN / LAUNCHER intent-filter；
 * 输入法候选条右侧齿轮（见 com.ispeech.input.IspeechImeService）也拉起本 Activity。
 *
 * 语音输入需要 RECORD_AUDIO 权限，而输入法服务自身无法弹系统权限对话框，
 * 因此由输入法服务携带 [REQUEST_MIC_EXTRA] 拉起本 Activity 代为申请。
 */
class MainActivity : ComponentActivity() {

    /** 麦克风权限申请结果不在此处理：输入法侧会在下次点击语音键时重新检查权限。 */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果由系统对话框呈现，页面无需追加 UI */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(REQUEST_MIC_EXTRA, false) == true) {
            requestMicrophonePermissionIfNeeded()
        }
        setContent {
            IspeechTheme {
                IspeechNavGraph()
            }
        }
    }

    /** 未被授予 RECORD_AUDIO 时拉起系统权限申请（供输入法语音输入使用）。 */
    private fun requestMicrophonePermissionIfNeeded() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
