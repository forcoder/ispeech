package com.ispeech.debug

import android.content.Intent
import android.speech.RecognitionService
import android.util.Log

/**
 * **仅 debug 构建**：能绑上、但一个回调都不回的识别服务（VOCA-231 复现装置）。
 *
 * 为什么要这个东西：本单的原始故障形态是「识别服务绑得上、`startListening` 之后再也不回话」，
 * 华为 ALN-AL00 预置的 `FakeRecognitionService` 就是这样 —— UI 停在「识别中」直到 75 秒。
 * 而 Pixel/AVD 上的系统识别服务即使被 `kill -STOP` 冻住，`SpeechRecognizer` 客户端也会在
 * 约 5 秒时自己回 `ERROR_NETWORK`，于是走的是 `ev=error` 路径，**永远走不到**
 * `ev=timeout stage=start` 这条兜底（实测：`adb shell kill -STOP <aiai pid>` 后仍 5.03s 复位）。
 * 没有这个桩，`AsrTiming.FIRST_CALLBACK_TIMEOUT_MS` 的分支在任何一台可用的机器上都验证不到。
 *
 * 用法（不改应用设置，直接让它成为系统默认识别服务）：
 *
 * ```
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb shell settings put secure voice_recognition_service \
 *     com.ispeech.ime/com.ispeech.debug.StubRecognitionService
 * # 点语音键 → 期望 10 秒内 ev=timeout stage=start + ev=ui_reset reason=session_timeout
 * adb shell settings delete secure voice_recognition_service   # 复现完务必还原
 * ```
 *
 * 进 release 的风险为零：本文件在 `src/debug/`，release 变体不编译它，清单里也没有它的声明。
 */
class StubRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // 故意什么都不做：不回 onReadyForSpeech、不回 onResults、出错也不回。
        Log.w(TAG, "ev=stub_start 已收下会话，故意不回任何回调（VOCA-231 复现装置）")
    }

    override fun onStopListening(listener: Callback?) {
        Log.w(TAG, "ev=stub_stop 收到停止请求，仍然不回回调")
    }

    override fun onCancel(listener: Callback?) {
        Log.w(TAG, "ev=stub_cancel 收到取消请求")
    }

    private companion object {
        const val TAG = "StubRecognitionService"
    }
}