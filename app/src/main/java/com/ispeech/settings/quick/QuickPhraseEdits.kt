package com.ispeech.settings.quick

import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickSource

/**
 * 快捷短语列表的纯编辑逻辑：新增 / 编辑 / 删除后的用户条目列表。
 *
 * 从 [QuickPhrasesViewModel] 抽出来是为了可单测：这几条规则决定「保存后列表长什么样」，
 * 而 ViewModel 依赖 `Application`，在无 Robolectric 的单测里构造不出来。
 * 这里不碰磁盘、不碰 Android，只做纯列表变换。
 */
internal object QuickPhraseEdits {

    /**
     * 新增或编辑后的条目列表；返回的条目来源统一为 [QuickSource.USER]。
     *
     * [originalTrigger] 非空表示这是一次编辑：先按旧快捷字母移除原条目，再按新快捷字母落位。
     * 不这么做的话，编辑时把快捷字母从 `thx` 改成 `thks` 会**追加**一条新规则，
     * 旧 trigger 的条目留在文件里成为孤儿——列表上看起来像多了一条，输入法里两个字母都能命中。
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

    /** 删除指定触发词的条目。 */
    fun remove(entries: List<QuickEntry>, trigger: String): List<QuickEntry> =
        entries.filterNot { it.trigger == trigger }
}