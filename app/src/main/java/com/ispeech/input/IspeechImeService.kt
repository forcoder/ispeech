package com.ispeech.input

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.graphics.Paint
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.ispeech.R
import com.ispeech.data.AiLearningStore
import com.ispeech.data.ClipboardHistory
import com.ispeech.data.CommitType
import com.ispeech.data.CsBabyAuthStore
import com.ispeech.data.DirectLlmClient
import com.ispeech.data.HanConversion
import com.ispeech.data.InputStats
import com.ispeech.data.PhraseUsageStore
import com.ispeech.data.Punctuation
import com.ispeech.data.PunctuationConverter
import com.ispeech.data.UserDictStore
import com.ispeech.data.UserDictMode
import com.ispeech.data.FunAsrModelStore
import com.ispeech.data.VoskModelStore
import com.ispeech.di.AppContainer
import com.ispeech.engine.CompositeEngine
import com.ispeech.engine.LookupCandidate
import com.ispeech.engine.LookupEngine
import com.ispeech.engine.LookupKind
import com.ispeech.engine.PinyinEngine
import com.ispeech.engine.QuickEntry
import com.ispeech.engine.QuickLocator
import com.ispeech.input.keyboard.KeyAction
import com.ispeech.input.keyboard.KeyDef
import com.ispeech.input.keyboard.KeyboardLayoutKind
import com.ispeech.input.keyboard.T9Layouts
import com.ispeech.settings.REQUEST_MIC_EXTRA
import com.ispeech.settings.store.AsrEngine
import com.ispeech.settings.store.AsrSettings
import com.ispeech.settings.store.ChineseInputMethod
import com.ispeech.settings.store.KeyboardSettings
import com.ispeech.settings.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智语 iSpeech 输入法服务。
 *
 * 输入状态机：
 * - [InputMode.WUBI]：a-z 进五笔缓冲码（最多 4 位）→ 查询候选 → 空格 / 回车 / 数字键上屏
 * - [InputMode.PINYIN]：a-z 进拼音组合串（最多 12 位）→ 拼音引擎（全拼 / 简拼）出候选
 * - [InputMode.ENGLISH]：字母直接上屏，Shift 出大写
 * - 数字 / 符号 / 表情布局：按键直接上屏（不经缓冲）
 * - 上屏后按设置给出「联想」候选（词库词组 + 快捷短语 + 用户词）
 *
 * 输入来源：
 * 1. 软键盘面板（[SoftKeyboardView]）—— 统一走 [handleSoftKey]
 * 2. 硬件键盘（蓝牙 / OTG）—— 走 [onKeyDown]，与软键盘共用同一批 handler
 *
 * 面板：[Panel.QUICK_PHRASE]（csBaby 快捷短语）、[Panel.CLIPBOARD]（剪贴板历史）、
 * [Panel.SWITCH_IME]（切换到系统里其他已启用的输入法）。
 * 面板打开时软键盘隐藏，硬件键盘按键用于筛选短语。
 */
class IspeechImeService : InputMethodService() {

    companion object {
        private const val TAG = "IspeechIme"

        /** 五笔编码最长 4 位 */
        private const val MAX_CODE_LENGTH = 4

        /**
         * 拼音组合串长度上限。
         *
         * 拼音比五笔长得多（全拼 `zhuang` 6 位、多字词更长），取 12 位覆盖常见输入；
         * [com.ispeech.engine.PinyinEngine] 自身上限为 32，这里先截断避免无谓的超长扫描。
         */
        private const val MAX_PINYIN_LENGTH = 12

        /** 一次查询最多取「每页候选数 × [MAX_PAGES]」条（实际页数按取回条数计算） */
        private const val MAX_PAGES = 3

        /** 设置入口（由设置模块提供，这里用类名解耦，避免强依赖） */
        private const val SETTINGS_ACTIVITY = "com.ispeech.settings.MainActivity"

        /** AI 生成时从输入框读取的最大字符数 */
        private const val AI_MAX_MESSAGE_CHARS = 1000

        /** AI 键一次最多给出的快捷短语候选数 */
        private const val QUICK_REPLY_LIMIT = 20

        /** 只有较短的词才写进用户词库；长文本说明是快捷短语内容 */
        private const val MAX_LEARN_TEXT_LENGTH = 8

        /** 九宫格（T9）数字串最长 9 位 */
        private const val T9_MAX_DIGITS = 9

        /**
         * 九宫格一次最多展开的字母组合数。
         *
         * 每个组合都要过一次词库，9 位数字理论上 4^9 组；截断保证长数字串下的单次查询可控，
         * 配合 [MAX_PAGES] 的候选池上限（见 lookupT9）实际开销远低于上限。
         */
        private const val T9_PATTERN_LIMIT = 192

        /** 字形缓存上限（超出整体清空，见 renderable） */
        private const val GLYPH_CACHE_LIMIT = 1024

        /** 九宫格候选排序：来源档位 → 权重降序 → 文本升序（与词库候选同一套排序哲学） */
        private val T9_RANK: Comparator<LookupCandidate> = Comparator { a, b ->
            val byKind = a.kind.order.compareTo(b.kind.order)
            if (byKind != 0) {
                byKind
            } else {
                val byWeight = b.weight.compareTo(a.weight)
                if (byWeight != 0) byWeight else a.display.compareTo(b.display)
            }
        }
    }

    /**
     * 输入态：五笔 / 拼音 / 英文。定义见 [InputMode]；五笔与拼音都是「中文态」，
     * 字母进组合缓冲再由候选上屏；英文态字母直接上屏。
     */

    /** 当前展示的辅助面板 */
    private enum class Panel { NONE, QUICK_PHRASE, CLIPBOARD, SWITCH_IME, SETTINGS }

    private var engine: CompositeEngine? = null

    /** 键盘设置（高度、候选数、标点、联想、学习…），随 DataStore 实时更新 */
    private var settings: KeyboardSettings = KeyboardSettings()

    /** 中文输入法设置（拼音 / 五笔）：中文态默认用哪种，也是中/英切回中文的目标 */
    private var chineseInputMethod: ChineseInputMethod = ChineseInputMethod.PINYIN

    /** 语音识别设置（引擎 / 语言 / 模型…），随 DataStore 实时更新 */
    private var asr: AsrSettings = AsrSettings()

    /** 标点转换器（内部有引号配对状态） */
    private val punctor = PunctuationConverter()

    private var mode: InputMode = InputMode.WUBI
    private var panel: Panel = Panel.NONE

    /** 当前组合中的五笔编码，只含 a-z，最多 4 位 */
    private var buffer: String = ""

    /** 九宫格（T9）数字串；与 [buffer] 互斥，非空时当前处于九宫格组合态 */
    private var t9Digits: String = ""

    /** 九宫格查询序号：数字串变化后旧查询结果作废 */
    private var t9Seq: Int = 0

    /** 引擎代次：设置页 [AppContainer.invalidateEngine] 后据此重取引擎 */
    private var engineGeneration: Int = -1

    /** 当前页候选 */
    private var candidates: List<LookupCandidate> = emptyList()

    /** 分页前的全部候选 */
    private var allCandidates: List<LookupCandidate> = emptyList()

    private var pageIndex: Int = 0
    private var pageCount: Int = 1

    /** 当前候选是否为「联想」（buffer 为空但候选有效） */
    private var associationActive: Boolean = false

    /** 最近一次上屏文本，用于撤销上屏 */
    private var lastCommit: String = ""

    /** 快捷短语面板的筛选关键词（硬件键盘输入） */
    private var panelKeyword: String = ""

    // ── 视图 ──

    private var candidateView: CandidateView? = null
    private var softKeyboard: SoftKeyboardView? = null
    private var quickPhrasePanel: QuickPhrasePanel? = null
    private var clipboardPanel: ClipboardPanel? = null
    private var inputMethodPanel: InputMethodPanel? = null
    private var settingsPanel: SettingsPanel? = null

    /** 输入统计（字数 / 命中率 / Top 词），落盘在 filesDir/input_stats.txt */
    private val stats: InputStats by lazy { InputStats.get(this) }

    /** 短语正文 → 规则条目，用于面板上屏时记一次「使用」 */
    private var quickByContent: Map<String, QuickEntry> = emptyMap()

    /** AI 学习库（生成 / 采纳计数 + few-shot 语料），落盘在 filesDir */
    private val aiLearning by lazy { AiLearningStore(this) }

    // ── AI 回复状态 ──

    /** 服务级协程作用域（AI 请求、面板数据加载） */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 是否有 AI 请求进行中 */
    private var aiBusy = false

    /** 当前候选是否为 AI 回复 */
    private var aiReplyActive = false

    /** 当前 AI 候选是否来自模型产出或历史复用（false = 快捷短语命中，不计入采纳率） */
    private var aiReplyFromModel = false

    /** 生成 AI 回复时输入框的原文（用于替换语义） */
    private var aiSourceText = ""
    private var aiSourceLen = 0

    /** 请求序号：用户继续输入 / 切换输入框后使旧结果失效 */
    private var aiRequestSeq = 0

    /** 候选条编码区状态提示（如「AI 生成中…」），非空时优先展示 */
    private var statusText: String? = null

    /** 候选条编码区覆盖显示（如「AI回复」「联想」「语音」） */
    private var codeOverride: String? = null

    // ── 语音输入状态 ──

    /** 系统语音识别封装（onCreate 主线程创建，onDestroy 释放） */
    private var voiceController: VoiceInputController? = null

    /** 内置 FunASR 离线识别引擎，与 [voskVoice] / [voiceController] 按「识别引擎」设置三选一 */
    private var funasrVoice: FunAsrSpeechEngine? = null

    /** 端侧离线识别（Vosk）引擎，与 [funasrVoice] / [voiceController] 按「识别引擎」设置三选一 */
    private var voskVoice: VoskSpeechEngine? = null

    /** 离线模型是否正在下载（FunASR / Vosk 共用同一把闸门，避免重复触发下载任务） */
    private var offlineModelDownloading: Boolean = false

    /** 当前候选是否为语音识别结果（空格 / 点选上屏后复位） */
    private var voiceActive = false

