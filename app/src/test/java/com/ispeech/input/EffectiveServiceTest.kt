package com.ispeech.input

import com.ispeech.settings.store.AsrEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ev=bind` 的 `effective=` 判据回归测试（VOCA-231 质检打回 2）。
 *
 * 旧字段 `first` 打的是 `queryServices()` 返回列表的第一项 —— 顺序由 PackageManager 决定，
 * 与真正绑到谁无关。质检设桩复跑时它照样打 `…AiAiSpeechRecognitionService`、`stubs=0`，
 * 而实际接管的是桩：本单的验收项之一恰是「日志打出识别服务绑定结果」，这个字段把人带错了方向。
 *
 * 现在的口径：CUSTOM 取配置里的组件，其余读系统 secure 设置（设了桩就是桩）。
 */
class EffectiveServiceTest {

    @Test
    fun `SYSTEM 引擎读 secure 设置——设了桩就必须打出桩`() {
        assertEquals(
            "com.ispeech.ime/com.ispeech.debug.StubRecognitionService",
            resolveEffectiveService(
                AsrEngine.SYSTEM,
                "",
                "com.ispeech.ime/com.ispeech.debug.StubRecognitionService",
            ),
        )
    }

    @Test
    fun `SYSTEM 引擎未设 secure 值时只能记 system_default，不猜具体组件`() {
        assertEquals("system_default", resolveEffectiveService(AsrEngine.SYSTEM, "", null))
        assertEquals("system_default", resolveEffectiveService(AsrEngine.SYSTEM, "", ""))
        assertEquals("system_default", resolveEffectiveService(AsrEngine.SYSTEM, "", "   "))
    }

    @Test
    fun `CUSTOM 引擎优先用配置的组件，且不受 secure 值影响`() {
        assertEquals(
            "com.vendor.asr/com.vendor.asr.Service",
            resolveEffectiveService(
                AsrEngine.CUSTOM,
                "com.vendor.asr/com.vendor.asr.Service",
                "com.ispeech.ime/com.ispeech.debug.StubRecognitionService",
            ),
        )
    }

    @Test
    fun `CUSTOM 的短类名写法按 ComponentName 规则展开为绝对类名`() {
        assertEquals(
            "com.vendor.asr/com.vendor.asr.Service",
            resolveEffectiveService(AsrEngine.CUSTOM, "com.vendor.asr/.Service", null),
        )
    }

    @Test
    fun `CUSTOM 但组件串解不开时原样回填，至少留下用户配了什么`() {
        assertEquals(
            "not-a-component",
            resolveEffectiveService(AsrEngine.CUSTOM, "not-a-component", null),
        )
    }

    @Test
    fun `CUSTOM 但组件留空时回退读 secure 设置`() {
        assertEquals(
            "com.ispeech.ime/com.ispeech.debug.StubRecognitionService",
            resolveEffectiveService(
                AsrEngine.CUSTOM,
                "",
                "com.ispeech.ime/com.ispeech.debug.StubRecognitionService",
            ),
        )
    }
}