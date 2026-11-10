package com.ispeech.input.keyboard

/**
 * 按键行为：软键盘只负责把「按了哪个键」告诉输入法服务，
 * 具体语义（进五笔缓冲 / 直接上屏 / 切换布局）全部由 IspeechImeService 决定。
 */
enum class KeyAction {
    /** 输出字符：中文态字母进五笔缓冲，英文态直接上屏；[KeyDef.output] 为待输出字符。 */
    CHAR,

    /** 标点键：按「中文标点 / 全角」设置转换后上屏。 */
    PUNCTUATE,

    /** Shift：单击切一次大写，锁定时再单击取消。 */
    SHIFT,

    /** 退格：删码 / 删字符，长按连删。 */
    BACKSPACE,

    /** 空格：上屏首选候选或输出空格。 */
    SPACE,

    /** 回车 / 换行。 */
    ENTER,

    /** Tab。 */
    TAB,

    /** 光标左移 / 右移（符号页里的方向键）。 */
    MOVE_LEFT,
    MOVE_RIGHT,

    /** 中英文切换。 */
    MODE_SWITCH,

    /** 切换键盘布局（配合 [KeyDef.layout]。 */
    LAYOUT,

    /** 候选翻页。 */
    PAGE_PREV,
    PAGE_NEXT,

    /** 打开 csBaby 快捷短语面板。 */
    PANEL_QUICK_PHRASE,

    /** 打开剪贴板面板。 */
    PANEL_CLIPBOARD,

    /** AI 回复。 */
    AI,

    /** 打开设置页。 */
    SETTINGS,

    /** 收起键盘。 */
    HIDE_KEYBOARD,

    /** 清空当前组合（等价 Esc）。 */
    CLEAR,

    /** 编辑动作：全选 / 复制 / 粘贴 / 撤销上屏。 */
    SELECT_ALL,
    COPY,
    PASTE,
    UNDO_COMMIT,

    /** 切换中文标点开关。 */
    TOGGLE_PUNCT,

    /** 切换全角 / 半角。 */
    TOGGLE_FULL_WIDTH,

    /**
     * 切换到系统其它输入法（服务侧弹系统输入法选择器）。
     *
     * 追加在枚举末尾：既有取值与 [Enum.ordinal] 全部不变。
     */
    SWITCH_IME,

    /**
     * 触发语音输入：拉起系统 [android.speech.SpeechRecognizer]，识别文本作为候选上屏。
     *
     * 追加在枚举末尾：既有取值与 [Enum.ordinal] 全部不变。
     */
    VOICE,
}

/** 按键视觉样式。 */
enum class KeyStyle {
    /** 普通字母键 */
    LETTER,

    /** 功能键（Shift / 退格 / 数字页切换） */
    SPECIAL,

    /** 空格 */
    SPACE,

    /** 主题色强调（AI、面板入口） */
    ACCENT,

    /** 主操作（回车） */
    PRIMARY,

    /** 危险操作（清空 / 收起） */
    DANGER,

    /**
     * 标点 / 符号键：键面字号比字母键小一号（搜狗习惯，全角标点不再显得过大）。
     *
     * 追加在枚举末尾：既有取值与 [Enum.ordinal] 全部不变。
     */
    PUNCT,
}

/**
 * 单个按键定义。
 *
 * @param action 按键行为
 * @param label 键面文字（默认取 [output]）
 * @param output [KeyAction.CHAR] / [KeyAction.PUNCTUATE] 的输出字符
 * @param weight 横向权重（1f 为普通宽度，1.5f 为宽键）
 * @param alt 长按弹出候选（滑动/点选后输出其中一项）
 * @param style 视觉样式
 * @param layout [KeyAction.LAYOUT] 时切到的布局种类
 * @param pageOffset 翻页键步长 / 布局切换时的目标页（默认 0）
 */
data class KeyDef(
    val action: KeyAction,
    val label: String = "",
    val output: String = "",
    val weight: Float = 1f,
    val alt: List<String> = emptyList(),
    val style: KeyStyle = KeyStyle.LETTER,
    val layout: KeyboardLayoutKind? = null,
    val pageOffset: Int = 0,
    /** 键面顶部的数字 / 符号提示（仅字母键使用，搜狗风格）。 */
    val hint: String = "",
    /**
     * 键面矢量图标（0 = 不用图标，按 [label] 文字渲染）。
     *
     * 只存 drawable 资源 id（不引 `R`），保持键位数据模型与资源包无耦合；
     * 渲染在 [com.ispeech.input.SoftKeyboardView.createKey]。
     * [label] 仍然保留：它同时充当无障碍朗读与长按/按下预览的文案。
     */
    val iconRes: Int = 0,
    /**
     * 键面显示「当前输入态」（拼 / EN / 五）。
     *
     * 仅工具栏第 4 键使用：同样的 [KeyAction.MODE_SWITCH] 在字母页底行要以「中/英」分色渲染，
     * 这里换成单色状态字，避免两个位置共用一个渲染分支。
     */
    val statusLabel: Boolean = false,
) {
    companion object {
        /** 字符键快捷构造。 */
        fun char(
            output: String,
            label: String = "",
            alt: List<String> = emptyList(),
            weight: Float = 1f,
            style: KeyStyle = KeyStyle.LETTER,
            hint: String = "",
        ): KeyDef = KeyDef(
            action = KeyAction.CHAR,
            label = label.ifEmpty { output },
            output = output,
            weight = weight,
            alt = alt,
            style = style,
            hint = hint,
        )

        /** 标点键快捷构造（上屏时按标点设置转换；键面用 [KeyStyle.PUNCT]，字号比字母键小一号）。 */
        fun punct(
            output: String,
            label: String = "",
            alt: List<String> = emptyList(),
            weight: Float = 1f,
        ): KeyDef = KeyDef(
            action = KeyAction.PUNCTUATE,
            label = label.ifEmpty { output },
            output = output,
            weight = weight,
            alt = alt,
            style = KeyStyle.PUNCT,
        )

        /** 功能键快捷构造。 */
        fun fn(
            action: KeyAction,
            label: String,
            weight: Float = 1f,
            style: KeyStyle = KeyStyle.SPECIAL,
            layout: KeyboardLayoutKind? = null,
            pageOffset: Int = 0,
            iconRes: Int = 0,
            statusLabel: Boolean = false,
        ): KeyDef = KeyDef(
            action = action,
            label = label,
            weight = weight,
            style = style,
            layout = layout,
            pageOffset = pageOffset,
            iconRes = iconRes,
            statusLabel = statusLabel,
        )
    }
}

/** 键盘布局种类（字母 / 数字 / 符号 / 表情 / 九宫格）。 */
enum class KeyboardLayoutKind(val label: String) {
    LETTER("字母"),
    NUMBER("数字"),
    SYMBOL("符号"),
    EMOJI("表情"),

    /**
     * 九宫格 T9：数字页 + `1` 标点页。
     *
     * 键位与「数字串 → 字母串」映射都在 [T9Layouts]；这里不新增任何 [KeyAction]，
     * 九宫格的翻页 / 切布局复用既有的 [KeyAction.PAGE_NEXT] 与 [KeyAction.LAYOUT]。
     */
    T9("九宫格"),
}

/** 一页键盘：标题 + 若干行按键（行内按键按 weight 分配宽度）。 */
data class KeyboardPage(
    val title: String,
    val rows: List<List<KeyDef>>,
)