    override fun onCreate() {
        super.onCreate()
        engineGeneration = AppContainer.engineGeneration()
        engine = AppContainer.compositeEngine(this)
        settings = SettingsStore.keyboardSettings(this)
        asr = SettingsStore.asrSettings(this)
        chineseInputMethod = SettingsStore.chineseInputMethod(this)
        // SpeechRecognizer 必须在主线程创建，onCreate 即在主线程
        voiceController = VoiceInputController(applicationContext)
        funasrVoice = FunAsrSpeechEngine(applicationContext)
        voskVoice = VoskSpeechEngine(applicationContext)
        // 预热拼音引擎：切到拼音态后首键即用，避免首次上屏时在主线程加载拼音词表造成卡顿
        serviceScope.launch(Dispatchers.Default) {
            runCatching { AppContainer.pinyinEngine(this@IspeechImeService) }
        }
        observeSettings()
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.ime_input_view, null) as ViewGroup

        // 候选条
        val bar = root.findViewById<CandidateView>(R.id.candidate_bar)
        bar.onCandidatePicked = { index -> pickCandidate(index) }
        bar.onCandidateLongPressed = { index -> rememberCandidate(index) }
        bar.onSettingsClick = { openSettings() }
        bar.onPagePrev = { moveCandidatePage(-1) }
        bar.onPageNext = { moveCandidatePage(1) }
        candidateView = bar

        // 软键盘
        val keyboard = root.findViewById<SoftKeyboardView>(R.id.soft_keyboard)
        keyboard.onKey = { key -> handleSoftKey(key) }
        // 空格键按住 ≥ 设置阈值直接进入语音输入：与语音键共用同一套按下 / 抬起语义
        keyboard.onSpaceLongPress = { onVoicePressDown() }
        keyboard.onSpaceLongPressEnd = { onVoicePressUp() }
        keyboard.keyHeightDp = settings.keyHeightDp
        keyboard.vibrate = settings.vibrate
        keyboard.setEnglishMode(mode == InputMode.ENGLISH)
        keyboard.setPinyinMode(mode == InputMode.PINYIN)
        keyboard.setLayout(KeyboardLayoutKind.LETTER)
        softKeyboard = keyboard

        // 快捷短语面板（csBaby）
        val phrases = root.findViewById<QuickPhrasePanel>(R.id.quick_phrase_panel)
        phrases.onInsert = { content -> insertFromPanel(content) }
        phrases.onClose = { closePanel() }
        phrases.onToggleStar = { entry -> togglePhraseStar(entry) }
        phrases.onClearKeyword = { setPanelKeyword("") }
        quickPhrasePanel = phrases

        // 剪贴板面板
        val clipboard = root.findViewById<ClipboardPanel>(R.id.clipboard_panel)
        clipboard.onInsert = { text -> insertFromPanel(text) }
        clipboard.onClose = { closePanel() }
        clipboard.onRefresh = { refreshClipboard() }
        clipboard.onUndo = { undoLastCommit() }
        clipboard.onClear = { clearClipboardHistory() }
        clipboardPanel = clipboard

        // 输入法切换面板
        val imeSwitcher = root.findViewById<InputMethodPanel>(R.id.input_method_panel)
        imeSwitcher.onPick = { id -> switchToInputMethod(id) }
        imeSwitcher.onClose = { closePanel() }
        imeSwitcher.onSystemPicker = { showSystemImePicker() }
        inputMethodPanel = imeSwitcher

