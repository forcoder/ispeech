package com.ispeech.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别服务可用性判据回归测试（VOCA-231 / VOCA-310）。
 *
 * 这条判据是语音键的前置门禁：判成「不可用」用户就永远进不到「识别中」，
 * 本单要复测的超时分支也随之复测不到。旧实现比对的是一个系统里不存在的权限字符串，
 * 于是在**所有正常机器**上恒判不可用 —— 用 2026-11-16 在 API 34 google_apis 镜像与
 * 华为 ALN-AL00 上实测到的四种取值把它钉住。
 */
class RecognitionServiceUsabilityTest {

    @Test
    fun `真识别服务不声明权限（实测 AiAi 与 GoogleTTS 均为 permission=null）必须判可用`() {
        assertTrue(isUsableRecognitionService(null))
        assertTrue(isUsableRecognitionService(""))
        assertTrue(isUsableRecognitionService("   "))
    }

    @Test
    fun `声明识别服务权限的服务判可用`() {
        assertTrue(isUsableRecognitionService("android.permission.BIND_SPEECH_RECOGNITION_SERVICE"))
    }

    @Test
    fun `语音助手桩服务（实测华为 ALN-AL00 的 FakeRecognitionService）判不可用`() {
        assertFalse(isUsableRecognitionService(STUB_RECOGNITION_PERMISSION))
    }

    @Test
    fun `判不准时一律放行——误拦的代价是功能永久不可用，误放的代价由 AsrTiming 时限兜住`() {
        assertTrue(isUsableRecognitionService("com.some.vendor.permission.RECOGNITION"))
        assertTrue(isUsableRecognitionService("android.permission.BIND_RECOGNITION_SERVICE"))
    }
}
