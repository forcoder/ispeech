package com.ispeech.settings.store

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 键高默认值回归（VOCA-184 AC8）。
 *
 * 人类在 VOCA-184 选定「键盘整体再高」：默认档 `标准` 由 52dp 提到 **56dp**，阶梯改为
 * 紧凑 48 / 标准 56 / 加高 64。要求「默认值只允许一处字面量、其余引用同一常量」——
 * 三处（`KeyboardSettings.keyHeightDp` 默认值 / `SettingsStore` 读盘兜底 /
 * `SoftKeyboardView.DEFAULT_KEY_HEIGHT_DP`）各写一个数字时，改档位漏掉一处就会出现
 * 「设置页选中标准档、键盘实际仍是旧高度」的静默错配，所以这里既测取值也测「同源」。
 *
 * `SettingsStore` / `SoftKeyboardView` 依赖 Android FrameWork，无法在 JVM 单测里实例化，
 * 所以这两处的不变量锁在源码结构层（与 `KeyboardViewModelInitOrderTest` 同一手法）。
 */
class KeyboardHeightDefaultTest {

    @Test
    fun `默认设置 keyHeightDp 为 56`() {
        assertEquals(56, KeyboardSettings().keyHeightDp)
    }

    @Test
    fun `默认档位是标准且阶梯为 48_56_64`() {
        assertEquals(KeyboardSize.STANDARD, KeyboardSize.DEFAULT)
        assertEquals(
            listOf(48, 56, 64),
            KeyboardSize.entries.map { it.heightDp },
        )
        assertEquals(56, KeyboardSize.DEFAULT_HEIGHT_DP)
        // 默认值必须能反查回默认档，否则设置页三档单选会出现「一个都不选中」
        assertEquals(KeyboardSize.STANDARD, KeyboardSettings().size)
    }

    @Test
    fun `未命中的键高反查回落到默认档而不是写死 STANDARD`() {
        assertEquals(KeyboardSize.DEFAULT, KeyboardSize.fromHeightDp(51))
        assertEquals(KeyboardSize.COMPACT, KeyboardSize.fromHeightDp(48))
        assertEquals(KeyboardSize.TALL, KeyboardSize.fromHeightDp(64))
    }

    @Test
    fun `读盘兜底与键盘视图都引用同一默认常量_不再各写一个数字`() {
        val settingsStore = sourceOf("com/ispeech/settings/store/SettingsStore.kt")
        assertTrue(
            "SettingsStore 的键高兜底必须引用 KeyboardSize.DEFAULT_HEIGHT_DP，不能写 `?: 数字`",
            KEY_HEIGHT_FALLBACK_LITERAL.containsMatchIn(settingsStore).not(),
        )
        assertTrue(
            "SettingsStore 里找不到 KeyboardSize.DEFAULT_HEIGHT_DP 引用",
            settingsStore.contains("KeyboardSize.DEFAULT_HEIGHT_DP"),
        )

        val keyboard = sourceOf("com/ispeech/input/SoftKeyboardView.kt")
        assertFalse(
            "SoftKeyboardView.DEFAULT_KEY_HEIGHT_DP 不能再是 const 数字字面量（那会绕开 KeyboardSize）",
            CONST_KEY_HEIGHT_LITERAL.containsMatchIn(keyboard),
        )
        assertTrue(
            "SoftKeyboardView.DEFAULT_KEY_HEIGHT_DP 应引用 KeyboardSize.DEFAULT_HEIGHT_DP",
            keyboard.contains("KeyboardSize.DEFAULT_HEIGHT_DP"),
        )
    }

    /** 按包路径找到源文件：Gradle 的测试工作目录可能是仓库根，也可能是 `app/`。 */
    private fun sourceOf(packagePath: String): String {
        val relative = "src/main/java/$packagePath"
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("找不到 $packagePath，工作目录 ${File(".").absolutePath}")
        return file.readText()
    }

    private companion object {
        /** `KEY_KEY_HEIGHT_DP] ?: 52` —— 把键高默认值写死在读盘调用股的兜底。 */
        val KEY_HEIGHT_FALLBACK_LITERAL =
            Regex("""KEY_KEY_HEIGHT_DP\]\s*\?:\s*\d+""")

        /** `const val DEFAULT_KEY_HEIGHT_DP = 52`。 */
        val CONST_KEY_HEIGHT_LITERAL =
            Regex("""const val DEFAULT_KEY_HEIGHT_DP\s*=\s*\d+""")
    }
}