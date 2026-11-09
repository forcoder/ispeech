package com.ispeech.data

import com.ispeech.engine.LookupKind
import com.ispeech.engine.QuickMatchMode
import com.ispeech.settings.quick.QuickPhraseCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「通用短语」CSV 导入的回归测试。
 *
 * 覆盖真实导出文件里出现过的四类坑：
 * 1. GBK 编码（Excel 另存）——按 UTF-8 硬解会把中文全变成 ``；
 * 2. 字段内换行（117 条长文本）——按行 split 会把一条短语拆成多行并错位；
 * 3. 孤立的 CR / CRLF 混排（`\r\r\n`）——需要规范化成 `\n`；
 * 4. 同码、无码、1 位字母、超 4 位、`IS_DELETE=1` 这些边界行——跳过要计数、导入要说清。
 */
class PhraseCsvImportTest {

    private val header =
        "UUID,GROUP_NAME,CATEGORY_TYPE,CONTENT,INPUT_CODE,CANDIDATE_INDEX," +
            "IS_DELETE,ORDER_TIMESTAMP,UPDATE_TIMESTAMP,SYNC_STATUS"

    private fun csv(vararg rows: String): String = rows.joinToString("\r\n")

    @Test
    fun `GBK 字节解码后中文完整`() {
        val text = csv(header, "u1,默认,1,鹭影,ly,2,0,1,1,0")
        val bytes = text.toByteArray(charset("GBK"))

        val parsed = PhraseCsvImport.parse(PhraseCsvImport.decode(bytes))

        assertEquals(1, parsed.entries.size)
        assertEquals("ly", parsed.entries[0].trigger)
        assertEquals("鹭影", parsed.entries[0].content)
        assertEquals("鹭影", parsed.entries[0].label)
        assertEquals(LookupKind.PHRASE, parsed.entries[0].kind)
    }

    @Test
    fun `带 BOM 的 UTF-8 也能识别表头`() {
        val text = "\uFEFF" + csv(header, "u1,默认,1,揽墅,ls,2,0,1,1,0")
        val bytes = text.toByteArray(Charsets.UTF_8)

        val parsed = PhraseCsvImport.parse(PhraseCsvImport.decode(bytes))

        assertNull(parsed.formatError)
        assertEquals(1, parsed.entries.size)
        assertEquals("揽墅", parsed.entries[0].content)
    }

    @Test
    fun `引号包裹的多行内容按一条记录解析并规范化换行`() {
        val text = csv(
            header,
            "u1,户型,1,\"湖山鹭韵五街5号\r\r\n【与隔壁栋互通】\r\n2、户型\r\n ★3房5床\",hxww,2,0,1,1,0",
            "u2,默认,1,揽墅,ls,2,0,1,1,0",
        )

        val parsed = PhraseCsvImport.parse(text)

        assertEquals(2, parsed.entries.size)
        assertEquals(2, parsed.totalRows)
        val long = parsed.entries.first { it.trigger == "hxww" }
        // `\r\r\n`（孤立 CR + CRLF）保留为段落空行，`\r\n` 收敛为单换行
        assertEquals("湖山鹭韵五街5号\n\n【与隔壁栋互通】\n2、户型\n ★3房5床", long.content)
        assertEquals("湖山鹭韵五街5号", long.label)
    }

    @Test
    fun `字段里的双引号转义与逗号不影响分列`() {
        val text = csv(header, "u1,其他,1,\"他说,\"\"好\"\"\",qh,2,0,1,1,0")

        val parsed = PhraseCsvImport.parse(text)

        assertEquals(1, parsed.entries.size)
        assertEquals("他说,\"好\"", parsed.entries[0].content)
    }

    @Test
    fun `缺少快捷字母或内容列时返回格式错误`() {
        val parsed = PhraseCsvImport.parse(csv("A,B", "1,2"))

        assertNotNull(parsed.formatError)
        assertTrue(parsed.entries.isEmpty())
    }

    @Test
    fun `表头顺序不敏感并支持别名`() {
        val parsed = PhraseCsvImport.parse(csv("content,input_code", "你好,nh"))

        assertNull(parsed.formatError)
        assertEquals("nh", parsed.entries.single().trigger)
        assertEquals("你好", parsed.entries.single().content)
    }

