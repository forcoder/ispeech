package com.ispeech.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ispeech.settings.nav.IspeechNavGraph
import com.ispeech.settings.store.SettingsStore
import com.ispeech.ui.theme.IspeechTheme
import kotlinx.coroutines.launch

/** 产品名（设置首页 logo 与关于页展示）。 */
const val ISPEECH_NAME = "智语 iSpeech"

/** 版本号（与 app/build.gradle.kts 的 versionName 保持一致）。 */
const val ISPEECH_VERSION = "1.5"

/**
 * 「申请麦克风权限」extra 键名。
 *
 * 输入法服务（com.ispeech.input.IspeechImeService）在用户**主动点击候选条「去授权」**后，
 * 以本键为 true 拉起设置页：设置页据此触发系统权限申请对话框，并在授权会话结束时
 * `finish()` 回到用户刚才的输入框（见 docs/adr/001-mic-permission-bridge.md 决策 4）。
 * 未授予权限本身不再是拉起本 Activity 的理由——那样会把整个输入场景踢掉（VOCA-166）。
 */
const val REQUEST_MIC_EXTRA = "com.ispeech.extra.REQUEST_MIC"

/**
 * 设置主界面入口。
 *
 * AndroidManifest 中声明 exported=true 并携带 MAIN / LAUNCHER intent-filter；
 * 键盘工具栏的齿轮键（见 com.ispeech.input.SoftKeyboardView）也拉起本 Activity。
 *
 * 语音输入需要 RECORD_AUDIO 权限，而输入法服务自身无法弹系统权限对话框，
 * 因此由输入法服务携带 [REQUEST_MIC_EXTRA] 拉起本 Activity 代为申请，
 * 并把「永久拒绝」结果落进 SettingsStore 供候选条文案使用。
 */
class MainActivity : ComponentActivity() {

    /**
     * 本次是否是由输入法发起的麦克风授权会话。
     *
     * 只有这条会话结束后要 `finish()` 回场；用户自己从桌面 / 齿轮进设置页浏览时不能被打断。
     */
    private var micSessionFromIme = false

    /**
     * 会话已建立、但系统权限框还没弹出去。
     *
     * 设置页声明为 singleTask，输入法以 NEW_TASK 拉起时**可能复用已在后台的实例**（真机上
     * 用户点过齿轮就必然这样），此时只走 onNewIntent / onResume、不走 onCreate；
     * 权限框必须在 STARTED 之后才能发，所以状态在这里过一拍，由 onResume 统一驱动。
     */
    private var micRequestPending = false

    /**
     * 麦克风授权结果：记住「永久拒绝」，然后结束会话回到用户刚才的输入框。
     *
     * 回调一定发生在「一次真实请求被答复之后」，此时 `shouldShowRequestPermissionRationale`
     * 为 false 就是系统的「不再询问」语义（Android 文档契约），可以据此落标志；
     * 拒绝但仍可再问则不写，保持默认 false，用户下次点「去授权」本该重弹系统框。
     */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            writeMicPermanentlyDenied(false)
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            writeMicPermanentlyDenied(true)
        }
        finishMicSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        beginMicSession(intent)
        setContent {
            IspeechTheme {
                IspeechNavGraph()
            }
        }
    }

    /** 复用实例路径：输入法再次拉起时不会走 onCreate，授权会话只能从这里建立。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        beginMicSession(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!micSessionFromIme) return
        if (isMicGranted()) {
            // 覆盖两种「已经在外面把权限开好」的返回：本页系统框回调之外，以及用户在本页手工开完再回来
            writeMicPermanentlyDenied(false)
            finishMicSession()
        } else if (micRequestPending) {
            micRequestPending = false
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 认一次来自输入法的授权会话；普通浏览设置页不会命中。 */
    private fun beginMicSession(intent: Intent?) {
        micSessionFromIme = intent?.getBooleanExtra(REQUEST_MIC_EXTRA, false) == true
        micRequestPending = micSessionFromIme
    }

    private fun isMicGranted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 授权会话结束：回到宿主输入框，不停在设置页 / 登录页（VOCA-166 AC-4）。 */
    private fun finishMicSession() {
        micSessionFromIme = false
        micRequestPending = false
        finish()
    }

    /** DataStore 写失败只影响「永久拒绝」文案精度，不该让设置页崩一次。 */
    private fun writeMicPermanentlyDenied(denied: Boolean) {
        lifecycleScope.launch {
            runCatching { SettingsStore.setMicPermanentlyDenied(this@MainActivity, denied) }
        }
    }
}
