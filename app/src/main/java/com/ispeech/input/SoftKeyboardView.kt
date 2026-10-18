    /**
     * 工具栏 7 键（严格复刻搜狗 Android 版）。
     *
     * [KeyDef.label] 保留原文（无障碍朗读 + 按下预览气泡文案），键面外观改由
     * [KeyDef.iconRes] 的矢量图标承担 —— 图标按几何复刻搜狗 Android 版
     * `assets/theme/theme_default/1080/res/black/ime_function_*.png`（见 `docs/icon-replication.md` §8）。
     *
     * 两处**刻意保持文字**：
     * - 「符号」：搜狗侧没有对应的符号键图标（`space_symbol` 是空格键上的提示、`more_shortcuts_symbols`
     *   是「＋自定义」），硬造一个图形是失真，故仍是文字；
     * - 「中/英」在工具栏，用 `ic_lang_switch.xml` 图标复刻搜狗 `ime_function_chinese_setting.png`。
     */
    private fun toolbarKeys(): List<KeyDef> = listOf(
        KeyDef.fn(
            KeyAction.SWITCH_IME,
            "⌨",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_ime_switch,
        ),
        KeyDef.fn(
            KeyAction.MODE_SWITCH,
            "中/英",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_lang_switch,
        ),
        KeyDef.fn(
            KeyAction.LAYOUT,
            "符",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            layout = KeyboardLayoutKind.SYMBOL,
        ),
        KeyDef.fn(
            KeyAction.LAYOUT,
            "☺",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            layout = KeyboardLayoutKind.EMOJI,
            iconRes = R.drawable.ic_emoji,
        ),
        KeyDef.fn(
            KeyAction.PANEL_QUICK_PHRASE,
            "短语",
            weight = 1.3f,
            style = KeyStyle.ACCENT,
            iconRes = R.drawable.ic_phrase,
        ),
        KeyDef.fn(
            KeyAction.PANEL_CLIPBOARD,
            "剪贴",
            weight = 1.2f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_clipboard,
        ),
        KeyDef.fn(
            KeyAction.SETTINGS,
            "⚙",
            weight = 1f,
            style = KeyStyle.SPECIAL,
            iconRes = R.drawable.ic_settings_gear,
        ),
    )