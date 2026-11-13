package com.ispeech.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载判定逻辑回归测试（纯函数，不联网、不依赖 Android 运行时）。
 *
 * 覆盖真实故障链：托管站返回 HTTP 200 的 HTML 拦截页被当成模型写入 `.part`，
 * 之后对这段 HTML 打 Range 永远 416。这里断言每一类响应都被正确分类，
 * 且坏 `.part` 会触发「删除后从 0 重来」而不是继续续传。
 */
class FunAsrModelStoreTest {

    private val expected = AsrDownloadPolicy.EXPECTED_BYTES

    // ── 1. 响应校验 ────────────────────────────────────────────

    @Test
    fun `首次请求返回 200 的 HTML 判为 NOT_MODEL_PAYLOAD 且不写入`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "text/html; charset=utf-8",
            contentLength = 16_780,
            existingBytes = 0,
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("NOT_MODEL_PAYLOAD", plan.category)
    }

    @Test
    fun `Content-Type 大小写与参数不影响 HTML 判定`() {
        assertTrue(AsrDownloadPolicy.isHtml("Text/HTML; Charset=UTF-8"))
        assertTrue(AsrDownloadPolicy.isHtml("text/html"))
        assertFalse(AsrDownloadPolicy.isHtml("application/octet-stream"))
        assertFalse(AsrDownloadPolicy.isHtml(null))
    }

    @Test
    fun `首次请求 Content-Length 与预期总长不符判为 SIZE_MISMATCH`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "application/octet-stream",
            contentLength = 16_780,
            existingBytes = 0,
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("SIZE_MISMATCH", plan.category)
    }

    @Test
    fun `首次请求长度未知时放行（由摘要兜底）`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "application/octet-stream",
            contentLength = -1,
            existingBytes = 0,
        )

        assertEquals(AsrDownloadPolicy.Action.WRITE_FROM_START, plan.action)
    }

    @Test
    fun `首次请求长度等于预期总长时从头写`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "application/octet-stream",
            contentLength = expected,
            existingBytes = 0,
        )

        assertEquals(AsrDownloadPolicy.Action.WRITE_FROM_START, plan.action)
        assertEquals(0L, plan.startAt)
    }

    @Test
    fun `续传回 206 时从已下载字节处追加`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 206,
            contentType = "application/octet-stream",
            contentLength = expected - 16_780,
            existingBytes = 16_780,
        )

        assertEquals(AsrDownloadPolicy.Action.APPEND, plan.action)
        assertEquals(16_780L, plan.startAt)
    }

    @Test
    fun `续传时服务端忽略 Range 回 200 则清空重写而不是续接`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "application/octet-stream",
            contentLength = expected,
            existingBytes = 16_780,
        )

        assertEquals(AsrDownloadPolicy.Action.WRITE_FROM_START, plan.action)
        assertEquals(0L, plan.startAt)
    }

    // ── 2. `.part` 自愈（416 死循环）────────────────────────────

    @Test
    fun `垃圾 part 触发 416 时删除后从 0 重试`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 416,
            contentType = null,
            contentLength = -1,
            existingBytes = 16_780,
        )

        assertEquals(AsrDownloadPolicy.Action.RETRY_FROM_START, plan.action)
        assertEquals("HTTP_416", plan.category)
    }

    @Test
    fun `没有 part 时收到 416 属于普通 HTTP 失败`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 416,
            contentType = null,
            contentLength = -1,
            existingBytes = 0,
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("HTTP_416", plan.category)
    }

    // ── 3. 错误可诊断 ──────────────────────────────────────────

    @Test
    fun `其他非 2xx 状态码归为 HTTP 加状态码`() {
        val plan = AsrDownloadPolicy.planForResponse(
            code = 503,
            contentType = "text/plain",
            contentLength = 0,
            existingBytes = 0,
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("HTTP_503", plan.category)
    }

    @Test
    fun `四类失败类别都是机器可识别的固定字符串`() {
        val notPayload = AsrDownloadPolicy.planForResponse(
            code = 200,
            contentType = "text/html",
            contentLength = 1,
            existingBytes = 0,
        )
        val sizeMismatch = AsrDownloadPolicy.planForCompletedFile(
            bytes = 16_780,
            expectedSha256 = "abc",
        )
        val digestMismatch = AsrDownloadPolicy.planForDigest(
            actualSha256 = "deadbeef",
            expectedSha256 = "abc",
        )

        assertEquals("NOT_MODEL_PAYLOAD", notPayload.category)
        assertEquals("SIZE_MISMATCH", sizeMismatch.category)
        assertEquals("DIGEST_MISMATCH", digestMismatch.category)
        assertTrue(
            AsrDownloadPolicy.planForResponse(500, "text/plain", 0, 0)
                .category!!.startsWith("HTTP_"),
        )
    }

    // ── 4. 大小 / 摘要校验 ─────────────────────────────────────

    @Test
    fun `有摘要时以权威总长判定完成`() {
        assertEquals(
            AsrDownloadPolicy.Action.ACCEPT,
            AsrDownloadPolicy.planForCompletedFile(bytes = expected).action,
        )
        val short = AsrDownloadPolicy.planForCompletedFile(bytes = expected - 1)
        assertEquals(AsrDownloadPolicy.Action.REJECT, short.action)
        assertEquals("SIZE_MISMATCH", short.category)
    }

    @Test
    fun `无摘要时回落到最小长度下限`() {
        val tooSmall = AsrDownloadPolicy.planForCompletedFile(
            bytes = AsrDownloadPolicy.MIN_ARCHIVE_BYTES - 1,
            expectedSha256 = "",
        )
        val bigEnough = AsrDownloadPolicy.planForCompletedFile(
            bytes = AsrDownloadPolicy.MIN_ARCHIVE_BYTES,
            expectedSha256 = "",
        )

        assertEquals(AsrDownloadPolicy.Action.REJECT, tooSmall.action)
        assertEquals("SIZE_MISMATCH", tooSmall.category)
        assertEquals(AsrDownloadPolicy.Action.ACCEPT, bigEnough.action)
    }

    @Test
    fun `摘要一致才通过且大小写不敏感`() {
        assertEquals(
            AsrDownloadPolicy.Action.ACCEPT,
            AsrDownloadPolicy.planForDigest(
                actualSha256 = AsrDownloadPolicy.EXPECTED_SHA256.uppercase(),
                expectedSha256 = AsrDownloadPolicy.EXPECTED_SHA256,
            ).action,
        )
        assertEquals(
            AsrDownloadPolicy.Action.ACCEPT,
            AsrDownloadPolicy.planForDigest(
                actualSha256 = AsrDownloadPolicy.EXPECTED_SHA256,
            ).action,
        )
    }

    @Test
    fun `摘要不符判为 DIGEST_MISMATCH（调用方据此删包并返回 false）`() {
        val plan = AsrDownloadPolicy.planForDigest(actualSha256 = "deadbeef")

        assertEquals(AsrDownloadPolicy.Action.REJECT, plan.action)
        assertEquals("DIGEST_MISMATCH", plan.category)
    }

    @Test
    fun `未配置期望摘要时跳过摘要校验`() {
        assertEquals(
            AsrDownloadPolicy.Action.ACCEPT,
            AsrDownloadPolicy.planForDigest(actualSha256 = null, expectedSha256 = "").action,
        )
    }
}
