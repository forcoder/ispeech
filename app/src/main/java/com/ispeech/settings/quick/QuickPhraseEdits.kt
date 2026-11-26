package com.ispeech.settings.quick

import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickSource

/**
 * 本地规则的纯列表编辑逻辑（新增 / 编辑）。
 *
 * 从 [QuickPhrasesViewModel] 抽出是为了可单测：ViewModel 依赖 `Application`，
 * 在无 Robolectric 的单测里构造不出来；这里不碰磁盘、不碰 Android。
 */
internal object QuickPhraseEdits {

    /**
     * 新增或编辑后的用户条目列表；写入条目的来源统一为 [QuickSource.USER]。
     *
     * [originalTrigger] 非空表示这是一次编辑：先按旧快捷字母移除原条目，再按新快捷字母落位。
     * 只按新 trigger 查找的话，把 `thx` 改成 `thks` 会**追加**一条新规则，旧条目留在文件里
     * 成为孤儿——列表多一条、输入法两个字母都能命中。
     */
    fun upsert(
        entries: List<QuickEntry>,
        entry: QuickEntry,
        originalTrigger: String? = null,
    ): List<QuickEntry> {
        val userEntry = entry.copy(source = QuickSource.USER)
        val list = entries.toMutableList()
        if (originalTrigger != null && originalTrigger != userEntry.trigger) {
            list.removeAll { it.trigger == originalTrigger }
        }
        val index = list.indexOfFirst { it.trigger == userEntry.trigger }
        if (index >= 0) list[index] = userEntry else list.add(userEntry)
        return list
    }
}