package com.ispeech.settings.store

/** 语音识别（ASR）引擎：系统内置识别器，或设备上已安装的其它识别服务。 */
enum class AsrEngine {
    /** Android 系统语音识别（[android.speech.SpeechRecognizer] 默认实现）。 */
    SYSTEM,

    /**
     * 指定设备上某个已安装的 [android.speech.RecognitionService] 组件。
     *
     * 走 [android.speech.SpeechRecognizer.createSpeechRecognizer] 的 ComponentName 重载，
     * 由该服务自行决定使用的声学 / 语言模型；模型名经自定义 extra 透传。
     */
    CUSTOM,

    /**
     * FunASR 离线识别：基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 运行时，
     * 使用 [FunASR](https://github.com/alibaba-damo-academy/FunASR) 训练并导出的 Paraformer-zh-small 模型，
     * 完全离线、不联网、中文准确率更高。
     *
     * 不经过任何系统 [android.speech.RecognitionService]：模型（约 74MB）首次使用时下载到
     * `filesDir`，之后本机识别，无网也能用。此时「识别语言」设置不生效（模型是普通话）。
     */
    FUNASR;

    companion object {
        /** 解析持久化值，未存过或无法识别时回落 [FUNASR]（内置离线识别的默认引擎）。 */
        fun parse(raw: String?): AsrEngine =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FUNASR
    }
}

/** 可选识别语言，tag 即 [android.speech.RecognizerIntent.EXTRA_LANGUAGE] 取值。 */
enum class AsrLanguage(val tag: String, val label: String) {
    ZH_CN("zh-CN", "普通话"),
    ZH_TW("zh-TW", "繁体中文"),
    YUE("yue-Hant-HK", "粤语"),
    EN_US("en-US", "English"),
    JA_JP("ja-JP", "日本語");

    companion object {
        /** 按 tag 反查，未命中回落 [ZH_CN]。 */
        fun parse(raw: String?): AsrLanguage =
            entries.firstOrNull { it.tag == raw } ?: ZH_CN
    }
}

/**
 * 语音识别设置（DataStore 持久化，默认值即此处默认值）。
 *
 * @param serviceComponent [AsrEngine.CUSTOM] 时使用的识别服务，格式为 ComponentName 扁平串
 *   （"包名/类名"），为空时识别器回退到系统默认服务。
 * @param model 模型名 / 变体，经 [ASR_MODEL_EXTRA] 透传给识别服务；不支持的服务会忽略。
 */
data class AsrSettings(
    /** 默认走内置 FunASR 离线识别：中文识别开箱即用，不依赖设备是否预置可用的系统识别服务。 */
    val engine: AsrEngine = AsrEngine.FUNASR,
    val serviceComponent: String = "",
    val model: String = "",
    val language: AsrLanguage = AsrLanguage.ZH_CN,
    /** 实时回传中间结果（边说边出候选）。 */
    val partialResults: Boolean = true,
    /** 优先离线识别（设备支持时不出网）。 */
    val preferOffline: Boolean = false,
) {
    companion object {
        /**
         * 模型名透传 extra 键。
         *
         * 非 Android 标准常量：标准 [android.speech.RecognizerIntent] 没有「模型」extra，
         * 这里用应用自有命名空间，仅当识别服务主动读取该键时才生效。
         */
        const val ASR_MODEL_EXTRA = "com.ispeech.extra.ASR_MODEL"
    }
}