    @Test
    fun `IS_DELETE 非零的行被跳过`() {
        val text = csv(header, "u1,默认,1,鹭影,ly,2,1,1,1,0")

        val parsed = PhraseCsvImport.parse(text)

        assertTrue(parsed.entries.isEmpty())
        assertEquals(1, parsed.skippedDeleted)
        assertEquals(1, parsed.skippedCount)
    }

    @Test
    fun `同码只保留首条并计数`() {
        val text = csv(
            header,
            "u1,其他,1,第一版说法,ht,2,0,2,2,0",
            "u2,其他,1,第二版说法,ht,2,0,1,1,0",
        )

        val parsed = PhraseCsvImport.parse(text)

        assertEquals(1, parsed.entries.size)
        assertEquals("第一版说法", parsed.entries[0].content)
        assertEquals(1, parsed.skippedDuplicateCode)
    }

    @Test
    fun `无快捷字母或非法快捷字母的行被跳过`() {
        val text = csv(
            header,
            "u1,户型,1,没有简写的长文本,,2,0,1,1,0",
            "u2,其他,1,数字简写,12,2,0,1,1,0",
        )

        val parsed = PhraseCsvImport.parse(text)

        assertTrue(parsed.entries.isEmpty())
        assertEquals(1, parsed.skippedNoCode)
        assertEquals(1, parsed.skippedBadCode)
    }

    @Test
    fun `一位字母用精确匹配_超过四位仍导入并计数`() {
        val text = csv(
            header,
            "u1,其他,1,亲，距离很近,j,2,0,1,1,0",
            "u2,户型,1,长码短语,hxwswwjx,2,0,1,1,0",
            "u3,默认,1,两位短语,ly,2,0,1,1,0",
        )

        val parsed = PhraseCsvImport.parse(text)

        assertEquals(3, parsed.entries.size)
        assertEquals(QuickMatchMode.EXACT, parsed.entries.first { it.trigger == "j" }.mode)
        assertEquals(QuickMatchMode.PREFIX, parsed.entries.first { it.trigger == "hxwswwjx" }.mode)
        assertEquals(QuickMatchMode.PREFIX, parsed.entries.first { it.trigger == "ly" }.mode)
        assertEquals(1, parsed.singleLetterCount)
        assertEquals(1, parsed.longCodeCount)
    }

    @Test
    fun `短名取首行并在重名时追加序号`() {
        val text = csv(
            header,
            "u1,泳池,1,\"关于泳池说明：\r\n第一版\",s1,2,0,1,1,0",
            "u2,泳池,1,\"关于泳池说明：\r\n第二版\",s2,2,0,1,1,0",
        )

        val parsed = PhraseCsvImport.parse(text)

        assertEquals(listOf("关于泳池说明：", "关于泳池说明：·2"), parsed.entries.map { it.label })
    }

    @Test
    fun `短名按码点截断到二十位`() {
        val first = "一二三四五六七八九十一二三四五六七八九十多余部分"
        val text = csv(header, "u1,其他,1,$first,cd,2,0,1,1,0")

        val parsed = PhraseCsvImport.parse(text)

        assertEquals("一二三四五六七八九十一二三四五六七八九十…", parsed.entries.single().label)
        assertEquals(first, parsed.entries.single().content)
    }

    @Test
    fun `多行内容经 QuickPhraseCodec 落盘后能原样读回`() {
        val text = csv(header, "u1,户型,1,\"第一行\r\n第二行\r\n 缩进行\",hxww,2,0,1,1,0")
        val imported = PhraseCsvImport.parse(text).entries

        val stored = QuickPhraseCodec.format(imported)
        val readBack = QuickPhraseCodec.parse(stored)

        assertEquals(1, readBack.size)
        assertEquals(imported[0].content, readBack[0].content)
        assertEquals(imported[0].label, readBack[0].label)
        assertEquals(imported[0].trigger, readBack[0].trigger)
        assertEquals(imported[0].mode, readBack[0].mode)
        assertEquals(imported[0].kind, readBack[0].kind)
    }
}