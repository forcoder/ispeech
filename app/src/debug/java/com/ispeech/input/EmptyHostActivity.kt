package com.ispeech.input

import android.app.Activity

/**
 * 仪器化测试用的空宿主 Activity（**仅 debug 变体**，见 `app/src/debug/AndroidManifest.xml`）。
 *
 * 存在的唯一理由：`QuickPhrasePanel` 的长按连移判定依赖 `isAttachedToWindow`，而「挂到窗口」
 * 只能靠真实 `Activity.setContentView`。放在产品侧 debug source set 而非 `androidTest`，
 * 是因为 `ActivityScenario` 的 `startActivitySync` 只能拉起与 instrumentation
 * targetPackage 同进程的 Activity（test APK 里的会被判定为不同进程而拒绝）。
 */
class EmptyHostActivity : Activity()
