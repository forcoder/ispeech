package com.ispeech.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `有可绑定的真服务时放行`() {
        assertNull(RecognitionServices.blockedReason(recognitionAvailable = true, usableServices = 1))
    }

    @Test
    fun `服务不可绑定就判无识别服务而不是无可用服务`() {
        // 两态的 reason 必须分得开：no_recognition_available = 系统侧压根没有可绑的服务，
        // no_usable_service = 枚举到了服务但全是绑得上却永不回结果的桩服务。
        // 用户看到的文案、设备回线后的归因都靠这两个值区分，混成一个就等于没日志。
        assertEquals(
            "no_recognition_available",
            RecognitionServices.blockedReason(recognitionAvailable = false, usableServices = 0),
        )
        assertEquals(
            "no_usable_service",
            RecognitionServices.blockedReason(recognitionAvailable = true, usableServices = 0),
        )
    }

    @Test
    fun `系统说没有服务时优先于候选计数`() {
        // 桩服务能查询到却绑不了：这一按的根因是「没有可绑的服务」，不是「枚举筛空了」
        assertEquals(
            RecognitionServices.REASON_NO_RECOGNITION_AVAILABLE,
            RecognitionServices.blockedReason(recognitionAvailable = false, usableServices = 3),
        )
    }

    @Test
    fun `三个reason字面量是日志契约不许漂移`() {
        // docs/voice-input.md 的取证命令按这些字面量 grep，改名会把线上日志读断
        assertEquals("no_recognition_available", RecognitionServices.REASON_NO_RECOGNITION_AVAILABLE)
        assertEquals("no_usable_service", RecognitionServices.REASON_NO_USABLE_SERVICE)
        assertEquals("service_bind_failed", RecognitionServices.REASON_SERVICE_BIND_FAILED)
    }
}
