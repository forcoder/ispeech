package com.ispeech.data

import com.ispeech.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Vosk 活链路（[VoskModelStore]）的下载判定与解压防护回归测试。
 *
 * 覆盖 VOCA-34 在 FunASR 上修掉、但 `main` 的 Zip 链路一直缺失的三条能力，外加 zip-slip：
 * 非模型响应被拒、`.part` 必须凑够整包长度、摘要不符判为 `DIGEST_MISMATCH`、
 * `../` 条目拒绝解压且不留半成品文件。
 *
 * 纯 JVM：只碰 java.util.zip、文件系统与 [AsrDownloadPolicy]，不联网、不需要 Android 运行时。
 */
class VoskModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = VoskModelStore.EXPECTED_BYTES
    private val sha256 = VoskModelStore.EXPECTED_SHA256

    // ── 1. 权威元数据（写死，别让下游再猜）──────────────────────

    @Test
    fun `权威包长与摘要来自 2026-11-18 实测且形态合法`() {
        assertEquals(43_898_754L, bytes)
        assertEquals(64, sha256.length)
        assertTrue("sha256 必须是小写十六进制", sha256.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `下载地址走构建配置项，可被 -PvoskModelUrl 覆盖且默认 HTTPS`() {
        // 未传 -P 时 BuildConfig 就是 build.gradle.kts 里的默认值；改默认值必须同步这条断言
        assertEquals(BuildConfig.VOSK_MODEL_URL, VoskModelStore.MODEL_URL)
        assertTrue(VoskModelStore.MODEL_URL.startsWith("https://"))
    }

    // ── 2. 响应判定（非 zip 响应被拒）───────────────────────────

    @Test
    fun `200 的 HTML 拦截页判为 NOT_MODEL_PAYLOAD 而不是模型包`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "text/html; charset=utf-8",
            contentLength = 16_780,
            existingBytes = 0,
            expectedBytes = bytes,
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("NOT_MODEL_PAYLOAD", plan.category)
    }

    @Test
    fun `非 zip 的二进制响应即使 200 也过不了摘要这一关`() {
        // 源站返回 application/octet-stream 的网页快照 / 占位文件：长度对得上也仍要看摘要
        val plan = AsrDownloadPolicy.planForDigest(
            actualSha256 = sha256Hex(zipOf("vosk-model-small-cn-0.22/conf/model.conf" to "x")),
            expectedSha256 = sha256,
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("DIGEST_MISMATCH", plan.category)
    }

    @Test
    fun `Content-Length 与整包不符判为 SIZE_MISMATCH`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "application/zip",
            contentLength = bytes - 1,
            existingBytes = 0,
            expectedBytes = bytes,
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("SIZE_MISMATCH", plan.category)
    }

    @Test
    fun `Content-Type 为 application zip 且长度正确才从头写`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "application/zip",
            contentLength = bytes,
            existingBytes = 0,
            expectedBytes = bytes,
        )

        assertEquals(AsrDownloadPolicy.Action.WRITE_FROM_START, plan.action)
        assertEquals(0L, plan.startAt)
    }

    // ── 3. `.part` 续传 / 自愈 ─────────────────────────────────

    @Test
    fun `带 Range 续传回 206 时从已下载字节处追加`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 206,
            contentType = "application/zip",
            contentLength = bytes - 1_048_576,
            existingBytes = 1_048_576,
            expectedBytes = bytes,
        )

        assertEquals(AsrDownloadPolicy.Action.APPEND, plan.action)
        assertEquals(1_048_576L, plan.startAt)
    }

    @Test
    fun `源站忽略 Range 回 200 整包时截断重写而不是续接`() {
        // 源站对带 Range 的请求回了 200 整包（OSS 的越界区间就是这个行为，见 runbook §6）：
        // 必须截断重写，否则半截 .part 拼在整包前面 = 永久坏包
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "application/zip",
            contentLength = bytes,
            existingBytes = 1_048_576,
            expectedBytes = bytes,
        )

        assertEquals(AsrDownloadPolicy.Action.WRITE_FROM_START, plan.action)
        assertEquals(0L, plan.startAt)
    }

    @Test
    fun `垃圾 part 触发 416 时丢弃后从 0 重试`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 416,
            contentType = null,
            contentLength = -1,
            existingBytes = 1_048_576,
            expectedBytes = bytes,
        )

        assertEquals(AsrDownloadPolicy.Action.RETRY_FROM_START, plan.action)
        assertEquals("HTTP_416", plan.category)
    }

    @Test
    fun `默认源断点越界回 416 加 HTML 错误页时仍按 416 自愈而不是当成模型内容`() {
        // 2026-11-18 实测 alphacephei.com（nginx）：Range: bytes=43898754- → 416 + text/html
        // （OSS 同一情形回 200 整包，落在 WRITE_FROM_START 分支；两侧都必须走得通）
        val plan = AsrDownloadPolicy.planForResponse(
            code = 416,
            contentType = "text/html",
            contentLength = 213,
            existingBytes = bytes,
            expectedBytes = bytes,
        )

        assertEquals(AsrDownloadPolicy.Action.RETRY_FROM_START, plan.action)
        assertEquals("HTTP_416", plan.category)
    }

    @Test
    fun `默认源续传回 206 时长度是剩余字节且与整包自洽`() {
        // 实测（2026-11-18）：Range: bytes=1048576- → 206 / Content-Length 42850178
        val existing = 1_048_576L
        val remaining = 42_850_178L
        val plan = AsrDownloadPolicy.planForResponse(
            code = 206,
            contentType = "application/zip",
            contentLength = remaining,
            existingBytes = existing,
            expectedBytes = bytes,
        )

        assertEquals(AsrDownloadPolicy.Action.APPEND, plan.action)
        assertEquals(existing, plan.startAt)
        assertEquals(bytes, existing + remaining)
    }

    @Test
    fun `part 长度恰好等于整包才算下载完成`() {
        val exact = AsrDownloadPolicy.planForCompletedFile(
            bytes = bytes,
            expectedBytes = bytes,
            expectedSha256 = sha256,
        )
        assertEquals(AsrDownloadPolicy.Action.ACCEPT, exact.action)

        // 半截包（少一个字节）不允许改名成正式文件
        val shortByOne = AsrDownloadPolicy.planForCompletedFile(
            bytes = bytes - 1,
            expectedBytes = bytes,
            expectedSha256 = sha256,
        )
        assertEquals(AsrDownloadPolicy.Action.REJECT, shortByOne.action)
        assertEquals("SIZE_MISMATCH", shortByOne.category)

        val over = AsrDownloadPolicy.planForCompletedFile(
            bytes = bytes + 1,
            expectedBytes = bytes,
            expectedSha256 = sha256,
        )
        assertEquals(AsrDownloadPolicy.Action.REJECT, over.action)
        assertEquals("SIZE_MISMATCH", over.category)
    }

    @Test
    fun `摘要不符判为 DIGEST_MISMATCH（删包重下由集成层覆盖，见 PR 说明）`() {
        val plan = AsrDownloadPolicy.planForDigest(
            actualSha256 = "deadbeef".repeat(8),
            expectedSha256 = sha256,
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("DIGEST_MISMATCH", plan.category)
        assertTrue(plan.detail.contains("sha256="))
        // 本用例只钉住**判定**；「AsrModelDownloader 真删 .part 并重试」需要 Looper，
        // 纯 JVM 下建不起来，该项属集成层证据，由 VOCA-476 真机回归与代码路径评审覆盖。
    }

    @Test
    fun `摘要判定大小写不敏感`() {
        assertEquals(
            AsrDownloadPolicy.Action.ACCEPT,
            AsrDownloadPolicy.planForDigest(sha256.uppercase(), sha256).action,
        )
    }

    @Test
    fun `sha256Hex 输出小写十六进制且随内容变化`() {
        val a = tmp.newFile("a.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = tmp.newFile("b.bin").apply { writeBytes(byteArrayOf(1, 2, 4)) }

        // 与 JDK MessageDigest 同口径（RFC 6234 测试向量 "abc"）
        val abc = tmp.newFile("abc.bin").apply { writeBytes("abc".toByteArray()) }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex(abc),
        )
        assertEquals(64, sha256Hex(a).length)
        assertNotEquals(sha256Hex(a), sha256Hex(b))
    }

    // ── 4. zip-slip 防护 ──────────────────────────────────────

    @Test
    fun `合法 zip 解出模型哨兵文件且去掉顶层目录`() {
        val zip = zipOf(
            "vosk-model-small-cn-0.22/conf/" to null,
            "vosk-model-small-cn-0.22/conf/model.conf" to "am-mvn",
            "vosk-model-small-cn-0.22/README" to "vosk-model-small-cn-0.22",
        )
        val dest = File(tmp.root, "model")

        VoskModelStore.extractZip(zip, dest)

        assertTrue(File(dest, "conf/model.conf").isFile)
        assertEquals("am-mvn", File(dest, "conf/model.conf").readText())
        assertTrue(File(dest, "README").isFile)
    }

    @Test
    fun `含上级目录条目的 zip 被拒且不留任何半成品文件`() {
        val outside = File(tmp.root, "outside")
        assertTrue(outside.mkdirs())
        // dest 比 outside 深两级：越界条目必须落在 outside/work/ 里才证明它真的逃出了 dest
        val dest = File(outside, "work/model")
        val escaped = File(outside, "work/evil.txt")

        val zip = zipOf(
            "vosk-model-small-cn-0.22/conf/model.conf" to "am-mvn",
            // 这一条落在 dest 之内、且排在越界条目**之前**：它一定会被先解出来，
            // 用它证明「先解出来的条目也被清理」，而不是靠异常提前抛出蒙混过去
            "vosk-model-small-cn-0.22/grafana/service" to "still-pwned",
            // 越界条目放最后：确保前面几条已实打实写盘
            "vosk-model-small-cn-0.22/../evil.txt" to "pwned",
        )

        val thrown = runCatching { VoskModelStore.extractZip(zip, dest) }.exceptionOrNull()
        assertTrue("越界条目必须以异常失败，实际=$thrown", thrown is AsrDownloadException)
        assertEquals("ARCHIVE_ENTRY_ESCAPE", (thrown as AsrDownloadException).category)

        // 越界产物没落盘（目标点是 outside/work/evil.txt，不是 outside/evil.txt）
        assertEquals(File(outside, "work/evil.txt").canonicalPath, escaped.canonicalPath)
        assertFalse("越界条目不得写到模型目录之外：${escaped.path}", escaped.exists())
        // 越界之前已解出的条目同样被清理
        assertFalse("已解出的条目必须随目录一起清掉", File(dest, "grafana/service").exists())
        assertFalse("解压目录不得留下半个模型", dest.exists())
    }

    @Test
    fun `绝对路径形态的条目不解到目录外`() {
        val zip = zipOf("/etc/passwd" to "root:x:0:0", "vosk-model-small-cn-0.22/README" to "ok")
        val dest = File(tmp.root, "model")

        VoskModelStore.extractZip(zip, dest)

        assertFalse(File(tmp.root, "etc/passwd").exists())
        assertTrue(File(dest, "README").isFile)
    }

    @Test
    fun `非 zip 内容解压失败且不留半个模型目录`() {
        val notAZip = tmp.newFile("model.zip").apply {
            // 典型的「200 + 长度也对」但内容是 HTML 拦截页
            writeText("<html><body>blocked</body></html>".repeat(64))
        }
        val dest = File(tmp.root, "model")

        val thrown = runCatching { VoskModelStore.extractZip(notAZip, dest) }.exceptionOrNull()
        assertTrue("非 zip 必须失败，实际=$thrown", thrown is AsrDownloadException)
        assertEquals("NOT_ZIP_ARCHIVE", (thrown as AsrDownloadException).category)
        assertFalse("失败后不得残留半个模型目录", dest.exists())
    }

    @Test
    fun `空 zip 也算非法包`() {
        val emptyZip = zipOf()
        val dest = File(tmp.root, "model")

        val thrown = runCatching { VoskModelStore.extractZip(emptyZip, dest) }.exceptionOrNull()

        assertEquals("NOT_ZIP_ARCHIVE", (thrown as AsrDownloadException).category)
        assertFalse(dest.exists())
    }

    // ── 5. FunASR 与 Vosk 共用同一份判定 ───────────────────────

    @Test
    fun `两条链路的默认判定常量都指向同一份 AsrDownloadPolicy`() {
        // FunASR 用包内默认值，Vosk 显式传自己的权威值；同一函数、同一类别字符串
        val funasr = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "text/html",
            contentLength = 1,
            existingBytes = 0,
        )
        val vosk = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "text/html",
            contentLength = 1,
            existingBytes = 0,
            expectedBytes = bytes,
        )

        assertEquals(funasr.category, vosk.category)
        assertEquals(AsrDownloadPolicy.Action.REJECT, vosk.action)
        assertNotEquals(AsrDownloadPolicy.EXPECTED_BYTES, bytes)
    }

    /** 生成一个 zip；value 为 null 表示目录条目。 */
    private fun zipOf(vararg entries: Pair<String, String?>): File {
        val file = File(tmp.root, "asset-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                if (content != null) zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }
}
