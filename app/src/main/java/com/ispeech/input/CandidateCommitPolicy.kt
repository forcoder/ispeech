package com.ispeech.input

/**
 * 点选候选上屏后，是否接着给联想候选。
 *
 * 这条判定从 [IspeechImeService.pickCandidate] 里提出来，是为了让「点选之后联想还在不在」
 * 能脱离 Android 环境被单测钉住：本仓库的 JVM 测试没有 Robolectric，服务本身跑不起来。
 *
 * VOCA-650：v1.6 起（VOCA-127 `0168e59`）非联想分支被写成 `allowAssociation = false`，
 * 点选候选 / 数字键选候选上屏后 `startAssociation()` 再不被调用，整条联想链在点选路径上断掉；
 * 空格 / 回车（`commitTop`）不受影响，所以现象是「有时有、点选就没有」。
 *
 * 两条分支的语义不同，不能合并成一个常量：
 * - 非联想分支：用户刚选了一个词，正是要接着联想；
 * - 联想分支：用户刚点了联想词，上屏后不再接续联想（VOCA-121 的既有口径）。
 */
internal object CandidateCommitPolicy {

    /**
     * @param associationActive 本次点选时是否正处于联想候选态
     * @return 上屏后是否调用 `startAssociation()`
     */
    fun allowAssociation(associationActive: Boolean): Boolean = !associationActive
}