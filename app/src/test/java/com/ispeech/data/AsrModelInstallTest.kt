package com.ispeech.data

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 语音模型「解压 → 产物校验 → 原子换入 → 最后写标记」与自愈判定的回归测试（QA 缺陷 D1 / VOCA-496）。
 *
 * 断言对象都是真实故障形态而不是函数返回值：临时目录用真文件系统，压缩包用 commons-compress
 * 现场生成的真 `.tar.bz2`（条目顺序照官方包：`tokens.txt` 在 `model.int8.onnx` 之前），
 * 而**联网动作由 [AsrModelInstall.prepare] 的 `download` 闭包注入** —— 于是「自愈零流量」这条
 * 判据是「下载闭包一次都没被调用」的机器断言。
 *
 * 期望字节 / 摘要按参数注入（默认值仍是 `AsrDownloadPolicy` 的权威常量，单测另有用例直接对着
 * 权威常量判定），既没有放宽既有下载判定，也不必在 JVM 单测里下载 74 MB。
 */
class AsrModelInstallTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val logs = mutableListOf<AsrModelLog>()

    /** [AsrModelInstall.prepare] 的 download 闭包被调用次数 == 网络请求次数。 */
    private var downloadCalls = 0

    /** 摘要计算被调用次数 == 冷启动是否要为 81.8 MB 买单。 */
    private var digestCalls = 0

    private val topDir = "sherpa-onnx-paraformer-zh-small-2024-03-09"

    /** 官方包里的必需产物（小体量替身），顺序照 QA `ac4-frames.log` 实测：tokens.txt 先于 onnx。 */
    private val modelEntries = listOf(
        "README.md" to "# paraformer\n".toByteArray(),
        "tokens.txt" to ByteArray(75_352) { ('a' + it % 26).code.toByte() },
        "model.int8.onnx" to ByteArray(512 * 1024) { (it % 251).toByte() },
        "test_wavs/0.wav" to ByteArray(1_024) { (it % 7).toByte() },
    )

    private val onnxPayload: ByteArray get() = modelEntries[2].second

    // ── 1. 就绪判定：哨兵不再是 tokens.txt ────────────────────────

    @Test
    fun `半截模型（tokens 在位、onnx 长度不足、完成标记缺失）判为未就绪`() {
        val paths = paths()
        seedHalfModel(paths.modelDir)

        assertFalse(
            "旧哨兵 tokens.txt 在位也不能判就绪（D1 的成因）",
            AsrModelInstall.isReady(paths, modelId(1L, "x")),
        )
        assertFalse("只有专用完成标记才算就绪", paths.readyMarker(modelId(1L, "x")).isFile)
    }

    @Test
    fun `完成标记存在但内容为空时判为未就绪`() {
        val paths = paths()
        val id = modelId(1L, "x")
        paths.modelDir.mkdirs()
        paths.readyMarker(id).writeBytes(ByteArray(0))

        assertFalse(AsrModelInstall.isReady(paths, id))
    }

    @Test
    fun `模型换版后旧完成标记自动失效`() {
        val paths = paths()
        val installedId = installCompleteModel(paths)

        assertTrue(AsrModelInstall.isReady(paths, installedId))
        // 换模型版本 ⇒ EXPECTED_BYTES / EXPECTED_SHA256 变 ⇒ 标记名字不同 ⇒ 旧目录不再被认作就绪
        val nextVersionId = AsrModelInstall.modelId(expectedBytes = 0L, expectedSha256 = "f".repeat(64))
        assertNotEquals(installedId, nextVersionId)
        assertFalse(AsrModelInstall.isReady(paths, nextVersionId))
    }

    // ── 2. 自愈：本地整包在位 ⇒ 只重新解压、零网络 ────────────────

    @Test
    fun `D1 现场（半截模型 + 本地整包在位）自愈只重新解压且一次网络都没有`() {
        val paths = paths()
        val archive = writeArchive(paths.archive, modelEntries)
        val digest = sha256Of(archive)
        seedHalfModel(paths.modelDir)
        digestCalls = 0

        val ready = prepareBlocking(
            paths = paths,
            modelId = modelId(0L, digest),
            expectedBytes = 0L,
            expectedSha256 = digest,
            download = { downloadCalls++; error("自愈不该联网：本地整包校验通过时必须零流量") },
        )

        assertTrue(ready)
        assertEquals("零流量判据：download 闭包调用次数", 0, downloadCalls)
        assertEquals("整包用后即删，不留 74 MB 常驻", 0L, paths.archive.length())
        assertFalse("临时目录已换入，不留 staging", paths.stagingDir.exists())

        val marker = paths.modelDir.listFiles()?.firstOrNull {
            it.name.startsWith(AsrModelInstall.READY_MARKER_PREFIX)
        }
        assertTrue("完成标记随整目录一起原子换入", marker != null && marker.length() > 0L)
        assertSameBytes("onnx 内容与整包一致（真的重新解压过）", onnxPayload, File(paths.modelDir, "model.int8.onnx").readBytes())
        assertTrue(
            "半截残留被完整目录替换",
            logs.any { it.category == "SELF_HEAL_RE_EXTRACT" && it.detail.contains("零流量") },
        )
    }

    @Test
    fun `自愈日志带 category 与实际字节（消除静默失败零日志）`() {
        val paths = paths()
        val archive = writeArchive(paths.archive, modelEntries)
        val digest = sha256Of(archive)
        val bytes = archive.length()
        seedHalfModel(paths.modelDir)

        prepareBlocking(
            paths = paths,
            modelId = modelId(0L, digest),
            expectedBytes = 0L,
            expectedSha256 = digest,
            download = { downloadCalls++; paths.archive },
        )

        val line = AsrModelInstall.render(logs.first { it.category == "SELF_HEAL_RE_EXTRACT" })
        assertTrue("日志要能按关键字定位：$line", line.startsWith("funasr category=SELF_HEAL_RE_EXTRACT"))
        assertTrue("日志要带实际字节：$line", line.contains(bytes.toString()))
    }

    // ── 3. 只有整包缺失 / 大小 / 摘要不过才重下 ───────────────────

    @Test
    fun `整包缺失才重新下载`() {
        val paths = paths()
        val fixture = newFixture(modelEntries)
        val digest = sha256Of(fixture)

        val ready = prepareBlocking(
            paths = paths,
            modelId = modelId(0L, digest),
            expectedBytes = 0L,
            expectedSha256 = digest,
            download = { downloadCalls++; fixture.copyTo(paths.archive, overwrite = true) },
        )

        assertEquals("整包缺失时才允许走网络", 1, downloadCalls)
        assertTrue(ready)
        assertTrue(logs.any { it.category == "ARCHIVE_MISSING" })
    }

    @Test
    fun `整包摘要不符才重新下载`() {
        val paths = paths()
        writeArchive(paths.archive, modelEntries) // 本地包在位，但内容不是权威包
        val fixture = newFixture(modelEntries + listOf("extra.txt" to "x".toByteArray()))
        val expectedDigest = sha256Of(fixture)

        val ready = prepareBlocking(
            paths = paths,
            modelId = modelId(0L, expectedDigest),
            expectedBytes = 0L,
            expectedSha256 = expectedDigest,
            download = { downloadCalls++; fixture.copyTo(paths.archive, overwrite = true) },
        )

        assertEquals("摘要不符 ⇒ 必须走重下", 1, downloadCalls)
        assertTrue(ready)
        assertTrue(logs.any { it.category == "DIGEST_MISMATCH" })
    }

    @Test
    fun `整包长度不足权威总长按 SIZE_MISMATCH 判重下`() {
        val full = newFixture(modelEntries)
        val paths = paths()
        paths.archive.writeBytes(full.readBytes().dropLast(1_024).toByteArray())

        val plan = AsrModelRepairPolicy.planFor(
            markerReady = false,
            archiveBytes = paths.archive.length(),
            archiveDigest = sha256Of(paths.archive),
            expectedBytes = AsrDownloadPolicy.EXPECTED_BYTES,
            expectedSha256 = AsrDownloadPolicy.EXPECTED_SHA256,
        )

        assertEquals(AsrModelRepairPolicy.Action.RE_DOWNLOAD, plan.action)
        assertEquals("SIZE_MISMATCH", plan.category)
        assertTrue(plan.detail.contains(AsrDownloadPolicy.EXPECTED_BYTES.toString()))
    }

    // ── 4. AC-4 既有判据：完整模型二次启动不重下不重解 ────────────

    @Test
    fun `完整模型二次准备不重下不重解且冷启动一次摘要都不算`() {
        val paths = paths()
        val fixture = newFixture(modelEntries)
        val digest = sha256Of(fixture)
        val id = modelId(0L, digest)

        val first = prepareBlocking(
            paths = paths,
            modelId = id,
            expectedBytes = 0L,
            expectedSha256 = digest,
            download = { downloadCalls++; fixture.copyTo(paths.archive, overwrite = true) },
        )
        assertTrue(first)
        assertEquals("首装走一次网络", 1, downloadCalls)

        val marker = paths.readyMarker(id)
        val markerBefore = marker.readBytes()
        val onnxBefore = File(paths.modelDir, "model.int8.onnx").readBytes()
        val listingBefore = paths.modelDir.list()?.sorted()
        downloadCalls = 0
        digestCalls = 0
        logs.clear()

        val second = prepareBlocking(
            paths = paths,
            modelId = id,
            expectedBytes = 0L,
            expectedSha256 = digest,
            download = { downloadCalls++; fixture.copyTo(paths.archive, overwrite = true) },
        )

        assertTrue(second)
        assertEquals("不重下（AC-4：rx_bytes 不该再涨）", 0, downloadCalls)
        assertEquals("不重解，且冷启动绝不为 81.8 MB 算摘要", 0, digestCalls)
        assertSameBytes("完成标记未被改写", markerBefore, marker.readBytes())
        assertSameBytes("onnx 未被重写", onnxBefore, File(paths.modelDir, "model.int8.onnx").readBytes())
        assertEquals("目录内容未变", listingBefore, paths.modelDir.list()?.sorted())
        assertFalse("无 staging 残留", paths.stagingDir.exists())
        assertTrue("就绪热路径不打日志", logs.isEmpty())
    }

    // ── 5. 解压失败：主目录要么完整要么不存在 ────────────────────

    @Test
    fun `解压中途失败时临时目录被清理、主目录不留半截、仍未就绪`() {
        val paths = paths()
        val good = newFixture(modelEntries)
        // 模拟解压窗口内被杀 / 存储写满：包体被截断，bzip2 流半路 EOF
        paths.archive.writeBytes(good.readBytes().copyOf(good.length().toInt() / 2))
        val half = paths.modelDir.apply { mkdirs() }
        File(half, "tokens.txt").writeBytes(ByteArray(16))
        val id = modelId(0L, "deadbeef")

        val error = runCatching { AsrModelInstall.extractAtomically(paths, id) { logs.add(it) } }.exceptionOrNull()

        assertTrue("解压中断必须抛可定位异常，实际=$error", error is AsrModelInstallException)
        assertEquals("EXTRACT_STREAM_FAILED", (error as AsrModelInstallException).category)
        assertFalse("临时目录必须被清掉", paths.stagingDir.exists())
        assertEquals("主目录不许多出半截产物", listOf("tokens.txt"), paths.modelDir.list()?.sorted())
        assertFalse(AsrModelInstall.isReady(paths, id))
        val line = AsrModelInstall.render(logs.last())
        assertTrue("失败路径必须打可定位日志：$line", line.startsWith("funasr category=EXTRACT_STREAM_FAILED"))
    }

    @Test
    fun `解压产物缺 onnx 判 ARTIFACT_MISSING_ONNX 且不写完成标记`() {
        val paths = paths()
        writeArchive(paths.archive, modelEntries.filterNot { it.first.endsWith(".onnx") })
        val id = modelId(0L, "cafebabe")

        val error = runCatching { AsrModelInstall.extractAtomically(paths, id) { logs.add(it) } }
            .exceptionOrNull() as AsrModelInstallException

        assertEquals("ARTIFACT_MISSING_ONNX", error.category)
        assertTrue(error.detail.contains("tokens="))
        assertFalse("临时目录已清理", paths.stagingDir.exists())
        assertFalse("主目录不会被建出来一半", paths.modelDir.exists())
        assertFalse(AsrModelInstall.isReady(paths, id))
    }

    @Test
    fun `解压产物缺 tokens 判 ARTIFACT_MISSING_TOKENS`() {
        val paths = paths()
        writeArchive(paths.archive, modelEntries.filterNot { it.first == "tokens.txt" })

        val error = runCatching { AsrModelInstall.extractAtomically(paths, modelId(0L, "cafebabe")) { logs.add(it) } }
            .exceptionOrNull() as AsrModelInstallException

        assertEquals("ARTIFACT_MISSING_TOKENS", error.category)
        assertTrue(error.detail.contains("model.int8.onnx:"))
    }

    @Test
    fun `tar 条目用相对路径逃逸模型目录会被拒绝`() {
        val paths = paths()
        writeArchive(paths.archive, listOf("../evil.txt" to "boom".toByteArray()) + modelEntries)

        val error = runCatching { AsrModelInstall.extractAtomically(paths, modelId(0L, "cafebabe")) { logs.add(it) } }
            .exceptionOrNull() as AsrModelInstallException

        assertEquals("EXTRACT_UNSAFE_ENTRY", error.category)
        assertFalse("模型目录外的路径一个字节都不能写", File(paths.root, "evil.txt").exists())
        assertFalse(paths.stagingDir.exists())
    }

    @Test
    fun `安装完成后整包删除且标记与产物齐备`() {
        val paths = paths()
        val id = installCompleteModel(paths)

        assertFalse("压缩包不常驻（成功后删掉，失败时才留着供自愈）", paths.archive.exists())
        assertTrue(AsrModelInstall.isReady(paths, id))
        assertEquals(1, downloadCalls)
        assertEquals(0, digestCalls)
        val marker = paths.readyMarker(id).readText()
        assertTrue("标记记录模型身份：$marker", marker.contains("model=$id"))
        assertTrue("标记记录预期与实际字节：$marker", marker.contains("onnx=model.int8.onnx:${onnxPayload.size}"))
        assertTrue(marker.contains("tokens=tokens.txt:75352"))
    }

    @Test
    fun `标记在位时热路径不碰压缩包`() {
        val paths = paths()
        val id = installCompleteModel(paths)
        val archive = newFixture(modelEntries)
        archive.copyTo(paths.archive, overwrite = true)
        val listing = paths.modelDir.list()?.sorted()

        assertTrue(AsrModelInstall.isReady(paths, id))
        assertEquals("isReady 只 stat 标记，不改目录", listing, paths.modelDir.list()?.sorted())
    }

    // ── 6. 纯决策表（无文件系统、无网络）──────────────────────────

    @Test
    fun `决策表：标记在位即 READY 不自愈不重下`() {
        val plan = AsrModelRepairPolicy.planFor(markerReady = true, archiveBytes = 0L, archiveDigest = null)

        assertEquals(AsrModelRepairPolicy.Action.READY, plan.action)
        assertEquals("READY", plan.category)
    }

    @Test
    fun `决策表：标记缺失且整包大小摘要都过则只重新解压`() {
        val plan = AsrModelRepairPolicy.planFor(
            markerReady = false,
            archiveBytes = AsrDownloadPolicy.EXPECTED_BYTES,
            archiveDigest = AsrDownloadPolicy.EXPECTED_SHA256.uppercase(),
        )

        assertEquals(AsrModelRepairPolicy.Action.RE_EXTRACT, plan.action)
        assertEquals("SELF_HEAL_RE_EXTRACT", plan.category)
    }

    @Test
    fun `决策表：四种重下原因分别是 ARCHIVE_MISSING 与 SIZE_MISMATCH 与 DIGEST_MISMATCH`() {
        val missing = AsrModelRepairPolicy.planFor(markerReady = false, archiveBytes = 0L, archiveDigest = null)
        val wrongSize = AsrModelRepairPolicy.planFor(
            markerReady = false,
            archiveBytes = AsrDownloadPolicy.EXPECTED_BYTES - 1,
            archiveDigest = AsrDownloadPolicy.EXPECTED_SHA256,
        )
        val wrongDigest = AsrModelRepairPolicy.planFor(
            markerReady = false,
            archiveBytes = AsrDownloadPolicy.EXPECTED_BYTES,
            archiveDigest = "deadbeef",
        )
        // 摘要读不出来（读盘失败）按不符处理，交给重下兜底，绝不放行半截包
        val unreadable = AsrModelRepairPolicy.planFor(
            markerReady = false,
            archiveBytes = AsrDownloadPolicy.EXPECTED_BYTES,
            archiveDigest = null,
        )

        assertEquals("ARCHIVE_MISSING", missing.category)
        assertEquals("SIZE_MISMATCH", wrongSize.category)
        assertEquals("DIGEST_MISMATCH", wrongDigest.category)
        assertEquals("DIGEST_MISMATCH", unreadable.category)
        listOf(missing, wrongSize, wrongDigest, unreadable).forEach {
            assertEquals(AsrModelRepairPolicy.Action.RE_DOWNLOAD, it.action)
        }
    }

    @Test
    fun `模型身份由权威字节与摘要前缀构成，任一变化即失效`() {
        val base = AsrModelInstall.modelId(77_920_048L, "da92b3db5218c5be53aad53e57d1b6e6")
        val otherBytes = AsrModelInstall.modelId(77_920_049L, "da92b3db5218c5be53aad53e57d1b6e6")
        val otherDigest = AsrModelInstall.modelId(77_920_048L, "0000b3db5218c5be53aad53e57d1b6e6")

        assertEquals("b77920048-sda92b3db5218", base)
        assertNotEquals(base, otherBytes)
        assertNotEquals(base, otherDigest)
    }

    // ── 工具 ────────────────────────────────────────────────────

    /** 核心层是 suspend（只为下载闭包里的退避 `delay`），单测用 runBlocking 直接驱动真文件。 */
    private fun prepareBlocking(
        paths: AsrModelPaths,
        modelId: String,
        expectedBytes: Long,
        expectedSha256: String,
        download: () -> File,
    ): Boolean = runBlocking {
        AsrModelInstall.prepare(
            paths = paths,
            modelId = modelId,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
            sha256Of = ::sha256Of,
            download = { download() },
            log = { logs.add(it) },
        )
    }

    private fun paths(): AsrModelPaths =
        AsrModelPaths(File(tmpFolder.newFolder("funasr-root-${System.nanoTime()}"), "funasr").apply { mkdirs() }, "model.tar.bz2")

    private fun modelId(expectedBytes: Long, expectedSha256: String): String =
        AsrModelInstall.modelId(expectedBytes, expectedSha256)

    private fun newFixture(entries: List<Pair<String, ByteArray>>): File =
        writeArchive(File(tmpFolder.root, "fixture-${System.nanoTime()}.tar.bz2"), entries)

    private fun seedHalfModel(modelDir: File) {
        modelDir.mkdirs()
        File(modelDir, "tokens.txt").writeBytes(ByteArray(75_352) { ('a' + it % 26).code.toByte() })
        File(modelDir, "model.int8.onnx").writeBytes(ByteArray(2_023_424))
    }

    /** 走一次完整的「下载 → 原子解压 → 写标记」，返回当时的模型身份；期间只碰一次网络。 */
    private fun installCompleteModel(paths: AsrModelPaths): String {
        val fixture = newFixture(modelEntries)
        val digest = sha256Of(fixture)
        val id = modelId(0L, digest)
        // 计数只描述核心层这一次安装：fixture 自己的摘要不计
        downloadCalls = 0
        digestCalls = 0

        val ready = prepareBlocking(
            paths = paths,
            modelId = id,
            expectedBytes = 0L,
            expectedSha256 = digest,
            download = { downloadCalls++; fixture.copyTo(paths.archive, overwrite = true) },
        )

        assertTrue("前置安装必须成功", ready)
        return id
    }

    /** 生成真 `.tar.bz2`：条目名带顶层目录前缀（`<包名>/tokens.txt`），与官方包结构一致。 */
    private fun writeArchive(
        file: File,
        entries: List<Pair<String, ByteArray>>,
        top: String = topDir,
    ): File {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { raw ->
            BZip2CompressorOutputStream(raw).use { bz2 ->
                TarArchiveOutputStream(bz2).use { tar ->
                    entries.forEach { (name, bytes) ->
                        val entry = TarArchiveEntry("$top/$name").apply { size = bytes.size.toLong() }
                        tar.putArchiveEntry(entry)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }
        return file
    }

    private fun sha256Of(file: File): String {
        digestCalls++
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun assertSameBytes(message: String, expected: ByteArray, actual: ByteArray) {
        assertEquals("$message（长度）", expected.size, actual.size)
        fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(message, hex(expected), hex(actual))
    }
}
