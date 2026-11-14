package com.ispeech.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 回归测试：内置 5 位触发码 `dtwsl` → 「泡池须知」。
 *
 * 这条规则的正文是逐字要发给客人的营业说明（11 行 / 281 字，含 emoji、全半角括号、`【】`、
 * `--`、金额与时长、以及一个半角空格），落盘时必须把换行转义成 `\n`，读取时必须原样还原。
 * 任何一处转义 / 解析回归都会在这里暴露，所以除了可读的片段断言，还把正文的 sha256 钉住。
 *
 * 同时验两条排序 / 模式契约：
 * - `exact` 模式：`dtws`（少一位）不能命中，避免打字中途弹出 281 字长文；
 * - 精确命中 + `QUICKAPPLY` 档位必须压过同码、权重 99999 的词库候选。
 */
class QuickPhraseDtwslTest {

    private val trigger = "dtwsl"
    private val label = "泡池须知"

    /** 正文 sha256（与 tools/quick/phrases/dtwsl.txt 一致，改动文案时同步更新）。 */
    private val contentSha256 = "3fb4293d73bfc3e628c859ab03fd3599009bd126bbc68a2add6fe12d0652e248"

    /** Gradle 单测的工作目录是模块目录；IDE 里可能是仓库根目录，两处都兜住。 */
    private fun assetFile(): File =
        listOf(
            File("src/main/assets/quick_phrases.txt"),
            File("app/src/main/assets/quick_phrases.txt"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 quick_phrases.txt（工作目录 ${File(".").absolutePath}）")

    private fun assetEngine(): QuickPhraseEngine {
        val text = assetFile().readText()
        return requireNotNull(QuickPhraseEngine.fromText(text)) { "内置规则表解析失败" }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun `输入 dtwsl 精确命中泡池须知并排在首位`() {
        val result = assetEngine().lookup(trigger, 10)

        assertTrue("应能查到候选", result.isNotEmpty())
        assertEquals(label, result.first().display)
        assertEquals(LookupKind.QUICKAPPLY, result.first().kind)
    }

    @Test
    fun `上屏正文与源文件逐字一致`() {
        val text = assetEngine().lookup(trigger, 10).first().insertText

        assertEquals("sha256 不一致说明正文有字符 / 转义变化", contentSha256, sha256(text))
        assertEquals("应保留 11 行（含 2 个空行）", 11, text.split("\n").size)
        assertEquals("UTF-16 长度（3 个 emoji 各占 2 单元）", 284, text.length)
        assertTrue("首行不能混入触发码或分隔空格", text.startsWith("🌹周六及节假日（不含末日）"))
        assertTrue("末行是泳池说明", text.endsWith("该季节泳池暂不开放"))
        assertTrue("半角空格只有「有详细 说明」这一处", text.count { it == ' ' } == 1)
        assertTrue("不能出现连续空格", !text.contains("  "))
        listOf(
            "【恒温泡池】",
            "💰。",
            "--平台“须知”有详细 说明：",
            "1、150元/池水",
            "恒温功能开2小时+余温可用约4小时",
            "2、若用请提前一天说明",
            "免费送1份中药",
            "瑶浴",
            "缓解疲劳🥰",
            "充气城堡：首2小时100元，之后50元/2小时",
            "【泳池】",
        ).forEach { fragment ->
            assertTrue("正文缺少片段：$fragment", text.contains(fragment))
        }
    }

    @Test
    fun `exact 模式下少一位不命中`() {
        val engine = assetEngine()

        assertTrue("dtws 不应命中整码规则", engine.lookup("dtws", 10).none { it.display == label })
        assertTrue("单字母不应命中整码规则", engine.lookup("d", 10).none { it.display == label })
        assertTrue("完整码仍应命中", engine.lookup(trigger, 10).any { it.display == label })
    }

    @Test
    fun `精确命中压过同码高权重词库候选`() {
        val engine = CompositeEngine(
            listOf(FixedEngine(LookupKind.WUBI, trigger to "同码五笔候选"), assetEngine()),
        )

        assertEquals(label, engine.lookup(trigger, 10).first().display)
    }

    @Test
    fun `内置规则表里 dtwsl 只有一条`() {
        val rows = assetFile().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split("\t") }

        assertEquals("规则行字段应齐全", 6, rows.first().size)
        assertEquals(1, rows.count { it[0].lowercase() == trigger })
        assertEquals(1, rows.count { it[0].lowercase() == "slogan" })
        assertTrue("规则总数不能回落（既有 7 条 + dtwsl）", rows.size >= 8)
    }

    @Test
    fun `其它内置规则照常命中`() {
        val engine = assetEngine()

        // 逐行确认每条内置规则都能按自己的触发码取到，且上屏内容非空
        assetFile().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }
            .filter { it.size >= 3 }
            .forEach { fields ->
                val code = fields[0].trim()
                val name = fields[1].trim()
                val hit = engine.lookup(code, 20).firstOrNull { it.display == name }
                assertTrue("规则 $code（$name）未命中", hit != null)
                assertTrue("规则 $code 的上屏内容为空", hit!!.insertText.isNotEmpty())
            }
    }

    @Test
    fun `转义与反转义往返一致`() {
        val content = "行1\n\t缩进\\\\反斜杠\n行3"
        val row = "zz\t测试\t${escape(content)}\texact\t100\ta"
        val engine = requireNotNull(QuickPhraseEngine.fromText(row))
        val hit = engine.lookup("zz", 10).first()

        assertEquals(content, hit.insertText)
        assertNotEquals(escape(content), hit.insertText)
    }

    /** 与 UserDictStore.escapeLineText 同口径：`\` → `\\`、换行 → `\n`、tab → `\t`。 */
    private fun escape(text: String): String =
        text.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")

    /** 固定返回同码候选的最小引擎，用来验证快捷短语的排序优先级。 */
    private class FixedEngine(
        private val kind: LookupKind,
        private val hit: Pair<String, String>,
    ) : LookupEngine {
        override val displayName: String = "fixed-${kind.label}"

        override fun lookup(input: String, limit: Int): List<LookupCandidate> {
            if (limit <= 0 || !hit.first.equals(input, ignoreCase = true)) return emptyList()
            return listOf(LookupCandidate(hit.first, hit.second, hit.second, 99_999, kind))
        }
    }
}