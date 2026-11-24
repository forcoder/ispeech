package com.ispeech.settings.keyboard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「init 回写了晚声明的属性」回归测试（VOCA-170）。
 *
 * 修复前 `_chineseInputMethod` / `_spaceLongPressMs` 声明在 init 块之后：Kotlin 按声明顺序
 * 执行属性初始化与 init 块，而 viewModelScope 走 Dispatchers.Main.immediate，构造期首个
 * flow 同步发射时字段还是 null，打开「设置 → 键盘与输入」100% 闪退。
 *
 * KeyboardViewModel 依赖 Android DataStore，无法在 JVM 单测里实例化，所以不变量锁在源码
 * 结构层：把属性挪回 init 块之后，或往 init 块里加一个尚未声明的后置属性写入，都会失败。
 */
class KeyboardViewModelInitOrderTest {

    private val source: String by lazy {
        val relative = "src/main/java/com/ispeech/settings/keyboard/KeyboardViewModel.kt"
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("找不到 KeyboardViewModel.kt，工作目录 ${File(".").absolutePath}")
        file.readText()
    }

    private fun initOffset(): Int = source.indexOf(INIT_HEADER).also {
        assertTrue("KeyboardViewModel 里找不到 init 块，测试口径需要更新", it >= 0)
    }

    /** init 块的正文（按花括号配对截取，不含相邻成员）。 */
    private fun initBody(): String {
        val start = initOffset() + INIT_HEADER.length
        var depth = 1
        var cursor = start
        while (cursor < source.length && depth > 0) {
            when (source[cursor]) {
                '{' -> depth++
                '}' -> depth--
            }
            cursor++
        }
        assertTrue("init 块花括号不配对，测试口径需要更新", depth == 0)
        return source.substring(start, cursor - 1)
    }

    private fun declarationOffset(name: String): Int =
        Regex("(?:private\\s+)?(?:val|var)\\s+$name\\b").find(source)?.range?.first ?: -1

    @Test
    fun `两个后置属性的声明早于init块`() {
        val init = initOffset()
        listOf("_chineseInputMethod", "_spaceLongPressMs").forEach { name ->
            val decl = declarationOffset(name)
            assertTrue("$name 没有找到属性声明", decl >= 0)
            assertTrue("$name 的声明必须早于 init 块，否则 init 里的 collect 回写会命中 null", decl < init)
        }
    }

    @Test
    fun `init块回写的每个后置属性都已先声明`() {
        val init = initOffset()
        val referenced = Regex("\\b(_[A-Za-z][A-Za-z0-9]*)\\b")
            .findAll(initBody())
            .map { it.value }
            .toSet()
        assertTrue("init 块没有回写任何后置属性，测试口径需要更新", referenced.isNotEmpty())
        referenced.forEach { name ->
            val decl = declarationOffset(name)
            assertTrue("init 块回写了 $name，但它的声明不早于 init 块", decl in 0 until init)
        }
    }

    private companion object {
        const val INIT_HEADER = "init {"
    }
}
