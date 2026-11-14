package com.ispeech.settings.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FunASR 恢复接线（VOCA-190）的引擎默认值与升级行为回归（纯 JVM，无 Android 依赖）。
 *
 * 验收里「明确写出升级行为」落地为下面几条硬断言：
 * - 全新安装（持久化值缺失 / 脏数据）默认 FunASR；
 * - 已存 SYSTEM / CUSTOM / VOSK 的老用户保留各自选择，不会被 parse() 吞掉；
 * - parse() 大小写不敏感，避免旧版本写入的大小写差异被误判为脏数据。
 */
class AsrEngineTest {

    @Test
    fun `全新安装默认 FunASR`() {
        assertEquals(AsrEngine.FUNASR, AsrSettings().engine)
    }

    @Test
    fun `未存过或脏数据回落 FunASR`() {
        assertEquals(AsrEngine.FUNASR, AsrEngine.parse(null))
        assertEquals(AsrEngine.FUNASR, AsrEngine.parse(""))
        assertEquals(AsrEngine.FUNASR, AsrEngine.parse("unknown-engine"))
        assertEquals(AsrEngine.FUNASR, AsrEngine.parse("乱码"))
    }

    @Test
    fun `已存 SYSTEM 或 CUSTOM 的老用户保留选择`() {
        assertEquals(AsrEngine.SYSTEM, AsrEngine.parse("SYSTEM"))
        assertEquals(AsrEngine.CUSTOM, AsrEngine.parse("CUSTOM"))
    }

    @Test
    fun `已存 VOSK 的老用户保留选择`() {
        assertEquals(AsrEngine.VOSK, AsrEngine.parse("VOSK"))
    }

    @Test
    fun `parse 大小写不敏感`() {
        assertEquals(AsrEngine.FUNASR, AsrEngine.parse("funasr"))
        assertEquals(AsrEngine.VOSK, AsrEngine.parse("vosk"))
        assertEquals(AsrEngine.SYSTEM, AsrEngine.parse("system"))
        assertEquals(AsrEngine.CUSTOM, AsrEngine.parse("custom"))
    }

    @Test
    fun `引擎枚举同时保留 FUNASR 与 VOSK 两个内置离线引擎`() {
        val names = AsrEngine.entries.map { it.name }.toSet()
        assertEquals(setOf("SYSTEM", "CUSTOM", "FUNASR", "VOSK"), names)
    }
}