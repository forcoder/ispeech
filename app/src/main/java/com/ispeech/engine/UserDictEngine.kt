package com.ispeech.engine

import com.ispeech.data.UserDictStore
import com.ispeech.data.UserDictMode

/**
 * 用户词典引擎：把用户词频/自造词并入候选排序，并提供联想。
 *
 * [mode] 决定读用户词库的哪一半：五笔态读 `wb`（编码是五笔码），拼音态读 `cn`
 * （编码是拼音串）。对齐搜狗 mac 版把用户词库拆成 `wb_dict_user` / `cn_dict_user` 的做法。
 */
class UserDictEngine(
    private val store: UserDictStore,
    private val mode: UserDictMode = UserDictMode.WUBI,
) : LookupEngine, AssociationSource {

    override val displayName: String = "user"

    /**
     * 用户词优先加成。
     *
     * 系统词库（wubi86.dict）权重最高到 65535，而用户词典为了可读性把权重限制在 400；
     * 若直接使用会让「学到的词」永远排不过系统词。这里统一加一个远大于系统词权的基数，
     * 保证用户选择过的词在同类型候选里排最前（类型优先级仍由 CandidateRanking 决定）。
     */
    private val boost: Int = USER_BOOST

    override fun lookup(input: String, limit: Int): List<LookupCandidate> =
        store.lookupCandidates(input, limit, mode).map { it.copy(weight = it.weight + boost) }

    override fun related(text: String, limit: Int): List<LookupCandidate> =
        store.related(text, limit, mode).map { it.copy(weight = it.weight + boost) }

    private companion object {
        /** 系统词库最大权重为 65535，这里取 10 万保证用户词稳定压过系统词。 */
        const val USER_BOOST = 100_000
    }
}
