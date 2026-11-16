package com.ispeech.input

/**
 * 候选条麦克风授权按钮的动作档位（VOCA-166）。
 *
 * 单独成类型而不是散在 [IspeechImeService] 的 if 里，是为了让「什么时候该出按钮」这条
 * 判定能脱离 Android 环境被单测覆盖——本仓库的 JVM 测试没有 Robolectric，服务本身跑不起来。
 */
internal enum class MicGrantAction {
    /** 不显示按钮：不在授权提示态，或权限其实已经授予 */
    NONE,

    /** 「去授权」：拉起设置页代申请桥，由系统弹授权框 */
    REQUEST,

    /** 「去设置」：系统不再弹框，直达应用信息页的权限入口 */
    OPEN_SETTINGS,
    ;

    companion object {
        /**
         * @param prompt 挂在候选条上的授权提示文本，null 表示当前不在授权提示态
         * @param line 候选条第 1 行当前展示的文本（组合码或其它状态文本）
         * @param granted 麦克风权限的**实时**判定结果
         * @param permanentlyDenied 设置页落下的「永久拒绝」标志
         */
        fun resolve(
            prompt: String?,
            line: String?,
            granted: Boolean,
            permanentlyDenied: Boolean,
        ): MicGrantAction = when {
            // 提示被任何一次按键 / 上屏 / 其它状态文本顶掉，按钮就跟着消失
            prompt == null || prompt != line -> NONE
            // 权限是唯一事实来源：标志只是提示精度，不能反过来门禁掉已经授予的状态
            granted -> NONE
            permanentlyDenied -> OPEN_SETTINGS
            else -> REQUEST
        }
    }
}
