package com.ispeech.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 远端规则文件的变更信号：写盘方 [publish]，已经打开的快捷短语页据此重新读盘。
 *
 * 为什么需要它（VOCA-293）：快捷短语页的 ViewModel 作用域在导航栈条目上，去「csBaby 同步」页
 * 点「立即同步」再返回不会重建 ViewModel，而列表只在构造时读一次盘 —— 刚拉下来的规则与随之
 * 变化的来源角标 / 「已覆盖」/「恢复默认」都得退出重进才看得到。
 *
 * **顺序契约：先写文件、后 [publish]**。[RemoteRulesReload] 用「本次读盘开始于哪个版本」判断
 * 自己是否已经包含某次变更，这个推断成立的前提就是版本推进不早于文件内容变化。
 */
object RemoteRulesChanges {

    private val _version = MutableStateFlow(0L)

    /** 远端规则文件的版本：每次成功写盘 +1，只增不减。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** 远端规则文件已更新，通知所有在读的页面重新加载。 */
    fun publish() {
        _version.update { it + 1 }
    }
}

/**
 * 页面侧订阅器：把「远端规则文件已变更」变成一次重新读盘。
 *
 * 不用 `version.drop(1)`：那样会漏掉「页面已经读过盘、收集协程还没跑起来」这段时间里到来的变更，
 * 而同步页拉完立刻返回正是最常见的时序。改成比较「读盘开始时的版本」与「当前版本」，
 * 判定结果与订阅时机无关；多出来的读盘只会是多读一次磁盘，不会读到旧内容。
 */
class RemoteRulesReload(
    private val version: StateFlow<Long>,
    private val reload: () -> Unit,
) {

    /** 最近一次读盘**开始时**已观察到的版本；落后于当前版本即说明该重读。 */
    private var loadedVersion = version.value

    /** 每次读盘开始前调用 —— 必须在真正碰磁盘之前。 */
    fun markReadStarted() {
        loadedVersion = version.value
    }

    /** 开始监听变更；现有内容对应的版本已过期时调用 [reload]。返回协程便于页面销毁时取消。 */
    fun start(scope: CoroutineScope): Job = scope.launch {
        version.collect { current ->
            if (current != loadedVersion) reload()
        }
    }
}
