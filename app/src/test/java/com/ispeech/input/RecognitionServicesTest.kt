package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别服务枚举与筛选规则（VOCA-310）。
 *
 * `PackageManager.queryIntentServices` 只能在真机跑，所以这里守住的是它两侧都能静态判定的部分：
 * 查询用的 action 取值、以及「绑得上 ≠ 能用」的真服务判据。action 写错的代价不对称——
 * 它不会崩、不会报错，只是每次都静默返回 0 条，语音键于是永远进不到「识别中」。
 */
class RecognitionServicesTest {

    @Test
    fun `枚举服务用服务方声明的 action`() {
        assertEquals("android.speech.RecognitionService", RecognitionServices.SERVICE_INTERFACE)
    }

    @Test
    fun `调用方发起识别的 action 不能拿来枚举服务`() {
        // VOCA-310 的原始缺陷就是把这两个 action 混为一谈：后者没有任何服务用来声明 filter，
        // 拿它查得到 0 条，前置判定于是把有服务的机器也判成「无可用服务」。
        assertNotEquals(
            RecognitionServices.SERVICE_INTERFACE,
            "android.speech.action.RECOGNIZE_SPEECH",
        )
    }

    @Test
    fun `只有以 BIND_RECOGNITION_SERVICE 自保护的服务才算真服务`() {
        val candidates = listOf(
            RecognitionServices.Candidate("com.huawei.vassistant/.FakeRecognitionService", null),
            RecognitionServices.Candidate(
                "com.google.android.as/.speech.AiAiSpeechRecognitionService",
                RecognitionServices.BIND_PERMISSION,
            ),
        )
        assertEquals(
            listOf("com.google.android.as/.speech.AiAiSpeechRecognitionService"),
            RecognitionServices.usable(candidates).map { it.component },
        )
    }

    @Test
    fun `桩服务（无保护权限或别的权限）一律挡在外面`() {
        val stubs = listOf(
            // 华为 ALN-AL00 实测：FakeRecognitionService 不声明任何 android:permission
            RecognitionServices.Candidate("com.huawei.vassistant/.FakeRecognitionService", null),
            // 声明了别的保护权限也不算：这个权限保护的是语音交互会话，不是识别服务
            RecognitionServices.Candidate("com.vendor.stub/.StubService", "android.permission.BIND_VOICE_INTERACTION"),
        )
        assertTrue(RecognitionServices.usable(stubs).isEmpty())
    }

    @Test
    fun `空清单判为无可用服务而不是异常`() {
        assertTrue(RecognitionServices.usable(emptyList()).isEmpty())
    }
}
