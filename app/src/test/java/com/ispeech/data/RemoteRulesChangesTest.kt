package com.ispeech.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 远端规则刷新链的回归测试（VOCA-293）。
 *
 * 缺陷原文：点「从远程同步」拉回新规则后，快捷短语页要退出重进才看得到 —— 写盘方不发通知、
 * 读盘方只在构造时读一次盘。这里锁的是订阅器的三条时序口径，任何一条退化都会把缺陷放回来：
 *
 * 1. 写盘后 publish → 已打开的页面立刻重新读盘；
 * 2. 变更发生在「已读盘、尚未开始收集」的窗口里 → 仍然补一次读盘
 *    （改成 `drop(1)` 就是这条判红，也正是「同步完立刻返回」的真实时序）；
 * 3. 读盘进行中到来的变更 → 不会被当成「这次已经读到了」而吞掉。
 */
class RemoteRulesChangesTest {

    /** 让 start() 里 launch 的收集协程跑到 StateFlow 的首帧发射。 */
    private suspend fun settle() {
        repeat(4) { yield() }
    }

    @Test
    fun `写盘后发通知，已打开的页面立刻重新读盘`() = runBlocking {
        val version = MutableStateFlow(0L)
        var reloads = 0
        val watcher = RemoteRulesReload(version) { reloads++ }
        watcher.markReadStarted()
        val job = launch { watcher.start(this) }
        settle()
        assertEquals("首帧版本没变，不该触发重载", 0, reloads)

        // SyncRepository.pullAndWriteRemoteRules 的口径：先写文件、后 publish
        version.value = 1L
        settle()

        assertEquals(1, reloads)
        job.cancel()
    }

    @Test
    fun `收集协程还没跑起来时到来的变更不会丢`() = runBlocking {
        val version = MutableStateFlow(0L)
        var reloads = 0
        val watcher = RemoteRulesReload(version) { reloads++ }
        watcher.markReadStarted()
        // 页面已经读过盘（v0），同步在这之后完成并通知，而收集协程尚未开始
        version.value = 1L

        val job = launch { watcher.start(this) }
        settle()

        assertEquals("订阅窗口里的变更被漏掉 → 用户仍要退出重进", 1, reloads)
        job.cancel()
    }

    @Test
    fun `读盘期间到来的变更会补一次重载`() = runBlocking {
        val version = MutableStateFlow(0L)
        var reloads = 0
        val watcher = RemoteRulesReload(version) { reloads++ }
        val job = launch { watcher.start(this) }
        settle()

        watcher.markReadStarted() // 本次读盘开始于 v0，磁盘内容还在读
        version.value = 1L        // 读盘期间同步完成
        settle()

        assertEquals(1, reloads)
        // 补读的那一次开始于 v1，之后没有新变更 → 不再叠加
        watcher.markReadStarted()
        settle()
        assertEquals("读盘已覆盖最新版本却仍在重载", 1, reloads)
        job.cancel()
    }

    @Test
    fun `版本只增不减，每次写盘推进一格`() {
        val before = RemoteRulesChanges.version.value
        RemoteRulesChanges.publish()
        RemoteRulesChanges.publish()
        assertEquals(before + 2L, RemoteRulesChanges.version.value)
    }
}