        // 输入法内置配置面板（搜狗风格功能设置）：不离开键盘即可改设置
        val panelSettings = root.findViewById<SettingsPanel>(R.id.settings_panel)
        panelSettings.onClose = { closePanel() }
        panelSettings.onOpenFullSettings = { launchSettingsActivity() }
        panelSettings.onToggle = { key, value -> applySettingsToggle(key, value) }
        panelSettings.onAsrChange = { next -> applyAsrChange(next) }
        panelSettings.onChineseInputMethodChange = { next -> applyChineseInputMethodChange(next) }
        settingsPanel = panelSettings

        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM,
        )

        updatePanelVisibility()
        renderCandidateView()
        return root
    }

    /** 候选条 + 软键盘始终随输入法窗口展示 */
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshEngineIfStale()
        // 空格长按进语音的阈值由设置页决定（0.5s / 1s），每次打开键盘时同步一次
        softKeyboard?.spaceLongPressMs = SettingsStore.voiceSpaceLongPressMs(this).toLong()
        // 设置页「中文输入法」决定中文态默认输入法（默认五笔）；英文态是临时状态，不覆盖
        chineseInputMethod = SettingsStore.chineseInputMethod(this)
        if (mode != InputMode.ENGLISH) {
            mode = InputModePolicy.chineseMode(chineseInputMethod)
            softKeyboard?.setPinyinMode(mode == InputMode.PINYIN)
        }
        punctor.reset()
        renderCandidateView()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetState()
    }

    override fun onFinishInput() {
        // 切换输入框前先把组合内容上屏，避免丢字（对应 macOS 版 deactivateServer）
        if (buffer.isNotEmpty()) {
            commitTop()
        }
        resetState()
        super.onFinishInput()
    }

    override fun onDestroy() {
        voiceController?.destroy()
        voiceController = null
        funasrVoice?.destroy()
        funasrVoice = null
        voskVoice?.destroy()
        voskVoice = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /** 订阅键盘设置：设置页改动后键盘高度 / 候选数 / 标点策略立即生效 */
    private fun observeSettings() {
        serviceScope.launch {
            SettingsStore.keyboardSettingsFlow(this@IspeechImeService).collect { latest ->
                settings = latest
                softKeyboard?.keyHeightDp = latest.keyHeightDp
                softKeyboard?.vibrate = latest.vibrate
                if (panel == Panel.SETTINGS) settingsPanel?.submit(latest, asr, chineseInputMethod)
                refreshCandidates()
            }
        }
        serviceScope.launch {
            SettingsStore.asrSettingsFlow(this@IspeechImeService).collect { latest ->
                asr = latest
                if (panel == Panel.SETTINGS) settingsPanel?.submit(settings, latest, chineseInputMethod)
            }
        }
        serviceScope.launch {
            SettingsStore.chineseInputMethodFlow(this@IspeechImeService).collect { latest ->
                val changed = latest != chineseInputMethod
                chineseInputMethod = latest
                if (panel == Panel.SETTINGS) {
                    settingsPanel?.submit(settings, asr, chineseInputMethod)
                }
                // 中文态下立即切到新默认；英文态保持不变，下次切回中文时生效
                if (changed && mode != InputMode.ENGLISH) {
                    val target = InputModePolicy.chineseMode(chineseInputMethod)
                    if (target != mode) enterMode(target)
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  软键盘入口
    // ──────────────────────────────────────────────

    /** 软键盘按键统一入口：视图只上报 KeyDef，语义在这里决定 */
    private fun handleSoftKey(key: KeyDef) {
        // 快捷短语面板：软键盘与面板同屏，字母进筛选框、退格删字符、空格上屏第一条。
        // 其余动作（MODE_SWITCH / LAYOUT / 面板键等）放行到下方原有逻辑。
        if (panel == Panel.QUICK_PHRASE) {
            when (key.action) {
                KeyAction.CHAR -> {
                    setPanelKeyword(panelKeyword + key.output)
                    return
                }
                KeyAction.BACKSPACE -> {
                    backspacePanelKeyword()
                    return
                }
                KeyAction.SPACE -> {
                    insertPanelFirstMatch()
                    return
                }
                else -> Unit
            }
        }
        // 九宫格（T9）：2-9 是编码键，数字先进 T9 缓冲再由空格上屏，不能直接上屏数字
        if (softKeyboard?.layoutKind == KeyboardLayoutKind.T9) {
            val digit = T9Layouts.codeDigitOf(key)
            if (digit != null) {
                handleT9Digit(digit)
                return
            }
        }
        when (key.action) {
            KeyAction.CHAR -> handleChar(key.output)
            KeyAction.PUNCTUATE -> commitPunctuation(key.output)
            KeyAction.SHIFT -> softKeyboard?.toggleShift()
            KeyAction.BACKSPACE -> handleBackspace()
            KeyAction.SPACE -> handleSpace()
            KeyAction.ENTER -> handleEnter()
            KeyAction.TAB -> sendKey(KeyEvent.KEYCODE_TAB)
            KeyAction.MOVE_LEFT -> sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            KeyAction.MOVE_RIGHT -> sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            KeyAction.MODE_SWITCH -> toggleMode()
            KeyAction.LAYOUT -> key.layout?.let { switchLayout(it, key.pageOffset) }
            KeyAction.PAGE_NEXT -> softKeyboard?.movePage(1)
            KeyAction.PAGE_PREV -> softKeyboard?.movePage(-1)
            KeyAction.PANEL_QUICK_PHRASE -> openPanel(Panel.QUICK_PHRASE)
            KeyAction.PANEL_CLIPBOARD -> openPanel(Panel.CLIPBOARD)
            KeyAction.SWITCH_IME -> openPanel(Panel.SWITCH_IME)
            KeyAction.AI -> startAiReply()
            KeyAction.SETTINGS -> openSettings()
            KeyAction.HIDE_KEYBOARD -> requestHideSelf(0)
            KeyAction.CLEAR -> handleEscape()
            KeyAction.SELECT_ALL -> contextAction(android.R.id.selectAll)
            KeyAction.COPY -> contextAction(android.R.id.copy)
            KeyAction.PASTE -> contextAction(android.R.id.paste)
            KeyAction.UNDO_COMMIT -> undoLastCommit()
            KeyAction.TOGGLE_PUNCT -> toggleChinesePunctuation()
            KeyAction.TOGGLE_FULL_WIDTH -> toggleFullWidth()
            KeyAction.VOICE -> toggleVoiceInput()
        }
    }

    /**
     * 按住说话：按下开始收音（空格长按进语音走这里）。
     *
     * 引擎选择沿用 [toggleVoiceInput] 的既有判定：内置离线识别（[AsrEngine.FUNASR] /
     * [AsrEngine.VOSK]）与系统识别是完全不同的链路，这里只负责「开始」，结束交给 [onVoicePressUp]。
     */
    private fun onVoicePressDown() {
        if (asr.engine == AsrEngine.FUNASR) {
            if (funasrVoice?.isListening == true) return
            toggleFunAsrVoice()
            return
        }
        if (asr.engine == AsrEngine.VOSK) {
            if (voskVoice?.isListening == true) return
            toggleVoskVoice()
            return
        }
        if (voiceController?.isListening == true) return
        toggleVoiceInput()
    }

    /**
     * 按住说话：松手结束收音并上屏。
     *
     * 系统识别走 [VoiceInputController.finish]（保留回调，收尾结果仍经 onFinal 上屏）；
     * FunASR 走 [FunAsrSpeechEngine.finish]、Vosk 走 [VoskSpeechEngine.finish]
     * （工作线程读最终结果后回调）。
     */
    private fun onVoicePressUp() {
        if (asr.engine == AsrEngine.FUNASR) {
            funasrVoice?.finish()
            return
        }
        if (asr.engine == AsrEngine.VOSK) {
            voskVoice?.finish()
            return
        }
        voiceController?.finish()
    }

    /** 切换键盘布局；离开字母页前先把未完成的组合上屏，避免丢字。[page] 为直达页（如九宫格 `#`）。 */
    private fun switchLayout(kind: KeyboardLayoutKind, page: Int = 0) {
        if (buffer.isNotEmpty() && kind != KeyboardLayoutKind.LETTER) {
            commitTop()
        }
        // 离开九宫格（或九宫格内跳页）时数字串要么已上屏、要么不再可编辑，先落定
        if (t9Digits.isNotEmpty() && kind != KeyboardLayoutKind.T9) {
            commitT9()
        }
        statusText = null
        if (kind == KeyboardLayoutKind.LETTER) {
            softKeyboard?.setEnglishMode(mode == InputMode.ENGLISH)
            softKeyboard?.setPinyinMode(mode == InputMode.PINYIN)
        }
        softKeyboard?.setLayout(kind, page)
        renderCandidateView()
    }

    // ──────────────────────────────────────────────
    //  硬件键盘入口
    // ──────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Ctrl / Alt / Meta 组合键放行给宿主应用，避免抢快捷键
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
            return false
        }

        // 面板打开时：字母进筛选框，退格删筛选字符，空格上屏第一条，返回键关面板
        if (panel != Panel.NONE) {
            when {
                keyCode == KeyEvent.KEYCODE_BACK -> {
                    closePanel()
                    return true
                }
                panel == Panel.QUICK_PHRASE && keyCode == KeyEvent.KEYCODE_DEL -> {
                    backspacePanelKeyword()
                    return true
                }
                panel == Panel.QUICK_PHRASE && keyCode == KeyEvent.KEYCODE_SPACE -> {
                    insertPanelFirstMatch()
                    return true
                }
                panel == Panel.QUICK_PHRASE && keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                    setPanelKeyword("")
                    return true
                }
                panel == Panel.QUICK_PHRASE && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                    val letter = 'a' + (keyCode - KeyEvent.KEYCODE_A)
                    setPanelKeyword(panelKeyword + letter)
                    return true
                }
            }
            return false
        }

        // 方向键：组合中吞掉，避免误提交
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        ) {
            return buffer.isNotEmpty() || associationActive
        }

        // 退格
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (buffer.isEmpty() && !aiReplyActive && !associationActive) return false
            handleBackspace()
            return true
        }

        // Esc：清空组合
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (buffer.isEmpty() && !aiReplyActive && !associationActive && statusText == null) return false
            handleEscape()
            return true
        }

        // 空格 / 回车：上屏首选（无候选则上屏原始编码）
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            if (buffer.isEmpty() && !aiReplyActive && !associationActive) return false
            if (keyCode == KeyEvent.KEYCODE_SPACE) handleSpace() else handleEnter()
            return true
        }

        // 数字 1-9：选第 N 个候选
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            if (buffer.isEmpty() && !associationActive) return false
            val index = keyCode - KeyEvent.KEYCODE_1
            when {
                index < candidates.size -> pickCandidate(index)
                candidates.isEmpty() && buffer.isNotEmpty() -> commit(buffer, allowAssociation = false)
            }
            return true
        }

        // - / = 翻候选页（对齐桌面输入法习惯）
        if (keyCode == KeyEvent.KEYCODE_MINUS) {
            if (pageCount <= 1) return false
            moveCandidatePage(-1)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_EQUALS) {
            if (pageCount <= 1) return false
            moveCandidatePage(1)
            return true
        }

        // 字母键 a-z
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val letter = 'a' + (keyCode - KeyEvent.KEYCODE_A)
            handleChar(if (event.isShiftPressed) letter.uppercaseChar().toString() else letter.toString())
            return true
        }

        // 其他按键：先上屏已选候选，再把按键放行给应用
        if (buffer.isNotEmpty()) commitTop()
        return false
    }

    // ──────────────────────────────────────────────
    //  统一 handler（硬件键盘 & 软键盘共用）
    // ──────────────────────────────────────────────

    /**
     * 处理字符输入。
     * - 非字母（数字 / 符号 / emoji）：直接上屏（按全角设置转换）
     * - 英文态：直接上屏，Shift / Caps 生效
     * - 中文态字母：进入五笔缓冲；Shift / Caps 开启时改上屏大写英文字母
     */
    /** 当前输入态的组合串长度上限：拼音比五笔长（全拼 / 多字词）。 */
    private fun maxComposeLength(): Int =
        if (mode == InputMode.PINYIN) MAX_PINYIN_LENGTH else MAX_CODE_LENGTH

    private fun handleChar(raw: String) {
        if (raw.isEmpty()) return
        val ch = raw[0]
        dismissAiState()

        // 只有「单个字符」才可能是编码字母：emoji 等是一个或多个码点（代理对），
        // 必须整串上屏，绝不能只取 raw[0] 把代理对截成半个字符。
        val isLetter = raw.length == 1 && ((ch in 'a'..'z') || (ch in 'A'..'Z'))
        if (!isLetter) {
            // 直接上屏的字符会顶掉输入框里的组合区，先把未完成的编码上屏，避免 buffer 残留
            if (buffer.isNotEmpty()) commitTop()
            clearAssociation()
            commitDirect(applyFullWidth(raw), directCommitType(raw))
            return
        }

        val upper = softKeyboard?.shiftOn == true || softKeyboard?.capsLock == true
        if (mode == InputMode.ENGLISH || upper) {
            clearAssociation()
            if (buffer.isNotEmpty()) commitTop()
            val out = if (upper || ch.isUpperCase()) {
                ch.uppercaseChar().toString()
            } else {
                ch.lowercaseChar().toString()
            }
            commitDirect(applyFullWidth(out), CommitType.ENGLISH)
            if (softKeyboard?.capsLock != true) softKeyboard?.resetShift()
            return
        }

        clearAssociation()
        val lower = ch.lowercaseChar()
        if (buffer.length >= maxComposeLength()) {
            commitTop()
        }
        buffer += lower
        refreshCandidates()
    }

    /** 退格：缓冲有内容时删码，否则删应用侧光标前字符 */
    private fun handleBackspace() {
        if (t9Digits.isNotEmpty()) {
            t9Digits = t9Digits.substring(0, t9Digits.length - 1)
            refreshT9Candidates()
            return
        }
        if (buffer.isNotEmpty()) {
            buffer = buffer.substring(0, buffer.length - 1)
            refreshCandidates()
            return
        }
        if (voiceActive) {
            cancelVoice()
            renderCandidateView()
            return
        }
        if (aiReplyActive || statusText != null) {
            dismissAiState()
            renderCandidateView()
            return
        }
        if (associationActive) {
            clearAssociation()
            renderCandidateView()
            return
        }
        val ic = currentInputConnection ?: return
        // 有选区时 deleteSurroundingText 只删光标前一个字符、不会删掉选中内容，
        // 交给应用处理 ⌫ 才会删选区；无选区（或查询失败）时保持原逻辑
        val hasSelection = runCatching { !ic.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false)
        if (hasSelection) sendKey(KeyEvent.KEYCODE_DEL) else ic.deleteSurroundingText(1, 0)
    }

    /** 空格：缓冲有内容时上屏首选，否则输出空格 */
    private fun handleSpace() {
        if (t9Digits.isNotEmpty()) {
            commitT9()
            return
        }
        if (buffer.isNotEmpty()) {
            commitTop()
            return
        }
        if (aiReplyActive) {
            commitAiReply()
            return
        }
        if (voiceActive) {
            commitVoice()
            return
        }
        if (associationActive) {
            pickCandidate(0)
            return
        }
        commitDirect(" ", CommitType.PUNCTUATION)
    }

    /** 回车：缓冲有内容时上屏首选，否则输出换行 */
    private fun handleEnter() {
        if (buffer.isNotEmpty()) {
            commitTop()
            return
        }
        if (aiReplyActive) {
            commitAiReply()
            return
        }
        clearAssociation()
        commitDirect("\n", CommitType.PUNCTUATION)
    }

    /** 输入态切换：中文态 ⇄ 英文态；回到中文时用设置页选定的默认中文输入法（见 [InputModePolicy]）。 */
    private fun toggleMode() {
        enterMode(InputModePolicy.toggle(mode, chineseInputMethod))
    }

    /** 落到指定输入态：落定未完成的组合，刷新键面 / 面板 / 状态。 */
    private fun enterMode(target: InputMode) {
        if (buffer.isNotEmpty()) commitTop()
        mode = target
        softKeyboard?.resetShift()
        softKeyboard?.setEnglishMode(mode == InputMode.ENGLISH)
        softKeyboard?.setPinyinMode(mode == InputMode.PINYIN)
        // 短语面板搜索框的「中/英」徽标复用同一个 mode，切换时同步刷新
        quickPhrasePanel?.setInputLanguage(mode == InputMode.ENGLISH)
        clearAssociation()
        statusText = when (mode) {
            InputMode.PINYIN -> "拼音输入"
            InputMode.ENGLISH -> "英文输入"
            InputMode.WUBI -> null
        }
        renderCandidateView()
    }

    /** 清空组合（Esc / 清键）：依次关面板 → 清 AI / 状态 → 清联想 → 清编码 */
    private fun handleEscape() {
        if (panel != Panel.NONE) {
            closePanel()
            return
        }
        if (voiceActive) {
            cancelVoice()
            renderCandidateView()
            return
        }
        if (aiReplyActive || statusText != null) {
            dismissAiState()
            renderCandidateView()
            return
        }
        if (t9Digits.isNotEmpty()) {
            t9Digits = ""
            refreshT9Candidates()
            return
        }
        if (buffer.isEmpty()) {
            clearAssociation()
            renderCandidateView()
            return
        }
        clear()
    }

    /** 切换中文标点（工具栏 / 符号页的标点键） */
    private fun toggleChinesePunctuation() {
        val next = !settings.chinesePunctuation
        serviceScope.launch { SettingsStore.setChinesePunctuation(this@IspeechImeService, next) }
        statusText = if (next) "中文标点" else "英文标点"
        renderCandidateView()
    }

    /** 切换全角 / 半角输出 */
    private fun toggleFullWidth() {
        val next = !settings.fullWidth
        serviceScope.launch { SettingsStore.setFullWidth(this@IspeechImeService, next) }
        statusText = if (next) "全角" else "半角"
        renderCandidateView()
    }

    // ──────────────────────────────────────────────
    //  上屏
    // ──────────────────────────────────────────────

    /**
     * 九宫格（T9）数字键：数字进独立缓冲，不直接上屏。
     *
     * 与五笔缓冲互斥——九宫格里不存在五笔编码，遇到残留组合先落定，避免两种状态打架。
     */
    private fun handleT9Digit(digit: Char) {
        if (buffer.isNotEmpty()) commitTop()
        if (t9Digits.length >= T9_MAX_DIGITS) commitT9()
        dismissAiState()
        clearAssociation()
        t9Digits += digit
        refreshT9Candidates()
    }

    /**
     * 九宫格数字串 → 候选。
     *
     * 展开字母组合后逐个查词库，放在后台线程（长数字串下组合数可达数百，主线程查会卡键盘），
     * 结果回来时校验序号与数字串，避免旧查询覆盖新输入。
     */
    private fun refreshT9Candidates() {
        val digits = t9Digits
        val seq = ++t9Seq
        pageIndex = 0
        associationActive = false
        statusText = null
        if (digits.isEmpty()) {
            codeOverride = null
            allCandidates = emptyList()
            candidates = emptyList()
            pageCount = 1
            currentInputConnection?.setComposingText("", 0)
            renderCandidateView()
            return
        }
        codeOverride = digits
        currentInputConnection?.setComposingText(digits, 1)
        val limit = settings.candidatePageSize * MAX_PAGES
        serviceScope.launch {
            val found = withContext(Dispatchers.Default) { lookupT9(digits, limit) }
            if (seq != t9Seq || t9Digits != digits) return@launch
            allCandidates = found
            applyPage()
            renderCandidateView()
        }
    }

    /**
     * 数字串 → 候选池。
     *
     * 数字本身不是编码，所以按字母组合展开（[T9Layouts.t9ToLetterPatterns]）逐个查词。
     * 查询源用九宫格专用拼音引擎（数字 → 拼音才是九宫格语义），拼音词表不可用时退回
     * 复合引擎（五笔 / 短语），保证九宫格至少能出候选。
     * 命中的候选按「来源档位 → 权重」重排：展开顺序是字典序，不重排会让低频词压住高频词。
     * 同一候选可能在多个组合下命中，按 display 去重。
     */
    private fun lookupT9(digits: String, limit: Int): List<LookupCandidate> {
        val composite = engine ?: return emptyList()
        if (limit <= 0) return emptyList()
        val pinyin = runCatching { AppContainer.pinyinEngine(this) }.getOrNull()?.takeIf { it.available }
        val source: LookupEngine = pinyin ?: composite
        val perPattern = settings.candidatePageSize.coerceAtLeast(1)
        val pool = ArrayList<LookupCandidate>()
        val seen = HashSet<String>()
        for (pattern in T9Layouts.t9ToLetterPatterns(digits).take(T9_PATTERN_LIMIT)) {
            for (candidate in source.lookup(pattern, perPattern)) {
                if (candidate.insertText.isEmpty()) continue
                if (seen.add(candidate.display)) pool += candidate
            }
            // 池子已经够几页了就停，别为了长数字串把整个组合空间查完
            if (pool.size >= limit * 4) break
        }
        if (pool.isEmpty()) return emptyList()
        val visible = pool.withoutTofu().toMutableList()
        visible.sortWith(T9_RANK)
        return if (visible.size > limit) visible.subList(0, limit).toList() else visible
    }

    /** 九宫格上屏：有候选上首选，没有就让数字原样上屏（电话号 / 验证码场景） */
    private fun commitT9() {
        if (t9Digits.isEmpty()) return
        val top = candidates.firstOrNull()
        val text = top?.insertText ?: t9Digits
        commit(text)
    }

    /** 上屏首选候选；无候选时上屏原始编码 */
    private fun commitTop() {
        if (buffer.isEmpty()) return
        val top = candidates.firstOrNull()
        if (top == null) {
            commit(buffer, allowAssociation = false)
            return
        }
        learn(top)
        commit(top.insertText)
    }

    /** 上屏文本，清空组合状态；[allowAssociation] 为真时接着给联想候选 */
    private fun commit(text: String, allowAssociation: Boolean = true) {
        if (text.isEmpty()) return
        val out = convertHan(text)
        currentInputConnection?.commitText(out, 1)
        buffer = ""
        t9Digits = ""
        t9Seq++
        lastCommit = out
        candidates = emptyList()
        allCandidates = emptyList()
        pageIndex = 0
        pageCount = 1
        associationActive = false
        voiceActive = false
        codeOverride = null
        statusText = null
        recordCommit(CommitType.CANDIDATE, out)
        if (allowAssociation) startAssociation(out) else renderCandidateView()
    }

    /** 直接上屏（不经过组合），用于英文、标点、符号、emoji、空格、换行 */
    private fun commitDirect(text: String, type: CommitType = CommitType.PUNCTUATION) {
        if (text.isEmpty()) return
        val out = convertHan(text)
        currentInputConnection?.commitText(out, 1)
        lastCommit = out
        recordCommit(type, out)
        renderCandidateView()
    }

    /** 标点上屏：按「中文标点 / 全角」设置转换，先提交未完成的组合 */
    private fun commitPunctuation(raw: String) {
        if (raw.isEmpty()) return
        if (buffer.isNotEmpty()) commitTop()
        dismissAiState()
        clearAssociation()
        // 中文标点只在中文态（五笔 / 拼音）生效：英文态字母页给的是半角标点，不该被翻成「，」「。」
        val chinese = settings.chinesePunctuation && mode != InputMode.ENGLISH
        val text = punctor.convert(raw, chinese, settings.fullWidth)
        commitDirect(text, CommitType.PUNCTUATION)
    }

    /** 全角转换（仅作用于直接上屏的字符；标点键走 [PunctuationConverter]） */
    private fun applyFullWidth(raw: String): String =
        if (settings.fullWidth) Punctuation.toFullWidth(raw) else raw

    /** 放弃当前组合，不往应用里写任何文字 */
    private fun clear() {
        buffer = ""
        candidates = emptyList()
        allCandidates = emptyList()
        pageCount = 1
        pageIndex = 0
        associationActive = false
        codeOverride = null
        currentInputConnection?.setComposingText("", 0)
        renderCandidateView()
    }

    /** 撤销上一次上屏（剪贴板面板「撤销上屏」） */
    private fun undoLastCommit() {
        val text = lastCommit
        if (text.isEmpty()) {
            statusText = "没有可撤销的内容"
            renderCandidateView()
            return
        }
        val ic = currentInputConnection
        val before = ic?.getTextBeforeCursor(text.length, 0)?.toString().orEmpty()
        if (before == text) {
            ic?.deleteSurroundingText(text.length, 0)
            lastCommit = ""
            statusText = "已撤销上屏"
        } else {
            statusText = "光标位置已变化，无法撤销"
        }
        renderCandidateView()
    }

    /**
     * 用户词频学习：把用户选中的词记进用户词典，下次该编码下排更前。
     *
     * 模式按候选来源决定（拼音候选写 `cn` 库、五笔候选写 `wb` 库），因此拼音态也能学习了；
     * 短语 / 快速应用的 code 是触发码而不是词库编码，一律不学，避免污染编码空间。
     */
    private fun learn(candidate: LookupCandidate) {
        if (!settings.learning) return
        if (t9Digits.isNotEmpty()) return
        val dictMode = dictModeOf(candidate.kind) ?: return
        if (candidate.code.isEmpty() || candidate.insertText.isEmpty()) return
        if (candidate.insertText.length > MAX_LEARN_TEXT_LENGTH) return
        if (candidate.code.length > maxCodeLengthOf(dictMode)) return
        val store: UserDictStore = AppContainer.userDict(this)
        serviceScope.launch(Dispatchers.IO) {
            store.record(candidate.code, candidate.insertText, dictMode)
        }
    }

    /**
     * 自造词：长按候选把它记进用户词库（对齐搜狗 `AddPhrase:Pinyin:` / `pfnAddPhrase`）。
     *
     * 与「词频学习」开关无关——那是自动行为，这是用户的显式动作。记完立刻重查候选，
     * 新词带用户权重排到最前，候选条自身就是「已记住」的反馈。
     */
    private fun rememberCandidate(index: Int) {
        val candidate = candidates.getOrNull(index) ?: return
        if (t9Digits.isNotEmpty()) return
        val dictMode = dictModeOf(candidate.kind) ?: return
        val code = candidate.code.ifEmpty { buffer }
        if (code.isEmpty() || candidate.insertText.isEmpty()) return
        if (candidate.insertText.length > MAX_LEARN_TEXT_LENGTH) return
        if (code.length > maxCodeLengthOf(dictMode)) return
        val store: UserDictStore = AppContainer.userDict(this)
        serviceScope.launch {
            withContext(Dispatchers.IO) { store.record(code, candidate.insertText, dictMode) }
            refreshCandidates()
        }
    }

    /** 候选来源 → 用户词库模式；短语 / 快速应用不是词库词，返回 null（不学习、不记词）。 */
    private fun dictModeOf(kind: LookupKind): UserDictMode? = when (kind) {
        LookupKind.WUBI -> UserDictMode.WUBI
        LookupKind.WUBIPHRASE -> UserDictMode.WUBI
        LookupKind.PINYIN -> UserDictMode.PINYIN
        LookupKind.PHRASE -> null
        LookupKind.QUICKAPPLY -> null
    }

    /** 词库编码长度上限：五笔码 4 位、拼音串最多 12 位（与 [MAX_PINYIN_LENGTH] 一致）。 */
    private fun maxCodeLengthOf(mode: UserDictMode): Int =
        if (mode == UserDictMode.PINYIN) MAX_PINYIN_LENGTH else MAX_CODE_LENGTH

    // ──────────────────────────────────────────────
    //  候选与分页
    // ──────────────────────────────────────────────

    /**
     * 当前输入态对应的候选来源。
     *
     * 五笔态走复合引擎（词库 + 短语 + 快速应用，含可选拼音混输）；
     * 拼音态走拼音引擎 + 用户词库 cn（[AppContainer.pinyinLookupEngine]），
     * 拼音词表缺失时回退复合引擎。
     */
    private fun lookupSource(): LookupEngine? {
        if (mode != InputMode.PINYIN) return engine
        val lookup = runCatching { AppContainer.pinyinLookupEngine(this) }.getOrNull()
        val hasDict = lookup?.engines?.any { it is PinyinEngine && it.available } == true
        return if (hasDict) lookup else engine
    }

    /** 组合码 / 联想文本变化后重新查询候选并回到第一页 */
    private fun refreshCandidates() {
        // 设置页 / 同步页改动引擎相关项后，下一次候选刷新即重建引擎，无需重启输入法
        refreshEngineIfStale()
        pageIndex = 0
        if (buffer.isEmpty()) {
            if (associationActive && lastCommit.isNotEmpty()) {
                // 联想可能因词库/设置变化查不到：查空时要复位联想状态，
                // 否则候选条会一直挂着「联想」且空格键点了没反应
                if (!lookupRelated(lastCommit)) clearAssociation()
                renderCandidateView()
            } else if (aiReplyActive) {
                // AI 回复候选展示中：设置变化不该把它清掉（清了空格键就点不动了）
                renderCandidateView()
            } else {
                // 既没有组合码也没有联想：顺手复位联想状态，避免残留导致空格变成死键
                clearAssociation()
                candidates = emptyList()
                allCandidates = emptyList()
                pageCount = 1
                currentInputConnection?.setComposingText("", 0)
                renderCandidateView()
            }
            return
        }
        val limit = settings.candidatePageSize * MAX_PAGES
        allCandidates = lookupSource()?.lookup(buffer, limit).orEmpty().withoutTofu()
        recordQuery()
        associationActive = false
        codeOverride = null
        applyPage()
        currentInputConnection?.setComposingText(markedCode(), 1)
        renderCandidateView()
    }

    /** 上屏一段文字后的联想候选 */
    private fun startAssociation(text: String) {
        val related = lookupRelated(text)
        if (!related) {
            renderCandidateView()
            return
        }
        associationActive = true
        codeOverride = "联想"
        renderCandidateView()
    }

    /** 查询联想候选并切页；返回是否命中 */
    private fun lookupRelated(text: String): Boolean {
        if (!settings.association) return false
        val tail = text.trim().takeLast(1)
        allCandidates = if (tail.isEmpty()) {
            emptyList()
        } else {
            engine?.related(tail, settings.candidatePageSize * MAX_PAGES).orEmpty().withoutTofu()
        }
        pageIndex = 0
        applyPage()
        return allCandidates.isNotEmpty()
    }

    /** 按当前页大小切出本页候选并计算总页数 */
    private fun applyPage() {
        val size = settings.candidatePageSize.coerceAtLeast(1)
        // 页数按真实候选数算，不再用 MAX_PAGES 截断：截断会让排在后面的候选永远翻不到，
        // MAX_PAGES 只用来约束查询条数 limit（见 refreshCandidates / lookupRelated）。
        pageCount = if (allCandidates.isEmpty()) 1 else (allCandidates.size + size - 1) / size
        if (pageIndex >= pageCount) pageIndex = 0
        val from = pageIndex * size
        val to = (from + size).coerceAtMost(allCandidates.size)
        candidates = if (from >= allCandidates.size) emptyList() else allCandidates.subList(from, to).toList()
    }

    /** 候选翻页（翻页器 / 硬件键盘 - =） */
    private fun moveCandidatePage(offset: Int) {
        if (pageCount <= 1) return
        val next = pageIndex + offset
        pageIndex = ((next % pageCount) + pageCount) % pageCount
        applyPage()
        renderCandidateView()
    }

    /** 点击候选条第 [index] 项：上屏其 insertText */
    private fun pickCandidate(index: Int) {
        if (aiReplyActive) {
            commitAiReply(index)
            return
        }
        val candidate = candidates.getOrNull(index) ?: return
        val wasAssociation = associationActive
        if (!wasAssociation) learn(candidate)
        commit(candidate.insertText, allowAssociation = !wasAssociation)
    }

    /** 组合码带下划线，等价于 macOS 版的 marked text 样式 */
    private fun markedCode(): CharSequence {
        val spanned = SpannableString(buffer)
        spanned.setSpan(UnderlineSpan(), 0, buffer.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spanned
    }

    /** 同步候选条：高亮组合码 + 候选列表 + 分页信息 */
    private fun renderCandidateView() {
        val code = statusText ?: codeOverride ?: buffer
        candidateView?.update(code, candidates, pageIndex, pageCount)
    }

    /** 清掉联想候选（用户继续输入时调用） */
    private fun clearAssociation() {
        if (!associationActive) return
        associationActive = false
        codeOverride = if (codeOverride == "联想") null else codeOverride
        allCandidates = emptyList()
        candidates = emptyList()
        pageCount = 1
        pageIndex = 0
    }

    /** 上屏未完成的组合（切面板、按其他键之前调用） */
    private fun commitPending() {
        if (buffer.isNotEmpty()) commitTop()
    }

    // ──────────────────────────────────────────────
    //  面板：快捷短语 / 剪贴板
    // ──────────────────────────────────────────────

    /** 打开 / 关闭面板（重复点击同一面板即关闭） */
    private fun openPanel(target: Panel) {
        if (panel == target) {
            closePanel()
            return
        }
        commitPending()
        clearAssociation()
        panel = target
        panelKeyword = ""
        updatePanelVisibility()
        when (target) {
            Panel.QUICK_PHRASE -> {
                quickPhrasePanel?.setKeyword("")
                quickPhrasePanel?.submit(emptyList())
                // 搜索框复用键盘左下角「中/英」键的当前输入状态，并把输入焦点落在搜索框
                quickPhrasePanel?.setInputLanguage(mode == InputMode.ENGLISH)
                quickPhrasePanel?.requestSearchFocus()
                statusText = "快捷短语 加载中…"
                renderCandidateView()
                loadQuickPhrases()
            }
            Panel.CLIPBOARD -> {
                clipboardPanel?.submit(emptyList())
                statusText = "剪贴板"
                renderCandidateView()
                refreshClipboard()
            }
            Panel.SWITCH_IME -> {
                inputMethodPanel?.submit(emptyList())
                statusText = "切换输入法"
                renderCandidateView()
                refreshInputMethods()
            }
            Panel.SETTINGS -> {
                settingsPanel?.submit(settings, asr, chineseInputMethod)
                statusText = getString(R.string.settings_panel_title)
                renderCandidateView()
            }
            Panel.NONE -> Unit
        }
    }

    private fun closePanel() {
        if (panel == Panel.NONE) return
        panel = Panel.NONE
        panelKeyword = ""
        quickPhrasePanel?.setKeyword("")
        statusText = null
        updatePanelVisibility()
        renderCandidateView()
    }

    /** 各内容视图显示切换：快捷短语面板与软键盘同屏，其余面板独占内容区 */
    private fun updatePanelVisibility() {
        // 快捷短语面板打开时保持软键盘可见（面板在上、键盘在下），以便用软键盘在面板内搜索；
        // 剪贴板 / 切换输入法面板打开时隐藏软键盘（维持原行为）。
        val showKeyboard = panel == Panel.NONE || panel == Panel.QUICK_PHRASE
        softKeyboard?.visibility = if (showKeyboard) View.VISIBLE else View.GONE
        quickPhrasePanel?.visibility = if (panel == Panel.QUICK_PHRASE) View.VISIBLE else View.GONE
        clipboardPanel?.visibility = if (panel == Panel.CLIPBOARD) View.VISIBLE else View.GONE
        inputMethodPanel?.visibility = if (panel == Panel.SWITCH_IME) View.VISIBLE else View.GONE
        settingsPanel?.visibility = if (panel == Panel.SETTINGS) View.VISIBLE else View.GONE
    }

    /** 读取全部快捷规则（内置 + csBaby 远端 + 用户），IO 线程解析后交给面板渲染 */
    private fun loadQuickPhrases() {
        serviceScope.launch {
            val entries: List<QuickEntry> = withContext(Dispatchers.IO) {
                runCatching {
                    val enableQuick = SettingsStore.enableQuickPhrasesFlow(this@IspeechImeService).first()
                    val enableRemote = SettingsStore.enableRemoteRulesFlow(this@IspeechImeService).first()
                    val singleNoTrigger = SettingsStore.singleLetterNoTriggerFlow(this@IspeechImeService).first()
                    QuickLocator.loadAll(
                        this@IspeechImeService,
                        includeLocal = enableQuick,
                        includeRemote = enableRemote,
                        includeUser = true,
                        minTriggerLength = if (singleNoTrigger) 2 else 1,
                    )
                }.getOrElse { emptyList() }
            }
            if (panel != Panel.QUICK_PHRASE) return@launch
            quickPhrasePanel?.submit(entries)
            refreshPhraseUsage(entries)
            statusText = if (entries.isEmpty()) "快捷短语为空" else "快捷短语 ${entries.size} 条"
            renderCandidateView()
        }
    }

    /** 面板点选的文本直接上屏，并关闭面板回到键盘 */
    private fun insertFromPanel(text: String) {
        if (text.isEmpty()) return
        val entry = quickByContent[text]
        if (entry != null) {
            val store = AppContainer.phraseUsage(this)
            serviceScope.launch { withContext(Dispatchers.IO) { store.recordUse(entry) } }
        }
        val out = convertHan(text)
        currentInputConnection?.commitText(out, 1)
        lastCommit = out
        recordCommit(CommitType.PHRASE, out)
        closePanel()
    }

    /** 面板筛选关键词（硬件键盘打字） */
    private fun setPanelKeyword(keyword: String) {
        panelKeyword = keyword
        quickPhrasePanel?.setKeyword(keyword)
        statusText = if (keyword.isEmpty()) "快捷短语" else "筛选：$keyword"
        renderCandidateView()
    }

    private fun backspacePanelKeyword() {
        if (panelKeyword.isEmpty()) return
        setPanelKeyword(panelKeyword.substring(0, panelKeyword.length - 1))
    }

    /** 面板内空格：上屏第一条匹配的短语 */
    private fun insertPanelFirstMatch() {
        val match = quickPhrasePanel?.firstMatch()
        if (match.isNullOrEmpty()) {
            statusText = "没有匹配的快捷短语"
            renderCandidateView()
            return
        }
        insertFromPanel(match)
    }

    /** 读取系统剪贴板并合并进历史（输入法窗口有焦点时系统才允许读取） */
    private fun refreshClipboard() {
        serviceScope.launch {
            val history: ClipboardHistory = AppContainer.clipboardHistory(this@IspeechImeService)
            val current = runCatching {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = manager?.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    null
                } else {
                    clip.getItemAt(0).coerceToText(this@IspeechImeService)?.toString()
                }
            }.getOrNull()
            val items = withContext(Dispatchers.IO) {
                if (!current.isNullOrBlank()) history.record(current)
                history.items()
            }
            if (panel != Panel.CLIPBOARD) return@launch
            clipboardPanel?.submit(items)
            statusText = if (items.isEmpty()) "剪贴板为空" else "剪贴板 ${items.size} 条"
            renderCandidateView()
        }
    }

    private fun clearClipboardHistory() {
        serviceScope.launch {
            val history = AppContainer.clipboardHistory(this@IspeechImeService)
            withContext(Dispatchers.IO) { history.clear() }
            clipboardPanel?.submit(emptyList())
            statusText = "剪贴板已清空"
            renderCandidateView()
        }
    }

    // ──────────────────────────────────────────────
    //  切换输入法
    // ──────────────────────────────────────────────

    // ──────────────────────────────────────────────
    //  简繁转换 / 输入统计 / 短语使用记录
    // ──────────────────────────────────────────────

    /** 繁体输出：按设置把上屏文本做简 → 繁转换（未装载映射时原样返回）。 */
    private fun convertHan(text: String): String =
        if (settings.traditionalOutput && text.isNotEmpty()) HanConversion.toTraditional(text) else text

    /** 直接上屏字符的统计分类：emoji 与数字符号分开记，统计页才能区分。 */
    private fun directCommitType(raw: String): CommitType {
        for (ch in raw) {
            val code = ch.code
            if (ch.isSurrogate() ||
                code in 0x1F300..0x1FAFF ||
                code in 0x2600..0x27BF ||
                code in 0x2190..0x21FF ||
                code in 0x2B00..0x2BFF
            ) {
                return CommitType.EMOJI
            }
        }
        return CommitType.PUNCTUATION
    }

    /** 记一次上屏（IO 线程，失败不影响输入） */
    private fun recordCommit(type: CommitType, text: String) {
        if (text.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching { stats.recordCommit(type, text) }
        }
    }

    /** 记一次候选查询（命中率的分母） */
    private fun recordQuery() {
        serviceScope.launch(Dispatchers.IO) {
            runCatching { stats.recordQuery() }
        }
    }

    /** 把规则集合与使用记录灌给面板：正文索引 + 收藏 / 最近两个筛选集 + 使用次数（联想排序） */
    private fun refreshPhraseUsage(entries: List<QuickEntry>) {
        quickByContent = entries.associateBy({ it.content }, { it })
        val store = AppContainer.phraseUsage(this)
        serviceScope.launch {
            val (starred, recent, counts) = withContext(Dispatchers.IO) {
                val known = entries.map { PhraseUsageStore.idOf(it) }.toSet()
                val starredIds = known.filter { store.isStarred(it) }.toSet()
                val recentIds = store.recentIds().filter { it in known }.toSet()
                // 使用次数只保留 > 0 的条目，面板按它给搜索联想加权
                val usageCounts = known.associateWith { store.countOf(it) }.filterValues { it > 0 }
                Triple(starredIds, recentIds, usageCounts)
            }
            quickPhrasePanel?.submitUsage(starred, recent, counts)
        }
    }

    /** 面板星标点击：切换收藏并刷新面板状态 */
    private fun togglePhraseStar(entry: QuickEntry) {
        val store = AppContainer.phraseUsage(this)
        serviceScope.launch {
            withContext(Dispatchers.IO) { store.toggleStar(entry) }
            statusText = if (store.isStarred(PhraseUsageStore.idOf(entry))) "已收藏" else "已取消收藏"
            renderCandidateView()
            refreshPhraseUsage(quickByContent.values.toList())
        }
    }

    /** 系统里已启用的输入法（当前项排最前，其余按名称排序） */
    private fun enabledInputMethods(): List<InputMethodPanel.InputMethodItem> {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptyList()
        val current = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        val items = ArrayList<InputMethodPanel.InputMethodItem>()
        for (info in manager.enabledInputMethodList) {
            val id = info.id ?: continue
            val label = runCatching { info.loadLabel(packageManager).toString() }.getOrNull()
                ?: info.packageName
            items.add(InputMethodPanel.InputMethodItem(id, label, id == current))
        }
        items.sortWith(
            compareByDescending<InputMethodPanel.InputMethodItem> { it.current }.thenBy { it.label }
        )
        return items
    }

    /** 刷新切换面板：输入法列表是本地数据，主线程查询即可，不走 IO */
    private fun refreshInputMethods() {
        val items = runCatching { enabledInputMethods() }.getOrElse { emptyList() }
        inputMethodPanel?.submit(items)
        statusText = when {
            items.isEmpty() -> "读不到输入法列表"
            items.size == 1 -> "没有其他输入法"
            else -> "输入法 ${items.size} 个"
        }
        renderCandidateView()
    }

    /**
     * 切换到指定输入法。
     *
     * [InputMethodManager.setInputMethod] 只允许当前输入法用自己窗口的 token 调用，
     * 个别 ROM 会拦截；失败就退回系统自带的输入法选择器，保证用户总能切走。
     */
    private fun switchToInputMethod(id: String) {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (manager == null) {
            statusText = "无法切换输入法"
            renderCandidateView()
            return
        }
        val token = window?.window?.attributes?.token
        val switched = token != null && runCatching { manager.setInputMethod(token, id) }
            .onFailure { Log.w(TAG, "setInputMethod 失败: ${it.message}") }
            .isSuccess
        if (switched) closePanel() else showSystemImePicker()
    }

    /** 系统输入法选择器（切换失败时的兜底） */
    private fun showSystemImePicker() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val shown = manager != null && runCatching { manager.showInputMethodPicker() }
            .onFailure { Log.w(TAG, "showInputMethodPicker 失败: ${it.message}") }
            .isSuccess
        if (shown) {
            closePanel()
        } else {
            statusText = "系统不允许切换输入法"
            renderCandidateView()
        }
    }

    // ──────────────────────────────────────────────
    //  语音输入（系统 SpeechRecognizer）
    // ──────────────────────────────────────────────

    /**
     * 点击语音键：未授权则引导授权 → 识别中则取消 → 否则开始识别。
     *
     * 识别文本**说完即上屏**（最终结果直接提交到输入框）：语音是一次到位的整句，
     * 不像五笔 / 拼音还要从候选里挑，若只放进候选条等用户再按空格，就会显得「说完不出字」。
     * 中间结果（partial）仍先作为候选实时展示。
     */
    private fun toggleVoiceInput() {
        // 内置离线识别（FunASR / Vosk）与系统识别是完全不同的链路，分开处理
        if (asr.engine == AsrEngine.FUNASR) {
            toggleFunAsrVoice()
            return
        }
        if (asr.engine == AsrEngine.VOSK) {
            toggleVoskVoice()
            return
        }
        val controller = voiceController
        if (controller == null || !controller.isAvailable) {
            statusText = getString(R.string.voice_unavailable)
            renderCandidateView()
            return
        }
        // 有的 ROM 只预置了桩识别服务（华为 ALN-AL00 的 FakeRecognitionService）：
        // isRecognitionAvailable() 为 true 却永远不出结果。这里提前拦截并给出可操作指引，
        // 免得用户反复点麦克风、以为是自己的操作问题。
        if (!controller.hasUsableService()) {
            statusText = getString(R.string.voice_service_unavailable)
            codeOverride = null
            candidates = emptyList()
            renderCandidateView()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText = getString(R.string.voice_need_permission)
            renderCandidateView()
            requestMicPermission()
            return
        }
        if (controller.isListening) {
            controller.stop()
            cancelVoice()
            statusText = getString(R.string.voice_cancel)
            renderCandidateView()
            return
        }
        if (panel != Panel.NONE) closePanel()
        commitPending()
        clearAssociation()
        dismissAiState()
        voiceActive = true
        statusText = getString(R.string.voice_listening)
        codeOverride = "语音"
        candidates = emptyList()
        allCandidates = emptyList()
        pageCount = 1
        pageIndex = 0
        renderCandidateView()
        controller.start(
            asr = asr,
            // 宿主应用包名：输入法自身不是宿主，部分 ROM 的识别服务据此归属录音会话与结果，
            // 缺失时会立刻回 ERROR_CLIENT（表现为「点语音键没反应」）
            callingPackage = currentInputEditorInfo?.packageName,
            onPartial = { text -> showVoiceCandidate(text) },
            onFinal = { text ->
                // 最终结果直接上屏（见 [commitVoiceText]），不再等用户按空格
                statusText = null
                commitVoiceText(text)
            },
            onError = { message ->
                voiceActive = false
                if (codeOverride == "语音") codeOverride = null
                statusText = message
                candidates = emptyList()
                renderCandidateView()
            },
        )
    }

    /** 把语音识别文本填入候选（点选 / 空格上屏）。 */
    private fun showVoiceCandidate(text: String) {
        if (text.isBlank()) return
        candidates = listOf(
            LookupCandidate(
                code = "voice",
                display = preview(text),
                insertText = text,
                weight = 0,
                kind = LookupKind.QUICKAPPLY,
            ),
        )
        renderCandidateView()
    }

    /** 上屏语音候选（空格 / 语音态直上屏后复位）。 */
    private fun commitVoice() {
        val candidate = candidates.firstOrNull()
        voiceActive = false
        if (candidate == null) {
            renderCandidateView()
            return
        }
        commit(candidate.insertText)
    }

    /**
     * 语音最终结果直接上屏。
     *
     * 中间结果走 [showVoiceCandidate] 当候选实时展示，最终结果则直接提交进输入框
     * （无需再按空格），这是与「候选待选」路径并存的收尾方式。
     */
    private fun commitVoiceText(text: String) {
        voiceActive = false
        if (codeOverride == "语音") codeOverride = null
        candidates = emptyList()
        allCandidates = emptyList()
        pageCount = 1
        pageIndex = 0
        if (text.isBlank()) {
            renderCandidateView()
            return
        }
        commitDirect(text, CommitType.VOICE)
    }

    // ── 内置离线识别（FunASR / Vosk 端侧引擎）──

    /**
     * FunASR 离线识别开关：模型未就绪先下载；收音中再点一次即「结束并上屏」。
     *
     * FunASR 不做端点检测（没有「说完自动结束」），所以交互是「点一次开始、再点一次结束」，
     * 与 [FunAsrSpeechEngine] 的 [FunAsrSpeechEngine.finish] 语义一致。下载与就绪判定复用
     * [FunAsrModelStore]（本 issue 只恢复接线，不改其下载 / 校验逻辑）。
     */
    private fun toggleFunAsrVoice() {
        val engine = funasrVoice ?: return
        if (!engine.isReady) {
            startFunAsrModelDownload()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText = getString(R.string.voice_need_permission)
            renderCandidateView()
            requestMicPermission()
            return
        }
        if (engine.isListening) {
            engine.finish()
            return
        }
        if (panel != Panel.NONE) closePanel()
        commitPending()
        clearAssociation()
        dismissAiState()
        voiceActive = true
        statusText = getString(R.string.voice_listening)
        codeOverride = "语音"
        candidates = emptyList()
        allCandidates = emptyList()
        pageCount = 1
        pageIndex = 0
        renderCandidateView()
        engine.start(
            onPartial = { text -> showVoiceCandidate(text) },
            onFinal = { text ->
                statusText = null
                commitVoiceText(text)
            },
            onError = { message ->
                voiceActive = false
                if (codeOverride == "语音") codeOverride = null
                statusText = message
                candidates = emptyList()
                renderCandidateView()
            },
        )
    }

    /**
     * Vosk 离线识别开关：模型未就绪先下载；收音中再点一次即「结束并上屏」。
     *
     * Vosk 不做端点检测（没有「说完自动结束」），所以交互是「点一次开始、再点一次结束」，
     * 与 [VoskSpeechEngine] 的 [VoskSpeechEngine.finish] 语义一致。
     */
    private fun toggleVoskVoice() {
        val engine = voskVoice ?: return
        if (!engine.isReady) {
            startVoskModelDownload()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText = getString(R.string.voice_need_permission)
            renderCandidateView()
            requestMicPermission()
            return
        }
        if (engine.isListening) {
            engine.finish()
            return
        }
        if (panel != Panel.NONE) closePanel()
        commitPending()
        clearAssociation()
        dismissAiState()
        voiceActive = true
        statusText = getString(R.string.voice_listening)
        codeOverride = "语音"
        candidates = emptyList()
        allCandidates = emptyList()
        pageCount = 1
        pageIndex = 0
        renderCandidateView()
        engine.start(
            onPartial = { text -> showVoiceCandidate(text) },
            onFinal = { text ->
                statusText = null
                commitVoiceText(text)
            },
            onError = { message ->
                voiceActive = false
                if (codeOverride == "语音") codeOverride = null
                statusText = message
                candidates = emptyList()
                renderCandidateView()
            },
        )
    }

    /** 首次使用 FunASR 离线识别时下载模型（约 74MB）：进度显示在候选条，完成后提示再点一次麦克风。 */
    private fun startFunAsrModelDownload() {
        if (offlineModelDownloading) return
        offlineModelDownloading = true
        statusText = getString(R.string.voice_model_downloading, 0)
        renderCandidateView()
        serviceScope.launch {
            val ok = FunAsrModelStore.ensureModel(this@IspeechImeService) { percent ->
                statusText = getString(R.string.voice_model_downloading, percent)
                renderCandidateView()
            }
            offlineModelDownloading = false
            statusText = getString(if (ok) R.string.voice_model_ready else R.string.voice_model_failed)
            renderCandidateView()
        }
    }

    /** 首次使用 Vosk 离线识别时下载模型（约 42MB）：进度显示在候选条，完成后提示再点一次麦克风。 */
    private fun startVoskModelDownload() {
        if (offlineModelDownloading) return
        offlineModelDownloading = true
        statusText = getString(R.string.voice_model_downloading, 0)
        renderCandidateView()
        serviceScope.launch {
            val ok = VoskModelStore.ensureModel(this@IspeechImeService) { percent ->
                statusText = getString(R.string.voice_model_downloading, percent)
                renderCandidateView()
            }
            offlineModelDownloading = false
            statusText = getString(if (ok) R.string.voice_model_ready else R.string.voice_model_failed)
            renderCandidateView()
        }
    }

    /** 取消语音识别并复位语音状态（不负责渲染）。 */
    private fun cancelVoice() {
        if (voiceController?.isListening == true) voiceController?.stop()
        funasrVoice?.cancel()
        voskVoice?.cancel()
        voiceActive = false
        if (codeOverride == "语音") codeOverride = null
        candidates = emptyList()
        allCandidates = emptyList()
        pageCount = 1
        pageIndex = 0
        statusText = null
    }

    /** 未授予 RECORD_AUDIO 时拉起设置页触发系统权限申请（extra 键见 [REQUEST_MIC_EXTRA]）。 */
    private fun requestMicPermission() {
        val intent = Intent().apply {
            setClassName(this@IspeechImeService, SETTINGS_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(REQUEST_MIC_EXTRA, true)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "未找到设置页 $SETTINGS_ACTIVITY", e)
        }
    }

    // ──────────────────────────────────────────────
    //  AI 回复（对接 csBaby 后端已配置的 LLM）
    // ──────────────────────────────────────────────

    /** 点击 AI 键：以输入框当前文本为消息，调用后端生成回复候选。 */
    private fun startAiReply() {
        if (aiBusy) return
        if (panel != Panel.NONE) closePanel()
        commitPending()
        clearAssociation()
        val message = currentInputConnection
            ?.getTextBeforeCursor(AI_MAX_MESSAGE_CHARS, 0)
            ?.toString()
            .orEmpty()
        if (message.isBlank()) {
            statusText = "请先输入要回复的内容"
            candidates = emptyList()
            aiReplyActive = false
            renderCandidateView()
            return
        }
        aiBusy = true
        aiReplyActive = false
        candidates = emptyList()
        statusText = "AI 生成中…"
        renderCandidateView()
        val seq = ++aiRequestSeq
        serviceScope.launch {
            try {
                // 1. 先找快捷短语：命中就直接当建议回复展示，不再调后端
                val phrases = matchQuickPhrases(message)
                if (seq != aiRequestSeq) return@launch
                if (phrases.isNotEmpty()) {
                    aiSourceText = message
                    aiSourceLen = message.length
                    aiReplyActive = true
                    // 快捷短语命中：不是模型产出，不计入采纳率与学习语料
                    aiReplyFromModel = false
                    statusText = null
                    codeOverride = "快捷短语"
                    allCandidates = emptyList()
                    pageCount = 1
                    pageIndex = 0
                    candidates = phrases
                    renderCandidateView()
                    return@launch
                }
                // 2. 没命中：按设置调用大模型生成建议回复
                val aiSettings = SettingsStore.aiSettingsFlow(this@IspeechImeService).first()
                if (!aiSettings.isReady()) {
                    if (seq == aiRequestSeq) {
                        statusText = if (aiSettings.provider.isDirect) {
                            "请先在设置页配置大模型"
                        } else {
                            "未登录：请先在设置页登录 csBaby"
                        }
                        candidates = emptyList()
                        renderCandidateView()
                    }
                    return@launch
                }
                // 2.5 学习复用：这次输入与某条已采纳的回复高度相似时，直接用那条。
                // 命中率高（是用户自己写过的），零延迟零成本；相似度不够还是走模型。
                if (aiSettings.learning) {
                    val reusable = withContext(Dispatchers.IO) { aiLearning.findReusable(message) }
                    if (seq != aiRequestSeq) return@launch
                    if (reusable != null) {
                        withContext(Dispatchers.IO) { aiLearning.recordGenerated() }
                        aiSourceText = message
                        aiSourceLen = message.length
                        aiReplyActive = true
                        // 复用历史回复同样计入采纳统计（用来强化高频回复）
                        aiReplyFromModel = true
                        statusText = null
                        codeOverride = "常用回复"
                        allCandidates = emptyList()
                        pageCount = 1
                        pageIndex = 0
                        candidates = listOf(
                            LookupCandidate(
                                code = "learned",
                                display = preview(reusable.reply),
                                insertText = reusable.reply,
                                weight = 0,
                                kind = LookupKind.QUICKAPPLY,
                            ),
                        )
                        renderCandidateView()
                        return@launch
                    }
                }
                val reply = if (aiSettings.provider.isDirect) {
                    // 学习用户输入：把过去采纳过的回复作为 few-shot 例子带进提示词
                    val examples = if (aiSettings.learning) {
                        withContext(Dispatchers.IO) {
                            aiLearning.examples(DirectLlmClient.MAX_FEW_SHOT, message)
                        }
                    } else {
                        emptyList()
                    }
                    DirectLlmClient(this@IspeechImeService).generate(aiSettings, message, examples)
                } else {
                    val auth = CsBabyAuthStore.load(this@IspeechImeService)
                    if (auth == null) {
                        if (seq == aiRequestSeq) {
                            statusText = "未登录：请先在设置页登录 csBaby"
                            candidates = emptyList()
                            renderCandidateView()
                        }
                        return@launch
                    }
                    AppContainer.csBabyClient(this@IspeechImeService)
                        .generateReply(auth, message)
                        .reply
                }
                if (seq != aiRequestSeq) return@launch
                withContext(Dispatchers.IO) { aiLearning.recordGenerated() }
                aiSourceText = message
                aiSourceLen = message.length
                aiReplyActive = true
                aiReplyFromModel = true
                statusText = null
                codeOverride = "AI回复"
                allCandidates = emptyList()
                pageCount = 1
                pageIndex = 0
                candidates = listOf(
                    LookupCandidate(
                        code = "ai",
                        display = preview(reply),
                        insertText = reply,
                        weight = 0,
                        kind = LookupKind.QUICKAPPLY,
                    ),
                )
                renderCandidateView()
            } catch (e: Exception) {
                if (seq == aiRequestSeq) {
                    statusText = "AI失败：" + shortText(e.message ?: "未知错误", 60)
                    candidates = emptyList()
                    renderCandidateView()
                }
            } finally {
                aiBusy = false
            }
        }
    }

    /**
     * 在快捷短语里找与 [message] 相关的条目（内置 + 远端 + 用户）。
     *
     * 双向包含：短语正文出现在用户输入里（输入提到「发票」→ 发票类短语），
     * 或用户输入是短语正文的一段（话没说完也先给出整句）。
     * 单字正文（如「好」）几乎能命中任何句子，会造成噪声，直接排除。
     *
     * 读规则文件是 IO 活，必须离开主线程：本方法自带 [Dispatchers.IO]。
     */
    private suspend fun matchQuickPhrases(message: String): List<LookupCandidate> =
        withContext(Dispatchers.IO) {
            val needle = message.trim().lowercase()
            if (needle.isEmpty()) return@withContext emptyList()
            val entries = runCatching {
                val enableQuick = SettingsStore.enableQuickPhrasesFlow(this@IspeechImeService).first()
                val enableRemote = SettingsStore.enableRemoteRulesFlow(this@IspeechImeService).first()
                val singleNoTrigger = SettingsStore.singleLetterNoTriggerFlow(this@IspeechImeService).first()
                QuickLocator.loadAll(
                    this@IspeechImeService,
                    includeLocal = enableQuick,
                    includeRemote = enableRemote,
                    includeUser = true,
                    minTriggerLength = if (singleNoTrigger) 2 else 1,
                )
            }.getOrElse { emptyList() }
            if (entries.isEmpty()) return@withContext emptyList()
            entries.asSequence()
                .filter { it.content.length >= 2 }
                .filter { entry ->
                    val content = entry.content.lowercase()
                    val label = entry.label.lowercase()
                    content.contains(needle) || needle.contains(content) ||
                        label.contains(needle) || needle.contains(label)
                }
                .sortedByDescending { it.weight }
                .distinctBy { it.content }
                .take(QUICK_REPLY_LIMIT)
                .map { entry ->
                    LookupCandidate(
                        code = entry.trigger,
                        display = preview(entry.content),
                        insertText = entry.content,
                        weight = entry.weight,
                        kind = LookupKind.QUICKAPPLY,
                    )
                }
                .toList()
        }

    /** 上屏 AI 回复（或第 [index] 条快捷短语候选）；若输入框仍是生成前原文，先删除原文（替换语义）。 */
    private fun commitAiReply(index: Int = 0) {
        val candidate = candidates.getOrNull(index) ?: return
        val ic = currentInputConnection
        if (ic != null && aiSourceLen > 0) {
            val before = ic.getTextBeforeCursor(aiSourceLen, 0)?.toString().orEmpty()
            if (before == aiSourceText) {
                ic.deleteSurroundingText(aiSourceLen, 0)
            }
        }
        ic?.commitText(candidate.insertText, 1)
        lastCommit = candidate.insertText
        // 用户采纳了模型建议：本地记一条「输入 → 采纳的回复」，下次作为 few-shot
        if (aiReplyFromModel) {
            val source = aiSourceText
            val reply = candidate.insertText
            serviceScope.launch(Dispatchers.IO) {
                runCatching { aiLearning.recordAccepted(source, reply) }
            }
        }
        dismissAiState()
        buffer = ""
        candidates = emptyList()
        renderCandidateView()
    }

    /** 清空 AI 提示 / 候选；有请求进行中时使旧结果失效。 */
    private fun dismissAiState() {
        if (aiBusy) {
            aiRequestSeq++
        }
        statusText = null
        if (codeOverride == "AI回复" || codeOverride == "快捷短语" || codeOverride == "常用回复") {
            codeOverride = null
        }
        if (aiReplyActive) {
            candidates = emptyList()
        }
        aiReplyActive = false
        aiReplyFromModel = false
        aiSourceText = ""
        aiSourceLen = 0
    }

    // ──────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────

    /** 发送按键给宿主应用（Tab / 方向键） */
    private fun sendKey(keyCode: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** 触发编辑区上下文菜单动作（全选 / 复制 / 粘贴） */
    private fun contextAction(actionId: Int) {
        runCatching { currentInputConnection?.performContextMenuAction(actionId) }
    }

    /**
     * 设置页改过拼音混输 / 模糊音 / 词库后会 [AppContainer.invalidateEngine]，
     * 这里比对代次重取引擎——运行中的输入法服务不会自动看到新设置，
     * 不重取就会出现「改了开关但键盘还是老行为」。
     */
    private fun refreshEngineIfStale() {
        val generation = AppContainer.engineGeneration()
        if (generation == engineGeneration) return
        engineGeneration = generation
        serviceScope.launch {
            val latest = withContext(Dispatchers.Default) { AppContainer.compositeEngine(this@IspeechImeService) }
            // 期间又改了一次设置：以最新代次为准，本次结果丢弃
            if (generation != AppContainer.engineGeneration()) return@launch
            engine = latest
            if (buffer.isNotEmpty()) refreshCandidates()
        }
    }

    // ──────────────────────────────────────────────
    //  字形过滤（豆腐块候选）
    // ──────────────────────────────────────────────

    /** 判断字体能否渲染候选文本（hasGlyph 要求 API 23，minSdk 26 安全） */
    private val glyphPaint by lazy { Paint() }

    /** hasGlyph 每次都要查字体，缓存结果；超过上限整体清空，避免长会话无界增长 */
    private val glyphCache = HashMap<String, Boolean>()

    private fun renderable(text: String): Boolean {
        if (text.isEmpty()) return true
        val cached = glyphCache[text]
        if (cached != null) return cached
        if (glyphCache.size > GLYPH_CACHE_LIMIT) glyphCache.clear()
        // 逐码点查：hasGlyph(String) 对多字符串的语义是「整串压成一个字形」，
        // 直接传「仰泳」这类词会误判成不可渲染（实测会被吃掉），必须拆开查。
        val ok = runCatching {
            var index = 0
            var renderable = true
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                if (!glyphPaint.hasGlyph(String(Character.toChars(codePoint)))) {
                    renderable = false
                    break
                }
                index += Character.charCount(codePoint)
            }
            renderable
        }.getOrDefault(true)
        glyphCache[text] = ok
        return ok
    }

    /**
     * 剔除本机字体渲染不了的候选。
     *
     * 真实词库里有非 BMP 生僻字（如 `wqiy` 的 `𠉎`），字体缺字形时候选条会显示豆腐块，
     * 用户点它只会得到乱码，所以展示前直接过滤掉。
     */
    private fun List<LookupCandidate>.withoutTofu(): List<LookupCandidate> =
        if (isEmpty()) this else filter { renderable(it.insertText) }

    private fun resetState() {
        buffer = ""
        t9Digits = ""
        t9Seq++
        candidates = emptyList()
        allCandidates = emptyList()
        pageIndex = 0
        pageCount = 1
        associationActive = false
        lastCommit = ""
        panelKeyword = ""
        panel = Panel.NONE
        softKeyboard?.resetShift()
        dismissAiState()
        if (voiceController?.isListening == true) voiceController?.stop()
        if (funasrVoice?.isListening == true) funasrVoice?.cancel()
        if (voskVoice?.isListening == true) voskVoice?.cancel()
        voiceActive = false
        if (codeOverride == "语音") codeOverride = null
        punctor.reset()
        updatePanelVisibility()
        renderCandidateView()
    }

    /** 状态文本超长截断，避免候选条被撑爆 */
    private fun shortText(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    /** AI 回复预览：换行转空格并截断 */
    private fun preview(reply: String): String =
        shortText(reply.replace("\n", " ").trim(), 40)

    /** 齿轮 / 候选条设置入口：先在输入法内打开配置面板（搜狗风格，不必离开键盘） */
    private fun openSettings() {
        openPanel(Panel.SETTINGS)
    }

    /** 配置面板「更多设置」：跳转完整设置页（输入法没有自己的任务栈，需要 NEW_TASK 标志） */
    private fun launchSettingsActivity() {
        val intent = Intent().apply {
            setClassName(this@IspeechImeService, SETTINGS_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "未找到设置页 $SETTINGS_ACTIVITY", e)
        }
    }

    /** 配置面板布尔开关落盘；影响引擎的开关（拼音混输 / 模糊音）顺带重建引擎。 */
    private fun applySettingsToggle(key: String, value: Boolean) {
        serviceScope.launch {
            when (key) {
                "chinesePunctuation" -> SettingsStore.setChinesePunctuation(this@IspeechImeService, value)
                "fullWidth" -> SettingsStore.setFullWidth(this@IspeechImeService, value)
                "association" -> SettingsStore.setAssociation(this@IspeechImeService, value)
                "learning" -> SettingsStore.setLearning(this@IspeechImeService, value)
                "pinyinMixed" -> SettingsStore.setPinyinMixed(this@IspeechImeService, value)
                "fuzzyPinyin" -> SettingsStore.setFuzzyPinyin(this@IspeechImeService, value)
                "traditionalOutput" -> SettingsStore.setTraditionalOutput(this@IspeechImeService, value)
                "vibrate" -> SettingsStore.setVibrate(this@IspeechImeService, value)
            }
            if (key == "pinyinMixed" || key == "fuzzyPinyin") {
                AppContainer.invalidateEngine()
            }
        }
    }

    /** 配置面板语音识别设置落盘（引擎 / 语言）。 */
    private fun applyAsrChange(next: AsrSettings) {
        serviceScope.launch { SettingsStore.setAsrSettings(this@IspeechImeService, next) }
    }

    /** 配置面板切换中文输入法落盘；输入态由 chineseInputMethodFlow 统一驱动。 */
    private fun applyChineseInputMethodChange(next: ChineseInputMethod) {
        serviceScope.launch { SettingsStore.setChineseInputMethod(this@IspeechImeService, next) }
    }
}
